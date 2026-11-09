package com.flyai.adalert

import android.app.Activity
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 허용 목록 관리 (피그마 v2 · 13G-2 · 1234:10).
 *
 * 허용한 앱·사이트에서는 광고 표시와 이동 전 확인을 하지 않는다. **차단은 예외 없이**
 * 계속 작동한다 — 허용 목록은 "귀찮은 확인을 끄는" 목록이지 "위험을 통과시키는" 목록이 아니다.
 * 시안의 마지막 줄(파란 글씨)이 그 뜻이고, 이 화면에서 가장 중요한 한 문장이다.
 *
 * 목록은 이 폰에만 저장한다([AlertSettingsActivity.PREFS_ALLOW]).
 * 「변경 내용 저장」을 눌러야 반영되고 뒤로 가면 버린다.
 *
 * TODO: 허용 목록을 AdDetectService·판정에 반영 (현재는 표시·해제까지만).
 */
class AllowListActivity : Activity() {

    private companion object {
        const val APPS = 0
        const val SITES = 1

        /** 시안 13G-2의 예시. 미리보기(`--es screen allow`) 전용 */
        val SAMPLES = listOf(
            "배달의민족" to "앱 · 자주 쓰는 배달 서비스",
            "카카오뱅크" to "앱 · 은행 서비스",
            "news.wec.co.kr" to "뉴스 · 자주 보는 곳",
        )
    }

    private val items = linkedMapOf<String, String>()
    private var tab = APPS
    private var preview = false

    private lateinit var segment: LinearLayout
    private lateinit var listCard: LinearLayout
    private lateinit var label: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_allow_list)
        Insets.apply(this)

        items.putAll(AlertSettingsActivity.allowed(this))
        val debug = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        preview = debug && intent.getBooleanExtra("preview", false)
        if (preview && items.isEmpty()) items.putAll(SAMPLES)

        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }
        segment = findViewById(R.id.segment)
        label = findViewById(R.id.text_label)
        listCard = findViewById(R.id.list_allow)

        findViewById<TextView>(R.id.text_note).text =
            "허용한 앱과 사이트에서는 광고 표시와 이동 전 확인을 하지 않아요\n" +
            "해제하면 다음 접속부터 다시 확인이 적용돼요"

        findViewById<Button>(R.id.btn_save).apply {
            Look.mintShadow(this)
            setOnClickListener { save(); finish() }
        }
        paint()
    }

    /** 앱은 이름에 점이 없고 사이트는 점이 있다 — 저장할 때 종류를 따로 적지 않아서 */
    private fun isApp(name: String) = !name.contains('.')

    private fun shown() = items.filter { isApp(it.key) == (tab == APPS) }

    private fun paint() {
        // ── 세그먼트 (시안 — #EEF1F5 r12 · 48 · 흰 알약 r10) ─────────────────
        segment.background = Look.box(this, Look.LINE_SOFT, null, 12)
        segment.removeAllViews()
        listOf(
            "앱 " + items.count { isApp(it.key) },
            "사이트 " + items.count { !isApp(it.key) },
        ).forEachIndexed { i, text ->
            val on = tab == i
            segment.addView(TextView(this).apply {
                this.text = text
                textSize = 15f
                gravity = Gravity.CENTER
                letterSpacing = -0.015f
                includeFontPadding = false
                typeface = Look.bold(this@AllowListActivity)
                setTextColor(Look.color(if (on) Look.INK else Look.INK_SOFT))
                if (on) background = Look.box(this@AllowListActivity, Look.ON_MINT, null, 10)
                isClickable = true
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1f
                )
                setOnClickListener { tab = i; paint() }
            })
        }

        label.text = if (tab == APPS) "항상 허용한 앱" else "항상 허용한 사이트"

        // ── 목록 ─────────────────────────────────────────────────────────────
        listCard.removeAllViews()
        val rows = shown()
        if (rows.isEmpty()) {
            listCard.addView(TextView(this).apply {
                text = "아직 허용한 곳이 없어요\n위험 기록에서 「항상 허용」을 누르면 여기에 모여요"
                textSize = 13.5f
                gravity = Gravity.CENTER
                letterSpacing = -0.015f
                includeFontPadding = false
                setLineSpacing(dp(4).toFloat(), 1f)
                setTextColor(Look.color(Look.INK_SOFT))
                setPadding(0, dp(18), 0, dp(18))
            })
        } else rows.entries.forEachIndexed { i, (name, desc) ->
            if (i > 0) listCard.addView(Row.divider(this, 0))
            listCard.addView(itemRow(name, desc))
        }
        listCard.addView(addRow())
    }

    /** 시안 — 아이콘 상자 36 + 이름 15.5 Bold + 설명 13 + 오른쪽 「해제」 흰 알약 */
    private fun itemRow(name: String, desc: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(12), 0, dp(12))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        addView(ImageView(this@AllowListActivity).apply {
            setImageResource(R.drawable.ic_allow_app)
            background = Look.box(this@AllowListActivity, Look.ON_MINT, null, 10)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
                .apply { rightMargin = dp(12) }
        })
        addView(LinearLayout(this@AllowListActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@AllowListActivity).apply {
                text = name
                textSize = 15.5f
                letterSpacing = -0.015f
                includeFontPadding = false
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                typeface = Look.bold(this@AllowListActivity)
                setTextColor(Look.color(Look.INK))
            })
            if (desc.isNotEmpty()) addView(TextView(this@AllowListActivity).apply {
                text = desc
                textSize = 13f
                letterSpacing = -0.015f
                includeFontPadding = false
                setTextColor(Look.color(Look.INK_SOFT))
                setPadding(0, dp(3), 0, 0)
            })
        })
        addView(TextView(this@AllowListActivity).apply {
            text = "해제"
            textSize = 12f      // 시안 1234:36 — 알약 42x26, 글자 폭 22
            gravity = Gravity.CENTER
            letterSpacing = -0.015f
            includeFontPadding = false
            typeface = Look.bold(this@AllowListActivity)
            setTextColor(Look.color(Look.INK_SOFT))
            background = Look.box(this@AllowListActivity, Look.ON_MINT, Look.LINE, 8, 1f)
            setPadding(dp(10), dp(4), dp(10), dp(4))   // 12 글자의 자연 행높이 17.4 + 8 = 26
            isClickable = true
            setOnClickListener { items.remove(name); paint() }
        })
    }

    /**
     * 「+ 앱 추가」 (시안의 점선 줄).
     *
     * 이름을 손으로 적게 하지 않는다 — 어느 앱인지 정확히 적기 어렵고, 오타 하나면
     * 허용이 안 된 채 허용된 줄 안다. 목록은 위험 기록 쪽에서 「항상 허용」으로 채운다.
     */
    private fun addRow() = TextView(this).apply {
        text = if (tab == APPS) "+  앱 추가" else "+  사이트 추가"
        textSize = 14.5f
        gravity = Gravity.CENTER
        letterSpacing = -0.015f
        includeFontPadding = false
        typeface = Look.bold(this@AllowListActivity)
        setTextColor(Look.color(Look.MINT))
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setStroke(
                Look.dp(this@AllowListActivity, 1.5f), Look.color("#B7C6E4"),
                dp(5).toFloat(), dp(4).toFloat()
            )
        }
        isClickable = true
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
        ).apply { topMargin = dp(12); bottomMargin = dp(4) }
        setOnClickListener {
            // 이 블록의 this는 방금 만든 TextView다. 그냥 findViewById를 부르면 화면이
            // 아니라 **그 글자 상자 안**을 뒤져 null이 오고, 누르는 순간 앱이 죽는다
            this@AllowListActivity.findViewById<TextView>(R.id.text_note).text =
                "허용은 위험 기록에서 「항상 허용」을 눌러 추가해요\n" +
                "이름을 손으로 적으면 오타 하나로 허용이 되지 않아요"
        }
    }

    private fun save() {
        // 미리보기의 예시 줄은 진짜 허용이 아님 — 이 폰의 목록에 저장하지 않는다
        if (preview) return
        getSharedPreferences(AlertSettingsActivity.PREFS_ALLOW, Context.MODE_PRIVATE).edit()
            .putStringSet(
                AlertSettingsActivity.KEY_ITEMS,
                items.map { (k, v) -> k + AlertSettingsActivity.SEP + v }.toSet()
            )
            .apply()
    }

    private fun dp(v: Int) = Look.dp(this, v)
}
