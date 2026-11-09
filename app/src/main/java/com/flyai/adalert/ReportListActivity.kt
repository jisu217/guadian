package com.flyai.adalert

import android.app.Activity
import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 안심 요약 목록 (피그마 v2 · REPORT-LIST · 1401:2) — 아래 탭 「안심요약」이 여는 화면.
 *
 * 주간 리포트는 매주 새로 쌓이는데 v1에는 **이번 주로 들어가는 문 하나뿐**이라
 * 지난주를 다시 볼 길이 없었다. 이 화면이 그 목록이고, 줄을 누르면 그 주의
 * 리포트([ReportActivity])가 열린다.
 *
 * 줄은 네 주치만 둔다 — 상세 분석의 추이 막대도 4주라 세는 범위를 맞췄다
 * ([ReportStats.fourWeeks]).
 */
class ReportListActivity : Activity() {

    private companion object {
        /** 시안 1401:2 — 목록에 두는 주 수 */
        const val WEEKS = 4

        /** 하루의 밀리초 — 날짜 차이를 주 수로 바꿀 때만 쓴다 */
        const val DAY_MS = 86_400_000L
    }

    private val ymd = SimpleDateFormat("yyyy.MM.dd", Locale.KOREA)
    private val md = SimpleDateFormat("MM.dd", Locale.KOREA)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_report_list)
        Insets.apply(this)

        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }

        findViewById<LinearLayout>(R.id.row_pick).setOnClickListener { pickDate() }

        val list = findViewById<LinearLayout>(R.id.list_weeks)
        repeat(WEEKS) { i -> list.addView(weekRow(i)) }

        Home.tabs(this, 2, Home.TABS_GUARDIAN) { tab ->
            Home.open(
                this, when (tab) {
                    0 -> GuardianHomeActivity::class.java
                    1 -> EventListActivity::class.java
                    else -> MeActivity::class.java
                }
            )
        }
    }

    /**
     * 한 주 (시안 1401:2 — 92 · r16 · 제목 16.5 Bold · 설명 13.5 · 오른쪽 꺾쇠).
     * 이번 주만 파란 테두리와 연한 파란 바탕이다.
     */
    private fun weekRow(weeksAgo: Int) = LinearLayout(this).apply {
        val now = weeksAgo == 0
        val from = ReportStats.weekStart(weeksAgo)
        val to = Calendar.getInstance().apply {
            time = from; add(Calendar.DAY_OF_YEAR, 6)
        }.time

        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background =
            if (now) Look.box(this@ReportListActivity, Look.MINT_TINT, Look.MINT, 16, 1.5f)
            else Look.box(this@ReportListActivity, Look.CARD, null, 16)
        setPadding(dp(20), dp(20), dp(20), dp(20))
        isClickable = true
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) }
        setOnClickListener {
            startActivity(
                Intent(this@ReportListActivity, ReportActivity::class.java)
                    .putExtra(ReportActivity.EXTRA_WEEK, weeksAgo)
            )
        }

        addView(LinearLayout(this@ReportListActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@ReportListActivity).apply {
                text = buildString {
                    when (weeksAgo) {
                        0 -> append("이번 주 · ")
                        1 -> append("지난 주 · ")
                    }
                    append(range(from, to))
                }
                textSize = 16.5f
                letterSpacing = -0.015f
                includeFontPadding = false
                typeface = Look.bold(this@ReportListActivity)
                setTextColor(Look.color(Look.INK))
            })
            addView(TextView(this@ReportListActivity).apply {
                text = "주간 안전 리포트"
                textSize = 13.5f
                letterSpacing = -0.015f
                includeFontPadding = false
                setTextColor(Look.color(Look.INK_SOFT))
                setPadding(0, dp(6), 0, 0)
            })
        })
        addView(ImageView(this@ReportListActivity).apply {
            setImageResource(R.drawable.ic_h2_chev)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
        })
    }

    /**
     * 날짜로 찾기 (시안 1527:2).
     *
     * 목록은 네 주치뿐이라 그보다 오래된 주는 여기로만 닿는다. 고른 날이 **속한 주**로
     * 가는 것이지 그 날 하루를 보는 것이 아니다 — 리포트의 단위가 주이기 때문이다.
     *
     * [ReportStats.weekStart]의 한 주는 달력의 월~일이 아니라 **오늘까지의 7일**이다.
     * 그래서 며칠 전인지만 세어 7로 나누면 그대로 몇 주 전인지가 된다.
     * 앞날은 고를 수 없다 — 아직 오지 않은 주의 리포트는 빈 화면일 수밖에 없다.
     */
    private fun pickDate() {
        val now = Calendar.getInstance()
        val dialog = DatePickerDialog(
            this,
            { _, y, m, d ->
                val picked = midnight(Calendar.getInstance().apply { set(y, m, d) })
                val today = midnight(Calendar.getInstance())
                val days = ((today.timeInMillis - picked.timeInMillis) / DAY_MS).toInt()
                startActivity(
                    Intent(this, ReportActivity::class.java)
                        .putExtra(ReportActivity.EXTRA_WEEK, (days / 7).coerceAtLeast(0))
                )
            },
            now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)
        )
        dialog.datePicker.maxDate = System.currentTimeMillis()
        dialog.show()
    }

    private fun midnight(c: Calendar) = c.apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }

    /** "2026.08.17 – 08.23" — 뒷날짜는 같은 해·달이 반복되므로 월·일만 */
    private fun range(from: Date, to: Date) = "${ymd.format(from)} – ${md.format(to)}"

    private fun dp(v: Int) = Look.dp(this, v)
}
