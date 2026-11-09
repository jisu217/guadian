package com.flyai.adalert.serp

/**
 * 신호·판별기 결과의 단일 등급 통합. 순수 함수 모음.
 *
 * ## 점수 산출 방식
 * 신호 단순 합산 없음 — 합산 시 사소한 신호 다섯이 확정 신호 하나를 압도,
 * 규칙 추가마다 기존 판정 전체 흔들림.
 *
 *   점수 = 가장 강한 신호 + (나머지 신호 합 ÷ 3) + 신뢰 가산(음수)
 *
 * 가장 강한 신호가 등급 결정, 나머지는 보조. 규칙 추가에도 기존 정답 판정 유지.
 * senioradguard V3 검증식 그대로 이식.
 */
object RiskAggregator {

    /** 보조 신호 반영 비율(분모). 작을수록 "가장 강한 신호" 중심 */
    private const val SUPPORT_DIVISOR = 3

    fun score(signals: List<Signal>): Int {
        val positives = signals.filter { it.weight > 0 }.sortedByDescending { it.weight }
        if (positives.isEmpty()) return 0

        val trust = signals.filter { it.weight < 0 }.sumOf { it.weight }
        val support = positives.drop(1).sumOf { it.weight } / SUPPORT_DIVISOR
        return (positives.first().weight + support + trust).coerceIn(0, 100)
    }

    /**
     * 확정 신호만으로 계산한 **하한**.
     *
     * 판별기가 "안전" 응답해도 이 아래로 하향 없음. 판별기는 글자만 보므로
     * 도박 용어·IP 직결 같은 **주소에 이미 드러난 사실**을 간과하는 경우 존재.
     *
     * 신뢰 가산 미반영 — 알려진 플랫폼의 글이라는 사실이 불법 사이트 안내라는
     * 사실을 지우지 못함.
     */
    fun hardFloor(signals: List<Signal>): Int {
        val hard = signals.filter { it.hard && it.weight > 0 }.sortedByDescending { it.weight }
        if (hard.isEmpty()) return 0
        val support = hard.drop(1).sumOf { it.weight } / SUPPORT_DIVISOR
        return (hard.first().weight + support).coerceIn(0, 100)
    }

    /** 가장 강한 신호의 성격. 없으면 신뢰 신호, 그것도 없으면 보류 */
    fun category(signals: List<Signal>): RiskCategory {
        signals.filter { it.weight > 0 && it.category != RiskCategory.UNKNOWN }
            .maxByOrNull { it.weight }
            ?.let { return it.category }

        if (signals.any { it.category == RiskCategory.TRUSTED_KNOWN_BRAND }) {
            return RiskCategory.TRUSTED_KNOWN_BRAND
        }
        if (signals.any { it.weight > 0 }) return RiskCategory.UNVERIFIED_THIRD_PARTY
        return RiskCategory.UNKNOWN
    }

    /** 배지용 근거 한 줄. 가장 강한 신호의 문장 사용 */
    fun reason(signals: List<Signal>): String {
        signals.filter { it.weight > 0 }.maxByOrNull { it.weight }?.let { return it.reason }
        signals.firstOrNull { it.weight < 0 }?.let { return it.reason }
        return "확인된 위험은 없습니다"
    }

    /**
     * 판별기 없이 신호만으로 내리는 판정.
     *
     * ## 걸린 규칙 없음 ≠ 안전
     * 위험·신뢰 신호 모두 없으면 [SerpVerdict.unknown]. 0점의 '안전' 유출 시
     * **모르는 사이트 전부에 초록 도장.** 목록은 작고 완결 불가 — 그 초록 대부분은 근거 없음.
     * 초록은 알려진 곳이거나 판별기의 실제 안전 판정 시에만.
     *
     * 위험 신호 하나라도 있으면 점수대로 판정. 종합 점수와 [hardFloor] 중 높은 쪽 —
     * 하한 누락 시 신뢰 가산이 확정 신호 상쇄.
     * 예: "cafe.naver.com의 도박 권유 글"이 네이버라는 이유로 '안전' 처리.
     */
    fun ruleVerdict(signals: List<Signal>): SerpVerdict {
        val trusted = signals.any { it.category == RiskCategory.TRUSTED_KNOWN_BRAND }
        val risky = signals.any { it.weight > 0 }
        if (!trusted && !risky) return SerpVerdict.unknown(SerpVerdict.SOURCE_RULE)

        return SerpVerdict.of(
            category = category(signals),
            score = maxOf(score(signals), hardFloor(signals)),
            reason = reason(signals),
            source = SerpVerdict.SOURCE_RULE
        )
    }

    /**
     * 판별기 결과와 신호의 결합.
     *
     * **판별기가 최종 판단, 신호는 [hardFloor]로 바닥만 지지.** 순서 반대
     * (신호 판단 + 판별기 보정) 시 목록 밖 새 사이트 영구 미검출 —
     * 판별기 도입 목적이 바로 목록 밖 검출.
     */
    fun combine(signals: List<Signal>, classified: SerpVerdict?): SerpVerdict {
        if (classified == null) return ruleVerdict(signals)

        val floor = hardFloor(signals)
        val score = maxOf(classified.score, floor)
        // 규칙이 판정을 끌어올렸으면 근거도 규칙 쪽 문장 —
        // 어르신에게 "왜 위험한지"·"얼마나 위험한지" 불일치 금지
        val reason = if (floor > classified.score) reason(signals) else classified.reason

        return SerpVerdict.of(
            category = if (classified.category == RiskCategory.UNKNOWN) category(signals)
            else classified.category,
            score = score,
            reason = reason,
            source = classified.source
        )
    }
}
