package com.flyai.adalert.serp

/**
 * 이름을 아는 사이트 목록. **규칙의 데이터 부분을 코드에서 분리한 자리.**
 *
 * ## 인터페이스인 이유 — 목록은 절대 완결 불가
 * 불법 사이트는 차단 후 숫자만 바꿔 부활(`tvhot2` → `tvhot3`,
 * `noonoo` → `nooo16` → `noonootvk2`). 실기기 확인: 목록에 있던 `tvhot2.com` 옆에
 * 목록에 없던 `nooo16.tv` 나란히 표시. 앱 내장 목록으로 추적은 **원리적으로 불가능.**
 *
 * 따라서 목록 의존 없는 설계.
 *
 *  - 목록에 있음 → 무료·즉시·네트워크 없이 판정 (빠른 길)
 *  - 목록에 없음 → 판별기 판정 (**기본 경로**)
 *  - 판별기도 불가 → [RiskGrade.UNKNOWN]. 표시 없음.
 *    모르는 것을 '안전'으로 말하지 않음 — 이 구조의 핵심
 *
 * 목록이 클수록 호출 감소일 뿐, 목록이 작아도 기능 정상.
 *
 * ## 교체 지점
 * 현재 [BuiltInKnownSites]가 앱 내장 씨앗 제공. 추후 방송통신심의위원회 차단 목록·KISA
 * 피드 수신 시 **이 인터페이스 구현 하나만 교체** — [UrlSignals]·[SerpRules] 수정 없음.
 */
interface KnownSites {

    /** 널리 알려진 사업자·공식 서비스의 등록 도메인 ("tving.com") */
    val trustedRoots: Set<String>

    /** 접미사만으로 신뢰하는 공공 영역 ("go.kr"). 기관 수 과다로 개별 등재 불가 */
    val publicSuffixes: Set<String>

    /** 사칭에 쓰이는 유명 이름. **토큰 정확 일치 시에만** 검사 */
    val impersonatedBrands: Set<String>

    /** 불법 스트리밍·웹툰·토렌트 사이트 도메인의 흔한 조각 */
    val piracyHostTerms: Set<String>

    /** 도박 사이트 이름. 짧아서 토큰 단위 검사만 */
    val gamblingHostTerms: Set<String>

    /** 성인 사이트 이름. 역시 토큰 단위 */
    val adultHostTerms: Set<String>

    /** 최종 목적지를 감추는 단축 주소 서비스 */
    val shorteners: Set<String>

    /**
     * 목록 두 개 병합. 원격 피드 수신 시 **씨앗 유지 + 얹기** 용도.
     * 피드가 비거나 다운로드 실패해도 씨앗만큼은 동작 유지.
     */
    operator fun plus(other: KnownSites): KnownSites = object : KnownSites {
        override val trustedRoots = this@KnownSites.trustedRoots + other.trustedRoots
        override val publicSuffixes = this@KnownSites.publicSuffixes + other.publicSuffixes
        override val impersonatedBrands =
            this@KnownSites.impersonatedBrands + other.impersonatedBrands
        override val piracyHostTerms = this@KnownSites.piracyHostTerms + other.piracyHostTerms
        override val gamblingHostTerms =
            this@KnownSites.gamblingHostTerms + other.gamblingHostTerms
        override val adultHostTerms = this@KnownSites.adultHostTerms + other.adultHostTerms
        override val shorteners = this@KnownSites.shorteners + other.shorteners
    }
}

/**
 * 앱 내장 씨앗 목록. **시연·개발용 소량 표본, 방어선 아님.**
 *
 * 목록 밖 검출은 판별기 몫. 목록 확장으로 성능 향상 시도 금지 — 늘려도 다음 주에
 * 이름 변경. 목록의 역할: **가장 흔한 것들을 무료로 걸러 판별기 호출 절약.**
 */
object BuiltInKnownSites : KnownSites {

    /**
     * 검색 결과에서 특히 중요한 목록. "드라마 다시보기" 검색 시 공식 OTT·불법 사이트가
     * 같은 목록에 나란히 표시 — **공식 쪽 초록이 불법 쪽 빨강만큼 중요.**
     * 눌러도 되는 것이 안 보이면 어르신은 결국 맨 위 탭.
     */
    override val trustedRoots = setOf(
        // 포털·플랫폼
        "naver.com", "daum.net", "kakao.com", "google.com", "google.co.kr", "youtube.com",
        "namu.wiki", "wikipedia.org", "tistory.com", "brunch.co.kr",
        // 공식 OTT·방송 (이 기능의 핵심 시나리오)
        "netflix.com", "tving.com", "wavve.com", "watcha.com", "coupangplay.com",
        "disneyplus.com", "primevideo.com", "seezn.com", "kocowa.com", "laftel.net",
        "kbs.co.kr", "imbc.com", "sbs.co.kr", "jtbc.co.kr", "ebs.co.kr", "tvn.co.kr",
        // 언론
        "yna.co.kr", "chosun.com", "joongang.co.kr", "donga.com", "hani.co.kr",
        "khan.co.kr", "ytn.co.kr", "hankyung.com", "mk.co.kr", "news1.kr", "newsis.com",
        // 유통·금융·통신
        "coupang.com", "11st.co.kr", "gmarket.co.kr", "auction.co.kr", "ssg.com",
        "lotteon.com", "musinsa.com", "oliveyoung.co.kr", "ohou.se",
        "samsung.com", "lge.co.kr", "apple.com", "microsoft.com",
        "sktelecom.com", "kt.com", "uplus.co.kr",
        "toss.im", "kbstar.com", "shinhan.com", "wooribank.com", "kebhana.com",
        "nonghyup.com", "ibk.co.kr", "kakaobank.com"
    )

    override val publicSuffixes = setOf("go.kr", "or.kr", "ac.kr")

    /**
     * 짧고 흔한 이름(sk·kt·lg·gov) 제외. 부분 일치 시 정상 도메인 대량 오검출 —
     * 정상에 붙는 경고 하나가 불법 누락보다 해로움.
     */
    override val impersonatedBrands = setOf(
        "naver", "kakao", "kakaobank", "daum", "toss", "kbstar", "shinhan", "woori",
        "nonghyup", "samsung", "coupang", "google", "youtube", "apple", "netflix",
        "disney", "tving", "wavve", "watcha", "paypal", "hometax", "epost", "kbank"
    )

    override val piracyHostTerms = setOf(
        "nunu", "noonoo", "tvwiki", "tvmon", "tvhot", "tvzone", "tvnori", "linkkf",
        "kissasian", "newtoki", "manatoki", "booktoki", "toonkor", "torrent", "openload",
        "streamtape", "dramacool", "9anime", "yadong", "avsee", "sharebox", "dasibogi",
        "movieuf", "kmovie", "hdtv", "freetv"
    )

    override val gamblingHostTerms = setOf(
        "bet", "bets", "betting", "toto", "casino", "slot", "slots", "baccarat",
        "poker", "gamble", "gambling", "sportstoto", "powerball"
    )

    override val adultHostTerms = setOf("av", "avsee", "yadong", "sex", "porn", "adult", "19")

    override val shorteners = setOf(
        "bit.ly", "tinyurl.com", "goo.gl", "t.co", "is.gd", "buly.kr", "me2.do",
        "han.gl", "vo.la", "abit.ly", "url.kr", "muz.so"
    )
}
