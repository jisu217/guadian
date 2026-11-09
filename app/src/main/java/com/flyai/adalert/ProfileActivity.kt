package com.flyai.adalert

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 이 폰의 주인 선택. 프로필이 하나도 없으면 아래에서 먼저 생성.
 *
 * 선택 후 재질의 없음 ([Family.select]가 기억). 어르신 폰에서도 이 화면을 한 번은
 * 거치므로 카드를 크게 — 피그마 02(역할 선택)·A01U의 선택 카드 규격:
 * 카드 #F5F7FA r16 · 안쪽 20 · 사이 12 · 고른 카드는 테두리 2 #1F63E0 + 바탕 #E8F0FF.
 */
class ProfileActivity : Activity() {

    private lateinit var list: LinearLayout

    /** 새로 만들 프로필의 역할. 기본 어르신 — 이 앱을 쓰는 폰이 대개 그쪽 */
    private var newIsSenior = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)
        Insets.apply(this)
        list = findViewById(R.id.list_profiles)

        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.label_new).typeface = Look.medium(this)
        findViewById<Button>(R.id.btn_add).apply {
            typeface = Look.bold(this@ProfileActivity)
            setOnClickListener { create() }
        }
        findViewById<TextView>(R.id.text_account).text =
            Family.code?.let { "가족 아이디 $it" } ?: "가족에 연결되어 있지 않아요"

        roles()
        load()
    }

    /** 어르신 / 보호자 두 알약. 고른 쪽만 파랑 (DS: 52 · r12 · 15 Bold · 안 고른 쪽 흰 바탕 + 1px #E6E9EE) */
    private fun roles() {
        val row = findViewById<LinearLayout>(R.id.row_role)
        row.removeAllViews()
        listOf(true to "어르신", false to "보호자").forEach { (senior, label) ->
            row.addView(TextView(this).apply {
                val on = newIsSenior == senior
                text = label
                textSize = 15f
                gravity = Gravity.CENTER
                letterSpacing = -0.015f
                includeFontPadding = false
                typeface = Look.bold(this@ProfileActivity)
                setTextColor(Look.color(if (on) Look.ON_MINT else Look.INK_BODY))
                background = Look.box(
                    this@ProfileActivity,
                    if (on) Look.MINT else Look.BG,
                    if (on) null else Look.LINE, Look.RADIUS_BUTTON, 1f
                )
                layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                    if (senior) rightMargin = dp(10)
                }
                isClickable = true
                setOnClickListener { newIsSenior = senior; roles() }
            })
        }
    }

    private fun load() {
        list.removeAllViews()
        list.addView(hint("불러오는 중…"))
        Family.profiles { profiles ->
            runOnUiThread {
                list.removeAllViews()
                if (profiles.isEmpty()) {
                    list.addView(hint("아직 프로필이 없어요.\n아래에서 먼저 만들어 주세요."))
                    return@runOnUiThread
                }
                val me = Family.current(this)
                profiles.forEach { p -> list.addView(card(p, p.id == me?.id)) }
            }
        }
    }

    private fun create() {
        val name = findViewById<EditText>(R.id.input_name).text.toString().trim()
        val msg = findViewById<TextView>(R.id.text_message)
        if (name.isEmpty()) {
            msg.text = "이름을 적어 주세요."
            return
        }
        msg.text = "만드는 중…"
        Family.addProfile(name, if (newIsSenior) Family.ROLE_SENIOR else Family.ROLE_GUARDIAN) { p ->
            runOnUiThread {
                msg.text = if (p == null) "만들지 못했어요. 인터넷을 확인해 주세요." else ""
                findViewById<EditText>(R.id.input_name).setText("")
                load()
            }
        }
    }

    /**
     * 프로필 한 장 — 선택 카드 규격. 지금 이 폰이 쓰는 프로필은
     * 테두리 2 #1F63E0 + 바탕 #E8F0FF, 나머지는 #F5F7FA 테두리 없음.
     */
    private fun card(p: Family.Profile, picked: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = Look.box(
            this@ProfileActivity, if (picked) Look.MINT_TINT else Look.CARD,
            if (picked) Look.MINT else null, Look.RADIUS_CARD, 2f
        )
        setPadding(dp(20), dp(18), dp(20), dp(18))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) }
        isClickable = true
        setOnClickListener {
            Family.select(this@ProfileActivity, p)
            startActivity(
                Intent(
                    this@ProfileActivity,
                    if (p.isSenior) SeniorHomeActivity::class.java
                    else GuardianHomeActivity::class.java
                )
            )
            finish()
        }

        // 이니셜 원 44 — 고른 카드는 바탕이 연파랑이라 원을 흰색으로
        addView(Home.avatar(this@ProfileActivity, p.name.take(1), 44, 17f).apply {
            background = Look.dot(if (picked) Look.BG else Look.MINT_TINT)
            (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(14)
        })
        addView(LinearLayout(this@ProfileActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@ProfileActivity).apply {
                text = p.name
                textSize = 16.5f
                letterSpacing = -0.015f
                includeFontPadding = false
                typeface = Look.bold(this@ProfileActivity)
                setTextColor(Look.color(Look.INK))
            })
            addView(TextView(this@ProfileActivity).apply {
                text = if (p.isSenior) "어르신 · 이 폰으로 광고를 봐요" else "보호자 · 위험을 전해 받아요"
                textSize = 13.5f
                letterSpacing = -0.015f
                includeFontPadding = false
                setTextColor(Look.color(Look.INK_SOFT))
                setPadding(0, dp(4), 0, 0)
            })
        })
        if (picked) addView(TextView(this@ProfileActivity).apply {
            text = "쓰는 중"
            textSize = 12f
            gravity = Gravity.CENTER
            letterSpacing = -0.015f
            includeFontPadding = false
            typeface = Look.bold(this@ProfileActivity)
            setTextColor(Look.color(Look.MINT))
            background = Look.pill(this@ProfileActivity, Look.BG, 999)
            // 시안 Chip/State는 「연결됨」이 54(글자 34 + 좌우 10)다. 우리 글자는 같은
            // 12 Bold라도 36으로 넓어 좌우 10을 주면 알약이 52.7에 머물러 여백이 8.3까지
            // 눌린다 — 글자가 가장자리에 닿아 잘려 보인다. 12를 주면 여백이 시안의 10이 된다
            // 알약을 24로 못 박아 두고 위아래 5를 주면 글자가 25.5를 요구해 **받침이 잘린다**.
            // 12 글자의 자연 행높이가 17.4라 3씩이면 23.5로 딱 들어간다
            setPadding(dp(12), dp(3), dp(12), dp(3))
        })
    }

    private fun hint(t: String): View = TextView(this).apply {
        text = t
        textSize = 14f
        gravity = Gravity.CENTER
        includeFontPadding = false
        setLineSpacing(dp(4).toFloat(), 1f)
        setTextColor(Look.color(Look.INK_MUTED))
        setPadding(0, dp(20), 0, dp(20))
    }

    private fun dp(v: Int) = Look.dp(this, v)
}
