package com.flyai.adalert

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 어르신 홈 (피그마 v2 · H1-B 1381:60 · E01 1039:384) — 이 폰 주인이 매일 보는 화면.
 *
 * 화면 한가운데는 상태 카드 한 장. 어르신에게 필요한 것은 목록도 숫자도 아니라
 * "괜찮다"는 확인이고, 아닐 때만 무엇이 있었는지다.
 *
 *   조용한 날 (E01)  : 알약 「안심」 · 「오늘은 조용했어요」 · 흰 알약 「오늘 감지 0건」
 *   보호한 날 (H1-B) : 알약 「보호 완료」 · 「위험한 이동을 n번 막았어요」 · 「지금 확인할 일은 없어요」
 *   확인 필요 (H1-R) : 빨강 · 「위험한 곳으로 갈 뻔한 일이 n번 있었어요」 · 흰 줄 「오늘 있었던 일 보기」
 *
 * 두 카드의 모양은 같다 — 바탕도 #E8F0FF로 같고 왼쪽 4px 막대도 같다. 바뀌는 것은 글뿐이라
 * 카드를 두 벌 만들지 않는다. v1의 진한 파랑 카드와 큰 숫자 하나(44 Bold)는 v2에서 사라졌다.
 *
 * 그 아래 큰 버튼 둘(안심 요약 · 가족에게 연락)과 「현재 보호 상태」 카드.
 * 숫자는 오늘 기록에서 집계한다 — 지어낸 값이 아니다([SeniorStats]).
 */
class SeniorHomeActivity : Activity() {

    private companion object {
        /** 시안 H1 Btn/보조 — #EEF1F5 위의 글자색 */
        const val SECOND_INK = "#1C2431"

        /** 시안 H1-R Card/ActionNeeded — 연한 빨강 바탕과 그 위의 글자색 */
        const val ALERT_TINT = "#FFECE9"
        const val ALERT_INK = "#A11419"
    }

    private var guardianName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        Insets.apply(this)

        header()
        SeniorSummaryActivity.tabs(this, 0)
        askNotify()
    }

    /**
     * 알림 허용을 여기서 처음 묻는다.
     *
     * S03(필수 권한)은 접근성 하나만 다룬다 — 시안이 그렇고, 준비 단계에서 창을 여러 번
     * 띄우면 어르신이 무엇에 답하는지 잃는다. 보호자가 보낸 안내([PushService])가 닿는
     * 곳은 이 화면부터라, 필요해지는 자리에서 묻는 편이 앞뒤가 맞는다.
     * 한 번 답한 뒤에는 시스템이 다시 띄우지 않으므로 매번 불러도 성가시지 않다.
     */
    private fun askNotify() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onResume() {
        super.onResume()
        draw(SeniorStats.Tally(0, 0))
        Family.loadEvents(100) { all ->
            val today = SeniorStats.tally(all, SeniorStats.todayStart())
            runOnUiThread { draw(today) }
        }
    }

    /** 위쪽 — 워드마크 17(y58) · 「OO님과 연결됨」 13.5 Bold #8E97A6(y82) · 오른쪽 이니셜 40 */
    private fun header() {
        val header = findViewById<LinearLayout>(R.id.header)
        header.removeAllViews()

        val linked = TextView(this).apply {
            text = "가족과 연결됨"
            textSize = 13.5f
            letterSpacing = -0.015f
            includeFontPadding = false
            typeface = Look.bold(this@SeniorHomeActivity)
            setTextColor(Look.color(Look.INK_MUTED))
            setPadding(0, dp(4), 0, 0)
        }
        val avatar = Home.avatar(this, "")

        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(this@SeniorHomeActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
                addView(Home.wordmark(this@SeniorHomeActivity, 17f, Look.MINT))
                addView(linked)
            })
            addView(avatar)
        })

        Family.profiles { all ->
            val name = all.firstOrNull { !it.isSenior }?.name
            val me = Family.current(this)?.name ?: all.firstOrNull { it.isSenior }?.name
            runOnUiThread {
                guardianName = name
                linked.text = if (name != null) "${name}님과 연결됨" else "가족과 연결되지 않음"
                avatar.text = me?.take(1) ?: ""
            }
        }
    }

    private fun draw(today: SeniorStats.Tally) {
        val body = findViewById<LinearLayout>(R.id.body)
        body.removeAllViews()

        val guarding = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )?.contains(packageName) == true

        // 계정 없이 쓰는 폰 (시안 A00) — 오늘의 숫자를 말할 자리가 아니라
        // "지금도 지켜보고 있다"를 말할 자리다
        if (Family.familyId == null) { drawNoAccount(body, guarding); return }

        body.addView(stateCard(today, guarding))
        body.addView(bigButton("이번 주 안심 요약 보기", true, 24) {
            Home.open(this, SeniorSummaryActivity::class.java)
        })
        body.addView(bigButton(
            guardianName?.let { "${it}님께 연락하기" } ?: "가족에게 연락하기", false, 12
        ) { dial() })

        body.addView(Home.label(this, "현재 보호 상태").apply {
            setPadding(0, dp(24), 0, dp(10))
        })
        body.addView(protectionCard(guarding))
    }


    /**
     * 계정 없이 보호 (피그마 v2 · A00 · 1037:221).
     *
     * 로그인은 관문이 아니다 — 계정 없이도 광고 표시와 차단은 그대로 돈다. 이 화면이
     * 그 사실을 말하는 자리이고, 켜진 것 둘과 꺼진 것 하나를 나눠 적어
     * **무엇이 빠져 있는지**(가족 알림)까지 분명히 한다. 그래야 연결이 선택으로 읽힌다.
     */
    private fun drawNoAccount(body: LinearLayout, guarding: Boolean) {
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Look.box(this@SeniorHomeActivity, Look.CARD, null, 16)
            setPadding(dp(20), dp(20), dp(20), dp(20))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(24) }

            addView(LinearLayout(this@SeniorHomeActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(View(this@SeniorHomeActivity).apply {
                    background = Look.dot(Look.INK_MUTED)
                    layoutParams = LinearLayout.LayoutParams(dp(8), dp(8))
                        .apply { rightMargin = dp(7) }
                })
                addView(TextView(this@SeniorHomeActivity).apply {
                    text = "연결 안 됨"
                    textSize = 13f
                    letterSpacing = -0.015f
                    includeFontPadding = false
                    setTextColor(Look.color(Look.INK_MUTED))
                })
            })
            addView(TextView(this@SeniorHomeActivity).apply {
                text = "가족과 연결되지 않음"
                textSize = 20f
                letterSpacing = -0.015f
                includeFontPadding = false
                typeface = Look.bold(this@SeniorHomeActivity)
                setTextColor(Look.color(Look.INK))
                setPadding(0, dp(10), 0, 0)
            })
            addView(TextView(this@SeniorHomeActivity).apply {
                text = "아직 가족과의 계정이 등록되지 않았어요"
                textSize = 14f
                letterSpacing = -0.015f
                includeFontPadding = false
                setTextColor(Look.color(Look.INK_SOFT))
                setPadding(0, dp(8), 0, 0)
            })
        })

        body.addView(Home.label(this, "현재 보호 상태").apply {
            setPadding(0, dp(28), 0, dp(10))
        })
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Look.box(this@SeniorHomeActivity, Look.CARD, null, 16)
            setPadding(dp(20), dp(20), dp(20), dp(20))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            addView(TextView(this@SeniorHomeActivity).apply {
                text = "로그인하지 않아도 지켜보고 있어요"
                textSize = 16f
                letterSpacing = -0.015f
                includeFontPadding = false
                typeface = Look.bold(this@SeniorHomeActivity)
                setTextColor(Look.color(Look.INK))
            })
            addView(TextView(this@SeniorHomeActivity).apply {
                text = "계정을 만들지 않아도\n핵심 보호 기능은 계속 작동해요"
                textSize = 14f
                letterSpacing = -0.015f
                includeFontPadding = false
                setLineSpacing(dp(4).toFloat(), 1f)
                setTextColor(Look.color(Look.INK_SOFT))
                setPadding(0, dp(8), 0, dp(16))
            })
            addView(Step.divider(this@SeniorHomeActivity))
            addView(onOffRow(R.drawable.ic_h1_shieldcheck, "광고 표시", guarding))
            addView(onOffRow(R.drawable.ic_h1_shieldcheck, "위험 차단", guarding))
            addView(onOffRow(R.drawable.ic_a00_belloff, "가족 알림", false))
        })

        body.addView(bigButton("가족과 연결하기", true, 24) {
            Home.open(this, RoleActivity::class.java)
        })
        body.addView(bigButton("나중에 연결하기", false, 12) { finish() })
    }

    /** A00의 상태 줄 — 아이콘 22 + 이름 15 + 오른쪽 켜짐/꺼짐 14 Bold */
    private fun onOffRow(icon: Int, name: String, on: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(14), 0, 0)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        addView(ImageView(this@SeniorHomeActivity).apply {
            setImageResource(icon)
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
                .apply { rightMargin = dp(12) }
        })
        addView(TextView(this@SeniorHomeActivity).apply {
            text = name
            textSize = 15f
            letterSpacing = -0.015f
            includeFontPadding = false
            typeface = Look.bold(this@SeniorHomeActivity)
            setTextColor(Look.color(if (on) Look.INK else Look.INK_SOFT))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(TextView(this@SeniorHomeActivity).apply {
            text = if (on) "켜짐" else "꺼짐"
            textSize = 14f
            letterSpacing = -0.015f
            includeFontPadding = false
            typeface = Look.bold(this@SeniorHomeActivity)
            setTextColor(Look.color(if (on) Look.MINT else Look.INK_MUTED))
        })
    }

    /**
     * 상태 카드 (시안 Card/State 1381:116 — #E8F0FF r16 · 왼쪽 4px 막대 · 안쪽 24).
     * 알약 → 큰 글 24 Bold/32 → 한 문장 16/23 → 흰 알약 한 개.
     */
    private fun stateCard(today: SeniorStats.Tally, guarding: Boolean) = LinearLayout(this).apply {
        val total = today.total
        val safe = total == 0
        // 아직 확인 안 된 일이 남았으면 빨강 (시안 H1-R). 같은 하루라도 확인이 끝났으면
        // 파랑(H1-B)으로 안심시키고, 남았으면 보러 가게 한다
        val todo = guarding && today.unchecked > 0
        val tint = if (todo) ALERT_TINT else Look.MINT_TINT
        val edge = if (todo) Look.DANGER else Look.MINT
        val ink = if (todo) ALERT_INK else Look.INK

        orientation = LinearLayout.HORIZONTAL
        background = Look.box(this@SeniorHomeActivity, tint, null, 16)
        clipToOutline = true
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(24) }

        addView(View(this@SeniorHomeActivity).apply {
            setBackgroundColor(Look.color(edge))
            layoutParams = LinearLayout.LayoutParams(dp(4), LinearLayout.LayoutParams.MATCH_PARENT)
        })
        addView(LinearLayout(this@SeniorHomeActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(24), dp(24))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            // 시안 1381:118 — 파란 알약 13 Bold r6
            addView(TextView(this@SeniorHomeActivity).apply {
                text = when {
                    !guarding -> "보호 꺼짐"
                    todo -> "확인 필요"
                    safe -> "안심"
                    else -> "보호 완료"
                }
                textSize = 13f
                gravity = Gravity.CENTER
                letterSpacing = -0.015f
                includeFontPadding = false
                typeface = Look.bold(this@SeniorHomeActivity)
                setTextColor(Look.color(Look.ON_MINT))
                background = Look.pill(
                    this@SeniorHomeActivity,
                    when {
                        !guarding -> Look.WARN_INK
                        todo -> Look.DANGER
                        else -> Look.MINT
                    }, 6
                )
                // 위 [chip]과 같은 이유 — 26에 자연 행높이 17.4를 넣으려면 위아래는 4가 한계다
                setPadding(dp(10), dp(4), dp(10), dp(4))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(26)
                )
            })
            addView(TextView(this@SeniorHomeActivity).apply {
                text = when {
                    !guarding -> "보호가 꺼져 있어요"
                    todo -> "위험한 곳으로 갈 뻔한 일이\n${total}번 있었어요"
                    safe -> "오늘은 조용했어요"
                    else -> "위험한 이동을\n${total}번 막았어요"
                }
                textSize = 24f
                letterSpacing = -0.015f
                includeFontPadding = false
                setLineSpacing(dp(8).toFloat(), 1f)
                typeface = Look.bold(this@SeniorHomeActivity)
                setTextColor(Look.color(ink))
                setPadding(0, dp(10), 0, 0)
            })
            addView(TextView(this@SeniorHomeActivity).apply {
                text = when {
                    !guarding -> "아래 「보호 기능」을 눌러 다시 켜 주세요"
                    todo -> "무슨 일이었는지 확인해 주세요"
                    safe -> "위험한 광고나 잘못 누른 일이 없어요"
                    else -> "모두 안전하게 되돌렸어요"
                }
                textSize = 16f
                letterSpacing = -0.015f
                includeFontPadding = false
                setLineSpacing(dp(4).toFloat(), 1f)
                setTextColor(Look.color(if (todo) ALERT_INK else Look.INK_SOFT))
                setPadding(0, dp(10), 0, 0)
            })
            // 시안 H1-B 1381:122는 흰 알약 한 개, H1-R는 **누를 수 있는 흰 줄**이다 —
            // 확인할 일이 남았을 때만 보러 가는 길이 필요하다
            if (todo) addView(LinearLayout(this@SeniorHomeActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = Look.box(this@SeniorHomeActivity, Look.ON_MINT, null, 12)
                setPadding(dp(18), 0, dp(18), 0)
                isClickable = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(52)
                ).apply { topMargin = dp(16) }
                setOnClickListener {
                    Home.open(this@SeniorHomeActivity, SeniorSummaryActivity::class.java)
                }
                addView(TextView(this@SeniorHomeActivity).apply {
                    text = "오늘 있었던 일 보기"
                    textSize = 16f
                    letterSpacing = -0.015f
                    includeFontPadding = false
                    typeface = Look.bold(this@SeniorHomeActivity)
                    setTextColor(Look.color(ALERT_INK))
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    )
                })
                addView(TextView(this@SeniorHomeActivity).apply {
                    text = "›"
                    textSize = 20f
                    includeFontPadding = false
                    typeface = Look.bold(this@SeniorHomeActivity)
                    setTextColor(Look.color(ALERT_INK))
                })
            }) else addView(TextView(this@SeniorHomeActivity).apply {
                text = if (safe) "오늘 감지 0건" else "지금 확인할 일은 없어요"
                textSize = 14f
                gravity = Gravity.CENTER
                letterSpacing = -0.015f
                includeFontPadding = false
                typeface = Look.bold(this@SeniorHomeActivity)
                setTextColor(Look.color(Look.MINT))
                background = Look.pill(this@SeniorHomeActivity, Look.ON_MINT, 6)
                setPadding(dp(14), dp(7), dp(14), dp(7))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(34)
                ).apply { topMargin = dp(13) }
            })
        })
    }

    /** 시안 1381:100/102 — 66 · r12 · 19 Bold. 위는 파랑 채움, 아래는 #EEF1F5 */
    private fun bigButton(label: String, primary: Boolean, top: Int, onTap: () -> Unit) =
        TextView(this).apply {
            text = label
            textSize = 19f
            gravity = Gravity.CENTER
            letterSpacing = -0.015f
            includeFontPadding = false
            typeface = Look.bold(this@SeniorHomeActivity)
            setTextColor(Look.color(if (primary) Look.ON_MINT else SECOND_INK))
            background = Look.box(
                this@SeniorHomeActivity, if (primary) Look.MINT else Look.LINE_SOFT, null, 12
            )
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(66)
            ).apply { topMargin = dp(top) }
            setOnClickListener { onTap() }
        }

    /**
     * 현재 보호 상태 (시안 Card/Protection 1381:105 — #F5F7FA r16 · 두 줄 · 오른쪽 알약 r6).
     * 보호가 꺼져 있으면 첫 줄을 눌러 권한 화면으로 갈 수 있다 — 홈에서 다시 켜는 유일한 길이다.
     */
    private fun protectionCard(guarding: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Look.box(this@SeniorHomeActivity, Look.CARD, null, 16)
        setPadding(dp(20), dp(20), dp(20), dp(20))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        addView(stateRow(
            R.drawable.ic_h1_shieldcheck,
            if (guarding) "보호 기능 작동 중" else "보호 기능이 꺼졌어요", true,
            if (guarding) "켜짐" else "꺼짐",
            if (guarding) Look.MINT else Look.DANGER,
            if (guarding) Look.MINT_TINT else Look.DANGER_TINT
        ).apply {
            if (!guarding) {
                isClickable = true
                setOnClickListener {
                    Home.open(this@SeniorHomeActivity, PermissionActivity::class.java)
                }
            }
        })
        addView(stateRow(
            R.drawable.ic_h1_people,
            guardianName?.let { "${it}님과 연결됨" } ?: "가족과 연결되지 않음", false,
            if (Family.familyId != null) "연결됨" else "연결 전",
            Look.INK_SOFT, Look.LINE_SOFT
        ).apply { setPadding(0, dp(14), 0, 0) })
    }

    /** 아이콘 22 + 글 16 + 오른쪽 알약 24 r6 한 줄 */
    private fun stateRow(
        icon: Int, label: String, bold: Boolean, chip: String, ink: String, tint: String,
    ) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        addView(ImageView(this@SeniorHomeActivity).apply {
            setImageResource(icon)
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
                .apply { rightMargin = dp(10) }
        })
        addView(TextView(this@SeniorHomeActivity).apply {
            text = label
            textSize = 16f
            letterSpacing = -0.015f
            includeFontPadding = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            if (bold) typeface = Look.bold(this@SeniorHomeActivity)
            setTextColor(Look.color(if (bold) Look.INK else Look.INK_SOFT))
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        })
        addView(TextView(this@SeniorHomeActivity).apply {
            text = chip
            textSize = 12f
            gravity = Gravity.CENTER
            letterSpacing = -0.015f
            includeFontPadding = false
            typeface = Look.bold(this@SeniorHomeActivity)
            setTextColor(Look.color(ink))
            background = Look.pill(this@SeniorHomeActivity, tint, 6)
            // 시안 Chip/State는 「연결됨」이 54(글자 34 + 좌우 10)다. 우리 글자는 같은
            // 12 Bold라도 36으로 넓어 좌우 10을 주면 알약이 52.7에 머물러 여백이 8.3까지
            // 눌린다 — 글자가 가장자리에 닿아 잘려 보인다. 12를 주면 여백이 시안의 10이 된다
            // 알약을 24로 못 박아 두고 위아래 5를 주면 글자가 25.5를 요구해 **받침이 잘린다**.
            // 12 글자의 자연 행높이가 17.4라 3씩이면 23.5로 딱 들어간다
            setPadding(dp(12), dp(3), dp(12), dp(3))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(24)
            )
        })
    }

    /** 전화 직접 걸기 없음 — 전화 앱만 열기 (보호자 홈과 같은 경로) */
    private fun dial() {
        try {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:")))
        } catch (e: ActivityNotFoundException) {
            // 전화 앱 없는 기기(태블릿)는 아무 동작 없음
        }
    }

    private fun dp(v: Int) = Look.dp(this, v)
}
