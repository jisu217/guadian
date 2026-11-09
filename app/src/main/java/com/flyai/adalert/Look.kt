package com.flyai.adalert

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 화면 생김새 한곳 집약.
 *
 * 값은 피그마 「GuADian · FULL SERVICE FLOW v2」 DS/Board v2 그대로:
 *   파랑 #2E7DFF(정보·광고 탐지·확인) · 주황 #FF8A1F(주의) · 빨강 #E01F26(차단) · 잉크 #0D1220
 *   Primary 버튼 #1F63E0 · 56dp · r12 · 17 Bold 흰 글자 (어르신 66dp · r14 · 19 Bold)
 *   카드 #F5F7FA · r16 · 보조 버튼 #EEF1F5/#39414F
 *
 * 이름의 `MINT`: 옛 시안(민트) 때 이름을 34개 화면이 그대로 사용 — 뜻은 "브랜드 기본색",
 * 현재 값은 파랑. 이름 변경 시 화면 전체 수정 필요 → 값만 교체.
 *
 * ## 왜 XML 테마가 아니라 코드인가
 * 화면 절반은 XML이 아니라 **코드로 그림** — 광고 테두리·배지·쉴드·가드 알림은 다른 앱 위
 * 오버레이 창이라 레이아웃 파일 없음. 두 곳이 같은 값을 쓰려면 코드 쪽에도 색 필요 → 한곳 집약.
 * XML용 값은 `res/values/colors.xml`이 이 파일을 그대로 복사 —
 * **둘 중 하나 변경 시 다른 하나도 변경 필수.**
 *
 * ## 어르신 화면의 크기 규칙
 * 큰 글자·버튼은 취향이 아니라 요구사항. 본문 19sp, 버튼 20sp, 세로 여백 18dp —
 * 손 떨림에도 누를 수 있는 크기 우선 확정, 그 안에 글 배치.
 */
object Look {

    // ── 브랜드 ────────────────────────────────────────────────────────────────
    /** 기본 버튼·강조. 시안에서 뽑은 민트 */
    const val MINT = "#1F63E0"
    const val MINT_DARK = "#174FB8"
    const val MINT_TINT = "#E8F0FF"

    /**
     * 민트 링 안 기호(✓)의 색. 링과 같은 민트면 얇은 획이 바탕에 묻힘 →
     * 시안은 같은 색상(hue)을 어둡게 눌러 사용 — H1 어르신 홈의 ✓에서 뽑은 값
     */
    const val MINT_INK = "#1F63E0"
    /**
     * 노랑 바탕 위의 **글자**. 시안 S02의 "동의는 연결에만 사용되며…"에서 뽑은 값.
     * 노랑(#F0A32E) 그대로 글자에 쓰면 연한 바탕에 묻혀 가독 불가 — 바탕은 노랑,
     * 글자는 그 노랑을 눌러 놓은 황토여야 한 세트
     */
    const val WARN_INK = "#A35A00"

    // ── 바탕과 글자 ───────────────────────────────────────────────────────────
    const val BG = "#FFFFFF"
    const val CARD = "#F5F7FA"
    const val LINE = "#E6E9EE"
    const val LINE_SOFT = "#EEF1F5"
    const val FIELD = "#F5F7FA"
    const val INK = "#0D1220"
    const val INK_BODY = "#39414F"
    const val INK_SOFT = "#525C6E"
    const val INK_MUTED = "#8E97A6"
    const val ON_MINT = "#FFFFFF"
    const val MINT_SHADOW = "#1F63E0"
    /** 아래에서 올라온 시트 위의 손잡이 막대 (시안 09O-M) */
    const val GRABBER = "#E6E9EE"

    // ── 화면 아래 안내 바 ─────────────────────────────────────────────────────
    //
    // 시안에 두 벌: 막았거나 빠져나왔을 때는 진한 먹색(12O), 괜찮다는 안내는 옅은 카드(09O-L).
    // 둘 다 [동그라미 + 굵은 한 줄 + 설명 한 줄] 구조
    const val BAR_DARK = "#0D1220"
    const val BAR_DARK_SUB = "#C9D0DB"
    const val BAR_INFO_LINE = "#E6E9EE"
    const val BAR_INFO_DOT = "#8E97A6"

    // 리포트·요약 화면용 한 벌 (피그마 변수 이름 그대로)
    /** brand/100 — 리포트의 민트 상자 바탕 */
    const val BRAND_100 = "#E8F0FF"
    /** status/warn-soft — 안내 상자 바탕 */
    const val WARN_SOFT = "#FFF1E0"
    /** color/border/default — 리포트 카드 테두리 1px */
    const val BORDER_SOFT = "#E6E9EE"

    // ── 시작하기(Phase 0·1)의 한 벌 ──────────────────────────────────────────
    //
    // 준비 화면은 색이 조금 다름 — 이 구간은 아직 위험을 다루지 않고 **설명하고 준비하는**
    // 시간이라 시안이 한 톤 부드럽게 설정
    /** 준비 화면 바탕 (그라데이션이 아니라 단색) */
    const val SETUP_BG = "#FFFFFF"
    /** 준비 화면의 줄 사이 선 */
    const val SETUP_LINE = "#E6E9EE"
    /** 단계 알약('보호자 2/5')과 '권고' 배지의 무채색 바탕 */
    const val NEUTRAL_TINT = "#EEF1F5"
    /** 준비 화면 안내 상자의 테두리 — 바탕과 같은 초록의 72% */
    const val NOTICE_LINE = "#00FFFFFF"
    /** 준비 화면 안내 상자 */
    const val SETUP_NOTICE = "#F5F7FA"
    /** P04 '공유하는 안전 정보' 상자의 테두리 — 안내 상자보다 한 톤 진함 */
    const val SHARED_LINE = "#E6E9EE"
    const val SETUP_NOTICE_INK = "#525C6E"
    /** 카드 줄의 제목·설명 */
    const val SETUP_TITLE_INK = "#0D1220"
    const val SETUP_DESC_INK = "#525C6E"

    // ── 광고 테두리 ───────────────────────────────────────────────────────────
    //
    // 서비스가 그리고([AdDetectService]) 내 정보 화면이 설정한다([MeActivity]).
    // 두 곳이 같은 값을 봐야 하므로 색과 같은 자리에 둔다.
    /** 기본 두께(dp) — 피그마 v2 2-1 AdArea/Outline */
    const val AD_BORDER_W = 4
    /**
     * 사용자가 올릴 수 있는 상한. 시력 편차가 커서 기본 4dp를 못 알아보는 경우가 있다.
     *
     * 테두리는 광고 영역 **안쪽**에 그려져 광고 자체의 여백보다 두꺼우면 내용을 덮는다 —
     * 실측(2026-08-24): blog.naver.com 광고는 8.53dp, cnuclinic.co.kr은 10.31dp에서 닿기 시작.
     * 그래도 12까지 연 것은 "가려도 잘 보이는 쪽"을 어르신이 고를 수 있게 하려는 것
     */
    const val AD_BORDER_MAX = 12
    /** 모서리 반경 — 시안 12에서 덜 둥글게 조정 */
    const val AD_BORDER_RADIUS = 6
    /** 두께 설정이 저장되는 열쇠 (prefs `settings`) */
    const val AD_BORDER_KEY = "border_w"

    // ── 위험도 ────────────────────────────────────────────────────────────────
    //
    // 시안의 네 가지 색 그대로. 뜻은 우리 쪽 규칙 —
    // 파랑 "광고 확실, 목적지 확인 전", 초록 "확인 결과 문제없음".
    // 시안에 초록(확인된 안전) 없음 → 민트 계열에서 한 톤 진하게 신규 지정
    const val UNKNOWN = "#2E7DFF"      // 파랑 — 확인 전
    const val UNKNOWN_TINT = "#E8F0FF"
    const val SAFE = "#17876B"         // 초록 — 확인된 안전
    const val SAFE_TINT = "#E6F4EF"
    const val WARN = "#FF8A1F"         // 노랑 — 주의
    const val WARN_TINT = "#FFF1E0"
    const val WARN_LINE = "#FFD6A8"    // 반복 위험 출처 카드 테두리

    /**
     * 쉴드 "의심 신호" 상자: 연한 바탕 + **한 톤 진한 테두리** (시안 09O-M·10O).
     * 노랑·분홍은 시안에서 뽑은 값, 파랑·초록은 시안에 해당 상자 없음 → 같은 규칙
     * (톤을 흰색 쪽으로 절반 끌어당김)으로 생성
     */
    const val DANGER_LINE = "#F5A3A6"
    const val UNKNOWN_LINE = "#A9C6FF"
    const val SAFE_LINE = "#9ED2C0"
    const val DANGER = "#E01F26"       // 빨강 — 위험
    const val DANGER_TINT = "#FFEAEA"

    /**
     * 파랑 바탕 위의 **글자** (P02의 '허용' 배지). [UNKNOWN] 그대로 글자에 쓰면
     * 연한 파랑 바탕에 묻힘 — 노랑에 [WARN_INK]가 있는 것과 같은 이유
     */
    const val INFO_TINT = "#E8F0FF"
    const val INFO_INK = "#1F63E0"
    const val DETECT = "#2E7DFF"       // 분홍 — 시안의 '감지됨' 표시
    /** 줄 설명·시각처럼 한 단계 더 흐린 글자 */
    const val INK_FAINT = "#8E97A6"

    // ── 보호자 홈(H2)이 쓰는 한 벌 ────────────────────────────────────────────
    //
    // 이 화면의 막대는 유형마다 색 고정. 같은 노랑·빨강 재사용이 아니라 시안이 따로 잡은 값 →
    // 위험도 색(WARN·DANGER)과 섞이지 않게 이름 분리
    const val BAR_APK = "#E01F26"      // 앱 설치 유도 막대
    const val BAR_LINK = "#FF8A1F"     // 위험 링크 막대
    /** 흰 알약 안의 '주의' 글자 — 노랑 위 노랑 회피용 살구색 */
    const val WARN_PILL_INK = "#A35A00"
    /** 민트 연한 알약 안의 글자 ('모두 경고함') */
    const val MINT_DEEP = "#1F63E0"

    /**
     * 연한 민트 알약 위의 글자 (스플래시의 서비스 이름, H1의 '연결됨').
     * `colors.xml`의 `brand_ink`와 같은 값 필수
     */
    const val BRAND_INK = "#1F63E0"

    // ── 고를 수 있는 카드 (역할 선택·프로필) ──────────────────────────────────
    //
    // 시안 값 그대로: 배경 #E0F7EF, 테두리 2px #3ED3B5, 안쪽 여백 24/20,
    // 카드 사이 간격 10. 고른 카드만 테두리 + 오른쪽 위 체크
    const val PICK_FILL = MINT_TINT
    const val PICK_LINE = MINT
    const val PICK_STROKE = 2
    const val PICK_PAD_H = 20
    const val PICK_PAD_V = 20
    const val PICK_GAP = 12

    /**
     * 시안의 '부모로 시작하기' 카드 — 어르신 쪽은 따뜻한 노랑 계열.
     * 값은 [WARN_TINT]·[WARN_LINE]과 **동일** (시안 02 실측값).
     * 뜻이 달라 이름은 분리, 색은 한 벌
     */
    const val PICK_FILL_SENIOR = WARN_TINT
    const val PICK_LINE_SENIOR = WARN_LINE

    /**
     * 눈에 걸려야 하는 낱말의 빨강 (시안 02의 '사용자', 09O-M의 '!').
     * [DANGER]보다 한 톤 진해 **글자용**으로 가독 — DANGER는 테두리·바탕용
     */
    const val ALERT_INK = "#E01F26"

    // ── 크기 ──────────────────────────────────────────────────────────────────
    const val RADIUS_CARD = 16
    const val RADIUS_BUTTON = 12
    const val RADIUS_BADGE = 12
    const val TEXT_TITLE = 24f
    const val TEXT_BODY = 16f
    const val TEXT_SMALL = 13.5f
    const val TEXT_BUTTON = 17f
    const val TEXT_BADGE = 12f

    fun color(hex: String): Int = Color.parseColor(hex)

    /**
     * 진짜 Bold 글꼴.
     *
     * `Typeface.DEFAULT_BOLD`는 **시스템 글꼴의 굵은 체** — 삼성폰이면 One UI Sans.
     * XML 화면은 테마에서 Noto, 코드로 만든 글자만 시스템 글꼴 → 같은 화면 안에 글씨체 두 가지.
     * 오버레이(테두리 배지·쉴드)는 XML 없음 → 여기서 직접 로드. 한 번 읽어 재사용(파일 2.8MB)
     */
    private var boldFace: Typeface? = null
    private var mediumFace: Typeface? = null

    fun bold(ctx: Context): Typeface =
        boldFace ?: (ctx.resources.getFont(R.font.noto_sans_kr_bold)).also { boldFace = it }

    /** 시안의 본문·부제 굵기(500). Regular보다 한 단계 진함 */
    fun medium(ctx: Context): Typeface =
        mediumFace ?: (ctx.resources.getFont(R.font.noto_sans_kr_medium)).also { mediumFace = it }

    fun dp(ctx: Context, v: Int): Int = dp(ctx, v.toFloat())

    /** 시안에 1.8px 같은 값 존재 → 소수점 입력용 오버로드 */
    fun dp(ctx: Context, v: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics
        ).toInt()

    // ── 조각들 ────────────────────────────────────────────────────────────────

    /** 카드 (#F5F7FA · r16). 쉴드·목록 항목 공통. 테두리는 선택 */
    fun card(ctx: Context, radius: Int = RADIUS_CARD, stroke: String? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color(CARD))
            cornerRadius = dp(ctx, radius).toFloat()
            if (stroke != null) setStroke(dp(ctx, 1), color(stroke))
        }

    fun pill(ctx: Context, fill: String, radius: Int = RADIUS_BADGE): GradientDrawable =
        GradientDrawable().apply {
            setColor(color(fill))
            cornerRadius = dp(ctx, radius).toFloat()
        }

    /**
     * 색이 깔린 상자 (경고 카드·미리보기 상자·줄 카드). 테두리는 선택.
     * @param fill null이면 배경 없이 테두리만 — 시안의 줄 카드 모양
     *             (화면 바탕 그라데이션 그대로 비침)
     */
    fun box(
        ctx: Context,
        fill: String?,
        stroke: String? = null,
        radius: Int = RADIUS_CARD,
        strokeW: Float = 1f,
    ): GradientDrawable = GradientDrawable().apply {
        setColor(if (fill == null) Color.TRANSPARENT else color(fill))
        cornerRadius = dp(ctx, radius).toFloat()
        if (stroke != null) setStroke(dp(ctx, strokeW), color(stroke))
    }

    /** 줄 앞에 붙는 동그라미 */
    fun dot(fill: String): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color(fill))
    }

    /**
     * 화면 아래 큰 버튼의 민트 그림자 (시안: Y6 · 흐림16 · #009F7D 28%).
     * 안드로이드 그림자 **색** 지정은 API 28부터. 그 아래는 회색
     */
    fun mintShadow(v: View) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            v.outlineSpotShadowColor = color(MINT_SHADOW)
            v.outlineAmbientShadowColor = color(MINT_SHADOW)
        }
    }

    /**
     * 큰 기본 버튼(민트). 어르신이 누르는 버튼 전부 이 크기.
     * @param primary false면 흰 바탕에 테두리만 있는 보조 버튼
     */
    fun bigButton(
        ctx: Context,
        label: String,
        primary: Boolean = true,
        onTap: () -> Unit,
    ): TextView = TextView(ctx).apply {
        // DS/Board v2: Primary 342×56 · r12 · 17 Bold 흰 글자 / Secondary 동일 규격 ·
        // #EEF1F5 바탕 · 16 Medium #39414F. 테두리 없음
        text = label
        textSize = if (primary) TEXT_BUTTON else 16f
        letterSpacing = -0.015f
        gravity = Gravity.CENTER
        includeFontPadding = false
        typeface = if (primary) bold(ctx) else medium(ctx)
        setTextColor(color(if (primary) ON_MINT else INK_BODY))
        background = GradientDrawable().apply {
            setColor(color(if (primary) MINT else LINE_SOFT))
            cornerRadius = dp(ctx, RADIUS_BUTTON).toFloat()
        }
        isClickable = true
        setOnClickListener { onTap() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 56)
        ).apply { bottomMargin = dp(ctx, 12) }
    }

    /**
     * 어르신 화면의 큰 버튼 — 342×66 · r14 · 19 Bold. 손 떨림에도 누를 수 있는 크기
     */
    fun seniorButton(
        ctx: Context,
        label: String,
        primary: Boolean = true,
        onTap: () -> Unit,
    ): TextView = bigButton(ctx, label, primary, onTap).apply {
        textSize = if (primary) 19f else 18f
        background = GradientDrawable().apply {
            setColor(color(if (primary) MINT else LINE_SOFT))
            cornerRadius = dp(ctx, 14).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 66)
        ).apply { bottomMargin = dp(ctx, 12) }
    }

    /** 제목 줄 */
    fun title(ctx: Context, text: String, tint: String = INK): TextView = TextView(ctx).apply {
        this.text = text
        textSize = TEXT_TITLE
        typeface = bold(ctx)
        letterSpacing = -0.015f
        setLineSpacing(0f, 1.12f)
        setTextColor(color(tint))
    }

    /** 본문 줄 */
    fun body(ctx: Context, text: String, tint: String = INK_SOFT): TextView = TextView(ctx).apply {
        this.text = text
        textSize = TEXT_BODY
        letterSpacing = -0.015f
        setLineSpacing(0f, 1.3f)
        setTextColor(color(tint))
    }

    /** 작은 설명 줄 */
    fun small(ctx: Context, text: String, tint: String = INK_MUTED): TextView = TextView(ctx).apply {
        this.text = text
        textSize = TEXT_SMALL
        setTextColor(color(tint))
    }

    /** 세로 여백 */
    fun gap(ctx: Context, height: Int): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, height)
        )
    }

    /**
     * 위험도별 색 한 벌. 테두리·배지·쉴드가 같은 표 참조 →
     * 한 곳 수정으로 화면 전체 반영
     */
    class Tone(val line: String, val tint: String, val label: String)

    /** 진한 색에 어울리는 연한 짝. 동그라미 배경처럼 색만 필요할 때 사용 */
    fun tintOf(ink: String): String = when (ink) {
        DANGER -> DANGER_TINT
        WARN -> WARN_TINT
        SAFE -> SAFE_TINT
        UNKNOWN -> UNKNOWN_TINT
        else -> FIELD
    }

    fun tone(mark: String?): Tone = when (mark) {
        // spec.md: 일반 광고 = 파랑. 확인 결과 안전한 것도 "일반 광고"라 파랑 그대로
        "LOW" -> Tone(UNKNOWN, UNKNOWN_TINT, "광고")
        "MEDIUM" -> Tone(WARN, WARN_TINT, "주의")
        "HIGH" -> Tone(DANGER, DANGER_TINT, "위험")
        else -> Tone(UNKNOWN, UNKNOWN_TINT, "광고")
    }
}
