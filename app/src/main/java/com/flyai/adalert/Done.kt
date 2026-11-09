package com.flyai.adalert

import android.app.Activity
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 「다 됐어요」 화면의 뼈대 (피그마 v2 · 보호자 가입 완료 1496:2 · P06 연결 완료 1349:114).
 *
 * 세 장이 같은 판이다 — 체크 76 아래 30에 제목, 그 아래 10에 부제, 버튼은 바닥.
 * 보호자 두 장(가입 완료 · P06)은 체크가 232에서 시작하고, 어르신 S04만 346이라
 * [top]으로 받는다. 받는 값은 그 y가 아니라 **몸통의 위 여백**이다 — 시안의 y에서
 * 상태 표시줄과 화면 위 여백을 뺀 값이라 232→186 · 346→298로 어긋나 보인다. 그 밖에 다른 것은 문구와, P06에만 붙는 상태 알약뿐이다.
 *
 * 앱바도 진행 바도 없다. 걸음이 끝난 자리라 되돌아갈 곳이 없기 때문이다 —
 * 여기서 뒤로 가면 방금 만든 계정을 다시 만드는 화면이 나온다.
 *
 * 이름이 비슷한 [DoneActivity]는 어르신 쪽 S04로, 아직 이 판을 쓰지 않는다.
 */
object Done {

    /** P06의 상태 알약 — 글자 하나에 색 두 벌 ([tint] 바탕 · [ink] 글자) */
    class Chip(val text: String, val ink: String, val tint: String)

    /**
     * 뼈대를 세우고 몸통([R.id.body])을 돌려준다.
     * 돌려주는 이유는 나중에 제목을 갈아 끼우기 위해서다 — 이름을 뒤늦게 받아 오는
     * 경우가 있어([LinkedActivity]) 자식 번호로 찾아 쓴다: 0 체크 · 1 제목 · 2 부제.
     */
    fun bind(
        a: Activity,
        title: String,
        sub: String,
        button: String,
        chip: Chip? = null,
        top: Int = 186,
        senior: Boolean = false,
        onNext: () -> Unit,
    ): LinearLayout {
        Step.bind(
            a,
            title = "",
            step = "",
            heading = "",
            sub = "",
            notice = "",
            button = button,
            onNext = onNext,
        )
        // 어르신 규격(버튼 66)은 여기서 건다. [Step.senior]가 몸통의 위 여백을 자기 값으로
        // 되돌리므로 [top]을 세우기 **전에** 불러야 한다 — 뒤에 부르면 체크가 제자리를 잃는다
        if (senior) Step.senior(a)

        // 뼈대만 빌려 쓰고 위쪽 슬롯은 전부 감춘다
        a.findViewById<View>(R.id.appbar).visibility = View.GONE
        a.findViewById<View>(R.id.progress).visibility = View.GONE
        a.findViewById<View>(R.id.text_heading).visibility = View.GONE
        a.findViewById<View>(R.id.text_sub).visibility = View.GONE
        a.findViewById<View>(R.id.content).setPadding(dp(a, 24), 0, dp(a, 24), 0)

        val body = a.findViewById<LinearLayout>(R.id.body)
        (body.layoutParams as LinearLayout.LayoutParams).topMargin = dp(a, top)
        body.gravity = Gravity.CENTER_HORIZONTAL

        body.addView(ImageView(a).apply {
            setImageResource(R.drawable.ic_p06_success)
            layoutParams = LinearLayout.LayoutParams(dp(a, 76), dp(a, 76))
        })

        body.addView(TextView(a).apply {
            text = title
            textSize = 26f
            letterSpacing = -0.015f
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = Look.bold(a)
            setTextColor(Look.color(Look.INK))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) lineHeight = sp(a, 34f)
            layoutParams = wide().apply { topMargin = dp(a, 30) }   // 시안: 체크 끝 308 → 제목 338
        })

        body.addView(TextView(a).apply {
            text = sub
            textSize = 16f
            letterSpacing = -0.015f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(Look.color(Look.INK_SOFT))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) lineHeight = sp(a, 23f)
            layoutParams = wide().apply { topMargin = dp(a, 10) }   // 시안: 제목 끝 372 → 부제 382
        })

        if (chip != null) body.addView(TextView(a).apply {
            text = chip.text
            textSize = 14f
            letterSpacing = -0.015f
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = Look.bold(a)
            setTextColor(Look.color(chip.ink))
            background = Look.pill(a, chip.tint, 17)
            setPadding(dp(a, 14), dp(a, 7), dp(a, 14), dp(a, 7))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(a, 34)
            ).apply { topMargin = dp(a, 22) }                       // 시안: 부제 끝 405 → 알약 427
        })

        return body
    }

    private fun wide() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun dp(a: Activity, v: Int) = Look.dp(a, v)

    private fun sp(a: Activity, v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, v, a.resources.displayMetrics
    ).toInt()
}
