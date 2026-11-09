package com.flyai.adalert.serp

/**
 * 이 패키지의 로그 태그.
 *
 * 값은 다른 레이어와 동일 — `adb logcat -s GuADian:*` 한 줄로 전부 조회.
 * **상수 자체는 여기서 정의.** 서비스 companion import 시 그 서비스 없이 컴파일 불가 —
 * 병합·이식 시 정확히 그 지점이 걸림돌.
 */
internal const val SERP_TAG = "GuADian"

/**
 * 판별기 호출 상한 (토큰버킷).
 *
 * ## `agent.RateLimiter` 미사용·별도 보관 이유
 * 역할 동일. 분리 이유: **패키지 통째로 다른 저장소 이식 가능.** 검색 결과 위험도는
 * 다른 팀원의 광고 감지와 별개 task — 상호 참조 시작 시 병합 때 한쪽만 분리 불가.
 *
 * 30줄 중복의 대가가 "패키지 밖 의존 없음" 성질이면 남는 거래. 통합 필요 시
 * 그때 공용 모듈로 승격.
 *
 * @param capacity 버킷 최대 토큰 수 (= 기간당 호출 상한)
 * @param refillMs 버킷 가득 차는 시간
 * @param now      현재 시각. 테스트에서 시간 직접 제어용 주입
 */
class SerpCallBudget(
    private val capacity: Int = 30,
    private val refillMs: Long = 60 * 60 * 1000L,
    private val now: () -> Long = System::currentTimeMillis
) {

    private var tokens: Double = capacity.toDouble()
    private var lastRefill: Long = now()

    @Synchronized
    fun tryAcquire(): Boolean {
        refill()
        if (tokens < 1.0) return false
        tokens -= 1.0
        return true
    }

    private fun refill() {
        val current = now()
        val elapsed = current - lastRefill
        if (elapsed <= 0) return

        val perMs = capacity.toDouble() / refillMs
        tokens = (tokens + elapsed * perMs).coerceAtMost(capacity.toDouble())
        lastRefill = current
    }
}
