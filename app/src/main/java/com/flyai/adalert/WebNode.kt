package com.flyai.adalert

import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 브라우저가 웹 문서의 정보를 접근성 노드에 얹어 보내는 통로.
 *
 * HTML 원문 전체가 오는 것은 아님 — 오는 것과 오지 않는 것이 고정.
 *   오는 것    : id 속성(viewIdResourceName), 링크 href·이미지 src(targetUrl), AX 롤(chromeRole)
 *   오지 않는 것: class 속성, data-* 속성, iframe의 src
 *
 * 크롬 계열과 파이어폭스(GeckoView)는 키 이름만 다르고 구조 동일.
 */
object WebNode {

    const val CHROME_ROLE = "AccessibilityNodeInfo.chromeRole"
    const val GECKO_ROLE = "AccessibilityNodeInfo.geckoRole"
    const val TARGET_URL = "AccessibilityNodeInfo.targetUrl"

    /**
     * 크롬이 화면 밖으로 스크롤된 노드에 붙이는 표시.
     * 그런 노드는 좌표가 높이 0으로 접혀 오기도 하지만 항상은 아니라 표시를 직접 확인.
     * (실측: 광고 노드 대부분이 `900x0` + offscreen=true 로 수신)
     */
    const val OFFSCREEN = "AccessibilityNodeInfo.offscreen"

    /** 크롬이 웹 문서 전체를 담는 호스트 노드로 쓰는 클래스명 */
    const val WEB_HOST_CLASS = "android.webkit.WebView"

    /** targetUrl이 채워지는 롤 (브라우저가 링크·이미지·비디오에만 실어 보냄) */
    private val urlBearingRoles = setOf("link", "image", "video", "img")

    /** 광고 한 칸의 경계가 되는 롤 */
    private val frameRoles = setOf("iframe", "iframePresentational", "rootWebArea")

    /**
     * 중첩 문서의 경계. iframe 안쪽은 남의 문서.
     * (rootWebArea는 iframe마다 하나씩 또 생기므로 경계 판정에 미사용)
     */
    private val nestedDocRoles = setOf("iframe", "iframePresentational")

    /**
     * `co.kr`처럼 두 단계짜리 공용 접미사. 여기까지는 "사이트"가 아니라 등록 단위 밖.
     * (m.news.nate.com 과 m.nate.com 을 같은 사이트로 보려면 nate.com 까지 절단 필요)
     */
    private val twoLevelSuffixes = setOf(
        "co", "or", "ne", "go", "re", "pe", "ac", "hs", "ms", "es", "sc", "kg", "com", "net", "org"
    )

    /**
     * extras는 노드마다 Bundle 생성 가능 — 고비용. 웹 서브트리 안에서만 호출,
     * 네이티브 UI에서는 접근 없음
     */
    fun str(node: AccessibilityNodeInfo, key: String): String? = try {
        node.extras?.getString(key)?.takeIf { it.isNotEmpty() }
    } catch (e: Throwable) {
        null
    }

    fun role(node: AccessibilityNodeInfo): String? =
        str(node, CHROME_ROLE) ?: str(node, GECKO_ROLE)

    /** 이 노드가 웹 문서의 뿌리인지 여부 (= 여기서부터 아래가 웹 콘텐츠) */
    fun isWebHost(node: AccessibilityNodeInfo): Boolean {
        if (node.className?.toString() == WEB_HOST_CLASS) return true
        //                ^^^^^^^^^^^ className은 CharSequence. String과 == 로 비교하면
        //                구현체가 SpannableString일 때 항상 false → 판정 통째로 붕괴
        return role(node) == "rootWebArea"
    }

    fun isFrame(node: AccessibilityNodeInfo): Boolean {
        if (node.className?.toString() == WEB_HOST_CLASS) return true
        return role(node) in frameRoles
    }

    /** 남의 문서(iframe)로 들어가는 경계인지 여부 */
    fun isNestedDoc(node: AccessibilityNodeInfo): Boolean = role(node) in nestedDocRoles

    /**
     * 화면 밖으로 스크롤되어 지금은 보이지 않는 노드.
     *
     * **이 값은 String이 아니라 Boolean.** `getString`으로 읽으면 Bundle이 내부에서
     * ClassCastException을 만들어 스택 트레이스째 로그에 찍고 null 반환 — 판정은 늘 false가
     * 되어 조용히 실패, 웹 노드마다 경고 누적 → 실측 25초에 100만 줄 초과.
     * (덤프 도구의 `offscreen=[true]` 표시는 값을 toString()한 결과였을 뿐.)
     */
    fun isOffscreen(node: AccessibilityNodeInfo): Boolean = try {
        val ex = node.extras
        ex != null && ex.containsKey(OFFSCREEN) && ex.getBoolean(OFFSCREEN, false)
    } catch (e: Throwable) {
        false
    }

    /** 이 노드가 URL을 실어 보낼 수 있는 종류인지 여부 — 아니면 targetUrl 읽기는 헛수고 */
    fun canBearUrl(node: AccessibilityNodeInfo): Boolean {
        val r = role(node) ?: return false
        return r.lowercase() in urlBearingRoles
    }

    /**
     * 앵커(`<a href>`)인지 여부. 이미지의 `src`와 구분 필요.
     * 현재 페이지의 사이트 추정 시 이미지까지 세면 안 됨 — 이미지는 CDN을 가리키고,
     * 그 CDN은 사이트와 다른 도메인인 경우가 많음. 실례: 11번가에서 이미지 CDN(cdn.011st.com)이
     * 링크보다 많아 페이지 주소 통째로 오인.
     */
    fun isLink(node: AccessibilityNodeInfo): Boolean = role(node)?.lowercase() == "link"

    fun targetHost(node: AccessibilityNodeInfo): String? =
        str(node, TARGET_URL)?.let { hostOf(it) }

    // 현재 페이지 주소는 노드에서 직접 획득 불가.
    // `AccessibilityNodeInfo.url` 키를 기대했으나 실측 결과 어느 노드에도 부재
    // (크롬 150, 2026-08-11), 주소창은 스크롤로 툴바가 숨으면 트리에서 아예 소실.
    // 그래서 [AdScanner]가 **최상위 문서(iframe 밖)의 링크들이 가리키는 호스트**로 역산.

    /**
     * 주소 문자열에서 호스트만 추출.
     *
     * 기존 구현은 `substringBefore('/')` — 주소창이 스킴을 포함한 전체 주소
     * (`https://m.example.com/...`)를 보여주는 순간 호스트가 모든 사이트에서 `https:`로
     * 고정. 크롬이 비포커스 상태에서 도메인만 보여주는 덕에 우연히 동작했을 뿐.
     */
    fun hostOf(raw: String?): String? {
        var s = raw?.trim()?.trimStart('‎', '‏') ?: return null
        if (s.isEmpty()) return null
        if (!s.contains("://")) s = "https://$s"
        val host = try {
            Uri.parse(s).host?.lowercase()
        } catch (e: Throwable) {
            null
        } ?: return null
        return host.ifEmpty { null }
    }

    /**
     * 서브도메인을 떼고 등록 단위 도메인만 유지.
     *
     * `m.news.nate.com`과 `m.nate.com`은 같은 사이트인데 접두사만 떼면 `news.nate.com` ≠ `nate.com`이
     * 되어 다른 사이트로 오인. 그래서 마지막 두 조각(`co.kr`류는 세 조각)만 유지.
     *
     * 공용 접미사 목록 전체를 들고 있지 않으므로 불완전. 다만 이 값은
     * "광고인가"를 정하는 근거가 아니라 **내부 링크를 광고로 오인하지 않기 위한 안전장치**로만 사용 —
     * 틀리는 쪽이 미탐(광고를 놓침)이지 오탐이 아님.
     */
    fun siteOf(host: String): String {
        val parts = host.lowercase().trim('.').split('.')
        if (parts.size <= 2) return parts.joinToString(".")
        val secondLast = parts[parts.size - 2]
        val take = if (parts.last().length <= 3 && secondLast in twoLevelSuffixes) 3 else 2
        return parts.takeLast(minOf(take, parts.size)).joinToString(".")
    }

    /** 같은 사이트인지 여부 — 광고망 판정에서 내부 링크를 걸러내는 용도 */
    fun sameSite(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        return siteOf(a) == siteOf(b)
    }

    /**
     * 광고 링크가 실제로 데려갈 **광고주 사이트**. 못 알아내면 null.
     *
     * 클릭 없이 목적지를 아는 문제라 광고 종류마다 상이 (2026-08-15 실측):
     *  - 직링크(오늘의집 `link.ohou.se` 등): 링크 도메인 = 광고주.
     *  - 구글 광고: 겉은 `googleadservices.com/pagead/aclk`지만 **`adurl=` 파라미터 안에**
     *    진짜 목적지 포함. 열어서 추출.
     *  - 매체 자체 트래커(`cyad1.nate.com/click.kti` 등): 목적지 정보 없음 → null.
     *    **미리 따라가기 금지** — 트래커는 요청하는 순간 클릭으로 집계되어
     *    가짜 클릭 발생. 클릭 전에는 모르는 것으로 유지.
     */
    fun advertiserSiteOf(url: String?): String? {
        if (url == null) return null
        val adurl = Regex("[?&]adurl=([^&]+)").find(url)?.groupValues?.get(1)?.let {
            runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull()
        }
        val host = hostOf(adurl ?: url) ?: return null
        // 광고망 도메인 자체는 목적지가 아님 (aclk인데 adurl이 없으면 알 수 없는 것)
        if (AdRules.isAdHost(host)) return null
        return siteOf(host)
    }

    /**
     * 클릭 직후 **리다이렉트가 멎기 전에** 서버에 보내도 되는 주소. 못 정하면 null.
     *
     * 서버는 받은 주소를 열어 리다이렉트를 끝까지 따라간다(fetch_chain). 첫 홉이 매체 트래커
     * (`cyad1.nate.com/click.kti`·`/click_redirect`)나 광고망이면 서버가 여는 순간 **클릭이 한 번
     * 더 집계**된다 — 그런 주소는 보내지 않고 주소가 멎은 뒤 도착지로 요청(예전 경로).
     * 구글 광고는 `adurl=` 안의 목적지를 보낸다 — 착지 페이지 열기는 클릭 집계와 무관.
     * 서버 `_prejudge_target`과 같은 기준
     */
    fun earlyJudgeTarget(url: String): String? {
        Regex("[?&]adurl=([^&]+)").find(url)?.groupValues?.get(1)?.let {
            runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull()
        }?.let { return it }
        val host = hostOf(url) ?: return null
        if (AdRules.isAdHost(host) || "cyad" in host) return null
        val low = url.lowercase()
        if ("/click" in low || "click_redirect" in low) return null
        return url
    }
}
