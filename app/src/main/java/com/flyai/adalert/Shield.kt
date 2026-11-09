package com.flyai.adalert

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * 광고가 데려간 페이지 위에 덮는 창.
 *
 * 광고 오터치로 낯선 페이지 도착 순간, 그 페이지를 **터치 불가로 덮고** 위험 확인 시간 확보.
 * 확인 후 위험도별 선택지 제공.
 *
 * ## 다른 오버레이와 성격이 정반대
 * 광고 테두리 창은 `FLAG_NOT_TOUCHABLE` — 터치 통과. 광고 가림·클릭 방해는 정책 위반.
 * **이 창은 반대로 터치 차단.** 차단 대상이 광고가 아니라 광고가 데려간 곳 —
 * 광고는 그대로 표시·그대로 클릭, 이미 클릭된 뒤.
 *
 * ## 안전장치 — 갇힘 방지
 * 화면 전체 덮기 + 터치 차단 창이라 실수 시 사용자의 폰 사용 불가. 대책:
 *  - **"돌아가기"는 첫 프레임부터 존재.** 분석 중에도 존재. 실수 진입이면
 *    위험도 무관하게 즉시 탈출 의사 → 판정 대기 이유 없음.
 *  - 판정이 [ANALYZE_TIMEOUT_MS] 안에 없으면 [Risk.UNKNOWN]으로 전환해 선택지 표시.
 *    서버 다운 시에도 "분석 중" 영구 정지 없음.
 *  - 화면 전환 시 서비스가 [hide] 호출.
 *
 * ## 생김새는 피그마 v2(Phase 2)의 개입 아키타입 기준
 * 호스트 화면 위 딤 + 화면 가운데 먹색 밴드 하나 — 맨 위 상태색 강조선, 픽토,
 * 상태 칩, 제목 한 줄, 설명 한 줄, **판단 근거** 한 줄, 버튼 둘.
 * "확인 중"만 밴드 없이 화면 전체 어둡게 + 스피너. 색은 [Look] 기준
 */
class Shield(
    private val service: AccessibilityService,
    /** "돌아가기" 클릭 콜백 */
    private val onLeave: () -> Unit,
    /** "계속 보기" 클릭 콜백 */
    private val onStay: () -> Unit,
) {

    private companion object {
        const val TAG = "Shield"

        /** A01U 「위험한 설치 차단」 — [MeActivity]와 같은 이름을 본다 */
        const val APK_BLOCK_KEY = "apkblock"

        /**
         * 판정 대기 상한. 초과 시 [Risk.UNKNOWN]으로 전환.
         *
         * 넉넉해 보여도 사용자 대기 시간이 아니라 **갇힘 방지 상한**.
         * 실제 판정은 대개 훨씬 빠름, 캐시 적중 시 즉시.
         *
         * 12초인 이유: 판정 서버가 **에이전트**가 되면서 페이지 읽기 → 페이지 신호 → 도메인
         * 등록일처럼 도구 다회 사용 가능(서버 예산 7.5초 + 왕복). 상한이 짧으면
         * 에이전트가 답을 찾아도 폰이 먼저 포기.
         */
        const val ANALYZE_TIMEOUT_MS = 12_000L

        // 색은 [Look] 기준 — 테두리·배지와 같은 표, 화면마다 다른 "주의" 노랑 방지
        val COLOR_HIGH = Look.DANGER
        val COLOR_MEDIUM = Look.WARN

        // ── 등급별 고정 문구 (이슈 #35) ────────────────────────────────────
        /**
         * 판정마다 다른 말이 나오면 어르신이 매번 처음부터 읽고 판단해야 한다. 서버 판정은
         * **등급을 고르는 데만** 쓰고 화면 글자는 늘 같게 둔다 — 두 번째 만났을 때는 읽지
         * 않고 모양만으로 알아보게 하려는 것.
         *
         * 설명이 「…해요」가 아니라 「…할 수 있어요」인 것도 시안 그대로다(2-5·2-6).
         * 우리가 본 것은 그 페이지의 징후지 사기 그 자체가 아니다 — 단정해 놓고 아니었을 때,
         * 다음부터는 이 화면을 믿지 않게 된다.
         */
        const val WARN_TITLE = "주의가 필요한 광고예요"
        const val WARN_BODY = "돈이나 개인정보를 요구할 수 있어요"
        const val BLOCK_TITLE = "위험한 접속을 막았어요"
        const val BLOCK_BODY = "사기나 앱 설치로 이어질 수 있어요"
        const val APK_TITLE = "설치하면 안돼요"
        const val APK_BODY = "출처를 알 수 없는 설치 파일이에요"

        /** 시안 2-4 「광고 탐지 중」 — 제목 두 줄과 흰 알약 버튼 */
        const val CHECK_TITLE = "안전한지\n확인하고 있어요"
        const val CHECK_LEAVE = "안전하게 돌아가기"
        val COLOR_LOW = Look.SAFE
        val COLOR_WORKING = Look.UNKNOWN

        // 피그마에만 있는 값 (Look에 없음)
        /** 밴드 바탕 rgba(10,12,16,.95) — 2-6/2-8/2-13. 2-5는 .90이지만 한 값으로 통일 */
        val BAND = Color.argb(242, 10, 12, 16)
        /** 밴드 밖 딤. 피그마는 0이지만 밴드 위치 인지용으로 옅게 적용 */
        val SCRIM_BAND = Color.argb(89, 10, 12, 16)
        /** 2-4 "확인 중"의 전체 스크림 rgba(0,0,0,.88) */
        val SCRIM_WORKING = Color.argb(224, 0, 0, 0)
        /** 2-6 차단 밴드의 강조선·칩·주 버튼 — DANGER(#E01F26)보다 한 톤 밝음 */
        const val ACCENT_HIGH = "#FF3B3B"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val windowManager by lazy {
        service.getSystemService(WindowManager::class.java)
    }

    private var root: FrameLayout? = null
    private var card: LinearLayout? = null

    /** 현재 덮고 있는 주소. 같은 페이지 이중 표시 방지용 */
    private var shownFor: String? = null

    /**
     * 덮고 있는 페이지의 사이트. **"아직 그 페이지에 있는가"** 판단용.
     *
     * 주소 전체가 아닌 사이트로 두는 이유: 주소창 표시가 로딩 중 변동 —
     * 도메인만 보이다가 전체 주소로 채워지는 사이 "다른 페이지 이동"으로 오해 시
     * 쉴드 단독 해제
     */
    var shownSite: String? = null
        private set

    /**
     * 앱 전환으로 뜬 쉴드가 덮고 있는 앱. 웹 쉴드는 null.
     *
     * 앱 쉴드의 "벗어남" 기준은 사이트가 아니라 **앱**. 사용자가 우리 버튼이 아닌
     * 시스템 뒤로가기로 앱을 나가면 사이트 기준으로는 해제 조건 영구 미충족 →
     * 크롬 복귀 후에도 "광고를 눌러서 넘어왔습니다" 잔류 (실기기에서 발생)
     */
    var shownAppPkg: String? = null
        private set

    /** 이 쉴드를 앱 전환용으로 표시. 판정용 주소가 있어도 해제 기준은 앱 */
    fun markApp(pkg: String) {
        shownAppPkg = pkg
        shownSite = null
    }

    private val timeout = Runnable {
        Log.i(TAG, "판정이 ${ANALYZE_TIMEOUT_MS}ms 안에 안 왔다 -> UNKNOWN")
        show(Verdict(Risk.UNKNOWN, "안전한지 확인하지 못했어요.", "잘 모르겠으면 돌아가세요."))
    }

    val isShowing: Boolean get() = root != null

    /** 이 주소로 이미 덮고 있는지 여부 — 스캔이 200ms마다 돌아 같은 판정 반복 호출 방지 */
    fun isShowingFor(url: String) = shownFor == url

    // ── 상황별 화면 ───────────────────────────────────────────────────────────

    /**
     * 덮고 "확인 중" 표시. 이 순간부터 "돌아가기" 클릭 가능.
     * 판정 도착 시 [show] 호출
     */
    fun showAnalyzing(url: String) {
        shownFor = url
        shownSite = WebNode.hostOf(url)?.let { WebNode.siteOf(it) }
        // 이 줄 ~ NavGuard의 PROMPT 줄 = "감지 → 화면 표시" 시간,
        // 판정 줄까지 = 사용자 대기 전체 시간. 측정에는 셋 다 필요
        Log.i(TAG, "덮음 url=$url")
        attach()
        render(
            tone = COLOR_WORKING,
            // 시안 2-4 — 제목이 곧 설명이라 아래 설명 줄은 두지 않는다
            title = CHECK_TITLE,
            body = "",
            signalTitle = "확인 중",
            signalSub = WebNode.hostOf(url) ?: url,
            // 시안에는 흰 알약 「안전하게 돌아가기」가 있다. 판정이 최대 12초까지
            // 걸릴 수 있어(ANALYZE_TIMEOUT_MS) 그동안 나갈 길이 없으면 갇힌 화면이 된다
            primary = CHECK_LEAVE,
            secondary = null,
        )
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, ANALYZE_TIMEOUT_MS)
    }

    /**
     * 배지로 이미 "위험" 확정된 광고의 착지 덮기.
     *
     * 판정 완료 상태라 서버 재질의 없음 — "확인 중"도, 12초 상한도 없음.
     * "위험" 광고는 터치 가드가 클릭 자체를 차단하므로 원래 여기까지 도달 없음.
     * 가드 누수(빠른 스크롤 등) 대비용
     */
    fun showKnown(url: String) {
        shownFor = url
        shownSite = WebNode.hostOf(url)?.let { WebNode.siteOf(it) }
        Log.i(TAG, "덮음(기억) HIGH url=$url")
        attach()
        handler.removeCallbacks(timeout)
        render(
            tone = COLOR_HIGH,
            title = BLOCK_TITLE,
            body = BLOCK_BODY,
            signalTitle = "",
            signalSub = "",
            // 고위험은 탈출 경로만 — 위의 [show] Risk.HIGH와 같은 이유
            primary = "돌아가기",
            secondary = null,
        )
    }

    /**
     * 설치 파일(APK) 수신 화면.
     *
     * [AdDetectService.onNotification]이 브라우저 다운로드 알림에서 파일 이름을 읽어 호출.
     * [from]은 받은 곳의 **호스트**, 미상이면 null — 문장 조립은 여기서.
     * 호출 쪽에서 문장을 만들어 넘기면 "…알 수 없어요 에서 전달됨"처럼 이중 부착(실측)
     */
    fun showApkRisk(fileName: String, from: String?) {
        // 어르신이 「위험한 설치 차단」(A01U)을 끈 폰에서는 덮지 않는다. 기록은 그대로
        // 남는다 — 막지 않았다는 것과 있었던 일을 감추는 것은 다르다
        if (!service.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean(APK_BLOCK_KEY, true)
        ) {
            Log.i(TAG, "설치 파일 덮기 꺼짐 — $fileName")
            return
        }
        shownFor = "apk:$fileName"
        shownSite = null
        Log.i(TAG, "덮음(설치 파일) $fileName")
        attach()
        handler.removeCallbacks(timeout)
        render(
            tone = COLOR_HIGH,
            // 시안 2-8(1040:2)의 글자 그대로. 등급별 고정 문구와 같은 규칙 —
            // 짧고 늘 같아야 두 번째부터는 읽지 않고 모양만으로 알아본다
            title = APK_TITLE,
            body = APK_BODY,
            signalTitle = "APK 설치 · $fileName",
            signalSub = from?.let { "$it 에서 전달됨" } ?: "받은 곳을 알 수 없어요",
            primary = "돌아가기",
            secondary = null,
            chip = "차단",
        )
    }

    /** 판정 도착. 위험도별로 화면·버튼 상이 */
    fun show(verdict: Verdict) {
        if (!isShowing) return          // 그 사이 화면 전환으로 이미 걷힌 상태
        handler.removeCallbacks(timeout)
        Log.i(TAG, "판정 ${verdict.risk} (캐시=${verdict.fromCache}) url=$shownFor")

        when (verdict.risk) {
            // **안전하면 카드 해제.** 대신 화면 아래 "안전한 광고예요" 안내 바
            // 잠깐 표시 후 소멸(서비스가 표시) — 아무 일 없던 것처럼 보이면
            // 앱이 일하고 있다는 것 자체가 미전달
            Risk.LOW -> hide()

            // **고위험에는 머무는 길 없음.** 다른 등급에는 "그냥 있기"가 있으나
            // 여기만 없음 — 위험 확인된 곳에서 "그래도 볼래요"를 한 번의 터치로
            // 열어 두면 차단 무의미. 진입 전에는 터치 가드가 차단,
            // 이미 진입한 여기서는 탈출 경로만 크게
            Risk.HIGH -> render(
                tone = COLOR_HIGH,
                title = BLOCK_TITLE,
                body = BLOCK_BODY,
                signalTitle = "",
                signalSub = "",
                primary = "돌아가기",
                secondary = null,
            )

            // 주의 — "주의가 필요한 광고예요" 안내 + 돌아가기 / 그래도 보기 선택 (spec.md 3)
            Risk.MEDIUM -> render(
                tone = COLOR_MEDIUM,
                title = WARN_TITLE,
                body = WARN_BODY,
                signalTitle = "",
                signalSub = "",
                primary = "돌아가기",
                secondary = "그래도 보기",
            )

            // 미확인은 **주의**로 처리 (spec.md에 별도 행 없음). 안전 근거 없음 →
            // "주의가 필요한 광고예요" 표시 + 돌아가기 / 그래도 보기 선택
            Risk.UNKNOWN -> render(
                tone = COLOR_MEDIUM,
                title = WARN_TITLE,
                body = WARN_BODY,
                signalTitle = "",
                signalSub = "",
                primary = "돌아가기",
                secondary = "그래도 보기",
            )
        }
    }

    fun hide() {
        handler.removeCallbacks(timeout)
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
        card = null
        shownFor = null
        shownSite = null
        shownAppPkg = null
    }

    // ── 창 ──────────────────────────────────────────────────────────────────
    //
    // 생김새는 피그마 「FULL SERVICE FLOW v2」 Phase 2의 개입 아키타입 기준 —
    // **호스트 화면 + 딤 + 가운데 먹색 밴드 + 상태색 강조선 + 픽토 + 칩 + 제목/설명 + 버튼 둘.**
    //   2-4 확인 중:  전체 스크림 rgba(0,0,0,.88) · 스피너 · 32 Bold 흰 제목 · 17 Medium #BFBFBF
    //   2-5 주의:     밴드 rgba(10,12,16,.90) · 강조선 5 #FF8A1F · 삼각 픽토 100×88 · 칩 "주의"
    //   2-6 차단:     밴드 rgba(10,12,16,.95) · 강조선 6 · 원 픽토 88 · 칩 "차단"
    //   2-8 설치 차단: 같은 밴드 · 강조선 10 · 픽토 112 · 칩 "설치 차단" · 30 Bold 제목
    //   버튼: 주 202×52 r8 상태색 17 Bold 흰 / 보조 126×52 r8 흰 18% 15 Medium 흰 88%
    // 색은 [Look]의 UNKNOWN/WARN/DANGER, 피그마에만 있는 값은 이 파일 안에 기재

    private fun attach() {
        if (root != null) return
        val group = FrameLayout(service).apply {
            // 밴드 밖은 옅게만 가림(피그마는 호스트 화면 그대로 노출). 터치는 창 전체가
            // 흡수(아래) → 비쳐 보여도 클릭 불가. 색은 [render]가 화면마다 결정
            setBackgroundColor(SCRIM_BAND)
            // 창의 터치 흡수로 아래 페이지 클릭 차단 → 아무 데나 눌러도 무반응
            setOnClickListener { }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // NOT_TOUCHABLE **미부여** — 이 창의 역할은 터치 차단.
            // NOT_FOCUSABLE은 부여: 키보드 독점 시 사용자가 폰의 다른 기능 사용 불가
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        runCatching { windowManager.addView(group, params) }
            .onFailure { Log.w(TAG, "쉴드를 띄우지 못했다: $it"); return }
        root = group

        // 밴드는 **화면 가운데** 가로 꽉 참(피그마 2-5 · 0..390 × 300..644).
        // 강조선이 맨 위에 붙어야 하므로 밴드 자체는 좌우 여백 없음, 안쪽 내용이 24 확보
        card = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BAND)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        group.addView(card)
    }

    /**
     * 밴드 내용 재렌더.
     *
     * 버튼은 **언제나 같은 자리** — 왼쪽(상태색)이 탈출 경로, 오른쪽(반투명)이 머무는 길.
     * 위험도에 따라 오른쪽 버튼이 없으면 왼쪽 버튼이 가로 전체 차지.
     *
     * @param chip 픽토 아래 작은 알약의 글자. 비우면 상태색에 따라 "주의"/"차단"/"확인 중".
     */
    private fun render(
        tone: String,
        title: String,
        body: String,
        signalTitle: String,
        signalSub: String,
        primary: String? = "돌아가기",
        secondary: String? = null,
        onSecondary: (() -> Unit)? = null,
        chip: String? = null,
    ) {
        val box = card ?: return
        val group = root ?: return
        box.removeAllViews()

        // 「확인 중」은 색 하나로 구분한다. v2에서 이 화면에도 버튼이 생겨(2-4)
        // 「버튼이 없으면 확인 중」이라는 이전 판별은 더 이상 성립하지 않는다.
        // COLOR_WORKING을 쓰는 곳은 [showAnalyzing] 하나뿐이다
        val working = tone == COLOR_WORKING
        // 2-4 "확인 중"은 밴드가 아니라 **화면 전체** 어둡게(rgba(0,0,0,.88)). 밴드는 투명
        group.setBackgroundColor(if (working) SCRIM_WORKING else SCRIM_BAND)
        box.setBackgroundColor(if (working) Color.TRANSPARENT else BAND)

        // 상태색 강조선 — 밴드 맨 위 가로 꽉 참 (2-5: 5 · 2-6: 6 · 2-8: 10). 확인 중엔 없음
        if (!working) box.addView(accentLine(tone))

        val content = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            // 피그마: 픽토 위 28 · 버튼 아래 36 · 좌우 24
            setPadding(dp(24), dp(if (working) 0 else 28), dp(24), dp(if (working) 0 else 36))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        box.addView(content)
        // 아래도 같은 줄 — 위아래가 이어져 밴드가 한 덩어리로 읽힌다
        if (!working) box.addView(accentLine(tone))

        // 픽토 — 차단은 빨간 원(88), 주의는 주황 삼각(100×88), 확인 중은 스피너, 그 외엔 탭 동심원
        content.addView(picto(tone, working))

        // 상태 칩 (2-5/2-6: 상태색 알약 · 12 Bold 흰 · 여백 12/6 · 픽토와 16 간격). 2-4에는 없음
        // 광고 테두리의 파란 「광고」 배지와 **같은 모양·글씨체** — 색만 상태색.
        // 같은 앱의 같은 종류 표식이 화면마다 다른 서체·다른 모서리로 나오면 따로 배운다
        // ([AdDetectService.labelChip]와 같은 값: 15 Medium · 모서리 6 · 좌우 16)
        if (!working) content.addView(TextView(service).apply {
            text = chip ?: chipOf(tone)
            textSize = 15f
            includeFontPadding = false
            gravity = Gravity.CENTER
            typeface = Look.medium(service)
            setTextColor(Color.WHITE)
            setPadding(dp(16), 0, dp(16), 0)
            background = Look.pill(service, accentOf(tone), Look.AD_BORDER_RADIUS)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(31)
            ).apply { topMargin = dp(8) }   // 픽토에 붙여 아래 글자까지 함께 올림
        })

        // 제목 — 2-5/2-6: 24 Bold 흰 가운데 · 2-4 확인 중: 32 Bold
        content.addView(TextView(service).apply {
            text = title
            textSize = if (working) 32f else 24f
            letterSpacing = -0.015f
            includeFontPadding = false
            gravity = Gravity.CENTER_HORIZONTAL
            typeface = Look.bold(service)
            setLineSpacing(0f, 1.32f)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) }
        })

        // 설명 — 2-5/2-6: 16 Regular 흰 80% · 2-4: 17 Medium #BFBFBF
        if (body.isNotEmpty()) content.addView(TextView(service).apply {
            text = body
            textSize = if (working) 17f else 16f
            letterSpacing = -0.015f
            includeFontPadding = false
            gravity = Gravity.CENTER_HORIZONTAL
            if (working) typeface = Look.medium(service)
            setLineSpacing(0f, 1.5f)
            setTextColor(if (working) Color.parseColor("#BFBFBF") else Color.argb(204, 255, 255, 255))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        })

        // 근거 한 줄 — 피그마에는 없지만 **판단 근거** 유지.
        // 판정 문장만 있으면 "이 앱이 그렇다니까 그런가 보다" 수준. 설명보다 한 단계 흐리게.
        // "확인 중"은 제목이 이미 그 말이라 근거 줄에는 확인 대상(주소)만
        val evidence = (if (working) listOf(signalSub) else listOf(signalTitle, signalSub))
            .filter { it.isNotEmpty() }.joinToString(" · ")
        if (evidence.isNotEmpty()) content.addView(TextView(service).apply {
            text = evidence
            textSize = 13f
            letterSpacing = -0.005f
            includeFontPadding = false
            gravity = Gravity.CENTER_HORIZONTAL
            maxLines = 2
            setTextColor(Color.argb(143, 255, 255, 255))   // 흰 56%
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        })

        if (primary == null && secondary == null) return
        content.addView(LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)
            ).apply { topMargin = dp(if (working) 30 else 24) }
            // 피그마: 주 202 · 간격 14 · 보조 126 (342 안에서). 보조가 없으면 주가 전체 차지
            if (primary != null) addView(
                bandButton(primary, buttonOf(tone), filled = true, white = working) {
                    hide()
                    onLeave()
                },
                LinearLayout.LayoutParams(0, dp(52), if (secondary != null) 202f else 1f)
            )
            if (secondary != null) addView(bandButton(secondary, buttonOf(tone), filled = false) {
                hide()
                (onSecondary ?: onStay)()
            }, LinearLayout.LayoutParams(0, dp(52), 126f).apply {
                if (primary != null) leftMargin = dp(14)
            })
        })
    }

    /** 밴드 버튼 — 글씨는 둘 다 17 Bold 흰. 바탕만 다름: 주 상태색 채움 / 보조 흰 18% (r8) */
    private fun bandButton(
        label: String,
        accent: String,
        filled: Boolean,
        /** 시안 2-4의 흰 알약 — 어두운 화면 전체 위에서는 상태색보다 흰색이 눈에 든다 */
        white: Boolean = false,
        onTap: () -> Unit,
    ) =
        TextView(service).apply {
            text = label
            textSize = 17f
            letterSpacing = -0.015f
            gravity = Gravity.CENTER
            includeFontPadding = false
            // 굵기는 주·보조 같게 — 나란한 두 버튼의 글씨 무게가 다르면 하나가 눌러도
            // 되는 것처럼 보인다. 크기·바탕으로만 주·보조를 구분한다
            typeface = Look.bold(service)
            // 주·보조 버튼의 글씨를 **크기·굵기·색까지 같게** 둔다. 무게가 다르면
            // 어르신에게는 흐린 쪽이 "눌러도 되는 것"처럼 보인다 — 어느 쪽을 권하는지는
            // 바탕색(상태색 채움 vs 흰 18%)만으로 말한다
            setTextColor(if (white) Look.color(Look.INK) else Color.WHITE)
            background = GradientDrawable().apply {
                setColor(
                    when {
                        white -> Color.WHITE
                        filled -> Look.color(accent)
                        else -> Color.argb(46, 255, 255, 255)
                    }
                )
                cornerRadius = dp(if (white) 26 else 8).toFloat()
            }
            isClickable = true
            setOnClickListener { onTap() }
        }

    /** 밴드 위·아래를 잇는 상태색 줄 */
    private fun accentLine(tone: String) = View(service).apply {
        setBackgroundColor(Look.color(accentOf(tone)))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(if (tone == COLOR_HIGH) 6 else 5)
        )
    }

    /** 픽토 자리. 확인 중은 돌아가는 스피너(피그마의 정지 PNG 대신), 나머지는 벡터 */
    private fun picto(tone: String, working: Boolean): View =
        if (working) ProgressBar(service).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(dp(96), dp(96))
        } else ImageView(service).apply {
            setImageDrawable(service.getDrawable(
                when (tone) {
                    COLOR_HIGH -> R.drawable.ic_ov_blocked
                    COLOR_MEDIUM -> R.drawable.ic_ov_warning
                    else -> R.drawable.ic_ov_tap
                }
            ))
            // **높이는 두 화면 같게** — 밴드 높이가 어긋나면 같은 앱의 같은 종류 화면이
            // 서로 다른 자리에 뜬다. 폭만 도형 비율대로 다르다 (삼각 108 · 원 88).
            // 삼각형 도형의 여백은 ic_ov_warning.xml에서 잘라 냈다 (이슈 #35)
            layoutParams = LinearLayout.LayoutParams(
                dp(if (tone == COLOR_MEDIUM) 108 else 88), dp(88)
            )
        }

    /** 강조선·칩·주 버튼의 색. 차단만 피그마가 DANGER보다 밝은 #FF3B3B를 강조선에 사용 */
    private fun accentOf(tone: String) = when (tone) {
        COLOR_HIGH -> ACCENT_HIGH
        COLOR_MEDIUM -> Look.WARN
        COLOR_LOW -> Look.SAFE
        else -> Look.UNKNOWN
    }

    /** 주 버튼 바탕. 2-6의 "돌아가기"는 강조선(#FF3B3B)이 아니라 DANGER(#E01F26) */
    private fun buttonOf(tone: String) = if (tone == COLOR_HIGH) Look.DANGER else accentOf(tone)

    private fun chipOf(tone: String) = when (tone) {
        COLOR_HIGH -> "차단"
        COLOR_MEDIUM -> "주의"
        COLOR_WORKING -> "확인 중"
        else -> "확인"
    }

    private fun dp(v: Int) = Look.dp(service, v)
}
