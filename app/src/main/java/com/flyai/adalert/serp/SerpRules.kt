package com.flyai.adalert.serp

/**
 * **AI 호출 여부 결정 문지기.** 이 기능의 비용 구조 전체가 여기서 결정.
 *
 * 규칙만으로 결론 시 [resolve]가 판정 반환, 판별기 호출 없음.
 * 결론 불가 시 null 반환 — 그때만 Gemini 호출.
 *
 * ## 규칙 단독 종결 가능 두 경우
 *
 * **1. 확정 위험** — 확정 신호([Signal.hard])만으로 '위험'(70) 이상.
 * 불법 다시보기 이름(tvhot·newtoki·누누), 도박 용어, 성인 사이트.
 * 전부 주소에 이미 드러난 사실 — 추론 불필요, **네트워크 왕복 없이 즉시 빨강.**
 *
 * **2. 확정 안전** — 잘 알려진 도메인 + 위험 신호 전무.
 * tving.com, netflix.com, kbs.co.kr. 보통 검색 결과 목록의 절반 가까이 해당.
 *
 * ## '주의'(40) 아닌 '위험'(70)에서만 종결하는 이유
 * 표본 27건으로 두 경계 검증(2026-08-14). 40 기준: 25/27 규칙 종결로 저렴하지만
 * **APK 직접 배포 사이트·IP 직결 무료영화 사이트가 '주의'로 확정** — 둘 다 판별기
 * 기준 90점대 '위험'.
 *
 * 규칙 확정 항목은 판별기 미열람 — **오판 시 수정 기회 없음.** '주의'는 정의상
 * 확신 없음, 확신 없으면 넘기는 것이 이 문지기의 계약. 경계 70: 규칙 확정 15/27로
 * 감소, 그중 오답 0.
 *
 * 비용 거의 동일 — 판별기 호출은 **항목당 아닌 화면당 1회**, 한 화면에서
 * 판별기행 항목이 2개든 12개든 호출 수 동일.
 *
 * ## "알려진 도메인"만으로 종결하지 않는 이유
 * 신뢰 도메인이어도 **위험 신호 하나라도 있으면 판별기행.** 티스토리·네이버 블로그 등
 * 정상 플랫폼의 "무료 다시보기 사이트 순위 TOP10" 류 글이 실제로 흔함 — 도메인만으로
 * 초록 시 그 글이 안내하는 불법 사이트로 어르신 직행.
 */
object SerpRules {

    /**
     * @return 규칙만으로 결론 시 판정, 판별기 필요 시 null
     */
    fun resolve(signals: List<Signal>): SerpVerdict? {
        val floor = RiskAggregator.hardFloor(signals)
        if (floor >= RiskGrade.HIGH.minScore) {
            return RiskAggregator.ruleVerdict(signals)
        }

        val trusted = signals.any { it.category == RiskCategory.TRUSTED_KNOWN_BRAND }
        val anyRisk = signals.any { it.weight > 0 }
        if (trusted && !anyRisk) {
            return SerpVerdict.of(
                category = RiskCategory.TRUSTED_KNOWN_BRAND,
                score = 0,
                reason = RiskAggregator.reason(signals),
                source = SerpVerdict.SOURCE_RULE
            )
        }

        return null
    }
}
