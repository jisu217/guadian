package com.flyai.adalert

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 보호 정책 설정 (피그마 v2 · P02 · 1036:49) — 보호자 네 걸음 중 둘째.
 *
 * 위험도 세 단계에 무엇이 일어나는지 **보여 주기만** 하는 화면이다. 시안에도 스위치나
 * 라디오가 없고 오른쪽 알약(허용/주의/차단)만 있다 — 등급별 대응은 앱이 정한 값이고
 * (`spec.md`) 보호자가 고르는 것이 아니다. 여기서 고르게 만들면 "차단을 껐다"는
 * 상태가 생기는데, 그 상태를 지키려면 어르신 폰의 판정까지 갈라져야 한다.
 *
 * 그래서 이 화면의 역할은 하나 — 다음 화면에서 알림을 정하기 전에
 * **무엇이 자동으로 막히는지 먼저 알리기**.
 */
class PolicyActivity : Activity() {

    /** 시안 1377:18 — 주의 알약의 글자색. WARN_INK(#A35A00)보다 한 단계 진하다 */
    private companion object { const val CAUTION_INK = "#8A4A00" }

    private class Grade(
        val name: String, val desc: String,
        val chip: String, val ink: String, val tint: String,
    )

    private val grades = listOf(
        Grade("일반 광고", "광고 표시 후 그대로 허용", "허용", Look.MINT, Look.MINT_TINT),
        Grade("주의 광고", "이동하기 전에 한 번 확인", "주의", CAUTION_INK, Look.WARN_TINT),
        Grade("차단 광고", "자동으로 차단하고 알려드림", "차단", Look.DANGER, Look.DANGER_TINT),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_step)
        Insets.apply(this)

        Step.bind(
            this,
            title = "보호 정책 설정",
            step = "보호자 2/4",
            eyebrow = "위험 수준별 대응을 정해요",
            heading = "위험도에 따라\n보호 방식을 정해요",
            sub = "안심폰 권장 설정이 미리 선택되어 있어요",
            notice = "",
            button = "알림과 접근성 설정",
        ) {
            startActivity(Intent(this, NotifyActivity::class.java))
        }
        // 시안 1377:8 부제 y=244 → 카드 y=290
        (findViewById<TextView>(R.id.text_sub).layoutParams as LinearLayout.LayoutParams)
            .topMargin = Look.dp(this, 10)
        (findViewById<LinearLayout>(R.id.body).layoutParams as LinearLayout.LayoutParams)
            .topMargin = Look.dp(this, 24)

        val body = findViewById<LinearLayout>(R.id.body)
        body.addView(Step.card(this).apply {
            grades.forEachIndexed { i, g ->
                if (i > 0) addView(Step.divider(this@PolicyActivity))
                addView(
                    Step.row(
                        this@PolicyActivity, 0, "", Look.CARD, Look.INK, g.name, g.desc,
                        right = chip(g), dot = false
                    )
                )
            }
        })
    }

    /**
     * 시안 Chip/State — 높이 24 · r12 · 12 Bold · 안쪽 가로 10.
     * 위아래는 5가 아니라 3이다 — 12 글자의 자연 행높이가 17.4이라
     * 5를 두면 알약이 28이 된다(실측). 3이면 시안과 같은 24
     */
    private fun chip(g: Grade) = Step.badge(this, g.chip, g.ink, g.tint).apply {
        background = Look.pill(this@PolicyActivity, g.tint, 12)
        setPadding(dp(10), dp(3), dp(10), dp(3))
    }

    private fun dp(v: Int) = Look.dp(this, v)
}
