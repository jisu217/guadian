package com.flyai.adalert.serp

/**
 * 검색 결과 위험도 판별기. **한 화면의 결과를 한 번에 수신.**
 *
 * 결과당 호출 없음 — 이 인터페이스의 핵심. 검색 한 화면에 결과 6~10개,
 * 개별 호출 시 검색 한 번에 호출 그만큼 발생. 배치 묶음 시 **화면당 1회**,
 * 모델이 결과들을 **상호 비교** 가능 — "이 목록에서 tving.com이 정상, tvhot2.com이
 * 그 흉내"라는 판단은 개별보다 함께 볼 때 우수.
 *
 * 이 인터페이스 = 교체점. 현재 Gemini 직접 호출, 배포 시 자체 서버 경유 구현으로
 * 교체 — 파이프라인·캐시·배지 수정 없음.
 */
interface SerpClassifier {

    /** 판정 출처. [SerpVerdict.source]에 그대로 기록 — 원인 추적용 */
    val source: String

    /**
     * @param query   사용자 검색어. "다시보기" 검색 맥락의 "무료 시청"은
     *                훨씬 강한 신호 — 문맥 없으면 모델이 이 차이 식별 불가
     * @param items   판별 대상 결과들. 규칙 종결 항목은 이미 제외
     * @return 입력 순서 무관, [Request.host]로 조회 가능한 판정 맵.
     *         실패·타임아웃 시 빈 맵 — 호출부 캐시 저장 없음
     */
    suspend fun classify(query: String, items: List<Request>): Map<String, SerpVerdict>

    /**
     * 판별 요청 한 건. **화면에 보이는 것만 포함.**
     *
     * 페이지 접속·내용 수집 없음. 접속 차단 도메인이 절반, 어르신 회선으로
     * 불법 사이트 요청 자체가 위험 유발.
     */
    data class Request(
        val host: String,
        val title: String,
        val snippet: String,
        /** 규칙이 먼저 찾아낸 근거. 프롬프트 동봉 시 판단 안정성 현저히 향상 */
        val signals: List<Signal>
    )
}

/**
 * 판별기 없이 규칙만 쓰는 대역.
 *
 * **추론 아님.** 이름의 이상한 조각 유무 문자열 검사뿐 — 처음 보는 사이트의
 * 문맥 판단 불가. 목적: 키 없음·AI 꺼둔 사용자도 전 구간(추출 → 판정 → 배지) 동작,
 * 상한 도달 시 기능 정지 대신 조용한 얕아짐.
 *
 * 같은 입력 → 항상 같은 결과 — 캐시 동작 검증에 필요한 성질.
 */
object RuleOnlyClassifier : SerpClassifier {

    override val source = SerpVerdict.SOURCE_RULE

    override suspend fun classify(
        query: String,
        items: List<SerpClassifier.Request>
    ): Map<String, SerpVerdict> =
        items.associate { it.host to RiskAggregator.ruleVerdict(it.signals) }
}
