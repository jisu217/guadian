package com.flyai.adalert

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 닫기 버튼 없는 광고를 **가려서** 없앤 것처럼 처리.
 *
 * ## 필요한 이유 — X는 대부분 존재하지 않음
 * "광고 모두 닫기"의 원래 기능: 광고 안의 작은 X 대신 누르기. 그러나 실기기 접근성
 * 트리(네이트 뉴스, 크롬)에서 웹 배너 광고 컨테이너의 자식은 이미지 하나뿐,
 * **닫기 후보 노드 0개**. X는 광고 iframe 안에 그려질 뿐 접근성 트리에 미노출 → 누를 X 자체가 없음.
 *
 * 결과: 못 찾으면 아무 일도 없음("닫기 버튼이 없어요" 토스트), 폴백인 뒤로 가기는
 * 광고가 아닌 사용자가 보던 페이지를 닫음.
 *
 * 가리기는 둘 다 회피 — 광고는 눈앞에서 소멸, 읽던 페이지는 그대로.
 *
 * ## 터치 흡수 — 이것이 요점
 * 테두리 오버레이: 광고 클릭 방해 금지 → FLAG_NOT_TOUCHABLE 필수.
 * 이 창은 반대. 사용자가 **직접 "광고 모두 닫기"를 눌러** 그 광고 거부 의사를 밝힌
 * 뒤에만 표시 → 광고 오탭으로 끌려가는 것 차단이 목적.
 * 커버 자체 탭 = 해제 → 사용자는 언제든 되돌리기 가능.
 *
 * ## 스크롤 추적
 * 사용자가 「광고 닫기」로 덮은 커버는 좌표 미추적 — 화면이 구르면 광고도 함께 밀려
 * 올라가므로 유지 이유 없고, 잘못 덮었어도 스크롤 한 번이면 해제.
 *
 * **자동(위험 판정) 마스크는 다르다.** 이 창은 위험 광고로 가는 탭을 막는 것이 존재
 * 이유라, 스크롤 중 제자리에 남으면 광고는 드러나고 마스크는 밑에서 올라온 기사를
 * 덮는다 — 위험은 노출되고 멀쩡한 기사 터치는 먹는 이중 실패(이슈 #43, 실기기 재현).
 * 그래서 테두리 추적([AdDetectService.moveBorders])이 표본을 읽을 때마다 [moveAuto]로
 * 함께 이동한다.
 */
class AdCoverOverlay(private val context: Context) {

    private companion object {
        /** 커버 잔존 시간 상한 — 초과 시 자동 해제 (안전핀) */
        const val MAX_COVER_MS = 30_000L
    }

    private val windowManager by lazy {
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    private val handler = Handler(Looper.getMainLooper())

    /**
     * 광고 하나당 창 하나. **화면 전체 덮는 창 하나 금지** — 이 창은 터치를 받아야
     * 하므로(존재 이유) MATCH_PARENT면 광고 밖 빈 영역까지 터치 전부 흡수.
     * 실기기 결과: 화면 어디를 만져도 무반응 → 폰 멈춤처럼 보임, 스크롤도 불가 →
     * 스크롤로 걷히게 둔 커버가 영구 잔존. 각 창을 광고 크기로 제한 → 나머지 화면은 평소대로.
     */
    private val panels = mutableListOf<FrameLayout>()

    /** 현재 덮고 있는 자리들. [ensureCovered]의 "이미 덮였나" 판단용 */
    private var current: List<Rect> = emptyList()

    val isShowing: Boolean get() = panels.isNotEmpty()

    /** 자동(위험 판정) 마스킹 여부. 사용자가 "광고 닫기"로 덮은 것과 구분 */
    private var auto = false

    /** 자동 마스킹 사용자 탭 콜백. 서비스가 "막았어요" 안내 + 보호자 기록 추가 */
    var onAutoTap: (() -> Unit)? = null

    /**
     * 클릭 **전** 마스킹(spec.md 1 · 차단). 스캔마다 "지금 화면의 위험 광고 자리"를 받아
     * 그대로 동기화 — 새로 나타나면 덮기, 움직이면 추적, 사라지면 해제.
     * 같은 자리를 이미 덮고 있으면 무동작(200ms마다 걷었다 덮으면 깜빡임).
     * 사용자가 "광고 닫기"로 덮어 둔 것은 유지.
     */
    fun syncAuto(regions: List<Rect>, text: String) {
        if (regions.isEmpty()) {
            if (auto && panels.isNotEmpty()) hide()
            return
        }
        if (panels.isNotEmpty() && !auto) return
        if (panels.isNotEmpty() && current == regions) return
        cover(regions, text, dismissible = false)
        auto = true
    }

    /**
     * 스크롤 추적 — 이미 덮인 **자동** 마스크를 새 좌표로 이동 (이슈 #43).
     *
     * 테두리 추적과 같은 표본(32ms)으로 불린다. 창을 지웠다 다시 붙이지 않고
     * [WindowManager.updateViewLayout]으로 자리만 옮긴다 — 재생성은 깜빡임이고,
     * 그 사이 광고가 드러난다.
     *
     * 구성이 바뀌는 경우(광고 수 증감)는 여기서 처리하지 않는다 — 다음 스캔의
     * [syncAuto]가 정확한 목록으로 다시 동기화한다. [current]를 함께 갱신해야
     * 그 [syncAuto]가 "같은 자리"로 보고 재생성을 건너뛴다.
     */
    fun moveAuto(regions: List<Rect>) {
        if (!auto || panels.isEmpty()) return
        if (regions.size != panels.size) return
        if (regions == current) return
        current = regions.map { Rect(it) }
        for ((i, view) in panels.withIndex()) {
            val r = regions[i]
            if (r.width() <= 0 || r.height() <= 0) continue
            val lp = view.layoutParams as? WindowManager.LayoutParams ?: continue
            lp.width = r.width()
            lp.height = r.height()
            lp.x = r.left
            lp.y = r.top
            runCatching { windowManager.updateViewLayout(view, lp) }
        }
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    /** 주어진 영역들 덮기. 이미 표시 중이면 새 목록으로 교체 */
    fun cover(
        regions: List<Rect>,
        text: String = "광고를 가렸어요",
        dismissible: Boolean = true,
    ) {
        hide()
        auto = false
        current = regions.map { Rect(it) }
        for (r in regions) {
            if (r.width() <= 0 || r.height() <= 0) continue
            val view = FrameLayout(context).apply {
                addView(panel(text, dismissible, r.height()), FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ))
            }
            val params = WindowManager.LayoutParams(
                r.width(), r.height(),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                // 터치 수신(NOT_TOUCHABLE 없음) — 광고로 가는 탭 차단이 이 창의 존재 이유.
                // 키 입력은 아래로 통과 → 뒤로 가기 유지
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = r.left
                y = r.top
            }
            runCatching { windowManager.addView(view, params) }.onSuccess { panels.add(view) }
        }
        // 안전핀 — 어떤 경로로도 커버가 화면에 눌러붙지 않게. 이 창은 터치를
        // 흡수하므로 잔존 자체가 위험, 실제로 한 번 눌러붙은 사례 있음
        handler.removeCallbacks(autoHide)
        if (panels.isNotEmpty()) handler.postDelayed(autoHide, MAX_COVER_MS)
    }

    private val autoHide = Runnable { hide() }

    /**
     * 무엇이 왜 가려졌는지 안내 — 빈 회색 칸만 남으면 오히려 불안.
     *
     * 생김새: 피그마 2-3 「악성 광고 마스킹」 — 먹색 #171A1F 바탕 · 빨강 #FF3B3B 6px 테두리 ·
     * 반경 12 · 가운데 [빨간 원 픽토 44 · 18 Bold 흰 제목 · 14 Regular 흰 64% 설명].
     * v2에는 왼쪽 위 알약이 없다 — 바탕·테두리·픽토가 이미 「막았다」를 말한다.
     *
     * 낮은 광고(320×50 인라인 배너): 세로 구성 불가 → 한 줄로 축소.
     * 문구는 호출 측이 " · "로 제목·설명을 이어 전달 → 여기서 두 줄로 분리.
     */
    private fun panel(label: String, dismissible: Boolean, heightPx: Int): FrameLayout {
        val parts = label.split(" · ", limit = 2)
        val title = parts[0]
        val sub = parts.getOrNull(1)
        val tall = heightPx >= dp(110)          // 픽토 + 두 줄 + 배지 수용 높이
        val stroke = if (tall) 6 else 3

        val body = LinearLayout(context).apply {
            orientation = if (tall) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(stroke + 6), dp(20), dp(stroke + 6))
            addView(ImageView(context).apply {
                setImageDrawable(context.getDrawable(R.drawable.ic_ov_blocked))
            }, LinearLayout.LayoutParams(dp(if (tall) 44 else 22), dp(if (tall) 44 else 22)).apply {
                if (tall) bottomMargin = dp(8) else rightMargin = dp(8)
            })
            addView(TextView(context).apply {
                text = title
                textSize = if (tall) 18f else 15f
                letterSpacing = -0.015f
                includeFontPadding = false
                typeface = Look.bold(context)
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                maxLines = if (tall) 2 else 1
            })
            if (tall && sub != null) addView(TextView(context).apply {
                text = sub
                textSize = 14f
                letterSpacing = -0.015f
                includeFontPadding = false
                gravity = Gravity.CENTER
                setTextColor(Color.argb(163, 255, 255, 255))     // 흰 64%
                maxLines = 2
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) })
        }

        return FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#171A1F"))
                setStroke(dp(stroke), Color.parseColor("#FF3B3B"))
                cornerRadius = dp(12).toFloat()
            }
            addView(body, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            ))
            // v1의 "차단됨" 알약은 v2 시안(1038:99)에서 사라졌다. 먹색 바탕에 빨간 테두리,
            // 빨간 원 픽토까지 이미 "막았다"를 말하고 있어 알약은 같은 말을 한 번 더 한다.
            // 좁은 배너에서는 그 알약이 제목 자리를 먹기까지 했다
            // 탭 = 전부 해제 — 잘못 가렸을 때 사용자가 스스로 되돌리는 길.
            // 위험 판정 자동 덮기(spec.md 1 차단)는 탭으로 해제 불가
            if (dismissible) setOnClickListener { hide() } else setOnClickListener { onAutoTap?.invoke() }
        }
    }

    fun hide() {
        handler.removeCallbacks(autoHide)
        for (p in panels) runCatching { windowManager.removeView(p) }
        panels.clear()
        current = emptyList()
    }
}
