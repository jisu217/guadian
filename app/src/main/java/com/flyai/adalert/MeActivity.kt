package com.flyai.adalert

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * 내 정보 (탭바 마지막 칸). 피그마 A01G(보호자) · A01U(어르신).
 *
 * 보호자(A01G): 프로필 → 연결된 가족 → 가족 연결 코드 관리 → 계정 → 로그아웃.
 * 어르신(A01U): 프로필 → 보호자 카드 → 보호 카드 → 적용된 보호 설정 → 연결 해제.
 * v2에서 맨 위 프로필은 카드가 아니다 — 바탕 없이 원·이름·한 줄뿐이다.
 *
 * 보는 사람에 따라 내용 분기. 어르신 폰: **이 폰이 지금 지켜지고 있는지** + 알림 방식 필요.
 * 보호자 폰: 감지 설정 불필요 — 광고를 보는 사람이 자기가 아님.
 *
 * **로그인 강제 없음.** 계정 없이도 광고 표시·쉴드는 그대로 동작,
 * 가족 계정은 "보호자에게 알리기"를 원할 때만 필요. 어르신 단독 사용 사례 존재,
 * 로그인을 관문으로 두면 그 사람은 앱 사용 자체 불가.
 */
class MeActivity : Activity() {

    private companion object {
        /** 「위험한 설치 차단」 — [Shield.showApkRisk]가 같은 이름을 본다 */
        const val KEY_APK_BLOCK = "apkblock"

        /** 「큰 글씨와 쉬운 안내」 — P03([NotifyActivity])이 정해 둔 그 값 */
        const val KEY_BIG_TEXT = "bigtext"
    }

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    /** 어르신 화면이면 true — 카드·글자 한 치수 큼 (A01U) */
    private var big = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        Insets.apply(this)
        header()
    }

    override fun onResume() {
        super.onResume()
        draw()
        askNotificationPermission()
    }

    /** 앱바 제목 — 피그마 A01: 20 Bold #0D1220 (y62) */
    private fun header() {
        val header = findViewById<LinearLayout>(R.id.header)
        header.removeAllViews()
        header.addView(TextView(this).apply {
            text = "내 정보"
            textSize = 20f
            letterSpacing = -0.015f
            includeFontPadding = false
            typeface = Look.bold(this@MeActivity)
            setTextColor(Look.color(Look.INK))
            setPadding(0, dp(12), 0, dp(18))
        })
    }

    private fun draw() {
        val me = Family.current(this)
        val guardian = me != null && !me.isSenior
        big = me?.isSenior == true
        val body = findViewById<LinearLayout>(R.id.body)
        body.removeAllViews()

        body.addView(profileHead(me, guardian))
        if (guardian) guardianBody(body) else seniorBody(body)

        if (guardian) Home.tabs(this, Home.TABS_GUARDIAN.size - 1, Home.TABS_GUARDIAN) { i ->
            open(
                when (i) {
                    0 -> GuardianHomeActivity::class.java
                    1 -> EventListActivity::class.java
                    else -> ReportListActivity::class.java
                }
            )
        } else SeniorSummaryActivity.tabs(this, SeniorSummaryActivity.TABS.size - 1)
    }

    /** 시안 A01G 1404:8~37 — 연결된 가족 · 가족 연결 코드 관리 · 계정 */
    private fun guardianBody(body: LinearLayout) {
        body.addView(Home.label(this, "연결된 가족").apply { setPadding(0, dp(24), 0, dp(10)) })
        val family = card()
        body.addView(family)
        Family.profiles { all ->
            val seniors = all.filter { it.isSenior }
            runOnUiThread {
                family.removeAllViews()
                if (seniors.isEmpty()) {
                    family.addView(row("연결된 가족이 없어요", "아래에서 연결 코드를 만들어 보내세요"))
                    return@runOnUiThread
                }
                seniors.forEachIndexed { i, p ->
                    if (i > 0) family.addView(divider())
                    family.addView(familyRow(p.name))
                }
            }
        }

        body.addView(dashedCard("가족 연결 코드 관리", "새 가족 연결하기") {
            // 시안 FC-G — P05와 같은 화면을 설정 쪽 제목·버튼으로 여는 길
            startActivity(
                Intent(this@MeActivity, InviteActivity::class.java)
                    .putExtra(InviteActivity.EXTRA_MANAGE, true)
            )
        })

        body.addView(Home.label(this, "계정").apply { setPadding(0, dp(24), 0, dp(10)) })
        body.addView(loginCard())
        if (Family.familyId != null) body.addView(logoutButton())
    }

    /** 시안 A01U 1405:7~34 — 보호자 카드 · 보호 카드 · 적용된 보호 설정 · 연결 해제 */
    private fun seniorBody(body: LinearLayout) {
        val guarding = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )?.contains(packageName) == true

        val guard = infoCard(
            R.drawable.ic_a01_people, Look.CARD,
            "가족과 연결되지 않음", "연결하면 위험 등급과 조치 결과만 함께 봐요"
        ) { if (Family.familyId == null) open(RoleActivity::class.java) }
        body.addView(guard.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(28)
        })
        Family.profiles { all ->
            val name = all.firstOrNull { !it.isSenior }?.name
            if (name != null) runOnUiThread {
                title(guard, "${name}님과 연결됨")
                desc(guard, "위험 등급과 조치 결과만 함께 봐요")
            }
        }

        body.addView(infoCard(
            R.drawable.ic_a01_shieldcheck, Look.MINT_TINT,
            if (guarding) "보호 기능 작동 중" else "보호가 꺼져 있어요",
            if (guarding) "위험한 광고와 이동을 실시간으로 살펴요"
            else "눌러서 접근성 권한을 켜 주세요"
        ) { if (!guarding) startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(12)
        })

        // 시안 1405:18 — 줄 둘, 설명 없이 제목과 스위치뿐. 무엇을 막을지에 대한 답이다
        body.addView(Home.label(this, "적용된 보호 설정", 14f)
            .apply { setPadding(0, dp(24), 0, dp(10)) })
        body.addView(card(
            toggleRow("위험한 설치 차단", null, KEY_APK_BLOCK),
            divider(),
            toggleRow("큰 글씨와 쉬운 안내", null, KEY_BIG_TEXT),
        ))

        // 시안에 없는 카드. 위 둘과 성격이 다르다 — 저쪽이 「무엇을 막을지」라면 이쪽은
        // **이 폰에서 어떻게 보이고 들릴지**다. 글씨가 잘 보이는지 소리가 들리는지는
        // 어르신 본인만 아는 것이고, 보호자 화면(13G)은 11F를 거쳐야 해서 닿지 못한다.
        // 「투터치」는 13G에 같은 스위치가 있어 뺐다 — 한 값을 두 곳에서 켜고 끄면
        // 어느 쪽이 진짜인지 알 수 없다
        body.addView(Home.label(this, "화면과 알림 방식", 14f)
            .apply { setPadding(0, dp(24), 0, dp(10)) })
        body.addView(card(
            toggleRow("화면에 표시", "광고에 테두리와 배지를 그려요", "visual"),
            divider(),
            borderWidthRow(),
            divider(),
            toggleRow("알림음", "위험한 광고를 만나면 소리로 알려요", "sound"),
            divider(),
            toggleRow("진동", "소리 대신 떨림으로 알려요", "vibe"),
        ))

        if (Family.familyId != null) {
            body.addView(unlinkButton())
            body.addView(TextView(this).apply {
                text = "해제하면 이 휴대폰의 보호가 잠시 멈춰요."
                textSize = 12.5f
                letterSpacing = -0.015f
                includeFontPadding = false
                setTextColor(Look.color(Look.INK_MUTED))
                setPadding(0, dp(14), 0, 0)
            })
        }
    }

    private fun title(card: View, t: String) {
        ((card as LinearLayout).getChildAt(1) as LinearLayout).let {
            (it.getChildAt(0) as TextView).text = t
        }
    }

    private fun desc(card: View, t: String) {
        ((card as LinearLayout).getChildAt(1) as LinearLayout).let {
            (it.getChildAt(1) as TextView).text = t
        }
    }

    /**
     * 맨 위 프로필 (시안 A01G 1404:3 · A01U 1405:3).
     * v1은 카드 한 장이었지만 v2에는 바탕이 없다 — 원 + 이름 + 한 줄뿐이고,
     * 보호자 화면에는 오른쪽에 「이름 수정 ›」이 붙는다.
     */
    private fun profileHead(me: Family.Profile?, guardian: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        addView(Home.avatar(
            this@MeActivity, me?.name?.take(1) ?: "?",
            if (big) 52 else 48, if (big) 22f else 20f
        ).apply { (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(16) })

        addView(LinearLayout(this@MeActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@MeActivity).apply {
                text = me?.let { "${it.name}님" } ?: "프로필을 골라 주세요"
                textSize = if (big) 22f else 20f
                letterSpacing = -0.015f
                includeFontPadding = false
                typeface = Look.bold(this@MeActivity)
                setTextColor(Look.color(Look.INK))
            })
            addView(TextView(this@MeActivity).apply {
                text = if (big) "내 계정" else buildString {
                    append(if (me == null) "역할 미선택" else "보호자")
                    Family.code?.let { append(" · 가족 아이디 "); append(it) }
                }
                textSize = if (big) 15f else 13.5f
                letterSpacing = -0.015f
                includeFontPadding = false
                setTextColor(Look.color(Look.INK_SOFT))
                setPadding(0, dp(6), 0, 0)
            })
        })

        // 시안 1503:2 — 보호자 화면에만 있는 오른쪽 링크
        if (!guardian) return@apply
        addView(TextView(this@MeActivity).apply {
            text = "이름 수정 ›"
            textSize = 14f
            letterSpacing = -0.015f
            includeFontPadding = false
            typeface = Look.medium(this@MeActivity)
            setTextColor(Look.color(Look.MINT))
            isClickable = true
            setOnClickListener { open(ProfileActivity::class.java) }
        })
    }

    /** 시안 A01G List/Family 1404:8 — 원 40 + 이름 16 Bold + 꺾쇠 20 (줄 높이 58) */
    private fun familyRow(name: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(58)
        )
        setOnClickListener {
            startActivity(
                Intent(this@MeActivity, FamilySettingsActivity::class.java)
                    .putExtra(FamilySettingsActivity.EXTRA_NAME, name)
            )
        }
        addView(Home.avatar(this@MeActivity, name.take(1), 40, 15f)
            .apply { (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(12) })
        addView(TextView(this@MeActivity).apply {
            text = "${name}님"
            textSize = 16f
            letterSpacing = -0.015f
            includeFontPadding = false
            typeface = Look.bold(this@MeActivity)
            setTextColor(Look.color(Look.INK))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(ImageView(this@MeActivity).apply {
            setImageResource(R.drawable.ic_h2_chev)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
        })
    }

    /**
     * 점선 테두리 카드 (시안 1404:26 — #F5F7FA · 점선 1.5 #B7C6E4 · r16 · 64).
     * 점선은 "아직 비어 있고 여기서 채운다"는 뜻이라 실선 카드와 구분해 쓴다.
     */
    private fun dashedCard(title: String, desc: String, onTap: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply {
            setColor(Look.color(Look.CARD))
            cornerRadius = dp(16).toFloat()
            setStroke(
                Look.dp(this@MeActivity, 1.5f), Look.color("#B7C6E4"),
                dp(5).toFloat(), dp(4).toFloat()
            )
        }
        setPadding(dp(18), 0, dp(20), 0)
        isClickable = true
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(64)
        ).apply { topMargin = dp(12) }
        setOnClickListener { onTap() }

        addView(ImageView(this@MeActivity).apply {
            setImageResource(R.drawable.ic_a01_plus)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
                .apply { rightMargin = dp(12) }
        })
        addView(LinearLayout(this@MeActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@MeActivity).apply {
                text = title
                textSize = 15.5f
                letterSpacing = -0.015f
                includeFontPadding = false
                typeface = Look.medium(this@MeActivity)
                setTextColor(Look.color(Look.INK))
            })
            addView(TextView(this@MeActivity).apply {
                text = desc
                textSize = 13f
                letterSpacing = -0.015f
                includeFontPadding = false
                setTextColor(Look.color(Look.INK_SOFT))
                setPadding(0, dp(4), 0, 0)
            })
        })
        addView(ImageView(this@MeActivity).apply {
            setImageResource(R.drawable.ic_h2_chev)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
        })
    }

    /** 아이콘 24 + 두 줄 카드 (시안 A01U Card/Guardian 1405:7 · Card/Protection 1405:12) */
    private fun infoCard(icon: Int, tint: String, title: String, desc: String, onTap: () -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Look.box(this@MeActivity, tint, null, 16)
            setPadding(dp(20), dp(18), dp(20), dp(18))
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { onTap() }
            addView(ImageView(this@MeActivity).apply {
                setImageResource(icon)
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
                    .apply { rightMargin = dp(12) }
            })
            addView(LinearLayout(this@MeActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
                addView(TextView(this@MeActivity).apply {
                    text = title
                    textSize = 17f
                    letterSpacing = -0.015f
                    includeFontPadding = false
                    typeface = Look.bold(this@MeActivity)
                    setTextColor(Look.color(Look.INK))
                })
                addView(TextView(this@MeActivity).apply {
                    text = desc
                    textSize = 14f
                    letterSpacing = -0.015f
                    includeFontPadding = false
                    setLineSpacing(dp(3).toFloat(), 1f)
                    setTextColor(Look.color(Look.INK_SOFT))
                    setPadding(0, dp(5), 0, 0)
                })
            })
        }

    /** 시안 A01U Btn/연결 해제 1405:32 — 흰 바탕 · 테두리 1.5 #F2C4C6 · 52 · r12 · 16 Bold 빨강 */
    private fun unlinkButton() = TextView(this).apply {
        text = "연결 해제"
        textSize = 16f
        gravity = Gravity.CENTER
        letterSpacing = -0.015f
        includeFontPadding = false
        typeface = Look.bold(this@MeActivity)
        setTextColor(Look.color(Look.DANGER))
        background = Look.box(this@MeActivity, Look.ON_MINT, "#F2C4C6", 12, 1.5f)
        isClickable = true
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(52)
        ).apply { topMargin = dp(32) }
        setOnClickListener { confirmSignOut("연결을 해제할까요?") }
    }

    /**
     * 로그인 상태 (시안 A01G Card/Login 1404:31 — 15.5 Bold + 오른쪽 알약 · 아래 13).
     * 연결 전에는 눌러서 역할 선택으로 간다 — 보호자는 가족을 만들고 어르신은 코드를 넣는다.
     */
    private fun loginCard() = LinearLayout(this).apply {
        val linked = Family.familyId != null
        orientation = LinearLayout.VERTICAL
        background = Look.box(this@MeActivity, Look.CARD, null, 16)
        setPadding(dp(20), dp(18), dp(20), dp(18))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        if (!linked) {
            isClickable = true
            setOnClickListener { open(RoleActivity::class.java) }
        }
        addView(LinearLayout(this@MeActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MeActivity).apply {
                text = "로그인 상태"
                textSize = 15.5f
                letterSpacing = -0.015f
                includeFontPadding = false
                typeface = Look.bold(this@MeActivity)
                setTextColor(Look.color(Look.INK))
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            })
            addView(TextView(this@MeActivity).apply {
                text = if (linked) "유지 중" else "로그인 전"
                textSize = 11.5f
                gravity = Gravity.CENTER
                letterSpacing = -0.015f
                includeFontPadding = false
                typeface = Look.bold(this@MeActivity)
                setTextColor(Look.color(if (linked) Look.MINT else Look.INK_SOFT))
                background = Look.pill(
                    this@MeActivity, if (linked) Look.MINT_TINT else Look.LINE_SOFT, 6
                )
                setPadding(dp(10), 0, dp(10), 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(24)
                )
            })
        })
        addView(TextView(this@MeActivity).apply {
            text = if (linked) "이 휴대폰에서 로그인 유지 중" else "눌러서 가족과 연결하기"
            textSize = 13f
            letterSpacing = -0.015f
            includeFontPadding = false
            setTextColor(Look.color(Look.INK_SOFT))
            setPadding(0, dp(8), 0, 0)
        })
    }

    /**
     * 켜고 끄는 줄. 값은 이 폰에만 저장 — 알림 방식은 폰마다 다를 수 있음.
     *
     * **투터치만 예외.** 보호자 화면([AlertSettingsActivity])에도 같은 줄이 있어서,
     * 각자 자기 폰에만 두면 한쪽에서 끈 것이 다른 쪽 화면에는 켜진 채로 남는다.
     * 그래서 이 값은 가족 문서에도 올린다 — 두 화면이 같은 값 하나를 만진다.
     */
    private fun toggleRow(title: String, desc: String?, key: String) = row(
        title, desc,
        right = Step.toggle(this, prefs.getBoolean(key, true)) { on ->
            prefs.edit().putBoolean(key, on).apply()
            if (key == "twotouch") Family.saveTwoTouch(this, on)
        }
    )

    /**
     * 테두리 두께 슬라이더 (이슈 #14).
     *
     * [row]를 쓰지 않는 이유 — 슬라이더는 폭을 다 써야 해서 제목 오른쪽이 아니라 아래로 내려간다.
     *
     * **숫자 대신 미리보기.** "8dp"가 얼마나 굵은지는 아무도 모른다. 오른쪽에 실제 테두리를
     * 그린 상자를 두어 고르는 즉시 보이게 한다 — 광고 화면으로 나가 확인할 필요 없음.
     *
     * "화면에 표시"가 꺼져 있어도 **그대로 둔다.** 껐다 켤 때를 대비해 미리 정해 둘 수 있어야 하고,
     * 흐리게 처리하면 왜 못 만지는지 어르신이 알기 어렵다.
     */
    private fun borderWidthRow(): View {
        val min = Look.AD_BORDER_W
        val max = Look.AD_BORDER_MAX
        val now = prefs.getInt(Look.AD_BORDER_KEY, Look.AD_BORDER_W)
            .coerceIn(min, max)

        // 미리보기 — 광고 테두리와 같은 색·같은 모서리 규칙
        val preview = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(if (big) 68 else 60), dp(if (big) 44 else 38))
        }
        fun paint(w: Int) {
            preview.background = GradientDrawable().apply {
                setStroke(dp(w), Look.color(Look.UNKNOWN))
                setColor(Color.TRANSPARENT)
                cornerRadius = dp(maxOf(Look.AD_BORDER_RADIUS, w)).toFloat()
            }
        }
        paint(now)

        val bar = SeekBar(this).apply {
            this.max = max - min
            progress = now - min
            // 어르신 손가락에 맞춰 손잡이를 기본(실측 약 14dp)의 두 배로 키운다.
            // 트랙 채운 색과 같은 파랑이라 오른쪽으로 갈수록 묻히므로 흰 테를 둘러 구분한다
            val knob = dp(if (big) 32 else 28)
            thumb = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Look.color(Look.MINT))
                setStroke(dp(3), Color.WHITE)
                setSize(knob, knob)
            }
            // setThumb()은 손잡이가 트랙 양 끝에 반씩 걸치도록 offset을 폭의 절반으로 잡는다.
            // 좌우 여백이 0이라 걸친 만큼 뷰 밖으로 나가 반원으로 잘렸다 — 0으로 되돌려
            // 트랙 안쪽에 세운다 (이슈 #14 후속). 여백을 주는 대신 이 방법을 쓴 이유는
            // 트랙 양 끝이 위 제목·설명 글과 같은 세로선에 맞아야 하기 때문
            thumbOffset = 0
            setPadding(0, dp(8), 0, dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                    val w = value + min
                    paint(w)
                    // 끄는 도중에도 저장 — 서비스는 매 스캔 다시 읽으므로 광고 화면과 나란히
                    // 두고 조절하면 곧바로 반영된다
                    prefs.edit().putInt(Look.AD_BORDER_KEY, w).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar) = Unit
                override fun onStopTrackingTouch(sb: SeekBar) = Unit
            })
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(if (big) 12 else 10), 0, dp(if (big) 12 else 10))
            addView(row("테두리 두께", "광고 테두리를 더 굵게 볼 수 있어요", right = preview))
            addView(bar)
        }
    }

    /**
     * 카드 안의 한 줄 (피그마 A01 카드 줄: 제목 15 Bold #0D1220 · 오른쪽 값/꺾쇠).
     * 설명 있으면 제목 아래 13.5 #525C6E. 탭 가능한 줄은 오른쪽에 꺾쇠.
     */
    private fun row(
        title: String,
        desc: String? = null,
        right: View? = null,
        onTap: (() -> Unit)? = null,
    ) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(if (big) 12 else 10), 0, dp(if (big) 12 else 10))
        if (onTap != null) {
            isClickable = true
            setOnClickListener { onTap() }
        }
        addView(LinearLayout(this@MeActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@MeActivity).apply {
                text = title
                textSize = if (big) 17f else 15f
                letterSpacing = -0.015f
                includeFontPadding = false
                typeface = Look.bold(this@MeActivity)
                setTextColor(Look.color(Look.INK))
            })
            if (desc != null) addView(TextView(this@MeActivity).apply {
                text = desc
                textSize = if (big) 14.5f else 13.5f
                letterSpacing = -0.015f
                includeFontPadding = false
                setTextColor(Look.color(Look.INK_SOFT))
                setPadding(0, dp(4), 0, 0)
            })
        })
        if (right != null) addView(right.apply {
            (layoutParams as? LinearLayout.LayoutParams)?.leftMargin = dp(12)
        })
        if (onTap != null) addView(ImageView(this@MeActivity).apply {
            setImageResource(R.drawable.ic_home_chevron)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
                .apply { leftMargin = dp(8) }
        })
    }

    /**
     * 로그아웃 (시안 A01G Btn/로그아웃 1404:36 — #EEF1F5 · 52 · r12 · 16 Bold #1C2431).
     * 탭 시 재확인 — 실수로 눌러 연결이 끊기면 보호자가 기록을 받지 못한다.
     * 접근성 서비스는 그대로 켜져 있어 광고 표시·차단은 계속된다.
     */
    private fun logoutButton() = TextView(this).apply {
        text = "로그아웃"
        textSize = 16f
        gravity = Gravity.CENTER
        letterSpacing = -0.015f
        includeFontPadding = false
        typeface = Look.bold(this@MeActivity)
        setTextColor(Look.color("#1C2431"))
        background = Look.box(this@MeActivity, Look.LINE_SOFT, null, 12)
        isClickable = true
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(52)
        ).apply { topMargin = dp(16) }
        setOnClickListener { confirmSignOut("로그아웃할까요?") }
    }

    /** 연결을 끊기 전 한 번 더 묻기. 확인 시 [Family.signOut] → 처음 화면 */
    private fun confirmSignOut(title: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(
                if (big) "가족과의 연결이 끊어져요. 광고 표시와 차단은 계속돼요."
                else "이 휴대폰에서 가족 계정 연결이 끊어져요."
            )
            .setNegativeButton("취소", null)
            .setPositiveButton("확인") { _, _ ->
                Family.signOut(this)
                startActivity(
                    Intent(this, IntroActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                finish()
            }
            .show()
    }

    /** DS 카드 — #F5F7FA · r16(어르신 r18) · 좌우 20 · 위아래 10 */
    private fun card(vararg rows: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Look.pill(this@MeActivity, Look.CARD, if (big) 18 else Look.RADIUS_CARD)
        setPadding(dp(20), dp(10), dp(20), dp(10))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        rows.forEach { addView(it) }
    }

    /** 줄 사이 선 — 1px #E6E9EE, 카드 안쪽 여백에서 여백까지 */
    private fun divider() = View(this).apply {
        setBackgroundColor(Look.color(Look.LINE))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1
        )
    }

    private fun open(target: Class<*>) = startActivity(Intent(this, target))

    /**
     * 프로필을 고른 폰에서만 알림 권한 요청. 보호자는 위험 알림, 어르신은 보호자가
     * 보낸 한마디 수신 — 양쪽 다 받을 것이 있으므로 역할 무관.
     * 단, 계정 미연결 폰에는 요청 없음.
     */
    private fun askNotificationPermission() {
        if (Family.current(this) == null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    private fun dp(v: Int) = Look.dp(this, v)
}
