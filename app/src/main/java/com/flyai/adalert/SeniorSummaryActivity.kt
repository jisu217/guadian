package com.flyai.adalert

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 어르신 안심 요약 (피그마 11U · 1084:2008) — 이번 주(최근 7일) 한 장 요약.
 *
 * 보호자 주간 리포트(11G)와 같은 기록, **숫자 계산 없음.** 어르신에게 필요한 것은
 * "있었지만 다 지나갔다"는 한 마디 — 줄마다 체크 + 문장. 자세한 것은 자녀 확인 안내,
 * '확인 완료' 한 번에 홈 복귀.
 *
 * 진입: 어르신 홈 '이번 주 보호 기록 보기' · 탭바 '안심 요약'.
 *
 * 피그마 값: 앱바 뒤로 44 · 제목 20 Bold / 제목 26 Bold(1.34) · 부제 17 Regular #525C6E /
 * 파란 카드 #E8F0FF r18 · 줄 = 체크 28 + 17 Bold + 15.5 Regular / 안내 카드 #F5F7FA r18 · 16 Regular /
 * 버튼 66 r14 19 Bold.
 */
class SeniorSummaryActivity : Activity() {

    companion object {
        /** 어르신 탭바 (피그마 v2 H1·E01) — 홈·보호기록·내 정보 */
        val TABS = listOf("홈", "보호기록", "내 정보")

        /** 탭 클릭 시 이동 대상. [here]: 현재 화면의 자리 */
        fun tabs(a: Activity, here: Int) = Home.tabs(a, here, TABS) { i ->
            Home.open(
                a, when (i) {
                    0 -> SeniorHomeActivity::class.java
                    1 -> SeniorSummaryActivity::class.java
                    else -> MeActivity::class.java
                }
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        Insets.apply(this)
        header()
        tabs(this, 1)
    }

    override fun onResume() {
        super.onResume()
        draw(SeniorStats.Tally(0, 0))
        Family.loadEvents(300) { all ->
            val week = SeniorStats.tally(all, SeniorStats.weekStart())
            runOnUiThread { draw(week) }
        }
    }

    /** 앱바 — 뒤로 화살표(x7) + '이번 주 안심 요약'(x52 · 20 Bold) */
    private fun header() {
        val header = findViewById<LinearLayout>(R.id.header)
        header.removeAllViews()
        // 이 칸의 안쪽 여백은 홈 머리글에 맞춘 24다. 앱바에서는 뒤로 화살표가 x=7에
        // 서야 하는데, 음수 여백으로 끌어내면 44 상자가 24에서 **잘려** 27만 남는다
        // (실측). 어르신이 가장 자주 누르는 자리라 상자를 온전히 두고 여백을 7로 옮긴다
        header.setPadding(dp(7), header.paddingTop, dp(24), header.paddingBottom)
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(this@SeniorSummaryActivity).apply {
                setImageResource(R.drawable.ic_back)
                contentDescription = "뒤로"
                isClickable = true
                setOnClickListener { finish() }
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            })
            addView(TextView(this@SeniorSummaryActivity).apply {
                text = "이번 주 안심 요약"
                textSize = 20f
                letterSpacing = -0.015f
                includeFontPadding = false
                typeface = Look.bold(this@SeniorSummaryActivity)
                setTextColor(Look.color(Look.INK))
                setPadding(dp(1), 0, 0, 0)
            })
        })
    }

    private fun draw(week: SeniorStats.Tally) {
        val body = findViewById<LinearLayout>(R.id.body)
        body.removeAllViews()
        val ads = SeniorStats.adsThisWeek(this)
        val quiet = week.total == 0

        // 시안 1051:213 맨 위 알약 — 이번 주를 한 줄로 요약한다
        body.addView(TextView(this).apply {
            text = if (quiet) "확인한 일 없음 · 모두 안전"
            else "확인한 일 ${week.total}건 · 모두 안전"
            textSize = 13f
            gravity = Gravity.CENTER
            letterSpacing = -0.015f
            includeFontPadding = false
            typeface = Look.bold(this@SeniorSummaryActivity)
            setTextColor(Look.color(Look.MINT))
            background = Look.pill(this@SeniorSummaryActivity, Look.MINT_TINT, 6)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) }
        })

        body.addView(TextView(this).apply {
            text = if (quiet) "이번 주는 조용했어요"
            else "의심스러운 이동 ${week.total}건을 확인했어요"
            textSize = 26f
            letterSpacing = -0.015f
            includeFontPadding = false
            setLineSpacing(dp(8).toFloat(), 1f)
            typeface = Look.bold(this@SeniorSummaryActivity)
            setTextColor(Look.color(Look.INK))
            setPadding(0, dp(14), 0, 0)
        })
        body.addView(TextView(this).apply {
            text = if (quiet) "위험한 일이 하나도 없었어요"
            else "모두 원래 화면으로 안전하게 돌아왔어요"
            textSize = 16f
            letterSpacing = -0.015f
            includeFontPadding = false
            setLineSpacing(dp(6).toFloat(), 1f)
            setTextColor(Look.color(Look.INK_SOFT))
            setPadding(0, dp(12), 0, dp(28))
        })

        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Look.pill(this@SeniorSummaryActivity, Look.BRAND_100, 18)
            setPadding(dp(20), dp(23), dp(20), dp(23))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(24) }
            addView(row(
                if (week.apk == 0) "위험한 설치가 없었어요" else "위험한 설치 ${week.apk}건을 막았어요",
                "설치된 위험 앱은 없어요"
            ))
            addView(row(
                if (week.redirected == 0) "위험한 곳으로 갈 뻔한 일 없음"
                else "위험한 곳으로 갈 뻔한 일 ${week.redirected}번",
                if (week.redirected == 0) "이번 주에는 한 번도 없었어요"
                else "모두 원래 화면으로 안전하게 돌아왔어요",
                top = 20
            ))
            addView(row(
                "보호자 확인이 필요한 일이 없어요",
                if (ads == 0) "지금 바로 해야 할 일은 없어요"
                else "광고는 ${ads}번 알려드렸어요",
                top = 20
            ))
        })

        body.addView(guardianNote())
        body.addView(Look.seniorButton(this, "확인 완료") { done() })
    }

    /** 시안 1051:213 — 회색 카드 한 장. 보호자 이름을 알면 이름으로 적는다 */
    private fun guardianNote(): TextView {
        val note = TextView(this).apply {
            text = "자세한 숫자와 기록은\n가족이 확인하고 있어요."
            textSize = 16f
            letterSpacing = -0.015f
            includeFontPadding = false
            setLineSpacing(dp(6).toFloat(), 1f)
            setTextColor(Look.color(Look.INK_SOFT))
            background = Look.pill(this@SeniorSummaryActivity, Look.CARD, 18)
            setPadding(dp(20), dp(20), dp(20), dp(20))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(36) }
        }
        Family.profiles { all ->
            val name = all.firstOrNull { !it.isSenior }?.name
            if (name != null) runOnUiThread {
                note.text = "자세한 숫자와 기록은\n${name}님이 확인하고 있어요."
            }
        }
        return note
    }

    /** 파란 카드의 한 줄 — 체크 28 · 제목 17 Bold · 설명 15.5 Regular */
    private fun row(title: String, desc: String, top: Int = 0) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(top), 0, 0)
        addView(ImageView(this@SeniorSummaryActivity).apply {
            setImageResource(R.drawable.ic_success_28)
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
                .apply { rightMargin = dp(16); topMargin = dp(2) }
        })
        addView(LinearLayout(this@SeniorSummaryActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@SeniorSummaryActivity).apply {
                text = title
                textSize = 17f
                letterSpacing = -0.015f
                includeFontPadding = false
                typeface = Look.bold(this@SeniorSummaryActivity)
                setTextColor(Look.color(Look.INK))
            })
            addView(TextView(this@SeniorSummaryActivity).apply {
                text = desc
                textSize = 15.5f
                letterSpacing = -0.015f
                includeFontPadding = false
                setLineSpacing(0f, 1.45f)
                setTextColor(Look.color(Look.INK_SOFT))
                setPadding(0, dp(4), 0, 0)
            })
        })
    }

    /** '확인 완료' — 홈 복귀. 뒤에 홈이 이미 있으면 그 위 화면만 제거 */
    private fun done() {
        startActivity(
            Intent(this, SeniorHomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }

    private fun dp(v: Int) = Look.dp(this, v)
}
