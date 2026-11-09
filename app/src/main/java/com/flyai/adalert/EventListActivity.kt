package com.flyai.adalert

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.firebase.firestore.ListenerRegistration
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 위험 이벤트 (시안 07G) — 어르신 폰에서 일어난 일 목록.
 *
 * 새로고침 버튼 없음. 어르신 폰의 방금 일이 **이 화면에 실시간 반영**(Firestore 구독).
 * 보호자가 켜 두면 실시간 대시보드.
 *
 * ## 목록이 카드가 아니라 줄인 이유
 * 기록 하나를 큰 카드로 그리면 한 화면에 두 건뿐. 보호자가 여기서 하는 일은 읽기가 아니라
 * **훑기** — 색과 제목만 보고 들어갈 것을 고르는 화면이라 시안대로 한 줄씩. 자세한 내용은 상세 화면.
 *
 * 목록에 주소 전체가 아니라 호스트만 표시 — 의도된 동작, [Family.logEvent] 참고.
 */
class EventListActivity : Activity() {

    private var sub: ListenerRegistration? = null
    private lateinit var list: LinearLayout

    private val hhmm = SimpleDateFormat("a h:mm", Locale.KOREA)
    private val md = SimpleDateFormat("M월 d일", Locale.KOREA)

    /** 현재 선택된 칩 */
    private var filter = ALL
    private var events: List<Family.Event> = emptyList()

    private companion object {
        const val ALL = 0
        const val TODO = 1
        const val DONE = 2
        /** 이 횟수 이상 나온 사이트 = "반복" 기준 */
        const val REPEAT_AT = 2
        /** 주황 연한 바탕(#FFF1E0) 위의 글자 — 시안 07G·11G-2 값 */
        const val REPEAT_INK = "#8A4A00"

        /** 같은 주황 글자를 「확인 필요」·「안내」 알약에도 쓴다 */
        const val CAUTION_INK = REPEAT_INK
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_event_list)
        Insets.apply(this)
        list = findViewById(R.id.list_events)

        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_top).apply {
            setOnClickListener { openWorst() }
            Look.mintShadow(this)
        }
        Home.tabs(this, 1, Home.TABS_GUARDIAN) { i ->
            Home.open(
                this, when (i) {
                    0 -> GuardianHomeActivity::class.java
                    2 -> ReportListActivity::class.java
                    else -> MeActivity::class.java
                }
            )
        }
    }

    override fun onStart() {
        super.onStart()
        sub = Family.watchEvents { list -> runOnUiThread { events = list; render() } }
        if (sub == null) note("로그인이 필요합니다.") else note("불러오는 중…")
    }

    override fun onStop() {
        super.onStop()
        sub?.remove()
        sub = null
    }

    // ── 그리기 ────────────────────────────────────────────────────────────────

    private fun render() {
        chips()
        list.removeAllViews()

        val shown = when (filter) {
            TODO -> events.filter { !it.done }
            DONE -> events.filter { it.done }
            else -> events
        }
        // 시안 07G의 CTA: "확인이 필요한 기록 2건 보기" — 건수 병기
        val todo = events.count { !it.done }
        findViewById<Button>(R.id.btn_top).apply {
            isEnabled = todo > 0
            text = if (todo > 0) "확인이 필요한 기록 ${todo}건 보기" else "확인이 필요한 기록이 없어요"
        }

        if (shown.isEmpty()) {
            note(
                if (events.isEmpty())
                    "아직 기록이 없습니다.\n어르신 폰에서 위험한 광고를 만나면 여기에 나타납니다."
                else "여기에 해당하는 기록이 없습니다."
            )
            return
        }

        // 날짜 바뀌는 자리에 라벨, 그 날의 줄은 회색 카드 하나에 묶음(시안 07G).
        // 시각만 있으면 어제·오늘 구분 불가.
        var day = ""
        shown.forEach { e ->
            val label = dayLabel(e.at)
            if (label != day) {
                day = label
                list.addView(Row.day(this, label).apply {
                    setPadding(0, dp(if (list.childCount == 0) 0 else 18), 0, dp(11))
                })
            }
            list.addView(card(e))
        }
        repeatCard()?.let { list.addView(it) }

        // 새로 그린 뒤 맨 위로 스크롤. 줄이 눌리는 자리라 안드로이드가 방금 붙은 줄로
        // 화면을 내려 제일 위 날짜·첫 줄이 잘리는 문제 방지.
        findViewById<android.widget.ScrollView>(R.id.scroll).post {
            findViewById<android.widget.ScrollView>(R.id.scroll).scrollTo(0, 0)
        }
    }

    /**
     * 기록 한 장 (시안 v2 07G Record/… — #F5F7FA r16 · 100 · 안쪽 20).
     *
     * v1은 날짜별로 카드 하나에 줄을 쌓고 왼쪽에 색 막대를 두었지만, v2는 기록마다 카드 한 장이고
     * 막대가 없다. 대신 상태를 알약 둘로 말한다 — 왼쪽 아래는 보호자가 할 일(확인 필요 / 확인 완료),
     * 오른쪽 위는 앱이 한 일(차단 / 안내).
     *
     * 시안의 제목은 유형뿐이라 호스트를 시각 옆에 붙였다. 기록 둘이 같은 유형이면 제목만으로는
     * 구분이 안 된다. 주소 전체가 아니라 호스트만 — [Family.logEvent] 참고.
     */
    private fun card(e: Family.Event) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = Look.box(this@EventListActivity, Look.CARD, null, 16)
        setPadding(dp(20), dp(20), dp(20), dp(20))
        isClickable = true
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) }
        setOnClickListener { open(e) }

        addView(LinearLayout(this@EventListActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
            addView(TextView(this@EventListActivity).apply {
                text = e.typeLabel.ifEmpty {
                    if (e.stopped) "위험 광고 차단" else "위험 사이트 이동"
                }
                textSize = 15.5f
                letterSpacing = -0.015f
                includeFontPadding = false
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                typeface = Look.bold(this@EventListActivity)
                setTextColor(Look.color(if (e.done) Look.INK_SOFT else Look.INK))
            })
            addView(LinearLayout(this@EventListActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(12), 0, 0)
                addView(chip(
                    if (e.done) "확인 완료" else "확인 필요",
                    if (e.done) Look.MINT else CAUTION_INK,
                    if (e.done) Look.MINT_TINT else Look.WARN_TINT, 24, 12
                ))
                addView(TextView(this@EventListActivity).apply {
                    text = buildString {
                        e.at?.let { append(hhmm.format(it)) }
                        if (e.host.isNotEmpty()) {
                            if (isNotEmpty()) append(" · ")
                            append(e.host)
                        }
                    }
                    textSize = 12.5f
                    letterSpacing = -0.015f
                    includeFontPadding = false
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(Look.color(Look.INK_SOFT))
                    setPadding(dp(10), 0, 0, 0)
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    )
                })
            })
        })

        // 오른쪽 위 알약 — 시안은 모서리를 6으로 두어 왼쪽 알약(r12)과 모양을 구분한다
        addView(chip(
            if (e.stopped) "차단" else "안내",
            if (e.stopped) Look.DANGER else CAUTION_INK,
            if (e.stopped) Look.DANGER_TINT else Look.WARN_TINT, 22, 6, hPad = 15
        ).apply {
            (layoutParams as LinearLayout.LayoutParams).leftMargin = dp(10)
        })
    }

    /**
     * 알약 하나 (시안 Chip/State — 11.5 Bold).
     *
     * 좌우 여백이 자리마다 다르다. 왼쪽 「확인 필요」는 65에 글자 45라 10이지만,
     * 오른쪽 등급 알약은 52에 글자 22라 15다 — 짧은 말일수록 넉넉하게 두었다.
     */
    private fun chip(
        text: String, ink: String, tint: String, height: Int, radius: Int, hPad: Int = 10,
    ) =
        TextView(this).apply {
            this.text = text
            textSize = 11.5f
            gravity = Gravity.CENTER
            letterSpacing = -0.015f
            includeFontPadding = false
            typeface = Look.bold(this@EventListActivity)
            setTextColor(Look.color(ink))
            background = Look.pill(this@EventListActivity, tint, radius)
            setPadding(dp(hPad), 0, dp(hPad), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(height)
            )
        }

    /**
     * 같은 사이트 반복 출현 (시안 11G-2 '반복해서 위험이 발생한 출처').
     * 한 번은 실수, 여러 번은 그 사이트가 어르신 화면에 계속 뜨는 상태 →
     * 보호자가 손쓸 곳은 광고가 아니라 그 앱·그 페이지.
     */
    private fun repeatCard(): LinearLayout? {
        val hosts = events.filter { it.host.isNotEmpty() }.groupBy { it.host }
        val worst = hosts.filterValues { it.size >= REPEAT_AT }
            .maxByOrNull { it.value.size } ?: return null
        val times = worst.value.mapNotNull { it.at?.time }
        // "7일 · 4회" — 며칠에 걸쳐 몇 번인지. 하루 안에 몰림 vs 일주일 내내 → 보호자 대응 다름.
        val days = if (times.size >= 2)
            ((times.max() - times.min()) / 86_400_000L).toInt() + 1 else 1

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }

            // 라벨: 경고 아이콘 20 + 16 Bold #8A4A00, 카드와 9 띄움 (시안 11G-2)
            addView(LinearLayout(this@EventListActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(9))
                addView(ImageView(this@EventListActivity).apply {
                    setImageResource(R.drawable.ic_rpt_alert)
                    layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
                        .apply { rightMargin = dp(9) }
                })
                addView(TextView(this@EventListActivity).apply {
                    text = "반복해서 위험이 발생한 출처"
                    textSize = 16f
                    letterSpacing = -0.015f
                    includeFontPadding = false
                    typeface = Look.bold(this@EventListActivity)
                    setTextColor(Look.color(REPEAT_INK))
                })
            })
            // 카드: #FFF1E0 · r16 · 안쪽 20/18 — 제목 15.5 Bold · 설명 13 (모두 #8A4A00)
            addView(LinearLayout(this@EventListActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = Look.box(this@EventListActivity, Look.WARN_TINT, null, 16)
                setPadding(dp(20), dp(18), dp(20), dp(18))
                addView(TextView(this@EventListActivity).apply {
                    text = "${worst.key} · ${days}일 ${worst.value.size}회"
                    textSize = 15.5f
                    letterSpacing = -0.015f
                    includeFontPadding = false
                    typeface = Look.bold(this@EventListActivity)
                    setTextColor(Look.color(REPEAT_INK))
                })
                addView(TextView(this@EventListActivity).apply {
                    text = "같은 사이트 방문이 반복되고 있어요"
                    textSize = 13f
                    letterSpacing = -0.015f
                    setLineSpacing(0f, 1.45f)
                    includeFontPadding = false
                    setTextColor(Look.color(REPEAT_INK))
                    setPadding(0, dp(4), 0, 0)
                })
            })
        }
    }

    /**
     * 세그먼트 필터 (시안 v2 Filter/Segment 1383:3).
     * 틀은 #EEF1F5 r12 · 40, 고른 칸만 흰 알약 r10 · 32. 글자는 13 Bold.
     * 칸 폭은 글자 길이에 따라 — 시안도 100/124/86으로 제각각이다.
     */
    private fun chips() {
        val bar = findViewById<LinearLayout>(R.id.chips)
        bar.background = Look.box(this, Look.LINE_SOFT, null, 12)
        bar.removeAllViews()
        listOf(
            "전체 " + events.size,
            "확인 필요 " + events.count { !it.done },
            "완료 " + events.count { it.done },
        ).forEachIndexed { i, label ->
            val on = filter == i
            bar.addView(TextView(this).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                letterSpacing = -0.015f
                includeFontPadding = false
                typeface = Look.bold(this@EventListActivity)
                setTextColor(Look.color(if (on) Look.INK else Look.INK_SOFT))
                if (on) background = Look.box(this@EventListActivity, Look.ON_MINT, null, 10)
                isClickable = true
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1f
                )
                setOnClickListener { filter = i; render() }
            })
        }
    }

    private fun dp(v: Int) = Look.dp(this, v)

    /** 시안 07G의 날짜 라벨: "오늘" · "어제" 한 낱말. 그보다 오래된 것은 날짜 표기 */
    private fun dayLabel(at: java.util.Date?): String {
        if (at == null) return "방금"
        val c = Calendar.getInstance().apply { time = at }
        val now = Calendar.getInstance()
        val sameYear = c.get(Calendar.YEAR) == now.get(Calendar.YEAR)
        val diff = now.get(Calendar.DAY_OF_YEAR) - c.get(Calendar.DAY_OF_YEAR)
        return when {
            sameYear && diff == 0 -> "오늘"
            sameYear && diff == 1 -> "어제"
            else -> md.format(at)
        }
    }

    /** 미확인 중 가장 위험한 것. 보호자가 무엇부터 볼지 고를 필요 없게 */
    private fun openWorst() {
        val worst = events.filter { !it.done }
            .minWithOrNull(compareBy({ if (it.risk == "HIGH") 0 else 1 }, { -(it.at?.time ?: 0L) }))
            ?: return
        open(worst)
    }

    private fun open(e: Family.Event) {
        startActivity(
            Intent(this, EventDetailActivity::class.java)
                .putExtra("id", e.id)
                .putExtra("risk", e.risk)
                .putExtra("type", e.typeLabel)
                .putExtra("host", e.host)
                .putExtra("reason", e.reason)
                .putExtra("who", e.who)
                .putExtra("blocked", e.stopped)
                .putExtra("done", e.done)
                .putExtra("atMs", e.at?.time ?: 0L)
                .putExtra("repeat", events.count { it.host.isNotEmpty() && it.host == e.host })
        )
    }

    private fun note(t: String) {
        list.removeAllViews()
        list.addView(TextView(this).apply {
            text = t
            textSize = Look.TEXT_BODY
            letterSpacing = -0.015f
            setLineSpacing(0f, 1.5f)
            setTextColor(Look.color(Look.INK_MUTED))
            setPadding(0, Look.dp(this@EventListActivity, 24), 0, 0)
        })
    }
}
