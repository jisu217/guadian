package com.flyai.adalert.serp

/**
 * 검색 결과 목록의 한 칸. **화면 좌표 없음.**
 *
 * ## 세 항목 모두 화면에 이미 글자로 존재
 * 검색 결과 화면은 광고 배너와 결정적 차이. 배너는 그림 한 장 — 픽셀 분석 필요.
 * **검색 결과는 도메인·제목·설명 전부 글자** — 스크린샷·비전 모델 불필요.
 *
 * 좌표 제외는 의도적. 위험도 판정 전체([UrlSignals] · [RiskAggregator] ·
 * [SerpRules] · [SerpRiskEngine])가 안드로이드 타입 미사용 →
 * **JVM 단위 테스트로 그대로 커버.** 좌표는 [SerpScanner.Hit]가 별도 보관, 배지 묘사 시 재결합.
 *
 * @param host      결과가 가리키는 호스트("tvhot2.com"). 캐시·판정의 단위
 * @param title     결과 제목
 * @param snippet   제목 아래 설명. 없을 수 있음
 */
data class SerpResult(
    val host: String,
    val title: String,
    val snippet: String
) {
    val parts: UrlParts get() = UrlParts.of(host)
}

/**
 * 호스트의 등록 도메인 단위 분해 결과. 위험도 판정 기준 단위 = 등록 도메인.
 *
 * "news.naver.com"·"blog.naver.com" = 같은 naver.com, "naver.evil.xyz" ≠ naver.com.
 * 이 구분이 사칭 탐지의 뼈대 — 문자열 포함 검사 불가, 반드시 분해 후 비교.
 *
 * @param host      전체 호스트 ("blog.naver.com")
 * @param root      등록 가능 도메인 ("naver.com")
 * @param tld       공개 접미사 ("com", "co.kr"). IP 주소면 빈 문자열
 * @param subdomain root 앞부분 ("blog"). 없으면 빈 문자열
 */
data class UrlParts(
    val host: String,
    val root: String,
    val tld: String,
    val subdomain: String
) {
    /** IP 주소 직접 연결 여부. 정상 서비스의 IP 노출은 거의 없음 */
    val isIpAddress: Boolean get() = tld.isEmpty() && host.isNotEmpty()

    companion object {
        /**
         * 2단계 공개 접미사. 미포함 시 마지막 한 조각만 TLD 처리.
         *
         * 전체 Public Suffix List(수천 줄) 미탑재 이유: 무게. 한국 사용자가 만나는
         * 결과 대부분은 아래로 커버, 누락 접미사가 있어도 등록 도메인이 한 칸 길게
         * 잡힐 뿐 판정 뒤집힘 없음.
         */
        private val TWO_LEVEL_SUFFIXES = setOf(
            "co.kr", "or.kr", "ne.kr", "go.kr", "re.kr", "pe.kr", "ac.kr", "hs.kr", "ms.kr",
            "co.uk", "org.uk", "ac.uk", "gov.uk",
            "co.jp", "ne.jp", "or.jp", "ac.jp", "go.jp",
            "com.au", "net.au", "org.au", "com.cn", "net.cn", "org.cn",
            "com.tw", "com.hk", "com.sg", "com.br", "co.in", "com.vn", "co.id"
        )

        private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

        /** "https://www.blog.naver.com/x" 처럼 껍데기가 붙어도 호스트만 추출 */
        fun of(raw: String): UrlParts {
            val host = normalizeHost(raw)
            if (host.isEmpty()) return UrlParts("", "", "", "")
            if (IPV4.matches(host)) return UrlParts(host, host, "", "")

            val labels = host.split('.')
            if (labels.size < 2) return UrlParts(host, host, "", "")

            val lastTwo = labels.takeLast(2).joinToString(".")
            val tldSize = if (labels.size >= 3 && lastTwo in TWO_LEVEL_SUFFIXES) 2 else 1
            val rootSize = tldSize + 1
            if (labels.size < rootSize) return UrlParts(host, host, lastTwo, "")

            return UrlParts(
                host = host,
                root = labels.takeLast(rootSize).joinToString("."),
                tld = labels.takeLast(tldSize).joinToString("."),
                subdomain = labels.dropLast(rootSize).joinToString(".")
            )
        }

        /**
         * 스킴·경로·포트·www 제거.
         *
         * 검색 결과 도메인 줄 표기는 제각각 — 구글: "tving.com › 다시보기"처럼
         * 경로를 › 로 연결, 네이버: "www." 포함 표시.
         */
        fun normalizeHost(raw: String): String {
            var value = raw.trim().lowercase()
            if (value.isEmpty()) return ""

            value = value.substringAfter("://")
            // 구글 모바일 경로 구분자. 이후는 호스트 아님
            value = value.substringBefore('›').substringBefore('>').trim()
            value = value.substringBefore('/').substringBefore('?').substringBefore('#')
            value = value.substringAfterLast('@')       // user:pass@host 형태
            value = value.substringBefore(':')          // 포트
            value = value.trim().trimEnd('.').removePrefix("www.")

            return if (value.any { it.isWhitespace() }) "" else value
        }
    }
}
