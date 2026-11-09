package com.flyai.adalert.serp

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 검색 결과 배지의 화면 추적 담당. 스크롤 문제의 답이 이 파일.
 *
 * ## 설계의 핵심 — 판정과 좌표의 분리
 * 처음 구현은 둘을 한 덩어리로 보유. `evaluate()`가 "이 사각형에 이 판정"을 함께
 * 반환하고, 화면이 움직이면 그 쌍이 통째로 무효. 실기기에서 드러난 두 결함의
 * **원인이 동일**.
 *
 *  - 스크롤하면 배지 소실 → 좌표가 낡아 판정까지 폐기
 *  - 판별기 응답 100% 폐기 → 돌아왔을 때 좌표가 이미 변경
 *
 * 그래서 둘을 분리.
 *
 * ```
 *   판정 = 호스트에 부착 (tvhot2.com → 위험)     — SerpRiskEngine 캐시. 화면과 무관
 *   좌표 = 화면에서 매번 새로 읽음                — 이 클래스의 anchors
 *   그리기 = anchors를 돌면서 호스트로 판정 조회  — redraw()
 * ```
 *
 * 결과: 두 결함이 **고쳐지는 게 아니라 존재 불가**. 스크롤 시 좌표만 새로 읽어 다시
 * 그리고(판정은 그대로), 판별기 응답이 5초 뒤에 와도 호스트에 얹으면 그 시점의 좌표에
 * 저절로 부착. 화면 세대 비교 불필요 — 늦게 온 응답을 버릴 이유 자체가 소멸.
 *
 * ## 두 속도로 동작
 *  - **위치 갱신**: 이벤트마다, [SCAN_INTERVAL_MS] 스로틀. 트리만 순회, 판별기 호출 없음
 *  - **판정 갱신**: 화면이 [IDLE_MS] 조용해진 뒤 한 번. 여기서만 규칙·캐시·판별기 동작
 *
 * 스크롤 중에도 위치 갱신은 계속 돌아 배지가 손가락을 추종. 그 사이 판별기 호출은
 * 없으므로 비용 증가 없음.
 *
 * ## 요청 폐기 없음
 * 스로틀 구간·스캔 진행 중에 들어온 요청은 [trailingScan]으로 연기. 버리면 드래그의
 * 마지막 위치 유실 — 손을 뗀 자리에서 배지가 어긋난 채로 정지.
 * (`BorderTracker`가 광고 테두리에서 같은 문제를 같은 방식으로 해결)
 */
class SerpTracker(
    /** 관문 창 부착용. **서비스 컨텍스트 필수** ([SerpFeature] 참고) */
    context: android.content.Context,
    private val scanner: SerpScanner,
    private val engine: SerpRiskEngine,
    private val overlay: SerpBadgeOverlay,
    private val scope: CoroutineScope,
    /**
     * 어르신이 위험한 검색 결과를 **눌렀을 때** 호출. 그 순간을 아는 것은
     * 관문([SerpGuard])이므로 이 클래스는 전달만 한다.
     *
     * 판정이 내려진 것만으로는 호출하지 않는다 — 지나친 위험까지 올리면 보호자 폰이
     * 하루에도 여러 번 울려 결국 알림이 꺼진다.
     *
     * 콜백에 호스트는 싣지 않는다. 같은 결과의 반복 탭은 [ALERT_REPEAT_MS] 동안
     * 억제 — [onGuardAlert] 참고
     */
    private val onRiskTapped: () -> Unit = {}
) {

    companion object {
        /**
         * **화면에 그릴 등급.** 이 한 곳이 등급 필터의 전부.
         *
         * 빨강([RiskGrade.HIGH])만 표시. 초록(안전)·주황(주의)은 표시 없음 —
         * 결과 열 칸에 전부 뭔가 붙으면 가장 위험한 한 칸이 그 속에 매몰.
         *
         * ## 주황을 뺀 이유
         * 한때 주황도 그렸다. 실기기에서 "위험한 곳이 둘인데 하나만 잡는다"로 보였기
         * 때문인데, 그 대가로 **어르신이 색을 판단해야 하는 화면**이 됐다. 빨강은
         * 들어가면 안 되는 곳, 주황은 조심해서 들어가도 되는 곳 — 그 구분을 어르신에게
         * 맡기는 것이 이 앱이 하려던 일과 반대였다. 위험하면 빨강 하나로 말하고,
         * 그 정도가 아니면 아무 말도 하지 않는다.
         *
         * 판정 자체는 그대로다. 주황 등급([RiskGrade.MEDIUM])은 여전히 매겨지고 로그에도
         * 남는다 — 표시하지 않을 뿐이다. 임계값을 조정할 때 근거가 필요하기 때문.
         *
         * 초록을 끄는 이유는 다르다 — **"표시 없음"이 안전인지 미확인인지 구분 불가**.
         * 원래 이 기능은 그 둘을 색으로 분리 ([RiskGrade.UNKNOWN] 주석 참고). 그래서
         * 필터를 분기 안에 흩뿌리지 않고 여기 하나로 집중
         */
        val SHOWN_GRADES = setOf(RiskGrade.HIGH)

        /**
         * 결과가 안 보여도 배지를 붙잡는 시간.
         *
         * [EMPTY_TOLERANCE]와 함께 적용 — 횟수만으로는 스캔이 느려진 순간 실제 유지
         * 시간이 들쭉날쭉. 값은 광고 테두리 쪽 히스테리시스(`AdDetectService.CLEAR_DELAY_MS`)와
         * 동일하게 설정. 같은 화면에서 두 표시가 서로 다른 속도로 사라지면 어느 쪽이
         * 무엇을 말하는지 식별 불가.
         * (그 상수를 import하지 않는 이유: 이 패키지가 자기 밖을 모르게 두기 위함.
         *  저쪽이 바뀌면 여기도 수동으로 맞출 것)
         */
        const val CLEAR_DELAY_MS = 700L

        /**
         * 같은 결과로 보호자 알림을 다시 올리기까지의 최소 간격.
         *
         * 관문 시트를 닫고 같은 칸을 또 누르는 일은 실제로 잦다("왜 안 열리지"). 그
         * 반복을 그대로 올리면 보호자 폰에 같은 줄만 쌓인다. 반대로 영구 억제도 답이
         * 아니다 — 내일 같은 곳을 또 누른다면 그건 알아야 할 일.
         *
         * 값은 spec.md Part 03의 검색 결과 억제 창(1분)과 같게 둔다. 받는 쪽([Family])도
         * 같은 창을 갖고 있지만 그쪽은 호스트를 모르므로, 호스트별 판단은 여기서 한다
         */
        const val ALERT_REPEAT_MS = 60 * 1000L

        /**
         * 위치 갱신 최소 간격. 크롬은 스크롤 중 약 100ms마다 이벤트 전송 → 그보다 조금
         * 길게 잡아 한 번씩 건너뜀. 트리 순회가 이보다 오래 걸리면 어차피 [scanning]
         * 플래그가 차단
         */
        const val SCAN_INTERVAL_MS = 150L

        /**
         * 판정 갱신 기준 유휴 시간. 스크롤 중에 판별기를 부르면 응답이 올 때쯤 그 결과는
         * 화면 밖.
         *
         * 원래 700ms였으나 실사용에서 "배지가 늦게 뜬다" 리포트 발생. 이 대기는 판정
         * **시작**을 미루는 값이라 판별기 왕복 시간에 그대로 가산 — 첫 배지까지의 체감이
         * 그만큼 길어짐. 크롬 스크롤 이벤트 간격(약 100ms)보다 넉넉히 길기만 하면
         * "스크롤 정지" 판단에는 충분
         */
        const val IDLE_MS = 300L

        /**
         * 배지 삭제에 필요한 연속 빈 스캔 횟수.
         *
         * 한 번이라도 비면 지우게 하면 배지 깜빡임 — 크롬은 스크롤·지연 로딩 도중 트리를
         * 잠깐 비우고, 순회가 예산에 걸려 잘리기도 함. 그때마다 지우면 **정작 위험한
         * 결과 위에서 경고 소실**
         */
        const val EMPTY_TOLERANCE = 3

        /** 위치 갱신에서 순회할 노드 수 상한. 판정 갱신은 넉넉히 */
        const val TRACK_NODE_BUDGET = 900
        const val JUDGE_NODE_BUDGET = 1800

        /**
         * 배지 표시 중 자체 재확인 주기.
         *
         * **이벤트만 믿으면 배지 삭제 불가.** 서비스는 `packageNames`에 적힌 앱의 이벤트만
         * 받는데, 검색 화면을 떠나 가는 곳(런처·잠금화면·다른 앱)은 그 목록에 없음.
         * 그래서 크롬을 닫거나 화면을 껐다 켜면 **지우라고 알려줄 이벤트가 아예 없음** —
         * 실기기에서 잠금화면 위에 테두리 잔존.
         *
         * 이벤트가 끊겨도 주기적으로 화면을 직접 확인, 검색 결과가 아니면 삭제.
         * 배지가 떠 있을 때만 도는 타이머라 평소에는 동작 없음.
         * (`BorderTracker`가 광고 테두리에서 같은 이유로 같은 장치 사용)
         */
        const val RECHECK_MS = 1000L
    }

    /** 루트 노드 취득 방법. 대상 앱이 아니면 null 반환 약속 */
    private var rootProvider: () -> AccessibilityNodeInfo? = { null }

    private val handler = Handler(Looper.getMainLooper())

    /**
     * 스캔 진행 중 플래그. 트리 순회는 수십~수백 ms 소요, 이벤트는 그보다 빨리 도착.
     * **겹친 요청 폐기 없음** — [trailingScan]이 재예약
     */
    private val scanning = AtomicBoolean(false)

    // ── 메인 스레드에서만 만지는 상태 ──

    private var lastScan = 0L
    private var scanQueued = false

    /** 다음 스캔에서 판정까지 갱신할지. 유휴 타이머가 설정 */
    private var judgePending = false

    /**
     * 지금 도는 스캔 시작 이후 화면이 세로로 구른 총량.
     *
     * 스캔 결과는 항상 몇십~몇백 ms 전의 화면. 그 사이 사용자가 계속 스크롤했다면 결과를
     * 그대로 그리는 순간 배지가 **뒤로 튐** — 손가락으로 밀어둔 위치를 스캔이 도로
     * 끌어내리는 셈. 결과를 그릴 때 이만큼 되밀어 그 튐을 제거
     */
    private var scrollSinceScanStart = 0

    /** 지금 화면에 있는 결과들의 자리. 스캔마다 통째로 교체 */
    private var anchors: List<SerpScanner.Hit> = emptyList()

    /** 결과를 못 찾은 스캔의 연속 횟수 */
    private var emptyScans = 0

    /** 결과가 처음 비어 보인 시각. [CLEAR_DELAY_MS] 측정 기준. 0이면 비지 않은 상태 */
    private var emptySince = 0L

    /** 빨강 결과의 첫 탭을 대신 받아 설명을 띄우는 관문 */
    private val guard = SerpGuard(context, ::onGuardAlert)

    /** 마지막으로 읽은 검색어. 판별기에 문맥으로 전달 */
    private var query = ""

    /**
     * 지금 화면이 검색 결과 화면인지. 서비스가 도메인 대조(UrlGuard) 생략 여부를 정할 때
     * 참조 — 검색 결과 위에서는 이 기능의 배지가 이미 같은 위험을 더 정밀하게(결과 칸
     * 단위로) 안내 중
     */
    @Volatile
    var isSerpScreen: Boolean = false
        private set

    /** 호스트별 마지막 알림 시각. [ALERT_REPEAT_MS] 안의 재발은 올리지 않는다 */
    private val alertedAt = mutableMapOf<String, Long>()

    /**
     * 관문이 알려 온 탭 하나.
     *
     * 호스트는 **여기까지만** 온다 — 밖으로 나가는 것은 "눌렸다"는 사실뿐. 호스트를
     * 받는 이유는 억제 판단 하나이고, 같은 화면의 다른 위험 결과는 별개 사건이므로
     * 각자 한 번씩 올라간다.
     */
    private fun onGuardAlert(host: String) {
        val now = SystemClock.uptimeMillis()
        val last = alertedAt[host]
        if (last != null && now - last < ALERT_REPEAT_MS) {
            Log.i(SERP_TAG, "serp 보호자 알림 억제 — 같은 결과 재탭")
            return
        }
        alertedAt[host] = now
        Log.i(SERP_TAG, "serp 보호자 알림 — 위험 결과 탭")
        onRiskTapped()
    }

    /**
     * 이번 화면에서 실제로 내려진 판정. **[SerpRiskEngine]의 캐시와 일부러 다름.**
     *
     * 그리기는 원래 `engine.known(host)` 하나만 참조했는데, 그 함수는 **캐시에 저장된**
     * 판정만 반환. 그런데 엔진은 판별기(AI)가 답하지 못한 호스트의 판정을 일부러 저장하지
     * 않음 — 규칙 판정을 캐시에 남기면 TTL(7일) 동안 그 도메인이 판별기 눈에 다시 띄지
     * 않기 때문. 그 선택 자체는 옳음.
     *
     * 문제는 저장하지 않은 판정이 **화면에도 미표시**였다는 것. 그래서 한 검색 결과에
     * 위험 도메인이 여럿 있어도, 규칙만으로 확정되는 것(불법 다시보기 이름 등) 하나만
     * 빨간 테두리가 붙고 나머지는 판정이 나와 있는데도 표시 없음 — "위험 도메인이 여러
     * 개인데 하나만 잡는다"는 실기기 지적의 정체.
     *
     * 그래서 **보여주기용 판정**을 화면 단위로 별도 보유. 캐시는 그대로이므로 판별기가
     * 다음 기회에 그 도메인을 다시 볼 권리도 그대로. 화면을 떠나면 함께 폐기
     */
    private var screenVerdicts: Map<String, SerpVerdict> = emptyMap()

    /**
     * 이벤트 하나 수신. 메인 스레드.
     *
     * @param root 루트 노드를 주는 함수. **대상 앱이 아니면 null 반환 필수**
     */
    fun onEvent(event: AccessibilityEvent, root: () -> AccessibilityNodeInfo?) {
        rootProvider = root

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // 다른 페이지로 전환. 이전 화면의 자리는 즉시 폐기 — 남겨두면 새 페이지 위에
            // 옛 배지가 뜬 채로 700ms 경과.
            // **판정 캐시는 유지.** 호스트에 붙은 판정은 페이지가 바뀌어도 그대로 유효,
            // 뒤로 가기로 돌아오면 곧바로 재사용
            anchors = emptyList()
            screenVerdicts = emptyMap()
            engine.reset()
            overlay.clear()
            guard.clear()
        }

        requestScan()
        scheduleJudge()
    }

    /**
     * 화면이 [deltaY]만큼 구른 경우. **노드 읽기 없음.**
     *
     * 화면이 위로 굴렀으면(deltaY > 0) 결과도 위로 가므로 배지는 -deltaY만큼 이동.
     * 다음 스캔 도착 전까지의 임시 보정 — 정확한 자리는 스캔이 결정
     */
    fun onScroll(deltaY: Int) {
        if (deltaY == 0) return
        overlay.offsetBy(-deltaY)
        guard.offsetBy(-deltaY)
        anchors = anchors.map { it.copy(rect = Rect(it.rect).apply { offset(0, -deltaY) }) }
        scrollSinceScanStart += deltaY
    }

    /** 표시 전부 삭제. 서비스 종료·인터럽트 시 */
    fun clear() {
        handler.removeCallbacksAndMessages(null)
        anchors = emptyList()
        screenVerdicts = emptyMap()
        isSerpScreen = false
        overlay.clear()
        // 여기만 시트까지 제거 — 화면 꺼짐·서비스 종료·쉴드가 덮는 순간.
        // 스캔 중의 정리(dropAll·빈 스캔)는 관문 창만 제거, 시트는 유지
        guard.dismissAll()
    }

    // ── 스캔 예약 ────────────────────────────────────────────

    private val trailingScan = Runnable {
        scanQueued = false
        postScan()
    }

    private val judgeTimer = Runnable {
        judgePending = true
        requestScan()
    }

    /**
     * 이벤트가 끊겨도 배지 유효성 자체 확인.
     *
     * 판별기 호출 없음(판정 갱신이 아니라 위치·유효성 확인). 검색 화면을 벗어났으면
     * [runScan]의 첫 두 줄에서 [dropAll]로 이탈
     */
    private val recheck = Runnable { requestScan() }

    private fun scheduleJudge() {
        handler.removeCallbacks(judgeTimer)
        handler.postDelayed(judgeTimer, IDLE_MS)
    }

    /** 메인 스레드. 스로틀만 담당, 실제 순회는 코루틴에 위임 */
    private fun requestScan() {
        val now = SystemClock.uptimeMillis()
        val since = now - lastScan
        if (since >= SCAN_INTERVAL_MS) {
            handler.removeCallbacks(trailingScan)
            scanQueued = false
            postScan()
        } else if (!scanQueued) {
            scanQueued = true
            handler.postDelayed(trailingScan, SCAN_INTERVAL_MS - since)
        }
    }

    private fun postScan() {
        if (scanning.get()) {
            // 이전 스캔 진행 중. 여기서 버리면 드래그의 마지막 위치가 유실되므로
            // 끝난 직후 재실행되도록 예약만 유지
            if (!scanQueued) {
                scanQueued = true
                handler.postDelayed(trailingScan, SCAN_INTERVAL_MS)
            }
            return
        }

        val judge = judgePending
        judgePending = false
        lastScan = SystemClock.uptimeMillis()
        scrollSinceScanStart = 0
        scanning.set(true)
        scope.launch { runScan(judge) }
    }

    // ── 스캔 ────────────────────────────────────────────────

    private suspend fun runScan(judge: Boolean) {
        try {
            val root = rootProvider() ?: return dropAll()
            val pkg = root.packageName?.toString().orEmpty()
            val pageUrl = runCatching { scanner.pageUrlOf(root) }.getOrNull()

            // 검색어는 주소에서, 주소가 없으면(구글 앱) 검색창에서 읽음.
            // 구글 앱에서는 이 값이 곧 ① 관문의 근거
            val typed = scanner.queryOf(pageUrl)
                .ifBlank { runCatching { scanner.queryFromSearchBox(root) }.getOrDefault("") }

            // ① 화면 관문 — 검색 결과가 아니면 여기서 종료. 트리 순회 없음.
            // 크롬은 주소로, 구글 앱은 검색창에 글자가 있는지로 판단
            if (!scanner.isSearchScreen(pageUrl, pkg, hasQuery = typed.isNotBlank())) {
                return dropAll()
            }
            isSerpScreen = true

            query = typed.ifBlank { query }

            val budget = if (judge) JUDGE_NODE_BUDGET else TRACK_NODE_BUDGET
            val hits = runCatching { scanner.extract(root, budget) }.getOrDefault(emptyList())

            if (hits.isEmpty()) {
                withContext(Dispatchers.Main) {
                    // 히스테리시스 — 한 번 비었다고 지우면 배지 깜빡임.
                    // 횟수와 시간을 **둘 다** 채워야 삭제. 판정이 그대로인데
                    // 트리가 한 프레임 비었다고 경고가 사라지면 안 됨
                    val now = SystemClock.uptimeMillis()
                    if (emptySince == 0L) emptySince = now
                    if (++emptyScans >= EMPTY_TOLERANCE && now - emptySince >= CLEAR_DELAY_MS) {
                        handler.removeCallbacks(recheck)
                        emptySince = 0L
                        anchors = emptyList()
                        overlay.clear()
                        guard.clear()
                    } else {
                        // 아직 붙잡는 중이라도 재확인 약속은 유지
                        handler.removeCallbacks(recheck)
                        handler.postDelayed(recheck, RECHECK_MS)
                    }
                }
                return
            }

            withContext(Dispatchers.Main) {
                emptyScans = 0
                emptySince = 0L
                // 스캔 중 더 구른 만큼 되밀어 배지 뒤로 튐 방지
                anchors = hits.shiftedBy(-scrollSinceScanStart)
                redraw()
            }

            if (!judge) return

            // ②③④ 관문 — 여기서만 규칙·캐시·판별기 동작
            val outcome = engine.evaluate(query, hits.map { it.result })
            Log.i(
                SERP_TAG,
                "serp 검색어='$query' 결과=${hits.size} 판별=${outcome.classifiedHosts.size} " +
                    "생략=${outcome.skippedUnchanged} " +
                    outcome.verdicts.filter { it.grade.isShown }
                        .groupingBy { it.grade.grade }.eachCount()
            )

            // 보호자 알림은 여기서 올리지 않는다. 판정이 내려졌다는 것은 "화면에 위험한
            // 결과가 떠 있다"는 뜻일 뿐이고, 어르신은 대개 그것을 누르지 않고 지나간다 —
            // 그때마다 올리면 스크롤 한 번에 보호자 폰이 울린다. 실측: 위험 결과 3개짜리
            // 검색 한 번에 보호자 기록 4줄.
            // 올릴 사실은 "눌렀다" 쪽이고, 그 순간은 관문이 안다 ([onGuardAlert])

            // 판정만 갱신, 좌표는 불변. 응답이 늦게 왔더라도 그 사이 스캔이 갱신해 둔
            // **지금의** anchors 위에 얹힘 — 버릴 이유 없음.
            // 이번 회차에 내려진 판정을 호스트별로 보관. 엔진 캐시에 저장되지 않는
            // 판정(판별기가 답하지 못한 호스트)도 여기에는 포함되므로, 위험 도메인이
            // 여럿인 화면에서 전부 표시. [screenVerdicts] 참고
            val judged = outcome.results.asSequence()
                .zip(outcome.verdicts.asSequence())
                .filter { (_, v) -> v.grade.isShown }
                .associate { (r, v) -> r.host to v }

            withContext(Dispatchers.Main) {
                screenVerdicts = judged
                redraw()
            }
        } finally {
            scanning.set(false)
        }
    }

    /**
     * 검색 결과 화면 아님 — 표시 전부 제거.
     *
     * 조건 없이 삭제. "anchors가 비어 있으면 건너뜀"으로 두면, 스크롤로 anchors만 비워진
     * 채 창이 남아 있는 순간에 삭제 불가. [SerpBadgeOverlay.clear]는 이미 비어 있으면
     * 아무 동작 없음 → 반복 호출 비용 낮음
     */
    private suspend fun dropAll() = withContext(Dispatchers.Main) {
        handler.removeCallbacks(recheck)
        emptySince = 0L
        anchors = emptyList()
        screenVerdicts = emptyMap()
        isSerpScreen = false
        overlay.clear()
        guard.clear()
        engine.reset()
    }

    /**
     * 지금의 자리에 지금 아는 판정을 적용. **그리기는 항상 이 함수 하나를 경유.**
     *
     * 아직 판정이 없는 결과([RiskGrade.UNKNOWN] 포함)는 표시 없음 — 모르는 것에 초록을
     * 칠하지 않기 위함. 판별기가 답하면 다음 [redraw]에서 저절로 표시. 그리는 등급은
     * [SHOWN_GRADES]가 결정
     */
    private fun redraw() {
        val hot = anchors.mapNotNull { hit ->
            // 캐시된 판정 우선(재방문에서도 같은 답 보장). 캐시에 없으면 이번 화면에서
            // 내려진 판정 사용 — 이 폴백이 없으면 판별기가 답하지 못한 위험 도메인이
            // 판정을 갖고도 미표시
            val verdict = engine.known(hit.result.host) ?: screenVerdicts[hit.result.host]
            verdict?.takeIf { it.grade in SHOWN_GRADES }
                ?.let { Triple(hit.result.host, hit.rect, it) }
        }
        val marks = hot.map { (_, rect, verdict) -> rect to verdict }
        if (marks.isEmpty()) overlay.clear() else overlay.show(marks)

        // 관문은 표시되는 등급 **전부**에 적용 — 지금은 빨강 하나뿐이다. 한 번 설명을
        // 본 결과를 기억해 빼 두지 않는다. 그러면 테두리는 그려져 있는데 아무것도 막지
        // 않는 칸이 생겨, 보호받고 있다고 믿는 사람을 보호하지 않게 된다
        guard.update(hot.filter { (_, _, verdict) -> verdict.grade in SHOWN_GRADES })

        // 무언가 그렸으면 자체 재확인 예약. 화면을 떠나는 순간에는 이벤트가 오지
        // 않으므로 이 타이머가 유일한 지우개
        handler.removeCallbacks(recheck)
        if (marks.isNotEmpty()) handler.postDelayed(recheck, RECHECK_MS)
    }

    private fun List<SerpScanner.Hit>.shiftedBy(dy: Int): List<SerpScanner.Hit> =
        if (dy == 0) this
        else map { it.copy(rect = Rect(it.rect).apply { offset(0, dy) }) }
}
