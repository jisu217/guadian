package com.flyai.adalert

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 상세 분석 (시안 11G-3 판단 근거 · 11G-4 예방 자료와 추이 — 한 화면 세로 스크롤).
 * 진입: 주간 리포트([ReportActivity])의 '상세 분석 보기'.
 *
 * ## 표시 내용
 * 1. 이 리포트에 담기는 정보 — 위험 등급·유형·결과·시각뿐이라는 약속.
 * 2. 최근 7일 접속 사이트 분석 — 이번 주 기록을 **곳별로 묶고**, 그 곳의 판정 이유를
 *    위험한 것부터 줄로 표시. 시안의 '공식 앱스토어 밖 설치 경로'·'반복 리디렉션'은 예시,
 *    실제 내용은 판정 서버가 돌려준 문장([Family.Event.reason]).
 * 3. 유사 피해 예방 · 공개 안전 자료 — KISA 보호나라 · 경찰청 사이버범죄 예방. 탭 시 브라우저.
 * 4. 최근 4주 위험 신호 추이 — 주간 리포트와 같은 규칙([ReportStats.week])으로 4주치 집계.
 *
 * ## 시안과 다른 점
 * - 4주 막대 이름: '1주·2주·3주·4주' 대신 '3주 전·2주 전·지난주·이번 주'.
 *   숫자만으로는 어느 쪽이 최근인지 보호자가 헤아려야 함.
 * - 4주 추이 설명 "외부 설치 유도와 낯선 도메인 신호" → 앱이 세는 세 유형(위험 검색 ·
 *   악성 도메인 · APK 설치)으로 교체 — 실제 집계와 다른 말 금지.
 * - "매주 월요일 오전에 알려드려요" 제외. 주간 알림 기능 미구현.
 */
class ReportDetailActivity : Activity() {

    private companion object {
        /** 주황 연한 알약(#FFF1E0) 위의 글자 — 시안 11G-3 '확인 필요 1건' 값 */
        const val WARN_PILL_INK = "#8A4A00"
        /** 사이트 카드 최대 개수 — 나머지는 기록 목록(07G)에서 확인 */
        const val MAX_SITES = 3
        val WEEKS = listOf("3주 전", "2주 전", "지난주", "이번 주")
    }

    /** 공개 안전 자료 (시안 11G-4 카드의 두 줄). 정적 — 기관·제목·한 줄 요약·주소 */
    private class Resource(val org: String, val title: String, val desc: String, val url: String)

    private val links = listOf(
        Resource(
            "KISA 보호나라", "출처 불명 APK 설치 예방",
            "공식 앱스토어 밖 설치 파일은 열기 전 출처를 다시 확인하세요",
            "https://www.boho.or.kr"
        ),
        Resource(
            "경찰청 사이버범죄 예방", "의심 링크·개인정보 요청 대응",
            "낯선 링크에서 결제·개인정보 입력을 요구하면 즉시 중단하세요",
            "https://ecrm.police.go.kr"
        ),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_report_detail)
        Insets.apply(this)

        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }
        val open = findViewById<Button>(R.id.btn_events)
        Look.mintShadow(open)
        open.setOnClickListener { startActivity(Intent(this, EventListActivity::class.java)) }

        Family.loadEvents { all -> runOnUiThread { render(all) } }
    }

    private fun render(all: List<Family.Event>) {
        val body = findViewById<LinearLayout>(R.id.body)
        body.removeAllViews()
        val week = ReportStats.week(all, 0)

        // ── 11G-3 ────────────────────────────────────────────────────────────
        body.addView(privacyCard())

        val sites = ReportStats.sites(week)
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = wide().apply { bottomMargin = dp(12) }
            addView(ReportUi.sectionTitle(this@ReportDetailActivity, "최근 7일 접속 사이트 분석", bottom = 0).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(pill(week.count { !it.done }))
        })
        if (sites.isEmpty()) body.addView(emptyCard())
        else sites.take(MAX_SITES).forEach { body.addView(siteCard(it)) }
        if (sites.size > MAX_SITES) body.addView(ReportUi.hint(this, "그 밖의 ${sites.size - MAX_SITES}곳은 위험 기록에서 볼 수 있어요", bottom = 8))

        body.addView(ReportUi.hint(this, "판단 근거는 기기 내 분석과 연결 주소 기준이며,\n화면 내용·사진·대화는 저장하지 않아요", bottom = 16))
        body.addView(Look.bigButton(this, "분석 방식 자세히 보기", primary = false) { howWeJudge() }
            .apply { (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(28) })

        // ── 11G-4 ────────────────────────────────────────────────────────────
        body.addView(ReportUi.sectionTitle(this, "유사 피해 예방 · 공개 안전 자료"))
        body.addView(ReportUi.sub(this, "접속 사이트와 유사한 위험 신호를 이해할 때\n참고할 수 있는 공공 안내예요"))
        body.addView(Row.card(this, bottomGap = 12).apply {
            links.forEachIndexed { i, r ->
                if (i > 0) addView(Row.divider(this@ReportDetailActivity))
                addView(resourceRow(r))
            }
        })
        body.addView(ReportUi.hint(this, "자료를 열 때는 발행 기관·게시 날짜를 함께 확인해 주세요", bottom = 28))

        val weeks = ReportStats.fourWeeks(all)
        body.addView(ReportUi.sectionTitle(this, "최근 4주 위험 신호 추이"))
        body.addView(ReportUi.sub(this, "위험 검색·악성 도메인·APK 설치 신호의 발생 횟수예요"))
        body.addView(Row.card(this, bottomGap = 12).apply {
            // 시안: 막대 40 너비 · 가장 큰 값이 72 높이
            addView(ReportUi.bars(this@ReportDetailActivity, weeks, WEEKS, barW = 40, maxH = 72))
        })
        body.addView(ReportUi.hint(this, run {
            val top = weeks.maxOrNull() ?: 0
            if (top == 0) "최근 4주 동안 위험 신호가 없었어요"
            else "최근 4주 최고치 ${WEEKS[weeks.indexOf(top)]} ${top}건 · 위험 기록 화면에서\n원인과 안내를 확인할 수 있어요"
        }, bottom = 8))
        body.addView(Row.note(this, "최근 4주 동안의 기록을 세어 보여드립니다."))
    }

    // ── 조각들 ────────────────────────────────────────────────────────────────

    /** '이 리포트에 담기는 정보' (시안 11G-3: 회색 카드 · 자물쇠 20 · 14 Bold · 13 #525C6E 두 줄) */
    private fun privacyCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Look.box(this@ReportDetailActivity, Look.CARD, null, 16)
        setPadding(dp(15), dp(18), dp(20), dp(18))
        layoutParams = wide().apply { bottomMargin = dp(28) }
        addView(LinearLayout(this@ReportDetailActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(this@ReportDetailActivity).apply {
                setImageResource(R.drawable.ic_rpt_lock)
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(15) }
            })
            addView(text("이 리포트에 담기는 정보", 14f, Look.INK, bold = true, lines = 1.3f))
        })
        addView(text(
            "위험 등급·유형·결과·발생 시각만 포함됩니다.\n화면 내용, 사진, 메시지와 비밀번호는 공유되지 않습니다.",
            13f, Look.INK_SOFT, lines = 1.5f
        ).apply { setPadding(dp(5), dp(10), 0, 0) })
    }

    /** 제목 오른쪽 알약 '확인 필요 N건' (시안: 24 높이 · r999 · #FFF1E0 · 12 Bold #8A4A00). 0건은 회색 */
    private fun pill(todo: Int) = TextView(this).apply {
        text = "확인 필요 ${todo}건"
        textSize = 12f
        letterSpacing = -0.015f
        gravity = Gravity.CENTER
        includeFontPadding = false
        typeface = Look.bold(this@ReportDetailActivity)
        setTextColor(Look.color(if (todo == 0) Look.INK_SOFT else WARN_PILL_INK))
        background = Look.pill(this@ReportDetailActivity, if (todo == 0) Look.NEUTRAL_TINT else Look.WARN_SOFT, 999)
        setPadding(dp(10), 0, dp(10), 0)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(24))
            .apply { leftMargin = dp(8) }
    }

    /**
     * 한 곳의 카드 (시안 11G-3): 이름 15.5 Bold → 요약 13 #525C6E → 선 → 근거 줄들.
     * 근거 줄: 왼쪽 색 막대(4×34 · r2 · 고위험 빨강 · 중위험 주황 · 그 밖 회색) ·
     * 제목 13.5 Bold · 설명 12 #525C6E. 마지막 줄은 시안 그대로 '분석 한계'.
     */
    private fun siteCard(site: ReportStats.Site) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Look.box(this@ReportDetailActivity, Look.CARD, null, 16)
        setPadding(dp(20), dp(18), dp(20), dp(18))
        layoutParams = wide().apply { bottomMargin = dp(12) }

        addView(text(site.name, 15.5f, Look.INK, bold = true, lines = 1.3f))
        addView(text(summary(site), 13f, Look.INK_SOFT, lines = 1.48f, top = 6))
        addView(View(this@ReportDetailActivity).apply {
            setBackgroundColor(Look.color(Look.LINE))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                .apply { topMargin = dp(14); bottomMargin = dp(16) }
        })
        site.reasons.forEach { r ->
            val title = if (r.times > 1) "${r.kind} · ${r.times}회" else r.kind
            addView(reasonRow(ReportStats.riskInk(r.risk), title, r.text))
        }
        addView(reasonRow(Look.INK_MUTED, "분석 한계", "사이트 전체 내용을 저장하거나 공유하지 않아요", last = true))
    }

    /** 카드 둘째 줄 — 횟수 · 유형 · 확인 잔여 여부 */
    private fun summary(site: ReportStats.Site): String = buildString {
        append("${ReportStats.riskLabel(site.worst)} ${site.count}회 · ${site.kind}")
        if (site.events.any { it.stopped }) append(" · 이동 전에 막았어요")
        if (site.todo > 0) append("\n보호자 확인이 ${site.todo}건 남아 있어요")
        else append("\n보호자 확인이 끝났어요")
    }

    /** 근거 한 줄: 색 막대 4×34 · 제목 13.5 Bold · 설명 12. 줄 사이 18 */
    private fun reasonRow(ink: String, title: String, desc: String, last: Boolean = false) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = wide().apply { bottomMargin = if (last) 0 else dp(18) }
            addView(View(this@ReportDetailActivity).apply {
                background = Look.pill(this@ReportDetailActivity, ink, 2)
                layoutParams = LinearLayout.LayoutParams(dp(4), dp(34)).apply { rightMargin = dp(12) }
            })
            addView(LinearLayout(this@ReportDetailActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(text(title, 13.5f, Look.INK, bold = true, lines = 1.3f))
                addView(text(desc, 12f, Look.INK_SOFT, lines = 1.4f, top = 2))
            })
        }

    /** 기록 없을 때의 카드 — 시안에 없음. 빈 화면 대신 비어 있는 이유 안내 */
    private fun emptyCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Look.box(this@ReportDetailActivity, Look.CARD, null, 16)
        setPadding(dp(20), dp(18), dp(20), dp(18))
        layoutParams = wide().apply { bottomMargin = dp(12) }
        addView(text("최근 7일 동안 분석할 접속 기록이 없어요", 15.5f, Look.INK, bold = true, lines = 1.3f))
        addView(text(
            "위험하다고 판정된 접속이 생기면 사이트별로 판단 근거가 여기에 쌓여요",
            13f, Look.INK_SOFT, lines = 1.48f, top = 6
        ))
    }

    /** 공개 안전 자료 한 줄 (시안 11G-4): 문서 아이콘 20 · 기관 11.5 Bold 회색 · 제목 14 Bold · 설명 11.5 */
    private fun resourceRow(r: Resource) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.TOP
        setPadding(dp(15), dp(16), dp(20), dp(16))
        layoutParams = wide()
        isClickable = true
        setOnClickListener {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.url))) }
        }
        addView(ImageView(this@ReportDetailActivity).apply {
            setImageResource(R.drawable.ic_rpt_doc)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
                .apply { rightMargin = dp(13); topMargin = dp(2) }
        })
        addView(LinearLayout(this@ReportDetailActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(text(r.org, 11.5f, Look.INK_MUTED, bold = true, lines = 1.3f))
            addView(text(r.title, 14f, Look.INK, bold = true, lines = 1.3f, top = 2))
            addView(text(r.desc, 11.5f, Look.INK_SOFT, lines = 1.4f, top = 3))
        })
    }

    /** '분석 방식 자세히 보기' — 판정 방식과 볼 수 없는 범위 안내 */
    private fun howWeJudge() {
        AlertDialog.Builder(this)
            .setTitle("분석 방식")
            .setMessage(
                "광고를 누르면 연결되는 주소를 판정 서버에 보내 위험한지 묻고, " +
                    "그 답(위험 등급·유형·이유)만 기록합니다.\n\n" +
                    "• 주소는 호스트까지만 남기고 그 뒤 경로는 버립니다.\n" +
                    "• 화면 내용·사진·메시지·비밀번호는 읽지도 보내지도 않습니다.\n" +
                    "• 처음 보는 사이트는 판정이 틀릴 수 있어요. 확실하지 않으면 '주의'로 " +
                    "표시하고 어르신이 고르게 합니다.\n" +
                    "• 앱 밖(문자·전화)의 위험은 보지 못합니다."
            )
            .setPositiveButton("확인", null)
            .show()
    }

    private fun text(
        t: String, sp: Float, ink: String, bold: Boolean = false, lines: Float = 1.3f, top: Int = 0,
    ) = TextView(this).apply {
        text = t
        textSize = sp
        letterSpacing = -0.015f
        setLineSpacing(0f, lines)
        includeFontPadding = false
        if (bold) typeface = Look.bold(this@ReportDetailActivity)
        setTextColor(Look.color(ink))
        if (top > 0) setPadding(0, dp(top), 0, 0)
    }

    private fun wide() = ReportUi.wide()

    private fun dp(v: Int) = Look.dp(this, v)
}
