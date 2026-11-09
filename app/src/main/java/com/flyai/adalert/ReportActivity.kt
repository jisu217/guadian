package com.flyai.adalert

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 주간 안전 리포트 (시안 11G-1 요약 · 11G-2 결과와 조치 — 한 화면 세로 스크롤).
 *
 * ## 숫자는 전부 기록에서 센 값
 * 지어낸 값 없음. 이번 주(최근 7일) 기록 집계 → 막은 건수·확인한 건수·요일별 분포,
 * 지난주 같은 기간과 비교해 개선/악화 표시. 셀 것이 없으면 없다고 표기.
 * 세는 규칙: [ReportStats].
 *
 * ## 시안과 다른 점
 * - 시안의 '92점' 안전 점수는 앱에 없는 값 → 그 자리에 spec.md 5의 3단계(안전/경고/심각)를
 *   크게 표기. 카드 색도 단계 기준(안전 파랑 · 경고 주황 · 심각 빨강).
 * - 세 번째 지표: '도움 필요' 대신 '확인 필요'. 어르신→보호자 도움 요청 경로가 이 앱에
 *   없어 영원히 0일 숫자 (09G는 반대 방향인 보호자→어르신 도움 보내기).
 * - '서비스가 도운 결과' 첫 줄: 시안 "위험한 설치가" → "위험한 설치·이동이"로 확장.
 *   막은 것에 설치 외 악성 도메인 이동도 포함.
 * - 11G-3·11G-4(판단 근거 · 예방 자료와 추이): [ReportDetailActivity]로 분리,
 *   아래쪽 '상세 분석 보기'로 진입.
 */
class ReportActivity : Activity() {

    private val md = SimpleDateFormat("M월 d일", Locale.KOREA)

    companion object {
        /**
         * 몇 주 전을 볼지. 0이 이번 주 — [ReportListActivity]가 줄마다 넘긴다.
         * 없으면 이번 주다(홈의 「이번 주 안심 요약」에서 곳바로 오는 경우).
         */
        const val EXTRA_WEEK = "week"

        /** 주황 연한 바탕(#FFF1E0) 위의 글자 — 시안 11G-2 값 */
        private const val WARN_CARD_INK = "#8A4A00"
    }

    /** 보고 있는 주 (0 = 이번 주) */
    private var weeksAgo = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_report)
        Insets.apply(this)

        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }
        // 시안 11G-2 아래 버튼 '위험 앱 확인하기' — 기록 목록(07G)으로
        val open = findViewById<Button>(R.id.btn_events)
        Look.mintShadow(open)
        open.setOnClickListener {
            startActivity(Intent(this, EventListActivity::class.java))
        }

        weeksAgo = intent.getIntExtra(EXTRA_WEEK, 0).coerceAtLeast(0)
        val from = ReportStats.weekStart(weeksAgo)
        val to = java.util.Calendar.getInstance().apply {
            time = from; add(java.util.Calendar.DAY_OF_YEAR, 6)
        }.time
        findViewById<TextView>(R.id.text_sub).text =
            "${md.format(from)} ~ ${md.format(if (weeksAgo == 0) Date() else to)}"

        Family.loadEvents { all -> runOnUiThread { render(all) } }
    }

    private fun render(all: List<Family.Event>) {
        val body = findViewById<LinearLayout>(R.id.body)
        body.removeAllViews()

        val week = ReportStats.week(all, weeksAgo)

        // 차단 = 진행이 실제로 멈춘 것 ([Family.Event.stopped] — 옛 기록 보정 포함)
        val blocked = week.count { it.stopped }
        val checked = week.count { !it.stopped }
        val todo = week.count { !it.done }
        val doneN = week.count { it.done }

        // ── 머리글 (시안 11G-1: 기간·이름 → 24 Bold 두 줄 → 15 설명) ──────────
        headline(week.size, todo)
        week.firstOrNull { it.who.isNotBlank() }?.who?.let { who ->
            // 시안 "8월 3주차 · 김순자님" — 기간은 이미 표기됨, 이름만 뒤에 추가
            findViewById<TextView>(R.id.text_sub).append(" · ${who}님")
        }

        // ── 이번 주 안전 상태 카드 ───────────────────────────────────────────────────
        body.addView(weekResult(doneN, todo))

        // ── 두 칸 지표 ───────────────────────────────────────────────────────
        body.addView(metrics(blocked, checked))

        // ── 요일별 발생 추이 (시안 11G-2) ────────────────────────────────────
        val counts = ReportStats.byDay(week)
        body.addView(ReportUi.sectionTitle(this, "요일별 위험 발생 추이"))
        body.addView(ReportUi.sub(this, run {
            val top = counts.maxOrNull() ?: 0
            if (top == 0) "이번 주에는 위험 행동이 없었어요"
            else "${ReportStats.DAYS[counts.indexOf(top)]}요일에 위험 행동이 가장 많이 발생했어요"
        }))
        body.addView(Row.card(this).apply {
            // 시안: 막대 26 너비 · 가장 큰 값이 84 높이
            addView(ReportUi.bars(this@ReportActivity, counts, ReportStats.DAYS, barW = 26, maxH = 84))
        })

        // ── 서비스가 도운 결과 (시안 11G-2 1051:63 — 아이콘 20 + 15.5 Bold 세 줄) ──
        body.addView(ReportUi.sectionTitle(this, "서비스가 도운 결과", bottom = 10))
        body.addView(Row.card(this).apply {
            addView(didRow(R.drawable.ic_rpt_ban, "차단 · ${blocked}건"))
            addView(Row.divider(this@ReportActivity))
            addView(didRow(R.drawable.ic_rpt_alert, "주의 · ${checked}건"))
            addView(Row.divider(this@ReportActivity))
            addView(didRow(R.drawable.ic_rpt_eye, "확인 필요 · ${todo}건"))
        })

        // ── 반복 위험 출처 ───────────────────────────────────────────────────
        repeatBox(ReportStats.repeated(week))?.let { body.addView(it) }

        // 11G-3·11G-4 진입 경로. 시안에 버튼 없음, 화면을 분리해 둔 탓에 문 필요
        body.addView(Look.bigButton(this, "상세 분석 보기", primary = false) {
            startActivity(Intent(this, ReportDetailActivity::class.java))
        }.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(8) })

        body.addView(Row.note(this, "최근 7일 동안의 기록을 세어 보여드립니다."))
    }

    // ── 조각들 ────────────────────────────────────────────────────────────────

    /**
     * 머리글 두 줄 (시안 1051:24 — 24 Bold/32, 두 번째 줄 앞부분만 #1F63E0).
     * 아래 파란 카드와 같은 사실을 말해야 한다 — 여기서 「모두 안전하게」라고 적었으면
     * 카드의 「확인 필요」도 0이어야 한다.
     */
    private fun headline(total: Int, todo: Int) {
        val (plain, tinted, tail) = when {
            total == 0 -> Triple("이번 주는\n", "위험한 일이 없었어요", "")
            todo == 0 -> Triple("위험한 일 ${total}건이 있었고,\n", "모두 안전하게", " 처리했어요")
            else -> Triple("위험한 일 ${total}건 가운데\n", "${todo}건은 확인이", " 필요해요")
        }
        findViewById<TextView>(R.id.text_headline).apply {
            val s = SpannableString(plain + tinted + tail)
            s.setSpan(
                ForegroundColorSpan(Look.color(if (todo == 0) Look.MINT else Look.DANGER)),
                plain.length, plain.length + tinted.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            text = s
            visibility = View.VISIBLE
        }
        findViewById<TextView>(R.id.text_desc).apply {
            text = when {
                total == 0 -> "광고는 보였지만 위험하다고 판정된 것은 없었어요"
                todo == 0 -> "지금 추가로 확인할 일은 없어요"
                else -> "아래에서 확인해 주세요"
            }
            visibility = View.VISIBLE
        }
    }

    /**
     * 이번 주 안전 상태 (시안 Card/WeekResult 1221:2 — #1F63E0 r18 · 132 · 안쪽 22/20).
     * 라벨 14 Bold → 큰 글 30 Bold → 한 줄 15(흰색 90%) · 오른쪽 위 방패 36.
     *
     * v1은 이 자리에 「안전/경고/심각」 세 단계를 40 Bold로 적고 카드 색까지 바꿨지만,
     * v2는 색을 바꾸지 않고 **건수 둘**을 말한다 — 막은 것과 남은 것. 단계 이름보다
     * 숫자가 보호자에게 할 일을 더 정확히 알려 준다.
     */
    private fun weekResult(doneN: Int, todo: Int) = FrameLayout(this).apply {
        background = Look.box(this@ReportActivity, Look.MINT, null, 18)
        setPadding(dp(22), dp(20), dp(22), dp(20))
        layoutParams = wide().apply { bottomMargin = dp(19) }

        addView(LinearLayout(this@ReportActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@ReportActivity).apply {
                text = "이번 주 안전 상태"
                textSize = 14f
                letterSpacing = -0.015f
                includeFontPadding = false
                typeface = Look.bold(this@ReportActivity)
                setTextColor(Look.color(Look.ON_MINT))
            })
            addView(TextView(this@ReportActivity).apply {
                text = "보호 완료 ${doneN}건"
                textSize = 26f      // 시안 1221:4 — 줄 높이 35 (30이면 52까지 부푼다)
                letterSpacing = -0.015f
                includeFontPadding = false
                typeface = Look.bold(this@ReportActivity)
                setTextColor(Look.color(Look.ON_MINT))
                setPadding(0, dp(8), 0, 0)
            })
            addView(TextView(this@ReportActivity).apply {
                text = "확인 필요 ${todo}건"
                textSize = 15f
                letterSpacing = -0.015f
                includeFontPadding = false
                setTextColor(0xE6FFFFFF.toInt())   // 흰색 90%
                setPadding(0, dp(8), 0, 0)
            })
        })
        addView(ImageView(this@ReportActivity).apply {
            setImageResource(R.drawable.ic_rpt_shield_check)
            layoutParams = FrameLayout.LayoutParams(dp(36), dp(36), Gravity.END or Gravity.TOP)
                .apply { topMargin = dp(6) }
        })
    }

    /**
     * 두 칸 지표 (시안 1051:33 — 회색 카드 r16 · 88 · 가운데 1px 선 44).
     * 숫자 21 Bold(차단 빨강 · 주의 주황) · 이름 12.5 #525C6E, 둘 다 가운데 정렬.
     * 세 번째 칸(확인 필요)은 위의 파란 카드가 이미 말하므로 v2에서 빠졌다.
     */
    private fun metrics(blocked: Int, checked: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = Look.box(this@ReportActivity, Look.CARD, null, 16)
        setPadding(0, dp(19), 0, dp(17))
        layoutParams = wide().apply { bottomMargin = dp(30) }

        fun cell(value: String, label: String, tint: String) = LinearLayout(this@ReportActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@ReportActivity).apply {
                text = value
                textSize = 18f      // 시안 1293:37 — 줄 높이 25
                gravity = Gravity.CENTER
                letterSpacing = -0.015f
                includeFontPadding = false
                typeface = Look.bold(this@ReportActivity)
                setTextColor(Look.color(tint))
            })
            addView(TextView(this@ReportActivity).apply {
                text = label
                textSize = 12.5f
                gravity = Gravity.CENTER
                letterSpacing = -0.015f
                includeFontPadding = false
                setTextColor(Look.color(Look.INK_SOFT))
                setPadding(0, dp(7), 0, 0)
            })
        }

        addView(cell("${blocked}건", "차단", Look.DANGER))
        addView(View(this@ReportActivity).apply {
            setBackgroundColor(Look.color(Look.LINE))
            layoutParams = LinearLayout.LayoutParams(dp(1), dp(44))
        })
        addView(cell("${checked}건", "주의", Look.WARN))
    }

    /**
     * 「서비스가 도운 결과」 한 줄 (시안 11G-2 — 아이콘 20 + 15.5 Bold, 줄 높이 52).
     * 시안에는 오른쪽에 붙는 값이 없다. 건수를 글에 함께 적어 한 줄로 읽히게 했다.
     */
    private fun didRow(icon: Int, text: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(20), 0, dp(20), 0)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(52)
        )
        addView(ImageView(this@ReportActivity).apply {
            setImageResource(icon)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
                .apply { rightMargin = dp(14) }
        })
        addView(TextView(this@ReportActivity).apply {
            this.text = text
            textSize = 15.5f
            letterSpacing = -0.015f
            includeFontPadding = false
            typeface = Look.bold(this@ReportActivity)
            setTextColor(Look.color(Look.INK))
        })
    }

    /**
     * 같은 곳 두 번 이상 → 이번 주에도 안내 (시안 11G-2 '반복해서 위험이 발생한 출처'):
     * 경고 아이콘 + 16 Bold 라벨, #FFF1E0 카드(r16 · 20/18)에
     * [곳 · 고위험 2회] 15.5 Bold → 판정 이유 13 → 선 → '이번 주에 확인하면 좋아요 · …' 12.5 Bold.
     * 시안의 'Clean Booster 앱을 삭제해 주세요'는 예시 → 유형에 맞는 할 일로 교체.
     */
    private fun repeatBox(site: ReportStats.Site?): View? {
        site ?: return null
        val ctx = this
        fun warnText(t: String, sp: Float, bold: Boolean, lines: Float, top: Int = 0) =
            TextView(ctx).apply {
                text = t
                textSize = sp
                letterSpacing = -0.015f
                setLineSpacing(0f, lines)
                includeFontPadding = false
                if (bold) typeface = Look.bold(ctx)
                setTextColor(Look.color(WARN_CARD_INK))
                setPadding(0, dp(top), 0, 0)
            }
        val todo = when (site.kind) {
            Family.KIND_APK -> "${site.name}에서 받은 앱이 설치되어 있다면 삭제해 주세요"
            Family.KIND_SEARCH -> "검색 결과의 광고 링크는 누르지 않도록 알려 주세요"
            else -> "${site.name}에 다시 들어가지 않도록 알려 주세요"
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = wide().apply { bottomMargin = dp(12) }
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(9))
                addView(ImageView(ctx).apply {
                    setImageResource(R.drawable.ic_rpt_alert)
                    layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
                        .apply { rightMargin = dp(9) }
                })
                addView(warnText("반복해서 위험이 발생한 출처", 16f, bold = true, lines = 1.3f))
            })
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = Look.box(ctx, Look.WARN_TINT, null, 16)
                setPadding(dp(20), dp(18), dp(20), dp(18))
                addView(warnText("${site.name} · ${ReportStats.riskLabel(site.worst)} ${site.count}회", 15.5f, bold = true, lines = 1.3f))
                // 가장 많이 나온 판정 이유 그대로 — 새 문장 작성 없음
                addView(warnText(site.reasons.first().text, 13f, bold = false, lines = 1.45f, top = 4))
                addView(View(ctx).apply {
                    setBackgroundColor(Look.color(Look.WARN_LINE))
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                        .apply { topMargin = dp(14); bottomMargin = dp(12) }
                })
                addView(warnText("이번 주에 확인하면 좋아요 · $todo", 12.5f, bold = true, lines = 1.45f))
            })
        }
    }

    private fun wide() = ReportUi.wide()

    private fun dp(v: Int) = Look.dp(this, v)
}
