package com.flyai.adalert

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 두 홈 화면 공용 — 아래 탭바와 카드 조각.
 *
 * 탭바는 시안에서 첫 등장. 이전에는 첫 화면에 버튼 누적 → 화면이 늘수록 첫 화면 길어짐.
 * 네 칸 분리로 늘어난 화면들이 각자 자리 확보 — 홈은 지금 상태, 알림은 기록, 가족은 사람, 내 정보는 설정.
 *
 * 생김새: 피그마 「FULL SERVICE FLOW v2」 H1·H2·A01의 Nav/Item 기준 —
 * 높이 68 · 흰 바탕 · 위 1px #E6E9EE 선 · 아이콘 24(위 12) · 글자 11.5 Bold(위 43) ·
 * 선택 #1F63E0 · 비선택 #8E97A6.
 */
object Home {

    val TABS_GUARDIAN = listOf("홈", "알림", "안심요약", "내 정보")
    val TABS_SENIOR = listOf("홈", "보호기록", "내 정보")

    /** 탭 이름 → 피그마 Nav/Icon. 이름이 곧 자리 → 표 형태 */
    private fun icon(name: String) = when (name) {
        "홈" -> R.drawable.ic_tab_home
        "알림" -> R.drawable.ic_tab_bell
        "안심요약" -> R.drawable.ic_tab_chart
        "보호기록" -> R.drawable.ic_tab_shield
        else -> R.drawable.ic_tab_person
    }

    /** 아래 탭바. [here]는 지금 화면의 자리(0부터) */
    fun tabs(a: Activity, here: Int, labels: List<String>, go: (Int) -> Unit) {
        val bar = a.findViewById<LinearLayout>(R.id.tabs)
        bar.removeAllViews()
        labels.forEachIndexed { i, name ->
            val on = i == here
            val tint = Look.color(if (on) Look.MINT else Look.INK_MUTED)
            bar.addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, Look.dp(a, 12), 0, 0)
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1f
                )
                isClickable = true
                setOnClickListener { if (i != here) go(i) }
                addView(ImageView(a).apply {
                    setImageResource(icon(name))
                    imageTintList = ColorStateList.valueOf(tint)
                    layoutParams = LinearLayout.LayoutParams(Look.dp(a, 24), Look.dp(a, 24))
                })
                addView(TextView(a).apply {
                    text = name
                    textSize = 11.5f
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    typeface = Look.bold(a)
                    setTextColor(tint)
                    setPadding(0, Look.dp(a, 7), 0, 0)
                })
            })
        }
    }

    /** 화면 열기 단축 경로 */
    fun open(a: Activity, target: Class<*>) {
        a.startActivity(Intent(a, target))
    }

    /** 섹션 라벨 ("오늘의 기록"). 피그마 H2·A01G: 14 Bold #525C6E · 카드 위 6 · 앞 카드 아래 22 */
    fun label(a: Activity, text: String, size: Float = 14f) = TextView(a).apply {
        this.text = text
        textSize = size
        letterSpacing = -0.015f
        includeFontPadding = false
        typeface = Look.bold(a)
        setTextColor(Look.color(Look.INK_SOFT))
        setPadding(0, Look.dp(a, 20), 0, Look.dp(a, 6))
    }

    /** 카드 — DS/Board v2: #F5F7FA · r16 · 안쪽 여백 20 · 테두리 없음 */
    fun card(a: Activity, radius: Int = Look.RADIUS_CARD) = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        background = Look.pill(a, Look.CARD, radius)
        setPadding(Look.dp(a, 20), Look.dp(a, 20), Look.dp(a, 20), Look.dp(a, 20))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = Look.dp(a, 12) }
    }

    /**
     * 머리글 오른쪽의 동그란 이니셜 (피그마 H1·H2: 40 원 #E8F0FF · 글자 16 Bold #1F63E0).
     * A01의 프로필 카드는 같은 것을 64·26으로 확대 사용.
     */
    fun avatar(a: Activity, initial: String, size: Int = 40, textSp: Float = 16f) = TextView(a).apply {
        text = initial
        textSize = textSp
        gravity = Gravity.CENTER
        letterSpacing = -0.015f
        includeFontPadding = false
        typeface = Look.bold(a)
        setTextColor(Look.color(Look.MINT))
        background = Look.dot(Look.MINT_TINT)
        layoutParams = LinearLayout.LayoutParams(Look.dp(a, size), Look.dp(a, size))
    }

    /**
     * 워드마크 "GuADian".
     *
     * 기본은 Gu·ian은 잉크, AD만 초록(#17876B)으로 갈라 적는 모양이다.
     * 피그마 v2의 홈(H1·H2·E01·E02)은 글자 전체를 한 색(#1F63E0)으로 쓰므로
     * 그럴 때는 [tint]를 넘긴다.
     */
    fun wordmark(a: Activity, sp: Float = 20f, tint: String? = null) = TextView(a).apply {
        val s = SpannableString("GuADian")
        s.setSpan(
            ForegroundColorSpan(Look.color(tint ?: Look.INK)), 0, 2,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        s.setSpan(
            ForegroundColorSpan(Look.color(tint ?: "#17876B")), 2, 4,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        s.setSpan(
            ForegroundColorSpan(Look.color(tint ?: Look.INK)), 4, 7,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        text = s
        textSize = sp
        letterSpacing = 0.01f
        includeFontPadding = false
        typeface = Look.bold(a)
    }

    /**
     * 화면 아래 보조 버튼 (DS Secondary: 342×56 · r12 · #EEF1F5 · 16 Medium #39414F).
     * 어르신 화면은 [senior]로 66 · r14 · 19 Bold #1C2431 (피그마 H1 '자녀에게 연락하기').
     */
    fun secondary(a: Activity, label: String, senior: Boolean = false, onTap: () -> Unit) =
        TextView(a).apply {
            text = label
            textSize = if (senior) 19f else 16f
            letterSpacing = -0.015f
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = if (senior) Look.bold(a) else Look.medium(a)
            setTextColor(Look.color(if (senior) "#1C2431" else Look.INK_BODY))
            background = Look.pill(a, Look.LINE_SOFT, if (senior) 14 else Look.RADIUS_BUTTON)
            isClickable = true
            setOnClickListener { onTap() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Look.dp(a, if (senior) 66 else 56)
            ).apply { bottomMargin = Look.dp(a, 12) }
        }
}
