package com.flyai.adalert

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 보호할 가족 설정 (피그마 v2 · P01 · 1036:5) — 보호자가 먼저 걷는 네 걸음의 첫 화면.
 *
 * ## 연결보다 설정이 먼저인 이유
 * 어르신 폰에서 할 일 최소화. 보호자가 위험 대응·알림 범위·큰 글씨까지 미리 정해 두면
 * 어르신 쪽은 **확인·동의만**. 이 화면은 앞으로 정할 것 세 줄 미리보기.
 *
 * 시안 v2의 카드 줄에는 왼쪽 아이콘 24가 있다 — 방패 · 종 · 한글 '가'.
 * '가'만 그림이 아닌 글자라 [Step.row]의 glyph 자리를 쓰되 동그라미는 투명하게 둔다.
 */
class SetupActivity : Activity() {

    /** 동그라미 없이 글자만 놓기 위한 투명 바탕 (시안의 '가'에는 배경이 없다) */
    private companion object { const val NO_TINT = "#00000000" }

    private class Item(val icon: Int, val glyph: String, val title: String, val desc: String)

    private val items = listOf(
        Item(R.drawable.ic_p01_shield, "", "위험 대응 방식", "일반, 주의, 차단"),
        Item(R.drawable.ic_p01_bell, "", "보호자 알림 범위", "필요한 위험 정보만 전달"),
        Item(0, "가", "쉬운 화면 설정", "큰 글씨와 명확한 안내"),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        Insets.apply(this)

        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }
        Step.progress(this, "보호자 1/4")

        val list = findViewById<LinearLayout>(R.id.box_list)
        items.forEachIndexed { i, it ->
            if (i > 0) list.addView(Step.divider(this))
            list.addView(
                Step.row(
                    this, it.icon, it.glyph, NO_TINT, Look.MINT, it.title, it.desc,
                    dotSize = 24, dotGap = 16, glyphSize = 17f
                )
            )
        }

        // 어르신 이름을 알면 "보호할 가족" → 실제 이름
        Family.profiles { all ->
            val name = all.firstOrNull { it.isSenior }?.name
            if (name != null) runOnUiThread {
                findViewById<TextView>(R.id.text_heading).text =
                    "${name}님의 휴대폰을\n간단하게 준비해요"
            }
        }

        findViewById<Button>(R.id.btn_next).setOnClickListener {
            startActivity(Intent(this, PolicyActivity::class.java))
        }
    }
}
