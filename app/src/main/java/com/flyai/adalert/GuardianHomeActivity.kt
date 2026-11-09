package com.flyai.adalert

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 보호자 홈 (피그마 v2 · H2-B 1506:8 · H2-R 1435:177 · E02 1039:333 · 조용한 날 1498:2).
 *
 * 어르신 홈이 "괜찮다"라면 여기는 **"오늘 무슨 일이 있었나"**다. 같은 기록이라도
 * 보는 사람이 달라 첫 줄이 다르다.
 *
 * ## 화면 하나, 상태 카드 넷
 * 시안은 홈을 네 장으로 그려 두었지만 아래 세 카드(오늘 처리한 일 · 안심 요약 · 가족)는
 * 모두 같다. 다른 것은 맨 위 **상태 카드** 하나뿐이라 여기서도 카드 하나만 갈아 끼운다.
 *
 *   연결 전   (E02)  : 파랑 · 「연결 전」 · 흰 버튼 「가족 연결코드 받기」 + 3단계 안내 카드
 *   확인 필요 (H2-R) : 빨강 · 「확인 필요 n건」 · 전화하기 / 기록 보기
 *   보호한 날 (H2-B) : 파랑 · 「보호 완료」 · 「오늘 위험한 이동 n번을 모두 막았어요」
 *   조용한 날 (1498) : 파랑 · 「보호 완료」 · 「오늘은 조용해요」
 *
 * v1의 진한 파랑 히어로 카드와 오늘의 기록 목록·유형별 막대는 v2에서 사라졌다.
 * 기록은 아래 탭의 「알림」이, 통계는 「안심요약」이 맡는다.
 */
class GuardianHomeActivity : Activity() {

    private companion object {
        /** 시안 H2-R Card/ActionNeeded — 연한 빨강 바탕과 그 위의 글자색 */
        const val ALERT_TINT = "#FFECE9"
        const val ALERT_INK = "#A11419"
        /** 상태 카드 왼쪽 세로 막대 (시안: 4px, 카드 높이 전체) */
        const val EDGE = 4
    }

    private val hhmm = SimpleDateFormat("a h:mm", Locale.KOREA)

    /** 어르신 이름 + 마지막으로 그린 기록 — 이름이 늦게 도착하면 다시 그린다 */
    private var seniorName: String? = null

    /**
     * 프로필을 한 번이라도 읽었는가.
     *
     * 기록([Family.loadEvents])과 프로필([Family.profiles])이 각자 돌아와서, 프로필이
     * 오기 전에 그리면 연결된 가족도 잠깐 「연결 전」으로 보인다. 누가 들어와 있는지
     * 알기 전에는 어느 홈인지 정하지 않는다.
     */
    private var profilesKnown = false
    private var lastEvents: List<Family.Event> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        Insets.apply(this)

        header()
        Home.tabs(this, 0, Home.TABS_GUARDIAN) { i ->
            when (i) {
                1 -> Home.open(this, EventListActivity::class.java)
                2 -> Home.open(this, ReportListActivity::class.java)
                else -> Home.open(this, MeActivity::class.java)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Family.loadEvents(100) { all -> runOnUiThread { lastEvents = all; draw(all) } }
    }

    /** 시안: 워드마크 17(y58) · 인사 24 Bold/32(y92) · 오른쪽 이니셜 40 원 */
    private fun header() {
        val header = findViewById<LinearLayout>(R.id.header)
        header.removeAllViews()

        val avatar = Home.avatar(this, "")
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(Home.wordmark(this@GuardianHomeActivity, 17f, Look.MINT).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            })
            addView(avatar)
        })

        val hello = TextView(this).apply {
            text = "안녕하세요"
            textSize = 24f
            letterSpacing = -0.015f
            includeFontPadding = false
            // 시안 「24/32」의 32는 줄 높이지 배수가 아니다. 한글의 자연 행높이가 이미
            // 34.8이라 1.33을 곱하면 46이 되어 인사말 한 줄이 시안보다 14 두꺼워진다
            typeface = Look.bold(this@GuardianHomeActivity)
            setTextColor(Look.color(Look.INK))
            // 시안은 인사말(92)을 「GuADian」 잉크 끝(79)에서 13 아래에 둔다. 그런데 우리
            // 위 칸의 높이는 글자가 아니라 **아바타 40**이 정하므로, 같은 13을 주면 8 더
            // 내려간다. 아바타는 오른쪽에 있어 시안에서도 인사말과 나란히 겹쳐 있다
            setPadding(0, dp(5), 0, 0)
        }
        header.addView(hello)

        Family.profiles { all ->
            // 프로필이 아직 없어도 이름은 이 폰에 있다 — 01-2에서 받아 [NameActivity]에
            // 넣어 둔 그 이름이다. 프로필은 가족이 만들어질 때(P05) 세워지므로, 그 전에
            // 홈에 들르면 서버에는 아무것도 없다. 그렇다고 「안녕하세요」에서 멈추면
            // 방금 이름을 적은 사람에게 앱이 그것을 잊은 것처럼 보인다
            val me = Family.current(this)?.name
                ?: all.firstOrNull { !it.isSenior }?.name
                ?: NameActivity.savedName(this).ifBlank { null }
            val senior = all.firstOrNull { it.isSenior }?.name
            runOnUiThread {
                hello.text = if (me != null) "안녕하세요, ${me}님" else "안녕하세요"
                avatar.text = me?.take(1) ?: ""
                seniorName = senior
                profilesKnown = true
                draw(lastEvents)
            }
        }
    }

    private fun draw(all: List<Family.Event>) {
        if (!profilesKnown) return
        val body = findViewById<LinearLayout>(R.id.body)
        body.removeAllViews()

        val from = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.time
        val today = all.filter { it.at?.after(from) == true }

        // 연결 전에는 아래 세 카드가 말할 것이 없다 — 시안 E02도 연결 안내만 보여 준다.
        //
        // 묻는 것은 **어르신 폰이 들어왔는가**다. [Family.familyId]로 물으면 안 된다 —
        // 이름과 달리 그것은 로그인 계정의 uid라 01(계정 만들기)을 지나는 순간 값이
        // 생긴다. 그걸 기준으로 삼는 동안 갓 가입한 보호자도 곧장 메인 홈으로 가서
        // E02(보호자 시작 홈)를 아무도 보지 못했다
        if (seniorName == null) {
            body.addView(notLinkedCard())
            body.addView(Home.label(this, "가족 연결은 이렇게 진행돼요", 15f).apply {
                typeface = Look.medium(this@GuardianHomeActivity)
                setPadding(0, dp(28), 0, dp(10))
            })
            body.addView(howToCard())
            return
        }

        val open = today.filter { !it.done }
        body.addView(if (open.isEmpty()) safeCard(today) else actionCard(open))

        body.addView(Home.label(this, "오늘 처리한 일").apply {
            setPadding(0, dp(20), 0, dp(10))
        })
        body.addView(todayDone(today))

        body.addView(rowCard(
            R.drawable.ic_h2_list, "이번 주 안심 요약",
            if (today.isEmpty()) "조용한 상태가 유지되고 있어요" else "자세히 보기",
            chev = true, top = 20
        ) { Home.open(this, ReportActivity::class.java) })

        body.addView(rowCard(
            R.drawable.ic_h2_people, seniorName?.let { "${it}님" } ?: "보호 중인 가족",
            "보호 중", chev = false, top = 12
        ) { Home.open(this, ProfileActivity::class.java) })
    }

    // ── 상태 카드 ────────────────────────────────────────────────────────────

    /**
     * 상태 카드의 바깥 틀 (시안 Card/State — r16 · 왼쪽 4px 세로 막대 · 안쪽 20).
     * 막대와 본문을 가로로 놓고, 막대에 카드 높이 전체를 주기 위해 높이는 MATCH_PARENT.
     */
    private fun stateCard(tint: String, edge: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = Look.box(this@GuardianHomeActivity, tint, null, 16)
        clipToOutline = true
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(22) }

        addView(View(this@GuardianHomeActivity).apply {
            setBackgroundColor(Look.color(edge))
            layoutParams = LinearLayout.LayoutParams(dp(EDGE), LinearLayout.LayoutParams.MATCH_PARENT)
        })
        addView(LinearLayout(this@GuardianHomeActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20) - dp(EDGE), dp(20), dp(20), dp(20))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        })
    }

    /** 상태 카드의 본문 자리 (막대 다음 칸) */
    private fun bodyOf(card: LinearLayout) = card.getChildAt(1) as LinearLayout

    /** 시안 Chip/State — 진한 바탕에 흰 글자 12 Bold · 높이 26 */
    private fun chip(text: String, fill: String, radius: Int) = TextView(this).apply {
        this.text = text
        textSize = 12f
        gravity = Gravity.CENTER
        letterSpacing = -0.015f
        includeFontPadding = false
        typeface = Look.bold(this@GuardianHomeActivity)
        setTextColor(Look.color(Look.ON_MINT))
        background = Look.pill(this@GuardianHomeActivity, fill, radius)
        // 위 [chip]과 같은 이유 — 26에 자연 행높이 17.4를 넣으려면 위아래는 4가 한계다
        setPadding(dp(10), dp(4), dp(10), dp(4))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, dp(26)
        )
    }

    /** 카드 안 큰 글 20 Bold/26 · 그 아래 설명 13.5/20 */
    private fun stateText(card: LinearLayout, title: String, sub: String, ink: String, subInk: String) {
        val box = bodyOf(card)
        box.addView(TextView(this).apply {
            text = title
            textSize = 20f
            letterSpacing = -0.015f
            includeFontPadding = false
            // 시안 1498:38·1382:136 — 상태 카드는 **158로 고정**이다. 제목이 한 줄인 날
            // (조용해요)에도 두 줄 자리를 비워 둔다. 홈은 매일 보는 화면이라 상태에 따라
            // 카드가 오르내리면 아래 요약·가족 카드가 날마다 다른 자리에 놓인다.
            // 어르신 홈(H1)은 반대로 194↔228로 늘어난다 — 거기서는 시안이 그렇게 그렸다
            minLines = 2
            typeface = Look.bold(this@GuardianHomeActivity)
            setTextColor(Look.color(ink))
            setPadding(0, dp(12), 0, 0)
        })
        box.addView(TextView(this).apply {
            text = sub
            textSize = 13.5f
            letterSpacing = -0.015f
            includeFontPadding = false
            setLineSpacing(dp(4).toFloat(), 1f)
            setTextColor(Look.color(subInk))
            setPadding(0, dp(8), 0, 0)
        })
    }

    /** 시안 1498:38 / 1506:44 — 조용한 날과 보호한 날. 문구만 다르고 모양은 같다 */
    private fun safeCard(today: List<Family.Event>) =
        stateCard(Look.MINT_TINT, Look.MINT).also { card ->
            val stopped = today.count { it.stopped }
            bodyOf(card).addView(chip("보호 완료", Look.MINT, 13))
            stateText(
                card,
                if (stopped > 0) "오늘 위험한 이동 ${stopped}번을\n모두 막았어요" else "오늘은 조용해요",
                if (stopped > 0) "지금 확인이 필요한 일은 없어요"
                else "감지된 위험이 없고 보호는 정상 연결 상태예요",
                Look.INK, Look.INK_SOFT
            )
        }

    /** 시안 H2-R 1435:191 — 아직 확인하지 않은 일이 있는 날. 알약 모서리가 6이다(r13 아님) */
    private fun actionCard(open: List<Family.Event>) =
        stateCard(ALERT_TINT, Look.DANGER).also { card ->
            val newest = open.first()
            val who = seniorName?.let { "${it}님께" } ?: "가족에게"
            bodyOf(card).addView(chip("확인 필요 ${open.size}건", Look.DANGER, 6))
            stateText(
                card,
                "${who} 확인이 필요해요",
                newest.at?.let { "${hhmm.format(it)} · ${newest.typeLabel}" } ?: newest.typeLabel,
                ALERT_INK, ALERT_INK
            )
            bodyOf(card).addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(16), 0, 0)
                addView(actionButton("전화하기", R.drawable.ic_h2_phone, true) { dial() }.apply {
                    (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(12)
                })
                addView(actionButton("기록 보기", 0, false) {
                    Home.open(this@GuardianHomeActivity, EventListActivity::class.java)
                })
            })
        }

    /** 시안 1435:197/201 — 48 · r12 · 15 Bold. 왼쪽 파랑 채움, 오른쪽 흰 바탕 */
    private fun actionButton(label: String, icon: Int, primary: Boolean, onTap: () -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = Look.box(
                this@GuardianHomeActivity, if (primary) Look.MINT else Look.ON_MINT, null, 12
            )
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
            if (icon != 0) addView(ImageView(this@GuardianHomeActivity).apply {
                setImageResource(icon)
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
                    .apply { rightMargin = dp(8) }
            })
            addView(TextView(this@GuardianHomeActivity).apply {
                text = label
                textSize = 15f
                letterSpacing = -0.015f
                includeFontPadding = false
                typeface = Look.bold(this@GuardianHomeActivity)
                setTextColor(Look.color(if (primary) Look.ON_MINT else ALERT_INK))
            })
            setOnClickListener { onTap() }
        }

    /** 시안 E02 1382:44 — 연결 전. 카드 안에 흰 버튼이 하나 들어간다 */
    private fun notLinkedCard() = stateCard(Look.MINT_TINT, Look.DETECT).also { card ->
        bodyOf(card).addView(chip("연결 전", Look.MINT, 13))
        stateText(
            card, "아직 연결된 가족이 없어요",
            "가족의 휴대폰을 연결하면 보호가 시작돼요",
            Look.INK, Look.INK_SOFT
        )
        bodyOf(card).addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Look.box(this@GuardianHomeActivity, Look.ON_MINT, null, 12)
            setPadding(dp(18), 0, dp(18), 0)
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
            ).apply { topMargin = dp(20) }
            addView(TextView(this@GuardianHomeActivity).apply {
                text = "가족 연결코드 받기"
                textSize = 15f
                letterSpacing = -0.015f
                includeFontPadding = false
                typeface = Look.bold(this@GuardianHomeActivity)
                setTextColor(Look.color(Look.MINT))
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            })
            addView(ImageView(this@GuardianHomeActivity).apply {
                setImageResource(R.drawable.ic_h2_arrow)
                layoutParams = LinearLayout.LayoutParams(dp(8), dp(14))
            })
            setOnClickListener {
                // 시안 흐름(1036:4) — 「시작 홈(미연결) → 연결 코드 받기 → P05 연결 보내기」.
                // P01(보호 설정)로 보내고 있었는데, 이 화면에 닿았다는 것 자체가 네 걸음을
                // 이미 지났다는 뜻이다. 남은 일은 코드를 꺼내 가족에게 건네는 것뿐
                Home.open(this@GuardianHomeActivity, InviteActivity::class.java)
            }
        })
    }

    /** 시안 E02 Card/연결 안내 1499:3 — 번호 동그라미 28 · 이름 15 Medium · 설명 13 */
    private fun howToCard() = Home.card(this).apply {
        setPadding(dp(20), dp(16), dp(20), dp(16))
        listOf(
            Triple("1", "연결 코드 받기", "아래 버튼을 누르면 코드가 나와요"),
            Triple("2", "가족에게 전달", "문자 링크나 QR로 보내요"),
            Triple("3", "연결 완료", "가족이 코드를 입력하면 연결돼요"),
        ).forEachIndexed { i, (no, title, desc) ->
            addView(LinearLayout(this@GuardianHomeActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(if (i == 0) 0 else 14), 0, 0)
                addView(FrameLayout(this@GuardianHomeActivity).apply {
                    background = Look.dot(Look.MINT_TINT)
                    layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
                        .apply { rightMargin = dp(12) }
                    addView(TextView(this@GuardianHomeActivity).apply {
                        text = no
                        textSize = 14f
                        gravity = Gravity.CENTER
                        includeFontPadding = false
                        typeface = Look.bold(this@GuardianHomeActivity)
                        setTextColor(Look.color(Look.MINT))
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                    })
                })
                addView(LinearLayout(this@GuardianHomeActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    )
                    addView(TextView(this@GuardianHomeActivity).apply {
                        text = title
                        textSize = 15f
                        letterSpacing = -0.015f
                        includeFontPadding = false
                        typeface = Look.medium(this@GuardianHomeActivity)
                        setTextColor(Look.color(Look.INK))
                    })
                    addView(TextView(this@GuardianHomeActivity).apply {
                        text = desc
                        textSize = 13f
                        letterSpacing = -0.015f
                        includeFontPadding = false
                        setTextColor(Look.color(Look.INK_SOFT))
                        setPadding(0, dp(6), 0, 0)
                    })
                })
            })
        }
    }

    // ── 아래 카드 셋 ──────────────────────────────────────────────────────────

    /**
     * 오늘 처리한 일 (시안 Card/TodayDone — 줄 14.5 Regular + 오른쪽 건수 15.5 Bold,
     * 아래 구분선과 12.5 한 줄).
     *
     * 「위험한 이동」과 「설치 시도」를 나눠 세는 이유는 둘의 무게가 달라서다 —
     * 이동은 되돌릴 수 있지만 설치는 되돌리기 어렵다. 0건은 회색으로 적어 파란 숫자가
     * "오늘 실제로 막은 것"만 가리키게 한다.
     */
    private fun todayDone(today: List<Family.Event>) = Home.card(this).apply {
        setPadding(dp(20), dp(20), dp(20), dp(16))
        // [Home.card]가 기본으로 다는 아래 여백 12를 끈다. 다음에 오는 요약 카드가 자기
        // 위 여백 20을 갖고 있어 그대로 두면 32가 되는데, 시안은 20이다 (494 → 514)
        (layoutParams as LinearLayout.LayoutParams).bottomMargin = 0
        val apk = today.count { it.typeLabel == Family.KIND_APK }
        val moved = today.count { it.stopped && it.typeLabel != Family.KIND_APK }

        addView(countRow("위험한 이동을 막았어요", moved))
        addView(countRow("설치 시도를 막았어요", apk).apply { setPadding(0, dp(14), 0, 0) })
        addView(View(this@GuardianHomeActivity).apply {
            setBackgroundColor(Look.color(Look.LINE))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply { topMargin = dp(16) }
        })
        addView(TextView(this@GuardianHomeActivity).apply {
            text = if (today.isEmpty()) "오늘은 감지된 위험이 없어요" else "모두 안전하게 처리했어요"
            textSize = 12.5f
            letterSpacing = -0.015f
            includeFontPadding = false
            setTextColor(Look.color(Look.INK_SOFT))
            setPadding(0, dp(14), 0, 0)
        })
    }

    private fun countRow(label: String, n: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        addView(TextView(this@GuardianHomeActivity).apply {
            text = label
            textSize = 14.5f
            letterSpacing = -0.015f
            includeFontPadding = false
            setTextColor(Look.color(Look.INK))
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        })
        addView(TextView(this@GuardianHomeActivity).apply {
            text = "${n}건"
            textSize = 15.5f
            letterSpacing = -0.015f
            includeFontPadding = false
            typeface = Look.bold(this@GuardianHomeActivity)
            setTextColor(Look.color(if (n > 0) Look.MINT else Look.INK_MUTED))
        })
    }

    /**
     * 아이콘 + 두 줄 + 오른쪽 꺾쇠(또는 알약) 한 장
     * (시안 Card/WeeklySummary 1506:30 · Card/Family 1506:37 — 72 · r16 · 아이콘 22).
     */
    private fun rowCard(
        icon: Int, title: String, sub: String, chev: Boolean, top: Int, onTap: () -> Unit,
    ) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = Look.box(this@GuardianHomeActivity, Look.CARD, null, 16)
        setPadding(dp(20), 0, dp(20), 0)
        isClickable = true
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(72)
        ).apply { topMargin = dp(top) }
        setOnClickListener { onTap() }

        addView(ImageView(this@GuardianHomeActivity).apply {
            setImageResource(icon)
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
                .apply { rightMargin = dp(10) }
        })
        addView(LinearLayout(this@GuardianHomeActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
            addView(TextView(this@GuardianHomeActivity).apply {
                text = title
                textSize = 16f
                letterSpacing = -0.015f
                includeFontPadding = false
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                typeface = Look.bold(this@GuardianHomeActivity)
                setTextColor(Look.color(Look.INK))
            })
            addView(TextView(this@GuardianHomeActivity).apply {
                text = sub
                textSize = 13f
                letterSpacing = -0.015f
                includeFontPadding = false
                setTextColor(Look.color(Look.INK_SOFT))
                setPadding(0, dp(6), 0, 0)
            })
        })
        if (chev) addView(ImageView(this@GuardianHomeActivity).apply {
            setImageResource(R.drawable.ic_h2_chev)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
        }) else addView(TextView(this@GuardianHomeActivity).apply {
            text = "연결됨"
            textSize = 12f
            gravity = Gravity.CENTER
            letterSpacing = -0.015f
            includeFontPadding = false
            typeface = Look.bold(this@GuardianHomeActivity)
            setTextColor(Look.color(Look.MINT))
            background = Look.pill(this@GuardianHomeActivity, Look.MINT_TINT, 12)
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

    /** 직접 발신 없음 — 전화 앱 열기만 */
    private fun dial() {
        try {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:")))
        } catch (e: ActivityNotFoundException) {
            // 전화 앱 없는 기기(태블릿): 아무 동작 없음
        }
    }

    private fun dp(v: Int) = Look.dp(this, v)
}
