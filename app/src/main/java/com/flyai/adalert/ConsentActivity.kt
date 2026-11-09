package com.flyai.adalert

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 연결 동의 (피그마 v2 · S02 · 1037:38) — 어르신이 실제로 "예"라고 답하는 유일한 화면.
 *
 * 앞뒤 화면이 다 설명이라면 여기는 **결정**이다. 좋은 점(연결하면 도움을 받는다)과
 * 보지 않는 것(화면·메시지·비밀번호)을 한 화면에 나란히 둔다. 하나만 보여 주면
 * 설득이지 동의가 아니다.
 *
 * 시안: 파란 카드(#E8F0FF r16 · 체크 44 · 제목 17 Bold · 설명 15.5/22 두 줄) 위,
 * 회색 목록(#F5F7FA r16 · 아이콘 24 · 15.5/22 세 줄) 아래. 버튼 아래에는 아무것도 없다.
 */
class ConsentActivity : Activity() {

    /** 시안 List/Private 1362:16 — 줄 간격 44 (아이콘 24 · 위아래 10) */
    private val privates = listOf(
        R.drawable.ic_s02_priv_screen to "보고 있는 화면과 사진",
        R.drawable.ic_s02_priv_message to "메시지와 통화 내용",
        R.drawable.ic_s02_priv_lock to "비밀번호와 결제 정보",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_step)
        Insets.apply(this)

        Step.bind(
            this,
            title = "연결 동의",
            step = "어르신 3/4",
            heading = "가족과 연결하고\n보호 설정을 적용할까요?",
            sub = "언제든 연결을 해제하거나 설정을 바꿀 수 있어요",
            notice = "",
            button = "동의하고 설정 적용하기",
            eyebrow = "가족이 보낸 요청이에요",
        ) { startActivity(Intent(this, PermissionActivity::class.java)) }
        Step.senior(this)

        val body = findViewById<LinearLayout>(R.id.body)
        // 시안: 부제 끝 275 → 파란 카드 300
        (body.layoutParams as LinearLayout.LayoutParams).topMargin = dp(25)

        body.addView(goodBox())
        // 시안: 파란 카드 끝 410 → 라벨 430
        body.addView(Step.gap(this, 20))
        body.addView(Step.label(this, "공유하지 않는 정보", size = 15f))
        body.addView(privateBox())

        Family.profiles { all ->
            val name = all.firstOrNull { !it.isSenior }?.name
            if (name != null) runOnUiThread {
                findViewById<TextView>(R.id.text_eyebrow).text = "${name}님이 보낸 요청이에요"
                findViewById<TextView>(R.id.text_heading).text =
                    "${name}님과 연결하고\n보호 설정을 적용할까요?"
                good.text = "위험한 광고나 설치가 생기면\n${name}님에게 최소 정보만 알려요"
            }
        }
    }

    private lateinit var good: TextView

    /** 시안 Card/Consent 1362:9 — #E8F0FF r16 · 체크 44 (x=20, y=15) · 글 x=76 */
    private fun goodBox() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = Look.box(this@ConsentActivity, Look.MINT_TINT, null, 16)
        setPadding(dp(20), dp(15), dp(20), dp(15))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        addView(ImageView(this@ConsentActivity).apply {
            setImageResource(R.drawable.ic_s02_success)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                .apply { rightMargin = dp(12) }
        })
        addView(LinearLayout(this@ConsentActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
            addView(TextView(this@ConsentActivity).apply {
                text = "연결하면 도움을 받을 수 있어요"
                textSize = 17f
                letterSpacing = -0.015f
                includeFontPadding = false
                typeface = Look.bold(this@ConsentActivity)
                setTextColor(Look.color(Look.INK))
                setPadding(0, dp(7), 0, 0)
            })
            good = TextView(this@ConsentActivity).apply {
                text = "위험한 광고나 설치가 생기면\n가족에게 최소 정보만 알려요"
                textSize = 15.5f
                letterSpacing = -0.015f
                includeFontPadding = false
                setLineSpacing(dp(2).toFloat(), 1f)
                setTextColor(Look.color(Look.INK_SOFT))
                setPadding(0, dp(4), 0, 0)
            }
            addView(good)
        })
    }

    /** 시안 List/Private 1362:16 — #F5F7FA r16 · 안쪽 20/10 · 줄 간격 44 */
    private fun privateBox() = Step.card(this).apply {
        setPadding(dp(20), dp(10), dp(20), dp(10))
        privates.forEach { (icon, text) ->
            addView(
                Step.line(
                    this@ConsentActivity, icon, text, Look.INK_SOFT,
                    iconSize = 24, textSize = 15.5f, textStart = 40
                ).apply { setPadding(0, dp(10), 0, dp(10)) }
            )
        }
    }

    private fun dp(v: Int) = Look.dp(this, v)
}
