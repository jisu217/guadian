package com.flyai.adalert.serp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.flyai.adalert.R
import kotlin.math.hypot

/**
 * 빨강(HIGH) 검색 결과 **탭 시 설명 시트 우선 표시** 관문.
 *
 * ## 배지 창이 못 하는 이유
 * [SerpBadgeOverlay]는 `FLAG_NOT_TOUCHABLE` — 터치 통과. 플래그 제거 금지
 * (배지는 결과 열 칸 위 표시라 터치를 받으면 안전한 결과까지 탭 불가).
 * 따라서 **탭을 받는 창 별도** — 빨강 결과 위에만, 그 칸 크기로만 표시.
 *
 * ## 나가는 길만 준다
 * 한때는 주의(주황) 결과에 「그래도 보기」를 줬다. 주황이 사라지면서 그 선택지도 함께
 * 걷었다 — 남은 것은 빨강뿐이고, 빨강은 들어가면 안 되는 곳이기 때문. 「가려던 사람을
 * 가두면 앱 이탈」이라는 우려는 주황이 있을 때의 이야기였다.
 *
 * ## 생김새: 쉴드(Shield)와 동일
 * 아래에서 올라온 흰 시트, 손잡이 막대, 제목 한 줄, 설명, 근거 상자, 큰 버튼 하나.
 * 색·크기 값은 `Look`에서 **옮겨 적은 값**(참조 아님) — 패키지 밖 클래스 import 없음 원칙
 * ([SerpFeature] 참고). 저쪽 값 변경 시 수동 동기화 필요.
 */
internal class SerpGuard(
    private val context: Context,
    /**
     * 어르신이 위험한 결과를 **누른 순간** 호출 — 그 결과의 호스트.
     * 억제·전송 판단은 받는 쪽([SerpTracker])의 몫이고, 여기서는 사실만 알린다
     */
    private val onAlert: (String) -> Unit = {},
) {

    private companion object {

        /** 관문 최소 칸 높이 — 손끝보다 작으면 오조작 유발 */
        const val MIN_GUARD_DP = 24

        // ── 등급별 고정 문구 (이슈 #35) ────────────────────────────────────
        /**
         * 광고 쉴드(`Shield`)와 같은 체계. 판정마다 다른 말이 아니라 등급마다 같은 말.
         *
         * 제목만 다르다 — 검색 결과는 광고가 아니라 "광고예요"가 사실과 어긋난다.
         * 값 자체는 옮겨 적은 것(패키지 밖 클래스 import 없음 원칙) — 저쪽이 바뀌면 수동 동기화
         */
        const val BLOCK_TITLE = "위험한 접속을 막았어요"
        const val BLOCK_BODY = "사기나 앱 설치로 이어져요"

        // ── 밴드 규격 (광고 쉴드 `Shield`에서 옮겨 적음) ──────────────────
        /** 밴드 바탕 · 밴드 밖 어둡게 */
        val BAND = Color.argb(242, 10, 12, 16)
        val SCRIM_BAND = Color.argb(89, 10, 12, 16)
        /** 강조선·칩 색. 차단은 주 버튼(`BUTTON_HIGH`)보다 밝은 값을 쓴다 */
        const val ACCENT_HIGH = "#FF3B3B"
        /** 차단의 주 버튼 바탕 */
        const val BUTTON_HIGH = "#E01F26"
        /** 칩 모서리 — 광고 테두리(`Look.AD_BORDER_RADIUS`)와 같은 값 */
        const val CHIP_RADIUS_DP = 6

        // ── 쓸어넘김 재생 (광고 쪽 가드와 같은 값) ──
        /** 재생 길이 하한·상한 */
        const val REPLAY_MIN_MS = 80L
        const val REPLAY_MAX_MS = 400L
        /** 관문 창이 실제로 걷힐 때까지의 대기 */
        const val REPLAY_WAIT_MS = 32L
        /** 재생이 끝난 뒤 관문을 다시 붙이기까지의 여유 */
        const val REPLAY_GAP_MS = 120L

        /**
         * 설명 시트 자동 종료 상한 (안전핀).
         *
         * 화면 전체 덮는 터치 수신 창 — 어떤 경로로도 눌러붙기 금지.
         * [clear]가 시트를 닫지 않게 된 자리를 이 상한이 대체. 읽기 30초면 충분,
         * 버튼 둘 중 하나 탭 시 그 전에 종료.
         */
        const val SHEET_MAX_MS = 30_000L
    }

    private val windowManager by lazy {
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    /** 결과 칸 하나당 창 하나 — 전체 화면 창 하나면 나머지 결과 탭 불가 */
    private val panels = mutableMapOf<String, View>()

    /** 현재 관문 걸린 칸들 — 키(호스트+좌표) → 판정·자리 */
    private var guarded: List<Guard> = emptyList()

    private var sheet: FrameLayout? = null

    private val handler = Handler(Looper.getMainLooper())

    // ── 쓸어넘김 판정 ────────────────────────────────────────────────────────
    private val touchSlop by lazy { ViewConfiguration.get(context).scaledTouchSlop }
    private var downX = 0f
    private var downY = 0f
    private var dragged = false
    /** 이 시각까지는 관문을 붙이지 않음 — 재생한 제스처를 되먹지 않도록 */
    private var replayUntil = 0L

    private val autoDismiss = Runnable {
        Log.i(SERP_TAG, "serp 설명 시트 자동 종료 (${SHEET_MAX_MS / 1000}초)")
        closeSheet()
    }

    private data class Guard(val host: String, val rect: Rect, val verdict: SerpVerdict) {
        val key: String get() = "$host@${rect.left},${rect.top},${rect.width()},${rect.height()}"
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    private fun color(hex: String) = Color.parseColor(hex)

    /**
     * 이 화면의 관문 대상 칸들. 매 스캔마다 통째로 교체.
     *
     * 좌표 동일 시 창 재생성 없음 — 스캔 주기 150ms, 매번 창을 떼었다 붙이면
     * 그 사이 터치 유실.
     */
    fun update(marks: List<Triple<String, Rect, SerpVerdict>>) {
        val next = marks
            .filter { (_, rect, _) -> rect.width() > 0 && rect.height() >= dp(MIN_GUARD_DP) }
            .map { (host, rect, verdict) -> Guard(host, Rect(rect), verdict) }
        if (next.map { it.key } == guarded.map { it.key }) {
            guarded = next
            return
        }
        guarded = next

        val wanted = next.associateBy { it.key }
        // 사라진 칸의 창 제거
        for (key in panels.keys.toList()) {
            if (key !in wanted) {
                panels.remove(key)?.let { runCatching { windowManager.removeView(it) } }
            }
        }
        // 새로 생긴 칸에 창 추가
        for ((key, guard) in wanted) {
            if (key in panels) continue
            // 쓸어넘김 재생 중에는 붙이지 않는다 — 재생한 제스처를 관문이 그대로 되먹는다
            if (SystemClock.uptimeMillis() < replayUntil) continue
            val view = View(context).apply {
                setOnTouchListener { v, e -> onGuardTouch(v, guard, e) }
            }
            runCatching { windowManager.addView(view, params(guard.rect)) }
                .onSuccess { panels[key] = view }
                .onFailure { Log.w(SERP_TAG, "serp 관문 창을 띄우지 못했다: $it") }
        }
    }

    /**
     * 관문 위의 터치 하나. **누르면 막고, 쓸어넘기면 흘려보낸다.**
     *
     * 종전에는 `setOnClickListener` 하나뿐이었다. 클릭 판정이 [MotionEvent.ACTION_DOWN]을
     * 삼키면서 쓸어넘김은 아래로 넘겨주지 않아, 관문이 덮은 칸에서 시작한 스크롤이 통째로
     * 죽었다 — 실측 화면 변화량 0(이슈 #30). 광고 쪽 가드가 쓰는 방식과 같게 맞춘다.
     */
    private fun onGuardTouch(v: View, guard: Guard, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX
                downY = e.rawY
                dragged = false
            }
            MotionEvent.ACTION_MOVE ->
                if (!dragged && hypot(e.rawX - downX, e.rawY - downY) > touchSlop) dragged = true

            MotionEvent.ACTION_UP -> if (dragged) replaySwipe(v, e) else openSheet(guard)
        }
        return true
    }

    /**
     * 관문이 먹은 쓸어넘김을 손을 뗀 뒤 같은 궤적으로 다시 그린다.
     *
     * 관성(플링)은 재현되지 않는다 — 재생이 등속 드래그라 손을 뗀 자리에서 멈춘다.
     * 스크롤이 아예 안 되던 것보다 낫다는 판단으로, 광고 쪽 가드와 같은 선택.
     */
    private fun replaySwipe(v: View, e: MotionEvent) {
        val ms = (e.eventTime - e.downTime).coerceIn(REPLAY_MIN_MS, REPLAY_MAX_MS)
        // 관문부터 걷는다. 남겨 두면 재생한 제스처를 이 관문이 그대로 다시 먹는다
        dropPanel(v)
        // [MotionEvent]는 시스템이 회수해 재사용 — 대기 뒤에 읽으면 다른 터치의 좌표가
        // 들어 있다. 지금 꺼내 둔다
        val fromX = downX
        val fromY = downY
        val toX = e.rawX
        val toY = e.rawY
        // 기다리는 동안에도 관문이 다시 붙으면 안 된다 — 대기까지 포함해서 막는다
        replayUntil = SystemClock.uptimeMillis() + REPLAY_WAIT_MS + ms + REPLAY_GAP_MS
        handler.postDelayed({ replayNow(fromX, fromY, toX, toY, ms) }, REPLAY_WAIT_MS)
    }

    /** 관문 창이 실제로 걷힌 뒤 실행되는 재생 본체. [replaySwipe] 참조 */
    private fun replayNow(fromX: Float, fromY: Float, toX: Float, toY: Float, ms: Long) {
        val service = context as? AccessibilityService
        if (service == null) {
            Log.w(SERP_TAG, "serp 쓸어넘김 재생 불가 — 서비스 컨텍스트가 아니다")
            return
        }
        // 점이 하나라도 화면 밖이면 [AccessibilityService.dispatchGesture]가 제스처를
        // 통째로 거부해 스크롤이 날아간다. 손가락은 화면 밖 좌표를 만들지 못하지만
        // 합성 입력이 넣을 수 있어 방어한다 (광고 쪽 clampX·clampY와 같은 이유)
        val maxX = context.resources.displayMetrics.widthPixels - 1f
        val maxY = context.resources.displayMetrics.heightPixels - 1f
        val path = Path().apply {
            moveTo(fromX.coerceIn(0f, maxX), fromY.coerceIn(0f, maxY))
            lineTo(toX.coerceIn(0f, maxX), toY.coerceIn(0f, maxY))
        }
        val ok = runCatching {
            service.dispatchGesture(
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0L, ms))
                    .build(),
                null, null
            )
        }.getOrDefault(false)
        Log.i(SERP_TAG, "serp 쓸어넘김 재생 ${if (ok) "성공" else "실패"} ${ms}ms")
    }

    /**
     * 재생 직전 그 관문 하나만 걷는다.
     *
     * [guarded]에서도 빼는 이유 — [update]는 키 목록이 그대로면 조기 반환한다. 창만 지우면
     * 다음 스캔이 "이미 있다"고 보고 넘어가 관문이 영영 복원되지 않는다.
     */
    private fun dropPanel(v: View) {
        val key = panels.entries.firstOrNull { it.value === v }?.key ?: return
        panels.remove(key)
        runCatching { windowManager.removeView(v) }
        guarded = guarded.filterNot { it.key == key }
    }

    /**
     * 스크롤만큼 창 이동. 재판정 없이 자리만 추적 —
     * [SerpBadgeOverlay.offsetBy]와 같은 이유, 정확한 자리는 다음 스캔이 결정.
     */
    fun offsetBy(dy: Int) {
        if (dy == 0 || guarded.isEmpty()) return
        val moved = guarded.map { it.copy(rect = Rect(it.rect).apply { offset(0, dy) }) }
        // 키에 좌표 포함 — 창 목록의 키도 함께 이동
        val remapped = mutableMapOf<String, View>()
        for ((old, guard) in guarded.zip(moved)) {
            val view = panels.remove(old.key) ?: continue
            runCatching { windowManager.updateViewLayout(view, params(guard.rect)) }
            remapped[guard.key] = view
        }
        // 짝 없는 잔여 창 제거 (정상 경로에서는 비어 있음)
        for (view in panels.values) runCatching { windowManager.removeView(view) }
        panels.clear()
        panels.putAll(remapped)
        guarded = moved
    }

    /**
     * 관문 창만 제거. **설명 시트는 유지.**
     *
     * 시트는 전체 화면 창 — 떠 있는 동안 `rootInActiveWindow`가 크롬 대신 이 창을 반환.
     * [SerpTracker] 화면 관문이 "검색 결과 아님"으로 읽고 dropAll → clear 경로 진입,
     * 예전에는 그 길에서 시트가 **자기 자신을 닫음** — 실기기에서 1~3초 만에 소실
     * (실측 2.8초, 1.4초).
     *
     * 시트는 사용자 응답 필수 관문. 닫는 경로: 버튼 둘, 화면 꺼짐([dismissAll]),
     * [SHEET_MAX_MS] 안전핀뿐.
     */
    fun clear() {
        for (view in panels.values) runCatching { windowManager.removeView(view) }
        panels.clear()
        guarded = emptyList()
    }

    /** 관문·시트 **전부** 제거. 화면 꺼짐·서비스 종료 시에만 호출 */
    fun dismissAll() {
        if (sheet != null) Log.i(SERP_TAG, "serp 시트 닫힘 — 전체 정리(dismissAll)")
        clear()
        closeSheet()
    }

    private fun params(r: Rect) = WindowManager.LayoutParams(
        r.width(), r.height(),
        r.left, r.top,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        // 터치 수신(NOT_TOUCHABLE 없음) — 첫 탭 대신 받기가 이 창의 존재 이유.
        // 키 입력은 아래로 통과 — 뒤로 가기 유지
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    // ── 설명 시트 ────────────────────────────────────────────────────────────

    private fun openSheet(guard: Guard) {
        if (sheet != null) return
        Log.i(SERP_TAG, "serp 관문 탭 host=${guard.host}")
        // 여기서 끝난다 — 시트가 나가는 길만 주므로 "눌렀지만 들어가지 못했다"가 이
        // 자리에서 확정. 시트를 못 띄워도 관문이 터치를 이미 삼켰으므로 사실은 같다
        onAlert(guard.host)

        val root = FrameLayout(context).apply {
            setBackgroundColor(SCRIM_BAND)
            // 시트 밖 탭 시 아래 페이지 탭 금지 — 동작 없음
            setOnClickListener { }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        runCatching { windowManager.addView(root, params) }
            .onFailure { Log.w(SERP_TAG, "serp 설명 시트를 띄우지 못했다: $it"); return }
        sheet = root
        root.addView(card(guard))
        // 안전핀 — 터치 수신 창이라 잔류 자체가 위험
        handler.removeCallbacks(autoDismiss)
        handler.postDelayed(autoDismiss, SHEET_MAX_MS)
    }

    private fun closeSheet() {
        handler.removeCallbacks(autoDismiss)
        sheet?.let { runCatching { windowManager.removeView(it) } }
        sheet = null
    }

    /**
     * 광고 쉴드([com.flyai.adalert.Shield])와 **같은 밴드**. 화면 아래에서 올라온 흰 시트가
     * 아니라 검은 밴드 · 큰 픽토 · 상태 칩 · 상태색 버튼.
     *
     * 같은 앱이 같은 말을 하는데 생김새가 다르면 어르신은 다른 앱의 화면으로 읽는다.
     * 수치·색은 저쪽에서 옮겨 적었다(패키지 밖 클래스 import 없음 원칙) — 저쪽이 바뀌면
     * 수동 동기화가 필요하다. 픽토만 같은 벡터를 쓴다(리소스라 중복시킬 이유가 없다).
     */
    private fun card(guard: Guard): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(BAND)
        // 광고 쉴드와 같은 자리 — 화면 **가운데**. 아래 붙이면 버튼이 내비게이션 막대에
        // 닿고, 같은 앱의 같은 경고인데 다른 화면처럼 보인다
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        )

        val accent = ACCENT_HIGH

        // 상태색 강조선 — 밴드 맨 위 가로 꽉 참
        addView(accentLine(guard))

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(28), dp(24), dp(36))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        addView(content)
        // 아래도 같은 줄 — 위아래가 이어져 밴드가 한 덩어리로 읽힌다
        addView(accentLine(guard))

        // 픽토 — 주의는 삼각형이라 같은 상자에서 작게 보여 키운 값 (이슈 #35)
        content.addView(ImageView(context).apply {
            setImageDrawable(context.getDrawable(
                R.drawable.ic_ov_blocked
            ))
            // **높이는 두 화면 같게** — 밴드 높이가 어긋나면 같은 앱의 같은 종류 화면이
            // 서로 다른 자리에 뜬다. 폭만 도형 비율대로 다르다 (삼각 108 · 원 88).
            // 삼각형 도형의 여백은 ic_ov_warning.xml에서 잘라 냈다 (이슈 #35)
            layoutParams = LinearLayout.LayoutParams(
                dp(88), dp(88)
            )
        })

        // 상태 칩 — 광고 테두리의 파란 「광고」 배지와 **같은 모양·글씨체**, 색만 상태색
        // (15 Medium · 모서리 6 · 좌우 16 · 높이 31)
        content.addView(TextView(context).apply {
            text = "차단"
            textSize = 15f
            includeFontPadding = false
            gravity = Gravity.CENTER
            typeface = medium()
            setTextColor(Color.WHITE)
            setPadding(dp(16), 0, dp(16), 0)
            background = GradientDrawable().apply {
                setColor(color(accent))
                cornerRadius = dp(CHIP_RADIUS_DP).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(31)
            ).apply { topMargin = dp(8) }   // 픽토에 붙여 아래 글자까지 함께 올림
        })

        content.addView(TextView(context).apply {
            text = BLOCK_TITLE
            textSize = 24f
            letterSpacing = -0.015f
            includeFontPadding = false
            gravity = Gravity.CENTER_HORIZONTAL
            typeface = bold()
            setLineSpacing(0f, 1.32f)
            setTextColor(Color.WHITE)
            layoutParams = wide().apply { topMargin = dp(14) }
        })

        content.addView(TextView(context).apply {
            text = BLOCK_BODY
            textSize = 16f
            letterSpacing = -0.015f
            includeFontPadding = false
            gravity = Gravity.CENTER_HORIZONTAL
            setLineSpacing(0f, 1.5f)
            setTextColor(Color.argb(204, 255, 255, 255))
            layoutParams = wide().apply { topMargin = dp(8) }
        })

        // 버튼 — 나가는 길만. 둘로 나뉘어 있던 자리를 하나가 가로로 다 쓴다
        content.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)
            ).apply { topMargin = dp(24) }
            addView(bandButton("돌아가기", BUTTON_HIGH, filled = true) {
                Log.i(SERP_TAG, "serp 시트 닫힘 — 사용자가 '돌아가기'")
                closeSheet()
            }, LinearLayout.LayoutParams(0, dp(52), 1f))
        })
    }

    /** 밴드 위·아래를 잇는 상태색 줄 */
    private fun accentLine(guard: Guard) = View(context).apply {
        setBackgroundColor(color(ACCENT_HIGH))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(6)
        )
    }

    /** 밴드 버튼 — 글씨는 둘 다 17 Bold 흰. 바탕만 다름: 주 상태색 채움 / 보조 흰 18% (r8) */
    private fun bandButton(label: String, accent: String, filled: Boolean, onTap: () -> Unit) =
        TextView(context).apply {
            text = label
            textSize = 17f
            letterSpacing = -0.015f
            gravity = Gravity.CENTER
            includeFontPadding = false
            // 굵기는 주·보조 같게 — 나란한 두 버튼의 글씨 무게가 다르면 하나가 눌러도
            // 되는 것처럼 보인다. 크기·바탕으로만 주·보조를 구분한다
            typeface = bold()
            // 주·보조 버튼의 글씨를 **크기·굵기·색까지 같게** 둔다. 무게가 다르면
            // 어르신에게는 흐린 쪽이 "눌러도 되는 것"처럼 보인다 — 어느 쪽을 권하는지는
            // 바탕색(상태색 채움 vs 흰 18%)만으로 말한다
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(if (filled) color(accent) else Color.argb(46, 255, 255, 255))
                cornerRadius = dp(8).toFloat()
            }
            isClickable = true
            setOnClickListener { onTap() }
        }

    private fun wide() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    /**
     * 앱 공용 Noto 글꼴. **R 클래스 import 없음** — 패키지 밖 미참조 원칙상
     * 리소스도 이름으로 조회. 미발견 시 시스템 글꼴 폴백.
     */
    private fun bold(): Typeface = face("noto_sans_kr_bold", Typeface.DEFAULT_BOLD)
    private fun medium(): Typeface = face("noto_sans_kr_medium", Typeface.DEFAULT)

    private fun face(name: String, fallback: Typeface): Typeface {
        cache[name]?.let { return it }
        val id = context.resources.getIdentifier(name, "font", context.packageName)
        val font = if (id == 0) fallback
        else runCatching { context.resources.getFont(id) }.getOrDefault(fallback)
        cache[name] = font
        return font
    }

    private val cache = mutableMapOf<String, Typeface>()
}
