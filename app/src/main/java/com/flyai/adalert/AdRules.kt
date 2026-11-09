package com.flyai.adalert

/**
 * 광고 식별 근거 한곳 집약. 로직이 아닌 데이터로 두는 이유: 광고망 변경 시
 * 감지 코드 수정 없이 이 파일만 수정.
 *
 * 등급의 의미 — 이 앱은 **미탐보다 오탐이 훨씬 치명적**.
 * 광고 누락은 아쉬운 정도, 진짜 기사에 "광고" 테두리는 어르신의 앱 신뢰 상실 →
 * 이후 맞는 표시도 무의미.
 *
 *  - [Grade.L1] 화면에 테두리 표시. **광고사가 스스로 만드는 표시**라
 *               퍼블리셔·사이트 무관하게 신뢰 가능한 것만 해당.
 *  - [Grade.L2] 표시 없이 로그만(shadow). 실기기 오탐 0 확인 후에만 L1 승격.
 */
enum class Grade { L1, L2 }

object AdRules {

    // ── 축 1. 라벨 ────────────────────────────────────────────────────────────
    /**
     * 화면에 이미 적힌 공식 광고 표기. 부분 일치가 아닌 **토큰 완전 일치**만 인정
     * ("광고 문의", "광고성 정보 수신" 같은 문구의 광고 오인 방지).
     *
     * 영어 항목 중요 — 구글 애드매니저는 광고 iframe title에 "3rd party ad content",
     * 애드센스는 "advertisement" 삽입. 국내 매체는 이미지 배너에 한글 라벨 미부착이 많아
     * 이 영어 title이 유일한 라벨인 경우 실재.
     */
    val labelTokens = setOf(
        "광고", "스폰서", "협찬 광고", "이웃광고", "프로모션", "유료광고", "유료 광고", "광고입니다",
        "ad", "ads", "advertisement", "sponsored", "promoted", "promotion",
        "3rd party ad content"
    )

    /**
     * 라벨 토큰 분리 구분자. 광고 카드 전체가 한 노드로 합쳐져
     * "제목 · 광고 · 더보기"처럼 붙어 오는 경우(유튜브 Litho 등) 분리용.
     * `-`는 양옆 공백이 있을 때만 구분자 — non-sponsored 같은 단어 분리 방지
     */
    val labelSeparators = Regex("\\s-\\s|\\s[|/ㅣ]\\s|[·,，•∙‧()\\[\\]\\n\\r\\t]")

    /** 광고 문구 사이에 끼워 문자열 검사를 회피하는 폭 0 문자들 */
    val zeroWidth = Regex("[\\u200b-\\u200d\\ufeff\\u2060]")

    // ── 축 2. HTML id ────────────────────────────────────────────────────────
    /**
     * 크롬 계열: HTML `id` 속성을 노드의 viewIdResourceName으로 그대로 노출.
     * (서비스 설정의 flagReportViewIds 필요. HTML `class`는 어떤 경로로도 미전달.)
     *
     * **표적은 퍼블리셔의 래퍼 div가 아니라 광고사가 만드는 iframe.**
     * 텍스트도 이름도 없는 빈 래퍼 div는 접근성 트리에서 잘려 id째 소실,
     * 광고사 iframe은 title 속성 보유로 생존.
     *
     * 실측(2026-08-11, m.news.nate.com, 크롬 150): google_ads_iframe_* 확인,
     * adsbygoogle·div-gpt-ad 미출현.
     */
    val containerIds: List<Pair<String, Grade>> = listOf(
        // 광고사 생성 — 퍼블리셔·사이트 무관. 신뢰도 최상
        "google_ads" to Grade.L1,        // google_ads_iframe_/네트워크/슬롯  (GPT가 주입)
        "aswift_" to Grade.L1,           // 애드센스 iframe
        "div-gpt-ad" to Grade.L1,        // GPT 표준 슬롯 명명 규약
        "adfit" to Grade.L1,             // 카카오 애드핏
        "criteo" to Grade.L1,
        // 추천 위젯 컨테이너 추가 금지 — 이유는 [mixedHosts].
        // taboola·outbrain은 같은 종류라 같은 오탐 가능성 높음, 기기 확인은 아직 없음.
        // 확인되면 데이블처럼 제외.
        "taboola" to Grade.L1,
        "outbrain" to Grade.L1,
        "mgcontainer" to Grade.L1,       // MediaGo 네이티브 광고
        "mobondivbanner" to Grade.L1,    // 모비온
        "innorame" to Grade.L1,
        "aceplanet" to Grade.L1,
        "widerplanet" to Grade.L1,
        "ad4989" to Grade.L1,
        "admaru" to Grade.L1,
        "clickads" to Grade.L1,

        // 퍼블리셔가 자유롭게 짓는 이름. 이름만으로는 확증 불가 → 기본 등급 L2.
        // 다만 **그 노드가 iframe이면 L1 승격** — 남의 문서를 통째로 끼워 넣는 자리에
        // 광고라는 이름이면 광고 확실. (AdScanner에서 승격)
        // 실측: 네이트의 ad_big(1080x568)·ifr_ad_shopbox·ifr_ad_bottom 모두 role=iframe
        "gpt-passback" to Grade.L2,
        "ifr_ad_" to Grade.L2,
        "ad_big" to Grade.L2,
        "adwrap" to Grade.L2,
        "ad_area" to Grade.L2,
        "ad_banner" to Grade.L2,
        "advertisement" to Grade.L2

        // 폐기: "adsbygoogle" — 애드센스는 <ins class="adsbygoogle">로 삽입.
        //       HTML class는 안드로이드 접근성 API 전달 경로 없음 → 원리상 매칭 불가
    )

    // ── 축 3. 링크·이미지가 가리키는 광고망 도메인 ──────────────────────────────
    /**
     * 광고망은 자기 도메인 은폐 불가 — 이 축이 견고한 이유.
     * 라벨 없고 id도 퍼블리셔 고유라 못 잡는 광고를 그 안의 링크 한 개로 포착
     */
    val adHosts = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "googletagservices.com", "2mdn.net", "adservice.google.com",
        "adfit.daum.net", "adfit.kakao.com", "ad.daum.net",
        "criteo.com", "criteo.net", "taboola.com", "outbrain.com",
        "mobon.net", "aceplanet.co.kr", "innorame.com",   // dable.io는 [mixedHosts]로 이동
        "widerplanet.com", "ad4989.co.kr", "admaru.com", "mediacategory.com",
        "adpies.com", "cauly.net", "tnkfactory.com", "igaworks.com",
        // 실측(2026-08-11) 확인 후 추가
        "popin.cc",                    // popIn / MediaGo 네이티브 광고
        "ads-partners.coupang.com",    // 쿠팡 파트너스 제휴 광고
        "acrosspf.com",                // 어크로스 (경향신문 실측)
        // 글로벌 SSP·애드익스체인지. 광고 소재 안에서만 쓰이는 도메인 →
        // 일반 콘텐츠의 링크 대상 아님
        "adnxs.com", "openx.net", "pubmatic.com", "rubiconproject.com",
        "casalemedia.com", "smartadserver.com", "teads.tv", "sharethrough.com",
        "adsrvr.org", "3lift.com", "yieldmo.com"
        // coupangcdn.com 제외 — 쿠팡 자사 사이트의 상품 이미지도 같은 CDN 사용.
        // 광고 링크는 ads-partners 쪽으로 오므로 그것으로 충분
    )

    /** 광고망 도메인 판정. 서브도메인까지 인정, 접미사 위조(evil-doubleclick.net)는 차단 */
    fun isAdHost(host: String): Boolean {
        val h = host.lowercase()
        return adHosts.any { h == it || h.endsWith(".$it") }
    }

    /**
     * 광고와 진짜 기사를 **같은 도메인으로** 내보내는 추천 위젯.
     *
     * 데이블 실측(2026-08-12, m.yonhapnewstv.co.kr): 한 위젯 안에 광고 5개 + 추천 기사 3개 혼재,
     * 광고는 ad-log.dable.io, 기사는 r-log.dable.io. 둘 다 dable.io라 도메인만으로 구별 불가
     * → **이 도메인은 광고 증거 불가.** 증거로 쓰던 동안 위젯 통째로 한 광고로 잡혀
     * 기사 3개에 테두리 (`rects=[[0,337][1080,2205]]`, 화면의 80%).
     *
     * 목록에서 단순 삭제도 불가: 페이지 주소 다수결 추정(AdScanner.notePageHost) 시
     * 이 링크들이 표본에 섞여 사이트 판정 교란. 광고 증거에서는 제외하되 표본에서도
     * 계속 제외 필요 → 별도 목록.
     *
     * 이 위젯 안의 광고는 항목마다 붙는 "AD" 라벨(축 1)로 포착 — 실측에서 광고 항목은 전부
     * 라벨 부착, 기사 항목은 전무. 둘을 가르는 유일한 신호
     */
    val mixedHosts = setOf("dable.io")

    /** 광고와 콘텐츠를 같은 도메인으로 섞어 내보내는 곳인지 여부 */
    fun isMixedHost(host: String): Boolean {
        val h = host.lowercase()
        return mixedHosts.any { h == it || h.endsWith(".$it") }
    }

    // ── 쇼핑몰 예외 ──────────────────────────────────────────────────────────
    /**
     * 광고 테두리 **미표시** 호스트.
     *
     * 쇼핑몰은 상품 목록 자체가 판매 대상 → "광고" 구분 표시 무의미.
     * 물건을 보러 정상 진입한 화면에 테두리 다수 표시는 방해만 초래.
     *
     * **"뒤로 가기" 안내는 그대로 동작.** 광고가 사용자를 쇼핑몰로 끌고 간 경우
     * 탈출 경로 필수 — 감지가 아니라 [NavigationGuard] 담당.
     *
     * 콘텐츠가 본체인 사이트 추가 절대 금지. 특히 포털 최상위 도메인(naver.com 등) 추가 시
     * 뉴스에서도 광고 표시 꺼짐. 쇼핑 전용 도메인만.
     */
    val shoppingHosts = setOf(
        // 오픈마켓·종합몰
        "coupang.com", "11st.co.kr", "011st.com", "gmarket.co.kr", "auction.co.kr",
        "ssg.com", "lotteon.com", "homeplus.co.kr",

        // TV홈쇼핑·T커머스 — 어르신 유입 경로의 핵심
        "gsshop.com", "cjonstyle.com", "hmall.com", "hyundaihmall.com",
        "lotteimall.com", "nsmall.com", "hnsmall.com", "skstoa.com",

        // 장보기·건강·가전·백화점·가구
        "kurly.com", "nonghyupmall.com", "oliveyoung.co.kr", "e-himart.co.kr",
        "thehyundai.com", "hanssem.com", "hyundailivart.co.kr", "ohou.se",

        // 해외직구
        "aliexpress.com", "temu.com", "shein.com", "iherb.com",
        "amazon.com", "ebay.com", "taobao.com",

        // 중고거래·리셀·중고차
        "daangn.com", "bunjang.co.kr", "joongna.com", "hellomarket.com",
        "encar.com", "kcar.com", "kbchachacha.com",
        "kream.co.kr", "soldout.co.kr", "gugus.co.kr", "feelway.com",

        // 패션·뷰티 전문몰 (queenit은 4050 여성 대상 → 이 앱의 사용자층과 중복)
        "queenit.kr", "musinsa.com", "29cm.co.kr",
        "zigzag.kr", "a-bly.com", "brandi.co.kr",
        "ssfshop.com", "thehandsome.com", "lfmall.co.kr", "kolonmall.com",
        "amoremall.com", "wconcept.co.kr", "trenbe.com", "mustit.co.kr",

        // 포털의 쇼핑 전용 호스트.
        // naver.com·kakao.com 자체 추가 시 뉴스·카페에서도 광고 표시 꺼짐. 절대 금지.
        "shopping.naver.com", "smartstore.naver.com", "brand.naver.com",
        "shoppinglive.naver.com", "gift.kakao.com", "makers.kakao.com"

        // 미포함 목록:
        //  - danawa.com, enuri.com (가격비교) — 직접 판매가 아니라 광고가 얹히는 구조. 표시 대상.
        //  - naver.com, daum.net, kakao.com — 콘텐츠가 본체
        //  - wemakeprice.com(2025.9 파산), tmon.co.kr, interpark.com(커머스 파산),
        //    balaan.co.kr(2026.2 파산), qoo10 국내 종료 — 서비스 없음
        //  - emart.com, lottemart.com, himart.co.kr — 매장 안내 사이트, 구매는 다른 도메인
    )

    /** 이 호스트에서는 광고 표시 억제 */
    fun isShoppingHost(host: String?): Boolean {
        if (host == null) return false
        val h = host.lowercase()
        return shoppingHosts.any { h == it || h.endsWith(".$it") }
    }

    // ── 광고를 눌러서 온 것인가 ────────────────────────────────────────────────
    /**
     * 도착 주소에 붙어 있으면 **광고 클릭 유입**으로 판정하는 표식.
     *
     * 접근성 클릭 이벤트(typeViewClicked) 판별 시도 → 실측(2026-08-15)에서 갈림 —
     * 네이트 자체 광고(`ad_big`)는 클릭 기록, **구글 광고 iframe(`google_ads`)은
     * 이벤트 전무.** 제일 흔한 광고가 신호 없음 → 그것만으로는 사용 불가.
     *
     * 반면 이 표식들은 광고망이 클릭 집계를 위해 **스스로 부착** → 누락 불가.
     * 실측 주소: `gclid=EAIaIQobChMI...`(구글), `utm_source=popin`(popIn),
     * `utm_source=affiliate&utm_campaign=brand_affiliate`(오늘의집).
     * 주소 직접 입력·탭 전환에서는 부착 이유 없음
     */
    private val adClickParams = listOf(
        "gclid", "gad_source", "gad_campaignid", "wbraid", "gbraid",  // 구글
        "msclkid",                                                    // 마이크로소프트
        "fbclid",                                                     // 메타
        "utm_source", "utm_medium", "utm_campaign",                   // 범용 캠페인 표식
        "airbridge_referrer", "referrer_id",                          // 어트리뷰션
    )

    /**
     * 매체 자체 트래커(네이트 cyad류)의 클릭 주소 여부 — 서버의 `_ad_key`가 키를 만들지
     * 않는 부류. ads_no가 광고가 아니라 광고 구좌를 가리킬 수 있어 주소로는 광고 식별 불가.
     * 이런 광고는 **소재 이미지 주소**가 식별자 (실측: 같은 소재는 노출이 바뀌어도
     * 이미지 주소 동일, 파일명에 광고주 그대로 포함)
     */
    fun isSlotTracker(url: String): Boolean =
        "cyad" in url || "/click." in url

    /** 광고 클릭 유입 주소로 보이는지 여부 */
    fun looksLikeAdClick(url: String?): Boolean {
        val q = url?.substringAfter('?', "")?.lowercase() ?: return false
        if (q.isEmpty()) return false
        return adClickParams.any { "$it=" in q }
    }

    // ── 부정 신호 ────────────────────────────────────────────────────────────
    /**
     * 감지 제외 앱. 오버레이 표시 시 방해 또는 무의미.
     * (자기 자신은 서비스 쪽에서 별도 필터)
     */
    val ignoredPackages = setOf(
        "com.android.systemui",
        "android",
        "com.samsung.android.app.aodservice",
        "com.sec.android.app.launcher",
        "com.google.android.apps.nexuslauncher"
    )

    fun isIgnoredPackage(pkg: String): Boolean =
        pkg in ignoredPackages ||
            pkg.contains("inputmethod") ||   // 키보드
            pkg.endsWith(".launcher") ||
            pkg.contains("launcher3")

    /**
     * 하던 일 위에 **잠깐** 뜨는 시스템 창. 앱 전환으로 집계 금지.
     *
     * 재난문자·화면캡처가 브라우저 위에 뜨는 순간 "웹에서 왔고 런처가 아닌 앱이 열림"
     * 조건 전부 충족 → "다른 앱이 열렸습니다" 쉴드 표시 (실기기 보고). 이 창들은
     * [isIgnoredPackage]처럼 기록 삭제도 금지 — 창이 닫히면 보던 페이지 그대로 →
     * 스캔 통째로 건너뛰고 아무것도 갱신하지 않는 것이 정답
     */
    fun isTransientOverlay(pkg: String): Boolean =
        pkg.contains("cellbroadcast") ||       // 재난문자 (구글)
            pkg.contains("cmas") ||            // 재난문자 (삼성)
            pkg.contains("smartcapture") ||    // 화면캡처
            pkg.contains("incallui") ||        // 전화 수신 화면
            pkg.contains("permissioncontroller")   // 권한 팝업

    /**
     * 네이티브 앱 화면(웹 아닌 곳)에서 라벨 신호를 볼 앱.
     *
     * 웹은 브라우저 불문 전부 감지(WebView는 어느 앱 안에 있든 감지),
     * 네이티브 UI는 앱마다 구조 제각각 → 임의 앱 스캔 시 오탐 예측 불가.
     * 예: 설정 앱의 "광고 설정" 항목에 테두리 = 고장난 앱.
     *
     * 여기 없는 앱이라도 그 안의 **웹 콘텐츠는 그대로 감지.**
     * 카카오톡 인앱 브라우저가 이 경로로 커버.
     *
     * 목록은 **실기기 검증 앱만**. 예: 카카오톡 추가 시 대화창에서 누군가 "광고"라고만
     * 보낸 메시지에 테두리 가능. 카톡에서 정말 필요한 것은 인앱 브라우저 쪽이고
     * 그건 웹 경로로 이미 커버
     */
    val nativeAdApps = setOf(
        "com.google.android.youtube",
        "com.google.android.apps.youtube.music",
        "com.instagram.android",
        "com.towneers.www"               // 당근
    )

    // ── 광고 카드가 될 수 없는 컨테이너 ────────────────────────────────────────
    /**
     * 앱이 화면에 상시 띄우는 도구 모음. **광고 한 칸이 아니므로 테두리 영역 금지.**
     *
     * 인스타그램 스토리 광고에서 실제 사고 발생. 스토리는 화면 전체가 광고인데 "광고" 라벨이
     * 하단 툴바 안(`reel_item_sponsored_label_footer_pill`) → 라벨에서 위로 올라가는
     * 카드 탐색이 툴바(`toolbar_left_right_container`)에서 정지 → **화면 맨 아래 한 줄에만**
     * 테두리. 툴바 높이는 화면의 6.75%뿐이라 원래 카드 조건 미달이나,
     * 화면 맨 아래 부착이라 "가장자리에 걸쳐 잘린 카드" 예외까지 통과.
     *
     * 이름으로 걸러 지나치면 스토리 뿌리까지 상승 → 화면 전체가 광고로 포착 (실제 동작)
     */
    private val chromeContainerWords = listOf(
        "toolbar", "action_bar", "actionbar", "tab_bar", "tabbar",
        "navigation_bar", "nav_bar", "bottom_bar", "status_bar"
    )

    fun isChromeContainer(id: String?): Boolean {
        val s = id?.lowercase() ?: return false
        return chromeContainerWords.any { it in s }
    }

    // ── 플랫폼이 이미 제공하는 컨트롤 ──────────────────────────────────────────
    val skipWords = listOf("건너뛰기", "skip ad", "skip ads", "광고 건너뛰기")
    val closeWords = listOf("btn_close", "close_btn", "close_button", "ad_close", "xbtn", "광고 닫기", "광고 숨기기")

    /**
     * 구글 광고(GPT/애드센스)가 배너 구석에 다는 닫기 버튼의 id. **부분 일치 금지** —
     * 세 글자짜리라 아무 id에나 우연 일치. 정확히 이 이름일 때만 인정.
     *
     * 실측(2026-08-13, 네이트 앵커 광고): 닫기는 `android.widget.Button id=cbb`,
     * 그 옆 `abgc`는 "이 광고가 표시된 이유"를 여는 애드초이스 버튼 → **추가 금지.**
     * 누르면 광고 닫힘이 아니라 설명 페이지 열림
     */
    private val closeIds = setOf("cbb", "dismiss-button", "close-button", "closeButton")

    fun isCloseId(id: String?): Boolean {
        val name = id?.substringAfterLast('/') ?: return false
        return name in closeIds
    }
}
