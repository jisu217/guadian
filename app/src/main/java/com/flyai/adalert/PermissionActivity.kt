package com.flyai.adalert

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 필수 권한 설정 (피그마 v2 · S03 · 1037:75) — 어르신이 직접 눌러야 하는 유일한 곳.
 *
 * 앞의 화면들은 보호자가 대신 정할 수 있지만 권한만은 불가능하다. 안드로이드의 제한이고
 * 옳은 제한이다 — 남의 폰을 대신 열어 줄 수 있으면 그게 곧 악성 앱이 다니는 길이다.
 *
 * 다루는 권한은 **접근성 서비스 하나**다 (시안과 같다). 한때 알림·배터리도 함께 물었는데
 * 둘 다 여기서 물을 이유가 없었다 — 배터리 예외는 앱 어디에서도 그 결과에 기대지 않고
 * (접근성 서비스는 시스템이 붙들고 있어 Doze의 대상이 아니다), 알림은 정작 필요해지는
 * 자리가 여기가 아니라 어르신 홈이라 [SeniorHomeActivity]가 그때 묻는다.
 *
 * 줄 모양은 시안 그대로 — 제목 18 Bold · 오른쪽 상태 알약 · 설명 15/22 ·
 * 왼쪽 아래 「항상 필요」 알약.
 */
class PermissionActivity : Activity() {

    private companion object {
        /** 시안 1363:16 — 흰 알약 「항상 필요」 (11.5 Bold #525C6E) */
        const val ALWAYS = "항상 필요"
    }

    private class Perm(val no: String, val title: String, val desc: String, val open: () -> Unit)

    private lateinit var perms: List<Perm>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_step)
        Insets.apply(this)

        perms = listOf(
            Perm("1", "접근성 서비스", "위험 영역과 이전 화면을 확인해요") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
        )

        Step.bind(
            this,
            title = "필수 권한 설정",
            step = "어르신 4/4",
            heading = "마지막으로 휴대폰의\n보호 권한을 켜주세요",
            sub = "이 단계만은 휴대폰 주인이 직접 허용해야 해요",
            notice = "",
            button = "마지막 설정 열기",
            eyebrow = "마지막 한 단계만 남았어요",
        ) { next() }
        Step.senior(this)
        (findViewById<LinearLayout>(R.id.body).layoutParams as LinearLayout.LayoutParams)
            .topMargin = dp(25)      // 시안: 부제 끝 275 → 카드 299
    }

    override fun onResume() {
        super.onResume()
        draw()
    }

    /** 미완료 권한 중 첫 번째 열기 */
    private fun next() {
        val undone = perms.indexOfFirst { !granted(it) }
        if (undone < 0) startActivity(Intent(this, DoneActivity::class.java))
        else perms[undone].open()
    }

    private fun granted(p: Perm): Boolean = Settings.Secure.getString(
        contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    )?.contains(packageName) == true

    private fun draw() {
        val body = findViewById<LinearLayout>(R.id.body)
        body.removeAllViews()

        perms.forEachIndexed { i, p ->
            if (i > 0) body.addView(Step.gap(this, 12))
            body.addView(permCard(p))
        }

        val done = perms.count { granted(it) }
        findViewById<Button>(R.id.btn_next).text =
            if (done == perms.size) "보호 시작하기" else "마지막 설정 열기"
    }

    /**
     * 권한 한 장 (시안 List/Permission 1363:11 — #F5F7FA r16 · 안쪽 20 ·
     * 제목 18 Bold + 오른쪽 상태 알약 · 설명 15/22 · 아래 「항상 필요」 알약).
     */
    private fun permCard(p: Perm) = LinearLayout(this).apply {
        val ok = granted(p)
        orientation = LinearLayout.VERTICAL
        background = Look.box(this@PermissionActivity, Look.CARD, null, 16)
        setPadding(dp(20), dp(20), dp(20), dp(16))   // 시안: 위 20 · 아래 16
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        isClickable = true
        setOnClickListener { p.open() }

        addView(LinearLayout(this@PermissionActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@PermissionActivity).apply {
                text = p.title
                textSize = 18f
                letterSpacing = -0.015f
                includeFontPadding = false
                typeface = Look.bold(this@PermissionActivity)
                setTextColor(Look.color(Look.INK))
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            })
            addView(chip(
                if (ok) "완료" else "필수",
                if (ok) Look.MINT else Look.DANGER,
                if (ok) Look.MINT_TINT else Look.DANGER_TINT,
                12f, null
            ))
        })

        addView(TextView(this@PermissionActivity).apply {
            text = p.desc
            textSize = 15f
            letterSpacing = -0.015f
            includeFontPadding = false
            setLineSpacing(dp(3).toFloat(), 1f)
            setTextColor(Look.color(Look.INK_SOFT))
            setPadding(0, dp(4), 0, 0)      // 시안: 제목 끝 343 → 설명 347
        })

        addView(chip(ALWAYS, Look.INK_SOFT, Look.ON_MINT, 11.5f, Look.LINE, vPad = 4).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(6)   // 시안 369 → 375
        })
    }

    /**
     * 시안 Chip/State — 높이 **24** · r12 · 좌우 10.
     *
     * 위아래 여백은 글자 크기마다 다르다. 한글의 자연 행높이(12는 17.4 · 11.5는 16.7)가
     * 이미 자리를 차지하므로, 24에서 그것을 뺀 나머지를 반씩 나눠 [vPad]로 받는다.
     * 한 값으로 묶으면 작은 글자의 알약이 24보다 낮아진다.
     */
    private fun chip(
        text: String, ink: String, tint: String, size: Float, line: String?, vPad: Int = 3,
    ) =
        TextView(this).apply {
            this.text = text
            textSize = size
            letterSpacing = -0.015f
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = Look.bold(this@PermissionActivity)
            setTextColor(Look.color(ink))
            background = Look.box(this@PermissionActivity, tint, line, 12, 1f)
            setPadding(dp(10), dp(vPad), dp(10), dp(vPad))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

    private fun dp(v: Int) = Look.dp(this, v)
}
