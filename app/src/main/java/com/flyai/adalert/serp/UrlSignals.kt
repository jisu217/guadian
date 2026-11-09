package com.flyai.adalert.serp

/**
 * 위험도 판단 세 축. 결과 한 칸을 서로 다른 눈으로 각각 검사.
 *
 * 한 축만 강해도 위험 가능. "tvhot2.com": 도메인 신뢰도·저작권 축 동시 검출,
 * "kakao-event.top": 피싱 축만 검출 — 그것만으로 충분히 위험.
 */
enum class RiskAxis(val label: String) {
    DOMAIN_TRUST("도메인 신뢰도"),
    ILLEGAL_CONTENT("저작권·불법성"),
    PHISHING_DECEPTION("피싱·속임수")
}

/**
 * 축 하나가 찾아낸 근거 한 줄.
 *
 * @param weight   양수 = 위험, 음수 = 신뢰. 0~100 눈금
 * @param reason   어르신용 문장. 배지에 그대로 표시
 * @param hard     단독 확정 신호 여부. **판별기가 "안전" 응답해도
 *                 이 신호들이 만든 점수 아래로 하향 없음**
 *                 ([RiskAggregator.hardFloor])
 * @param category 이 신호가 가리키는 성격
 */
data class Signal(
    val axis: RiskAxis,
    val weight: Int,
    val reason: String,
    val hard: Boolean = false,
    val category: RiskCategory = RiskCategory.UNKNOWN
)

/**
 * 검색 결과 한 칸의 위험 신호 추출. **네트워크 미사용 순수 함수.**
 *
 * senioradguard V3 `url/UrlSignals`의 검색 결과용 이식. 원본: 광고 클릭 URL(경로·쿼리 포함),
 * 여기: 검색 결과(호스트 + 제목 + 설명). 축 넷→셋 축소 — 광고 트래킹 축은 검색 결과에 부재.
 *
 * 신호의 용도 둘.
 *  1. **판별기 호출 여부 기준.** 확정([Signal.hard]) 시 AI 호출 없음.
 *     누누티비류는 이름만으로 검출 — 네트워크 없이 즉시 빨강
 *  2. **판별기에 함께 넘기는 증거.** 도메인만 전달 시 모델이 "잘 모르겠다"로 수렴
 *
 * 사이트 이름 목록은 [KnownSites]로 분리 — 목록은 완결 불가(불법 사이트는 차단 후 숫자만
 * 바꿔 부활), 추후 원격 피드로 교체 예정. 목록 밖은 판별기 몫, 여기서는
 * **이름만 봐도 이상한 것**을 무료로 필터. 남은 상수는 목록이 아닌 **형태 판정** —
 * 위험한 TLD, 정식 서비스가 안 쓰는 표현, 재촉 문구 등 개별 사이트 무관 항목.
 */
class UrlSignals(private val sites: KnownSites = BuiltInKnownSites) {

    companion object {
        /** 기본 목록 공용 인스턴스. 목록 교체 시에만 직접 생성 */
        val DEFAULT = UrlSignals()
    }

    // ── 도메인 신뢰도 ──────────────────────────────────────────

    /**
     * 무료·익명 등록이 쉬워 악용 비중 높은 최상위 도메인.
     * 포함 = 즉시 위험 아님 — 가중치 낮은 이유.
     */
    private val RISKY_TLDS = setOf(
        "xyz", "top", "cc", "tk", "ml", "ga", "cf", "gq", "buzz", "click", "link",
        "work", "rest", "icu", "pw", "su", "loan", "men", "date", "racing", "fit",
        "lol", "quest", "cyou", "bond", "shop", "store", "online", "site", "live"
    )

    // ── 저작권·불법성 ──────────────────────────────────────────

    /** 제목·설명의 불법 유통 표현. 정식 서비스 미사용 문구 */
    private val PIRACY_TEXT_TERMS = setOf(
        "전편무료", "전편 무료", "무료 다시보기", "무료다시보기", "무료보기", "무료 보기",
        "무료다운", "무료 시청", "무료시청", "토렌트", "링크모음", "링크 모음", "누누티비",
        "무료영화", "웹툰 무료", "회원가입 없이", "가입없이", "실시간 무료"
    )

    private val GAMBLING_TEXT_TERMS = setOf(
        "토토", "카지노", "바카라", "슬롯", "먹튀", "첫충", "꽁머니", "배팅",
        "사설", "안전 놀이터", "안전놀이터"
    )

    private val ADULT_TEXT_TERMS = setOf("성인 영상", "성인영상", "야동", "19금", "성인 무료")

    // ── 피싱·속임수 ────────────────────────────────────────────

    /** 다급함·이득으로 판단을 흐리는 문구. 어르신 대상 사기의 공통 문법 */
    private val BAIT_TERMS = setOf(
        "당첨", "경품", "축하합니다", "무료 지급", "지금 확인", "긴급", "본인인증",
        "계정 정지", "이용 제한", "확인 요망", "미수령", "환급", "무료 계정", "계정 공유"
    )

    /** APK **다운로드 유도** 표현. 단순 "apk" 낱말 등장과 구분 */
    private val APK_MARKERS = setOf(
        ".apk", "apk 다운", "apk다운", "apk 설치", "apk설치", "apk 파일", "apk 배포"
    )

    /**
     * 결과 한 칸의 전 축 신호 추출.
     */
    fun of(result: SerpResult): List<Signal> {
        val parts = result.parts
        if (parts.host.isEmpty()) return emptyList()

        val text = "${result.title} ${result.snippet}".lowercase()
        val trusted = isTrusted(parts)
        return domainTrust(parts) + illegalContent(parts, text) + phishing(parts, text, trusted)
    }

    private fun isTrusted(p: UrlParts): Boolean =
        p.root in sites.trustedRoots || p.tld in sites.publicSuffixes

    private fun domainTrust(p: UrlParts): List<Signal> {
        val out = mutableListOf<Signal>()

        if (isTrusted(p)) {
            // **이 축만** 여기서 종료. 저작권·피싱 축은 계속 — 티스토리·네이버 블로그 등
            // 정상 플랫폼의 불법 사이트 안내 글은 도메인이 멀쩡해도 본문 검출 필요
            out += Signal(
                RiskAxis.DOMAIN_TRUST, -45,
                "잘 알려진 곳입니다(${p.root})",
                category = RiskCategory.TRUSTED_KNOWN_BRAND
            )
            return out
        }

        if (p.isIpAddress) {
            out += Signal(
                RiskAxis.DOMAIN_TRUST, 45,
                "주소 대신 숫자로 연결되는 곳입니다",
                hard = true, category = RiskCategory.PHISHING_OR_SCAM
            )
        }
        if (p.tld in RISKY_TLDS) {
            out += Signal(RiskAxis.DOMAIN_TRUST, 25, "위험한 사이트가 자주 쓰는 주소(.${p.tld})입니다")
        }
        if (p.host.contains("xn--")) {
            out += Signal(
                RiskAxis.DOMAIN_TRUST, 30,
                "다른 글자로 흉내 낼 수 있는 주소입니다",
                category = RiskCategory.PHISHING_OR_SCAM
            )
        }

        val name = p.root.substringBefore('.')
        if (name.count { it == '-' } >= 3 || name.length >= 25) {
            out += Signal(RiskAxis.DOMAIN_TRUST, 15, "기계가 만든 듯한 긴 주소입니다")
        }
        // "tvhot2", "tvmon77" — 차단 회피용 숫자 교체 부활 사이트의 흔한 형태
        if (name.length >= 4 && name.takeLast(2).all { it.isDigit() }) {
            out += Signal(RiskAxis.DOMAIN_TRUST, 20, "이름 끝에 숫자가 붙은 주소입니다")
        }
        return out
    }

    private fun illegalContent(p: UrlParts, text: String): List<Signal> {
        val out = mutableListOf<Signal>()
        val host = p.host

        sites.piracyHostTerms.firstOrNull { it in host }?.let {
            out += Signal(
                RiskAxis.ILLEGAL_CONTENT, 70,
                "불법 다시보기 사이트가 쓰는 이름입니다",
                hard = true, category = RiskCategory.ILLEGAL_STREAMING_OR_COPYRIGHT
            )
        }
        PIRACY_TEXT_TERMS.firstOrNull { it in text }?.let {
            out += Signal(
                RiskAxis.ILLEGAL_CONTENT, 45,
                "'$it' — 정식 서비스가 쓰지 않는 표현입니다",
                category = RiskCategory.ILLEGAL_STREAMING_OR_COPYRIGHT
            )
        }

        // 도박·성인 용어는 짧아 부분 일치 시 "betterlife", "average"까지 검출.
        // 호스트를 토큰으로 분리해 정확 일치만 인정
        val tokens = labelTokens(host)
        tokens.firstOrNull { it in sites.gamblingHostTerms }?.let {
            out += Signal(
                RiskAxis.ILLEGAL_CONTENT, 75,
                "불법 도박 사이트가 쓰는 이름입니다",
                hard = true, category = RiskCategory.ILLEGAL_GAMBLING
            )
        }
        GAMBLING_TEXT_TERMS.firstOrNull { it in text }?.let {
            out += Signal(
                RiskAxis.ILLEGAL_CONTENT, 55,
                "도박을 권유하는 내용이 있습니다",
                hard = true, category = RiskCategory.ILLEGAL_GAMBLING
            )
        }
        tokens.firstOrNull { it in sites.adultHostTerms }?.let {
            out += Signal(
                RiskAxis.ILLEGAL_CONTENT, 70,
                "성인 사이트로 보입니다",
                hard = true, category = RiskCategory.ADULT_CONTENT
            )
        }
        ADULT_TEXT_TERMS.firstOrNull { it in text }?.let {
            out += Signal(
                RiskAxis.ILLEGAL_CONTENT, 65,
                "성인 내용이 포함돼 있습니다",
                hard = true, category = RiskCategory.ADULT_CONTENT
            )
        }
        return out
    }

    private fun phishing(p: UrlParts, text: String, trusted: Boolean): List<Signal> {
        val out = mutableListOf<Signal>()

        if (p.root in sites.shorteners) {
            out += Signal(
                RiskAxis.PHISHING_DECEPTION, 40,
                "어디로 가는지 알 수 없는 짧은 주소입니다",
                category = RiskCategory.UNVERIFIED_THIRD_PARTY
            )
        }

        // 사칭 — 유명 이름이 등록 도메인의 **주인이 아닌 자리**에 위치.
        // "netflix-free.xyz"의 실제 주인: netflix 아닌 netflix-free.xyz,
        // "naver.secure-login.top"의 실제 주인: secure-login.top.
        //
        // 이중 방어. 이 검사는 도메인 신뢰도 축과 **독립** 실행(티스토리 글의 위험 문구
        // 검출 목적) — 필터 없으면 netflix.com·blog.naver.com이 자기 이름 사칭으로 오검출.
        //  1. 신뢰 도메인은 검사 제외
        //  2. 이름 = 등록 도메인 자체면 사칭 아님 (naver.com의 "naver")
        val rootName = p.root.substringBefore('.')
        if (!trusted) {
            val suspectTokens = labelTokens(p.subdomain) + labelTokens(rootName)
            suspectTokens.firstOrNull { it in sites.impersonatedBrands && it != rootName }?.let {
                out += Signal(
                    RiskAxis.PHISHING_DECEPTION, 55,
                    "'$it' 이름을 흉내 냈지만 실제 주소는 ${p.root}입니다",
                    hard = true, category = RiskCategory.PHISHING_OR_SCAM
                )
            }
        }

        if (p.subdomain.count { it == '.' } >= 2) {
            out += Signal(RiskAxis.PHISHING_DECEPTION, 15, "주소 앞단이 불필요하게 깁니다")
        }

        // "apk"만으로 검출 시 APK 설명 정상 기사까지 오검출 — 다운로드 **유도** 표현만 인정
        if (APK_MARKERS.any { it in text } || "apk" in labelTokens(p.host)) {
            out += Signal(
                RiskAxis.PHISHING_DECEPTION, 60,
                "앱 설치 파일을 직접 내려받게 하는 곳입니다",
                hard = true, category = RiskCategory.MALWARE_OR_UNWANTED_APP
            )
        }

        BAIT_TERMS.firstOrNull { it in text }?.let {
            out += Signal(
                RiskAxis.PHISHING_DECEPTION, 30,
                "'$it' — 판단을 재촉하는 문구입니다",
                category = RiskCategory.PHISHING_OR_SCAM
            )
        }
        return out
    }

    /** "tv-hot2.com" → [tv, hot]. 숫자·하이픈·점으로 분리, 글자 토큰만 유지 */
    private fun labelTokens(value: String): List<String> =
        value.lowercase().split(Regex("""[^a-z]+""")).filter { it.isNotEmpty() }
}
