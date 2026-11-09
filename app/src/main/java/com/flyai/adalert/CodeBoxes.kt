package com.flyai.adalert

import android.app.Activity
import android.content.Context
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 한 글자씩 떨어진 입력 칸 (피그마 v2 · S00 CodeBox — 50×64 r14).
 *
 * 칸 세 가지 모양이 시안에 있다.
 *   채운 칸 : #F5F7FA 채움 · 테두리 없음 · 26 Bold
 *   지금 칸 : 흰 바탕 · 테두리 2 #1F63E0
 *   빈 칸   : 흰 바탕 · 테두리 1.5 #E6E9EE
 *
 * ## 왜 칸마다 EditText를 두지 않았나
 * 칸이 여섯이면 EditText도 여섯이 되고, 지우기·붙여넣기·자동완성이 칸 사이를 오가며
 * 제각각 움직인다(어르신 폰에서 특히). 그래서 **글자를 담는 것은 보이지 않는 EditText 하나**,
 * 칸은 그 값을 비추기만 한다. 아무 칸이나 누르면 그 하나에 초점이 가고 자판이 열린다.
 */
class CodeBoxes(
    private val ctx: Context,
    private val count: Int,
    private val boxW: Int,
    private val onChange: (String) -> Unit = {},
) {

    companion object {
        private const val BOX_H = 64
        private const val RADIUS = 14
        private const val GAP = 8
        private const val EMPTY_STROKE = 1.5f
        private const val FOCUS_STROKE = 2f

        /**
         * 칸 하나의 폭(dp). 시안은 390 화면에 50짜리 여섯 칸(사이 8)이라 좌우 여백 24를 뺀 폭을
         * **여섯으로 나눈다**. 네 칸짜리 비밀번호 줄도 같은 값을 써야 두 줄의 칸이 같아 보인다.
         */
        fun boxWidth(a: Activity): Int {
            val d = a.resources.displayMetrics
            val usablePx = d.widthPixels - Look.dp(a, 48) - Look.dp(a, GAP) * 5
            return ((usablePx / 6) / d.density).toInt()
        }
    }

    /** 값을 담는 자리. 화면에는 1×1로만 존재한다 (없애면 자판이 열리지 않는다) */
    val input: EditText = EditText(ctx).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        alpha = 0f
        isCursorVisible = false
        setBackgroundColor(0)
        setPadding(0, 0, 0, 0)
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        filters = arrayOf(InputFilter.LengthFilter(count))
    }

    private val cells = ArrayList<TextView>(count)

    val text: String get() = input.text.toString()

    /** 칸 여섯 + 숨은 입력칸이 함께 들어 있는 줄. 이 줄을 화면에 붙이면 된다 */
    fun view(): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        repeat(count) { i ->
            addView(FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(boxW), dp(BOX_H)).apply {
                    if (i > 0) leftMargin = dp(GAP)
                }
                isClickable = true
                setOnClickListener { focus() }
                addView(TextView(ctx).apply {
                    textSize = 26f
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    typeface = Look.bold(ctx)
                    setTextColor(Look.color(Look.INK))
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    cells.add(this)
                })
            })
        }

        addView(input, LinearLayout.LayoutParams(1, 1))

        input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                paint()
                onChange(text)
            }

            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })
        input.setOnFocusChangeListener { _, _ -> paint() }
        paint()
    }

    /** 자판 열기 — 칸을 눌렀을 때와 화면이 열릴 때 */
    fun focus() {
        input.requestFocus()
        input.setSelection(input.text.length)
        ctx.getSystemService(InputMethodManager::class.java)
            ?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun paint() {
        val v = text
        cells.forEachIndexed { i, cell ->
            cell.text = v.getOrNull(i)?.toString().orEmpty()
            val box = cell.parent as View
            box.background = when {
                i < v.length -> Look.box(ctx, Look.CARD, null, RADIUS)
                i == v.length && input.hasFocus() ->
                    Look.box(ctx, Look.ON_MINT, Look.MINT, RADIUS, FOCUS_STROKE)
                else -> Look.box(ctx, Look.ON_MINT, Look.LINE, RADIUS, EMPTY_STROKE)
            }
        }
    }

    private fun dp(v: Int) = Look.dp(ctx, v)
}
