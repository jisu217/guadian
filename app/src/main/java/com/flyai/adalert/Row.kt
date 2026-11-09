package com.flyai.adalert

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 보호자 화면 셋(위험 이벤트·이벤트 상세·주간 리포트) 공용 줄·카드 조각.
 *
 * 값은 전부 피그마 「GuADian · FULL SERVICE FLOW v2」 실측.
 *
 * - 07G 위험 이벤트: 회색 카드(#F5F7FA · r16) 안에 **색 막대(6×44 · r3)** 줄 적층,
 *   줄 사이 1px #E6E9EE 선. 오른쪽에 등급 알약(24 · r999 · 12 Bold).                  → [card] [bar] [divider]
 * - 08G 이벤트 상세: 같은 회색 카드 안의 **타임라인** — 16 동그라미와 2px #D7DBE2 연결선,
 *   제목 16 Bold · 설명 13.5 #525C6E.                                                    → [line]
 * - 날짜·섹션 라벨: 14 Bold #525C6E.                                                    → [day]
 */
object Row {

    /** 시안 08G 타임라인의 연결선 색 */
    private const val CONNECTOR = "#D7DBE2"

    /** 연결선 탐색·비표시용 꼬리표 */
    private const val TAG_CONNECTOR = "row_connector"

    /**
     * 타임라인 한 단계 (시안 08G '무슨 일이 있었나요').
     *
     *     ●  제목                      14:17
     *     │  회색 설명
     *
     * 동그라미 색 = [ink]. 마지막 단계면 [endLine]으로 아래 연결선 제거.
     *
     * @param tint  (이전 모양의 동그라미 바탕색. 시안 변경으로 현재 미사용, 호출 측
     *              수정 회피용으로 자리만 유지)
     * @param glyph 동그라미 안 기호 — 시안의 동그라미는 비어 있어 미표시
     * @param icon  동그라미 안 그림 — 위와 같은 이유로 미표시
     * @param time  오른쪽 끝 시각. 없으면 자리 비움
     */
    @Suppress("UNUSED_PARAMETER")
    fun line(
        ctx: Context,
        tint: String,
        ink: String,
        glyph: String,
        title: String,
        sub: String,
        time: String = "",
        dim: Boolean = false,
        icon: Int = 0,
        onTap: (() -> Unit)? = null,
    ): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        // 시안: 단계 사이 간격 62 = 글 41 + 아래 여백 ~20
        setPadding(0, 0, 0, Look.dp(ctx, 20))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        if (onTap != null) {
            isClickable = true
            setOnClickListener { onTap() }
        }

        // 동그라미(16) + 아래로 내려가는 연결선(2px). 줄 높이만큼 확장 → 다음 동그라미에 접촉
        addView(FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                Look.dp(ctx, 16), LinearLayout.LayoutParams.MATCH_PARENT
            ).apply { rightMargin = Look.dp(ctx, 14) }
            addView(View(ctx).apply {
                tag = TAG_CONNECTOR
                setBackgroundColor(Look.color(CONNECTOR))
                layoutParams = FrameLayout.LayoutParams(
                    Look.dp(ctx, 2), FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER_HORIZONTAL
                ).apply { topMargin = Look.dp(ctx, 18) }
            })
            addView(View(ctx).apply {
                background = Look.dot(ink)
                layoutParams = FrameLayout.LayoutParams(Look.dp(ctx, 16), Look.dp(ctx, 16))
                    .apply { topMargin = Look.dp(ctx, 2) }
            })
        })

        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            // 제목 Bold 16 / 130% (-1.5%) · 설명 Regular 13.5 / 140% (-1.5%) — 시안 08G
            addView(TextView(ctx).apply {
                text = title
                textSize = 16f
                letterSpacing = -0.015f
                setLineSpacing(0f, 1.3f)
                includeFontPadding = false
                typeface = Look.bold(ctx)
                setTextColor(Look.color(if (dim) Look.INK_MUTED else Look.INK))
            })
            if (sub.isNotEmpty()) addView(TextView(ctx).apply {
                text = sub
                textSize = 13.5f
                letterSpacing = -0.015f
                setLineSpacing(0f, 1.4f)
                includeFontPadding = false
                setTextColor(Look.color(if (dim) Look.INK_MUTED else Look.INK_SOFT))
                setPadding(0, Look.dp(ctx, 1), 0, 0)
            })
        })

        if (time.isNotEmpty()) addView(TextView(ctx).apply {
            text = time
            textSize = 12f
            letterSpacing = -0.015f
            includeFontPadding = false
            setTextColor(Look.color(Look.INK_MUTED))
            setPadding(Look.dp(ctx, 8), Look.dp(ctx, 3), 0, 0)
        })
    }

    /** 타임라인의 마지막 단계 — 아래로 내려가는 연결선 제거 */
    fun endLine(row: LinearLayout) {
        row.findViewWithTag<View>(TAG_CONNECTOR)?.visibility = View.GONE
    }

    /**
     * 위험 이벤트 목록의 한 줄 (시안 07G).
     *
     *     ▌ 제목 15 Bold                         [등급]
     *     ▌ 10:30 · 차단됨
     *
     * 왼쪽 색 막대(6×44 · r3) = 등급, 오른쪽 알약 = 등급 이름. [card] 안에 적층,
     * 사이에 [divider] 삽입.
     *
     * @param color     막대 색 (#E01F26 · #FF8A1F · #2E7DFF)
     * @param badge     알약 글자 ('고위험' 등). 비면 알약 미표시
     * @param badgeInk  알약 글자색 · [badgeTint] 알약 바탕색
     * @param dim       보호자가 확인을 끝낸 줄 — 글자를 흐리게
     */
    fun bar(
        ctx: Context,
        color: String,
        title: String,
        sub: String,
        badge: String = "",
        badgeInk: String = Look.INK_SOFT,
        badgeTint: String = Look.LINE_SOFT,
        dim: Boolean = false,
        onTap: (() -> Unit)? = null,
    ): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.TOP
        // 시안: 카드 안쪽 가로 20 · 줄 위아래 14 (막대는 글보다 2 아래에서 시작)
        setPadding(Look.dp(ctx, 20), Look.dp(ctx, 14), Look.dp(ctx, 20), Look.dp(ctx, 14))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        if (onTap != null) {
            isClickable = true
            setOnClickListener { onTap() }
        }

        addView(View(ctx).apply {
            background = Look.pill(ctx, color, 3)
            layoutParams = LinearLayout.LayoutParams(Look.dp(ctx, 6), Look.dp(ctx, 44))
                .apply { topMargin = Look.dp(ctx, 2); rightMargin = Look.dp(ctx, 14) }
        })

        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            // 제목 Bold 15 / 130% (-1.5%) · 설명 Regular 13 / 140% (-1.5%) #8E97A6 — 시안 07G
            addView(TextView(ctx).apply {
                text = title
                textSize = 15f
                letterSpacing = -0.015f
                setLineSpacing(0f, 1.3f)
                includeFontPadding = false
                typeface = Look.bold(ctx)
                setTextColor(Look.color(if (dim) Look.INK_MUTED else Look.INK))
            })
            if (sub.isNotEmpty()) addView(TextView(ctx).apply {
                text = sub
                textSize = 13f
                letterSpacing = -0.015f
                setLineSpacing(0f, 1.4f)
                includeFontPadding = false
                setTextColor(Look.color(Look.INK_MUTED))
                setPadding(0, Look.dp(ctx, 3), 0, 0)
            })
        })

        if (badge.isNotEmpty()) addView(TextView(ctx).apply {
            // 알약: 높이 24 · r999 · 가로 여백 10 · 12 Bold (-1.5%)
            text = badge
            textSize = 12f
            letterSpacing = -0.015f
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = Look.bold(ctx)
            setTextColor(Look.color(badgeInk))
            background = Look.pill(ctx, badgeTint, 999)
            setPadding(Look.dp(ctx, 10), 0, Look.dp(ctx, 10), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, Look.dp(ctx, 24)
            ).apply { leftMargin = Look.dp(ctx, 8); topMargin = Look.dp(ctx, 4) }
        })
    }

    /**
     * 줄을 담는 회색 카드 (#F5F7FA · r16). 안쪽 여백 없음 — 줄이 제 여백 보유.
     * @param bottomGap 다음 라벨·카드까지의 간격 (시안 07G: 22)
     */
    fun card(ctx: Context, bottomGap: Int = 22): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = Look.box(ctx, Look.CARD, null, 16)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = Look.dp(ctx, bottomGap) }
    }

    /** 카드 안 줄 사이의 1px #E6E9EE 선. 양옆 20 여백 */
    fun divider(ctx: Context, inset: Int = 20): View = View(ctx).apply {
        setBackgroundColor(Look.color(Look.LINE))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, Look.dp(ctx, 1)
        ).apply { leftMargin = Look.dp(ctx, inset); rightMargin = Look.dp(ctx, inset) }
    }

    /** 목록 사이의 날짜·섹션 라벨 ("오늘") — 14 Bold #525C6E, 카드와 간격 6 (시안 07G·08G) */
    fun day(ctx: Context, label: String): TextView = TextView(ctx).apply {
        text = label
        textSize = 14f
        letterSpacing = -0.015f
        setLineSpacing(0f, 1.3f)
        includeFontPadding = false
        typeface = Look.bold(ctx)
        setTextColor(Look.color(Look.INK_SOFT))
        setPadding(0, 0, 0, Look.dp(ctx, 6))
    }

    /** 화면 아래에 붙는 회색 안내 한 줄 (시안 11G-1 · 12.5 #8E97A6 가운데) */
    fun note(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 12.5f
        letterSpacing = -0.015f
        gravity = Gravity.CENTER
        includeFontPadding = false
        setTextColor(Look.color(Look.INK_MUTED))
        setPadding(0, Look.dp(ctx, 14), 0, Look.dp(ctx, 4))
    }

    fun gap(ctx: Context, h: Int): View = Look.gap(ctx, h)
}
