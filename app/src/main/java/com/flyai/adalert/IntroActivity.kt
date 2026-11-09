package com.flyai.adalert

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 서비스 소개 (피그마 v2 · 00) — 앱 첫 실행 화면.
 *
 * 이 화면의 메시지는 셋뿐: 광고 **감지**, 위험 시 **차단**, 가족에게 **알림**.
 * 권한·계정 이야기는 아직 없음 — 무엇을 해주는 앱인지 먼저 알아야
 * 다음 화면에서 번호를 넣을 마음이 생김.
 *
 * 이미 프로필을 고른 폰은 여기 진입 없음([MeActivity] 바로 열림).
 */
class IntroActivity : Activity() {

    private companion object {
        /** 줄 사이 틈 — 위 [row] 설명 참고 (시안의 20이 아니다) */
        const val ROW_GAP = 17
    }

    private class Feature(val icon: Int, val title: String, val desc: String)

    // 문구·아이콘은 피그마 00 그대로 (타일 40 r12 #E8F0FF 안의 파란 선 아이콘)
    private val features = listOf(
        Feature(R.drawable.ic_p0_eye, "위험 광고 자동 감지", "화면 속 광고를 먼저 찾아 표시해요"),
        Feature(R.drawable.ic_p0_shield_check, "이동 전 한 번 더 확인", "위험한 곳으로 가기 전에 막아요"),
        Feature(R.drawable.ic_p0_bell, "가족에게 즉시 알림", "필요할 때 가족에게 바로 연결돼요"),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 가족 연결·프로필 선택 완료 폰은 소개 재표시 불필요.
        // 개발 중 이 화면만 볼 때는 --ez preview true 로 건너뛰기
        // (BuildConfig는 이 프로젝트에서 미생성 — [AdDetectService] 참고).
        val debug = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        val preview = debug && intent.getBooleanExtra("preview", false)

        // 개발 중 화면 확인용 통로. 준비 화면들은 순서대로만 열림 → 가운데 한 장 수정 때마다
        // 처음부터 진입은 시간 낭비. 배포 빌드에는 없음.
        //   adb shell am start -n .../.IntroActivity --ez preview true --es screen s01
        val screen = if (debug) intent.getStringExtra("screen") else null
        if (screen != null) {
            val target = when (screen) {
                "intro" -> null
                "account" -> AccountActivity::class.java
                "name" -> NameActivity::class.java
                "role" -> RoleActivity::class.java
                "p01" -> SetupActivity::class.java
                "p02" -> PolicyActivity::class.java
                "p03" -> NotifyActivity::class.java
                "p04" -> ShareActivity::class.java
                "p05" -> InviteActivity::class.java
                "fcg" -> InviteActivity::class.java     // FC-G — 아래에서 관리 모드로 연다
                "signedup" -> SignedUpActivity::class.java
                "p06" -> LinkedActivity::class.java
                "s01" -> SeniorSetupActivity::class.java
                "s02" -> ConsentActivity::class.java
                "s03" -> PermissionActivity::class.java
                "s04" -> DoneActivity::class.java
                "home" -> SeniorHomeActivity::class.java
                "summary" -> SeniorSummaryActivity::class.java
                "homeg" -> GuardianHomeActivity::class.java
                "me" -> MeActivity::class.java
                "login" -> LoginActivity::class.java
                "profile" -> ProfileActivity::class.java
                "events" -> EventListActivity::class.java
                "family" -> FamilySettingsActivity::class.java
                "report" -> ReportActivity::class.java
                "reportlist" -> ReportListActivity::class.java
                "reportdetail" -> ReportDetailActivity::class.java
                "helpsend" -> HelpSendActivity::class.java
                "result" -> ProtectResultActivity::class.java
                "alerts" -> AlertSettingsActivity::class.java
                "allow" -> AllowListActivity::class.java
                else -> null
            }
            // **자기 자신 finish 금지.** 끝내면 그 화면 하나만 남은 앱 → 뒤로가기 = 종료,
            // 화면 훑어보는 중 앱이 자꾸 꺼짐. 소개를 뒤에 남기면 뒤로가기 돌아올 자리 확보.
            // preview 표시 동봉 — 데이터 없이 열린 화면이 시안의 예시를 채워 보여주기 위함
            // (13G의 허용 목록). 다른 화면은 이 값 미사용.
            if (target != null) startActivity(
                Intent(this, target).putExtra("preview", true)
                    // FC-G(1475:2)는 P05와 같은 화면의 **관리 모드**다 — 이미 만든 코드를
                    // 다시 꺼내 보는 자리라 따로 열 길이 없으면 눈으로 확인할 수 없다
                    .putExtra(InviteActivity.EXTRA_MANAGE, screen == "fcg")
            )
        }
        val me = Family.current(this)
        if (screen == null && !preview && Family.familyId != null && me != null) {
            // 역할별 홈 분기 — 어르신은 "지금은 안전해요", 보호자는 "오늘의 위험".
            startActivity(
                Intent(
                    this,
                    if (me.isSenior) SeniorHomeActivity::class.java
                    else GuardianHomeActivity::class.java
                )
            )
            finish()
            return
        }

        setContentView(R.layout.activity_intro)
        Insets.apply(this)

        paintWordmark(findViewById(R.id.text_wordmark))

        val list = findViewById<LinearLayout>(R.id.list_features)
        features.forEachIndexed { i, f -> list.addView(row(f, last = i == features.lastIndex)) }

        findViewById<Button>(R.id.btn_start).setOnClickListener {
            startActivity(Intent(this, AccountActivity::class.java))
        }
        // 피그마 00의 "이미 계정이 있어요" — 가족 코드 보유 폰은 바로 연결 화면으로
        // (01 계정 화면의 '이미 계정이 있나요? 로그인'과 같은 경로).
        findViewById<TextView>(R.id.btn_login).setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
        }
    }

    /** 워드마크 "GuADian" — Gu·ian은 먹색, 가운데 AD만 로고 꺾쇠와 같은 초록(DS v2) */
    private fun paintWordmark(v: TextView) {
        val word = "GuADian"
        v.text = SpannableString(word).apply {
            setSpan(
                ForegroundColorSpan(getColor(R.color.brand_green)),
                word.indexOf("AD"), word.indexOf("AD") + 2,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    /**
     * 기능 한 줄 (피그마 00 카드 안): 아이콘 타일 40 · 오른쪽 12 · 제목 16 Bold/130% ·
     * 설명 13.5/140% #525C6E. 줄 사이 20 — 카드 자체가 #F5F7FA 바탕이라 줄 테두리 없음.
     */
    /**
     * 시안은 아이콘끼리 60 간격(1035:24 → 1035:30 → 1035:36)이고 카드는 204다.
     * 그런데 글 덩어리(제목 16 + 설명 13.5)가 아이콘 40보다 약 3 높아서
     * 줄 높이가 43이 된다 — 여기에 20을 그대로 더하면 간격이 63이다(실측).
     * 글꼴의 자연 행높이는 lineHeight로 줄여지지 않아, 대신 틈을 줄여 60을 맞춘다.
     * 이 값이면 카드 높이도 시안과 같은 204가 된다
     */
    private fun row(f: Feature, last: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = if (last) 0 else dp(ROW_GAP) }

        addView(ImageView(this@IntroActivity).apply {
            setImageResource(f.icon)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                .apply { rightMargin = dp(12) }
        })

        // 시안 1035:28/29 — 제목 16/1.3 · 설명 13.5/1.4, 둘을 합치면 딱 40으로
        // 아이콘 높이와 같다. 행간을 잡지 않으면 글 덩어리가 아이콘보다 높아져
        // 줄 간격이 60이 아니라 66으로 벌어졌다(실측)
        addView(LinearLayout(this@IntroActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@IntroActivity).apply {
                text = f.title
                textSize = 16f
                letterSpacing = -0.015f
                includeFontPadding = false
                typeface = Look.bold(this@IntroActivity)
                setTextColor(Look.color(Look.INK))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) lineHeight = sp(21f)
            })
            addView(TextView(this@IntroActivity).apply {
                text = f.desc
                textSize = 13.5f
                letterSpacing = -0.015f
                includeFontPadding = false
                setTextColor(Look.color(Look.INK_SOFT))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) lineHeight = sp(19f)
                else setPadding(0, dp(3), 0, 0)
            })
        })
    }

    private fun dp(v: Int) = Look.dp(this, v)

    private fun sp(v: Float) = android.util.TypedValue.applyDimension(
        android.util.TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics
    ).toInt()
}
