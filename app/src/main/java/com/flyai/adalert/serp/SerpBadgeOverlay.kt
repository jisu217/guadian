package com.flyai.adalert.serp

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/**
 * 검색 결과별 위험도 배지 비차단 오버레이.
 *
 * ## 광고 테두리와 **별도 창**
 * 분리 이유: 소유권. 광고 감지(task 1)·검색 결과 위험도는 다른 사람이 다른 속도로
 * 수정하는 기능 — 창 공유 시 한쪽 변경이 다른 쪽 표시 삭제. 창 분리로 코드 상호 불간섭.
 *
 * ## FLAG_NOT_TOUCHABLE 제거 절대 금지
 * 터치 통과 — 사용자 선택 방해 없음. 알림만, 탭 차단 없음 — 구글 정책,
 * 가려던 사용자를 가두면 앱 이탈.
 *
 * ## 글자 없음 — 테두리 하나가 전부
 * 예전: 결과 칸 아래 "위험 · 불법 다시보기 사이트가 쓰는 이름입니다" 설명 막대.
 * 검색 결과 설명문 자리를 덮어 읽던 글 가림, 광고 테두리와 생김새 불일치로 같은 앱 표시로 미인식.
 *
 * 현재: **테두리만**. 위험 이유는 탭 시 [SerpGuard] 설명 시트 담당 —
 * 알림은 눈에 띄기만, 설명은 원할 때.
 *
 * ## 광고 테두리와 동일 규격
 * 두께·모서리·색을 광고 감지 쪽(`Look.AD_BORDER_W`·`Look.AD_BORDER_RADIUS`,
 * `Look.DANGER`)과 일치. 한 화면에 두 표시 동시 가능 — 생김새 다르면 서로 다른 경고로 오독.
 *
 * import 대신 **옮겨 적기** — 패키지 밖 클래스 미참조 원칙 ([SerpFeature] 참고).
 * 저쪽 변경 시 수동 동기화 필요.
 */
class SerpBadgeOverlay(private val context: Context) {

    private companion object {
        /** 광고 테두리와 같은 두께 (`Look.AD_BORDER_W`) — 사용자 설정은 따르지 않음 */
        const val BORDER_W_DP = 4f

        /** 광고 테두리와 같은 모서리 (`Look.AD_BORDER_RADIUS`) */
        const val BORDER_RADIUS_DP = 6

        /**
         * 광고 테두리의 '위험' 색 (`Look.DANGER`).
         *
         * [RiskGrade.HIGH]의 빨강(#E53935)과 의도적으로 다름 — 그 값은 이 기능 단독
         * 신호등 색, 현재는 광고 쪽과 한 세트 필요. 등급 색 정보 유지, **그리는 쪽만** 교체.
         */
        const val DANGER = "#E01F26"   // 피그마 v2 빨강

        /** 등급 알약 — 광고 배지와 같은 규격 (`AdDetectService.BADGE_H`·`BADGE_INSET`) */
        const val CHIP_H = 31
        const val CHIP_INSET = 12
        const val CHIP_TEXT_SP = 15f
    }

    private val windowManager by lazy {
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    private var overlay: FrameLayout? = null
    private var shown: List<Pair<Rect, SerpVerdict>> = emptyList()

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    /** 광고 테두리 두께 소수점 가능 — Float 오버로드 */
    private fun dp(v: Float) = (v * context.resources.displayMetrics.density).toInt()

    /** 현재 표시 중인 판정들. 진단·테스트용 */
    fun current(): List<Pair<Rect, SerpVerdict>> = shown

    fun show(marks: List<Pair<Rect, SerpVerdict>>) {
        if (marks.isEmpty()) {
            clear()
            return
        }
        shown = marks
        render()
    }

    /** 스크롤만큼 통째로 이동. 재판정 없이 자리만 추적 */
    fun offsetBy(dy: Int) {
        if (dy == 0 || shown.isEmpty()) return
        shown = shown.map { (rect, verdict) -> Rect(rect).apply { offset(0, dy) } to verdict }
        render()
    }

    fun clear() {
        shown = emptyList()
        removeOverlay()
    }

    private fun removeOverlay() {
        overlay?.let { runCatching { windowManager.removeView(it) } }
        overlay = null
    }

    private fun attach(): FrameLayout {
        overlay?.let { return it }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // NOT_TOUCHABLE — 사용자 터치 그대로 통과. 제거 금지
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        val root = FrameLayout(context)
        runCatching { windowManager.addView(root, params) }
        overlay = root
        return root
    }

    /**
     * 현재 아는 자리에 테두리 재묘사.
     *
     * ## 화면 좌표 ≠ 창 좌표
     * [Rect]는 접근성 트리의 **화면** 좌표. 이 창이 화면 맨 위에서 시작한다는 보장 없음 —
     * 상태바 아래 시작 시 그만큼 하향. 차이 미보정 `topMargin = rect.top`으로는 테두리가
     * 결과 칸보다 **조금씩 아래** 묘사(실기기 지적). 광고 테두리 쪽은 `getLocationOnScreen`으로
     * 이미 보정, 이쪽만 누락 상태였음.
     *
     * 창 배치 전에는 좌표 0 반환 — 그때만 한 프레임 지연. 배치 후 지연 없음 —
     * 스크롤 중 그 한 프레임이 그대로 지연.
     */
    private fun render() {
        val root = attach()
        if (root.width == 0 || root.height == 0) {
            root.post { render() }
            return
        }
        val loc = IntArray(2).also { root.getLocationOnScreen(it) }
        root.removeAllViews()

        for ((rect, verdict) in shown) {
            if (rect.width() <= 0 || rect.height() <= 0) continue
            root.addView(
                markView(verdict.grade),
                FrameLayout.LayoutParams(rect.width(), rect.height()).apply {
                    leftMargin = rect.left - loc[0]
                    topMargin = rect.top - loc[1]
                }
            )
        }
    }

    /**
     * 테두리 하나 + 왼쪽 위 알약 (시안 v2 2-12 · 2-13).
     *
     * v1은 테두리만 그리고 글자는 [SerpGuard] 시트에 맡겼는데, 시안은 결과 위에
     * **등급 알약**을 얹는다. 광고 테두리에 「광고」 배지가 붙는 것과 같은 자리·같은 규격이라
     * (15 Medium · 흰 글자 · 모서리 6 · 좌우 16 · 높이 31) 두 표시가 한 앱의 말로 읽힌다.
     *
     * 시안에는 알약 아래에 이유 한 줄도 있지만 그리지 않는다 — 이 창은 결과 칸 크기에
     * 딱 맞춰 떠 있어서, 안에 줄을 더하면 결과의 마지막 줄을 덮는다. 이유는 시트가 말한다.
     */
    private fun markView(grade: RiskGrade): View {
        val color = Color.parseColor(DANGER)
        val chip = TextView(context).apply {
            text = "위험"
            textSize = CHIP_TEXT_SP
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.WHITE)
            setPadding(dp(16), 0, dp(16), 0)
            background = GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(BORDER_RADIUS_DP).toFloat()
            }
        }
        return FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = dp(BORDER_RADIUS_DP).toFloat()
                // spec.md 3 · 주의 = 주황, 차단 = 빨강
                setStroke(dp(BORDER_W_DP), color)
            }
            // 창 자체가 터치를 통과시키므로(FLAG_NOT_TOUCHABLE) 알약도 결과 클릭을 막지 않는다
            addView(chip, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, dp(CHIP_H)
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                setMargins(dp(CHIP_INSET), dp(CHIP_INSET), 0, 0)
            })
        }
    }
}
