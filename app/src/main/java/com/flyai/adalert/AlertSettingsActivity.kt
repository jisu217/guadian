package com.flyai.adalert

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 알림 범위와 보호 (피그마 v2 · 13G · 1039:206) — 보호자가 받을 알림과 이동 확인.
 *
 * 「보호 설정 저장」을 눌러야 반영되고 뒤로 가면 버린다 — 토글 하나 건드린 채 나갔을 때
 * 저장됐는지 헷갈리지 않도록 한 버튼으로 모았다.
 *
 * ## 값이 가족 문서로 가는 이유
 * 정하는 사람과 쓰는 폰이 다르다. 「반복 위험 알림」은 여기서 정하지만 보낼지 말지는
 * **어르신 폰**이 판단하고([Family.logEvent]), 「투터치」는 어르신 폰의 내 정보 화면에도
 * 같은 줄이 있다. 이 폰에만 저장하던 동안에는 어느 쪽도 상대의 값을 볼 수 없어, 화면은
 * 꺼져 있는데 동작은 그대로였다 ([Family.saveSettings] 참고).
 *
 * 화면을 열 때 가족 문서를 한 번 확인한다 — 어르신 폰에서 투터치를 바꿨을 수 있다.
 *
 * ## 「차단 알림」에 스위치가 없는 이유
 * 시안도 이 줄만 알약(「항상 켜짐」)이다. 고위험을 막았는데 아무에게도 알리지 않는 상태를
 * 만들 수 있으면, 그건 보호자가 끌 수 있는 설정이 아니라 앱이 하는 일을 껐다는 뜻이 된다.
 *
 * 허용 목록은 v2에서 별도 화면(13G-2)으로 나갔다 — [AllowListActivity].
 */
class AlertSettingsActivity : Activity() {

    companion object {
        const val PREFS_ALLOW = "allowlist"
        const val KEY_ITEMS = "items"
        /** 집합의 원소 한 줄: "이름<TAB>설명". 설명은 빈 값 가능 */
        const val SEP = "\t"

        /** 허용 목록 읽기 (이름 → 설명). 집합이라 저장 순서 없음 → 이름순 반환 */
        fun allowed(ctx: Context): Map<String, String> =
            ctx.getSharedPreferences(PREFS_ALLOW, Context.MODE_PRIVATE)
                .getStringSet(KEY_ITEMS, emptySet()).orEmpty()
                .associate { it.substringBefore(SEP) to it.substringAfter(SEP, "") }
                .toSortedMap()
    }

    private var repeat = true
    private var twoTouch = true

    /** 가족 문서를 확인하는 동안 떠 있던 화면. 값이 달랐으면 그때 다시 그린다 */
    private var watch: com.google.firebase.firestore.ListenerRegistration? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_alert_settings)
        Insets.apply(this)

        repeat = Family.repeatAlerts(this)
        twoTouch = Family.twoTouch(this)
        // 어르신 폰에서 투터치를 바꿨을 수 있다. 값이 달라지면 화면을 다시 그린다 —
        // 토글은 만들 때 상태가 정해지므로 나중에 고쳐 끼우는 것보다 이쪽이 단순하다
        watch = Family.watchSettings(this) { runOnUiThread { if (!isFinishing) recreate() } }

        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }

        val body = findViewById<LinearLayout>(R.id.body)

        // 시안 1039:206 — 라벨 하나에 카드 하나, 그 안에 세 줄
        body.addView(TextView(this).apply {
            text = "위험 알림과 보호 동작"
            textSize = 14f
            letterSpacing = -0.015f
            includeFontPadding = false
            typeface = Look.bold(this@AlertSettingsActivity)
            setTextColor(Look.color(Look.INK_SOFT))
            setPadding(0, 0, 0, dp(10))
        })
        body.addView(Row.card(this).apply {
            setPadding(dp(20), dp(6), dp(20), dp(6))
            addView(row("차단 알림", "차단 즉시 보호자에게 알려드려요", alwaysChip()))
            addView(Row.divider(this@AlertSettingsActivity, 0))
            // 판단 기준은 앱이 아니라 **사이트**다 ([Family.repeatVisit]) — 시안 문구를
            // 그대로 두면 보호자가 앱 단위로 동작한다고 읽는다
            addView(row(
                "반복 위험 알림", "같은 곳에서 반복되면 요약해서 알려드려요",
                Step.toggle(this@AlertSettingsActivity, repeat) { repeat = it }
            ))
            addView(Row.divider(this@AlertSettingsActivity, 0))
            addView(row(
                "투터치 이동 확인", "한 번 더 눌러야 이동해요",
                Step.toggle(this@AlertSettingsActivity, twoTouch) { twoTouch = it }
            ))
        })

        // 시안에는 없는 줄 — 허용 목록(13G-2)으로 가는 유일한 문이라 여기 둔다
        body.addView(TextView(this).apply {
            text = "허용 목록 관리 ›"
            textSize = 14.5f
            letterSpacing = -0.015f
            includeFontPadding = false
            typeface = Look.bold(this@AlertSettingsActivity)
            setTextColor(Look.color(Look.MINT))
            setPadding(dp(4), dp(20), dp(4), dp(20))
            isClickable = true
            setOnClickListener {
                startActivity(Intent(this@AlertSettingsActivity, AllowListActivity::class.java))
            }
        })

        findViewById<Button>(R.id.btn_save).apply {
            Look.mintShadow(this)
            setOnClickListener { save(); finish() }
        }
    }

    override fun onDestroy() {
        watch?.remove()
        super.onDestroy()
    }

    private fun save() {
        // 저장하면서 화면을 닫으므로, 이 순간의 스냅샷이 recreate()를 부르지 않도록 먼저 뗀다
        watch?.remove()
        watch = null
        Family.saveSettings(this, repeat, twoTouch)
    }

    /** 시안 1039:206 — 끌 수 없는 줄의 알약 (#E8F0FF · 11.5 Bold #1F63E0 · r6) */
    private fun alwaysChip() = TextView(this).apply {
        text = "항상 켜짐"
        textSize = 11.5f
        gravity = Gravity.CENTER
        letterSpacing = -0.015f
        includeFontPadding = false
        typeface = Look.bold(this@AlertSettingsActivity)
        setTextColor(Look.color(Look.MINT))
        background = Look.pill(this@AlertSettingsActivity, Look.MINT_TINT, 6)
        setPadding(dp(10), 0, dp(10), 0)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, dp(24)
        )
    }

    /** 시안 13G 줄: 제목 16 Bold · 설명 13.5 #525C6E, 오른쪽 토글 50×30 또는 알약 */
    private fun row(title: String, desc: String, right: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(14), 0, dp(14))
        addView(LinearLayout(this@AlertSettingsActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@AlertSettingsActivity).apply {
                text = title
                textSize = 16f
                letterSpacing = -0.015f
                includeFontPadding = false
                typeface = Look.bold(this@AlertSettingsActivity)
                setTextColor(Look.color(Look.INK))
            })
            addView(TextView(this@AlertSettingsActivity).apply {
                text = desc
                textSize = 13.5f
                letterSpacing = -0.015f
                setLineSpacing(dp(3).toFloat(), 1f)
                includeFontPadding = false
                setTextColor(Look.color(Look.INK_SOFT))
                setPadding(0, dp(4), 0, 0)
            })
        })
        addView(right, (right.layoutParams as? LinearLayout.LayoutParams
            ?: LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )).apply { leftMargin = dp(12) })
    }

    private fun dp(v: Int) = Look.dp(this, v)
}
