package com.flyai.adalert

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout

/**
 * 알림과 접근성 (피그마 v2 · P03 · 1036:95) — 보호자 네 걸음 중 셋째.
 *
 * 시안은 두 구역이다 — '알림'(한 줄) · '접근성과 이동 보호'(세 줄). 네 스위치 모두 켜진 상태.
 *
 * ## 스위치가 실제로 하는 일
 * 값은 이 폰의 `settings` 저장소에 적힌다. 그중 지금 동작까지 이어지는 것은 **투터치**뿐이고
 * ([AdDetectService.twoTouchOn]), 큰 글씨·소리·즉시 알림은 아직 읽는 쪽이 없다.
 * 그래도 적어 두는 이유는, 나중에 읽는 쪽이 생겼을 때 보호자가 고른 값이 남아 있어야 해서다.
 * 켜진 척하는 화면을 만들지 않으려고 "(준비 중)" 같은 덧말을 붙였던 적이 있는데,
 * 시안에 없는 글자가 늘 붙어 있게 되어 되돌렸다.
 *
 * 투터치는 여기가 **보호자 폰**이라는 점이 걸린다 — 실제로 그 값을 쓰는 것은 어르신 폰이다.
 * 그래서 가족 문서에도 함께 올린다 ([Family.saveTwoTouch]). 연결 전이라면 이 폰에만 남고,
 * 연결된 뒤 이 화면에서 다시 저장하면 그때 넘어간다.
 */
class NotifyActivity : Activity() {

    private companion object {
        const val PREFS = "settings"
        const val KEY_ALERT = "notify_block"
        const val KEY_BIG = "bigtext"
        const val KEY_SPEAK = "speak"
        const val KEY_TWOTOUCH = "twotouch"
    }

    private fun get(key: String) =
        getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(key, true)

    private fun put(key: String, on: Boolean) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(key, on).apply()
        // 투터치를 쓰는 것은 어르신 폰이다. 이 폰에만 적으면 아무 일도 일어나지 않는다
        if (key == KEY_TWOTOUCH) Family.saveTwoTouch(this, on)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_step)
        Insets.apply(this)

        Step.bind(
            this,
            title = "알림과 접근성",
            step = "보호자 3/4",
            heading = "꼭 필요한 알림과\n쉬운 화면을 선택해요",
            sub = "",
            notice = "",
            button = "설정 저장하고 계속",
        ) {
            startActivity(Intent(this, ShareActivity::class.java))
        }

        val body = findViewById<LinearLayout>(R.id.body)
        // 시안 1364:6 제목 y=156(2줄 → 220) → 1364:7 '알림' y=259
        (body.layoutParams as LinearLayout.LayoutParams).topMargin = Look.dp(this, 39)

        body.addView(Step.label(this, "알림"))
        body.addView(Step.card(this).apply {
            addView(toggleRow("차단 즉시 알림", "광고·설치 차단 시 바로 알림", KEY_ALERT))
        })

        // 시안: 카드 y=365 끝 → '접근성과 이동 보호' y=376
        body.addView(Step.gap(this, 11))
        body.addView(Step.label(this, "접근성과 이동 보호"))
        body.addView(Step.card(this).apply {
            addView(toggleRow("큰 글씨와 쉬운 안내", "본문과 버튼을 더 크게 표시해요", KEY_BIG))
            addView(Step.divider(this@NotifyActivity))
            addView(toggleRow("소리로 함께 알리기", "중요 경고를 소리로 읽어줘요", KEY_SPEAK))
            addView(Step.divider(this@NotifyActivity))
            addView(toggleRow("투터치 이동 확인", "두 번 눌러야 광고로 이동해요", KEY_TWOTOUCH))
        })
    }

    /** 시안 List/Toggle 한 줄 — 왼쪽 동그라미 없음, 오른쪽 스위치 50×30 */
    private fun toggleRow(title: String, desc: String, key: String) = Step.row(
        this, 0, "", Look.CARD, Look.INK, title, desc,
        right = Step.toggle(this, get(key)) { put(key, it) },
        dot = false
    )
}
