package com.flyai.adalert

import android.app.Activity
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 단계 화면(P01~P05, S01~S04) 공용 조각.
 *
 * 값은 피그마 「GuADian · FULL SERVICE FLOW v2」 그대로.
 *   보호자(P0x): 진행 바 5dp(#EEF1F5 / #1F63E0) + "n / m" 12.5 Bold #8E97A6 · 제목 24 Bold ·
 *               카드 #F5F7FA r16 · 줄 16.5 Bold + 13.5 Regular · 구분선 1px #E6E9EE · 버튼 56 r12
 *   어르신(S0x): 제목 26 Bold/34 · 부제 16/23 · 카드 r18 · 줄 18 Bold + 15 Regular ·
 *               버튼 66 r14 19 Bold. 진행 바는 S00~S03에 있고 S04에만 없다
 *
 * 같은 모양을 화면마다 다시 적으면 간격이 조금씩 어긋남 → 시안과의 차이로 잔존.
 * 줄·스위치·구분선은 여기서 한 번만 생성.
 */
object Step {

    /** 진행 바 트랙 (피그마 #EEF1F5) */
    private const val TRACK = "#EEF1F5"
    /** 꺼진 스위치의 트랙 (피그마 P03 '소리로 함께 알리기') */
    private const val TOGGLE_OFF = "#D7DBE2"

    /** 앱바·진행 바·제목·부제·안내·버튼 일괄 채우기 */
    fun bind(
        a: Activity,
        title: String,
        step: String,
        heading: String,
        sub: String,
        notice: String,
        noticeTint: String = Look.SETUP_NOTICE,
        button: String,
        eyebrow: String? = null,
        topGap: Int? = null,
        onNext: () -> Unit,
    ) {
        a.findViewById<ImageView>(R.id.btn_back).setOnClickListener { a.finish() }
        a.findViewById<TextView>(R.id.text_title).text = title
        progress(a, step)
        a.findViewById<TextView>(R.id.text_heading).text = heading
        a.findViewById<TextView>(R.id.text_sub).apply {
            text = sub
            visibility = if (sub.isEmpty()) View.GONE else View.VISIBLE
        }
        // 피그마 P01·P02·P04: 안내 카드 #F5F7FA r16 · 14 Regular #525C6E
        a.findViewById<TextView>(R.id.text_notice).apply {
            text = notice
            visibility = if (notice.isEmpty()) View.GONE else View.VISIBLE
            setTextColor(Look.color(Look.INK_SOFT))
            background = Look.box(a, noticeTint, null, 16)
        }
        if (eyebrow != null) {
            a.findViewById<View>(R.id.row_eyebrow).visibility = View.VISIBLE
            a.findViewById<TextView>(R.id.text_eyebrow).text = eyebrow
        }
        // 진행 바 아래 첫 글까지의 거리는 화면마다 다르다 (시안: 라벨 있으면 6, 없으면 16~25).
        // progress()가 이미 padding을 만졌을 수 있어 여기서 마지막으로 덮는다
        val gap = topGap ?: if (eyebrow != null) 6 else null
        if (gap != null) a.findViewById<View>(R.id.content)
            .setPadding(dp(a, 24), dp(a, gap), dp(a, 24), 0)
        a.findViewById<Button>(R.id.btn_next).apply {
            text = button
            setOnClickListener { onNext() }
        }
    }

    /**
     * 진행 바 + "n / m" (피그마 P01~P03). `step`에서 "n/m" 추출 —
     * "보호자 2/4"처럼 앞에 말이 붙어도 숫자만 사용. 숫자 없으면 통째로 숨김.
     */
    fun progress(a: Activity, step: String) {
        val block = a.findViewById<View>(R.id.progress)
        val m = Regex("(\\d+)\\s*/\\s*(\\d+)").find(step)
        if (m == null) {
            block.visibility = View.GONE
            // 진행 바 없는 화면(P04·P05): 제목이 앱바 아래 27에서 시작
            a.findViewById<View>(R.id.content).setPadding(dp(a, 24), dp(a, 27), dp(a, 24), 0)
            return
        }
        val done = m.groupValues[1].toInt()
        val all = m.groupValues[2].toInt().coerceAtLeast(1)
        // "보호자 1/4"의 앞말은 그대로 살린다 — 시안의 라벨이 "보호자 1 / 4"라서
        val prefix = step.substring(0, m.range.first).trim()
        block.visibility = View.VISIBLE
        a.findViewById<View>(R.id.progress_track).background = Look.pill(a, TRACK, 3)
        a.findViewById<View>(R.id.progress_fill).apply {
            background = Look.pill(a, Look.MINT, 3)
            (layoutParams as LinearLayout.LayoutParams).weight = done.coerceIn(0, all).toFloat()
        }
        a.findViewById<View>(R.id.progress_rest).apply {
            (layoutParams as LinearLayout.LayoutParams).weight = (all - done).coerceAtLeast(0).toFloat()
        }
        a.findViewById<TextView>(R.id.text_step).text =
            if (prefix.isEmpty()) "$done / $all" else "$prefix $done / $all"
        block.requestLayout()
    }

    /**
     * 어르신 화면 규격으로 확대 (피그마 v2 · S00~S04).
     *
     * 라벨 15 · 제목 26/34 · 부제 16/23 · 버튼 66·r14·19 Bold.
     * 진행 바는 건드리지 않는다 — S00~S03에는 있고 [bind]가 이미 그렸다.
     * 위 여백도 건드리지 않는다 — 화면마다 달라 [bind]의 topGap이 정한다.
     */
    fun senior(a: Activity) {
        a.findViewById<TextView>(R.id.text_eyebrow).textSize = 15f
        a.findViewById<TextView>(R.id.text_heading).apply {
            textSize = 26f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) lineHeight = sp(a, 34f)
        }
        a.findViewById<TextView>(R.id.text_sub).apply {
            textSize = 16f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) lineHeight = sp(a, 23f)
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(a, 12)
        }
        a.findViewById<View>(R.id.body).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(a, 22)
        }
        // 안내 카드는 v2에서도 버튼 위 카드 그대로다(시안 S01 1200:99) —
        // 안쪽 여백만 18로 넓힌다
        a.findViewById<TextView>(R.id.text_notice)
            .setPadding(dp(a, 18), dp(a, 18), dp(a, 18), dp(a, 18))
        a.findViewById<Button>(R.id.btn_next).apply {
            layoutParams.height = dp(a, 66)
            background = a.getDrawable(R.drawable.btn_mint_senior)
            textSize = 19f
            requestLayout()
        }
    }

    /** 카드 (피그마: #F5F7FA · r16, 어르신 r18 · 테두리 없음 · 안쪽 가로 20) */
    fun card(a: Activity, senior: Boolean = false) = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        background = Look.box(a, Look.CARD, null, if (senior) 18 else 16)
        val v = dp(a, if (senior) 11 else 8)
        setPadding(dp(a, 20), v, dp(a, 20), v)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    /** 카드 위의 작은 구역 이름 (피그마 '알림' · '접근성' · '연결 상태 확인': 14 Bold #525C6E) */
    fun label(
        a: Activity,
        text: String,
        ink: String = Look.INK_SOFT,
        icon: Int = 0,
        size: Float = 14f,
    ) =
        LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(a, 10) }   // 시안 P03·P04·P05: 라벨 아래 카드까지 10
            if (icon != 0) addView(ImageView(a).apply {
                setImageResource(icon)
                layoutParams = LinearLayout.LayoutParams(dp(a, 18), dp(a, 18))
                    .apply { rightMargin = dp(a, 6) }
            })
            addView(TextView(a).apply {
                this.text = text
                textSize = size
                letterSpacing = -0.015f
                includeFontPadding = false
                typeface = Look.bold(a)
                setTextColor(Look.color(ink))
            })
        }

    /** 카드 사이 간격 (피그마: 카드 → 다음 구역 이름 22) */
    fun gap(a: Activity, height: Int = 22) = View(a).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(a, height)
        )
    }

    /** 줄 사이 선 (피그마: 1px #E6E9EE, 카드 안쪽 여백 20 안에서 끝까지) */
    fun divider(a: Activity, inset: Int = 0) = View(a).apply {
        setBackgroundColor(Look.color(Look.LINE))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(a, 1)
        ).apply { leftMargin = dp(a, inset) }
    }

    /**
     * 카드 안의 한 줄. 오른쪽에 붙는 것은 화면마다 다름 —
     * 배지(S03), 스위치(P03), 없음(P01).
     *
     * @param icon 왼쪽 아이콘(피그마 Icon/Success 등). 있으면 그대로 표시(색 덧입힘 없음)
     * @param glyph icon 없을 때 `tint` 동그라미 안에 `ink` 색으로 적는 한 글자
     * @param dot 왼쪽 아이콘/동그라미 표시 여부. 피그마 P01·P03·S03 줄에는 없음
     * @param senior 어르신 규격 — 아이콘 28 · 제목 18 · 설명 15
     */
    fun row(
        a: Activity,
        icon: Int,
        glyph: String,
        tint: String,
        ink: String,
        title: String,
        desc: String,
        right: View? = null,
        dot: Boolean = true,
        senior: Boolean = false,
        dotSize: Int = 0,
        dotGap: Int = 0,
        glyphSize: Float = 0f,
    ) = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        // 시안의 줄 높이는 64(P01 1359:9 구분선 73·138 사이)다. 글 덩어리가
        // 44이라 위아래 10씩이 남는다 — 13을 쓰면 줄마다 9씩 밀려 카드가 24 커졌다(실측)
        val v = dp(a, if (senior) 13 else 10)
        setPadding(0, v, 0, v)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        if (dot) {
            val size = dp(a, if (dotSize > 0) dotSize else if (senior) 28 else 20)
            val gap = dp(a, if (dotGap > 0) dotGap else if (senior) 16 else 12)
            addView(
                if (icon != 0) ImageView(a).apply {
                    setImageResource(icon)
                    layoutParams = LinearLayout.LayoutParams(size, size).apply { rightMargin = gap }
                } else FrameLayout(a).apply {
                    background = Look.dot(tint)
                    layoutParams = LinearLayout.LayoutParams(size, size).apply { rightMargin = gap }
                    addView(TextView(a).apply {
                        text = glyph
                        textSize =
                            if (glyphSize > 0f) glyphSize else if (senior) 15f else 12f
                        gravity = Gravity.CENTER
                        includeFontPadding = false
                        typeface = Look.bold(a)
                        setTextColor(Look.color(ink))
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                    })
                }
            )
        }

        addView(LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(a).apply {
                text = title
                textSize = if (senior) 18f else 16.5f
                letterSpacing = -0.015f
                includeFontPadding = false
                typeface = Look.bold(a)
                setTextColor(Look.color(Look.INK))
            })
            if (desc.isNotEmpty()) addView(TextView(a).apply {
                text = desc
                textSize = if (senior) 15f else 13.5f
                letterSpacing = -0.015f
                includeFontPadding = false
                setLineSpacing(dp(a, 3).toFloat(), 1f)
                setTextColor(Look.color(Look.INK_SOFT))
                // 위 틈 없음 — 제목의 자연 행높이(16.5 → 24)가 시안의
                // 제목→설명 간격 24와 같아 그것만으로 맞는다
            })
        })

        // 오른쪽 붙임(칩·스위치)은 아직 layoutParams가 없을 수 있다 —
        // 그대로 addView하면 왼쪽 여백이 사라진다. 없으면 여기서 만들어 준다
        if (right != null) addView(right, (right.layoutParams as? LinearLayout.LayoutParams
            ?: LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )).apply { leftMargin = dp(a, 12) })
    }

    /**
     * 아이콘 + 글 한 줄 (피그마 P04 카드 안의 목록: 아이콘 20 · 14.5 Regular, 줄 사이 32).
     * 아이콘 크기가 달라도 글 시작점은 카드 안쪽 [textStart]로 고정 —
     * P04는 32, 어르신 화면(S02)은 40이다.
     */
    fun line(
        a: Activity,
        icon: Int,
        text: String,
        ink: String = Look.INK,
        iconSize: Int = 20,
        textSize: Float = 14.5f,
        bold: Boolean = false,
        textStart: Int = 32,
    ) = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(a, 5), 0, dp(a, 5))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        addView(ImageView(a).apply {
            setImageResource(icon)
            layoutParams = LinearLayout.LayoutParams(dp(a, iconSize), dp(a, iconSize))
                .apply { rightMargin = dp(a, textStart - iconSize) }
        })
        addView(TextView(a).apply {
            this.text = text
            this.textSize = textSize
            letterSpacing = -0.015f
            includeFontPadding = false
            setLineSpacing(dp(a, 3).toFloat(), 1f)
            if (bold) typeface = Look.bold(a)
            setTextColor(Look.color(ink))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
    }

    /** 알약 배지 (피그마 P02 '표시만'·S03 '필수': 높이 24~26 · r999 · 12~13 Bold · 안쪽 가로 10) */
    fun badge(a: Activity, text: String, ink: String, tint: String) = TextView(a).apply {
        this.text = text
        textSize = 12f
        letterSpacing = -0.015f
        gravity = Gravity.CENTER
        includeFontPadding = false
        typeface = Look.bold(a)
        setTextColor(Look.color(ink))
        background = Look.pill(a, tint, 999)
        setPadding(dp(a, 10), dp(a, 6), dp(a, 10), dp(a, 6))
    }

    /**
     * 켜고 끄는 스위치 (피그마 P03: 트랙 50×30 r15 · 동그라미 24 흰색 · ON #1F63E0 · OFF #D7DBE2).
     *
     * 안드로이드 기본 Switch 미사용 — 제조사마다 두께·색이 달라 삼성폰에서는
     * 시안과 전혀 다른 모양. 트랙과 동그라미 직접 그리기.
     */
    fun toggle(a: Activity, on: Boolean, onChange: (Boolean) -> Unit): View {
        val track = FrameLayout(a)
        val thumb = View(a)
        var state = on
        fun paint() {
            track.background = Look.pill(a, if (state) Look.MINT else TOGGLE_OFF, 15)
            thumb.background = Look.dot(Look.ON_MINT)
            (thumb.layoutParams as FrameLayout.LayoutParams).gravity =
                Gravity.CENTER_VERTICAL or (if (state) Gravity.END else Gravity.START)
            thumb.requestLayout()
        }
        thumb.layoutParams = FrameLayout.LayoutParams(dp(a, 24), dp(a, 24))
            .apply { setMargins(dp(a, 3), 0, dp(a, 3), 0) }
        track.addView(thumb)
        track.layoutParams = LinearLayout.LayoutParams(dp(a, 50), dp(a, 30))
        track.isClickable = true
        track.setOnClickListener {
            state = !state
            paint()
            onChange(state)
        }
        paint()
        return track
    }

    private fun dp(a: Activity, v: Int) = Look.dp(a, v)

    private fun sp(a: Activity, v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, v, a.resources.displayMetrics
    ).toInt()
}
