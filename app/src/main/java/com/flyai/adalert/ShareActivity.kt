package com.flyai.adalert

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 정보 공유 동의 (피그마 v2 · P04 · 1036:140) — 보호자 네 걸음의 마지막.
 *
 * 이 앱에서 가장 중요한 약속을 적어 둔 곳. 부모의 휴대폰에 들어가는 앱이니
 * **무엇을 보지 않는지**를 먼저 분명히. 기록에 남는 것은 등급·유형·호스트·시각뿐
 * ([Family.logEvent]), 화면 내용·사진·검색어는 어디에도 저장 없음.
 *
 * 목록 두 벌은 시안 문구 그대로다 — 공유하는 쪽은 파란 체크 20 + 14.5 **Bold** 검정,
 * 공유하지 않는 쪽은 회색 금지 20 + 14.5 Regular 회색. 두 카드 모두 안쪽 12,
 * 줄 사이 32(줄 높이 20 + 위아래 6)라 시안의 카드 높이 120·152와 그대로 맞는다.
 *
 * 마지막 확인 줄은 **카드가 아니다** — 시안에서 바탕 없이 화면 여백(24)에서 시작한다.
 */
class ShareActivity : Activity() {

    private var agreed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_step)
        Insets.apply(this)

        Step.bind(
            this,
            title = "정보 공유 동의",
            step = "보호자 4/4",
            heading = "보호할 가족에게 설명할\n정보 범위를 확인해요",
            sub = "연결 전, 무엇이 공유되고 공유되지 않는지 확인해요",
            notice = "",
            button = "연결 링크 만들기",
            topGap = 25,          // 시안 1360:7 제목 y=165 (진행 바 라벨 끝 140)
        ) {
            if (!agreed) {
                findViewById<TextView>(R.id.text_sub).text = "위 내용을 확인했다고 표시해 주세요."
            } else startActivity(Intent(this, SignedUpActivity::class.java))
        }
        // 시안: 제목 165(2줄 → 229) → 부제 243 → 라벨 294
        (findViewById<TextView>(R.id.text_sub).layoutParams as LinearLayout.LayoutParams)
            .topMargin = dp(14)
        val body = findViewById<LinearLayout>(R.id.body)
        (body.layoutParams as LinearLayout.LayoutParams).topMargin = dp(29)

        body.addView(Step.label(this, "공유하는 안전 정보"))
        body.addView(listCard(
            R.drawable.ic_p04_check, Look.INK, bold = true,
            items = listOf(
                "사건 종류 7가지",
                "경고·취소·무시하고 진행 결과",
                "발생 시각 · 광고일 때 감지 건수",
            )
        ))

        // 시안: 카드 끝 442 → 라벨 460
        body.addView(Step.gap(this, 18))
        body.addView(Step.label(this, "공유하지 않는 개인 정보"))
        body.addView(listCard(
            R.drawable.ic_p04_ban, Look.INK_SOFT, bold = false,
            items = listOf(
                "화면 내용 · 사진",
                "메시지 · 통화 내용",
                "사이트 주소 · 앱 이름 · 검색어",
                "비밀번호 · 결제 정보",
            )
        ))

        body.addView(Step.gap(this, 22))
        body.addView(agreeRow())

        Family.profiles { all ->
            val name = all.firstOrNull { it.isSenior }?.name
            if (name != null) runOnUiThread {
                findViewById<TextView>(R.id.text_heading).text =
                    "${name}님께 설명할\n정보 범위를 확인해요"
                agreeLabel.text = "${name}님께 내용을 설명하고 사전 동의를 받았어요"
            }
        }
    }

    /** 시안 List/check · List/ban — #F5F7FA r16 · 안쪽 12 · 줄 사이 32 */
    private fun listCard(icon: Int, ink: String, bold: Boolean, items: List<String>) =
        Step.card(this).apply {
            setPadding(dp(20), dp(12), dp(20), dp(12))
            items.forEach {
                addView(
                    Step.line(this@ShareActivity, icon, it, ink, bold = bold)
                        .apply { setPadding(0, dp(6), 0, dp(6)) }
                )
            }
        }

    private lateinit var agreeLabel: TextView

    /**
     * 확인 줄 — 눌러야 다음 진행 가능. 시안 1360:34/37: 체크 22 x=24 · 글 14 Regular x=56.
     * 카드 바탕이 없어 화면 여백(24)에서 바로 시작하므로 안쪽 여백을 두지 않는다.
     */
    private fun agreeRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        isClickable = true

        val check = ImageView(this@ShareActivity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
                .apply { rightMargin = dp(10) }
        }
        agreeLabel = TextView(this@ShareActivity).apply {
            text = "보호할 가족에게 내용을 설명하고 사전 동의를 받았어요"
            textSize = 14f
            letterSpacing = -0.015f
            includeFontPadding = false
            setLineSpacing(dp(3).toFloat(), 1f)
            setTextColor(Look.color(Look.INK_SOFT))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        addView(check)
        addView(agreeLabel)

        fun paint() {
            check.setImageResource(
                if (agreed) R.drawable.ic_p04_agree_on else R.drawable.ic_p04_agree_off
            )
        }
        paint()
        setOnClickListener { agreed = !agreed; paint() }
    }

    private fun dp(v: Int) = Look.dp(this, v)
}
