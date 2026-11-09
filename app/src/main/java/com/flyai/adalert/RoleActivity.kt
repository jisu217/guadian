package com.flyai.adalert

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 역할 선택 (피그마 v2 · 02) — 이 휴대폰의 주인.
 *
 * 선택이 앱의 성격 결정. 자녀 폰은 기록을 **보는** 쪽, 부모 폰은 광고를 **막는** 쪽.
 * 같은 앱이지만 두 폰의 역할 중복 없음.
 *
 * 선택 표시는 피그마 v2 · 02 그대로 **카드 한 장 전체** — 바탕 #E8F0FF · 테두리 2 #1F63E0 ·
 * 파란 타일에 흰 픽토 · 오른쪽 체크 28. 안 고른 카드는 #F5F7FA · 테두리 없음 ·
 * 흰 타일에 파란 픽토 · 체크 숨김.
 */
class RoleActivity : Activity() {

    private companion object {
        // 피그마 v2 · 02 카드 값 (1358:18 고름 · 1358:27 안 고름)
        const val PICK_FILL = Look.MINT_TINT   // #E8F0FF
        const val PICK_LINE = Look.MINT        // 테두리 2 #1F63E0
        const val PICK_STROKE = 2f
        const val REST_FILL = Look.CARD        // #F5F7FA — 테두리 없음
        val REST_LINE: String? = null
        const val REST_STROKE = 0f
        const val TILE_ON = Look.MINT          // 고른 카드의 타일 #1F63E0
        const val TILE_OFF = "#FFFFFF"         // 안 고른 카드의 타일은 흰색
        const val GLYPH_ON = Look.ON_MINT
        const val GLYPH_OFF = Look.MINT    // 흰 타일 위 파란 픽토 (시안 1358:29)
        const val CARD_H = 154
        const val TILE = 56
    }

    private class Role(val value: String, val icon: Int, val name: String, val desc: String)

    // 순서·문구는 피그마 v2 · 02 그대로 (보호자가 위, 보호받는 분이 아래)
    private val roles = listOf(
        Role(
            Family.ROLE_GUARDIAN, R.drawable.ic_p0_people, "보호자로 시작하기",
            "가족의 위험 상황을 확인하고\n전화나 안내 메시지로 도와드려요"
        ),
        Role(
            Family.ROLE_SENIOR, R.drawable.ic_p0_person, "보호받는 분으로 시작하기",
            "큰 글씨와 쉬운 안내로 위험한 광고를 피하고\n가족에게 도움을 요청해요"
        ),
    )

    private var picked = Family.ROLE_GUARDIAN

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_role)
        Insets.apply(this)

        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }

        val list = findViewById<LinearLayout>(R.id.list_roles)
        roles.forEach { list.addView(card(it)) }
        draw()

        val next = findViewById<Button>(R.id.btn_next)
        next.setOnClickListener {
            // 자녀(보호자)는 다섯 걸음으로 설정 미리 생성,
            // 부모(어르신)는 그 코드 입력만 — 여기서 경로 분기.
            startActivity(
                if (picked == Family.ROLE_GUARDIAN) Intent(this, SetupActivity::class.java)
                else Intent(this, LoginActivity::class.java).putExtra("role", picked)
            )
        }
    }

    /**
     * 카드 한 장 (피그마 v2 · 02: 342×154 · r16 · 안쪽 18/16).
     *
     * **두 단**이다 — 윗줄에 [타일 56 · 이름 20 Bold · 체크 28]이 가로로 서고,
     * 설명 14/21은 그 아래 **카드 폭 전체**를 쓴다. 타일을 왼쪽에 세로로 걸치고
     * 글을 오른쪽에 몰면 설명 폭이 좁아져 시안과 줄 나눔이 달라진다(실기기에서 그랬음).
     *
     * 자식 순서는 [윗줄(0) → 설명(1)], 윗줄 안은 [타일(0) → 이름(1) → 체크(2)].
     * [draw]가 이 순서로 색·표시를 바꾼다.
     */
    private fun card(r: Role) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        minimumHeight = dp(CARD_H)
        setPadding(dp(18), dp(16), dp(18), dp(16))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(16) }
        isClickable = true
        tag = r.value
        setOnClickListener { picked = r.value; draw() }

        addView(LinearLayout(this@RoleActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            addView(FrameLayout(this@RoleActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(TILE), dp(TILE))
                    .apply { rightMargin = dp(12) }
                addView(ImageView(this@RoleActivity).apply {
                    setImageResource(r.icon)
                    layoutParams = FrameLayout.LayoutParams(dp(TILE), dp(TILE), Gravity.CENTER)
                })
            })

            addView(TextView(this@RoleActivity).apply {
                text = r.name
                textSize = 20f
                letterSpacing = -0.015f
                includeFontPadding = false
                typeface = Look.bold(this@RoleActivity)
                setTextColor(Look.color(Look.INK))
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            })

            // 시안 1358:23 Icon/Check 28 — 고른 카드에만 보인다
            addView(ImageView(this@RoleActivity).apply {
                setImageResource(R.drawable.ic_p0_check)
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
            })
        })

        addView(TextView(this@RoleActivity).apply {
            text = r.desc
            textSize = 14f
            letterSpacing = -0.015f
            includeFontPadding = false
            // 시안 행간 21 — 배수(1.5)는 글꼴 기본 줄높이에 곱해져 더 벌어진다.
            // 정확히 21sp가 되도록 lineHeight로 지정
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                lineHeight = (21 * resources.displayMetrics.scaledDensity).toInt()
            } else {
                setLineSpacing(0f, 1.2f)
            }
            setTextColor(Look.color(Look.INK_SOFT))
            setPadding(0, dp(18), 0, 0)   // 시안: 타일 아래 72 → 설명 90
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        })
    }

    /** 선택 카드만 파랑 — 바탕·테두리·타일·픽토그램 획이 한 벌로 변경 */
    private fun draw() {
        val list = findViewById<LinearLayout>(R.id.list_roles)
        for (i in 0 until list.childCount) {
            val card = list.getChildAt(i) as LinearLayout
            val on = card.tag == picked
            card.background =
                if (on) Look.box(this, PICK_FILL, PICK_LINE, Look.RADIUS_CARD, PICK_STROKE)
                else Look.box(this, REST_FILL, REST_LINE, Look.RADIUS_CARD, REST_STROKE)

            val top = card.getChildAt(0) as LinearLayout
            val tile = top.getChildAt(0) as FrameLayout
            tile.background = Look.pill(this, if (on) TILE_ON else TILE_OFF, Look.RADIUS_CARD)
            // 시안: 고른 카드는 파란 타일 + 흰 픽토, 안 고른 카드는 흰 타일 + 파란 픽토
            (tile.getChildAt(0) as ImageView).imageTintList =
                ColorStateList.valueOf(Look.color(if (on) GLYPH_ON else GLYPH_OFF))
            // 시안은 안 고른 카드(1358:27)에 체크가 **아예 없다** — 자리를 비워 두면
            // 「보호받는 분으로 시작하기」가 두 줄로 접힌다. GONE으로 폭을 돌려준다
            top.getChildAt(2).visibility = if (on) View.VISIBLE else View.GONE
        }
    }

    private fun dp(v: Int) = Look.dp(this, v)
}
