package com.flyai.adalert

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 연결 보내기 (피그마 v2 · P05 · 1036:184) — 보호자 네 걸음을 마친 뒤의 전달 화면.
 *
 * **진짜 가족 계정 생성 지점**. 앞의 네 화면은 설명·설정, 이 화면 진입 순간 계정 생성 + 코드 발급.
 * 어르신 폰에서는 이 숫자만 입력. 시안에 진행 바가 없는 것도 그래서다 — 설정은 이미 끝났다.
 *
 * ## 시안과 다른 두 가지
 * 1. 코드가 시안은 「영문+숫자 6자리」, 우리는 **숫자 6자리**([Family.createFamily]).
 *    어르신 폰에서 손으로 넣는 값이라 영문을 섞지 않았다.
 * 2. 우리에게는 **네 자리 비밀번호**가 하나 더 있다. 코드만으로 남의 가족에 들어가는 것을
 *    막는 값이라 뺄 수 없어, 시안의 「10분 뒤 만료」 알약 자리에 그대로 넣었다.
 *    우리 코드는 만료되지 않는다 — 없는 만료를 적어 두면 그게 거짓말이 된다.
 */
class InviteActivity : Activity() {

    companion object {
        /**
         * 내 정보에서 열었을 때 true. 같은 화면이지만 시안이 둘로 나뉘어 있다 —
         * 보호자 흐름 중간이면 P05「연결 보내기」, 설정에서 들어오면
         * FC-G「가족 연결 관리」. 다른 것은 앱바 제목·머리글과 아래 버튼 글자뿐이다.
         */
        const val EXTRA_MANAGE = "manage"

        /** 시안 1361:23 — QR 보기 버튼의 글자색 */
        private const val SECOND_INK = "#1C2431"
    }

    private var code: String? = null
    private var pin: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_step)
        Insets.apply(this)

        val manage = intent.getBooleanExtra(EXTRA_MANAGE, false)
        Step.bind(
            this,
            title = if (manage) "가족 연결 관리" else "연결 보내기",
            step = "",
            heading = if (manage) "가족 연결 코드"
            else "이 코드를\n보호할 가족에게 전달해주세요",
            sub = "문자 링크나 QR로 간단하게 전달할 수 있어요",
            notice = "",
            button = if (manage) "새 가족 연결하기" else "연결 상태 확인",
            topGap = 49,          // 시안 1361:7 제목 y=146 (앱바 끝 97 · 진행 바 없음)
        ) { check() }

        (findViewById<TextView>(R.id.text_sub).layoutParams as LinearLayout.LayoutParams)
            .topMargin = dp(15)
        val body = findViewById<LinearLayout>(R.id.body)
        (body.layoutParams as LinearLayout.LayoutParams).topMargin = dp(16)

        body.addView(codeCard())
        body.addView(actions())
        body.addView(Step.gap(this, 25))
        body.addView(Step.label(this, "연결 상태"))
        body.addView(stateCard())

        // 가족 없음 → 지금 생성. 이미 있음 → 그 코드 표시.
        if (Family.familyId == null) {
            Family.createFamily(this) { c, p, error ->
                runOnUiThread {
                    if (error != null) {
                        stateHint.text = error
                    } else {
                        code = c; pin = p; showCode()
                        // 계정이 방금 생겼다 — 01-2의 이름을 이제야 프로필로 세울 수 있다
                        Family.claimProfile(this, Family.ROLE_GUARDIAN)
                    }
                }
            }
        } else {
            code = Family.code
            pin = Family.pin(this)
            showCode()
        }
    }

    private lateinit var codeText: TextView
    private lateinit var pinChip: TextView
    private lateinit var stateText: TextView
    private lateinit var stateHint: TextView

    /** 시안 Card/Code 1361:9 — #E8F0FF r16 · 안쪽 20 · 라벨 12.5 Bold · 코드 34 Bold/44 */
    private fun codeCard() = FrameLayout(this).apply {
        background = Look.box(this@InviteActivity, Look.MINT_TINT, null, 16)
        setPadding(dp(20), dp(20), dp(20), dp(20))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        addView(LinearLayout(this@InviteActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@InviteActivity).apply {
                text = "가족 연결 코드 · 숫자 6자리"
                textSize = 12.5f
                letterSpacing = -0.015f
                includeFontPadding = false
                typeface = Look.bold(this@InviteActivity)
                setTextColor(Look.color(Look.MINT))
            })
            codeText = TextView(this@InviteActivity).apply {
                text = "······"
                textSize = 34f
                // 시안의 「H4K 7QM」처럼 자리를 벌려 읽기 쉽게
                letterSpacing = 0.06f
                includeFontPadding = false
                typeface = Look.bold(this@InviteActivity)
                setTextColor(Look.color(Look.MINT))
                // 위 틈 없음 — 34 글자의 자연 행높이(49)가 이미 시안의 44보다 커서
                // 여백까지 더하면 카드가 시안(104)보다 8 높아진다(실측)
            }
            addView(codeText)
        })

        // 시안 Btn/Copy 1361:12 — 흰 알약 72×30 r15 · 아이콘 16 · 12.5 Bold
        addView(LinearLayout(this@InviteActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Look.pill(this@InviteActivity, Look.ON_MINT, 15)
            setPadding(dp(10), 0, dp(10), 0)
            isClickable = true
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, dp(30), Gravity.END or Gravity.TOP
            ).apply { topMargin = dp(-2) }
            addView(ImageView(this@InviteActivity).apply {
                setImageResource(R.drawable.ic_p05_copy)
                layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
                    .apply { rightMargin = dp(6) }
            })
            addView(TextView(this@InviteActivity).apply {
                text = "복사"
                textSize = 12.5f
                letterSpacing = -0.015f
                includeFontPadding = false
                typeface = Look.bold(this@InviteActivity)
                setTextColor(Look.color(Look.MINT))
            })
            setOnClickListener { copy() }
        })
    }

    /** 시안 1361:16/20 — 164×56 r12 둘, 사이 14. 왼쪽 파랑 · 오른쪽 #EEF1F5 */
    private fun actions() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(23) }

        addView(iconButton(R.drawable.ic_p05_message, "문자로 보내기", true) { sms() }.apply {
            (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(14)
        })
        addView(iconButton(R.drawable.ic_p05_qr, "QR 보기", false) { qr() })
    }

    /** 아이콘 20 + 글자 15 Bold가 가운데 나란히 놓이는 버튼 (56 · r12) */
    private fun iconButton(icon: Int, label: String, primary: Boolean, onTap: () -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background =
                if (primary) getDrawable(R.drawable.btn_mint)
                else Look.pill(this@InviteActivity, Look.LINE_SOFT, 12)
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(0, dp(56), 1f)
            addView(ImageView(this@InviteActivity).apply {
                setImageResource(icon)
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
                    .apply { rightMargin = dp(8) }
            })
            addView(TextView(this@InviteActivity).apply {
                text = label
                textSize = 15f
                letterSpacing = -0.015f
                includeFontPadding = false
                typeface = Look.bold(this@InviteActivity)
                setTextColor(Look.color(if (primary) Look.ON_MINT else SECOND_INK))
            })
            setOnClickListener { onTap() }
        }

    /** 시안 Card/ConnState 1361:25 — #F5F7FA r16 · 점 8 · 13 Bold 라벨 · 22 Bold 상태 · 선 · 13 안내 */
    private fun stateCard() = Step.card(this).apply {
        setPadding(dp(20), dp(15), dp(20), dp(20))

        addView(LinearLayout(this@InviteActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(View(this@InviteActivity).apply {
                background = Look.dot(Look.MINT)
                layoutParams = LinearLayout.LayoutParams(dp(8), dp(8))
                    .apply { rightMargin = dp(6) }
            })
            addView(TextView(this@InviteActivity).apply {
                text = "가족 연결 코드"
                textSize = 13f
                letterSpacing = -0.015f
                includeFontPadding = false
                typeface = Look.bold(this@InviteActivity)
                setTextColor(Look.color(Look.INK_SOFT))
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            })
            // 시안의 「10분 뒤 만료」 자리 — 우리 코드는 만료가 없어 비밀번호를 적는다
            pinChip = TextView(this@InviteActivity).apply {
                text = "비밀번호 ····"
                textSize = 12f
                letterSpacing = -0.015f
                gravity = Gravity.CENTER
                includeFontPadding = false
                typeface = Look.bold(this@InviteActivity)
                setTextColor(Look.color(Look.INK_SOFT))
                background = Look.box(this@InviteActivity, Look.ON_MINT, Look.LINE, 13, 1f)
                // 위아래 4 — 12 글자의 자연 행높이 17.4에 더해 시안의 26이 된다
                setPadding(dp(9), dp(4), dp(9), dp(4))
            }
            addView(pinChip)
        })

        stateText = TextView(this@InviteActivity).apply {
            text = "연결 대기 중"
            textSize = 22f
            letterSpacing = -0.015f
            includeFontPadding = false
            typeface = Look.bold(this@InviteActivity)
            setTextColor(Look.color(Look.INK))
            setPadding(0, dp(9), 0, dp(14))
        }
        addView(stateText)
        addView(Step.divider(this@InviteActivity))

        stateHint = TextView(this@InviteActivity).apply {
            text = "가족이 이 코드를 입력하면 연결이 시작돼요"
            textSize = 13f
            letterSpacing = -0.015f
            includeFontPadding = false
            setLineSpacing(dp(3).toFloat(), 1f)
            setTextColor(Look.color(Look.INK_SOFT))
            setPadding(0, dp(13), 0, 0)
        }
        addView(stateHint)
    }

    private fun showCode() {
        codeText.text = code ?: "······"
        pinChip.text = pin?.let { "비밀번호 $it" } ?: "비밀번호는 만든 폰에"
    }

    /** **문자 전용** 문구. 클립보드는 [copy]가 코드만 담는다 — 붙여넣을 곳이 코드 입력칸이라서 */
    private fun message() = buildString {
        append("안심폰 연결 코드입니다.\n")
        append("가족 아이디: ${code ?: "-"}")
        pin?.let { append("\n비밀번호: $it") }
    }

    /**
     * 코드 **여섯 자리만** 클립보드에 담는다.
     *
     * 문자 전용 문구인 [message]를 그대로 쓰면 안내 문장과 비밀번호까지 딸려 가, 받는 쪽이
     * 어르신 폰의 코드 입력칸에 그대로 붙여넣지 못하고 손으로 코드만 골라내야 했다.
     *
     * 비밀번호를 뺀 이유는 하나 더 있다 — 클립보드는 다른 앱도 읽는다. 비밀번호는 화면에
     * 떠 있고([showCode]) 문자로 보내면([sms]) 함께 나가므로 전달 경로는 그대로 남는다.
     */
    private fun copy() {
        val c = code
        if (c == null) {
            // 계정 생성 직후 코드 발급 전에 누른 경우. 빈 클립보드를 만들면 붙여넣기 때
            // 직전에 복사해 둔 남의 값이 들어가므로, 아무것도 담지 않고 다시 누르게 한다
            stateHint.text = "코드를 아직 받지 못했어요. 잠시 뒤 다시 눌러 주세요."
            return
        }
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("안심폰 연결 코드", c))
        stateHint.text = "코드를 복사했어요. 비밀번호까지 보내려면 문자로 보내기를 쓰세요."
    }

    private fun sms() {
        // 앱의 직접 문자 전송 없음 — 문자 앱 열기 + 내용 채우기만. 전송은 보호자 몫.
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("smsto:"))
                    .putExtra("sms_body", message())
            )
        } catch (e: ActivityNotFoundException) {
            stateHint.text = "문자 앱을 열 수 없어요. 코드를 복사해 주세요."
        }
    }

    /** QR 보기 — 코드와 비밀번호를 담은 사각 코드를 큰 화면으로 */
    private fun qr() {
        val c = code
        if (c == null) {
            stateHint.text = "코드를 아직 받지 못했어요. 잠시 뒤 다시 눌러 주세요."
            return
        }
        startActivity(
            Intent(this, QrActivity::class.java)
                .putExtra(QrActivity.EXTRA_CODE, c)
                .putExtra(QrActivity.EXTRA_PIN, pin)
        )
    }

    /** 어르신 폰의 이 가족 참여 여부 확인 */
    private fun check() {
        stateHint.text = "확인하는 중…"
        Family.profiles { all ->
            runOnUiThread {
                val senior = all.firstOrNull { it.isSenior }
                if (senior != null) {
                    stateText.text = "연결됨"
                    startActivity(
                        Intent(this, LinkedActivity::class.java)
                            .putExtra(LinkedActivity.EXTRA_NAME, senior.name)
                    )
                } else {
                    stateHint.text = "가족이 이 코드를 입력하면 연결이 시작돼요"
                    notLinkedDialog()
                }
            }
        }
    }


    /**
     * 아직 연결되지 않았을 때 (피그마 v2 · 1514:2).
     *
     * v1은 카드 안 회색 한 줄로만 알렸는데, 그 줄은 이미 다른 안내가 있던 자리라
     * 「연결 상태 확인」을 눌렀을 때 무엇이 달라졌는지 알기 어려웠다. 시안은 알림창이다.
     *
     * 「새 코드 받기」는 **가족을 새로 만든다.** 아직 아무도 들어오지 않은 코드라
     * 버려도 잃을 것이 없고, 코드가 잘못 전달됐을 때 되돌릴 유일한 길이다.
     * 이미 누군가 들어온 뒤에는 이 창 자체가 뜨지 않는다([check]가 먼저 걸러 낸다).
     */
    private fun notLinkedDialog() {
        val ctx = this
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(28), dp(24), dp(24))

            addView(TextView(ctx).apply {
                text = "i"
                textSize = 26f
                gravity = Gravity.CENTER
                includeFontPadding = false
                typeface = Look.bold(ctx)
                setTextColor(Look.color(Look.MINT))
                background = Look.dot(Look.MINT_TINT)
                layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
            })
            addView(TextView(ctx).apply {
                text = "아직 연결되지 않았어요"
                textSize = 18f
                gravity = Gravity.CENTER
                letterSpacing = -0.015f
                includeFontPadding = false
                typeface = Look.bold(ctx)
                setTextColor(Look.color(Look.INK))
                setPadding(0, dp(18), 0, 0)
            })
            addView(TextView(ctx).apply {
                text = "연결 코드를 다시 확인하거나\n새 코드를 받아보세요"
                textSize = 14f
                gravity = Gravity.CENTER
                letterSpacing = -0.015f
                includeFontPadding = false
                setLineSpacing(dp(4).toFloat(), 1f)
                setTextColor(Look.color(Look.INK_SOFT))
                setPadding(0, dp(10), 0, 0)
            })
        }

        val dialog = AlertDialog.Builder(ctx).setView(box).create()
        dialog.window?.setBackgroundDrawable(Look.box(ctx, Look.BG, null, 20))

        box.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)
            ).apply { topMargin = dp(22) }
            addView(dialogButton("닫기", false) { dialog.dismiss() }.apply {
                (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(10)
            })
            addView(dialogButton("새 코드 받기", true) {
                dialog.dismiss()
                newCode()
            })
        })
        dialog.show()
    }

    /** 알림창의 버튼 한 개 (시안 1514:2 — 52 · r12 · 15.5 Bold) */
    private fun dialogButton(label: String, primary: Boolean, onTap: () -> Unit) =
        TextView(this).apply {
            text = label
            textSize = 15.5f
            gravity = Gravity.CENTER
            letterSpacing = -0.015f
            includeFontPadding = false
            typeface = Look.bold(this@InviteActivity)
            setTextColor(Look.color(if (primary) Look.ON_MINT else SECOND_INK))
            background = Look.box(
                this@InviteActivity, if (primary) Look.MINT else Look.LINE_SOFT, null, 12
            )
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f)
            setOnClickListener { onTap() }
        }

    /**
     * 코드 새로 발급 — 지금 계정을 버리고 가족을 다시 만든다.
     * 아무도 안 들어온 코드라 버리는 것이 곧 잃는 것은 아니다.
     */
    private fun newCode() {
        stateHint.text = "새 코드를 만드는 중…"
        Family.signOut(this)
        code = null
        pin = null
        showCode()
        Family.createFamily(this) { c, p, error ->
            runOnUiThread {
                if (error != null) {
                    stateHint.text = error
                } else {
                    code = c; pin = p; showCode()
                    stateHint.text = "새 코드를 만들었어요. 가족에게 다시 보내 주세요"
                }
            }
        }
    }

    private fun dp(v: Int) = Look.dp(this, v)
}
