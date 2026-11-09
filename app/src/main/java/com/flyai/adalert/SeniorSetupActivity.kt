package com.flyai.adalert

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 준비된 보호 설정 (피그마 v2 · S01 · 1037:5) — 코드를 넣은 뒤 어르신이 보는 첫 화면.
 *
 * 보호자가 네 걸음으로 정해 둔 것을 여기서 확인만. 어르신이 할 일은 읽고 넘기기뿐 —
 * 고를 것도 적을 것도 없다. 그래서 진행 바의 걸음도 「어르신 2 / 4」다: 코드 입력(S00)이
 * 1, 이 확인이 2, 동의(S02)가 3, 권한(S03)이 4.
 *
 * 어르신 화면은 글자가 한 단계 크다 — 라벨 15 · 제목 26 · 줄 18/15 · 버튼 66.
 * 카드 줄마다 파란 체크(28)가 붙고 구분선은 없다.
 */
class SeniorSetupActivity : Activity() {

    private class Line(val title: String, val desc: String)

    private val lines = listOf(
        Line("위험한 설치는 자동 차단", "피해 가능성이 높은 행동을 막아요"),
        Line("의심 링크는 먼저 확인", "이동하기 전에 물어봐요"),
        Line("큰 글씨와 쉬운 안내", "버튼과 문구를 알아보기 쉽게 표시"),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_step)
        Insets.apply(this)

        Step.bind(
            this,
            title = "보호 설정 확인",
            step = "어르신 2/4",
            heading = "휴대폰 보호 설정이\n준비되어 있어요",
            sub = "복잡한 입력 없이 아래 내용만 확인하면 돼요",
            notice = "보호자는 위험 등급·결과·시각만 볼 수 있어요.",
            button = "설정 내용 확인하기",
            eyebrow = "가족이 보호 설정을 준비했어요",
        ) { startActivity(Intent(this, ConsentActivity::class.java)) }

        Step.senior(this)

        val card = Step.card(this, senior = true)
        lines.forEach {
            card.addView(
                Step.row(
                    this, R.drawable.ic_s01_success, "", Look.MINT, Look.ON_MINT,
                    it.title, it.desc, senior = true
                )
            )
        }
        findViewById<LinearLayout>(R.id.body).addView(card)

        // 시안 1200:99 — 안내 카드가 버튼 위가 아니라 **목록 바로 아래**(573)에 붙는다.
        // [Step]의 기본 자리는 버튼 위다: 긴 화면에서 스크롤 끝에 두면 윗변만 보이기 때문.
        // 이 화면은 목록이 546에서 끝나 아래 자리도 접히지 않으므로 시안 자리로 옮긴다.
        // 왼쪽 안쪽 여백 40도 시안 값이다 (1200:100 x=62 · 카드 x=22 기준).
        val note = findViewById<TextView>(R.id.text_notice)
        (note.parent as ViewGroup).removeView(note)
        findViewById<LinearLayout>(R.id.scroll_content).addView(note)
        (note.layoutParams as LinearLayout.LayoutParams).topMargin = Look.dp(this, 27)
        note.setPadding(Look.dp(this, 40), note.paddingTop, note.paddingEnd, note.paddingBottom)

        // 준비한 사람을 이름으로 표시 — "가족"보다 "한민수님"이 더 안심된다
        Family.profiles { all ->
            val name = all.firstOrNull { !it.isSenior }?.name
            if (name != null) runOnUiThread {
                findViewById<TextView>(R.id.text_eyebrow).text = "${name}님이 보호 설정을 준비했어요"
            }
        }
    }
}
