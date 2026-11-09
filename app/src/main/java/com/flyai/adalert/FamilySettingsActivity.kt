package com.flyai.adalert

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 가족별 설정 (피그마 v2 · 11F · 1401:42) — 내 정보의 「연결된 가족」 줄이 여는 화면.
 *
 * 가족이 여럿일 수 있으므로 설정이 **사람 아래** 놓인다. 이 화면 자체는 고르는 곳이
 * 아니라 문 두 개(알림 범위 · 허용 목록)와 연결 해제를 모아 둔 자리다.
 *
 * ## 아직 사람마다 나뉘지 않는다
 * 알림·허용 목록은 지금 이 폰 하나에만 저장된다([AlertSettingsActivity]).
 * 가족이 둘 이상이면 두 사람이 같은 값을 보게 되므로, 사람별로 갈라 저장하기 전까지는
 * 이 화면도 「이 폰의 설정」을 사람 이름 아래에서 여는 문에 가깝다. 갈라 저장하는 일은
 * 값의 소유자를 가족 문서로 옮기는 작업이라 이 화면이 아니라 [Family] 쪽 몫이다.
 */
class FamilySettingsActivity : Activity() {

    companion object {
        const val EXTRA_NAME = "name"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_family_settings)
        Insets.apply(this)

        val name = intent.getStringExtra(EXTRA_NAME)?.takeIf { it.isNotBlank() } ?: "가족"
        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.text_title).text = "${name}님 설정"
        findViewById<TextView>(R.id.text_lead).text = "${name}님의 보호 방식을 관리해요"

        val body = findViewById<LinearLayout>(R.id.body)
        body.addView(stateCard())

        body.addView(TextView(this).apply {
            text = "보호 설정"
            textSize = 14f
            letterSpacing = -0.015f
            includeFontPadding = false
            typeface = Look.bold(this@FamilySettingsActivity)
            setTextColor(Look.color(Look.INK_SOFT))
            setPadding(0, dp(24), 0, dp(10))
        })
        body.addView(Row.card(this, bottomGap = 0).apply {
            setPadding(dp(20), dp(6), dp(20), dp(6))
            addView(menuRow(
                R.drawable.ic_11f_bell, "알림 범위", "차단 즉시 알림"
            ) { startActivity(Intent(this@FamilySettingsActivity, AlertSettingsActivity::class.java)) })
            addView(menuRow(
                R.drawable.ic_11f_list, "항상 허용 앱·사이트", allowSummary()
            ) { startActivity(Intent(this@FamilySettingsActivity, AllowListActivity::class.java)) })
        })

        body.addView(unlinkButton(name))
        body.addView(TextView(this).apply {
            text = "연결을 해제하면 이 휴대폰의 보호가 멈추고\n리포트도 더 이상 오지 않아요."
            textSize = 12.5f
            letterSpacing = -0.015f
            includeFontPadding = false
            setLineSpacing(dp(4).toFloat(), 1f)
            setTextColor(Look.color(Look.INK_MUTED))
            setPadding(0, dp(14), 0, 0)
        })
    }

    /** "앱 2 · 사이트 3" — 목록에서 직접 센다. 이름에 점이 있으면 사이트 */
    private fun allowSummary(): String {
        val all = AlertSettingsActivity.allowed(this).keys
        val apps = all.count { !it.contains('.') }
        return "앱 ${apps} · 사이트 ${all.size - apps}"
    }

    /** 시안 1401:42 — #F5F7FA r16 · 점 10 + 「연결됨」 13 · 굵은 한 줄 · 오른쪽 「상세」 알약 */
    private fun stateCard() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = Look.box(this@FamilySettingsActivity, Look.CARD, null, 16)
        setPadding(dp(20), dp(18), dp(20), dp(18))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        addView(LinearLayout(this@FamilySettingsActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(LinearLayout(this@FamilySettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(View(this@FamilySettingsActivity).apply {
                    background = Look.dot(Look.MINT)
                    layoutParams = LinearLayout.LayoutParams(dp(10), dp(10))  // 시안 1403:5
                        .apply { rightMargin = dp(7) }
                })
                addView(TextView(this@FamilySettingsActivity).apply {
                    text = if (Family.familyId != null) "연결됨" else "연결 전"
                    textSize = 13f
                    letterSpacing = -0.015f
                    includeFontPadding = false
                    setTextColor(Look.color(Look.INK_SOFT))
                })
            })
            addView(TextView(this@FamilySettingsActivity).apply {
                // 연결 시작 시각은 저장하지 않는다 — 없는 날짜를 지어내는 대신
                // 지금 상태만 적는다 (시안은 "2025년 8월부터 보호 중")
                text = if (Family.familyId != null) "보호 중" else "아직 연결되지 않았어요"
                textSize = 16.5f
                letterSpacing = -0.015f
                includeFontPadding = false
                typeface = Look.bold(this@FamilySettingsActivity)
                setTextColor(Look.color(Look.INK))
                setPadding(0, dp(6), 0, 0)
            })
        })
        addView(TextView(this@FamilySettingsActivity).apply {
            text = "상세"
            textSize = 12f
            gravity = Gravity.CENTER
            letterSpacing = -0.015f
            includeFontPadding = false
            typeface = Look.bold(this@FamilySettingsActivity)
            setTextColor(Look.color(Look.INK_SOFT))
            background = Look.pill(this@FamilySettingsActivity, Look.LINE_SOFT, 6)
            setPadding(dp(10), dp(4), dp(10), dp(4))   // 시안 1403:8 — 알약 43x26
            isClickable = true
            setOnClickListener {
                startActivity(Intent(this@FamilySettingsActivity, ReportListActivity::class.java))
            }
        })
    }

    /** 시안 — 아이콘 22 + 제목 16 Bold + 설명 13.5 + 오른쪽 꺾쇠 (줄 높이 72) */
    private fun menuRow(icon: Int, title: String, desc: String, onTap: () -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(72)
            )
            setOnClickListener { onTap() }
            addView(ImageView(this@FamilySettingsActivity).apply {
                setImageResource(icon)
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
                    .apply { rightMargin = dp(12) }
            })
            addView(LinearLayout(this@FamilySettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
                addView(TextView(this@FamilySettingsActivity).apply {
                    text = title
                    textSize = 16f
                    letterSpacing = -0.015f
                    includeFontPadding = false
                    typeface = Look.bold(this@FamilySettingsActivity)
                    setTextColor(Look.color(Look.INK))
                })
                addView(TextView(this@FamilySettingsActivity).apply {
                    text = desc
                    textSize = 13.5f
                    letterSpacing = -0.015f
                    includeFontPadding = false
                    setTextColor(Look.color(Look.INK_SOFT))
                    setPadding(0, dp(6), 0, 0)
                })
            })
            addView(ImageView(this@FamilySettingsActivity).apply {
                setImageResource(R.drawable.ic_h2_chev)
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
            })
        }

    /** 시안 — 흰 바탕 · 테두리 1.5 #F2C4C6 · 56 · r12 · 16 Bold 빨강 */
    private fun unlinkButton(name: String) = TextView(this).apply {
        text = "연결 해제"
        textSize = 16f
        gravity = Gravity.CENTER
        letterSpacing = -0.015f
        includeFontPadding = false
        typeface = Look.bold(this@FamilySettingsActivity)
        setTextColor(Look.color(Look.DANGER))
        background = Look.box(this@FamilySettingsActivity, Look.ON_MINT, "#F2C4C6", 12, 1.5f)
        isClickable = true
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
        ).apply { topMargin = dp(28) }
        setOnClickListener {
            AlertDialog.Builder(this@FamilySettingsActivity)
                .setTitle("${name}님과의 연결을 해제할까요?")
                .setMessage("이 휴대폰에서 가족 계정 연결이 끊어져요.")
                .setNegativeButton("취소", null)
                .setPositiveButton("연결 해제") { _, _ ->
                    Family.signOut(this@FamilySettingsActivity)
                    startActivity(
                        Intent(this@FamilySettingsActivity, IntroActivity::class.java)
                            .addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            )
                    )
                    finish()
                }
                .show()
        }
    }

    private fun dp(v: Int) = Look.dp(this, v)
}
