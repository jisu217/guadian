package com.flyai.adalert

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 도움 보내기 (피그마 v2 · 09G · 1039:104) — 위험 기록을 본 보호자가 어르신에게 보내는 한마디.
 *
 * 빈 칸에서 글을 지어내면 급할 때 손이 멈춘다. 그래서 **문구를 고르게** 한다 —
 * 시안의 「보낼 안내 선택」 세 줄이 그것이고, 첫 줄은 이벤트 유형에 맞춰 미리 골라 둔다.
 *
 * ## 현재는 문자 전송만
 * 시안에는 어르신 화면에 크게 뜨는 안내가 전제되어 있지만 그 경로(푸시 → 어르신 폰 표시)는
 * 아직 없다. 사실이 아닌 안내를 적지 않으려고 미리보기 카드 아래에 「문자로 전달돼요」를 둔다.
 * 어르신 전화번호는 [Family]에 저장하지 않으므로 번호 없이 문자 앱만 연다.
 */
class HelpSendActivity : Activity() {

    private companion object {
        const val CALL = 0
        const val SMS = 1

        /** 시안 09G의 세 줄 그대로 */
        val GUIDES = listOf(
            "해당 앱을 설치하지 마세요",
            "현재 사이트를 닫아주세요",
            "필요하면 저에게 전화해주세요",
        )

        /** 시안 1385:12 — 「메시지」 버튼의 글자색 */
        const val SECOND_INK = "#1C2431"
    }

    private var picked = 0
    private var how = CALL
    private var seniorName = "가족"

    private lateinit var guideCard: LinearLayout
    private lateinit var preview: TextView
    private lateinit var toText: TextView
    private val ways = arrayOfNulls<LinearLayout>(2)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_help_send)
        Insets.apply(this)

        // [EventDetailActivity]와 같은 extra 묶음. 여기서 쓰는 것은 유형·어르신 이름뿐
        val type = intent.getStringExtra("type").orEmpty()
        val who = intent.getStringExtra("who").orEmpty()
        picked = defaultGuide(type)

        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }

        val body = findViewById<LinearLayout>(R.id.body)

        // ── 바로 연락하기 ────────────────────────────────────────────────────
        body.addView(label("바로 연락하기", 0))
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            addView(way(CALL, R.drawable.ic_h2_phone, "전화하기").also { ways[CALL] = it }.apply {
                (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(14)
            })
            addView(way(SMS, R.drawable.ic_09_message, "메시지").also { ways[SMS] = it })
        })

        // ── 보낼 안내 선택 ───────────────────────────────────────────────────
        body.addView(label("보낼 안내 선택", 30))
        guideCard = Step.card(this).apply { setPadding(dp(20), 0, dp(20), 0) }
        body.addView(guideCard)

        // ── 전송 미리보기 ────────────────────────────────────────────────────
        body.addView(label("전송 미리보기", 20))
        body.addView(previewCard())

        paint()

        if (who.isNotEmpty()) {
            seniorName = who
            paint()
        } else Family.profiles { all ->
            val senior = all.firstOrNull { it.isSenior }?.name
            if (senior != null) runOnUiThread { seniorName = senior; paint() }
        }
    }

    /**
     * 유형에 맞는 첫 문구. 기록의 type extra = [Family.kindOf]가 만든 라벨.
     * 설치 유도면 「설치하지 마세요」, 사이트·검색이면 「닫아주세요」.
     */
    private fun defaultGuide(type: String) = when (type) {
        Family.KIND_APK -> 0
        Family.KIND_DOMAIN, Family.KIND_SEARCH -> 1
        else -> 0
    }

    /** 구역 이름 (시안: 14 Bold #525C6E · 아래 10) */
    private fun label(text: String, top: Int) = TextView(this).apply {
        this.text = text
        textSize = 14f
        letterSpacing = -0.015f
        includeFontPadding = false
        typeface = Look.bold(this@HelpSendActivity)
        setTextColor(Look.color(Look.INK_SOFT))
        setPadding(0, dp(top), 0, dp(10))
    }

    /** 시안 1385:5/9 — 164×56 r12 · 아이콘 20 · 15.5 Bold. 고른 쪽만 파랑 */
    private fun way(which: Int, icon: Int, text: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        isClickable = true
        layoutParams = LinearLayout.LayoutParams(0, dp(56), 1f)
        addView(ImageView(this@HelpSendActivity).apply {
            setImageResource(icon)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
                .apply { rightMargin = dp(8) }
        })
        addView(TextView(this@HelpSendActivity).apply {
            this.text = text
            textSize = 15.5f
            letterSpacing = -0.015f
            includeFontPadding = false
            typeface = Look.bold(this@HelpSendActivity)
        })
        setOnClickListener { how = which; paint() }
    }

    /** 시안 Card/Preview 1385:25 — #F5F7FA r16 · 받는 사람 12.5 → 이름 15 Bold → 선 → 문구 15/23 */
    private fun previewCard() = Step.card(this).apply {
        setPadding(dp(20), dp(18), dp(20), dp(20))
        addView(TextView(this@HelpSendActivity).apply {
            text = "받는 사람"
            textSize = 12.5f
            letterSpacing = -0.015f
            includeFontPadding = false
            setTextColor(Look.color(Look.INK_MUTED))
        })
        toText = TextView(this@HelpSendActivity).apply {
            textSize = 15f
            letterSpacing = -0.015f
            includeFontPadding = false
            typeface = Look.bold(this@HelpSendActivity)
            setTextColor(Look.color(Look.INK))
            setPadding(0, dp(3), 0, dp(13))   // 시안 1385:27 — 받는 사람 끝 543 → 이름 546 → 선 580
        }
        addView(toText)
        addView(Step.divider(this@HelpSendActivity))
        preview = TextView(this@HelpSendActivity).apply {
            textSize = 15f
            letterSpacing = -0.015f
            includeFontPadding = false
            setLineSpacing(dp(4).toFloat(), 1f)
            setTextColor(Look.color(Look.INK))
            setPadding(0, dp(14), 0, 0)
        }
        addView(preview)
        // 「안내는 문자로 전달돼요」 한 줄이 여기 있었다. 시안 Card/Preview(1385:25)는
        // 받는 사람 · 이름 · 선 · 문구까지 152로 끝난다 — 아래 버튼이 「안내 보내고
        // 전화하기」라 무엇으로 가는지는 그 글자가 이미 말한다
    }

    /** 고른 것에 맞춰 버튼·목록·미리보기를 한 번에 다시 칠한다 */
    private fun paint() {
        ways.forEachIndexed { i, v ->
            val on = how == i
            v?.background = Look.box(
                this, if (on) Look.MINT else Look.LINE_SOFT, null, 12
            )
            (v?.getChildAt(0) as? ImageView)?.imageTintList =
                android.content.res.ColorStateList.valueOf(
                    Look.color(if (on) Look.ON_MINT else SECOND_INK)
                )
            (v?.getChildAt(1) as? TextView)?.setTextColor(
                Look.color(if (on) Look.ON_MINT else SECOND_INK)
            )
        }

        guideCard.removeAllViews()
        GUIDES.forEachIndexed { i, text ->
            if (i > 0) guideCard.addView(Step.divider(this))
            guideCard.addView(guideRow(i, text))
        }

        toText.text = "${seniorName}님"
        preview.text = "“${GUIDES[picked]}.\n" +
            (if (how == CALL) "제가 곧 전화드릴게요.”" else "필요하면 답장 주세요.”")
    }

    /** 시안 List/Guide 1385:14 — 동그라미 22 · 15 · 줄 높이 52 */
    private fun guideRow(i: Int, text: String) = LinearLayout(this).apply {
        val on = picked == i
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(52)
        )
        addView(ImageView(this@HelpSendActivity).apply {
            setImageResource(
                if (on) R.drawable.ic_p04_agree_on else R.drawable.ic_p04_agree_off
            )
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
                .apply { rightMargin = dp(10) }
        })
        addView(TextView(this@HelpSendActivity).apply {
            this.text = text
            textSize = 15f
            letterSpacing = -0.015f
            includeFontPadding = false
            if (on) typeface = Look.bold(this@HelpSendActivity)
            setTextColor(Look.color(if (on) Look.INK else Look.INK_SOFT))
        })
        setOnClickListener { picked = i; paint() }
    }

    override fun onStart() {
        super.onStart()
        val footer = findViewById<LinearLayout>(R.id.footer)
        footer.removeAllViews()
        footer.addView(Look.bigButton(this, "안내 보내고 전화하기") { send() }
            .also { Look.mintShadow(it) }
            .apply { (layoutParams as LinearLayout.LayoutParams).bottomMargin = 0 })
    }

    /**
     * 문자 앱에 문구를 채워 연다. 번호는 저장처가 없으므로 비운다(받는 사람은 보호자가 고른다).
     * 전화하기를 골랐으면 전화 앱도 이어서 연다 — 두 앱을 차례로 보여 준 뒤 이 화면은 닫는다.
     */
    private fun send() {
        // resolveActivity는 Android 11부터 <queries> 없이 null — 바로 열고 없으면 무시
        fun open(i: Intent) = try { startActivity(i) } catch (_: ActivityNotFoundException) { }
        val body = preview.text.toString().trim('“', '”')
        open(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")).putExtra("sms_body", body))
        if (how == CALL) open(Intent(Intent.ACTION_DIAL, Uri.parse("tel:")))
        finish()
    }

    private fun dp(v: Int) = Look.dp(this, v)
}
