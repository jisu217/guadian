package com.flyai.adalert.serp

/**
 * 검색 결과 위험도 판정의 흐름 제어. **AI 호출 트리거 전부가 여기 집중.**
 *
 * 안드로이드 의존 없음 — 흐름 전체를 단위 테스트로 커버 가능. 노드에서 결과 추출은
 * [SerpScanner], 배지 그리기는 [SerpBadgeOverlay] 담당.
 *
 * ## 판별기(AI) 호출 조건 — 네 관문 전부 통과
 *
 * ```
 * ① 화면 관문   크롬이고 주소가 검색 결과 페이지인가          (SerpScanner.isSearchScreen)
 *              → 아니면 스캔조차 없음. 비용 0
 *
 * ② 유휴 관문   화면 변경이 멈추고 IDLE_MS 지났는가            (SerpTracker)
 *              → 스크롤 중에는 위치만 추적, 판별기 호출 없음
 *
 * ③ 변경 관문   이번 화면의 결과 구성이 직전과 다른가          (signatureOf)
 *              → 같은 화면이면 캐시된 판정 그대로 사용
 *
 * ④ 미지 관문   규칙으로도 캐시로도 결론이 안 난 결과가 있는가  (SerpRules / cache)
 *              → **여기 남은 것만** 배치 1회로 판별
 * ```
 *
 * 실제로 ④까지 내려오는 것은 소수. 공식 OTT·방송사·포털은 규칙에서 '안전'으로 종결,
 * 누누티비류는 이름만으로 '위험' 확정. 판별기가 필요한 것은 **처음 보는 도메인**뿐이고,
 * 그것도 재방문 시 캐시 적중.
 *
 * ## 판정은 호스트 단위 — 화면과 무관
 * 이 클래스의 보유 상태는 "호스트 → 판정"이 전부. 좌표·화면 세대 미인지. 그래서
 * 판별기 응답이 늦어도 폐기 이유 없음 — [known] 조회 시 그 시점의 화면에 저절로 적용.
 * 좌표 부착은 [SerpTracker] 담당.
 *
 * ## 사용자 버튼이 아닌 자동 판정인 이유
 * 광고 판별(Layer 2)은 [광고 찾기] 버튼을 눌러야 동작. 검색 결과는 불가 — 위험도는
 * **누르기 전에** 보여야 의미가 있고, 검색마다 버튼을 한 번 더 누르게 하면 어르신은
 * 그냥 안 누름. 대신 나가는 정보 축소: 화면 그림·페이지 본문 아님, **검색 결과에
 * 이미 공개된 제목·도메인**만 전송.
 *
 * @param classifier   판별기. AI 끄면 [RuleOnlyClassifier]
 * @param limiter      호출 상한. 시간당 [DEFAULT_CALLS_PER_HOUR]회
 * @param ttlMillis    캐시 유효기간. 도메인 평판은 가변 — 정상이던 곳이 팔려 악성으로
 *                     바뀌는 일이 실제로 흔함
 */
class SerpRiskEngine(
    private val classifier: SerpClassifier,
    /** 규칙 신호. 목록 교체 시 주입 지점 ([KnownSites] 참고) */
    private val signals: UrlSignals = UrlSignals.DEFAULT,
    private val limiter: SerpCallBudget = SerpCallBudget(capacity = DEFAULT_CALLS_PER_HOUR),
    private val maxEntries: Int = 300,
    private val ttlMillis: Long = 7L * 24 * 60 * 60 * 1000,
    private val now: () -> Long = System::currentTimeMillis
) {

    companion object {
        /**
         * 시간당 배치 호출 상한. 배치 1회 = 화면 하나 → 곧 "시간당 검색 화면 수".
         * 어르신 사용 패턴에서 시간당 30번 검색은 없음 — 폭주 방지용 뚜껑
         */
        const val DEFAULT_CALLS_PER_HOUR = 30

        /** 한 배치의 최대 항목 수. 화면에 보이는 결과 보통 6~8개 */
        const val MAX_BATCH = 8
    }

    private class Entry(val verdict: SerpVerdict, val storedAt: Long)

    /** accessOrder=true인 LinkedHashMap = LRU */
    private val cache = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean =
            size > maxEntries
    }

    /** 직전에 판정한 화면의 지문. ③ 변경 관문의 비교 기준 */
    private var lastSignature: String? = null

    /**
     * @param results  판정 대상 결과들
     * @param verdicts 결과와 같은 순서의 판정
     * @param classifiedHosts 이번에 실제로 판별기를 부른 호스트. 로그·검증용
     */
    data class Outcome(
        val results: List<SerpResult>,
        val verdicts: List<SerpVerdict>,
        val classifiedHosts: List<String> = emptyList(),
        val skippedUnchanged: Boolean = false
    )

    /**
     * 화면 하나 판정.
     *
     * @param force ③ 변경 관문 생략. 사용자가 직접 재확인을 요청했을 때만 사용
     */
    suspend fun evaluate(
        query: String,
        results: List<SerpResult>,
        force: Boolean = false
    ): Outcome {
        if (results.isEmpty()) {
            lastSignature = null
            return Outcome(emptyList(), emptyList())
        }

        // ③ 변경 관문 — 같은 화면이면 판별기는 물론 규칙도 재실행 없음
        val signature = signatureOf(results)
        if (!force && signature == lastSignature) {
            val cached = results.map { cachedOf(it.host) }
            // 캐시 만료로 구멍이 생겼으면 지문 무효화 → 다음 기회에 재판정
            if (cached.all { it != null }) {
                return Outcome(results, cached.filterNotNull(), skippedUnchanged = true)
            }
            lastSignature = null
        }

        // ④ 미지 관문 — 규칙·캐시로 결론 나는 것부터 제거
        val ruleSignals = results.map { signals.of(it) }
        val resolved = arrayOfNulls<SerpVerdict>(results.size)
        val pending = mutableListOf<Int>()

        for (i in results.indices) {
            val host = results[i].host
            val byRule = SerpRules.resolve(ruleSignals[i])
            if (byRule != null) {
                resolved[i] = byRule
                // 규칙 판정도 캐시에 저장 — ③ 관문의 빠른 경로가 화면 전체를 캐시로
                // 채울 수 있어야 하기 때문. 규칙은 결정적이고 캐시보다 먼저 보므로
                // 저장된 값이 판정을 바꾸는 일 없음
                store(host, byRule)
                continue
            }
            val hit = cachedOf(host)
            if (hit != null) {
                resolved[i] = hit
                continue
            }
            pending += i
        }

        // 같은 호스트가 결과 목록에 여러 번 등장하는 일 흔함(같은 사이트의 다른 페이지).
        // 판별은 호스트 단위이므로 한 번만 전송
        val batch = LinkedHashMap<String, SerpClassifier.Request>()
        for (i in pending) {
            val r = results[i]
            if (batch.size >= MAX_BATCH) break
            batch.getOrPut(r.host) {
                SerpClassifier.Request(r.host, r.title, r.snippet, ruleSignals[i])
            }
        }

        var classified = emptyMap<String, SerpVerdict>()
        if (batch.isNotEmpty() && limiter.tryAcquire()) {
            classified = runCatching { classifier.classify(query, batch.values.toList()) }
                .getOrDefault(emptyMap())
        }

        for (i in pending) {
            val host = results[i].host
            val fromAi = classified[host]
            val verdict = RiskAggregator.combine(ruleSignals[i], fromAi)
            resolved[i] = verdict
            // 판별 실패(null)면 저장 없음. 규칙 판정을 캐시에 남기면 TTL 동안
            // 판별기가 그 도메인을 볼 기회 소멸
            if (fromAi != null) store(host, verdict)
        }

        lastSignature = signature
        return Outcome(
            results = results,
            verdicts = resolved.map { it ?: RiskAggregator.ruleVerdict(emptyList()) },
            classifiedHosts = classified.keys.toList()
        )
    }

    /**
     * 화면 지문. **호스트 구성만 기준** — 좌표는 스크롤로 계속 바뀌지만 그때마다
     * 재판정할 이유 없음, 제목은 같은 결과여도 접힘·펼침으로 변동
     */
    fun signatureOf(results: List<SerpResult>): String =
        results.map { it.host }.sorted().joinToString("|")

    /** 화면 완전 전환(다른 페이지로 이동). 다음 스캔을 새 화면으로 취급 */
    fun reset() {
        lastSignature = null
    }

    /**
     * 이 호스트에 대해 **지금 아는 것**. 모르면 null.
     *
     * [SerpTracker]가 배지를 그릴 때마다 호출. 판정이 호스트에 붙어 화면과 무관하다는
     * 사실이 이 함수 하나로 드러남 — 좌표도 화면 세대도 묻지 않음. 그래서 스크롤
     * 도중에도, 판별기 응답이 5초 뒤에 와도 같은 답
     */
    fun known(host: String): SerpVerdict? = cachedOf(host)

    private fun cachedOf(host: String): SerpVerdict? = synchronized(cache) {
        val entry = cache[host] ?: return@synchronized null
        if (now() - entry.storedAt >= ttlMillis) {
            cache.remove(host)
            return@synchronized null
        }
        entry.verdict.copy(source = SerpVerdict.SOURCE_CACHE)
    }

    private fun store(host: String, verdict: SerpVerdict) = synchronized(cache) {
        cache[host] = Entry(verdict, now())
        Unit
    }

    /** 테스트·진단용 */
    fun cacheSize(): Int = synchronized(cache) { cache.size }
}
