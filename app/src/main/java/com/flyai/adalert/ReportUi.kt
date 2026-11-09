package com.flyai.adalert

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 주간 리포트(11G-1·2)와 상세 분석(11G-3·4)이 같이 쓰는 글줄·막대 조각.
 *
 * 값: 피그마 「FULL SERVICE FLOW v2」 11G 실측 —
 * 섹션 제목 16 Bold · 설명 13 #525C6E/145% · 안내 12 #8E97A6/150% ·
 * 막대 r6 · 최대값 = 최대 높이 · 0건 = 3 높이 #DFE3EA · 최다 칸만 주황.
 */
object ReportUi {

    /** 요일·주 막대 그래프의 0건 막대 — 시안 11G-2 값 */
    private const val BAR_ZERO = "#DFE3EA"

    /** 섹션 제목 16 Bold — 카드와 간격 6 (시안 11G-1·11G-2) */
    fun sectionTitle(ctx: Context, t: String, bottom: Int = 6) = TextView(ctx).apply {
        text = t
        textSize = 16f
        letterSpacing = -0.015f
        setLineSpacing(0f, 1.3f)
        includeFontPadding = false
        typeface = Look.bold(ctx)
        setTextColor(Look.color(Look.INK))
        layoutParams = wide().apply { bottomMargin = Look.dp(ctx, bottom) }
    }

    /** 제목 아래 설명 13 #525C6E (시안 "수요일에 위험 행동이 가장 많이 발생했어요") */
    fun sub(ctx: Context, t: String, bottom: Int = 12) = TextView(ctx).apply {
        text = t
        textSize = 13f
        letterSpacing = -0.015f
        setLineSpacing(0f, 1.45f)
        includeFontPadding = false
        setTextColor(Look.color(Look.INK_SOFT))
        layoutParams = wide().apply { bottomMargin = Look.dp(ctx, bottom) }
    }

    /** 카드 아래 왼쪽 정렬 안내 12 #8E97A6 (시안 11G-3·11G-4) */
    fun hint(ctx: Context, t: String, bottom: Int = 24) = TextView(ctx).apply {
        text = t
        textSize = 12f
        letterSpacing = -0.015f
        setLineSpacing(0f, 1.5f)
        includeFontPadding = false
        setTextColor(Look.color(Look.INK_MUTED))
        layoutParams = wide().apply { bottomMargin = Look.dp(ctx, bottom) }
    }

    /**
     * 막대 그래프 (시안 11G-2 요일 일곱 개 · 11G-4 주 네 개).
     * 최대값 = [maxH] 높이, 0건 = 3 높이 회색. 최다 칸만 주황, 나머지 파랑.
     * 위 건수 12 Bold, 아래 이름 12. 칸 너비 균등 분할.
     */
    fun bars(
        ctx: Context,
        counts: IntArray,
        labels: List<String>,
        barW: Int,
        maxH: Int,
    ) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.BOTTOM
        // 카드 안쪽: 위 18 · 아래 12 · 가로 24
        setPadding(Look.dp(ctx, 24), Look.dp(ctx, 18), Look.dp(ctx, 24), Look.dp(ctx, 12))
        layoutParams = wide()

        val top = (counts.maxOrNull() ?: 0).coerceAtLeast(1)
        labels.forEachIndexed { i, label ->
            val n = counts.getOrElse(i) { 0 }
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(ctx).apply {
                    text = "$n"
                    textSize = 12f
                    letterSpacing = -0.015f
                    includeFontPadding = false
                    typeface = Look.bold(ctx)
                    setTextColor(Look.color(if (n == 0) Look.INK_MUTED else Look.INK))
                })
                addView(View(ctx).apply {
                    background = Look.pill(
                        ctx,
                        when {
                            n == 0 -> BAR_ZERO
                            n == top -> Look.WARN
                            else -> Look.UNKNOWN
                        }, 6
                    )
                    layoutParams = LinearLayout.LayoutParams(
                        Look.dp(ctx, barW),
                        if (n == 0) Look.dp(ctx, 3) else Look.dp(ctx, maxH * n / top)
                    ).apply { topMargin = Look.dp(ctx, 6) }
                })
                addView(TextView(ctx).apply {
                    text = label
                    textSize = 12f
                    letterSpacing = -0.015f
                    includeFontPadding = false
                    setTextColor(Look.color(Look.INK_MUTED))
                    setPadding(0, Look.dp(ctx, 10), 0, 0)
                })
            })
        }
    }

    fun wide() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    )
}
