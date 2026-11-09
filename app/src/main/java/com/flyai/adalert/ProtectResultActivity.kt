package com.flyai.adalert

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 보호 결과 (피그마 v2 · 10G · 1039:139) — 위험 기록 한 건의 **결말** 한 화면.
 *
 * 이벤트 상세(08G)가 "진행 과정"이라면 여기는 결론이다. 가운데 체크 하나와 한 줄,
 * 그 아래에 무엇이 일어나지 **않았는지**(설치되지 않음 · 원래 화면으로 돌아옴)를 적는다.
 * 막았다는 말보다 "아무 일도 없었다"가 보호자에게 필요한 문장이다.
 *
 * ## 줄은 기록 기준
 * 시안의 세 줄을 그대로 찍지 않는다. 기록의 유형(APK·도메인·검색)·차단 여부·등급으로
 * 실제로 일어난 것만 줄로 만든다. 보호자 알림은 기록에 없으므로 알림 규칙
 * ([Family.logEvent]: 고위험 또는 같은 곳 재조우)을 여기서 같이 적용해 계산한다.
 *
 * 받는 extra는 [EventDetailActivity]와 같은 묶음이고, 「확인 완료로 처리」는 상세 화면의
 * 「확인 완료」와 같은 [Family.markDone]을 부른 뒤 목록([EventListActivity])으로 돌아간다.
 */
class ProtectResultActivity : Activity() {

    private val hhmm = SimpleDateFormat("a h:mm", Locale.KOREA)
    private val hm = SimpleDateFormat("HH:mm", Locale.KOREA)

    private lateinit var footer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_protect_result)
        Insets.apply(this)

        var id = intent.getStringExtra("id").orEmpty()
        var risk = intent.getStringExtra("risk").orEmpty()
        var type = intent.getStringExtra("type").orEmpty()
        var blocked = intent.getBooleanExtra("blocked", false)
        var done = intent.getBooleanExtra("done", false)
        var repeat = intent.getIntExtra("repeat", 1)
        val atMs = intent.getLongExtra("atMs", 0L)
        val at = if (atMs > 0) Date(atMs) else null

        // 기록 없이 열린 경우(미리보기): 시안 10G의 예시 — 설치 파일 차단 고위험 한 건
        if (id.isEmpty() && intent.getBooleanExtra("preview", false)) {
            risk = "HIGH"; type = Family.KIND_APK; blocked = true; done = false; repeat = 1
        }

        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }
        val body = findViewById<LinearLayout>(R.id.body)

        body.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_p06_success)
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(28)
            }
        })
        body.addView(TextView(this).apply {
            text = if (blocked) "안전하게 중단했어요" else "위험을 안내했어요"
            textSize = 26f
            gravity = Gravity.CENTER
            letterSpacing = -0.015f
            includeFontPadding = false
            setLineSpacing(dp(8).toFloat(), 1f)
            typeface = Look.bold(this@ProtectResultActivity)
            setTextColor(Look.color(Look.INK))
            setPadding(0, dp(20), 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        })
        body.addView(TextView(this).apply {
            text = buildString {
                at?.let { append(dayWord(it)); append(hhmm.format(it)); append(" · ") }
                append("대응 종료")
            }
            textSize = 14f
            gravity = Gravity.CENTER
            letterSpacing = -0.015f
            includeFontPadding = false
            setTextColor(Look.color(Look.INK_MUTED))
            setPadding(0, dp(8), 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        })

        // ── 결과 (시안 Card/Outcome 1386:8 — 체크 22 + 15.5 Bold, 줄 간격 40) ──
        body.addView(outcomeCard(outcomes(type, blocked)))

        // ── 서비스가 도운 과정 ───────────────────────────────────────────────
        body.addView(label("서비스가 도운 과정"))
        body.addView(timeline(steps(type, risk, blocked, repeat, at)))

        body.addView(TextView(this).apply {
            text = "이 기록은 가족 보호 리포트에 저장됩니다."
            textSize = 13f
            letterSpacing = -0.015f
            includeFontPadding = false
            setLineSpacing(dp(4).toFloat(), 1f)
            setTextColor(Look.color(Look.INK_MUTED))
            setPadding(0, dp(18), 0, dp(4))
        })

        // ── 아래 버튼 ────────────────────────────────────────────────────────
        footer = findViewById(R.id.footer)
        val btn = Look.bigButton(this, if (done) "확인 완료됨" else "확인 완료로 처리") {}
        Look.mintShadow(btn)
        btn.isEnabled = !done
        btn.alpha = if (done) 0.5f else 1f
        btn.setOnClickListener {
            // 기록 없음(미리보기) → 표시할 줄 없음 — 목록으로만 이동
            if (id.isEmpty()) { toList(); return@setOnClickListener }
            btn.isEnabled = false
            Family.markDone(id) { ok ->
                runOnUiThread {
                    if (ok) toList() else {
                        btn.isEnabled = true
                        note("확인 표시를 저장하지 못했습니다. 잠시 뒤 다시 눌러 주세요.")
                    }
                }
            }
        }
        footer.addView(btn.apply { (layoutParams as LinearLayout.LayoutParams).bottomMargin = 0 })
    }

    /** 구역 이름 (시안: 14 Bold #525C6E · 카드까지 10) */
    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        letterSpacing = -0.015f
        includeFontPadding = false
        typeface = Look.bold(this@ProtectResultActivity)
        setTextColor(Look.color(Look.INK_SOFT))
        setPadding(0, dp(20), 0, dp(10))
    }

    /**
     * 「무슨 일이 일어나지 않았는가」 세 줄. 시안 문구를 기록에 맞춰 고른다 —
     * 설치 유도였는지 이동이었는지에 따라 첫 줄이 다르고, 막지 못한 기록에는
     * 「설치되지 않음」을 쓸 수 없다.
     */
    private fun outcomes(type: String, blocked: Boolean): List<String> {
        val out = mutableListOf<String>()
        if (type == Family.KIND_APK) {
            out.add(if (blocked) "설치되지 않음" else "설치 화면을 안내함")
        } else {
            out.add(if (blocked) "위험한 곳으로 가지 않음" else "위험한 곳임을 안내함")
        }
        if (blocked) out.add("원래 화면으로 돌아옴")
        out.add(if (blocked) "경고 후 이동을 취소함" else "경고를 보고 사용자가 확인함")
        return out
    }

    /** 시안 Card/Outcome — #F5F7FA r16 · 안쪽 20 · 체크 22 + 15.5 Bold · 줄 간격 40 */
    private fun outcomeCard(items: List<String>) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Look.box(this@ProtectResultActivity, Look.CARD, null, 16)
        setPadding(dp(20), dp(20), dp(20), dp(20))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(28) }

        items.forEachIndexed { i, text ->
            addView(LinearLayout(this@ProtectResultActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(if (i == 0) 0 else 18), 0, 0)
                addView(ImageView(this@ProtectResultActivity).apply {
                    setImageResource(R.drawable.ic_p04_check)
                    layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
                        .apply { rightMargin = dp(10) }
                })
                addView(TextView(this@ProtectResultActivity).apply {
                    this.text = text
                    textSize = 15.5f
                    letterSpacing = -0.015f
                    includeFontPadding = false
                    typeface = Look.bold(this@ProtectResultActivity)
                    setTextColor(Look.color(Look.INK))
                })
            })
        }
    }

    /** 과정 한 걸음 — 제목 · 설명 · 시각 */
    private class Step(val title: String, val desc: String, val at: String)

    /**
     * 서비스가 한 일. 시안은 셋(경고 → 안내 → 최종 행동)이고 우리도 그 순서를 따르되
     * 실제로 한 것만 남긴다. 시각은 기록에 하나뿐이라 마지막 걸음에만 적는다 —
     * 없는 시각을 지어내면 그게 곧 거짓 기록이다.
     */
    private fun steps(
        type: String, risk: String, blocked: Boolean, repeat: Int, at: Date?,
    ): List<Step> {
        val out = mutableListOf<Step>()
        if (type == Family.KIND_APK) {
            out.add(Step("앱 설치 안내", "설치하지 않도록 설명했어요", ""))
        } else {
            out.add(Step("위험 이동 경고", "위험 사이트 안내를 표시했어요", ""))
        }
        if (risk == "HIGH" || repeat >= 2) {
            out.add(Step("보호자 안내", "가족에게 알림을 보냈어요", ""))
        }
        out.add(Step(
            "최종 행동",
            if (blocked) "경고 후 취소했어요" else "안내를 보고 사용자가 확인했어요",
            at?.let { hm.format(it) } ?: ""
        ))
        return out
    }

    /** 시안 Card/Timeline 1386:19 — 점 10 · 선 2px #E6E9EE · 제목 15.5 Bold + 오른쪽 시각 12.5 */
    private fun timeline(steps: List<Step>) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Look.box(this@ProtectResultActivity, Look.CARD, null, 16)
        setPadding(dp(20), dp(20), dp(20), dp(20))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        steps.forEachIndexed { i, s -> addView(step(s, i == steps.lastIndex)) }
    }

    private fun step(s: Step, last: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        addView(LinearLayout(this@ProtectResultActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            // 시안 1386:20 — 점이 x=44(카드 안쪽 20)에서 시작하고 글이 68이다.
            // 이 칸을 24로 두면 점이 가운데로 밀려 글까지 21 안쪽으로 들어간다
            layoutParams = LinearLayout.LayoutParams(dp(10), LinearLayout.LayoutParams.MATCH_PARENT)
            addView(View(this@ProtectResultActivity).apply {
                background = Look.dot(if (last) Look.MINT else "#D7DBE2")
                layoutParams = LinearLayout.LayoutParams(dp(10), dp(10))
                    .apply { topMargin = dp(6) }
            })
            if (!last) addView(View(this@ProtectResultActivity).apply {
                setBackgroundColor(Look.color(Look.LINE))
                layoutParams = LinearLayout.LayoutParams(dp(2), 0, 1f)
                    .apply { topMargin = dp(2) }
            })
        })

        addView(LinearLayout(this@ProtectResultActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, dp(if (last) 0 else 18))   // 시안: 점 끝 54 → 글 68
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
            addView(LinearLayout(this@ProtectResultActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@ProtectResultActivity).apply {
                    text = s.title
                    textSize = 15.5f
                    letterSpacing = -0.015f
                    includeFontPadding = false
                    typeface = Look.bold(this@ProtectResultActivity)
                    setTextColor(Look.color(Look.INK))
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    )
                })
                if (s.at.isNotEmpty()) addView(TextView(this@ProtectResultActivity).apply {
                    text = s.at
                    textSize = 12.5f
                    letterSpacing = -0.015f
                    includeFontPadding = false
                    setTextColor(Look.color(Look.INK_SOFT))
                })
            })
            addView(TextView(this@ProtectResultActivity).apply {
                text = s.desc
                textSize = 13.5f
                letterSpacing = -0.015f
                includeFontPadding = false
                setLineSpacing(dp(3).toFloat(), 1f)
                setTextColor(Look.color(Look.INK_SOFT))
                setPadding(0, dp(4), 0, 0)
            })
        })
    }

    /** 오늘 것은 "오늘 ", 아니면 "8월 12일 " */
    private fun dayWord(at: Date): String {
        val c = java.util.Calendar.getInstance().apply { time = at }
        val now = java.util.Calendar.getInstance()
        val today = c.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR) &&
            c.get(java.util.Calendar.DAY_OF_YEAR) == now.get(java.util.Calendar.DAY_OF_YEAR)
        return if (today) "오늘 " else SimpleDateFormat("M월 d일 ", Locale.KOREA).format(at)
    }

    /** 버튼 아래 회색 한 줄 — 저장 실패 시에만 */
    private fun note(text: String) {
        footer.findViewWithTag<TextView>("note")?.let { footer.removeView(it) }
        footer.addView(Row.note(this, text).apply { tag = "note" })
    }

    /**
     * 기록 목록으로 이동. 보통 [EventListActivity] → [EventDetailActivity] → 여기 순 스택 →
     * CLEAR_TOP으로 목록까지 정리. 홈에서 바로 온 경우 목록 새로 열기.
     */
    private fun toList() {
        startActivity(
            Intent(this, EventListActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }

    private fun dp(v: Int) = Look.dp(this, v)
}
