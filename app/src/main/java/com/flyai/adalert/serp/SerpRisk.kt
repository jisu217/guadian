package com.flyai.adalert.serp

/**
 * 검색 결과 한 칸의 위험도. 상 · 중 · 하 세 단계.
 *
 * 화면: [label]("위험"/"주의"/"안전"), 로그·팀 문서: [grade]("상"/"중"/"하").
 * 어르신에게 "상"만으로는 의미 전달 불가 — 등급 이름·화면 문구 분리 이유.
 *
 * 색은 신호등 그대로 — 학습 없이 아는 유일한 색 규약, 글자 읽기 어려운 사용자에게도 전달.
 *
 * 경계값(40 / 70): senioradguard V3 검증값 그대로. 프롬프트도 이 경계 전제
 * (“70 이상이면 빨간 표시가 붙는다”) — 한쪽만 변경 시 판정 분포 전체 붕괴.
 */
enum class RiskGrade(val grade: String, val label: String, val minScore: Int, val color: Int) {
    /**
     * 미판단 상태. **'안전' 아님.**
     *
     * 존재 이유 = 이 기능의 전제. 규칙 목록은 작고 완결 불가 — 불법 사이트는
     * 차단 후 숫자만 바꿔 부활. "걸린 규칙 없음" = '안전' 처리 시 **모르는 사이트
     * 전부에 초록 도장** — 어르신이 믿고 누르는 초록이 근거 없는 표시가 됨.
     *
     * 초록은 근거 있을 때만 — 알려진 곳 목록 포함, 또는 판별기의 실제 안전 판정.
     * 그 외 표시 없음.
     */
    UNKNOWN("?", "확인 안 됨", -1, 0x00000000),

    LOW("하", "안전", 0, 0xFF43A047.toInt()),      // 초록
    MEDIUM("중", "주의", 40, 0xFFFB8C00.toInt()),  // 주황
    HIGH("상", "위험", 70, 0xFFE53935.toInt());    // 빨강

    /** 화면 표시 등급 여부. [UNKNOWN]은 표시 없음 */
    val isShown: Boolean get() = this != UNKNOWN

    companion object {
        /**
         * 점수 → 등급. **근거 있는 점수 전용** — 판별기 판정·신호가 만든 점수.
         * "신호 없음 → 0점"에는 사용 금지 (그건 [UNKNOWN]).
         */
        fun of(score: Int): RiskGrade = when {
            score >= HIGH.minScore -> HIGH
            score >= MEDIUM.minScore -> MEDIUM
            else -> LOW
        }
    }
}

/**
 * 위험 원인 분류. 점수만 남기면 추후 원인 추적 불가.
 *
 * 판별기는 스키마로 이 이름 중 하나 선택 강제. 모르는 이름은 [UNKNOWN] 처리 —
 * 자유 문자열 수용 시 집계 불가.
 */
enum class RiskCategory(val label: String) {
    /** 불법 다시보기·토렌트·웹툰 불법유통. 이 기능의 1순위 표적 */
    ILLEGAL_STREAMING_OR_COPYRIGHT("불법 다시보기"),

    /** 사설 도박·토토·카지노 */
    ILLEGAL_GAMBLING("불법 도박"),

    /** 성인물 */
    ADULT_CONTENT("성인 사이트"),

    /** 피싱·사칭·가짜 당첨 */
    PHISHING_OR_SCAM("사기·피싱 의심"),

    /** APK 직접 배포, 악성 앱 설치 유도 */
    MALWARE_OR_UNWANTED_APP("악성 앱 설치 유도"),

    /** 효과를 부풀린 광고성 페이지 */
    EXAGGERATED_CLAIM("과장 광고"),

    /** 정상으로 보이나 확인되지 않은 곳 */
    UNVERIFIED_THIRD_PARTY("확인되지 않은 곳"),

    /** 널리 알려진 사업자·공식 서비스 */
    TRUSTED_KNOWN_BRAND("알려진 곳"),

    UNKNOWN("판단 보류");

    companion object {
        fun parse(raw: String?): RiskCategory =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: UNKNOWN
    }
}

/**
 * 결과 한 칸의 판정.
 *
 * @param score  0~100. 등급의 근거
 * @param reason 어르신용 한 줄. 배지 아래 그대로 표시 — 짧게 유지
 * @param source 판정 출처 — 원인 추적용. [SOURCE_RULE] / [SOURCE_LLM] / [SOURCE_CACHE]
 */
data class SerpVerdict(
    val category: RiskCategory,
    val grade: RiskGrade,
    val score: Int,
    val reason: String,
    val source: String
) {
    companion object {
        const val SOURCE_RULE = "RULE"
        const val SOURCE_LLM = "LLM"
        const val SOURCE_CACHE = "CACHE"

        /** 배지 근거 길이 상한. 길면 어르신이 읽지 않음 */
        const val MAX_REASON_CHARS = 40

        fun of(category: RiskCategory, score: Int, reason: String, source: String): SerpVerdict {
            val bounded = score.coerceIn(0, 100)
            return SerpVerdict(
                category = category,
                grade = RiskGrade.of(bounded),
                score = bounded,
                reason = reason.trim().take(MAX_REASON_CHARS),
                source = source
            )
        }

        /**
         * 미판단 판정. 점수 0이지만 **등급은 [RiskGrade.UNKNOWN]** — 화면 표시 없음.
         * 0점의 '안전' 유출 방지용 별도 생성자.
         */
        fun unknown(source: String) = SerpVerdict(
            category = RiskCategory.UNKNOWN,
            grade = RiskGrade.UNKNOWN,
            score = 0,
            reason = "",
            source = source
        )
    }
}
