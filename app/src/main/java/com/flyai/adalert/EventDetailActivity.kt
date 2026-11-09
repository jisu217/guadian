package com.flyai.adalert

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 위험 이벤트 상세 (피그마 v2 · 08G · 1039:65).
 *
 * 목록이 "무슨 일이 있었나"라면 여기는 **진행 과정**이다. 광고를 누른 순간부터 되돌린 순간까지
 * 시간 순으로 늘어놓는다 — 보호자가 어르신에게 설명할 때 필요한 것은 결론보다 과정이다.
 *
 * ## 과정 일부는 시각이 비어 있다
 * 기록은 한 줄(등급·유형·사이트·이유·시각)뿐이라 단계마다 시각을 적을 수 없다.
 * 확실한 것만 채우고 나머지 시각은 비운다. 시안처럼 모든 줄에 그럴듯한 시각을 넣으면
 * 기록에 없는 것을 있는 것처럼 보이게 만든다.
 *
 * ## 알림에서 바로 들어오는 길
 * 보호자 폰의 알림은 기록 **이름 하나**만 싣는다([PushService]). 목록을 거쳐 온 경우와
 * 달리 화면에 그릴 값이 없으므로, 이름만 있으면 먼저 그 기록을 읽어 온 뒤 그린다.
 * 읽지 못하면(지워졌거나 연결이 끊겼거나) 목록으로 보낸다 — 빈 상세를 띄우는 것보다 낫다.
 *
 * 이때 「반복해서 위험이 발생한 출처」 칸은 나오지 않는다. 그 값은 목록이 여러 기록을
 * 세어 만드는 것이라 기록 한 줄에는 없다. 없는 것을 그럴듯하게 채우지 않는다.
 *
 * ## 버튼이 시안보다 하나 많다
 * 시안의 버튼은 「OO님께 바로 연락하기」 하나지만, 목록(07G)이 「확인 필요 / 확인 완료」로
 * 나뉘므로 **확인 표시를 남길 자리**가 어디든 있어야 한다. 그 자리를 여기 둔다 —
 * 기록을 다 읽은 사람만 확인을 누를 수 있어야 하니 목록보다 여기가 맞다.
 */
class EventDetailActivity : Activity() {

    private val hhmm = SimpleDateFormat("a h:mm", Locale.KOREA)

    private companion object {
        const val TAG = "EventDetail"

        /** 시안 08G Card/EventHead — 등급마다 [바탕, 막대·알약, 글자] 한 벌 */
        val HIGH = Triple(Look.DANGER_TINT, Look.DANGER, "#A11419")
        val MEDIUM = Triple(Look.WARN_TINT, Look.WARN, "#8A4A00")
        val LOW = Triple(Look.MINT_TINT, Look.MINT, Look.MINT)

        /** 발생 과정의 점과 선 (시안 08G: 점 10 · 선 2px #E6E9EE) */
        const val DOT = 10
        const val STEP_IDLE = "#D7DBE2"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_event_detail)
        Insets.apply(this)

        // 알림에서 들어온 경우 — 이름만 있고 나머지는 없다. 읽어서 채운 뒤 그린다
        val pending = intent.getStringExtra("eventId").orEmpty()
        if (pending.isNotEmpty() && intent.getStringExtra("id").isNullOrEmpty()) {
            Family.event(pending) { event ->
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    if (event == null) {
                        Log.w(TAG, "알림이 가리키는 기록을 읽지 못했다 — 목록으로")
                        startActivity(Intent(this, EventListActivity::class.java))
                        finish()
                        return@runOnUiThread
                    }
                    intent.putExtra("id", event.id)
                        .putExtra("risk", event.risk)
                        .putExtra("type", event.type)
                        .putExtra("host", event.host)
                        .putExtra("reason", event.reason)
                        .putExtra("who", event.who)
                        .putExtra("blocked", event.blocked)
                        .putExtra("done", event.done)
                        .putExtra("atMs", event.at?.time ?: 0L)
                    render()
                }
            }
            return
        }
        render()
    }

    private fun render() {
        val id = intent.getStringExtra("id").orEmpty()
        val risk = intent.getStringExtra("risk").orEmpty()
        val type = intent.getStringExtra("type").orEmpty()
        val host = intent.getStringExtra("host").orEmpty()
        val reason = intent.getStringExtra("reason").orEmpty()
        val who = intent.getStringExtra("who").orEmpty()
        val blocked = intent.getBooleanExtra("blocked", false)
        val done = intent.getBooleanExtra("done", false)
        val atMs = intent.getLongExtra("atMs", 0L)
        val repeat = intent.getIntExtra("repeat", 1)

        val at = if (atMs > 0) Date(atMs) else null
        val time = at?.let { hhmm.format(it) } ?: "방금"

        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }
        // 시안(08G): 감지 시각은 요약 카드 안. 제목 아래 줄은 도움말·오류 시에만 표시.
        val when_ = findViewById<TextView>(R.id.text_when)

        val body = findViewById<LinearLayout>(R.id.body)

        body.addView(headCard(risk, type, host, at, time, done))

        // ── 발생 과정 ────────────────────────────────────────────────────────
        body.addView(label("발생 과정", 22))
        body.addView(timeline(host, reason, time, blocked))

        // ── 사이트 확인 결과 ─────────────────────────────────────────────────
        body.addView(label("사이트 확인 결과", 22))
        body.addView(verifyCard(risk, host, reason))

        // ── 반복 위험 사이트 ─────────────────────────────────────────────────
        if (repeat >= 2 && host.isNotEmpty()) {
            body.addView(label("반복해서 위험이 발생한 출처", 22, MEDIUM.third))
            body.addView(repeatCard(host, repeat))
        }

        // 시안 1384:34 — 버튼 위 회색 한 줄
        body.addView(TextView(this).apply {
            text = if (blocked) "휴대폰에서는 이미 처리됐고, 보호자 확인만 남았어요"
            else "휴대폰에서 안내만 했어요. 필요하면 직접 연락해 주세요"
            textSize = 12.5f
            letterSpacing = -0.015f
            includeFontPadding = false
            setLineSpacing(dp(3).toFloat(), 1f)
            setTextColor(Look.color(Look.INK_MUTED))
            setPadding(0, dp(22), 0, 0)
        })

        // ── 아래 버튼 ────────────────────────────────────────────────────────
        // 시안의 Primary는 연락하기. 09G 도움 보내기로 간다(extra 묶음 그대로 전달)
        val btn = findViewById<Button>(R.id.btn_help)
        Look.mintShadow(btn)
        btn.text = "${who.ifEmpty { "가족" }}님께 바로 연락하기"
        btn.setOnClickListener {
            startActivity(Intent(this, HelpSendActivity::class.java).putExtras(intent))
        }
        if (who.isEmpty()) Family.profiles { all ->
            val senior = all.firstOrNull { it.isSenior }?.name
            if (senior != null) runOnUiThread { btn.text = "${senior}님께 바로 연락하기" }
        }

        val footer = btn.parent as LinearLayout
        // 확인 표시 — 주간 리포트의 「보호 완료 / 확인 필요」 구분 기준 값 (spec.md 5)
        footer.addView(Look.bigButton(this, if (done) "확인 완료됨" else "확인 완료", primary = false) {
            Family.markDone(id) { ok ->
                runOnUiThread {
                    if (ok) finish() else {
                        when_.text = "확인 표시를 저장하지 못했습니다. 잠시 뒤 다시 눌러 주세요."
                        when_.visibility = View.VISIBLE
                    }
                }
            }
        }.apply {
            isEnabled = !done
            (layoutParams as LinearLayout.LayoutParams).setMargins(dp(24), dp(12), dp(24), 0)
        })
        // 10G 보호 결과 — 같은 extra 묶음
        footer.addView(Look.bigButton(this, "보호 결과 보기", primary = false) {
            startActivity(Intent(this, ProtectResultActivity::class.java).putExtras(intent))
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).setMargins(dp(24), dp(12), dp(24), 0)
        })
    }

    private fun tone(risk: String) = when (risk) {
        "HIGH" -> HIGH
        "MEDIUM" -> MEDIUM
        else -> LOW
    }

    /** 구역 이름 (시안: 14 Bold #525C6E · 카드까지 10) */
    private fun label(text: String, top: Int, ink: String = Look.INK_SOFT) = TextView(this).apply {
        this.text = text
        textSize = 14f
        letterSpacing = -0.015f
        includeFontPadding = false
        typeface = Look.bold(this@EventDetailActivity)
        setTextColor(Look.color(ink))
        setPadding(0, dp(top), 0, dp(10))
    }

    /**
     * 맨 위 요약 카드 (시안 Card/EventHead 1384:3 — r16 · 왼쪽 4px 막대 · 안쪽 20).
     * 왼쪽 알약은 보호자가 할 일(진한 바탕 · 흰 글자), 오른쪽 알약은 등급(흰 바탕).
     * 글자는 모두 등급의 진한 색 한 가지다.
     */
    private fun headCard(
        risk: String, type: String, host: String, at: Date?, time: String, done: Boolean,
    ) = LinearLayout(this).apply {
        val (tint, edge, ink) = tone(risk)
        orientation = LinearLayout.HORIZONTAL
        background = Look.box(this@EventDetailActivity, tint, null, 16)
        clipToOutline = true
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        addView(View(this@EventDetailActivity).apply {
            setBackgroundColor(Look.color(edge))
            layoutParams = LinearLayout.LayoutParams(dp(4), LinearLayout.LayoutParams.MATCH_PARENT)
        })
        addView(LinearLayout(this@EventDetailActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(20), dp(20))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            addView(LinearLayout(this@EventDetailActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(chip(
                    if (done) "확인 완료" else "확인 필요", Look.ON_MINT, edge, 26, 12f
                ).apply {
                    (layoutParams as LinearLayout.LayoutParams).weight = 1f
                    (layoutParams as LinearLayout.LayoutParams).width = 0
                    gravity = Gravity.CENTER_VERTICAL
                })
                addView(chip(
                    when (risk) {
                        "HIGH" -> "위험"
                        "MEDIUM" -> "주의"
                        else -> "안전"
                    }, ink, Look.ON_MINT, 24, 11.5f, hPad = 15
                ))
            })
            addView(TextView(this@EventDetailActivity).apply {
                text = type.ifEmpty { "위험한 페이지" }
                textSize = 20f
                letterSpacing = -0.015f
                includeFontPadding = false
                setLineSpacing(dp(6).toFloat(), 1f)
                typeface = Look.bold(this@EventDetailActivity)
                setTextColor(Look.color(ink))
                setPadding(0, dp(12), 0, 0)
            })
            addView(TextView(this@EventDetailActivity).apply {
                text = host.ifEmpty { "주소를 확인하지 못함" }
                textSize = 13.5f
                letterSpacing = -0.015f
                includeFontPadding = false
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(Look.color(ink))
                setPadding(0, dp(7), 0, 0)
            })
            addView(TextView(this@EventDetailActivity).apply {
                // "오늘 오후 2:18 · 3분 전" — 시안과 같은 꼴. 경과 시간은 시각에서 계산
                text = buildString {
                    append(dayWord(at)); append(time)
                    ago(at)?.let { append(" · "); append(it) }
                }
                textSize = 12.5f
                letterSpacing = -0.015f
                includeFontPadding = false
                setTextColor(Look.color(ink))
                setPadding(0, dp(7), 0, 0)
            })
        })
    }

    /**
     * 발생 과정 (시안 Card/Timeline 1384:13 — 점 10 · 선 2px · 제목 15.5 Bold + 오른쪽 시각 12.5 ·
     * 설명 13.5). 마지막 점만 파랑(끝났다는 뜻), 앞의 점은 회색.
     */
    private fun timeline(host: String, reason: String, time: String, blocked: Boolean) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Look.box(this@EventDetailActivity, Look.CARD, null, 16)
            setPadding(dp(20), dp(20), dp(20), dp(20))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            val steps = listOf(
                Triple("위험 광고 누름", "화면에서 광고로 표시된 자리를 눌렀어요", ""),
                Triple(
                    if (blocked) "이동을 막았어요" else "외부 사이트로 이동",
                    host.ifEmpty { "주소를 확인하지 못함" }, time
                ),
                Triple(
                    if (blocked) "되돌아왔어요" else "위험을 알렸어요",
                    reason.ifEmpty { "확인 결과에 따라 안내했어요" }, ""
                ),
            )
            steps.forEachIndexed { i, (title, desc, at) ->
                addView(step(title, desc, at, i == steps.lastIndex))
            }
        }

    /** 과정 한 걸음 — 왼쪽에 점과 선, 오른쪽에 글 두 줄 */
    private fun step(title: String, desc: String, at: String, last: Boolean) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            addView(LinearLayout(this@EventDetailActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                // 시안 1384:14 — 점이 x=44(카드 안쪽 20), 글이 68이다. 이 칸을 24로 두면
                // 점이 가운데로 밀리고 글까지 21 안쪽으로 들어간다 (10G도 같았다)
                layoutParams = LinearLayout.LayoutParams(dp(10), LinearLayout.LayoutParams.MATCH_PARENT)
                addView(View(this@EventDetailActivity).apply {
                    background = Look.dot(if (last) Look.MINT else STEP_IDLE)
                    layoutParams = LinearLayout.LayoutParams(dp(DOT), dp(DOT))
                        .apply { topMargin = dp(6) }
                })
                if (!last) addView(View(this@EventDetailActivity).apply {
                    setBackgroundColor(Look.color(Look.LINE))
                    layoutParams = LinearLayout.LayoutParams(dp(2), 0, 1f)
                        .apply { topMargin = dp(2) }
                })
            })

            addView(LinearLayout(this@EventDetailActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, 0, dp(if (last) 0 else 20))   // 시안: 점 끝 54 → 글 68
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
                addView(LinearLayout(this@EventDetailActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(this@EventDetailActivity).apply {
                        text = title
                        textSize = 15.5f
                        letterSpacing = -0.015f
                        includeFontPadding = false
                        typeface = Look.bold(this@EventDetailActivity)
                        setTextColor(Look.color(Look.INK))
                        layoutParams = LinearLayout.LayoutParams(
                            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                        )
                    })
                    if (at.isNotEmpty()) addView(TextView(this@EventDetailActivity).apply {
                        text = at
                        textSize = 12.5f
                        letterSpacing = -0.015f
                        includeFontPadding = false
                        setTextColor(Look.color(Look.INK_SOFT))
                    })
                })
                addView(TextView(this@EventDetailActivity).apply {
                    text = desc
                    textSize = 13.5f
                    letterSpacing = -0.015f
                    includeFontPadding = false
                    setLineSpacing(dp(3).toFloat(), 1f)
                    setTextColor(Look.color(Look.INK_SOFT))
                    setPadding(0, dp(4), 0, 0)
                })
            })
        }

    /**
     * 사이트 확인 결과 (시안 Card/SiteVerify 1384:29).
     * 알약은 판정 등급을 그대로 말한다 — 시안의 「공식 확인」은 저위험일 때의 한 모습이다.
     */
    private fun verifyCard(risk: String, host: String, reason: String) = LinearLayout(this).apply {
        val (_, _, ink) = tone(risk)
        orientation = LinearLayout.VERTICAL
        background = Look.box(this@EventDetailActivity, Look.CARD, null, 16)
        setPadding(dp(20), dp(20), dp(20), dp(20))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        addView(LinearLayout(this@EventDetailActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@EventDetailActivity).apply {
                text = host.ifEmpty { "주소를 확인하지 못함" }
                textSize = 15.5f
                letterSpacing = -0.015f
                includeFontPadding = false
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                typeface = Look.bold(this@EventDetailActivity)
                setTextColor(Look.color(Look.INK))
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            })
            addView(chip(
                when (risk) {
                    "HIGH" -> "위험 확인"
                    "MEDIUM" -> "확인 필요"
                    else -> "공식 확인"
                },
                ink,
                when (risk) {
                    "HIGH" -> Look.DANGER_TINT
                    "MEDIUM" -> Look.WARN_TINT
                    else -> Look.MINT_TINT
                }, 24, 11.5f
            ))
        })
        addView(TextView(this@EventDetailActivity).apply {
            text = reason.ifEmpty { "판정 근거를 남기지 못했어요" }
            textSize = 13f
            letterSpacing = -0.015f
            includeFontPadding = false
            setLineSpacing(dp(4).toFloat(), 1f)
            setTextColor(Look.color(Look.INK_SOFT))
            setPadding(0, dp(10), 0, 0)
        })
    }

    /** 반복 위험 사이트 — #FFF1E0 카드에 15.5 Bold 한 줄 (시안 11G-2 꼴) */
    private fun repeatCard(host: String, count: Int) = TextView(this).apply {
        text = "$host · ${count}회 방문"
        textSize = 15.5f
        letterSpacing = -0.015f
        includeFontPadding = false
        typeface = Look.bold(this@EventDetailActivity)
        setTextColor(Look.color(MEDIUM.third))
        background = Look.box(this@EventDetailActivity, Look.WARN_TINT, null, 16)
        setPadding(dp(20), dp(18), dp(20), dp(18))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    /**
     * 알약 하나 (시안 Chip/State — r6).
     *
     * 좌우 여백이 자리마다 다르다. 등급 알약은 52에 글자 22라 15고(1384:7),
     * 왼쪽 「확인 필요」는 67에 글자 47이라 10이다.
     */
    private fun chip(
        text: String, ink: String, tint: String, height: Int, size: Float, hPad: Int = 10,
    ) =
        TextView(this).apply {
            this.text = text
            textSize = size
            gravity = Gravity.CENTER
            letterSpacing = -0.015f
            includeFontPadding = false
            typeface = Look.bold(this@EventDetailActivity)
            setTextColor(Look.color(ink))
            background = Look.pill(this@EventDetailActivity, tint, 6)
            setPadding(dp(hPad), 0, dp(hPad), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(height)
            )
        }

    /** "3분 전" · "2시간 전" · "3일 전". 시각 없으면 null */
    private fun ago(at: Date?): String? {
        if (at == null) return null
        val m = (System.currentTimeMillis() - at.time) / 60_000L
        return when {
            m < 1 -> "방금"
            m < 60 -> "${m}분 전"
            m < 60 * 24 -> "${m / 60}시간 전"
            else -> "${m / (60 * 24)}일 전"
        }
    }

    /** 오늘 것은 "오늘 ", 아니면 "8월 12일 " */
    private fun dayWord(at: Date?): String {
        if (at == null) return ""
        val c = java.util.Calendar.getInstance().apply { time = at }
        val now = java.util.Calendar.getInstance()
        val today = c.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR) &&
            c.get(java.util.Calendar.DAY_OF_YEAR) == now.get(java.util.Calendar.DAY_OF_YEAR)
        return if (today) "오늘 " else SimpleDateFormat("M월 d일 ", Locale.KOREA).format(at)
    }

    private fun dp(v: Int) = Look.dp(this, v)
}
