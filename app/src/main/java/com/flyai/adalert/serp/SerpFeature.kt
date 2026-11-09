package com.flyai.adalert.serp

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope

/**
 * 검색 결과 위험도 기능 전체의 **단 하나의 입구**
 *
 * ## 존재 이유 — 병합 충돌 방지
 * 이 기능은 다른 팀원의 task(광고 감지·광고 닫기·전환 탈출)와 별개. 그러나 접근성
 * 서비스는 앱에 **하나뿐**이라 모든 task가 `GuardianAccessibilityService` 한 곳으로
 * 이벤트 수신 — 각 task가 그 파일에 필드·분기를 흩뿌리면 병합마다 같은 자리에서 충돌.
 *
 * 서비스에 요구하는 것은 **여섯 줄**뿐:
 *
 * ```kotlin
 * // 1) 필드 하나
 * private val serp by lazy { SerpFeature(this, scope) }
 *
 * // 2) 이벤트 필터에 우리 앱 추가 (구글 앱은 targetApps에 없음)
 * packageNames = (targetApps + storePackages + SerpFeature.PACKAGES).toTypedArray()
 *
 * // 3) dispatch에서 한 줄 — targetApps 필터보다 **앞에**
 * serp.onEvent(event, pkg)
 *
 * // 4) 정리 — onInterrupt()와 onDestroy()에 각각
 * serp.stop()
 * ```
 *
 * 이 여섯 줄 밖에서 `com.flyai.adalert.serp` 패키지는 서비스를 전혀 모름. 패키지째 다른
 * 저장소로 옮겨도 그대로 컴파일 — **자기 밖의 어떤 클래스도 import 없음**
 * (안드로이드 SDK·코루틴 제외).
 *
 * ## 건드리지 않는 것
 * 광고 감지의 오버레이(`AdBorderOverlay`)·액션바·룰 엔진·판정 캐시 사용 없음. 배지는
 * **자기 창**에 그리므로 광고 테두리와 서로 지우지 않음 (실기기에서 두 표시 공존 확인).
 *
 * @param service 접근성 서비스 본체. 창 부착([SerpBadgeOverlay])·화면 루트 읽기에 사용.
 *        **서비스 컨텍스트 필수** — applicationContext는 오버레이 창 토큰 없음 →
 *        `BadTokenException`으로 사망
 * @param scope 서비스의 코루틴 스코프. 서비스 종료 시 함께 취소되도록 차용
 */
class SerpFeature(
    private val service: AccessibilityService,
    scope: CoroutineScope,
    /**
     * 어르신이 위험한 검색 결과를 **눌렀을 때** 호출.
     *
     * 판정이 내려진 것만으로는 호출되지 않는다 — 위험한 줄이 섞인 검색 결과는 흔하고
     * 어르신은 대개 누르지 않고 지나간다. 화면에 떴다는 이유로 매번 올리면 스크롤 한
     * 번에 보호자 폰이 울려 결국 알림 자체가 꺼진다. 올릴 사실은 "눌렀다" 쪽이다.
     *
     * 누른 뒤에 들어가지는 못한다 — 관문이 나가는 길만 준다([SerpGuard]). 호스트 전달
     * 없음: 어르신이 무엇을 검색했고 무엇을 눌렀는지는 기기 밖으로 내보내지 않는다
     */
    onRiskTapped: () -> Unit = {}
) {

    companion object {
        /**
         * 이 기능이 이벤트를 받아야 하는 앱 목록.
         *
         * **`targetApps`와 일부러 다름.** 어르신의 구글 검색 경로는 크롬보다 구글 앱·홈
         * 위젯이 오히려 흔한데, 구글 앱은 광고 감지 대상이 아니라 `targetApps`에 없음.
         * 서비스의 `packageNames`에 이 목록을 더해야 구글 앱 이벤트가 서비스까지 도달 —
         * 안 더하면 그 앱에서 기능 전체가 침묵 (실기기에서 확인)
         */
        val PACKAGES = setOf("com.android.chrome", SerpScanner.GOOGLE_APP)
    }

    private val scanner = SerpScanner()

    private val overlay = SerpBadgeOverlay(service)

    private val engine: SerpRiskEngine = run {
        // 규칙만으로 동작. 규칙은 네트워크 미사용이라 누누티비류(이름만으로 확정)는 포착.
        // 처음 보는 사이트는 '확인 안 됨'으로 남겨 표시 없음 — 모르는 것을 안전하다고
        // 말하지 않기 위함
        SerpRiskEngine(RuleOnlyClassifier)
    }

    private val tracker = SerpTracker(service, scanner, engine, overlay, scope, onRiskTapped)

    /**
     * 지금 화면이 검색 결과 화면인지. 서비스가 도메인 대조(UrlGuard)를 건너뛸 때 참조 —
     * 검색 결과 위에서는 배지가 결과 칸 단위로 이미 같은 위험을 안내
     */
    val isSerpScreen: Boolean get() = tracker.isSerpScreen

    /**
     * 화면 꺼짐 시 배지 즉시 제거.
     *
     * 재확인 타이머([SerpTracker.RECHECK_MS])만으로도 결국 지워지지만, 그 1초 사이에
     * 화면을 켜면 **잠금화면 위에 정체불명의 빨간 테두리 잔존** (실기기에서 실제 발생).
     * 잠금화면은 접근성 이벤트로 알 수 없음(`packageNames`에 없음) — 브로드캐스트가
     * 이 구멍을 메우는 유일한 경로
     */
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = tracker.clear()
    }

    init {
        runCatching { service.registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF)) }
    }

    /**
     * 이벤트 하나 수신. **서비스의 `targetApps` 필터보다 앞에서 호출할 것** —
     * 구글 앱은 그 목록에 없어, 뒤에 두면 이벤트가 여기까지 오지 않음.
     *
     * 관심 없는 앱이면 아무 동작 없음
     */
    fun onEvent(event: AccessibilityEvent, packageName: String) {
        if (packageName !in PACKAGES) return

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            tracker.onScroll(scrollDeltaY(event))
        }
        tracker.onEvent(event) { searchRoot() }
    }

    /** 서비스 정지·종료 시. 창을 떼지 않으면 배지가 화면에 잔존 */
    fun stop() {
        tracker.clear()
        runCatching { service.unregisterReceiver(screenOff) }
    }

    /**
     * 배지만 잠시 제거 (수신기·판정 캐시는 유지). 가림막·설치 경고 같은 HIGH 위험
     * 오버레이가 떠 있는 동안 서비스가 호출 — 화면을 덮은 경고 위에 배지까지 겹치면
     * 어느 것도 읽히지 않음. 경고가 걷히고 이벤트가 다시 들어오면 다음 스캔이 배지 복원
     */
    fun hide() = tracker.clear()

    /** 검색 앱일 때만 루트 반환. 아니면 null — 트래커가 그걸 보고 배지 제거 */
    private fun searchRoot(): AccessibilityNodeInfo? =
        service.rootInActiveWindow?.takeIf { it.packageName?.toString() in PACKAGES }

    /**
     * 이 스크롤로 화면이 세로로 움직인 px. 노드 읽기 없음.
     *
     * 값을 채우지 않는 뷰는 UNDEFINED(-1) 그대로. 1px 스크롤은 눈에 보이지도 않으므로
     * 둘을 구분 없이 함께 폐기. 화면 높이를 넘는 값은 페이지 점프 또는 쓰레기값 —
     * 밀지 않고 스캔에 위임.
     *
     * 서비스에도 같은 함수가 있지만 미사용 — 그 한 줄로 이 패키지가 서비스 없이는
     * 컴파일 불가가 되고, 그것이 정확히 이 클래스가 없애려는 결합
     */
    private fun scrollDeltaY(event: AccessibilityEvent): Int {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) return 0
        val dy = event.scrollDeltaY
        if (dy > -2 && dy < 2) return 0
        val limit = service.resources.displayMetrics.heightPixels
        if (dy > limit || dy < -limit) return 0
        return dy
    }
}
