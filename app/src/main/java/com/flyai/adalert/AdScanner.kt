package com.flyai.adalert

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 화면의 접근성 트리를 **한 번만** 훑어 광고 영역 탐색.
 *
 * 이전 구현: 감지·주소읽기·버튼찾기가 각각 트리를 따로 순회 — 한 화면에 세 번.
 * 지금: 한 번 순회로 필요한 것 전부 수집.
 *
 * ## 대상 화면
 * 앱 이름 기준 아님. 노드에서 웹 엔진의 흔적 발견 시 **웹 규칙** 적용 —
 * 크롬·웨일·엣지·파이어폭스는 물론 카카오톡 인앱 브라우저까지 같은 경로.
 * 네이티브 UI는 앱마다 구조가 제각각이라 [AdRules.nativeAdApps]에 있는 앱에서만 검사
 * — 임의의 앱 순회 시 "광고 설정" 같은 메뉴에 테두리 씌우는 사고 발생.
 *
 * ## 예산으로 멈추는 이유
 * 이전: depth 60 제한 + 광고 5개 발견 시 조기 종료 → **미지원 사이트일수록 비용 최대**
 * (끝까지 다 훑고 아무것도 못 찾음). 이 구조 위에서는 감지 범위 확대 = 곧바로 비용 폭발.
 * 지금: 노드 수·시간 상한.
 */
/**
 * 스크롤 중 좌표만 다시 읽으려고 붙잡아 두는 노드.
 *
 * 노드 손잡이만으로는 부족. **크롬은 문서가 바뀌면 접근성 노드 id 재사용.**
 * `refresh()` 성공해도 그 자리가 아까 그 광고가 아니라 기사일 수 있음.
 * 붙잡을 때의 지문을 함께 보관, 매번 확인.
 */
class Anchor private constructor(
    private val node: AccessibilityNodeInfo,
    private val signature: String,
    /**
     * 이 노드의 좌표 = 광고 영역인지 여부.
     *
     * false면 합성 영역(스토리·릴스의 전체 화면, 피드 항목을 아래로 늘린 것)이라 노드 추적 금지.
     * 그래도 노드는 보관 — **광고가 아직 화면에 있는지** 확인할 유일한 수단.
     * 없으면 릴스를 넘긴 뒤에도 다음 영상 위에 테두리 1초 가까이 잔류.
     */
    val follow: Boolean
) {
    companion object {
        fun of(node: AccessibilityNodeInfo?, follow: Boolean): Anchor? =
            node?.let { Anchor(it, sign(it), follow) }

        private fun sign(node: AccessibilityNodeInfo): String =
            "${node.viewIdResourceName}|${node.className}"
    }

    /**
     * 영역을 만든 노드 자체. 광고의 **목적지**(테두리 색을 미리 정할 곳) 탐색용 —
     * 이 노드에서 아래로 순회. 좌표 추적에는 사용 금지 — 그건 [bounds]
     */
    fun raw(): AccessibilityNodeInfo = node

    /** 지금 좌표. 사라졌거나 **다른 요소로 바뀌었으면** null */
    fun bounds(): Rect? {
        if (!node.refresh()) return null
        if (sign(node) != signature) return null
        // 인스타는 다음 스토리로 넘어간 뒤에도 이전 페이지의 노드를 잠깐 유지.
        // 좌표는 멀쩡한 값 그대로 — 좌표만 보면 광고가 아직 있는 것으로 오인.
        // 실측: 이 검사 없으면 다음 영상 위에 테두리 450ms 잔류
        if (!node.isVisibleToUser) return null
        val r = Rect().also { node.getBoundsInScreen(it) }
        // 크롬: 화면 밖으로 나간 노드를 높이 0으로 접어 보고.
        // 인스타 페이저: 넘어간 페이지를 폭 0으로 유지
        return if (r.width() > 0 && r.height() > 0) r else null
    }
}

class AdScanner {

    class Result(
        /** 실제로 테두리를 그릴 영역 (L1 등급만) */
        val regions: List<Rect>,
        /**
         * 각 영역의 좌표를 만든 노드. [regions]와 같은 순서·같은 길이.
         * 스크롤 중 트리 재순회 없이 이 노드들의 좌표만 다시 읽어 테두리 추적.
         * null이면 합성 좌표 — 추적 불가
         */
        val anchors: List<Anchor?>,
        /** 각 영역을 광고로 판정한 근거. 오탐 조사의 필수 정보 */
        val reasons: List<String>,
        /** 그리지는 않고 로그만 남길 근거 (L2 등급) */
        val shadow: List<String>,
        /** 웹 문서 뿌리에서 읽은 현재 페이지 호스트. 브라우저 UI를 거치지 않은 값 */
        val pageHost: String?,
        /** 이 화면에서 웹 콘텐츠를 만났는지 여부 */
        val sawWeb: Boolean,
        /** 쇼핑몰이라 표시를 억제했는지 여부 (아무것도 안 뜨는 이유 파악에 필요) */
        val shoppingHost: Boolean,
        /**
         * 브라우저가 **자기 화면에** 띄운 설치 파일 이름 (예: `clean_booster.apk`).
         *
         * 웹 콘텐츠 밖에서 찾은 것만 — 페이지 본문에 적힌 `무엇.apk`는 글일 뿐,
         * 다운로드 안내는 브라우저 UI. 순회가 이미 "웹 안인지"를 알고 있어 추가 비용 없이 구분
         */
        val apkName: String?,
        val visited: Int,
        /** 예산 부족으로 도중에 끊겼는지 여부 — 끊긴 결과로는 새 영역 추가 없음 */
        val truncated: Boolean,
        val elapsedMs: Long,
        /** 이번 스캔에서 광고를 실제로 잘라낸 고정 띠(웹 영역 폭 · 화면 좌표). 스크롤 추적이 같은 띠로 잘라냄 */
        val coverBands: List<Rect>
    )

    private companion object {
        /**
         * 노드 하나 읽을 때마다 앱 프로세스로 IPC 왕복. 크롬 모바일 뉴스 페이지 500~1300 노드,
         * 전부 훑는 데 150~250ms (S25 Edge 실측).
         * 예전 구현: 상한 없이 **한 화면에 세 번** 순회. 한 번으로 줄인 지금은 넉넉히 잡아도 예전보다 저렴.
         * 너무 조이면 광고에 닿기 전에 중단 — 120ms 설정 시 700 노드에서 잘려 광고 통째로 누락(실측)
         */
        /** 브라우저 UI에 적힌 설치 파일 이름 추출 패턴 */
        val APK_IN_UI = Regex("[^\\s\"'/\\\\]+\\.apk", RegexOption.IGNORE_CASE)

        const val NODE_BUDGET = 5000
        const val TIME_BUDGET_MS = 400L
        const val MAX_DEPTH = 70          // 인스타 릴스의 광고 라벨이 30단계보다 깊음
        const val MAX_REGIONS = 5
        /** 페이지 주소 추정 시 표본으로 삼을 서로 다른 호스트의 최대 개수 */
        const val HOST_SAMPLE_LIMIT = 24
        /**
         * 다수결을 믿을 수 있는 최소 링크 표본 수. 이만큼 모으기 전에는 영역이 다 찼어도
         * 순회 계속 ([enough] 참고)
         */
        const val HOST_SAMPLE_MIN = 30

        /**
         * 덮개(고정 헤더·푸터) 후보 조건 — 웹 영역 폭의 90% 이상 · 화면 높이 30% 이하 · 위나 아래 가장자리에 접함.
         * 접근성 트리는 z축을 안 주므로, 광고와 겹치는 **혈연 없는** 가로띠 = 광고 위에 얹힌 고정 요소로 간주
         * (실측 2026-08-24 nate: 스크롤로 헤더 밑에 들어간 광고에 테두리 잔류)
         */
        const val COVER_MIN_WIDTH = 0.9f
        const val COVER_MAX_HEIGHT = 0.3f
        const val COVER_EDGE_SLOP = 4
        const val COVER_LIMIT = 8
    }

    private val scratch = Rect()
    private var visited = 0
    private var deadline = 0L
    private var truncated = false
    private var sawWeb = false
    /** 웹 콘텐츠 영역(WebView 노드 경계). 덮개 판정의 위·아래 가장자리 기준 */
    private var webRect: Rect? = null
    /** 덮개 후보(가로띠 rect · 그 노드). [clipByCovers]가 광고와 대조 */
    private val covers = mutableListOf<Pair<Rect, AccessibilityNodeInfo>>()
    private val coverBands = mutableListOf<Rect>()

    /** 이번 스캔에서 브라우저 UI에 보인 설치 파일 이름 */
    private var apkName: String? = null
    private var pageHost: String? = null

    private val regions = mutableListOf<Rect>()
    private val anchors = mutableListOf<Anchor?>()
    private val reasons = mutableListOf<String>()
    private val shadow = mutableListOf<String>()
    /** 최상위 문서 링크들이 가리킨 호스트와 횟수 */
    private val hostCounts = HashMap<String, Int>()
    /** 지금까지 센 링크의 총 개수 (서로 다른 호스트 수 아님) */
    private var hostSamples = 0

    fun scan(root: AccessibilityNodeInfo, screen: Rect, pkg: String): Result {
        val started = SystemClock.uptimeMillis()
        visited = 0
        truncated = false
        sawWeb = false
        webRect = null
        covers.clear()
        coverBands.clear()
        apkName = null
        pageHost = null
        regions.clear()
        anchors.clear()
        reasons.clear()
        shadow.clear()
        deadline = started + TIME_BUDGET_MS

        hostCounts.clear()
        hostSamples = 0
        val nativeAllowed = pkg in AdRules.nativeAdApps
        walk(root, 0, screen, inWeb = false, frameDepth = 0, nativeAllowed = nativeAllowed)

        // 순회 종료 후 가장 많이 나온 호스트로 확정 (잠정값은 첫 링크)
        hostCounts.maxByOrNull { it.value }?.let { pageHost = it.key }

        // 쇼핑몰은 상품 목록 자체가 파는 것 — 광고 별도 표시 무의미.
        // 물건 보러 들어온 정상 화면에 테두리는 방해만 됨.
        // (여기서 지워도 pageHost·sawWeb은 그대로 전달 — "뒤로 가기" 안내는 계속 동작)
        val shopping = AdRules.isShoppingHost(pageHost)
        if (shopping && regions.isNotEmpty()) {
            shadow.addAll(regions.indices.map { "shop-suppressed:${reasons.getOrElse(it) { "?" }}" })
            regions.clear()
            anchors.clear()
            reasons.clear()
        }

        // 전체 화면 광고가 하나라도 있으면 그것 하나만 유지 (테두리 겹침 방지)
        clipByCovers(screen)

        val fullIndex = regions.indexOfFirst { it == screen }
        if (fullIndex >= 0) {
            val full = regions[fullIndex]
            val anchor = anchors.getOrNull(fullIndex)
            val reason = reasons.getOrElse(fullIndex) { "?" }
            regions.clear(); regions.add(full)
            anchors.clear(); anchors.add(anchor)
            reasons.clear(); reasons.add(reason)
        }
        return Result(
            regions = regions.toList(),
            anchors = anchors.toList(),
            reasons = reasons.toList(),
            shadow = shadow.toList(),
            pageHost = pageHost,
            sawWeb = sawWeb,
            apkName = apkName,
            shoppingHost = shopping,
            visited = visited,
            truncated = truncated,
            elapsedMs = SystemClock.uptimeMillis() - started,
            coverBands = coverBands.toList()
        )
    }

    /**
     * 더 훑을 이유가 없는지 여부.
     *
     * 영역 [MAX_REGIONS]개를 채우는 즉시 순회 종료 → **웹에서 감지 붕괴.**
     * 현재 페이지 주소는 최상위 문서 링크들의 다수결([notePageHost])인데, 광고 다섯 개를 먼저 만나
     * 순회가 끊기면 표본이 몇 개 안 남음. 그 몇 개가 외부 링크면 주소 통째로 오인 →
     * [AdRules.isShoppingHost] 억제 해제 → 쇼핑몰 상품 목록에 테두리 표시.
     * 광고 많은 페이지일수록 표본이 일찍 끊겨 가장 필요한 곳에서 먼저 붕괴.
     *
     * 대책: 영역은 다섯 개에서 중단([accept]), 표본은 [HOST_SAMPLE_MIN]개 모일 때까지 계속 하강.
     * 비용은 그대로 노드 수·시간 예산이 제한
     */
    private fun enough(): Boolean =
        regions.size >= MAX_REGIONS && !(sawWeb && hostSamples < HOST_SAMPLE_MIN)

    private fun budgetLeft(): Boolean {
        if (visited >= NODE_BUDGET || SystemClock.uptimeMillis() > deadline) {
            truncated = true
            return false
        }
        return true
    }

    private fun walk(
        node: AccessibilityNodeInfo,
        depth: Int,
        screen: Rect,
        inWeb: Boolean,
        frameDepth: Int,
        nativeAllowed: Boolean
    ) {
        if (depth > MAX_DEPTH || enough()) return
        if (!budgetLeft()) return
        visited++

        // 여기서부터 아래가 웹 문서인지 여부. extras는 웹 안에서만 읽음 (네이티브에서는 비용만 발생)
        var web = inWeb
        if (!web && WebNode.isWebHost(node)) {
            web = true
            sawWeb = true
            if (webRect == null) webRect = Rect().also { node.getBoundsInScreen(it) }
        }
        // iframe 안쪽은 남의 문서. 현재 페이지 주소 역산 시 이 깊이를 세어 최상위만 사용
        val frame = if (web && WebNode.isNestedDoc(node)) frameDepth + 1 else frameDepth

        // 브라우저가 자기 화면에 띄운 설치 파일 이름. 크롬은 다운로드 시 아래쪽에
        // 파일 이름 표시 — 웹 콘텐츠가 **아니라** 브라우저 UI.
        // 알림 경로는 무음 모드에서 오지 않으므로(실측) 받는 순간을 잡는 유일한 경로
        if (!web && apkName == null) {
            val words = "${node.text} ${node.contentDescription}"
            if (words.contains(".apk", ignoreCase = true)) {
                apkName = APK_IN_UI.find(words)?.value
            }
        }

        // 화면 밖으로 접힌 컨테이너의 하위를 통째로 건너뛰는 최적화: 시도 후 되돌림.
        // 노드 수 14~24% 감소, **시간은 같거나 증가** (클리앙 282ms→316ms, 잘린 비율 34%→57%).
        // 병목은 노드 개수가 아니라 노드당 IPC 지연 — 건너뛸지 판단하려고 매 노드마다 하는
        // 검사 비용이 절약분을 그대로 상쇄. 재시도 금지. (2026-08-11 A/B 실측)
        if (node.isVisibleToUser && !(web && WebNode.isOffscreen(node))) {
            node.getBoundsInScreen(scratch)
            // 화면 밖 요소: 릴스 페이저는 좌표 어긋남, 크롬은 높이 0으로 접어 보고.
            // 실제로 화면에 크기를 차지할 때만 광고로 인정
            if (scratch.width() > 0 && scratch.height() > 0) {
                val hit = match(node, web, frame, nativeAllowed)
                if (hit != null) {
                    if (hit.grade == Grade.L1) {
                        accept(node, hit, screen)
                        return   // 광고 컨테이너 안쪽은 더 볼 필요 없음
                    }
                    // L2 = "광고 같지만 확신은 없음". 여기서 멈추면 **그 안에 있는 확실한 근거를 가림.**
                    // 실례: 퍼블리셔의 래퍼(gpt-passback)가 바로 아래 자식인
                    // google_ads_iframe_*(L1)을 덮어 광고 통째로 누락. 기록만 하고 계속 하강
                    shadow.add(hit.reason)
                } else if (web && covers.size < COVER_LIMIT && isDockedBar(scratch, screen)) {
                    covers.add(Rect(scratch) to node)
                }
            }
        } else if (web && frame == 0) {
            // 화면 밖이라 그리지는 않아도 최상위 문서의 링크면 페이지 주소 단서.
            // (주소창은 스크롤로 툴바가 숨으면 트리에서 사라져 신뢰 불가)
            notePageHost(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            walk(child, depth + 1, screen, web, frame, nativeAllowed)
            if (enough() || truncated) return
        }
    }

    /**
     * 최상위 문서(iframe 밖)의 링크가 가리키는 곳 = 현재 사이트.
     * 광고망 도메인 제외 — 최상위에도 광고 링크 혼재 가능.
     *
     * **첫 번째 링크가 아니라 최다 출현 호스트 사용.** 첫 링크만 보면 그게 외부 링크일 때
     * 페이지 주소 통째로 오인. 실례: 쿠팡에서 주소가 coupangplay.com으로 잡혀
     * 쇼핑몰 억제 해제. 내부 링크가 압도적으로 많다는 성질 이용
     */
    /** 웹 영역 위·아래 가장자리에 붙은 가로띠인지 — [COVER_MIN_WIDTH] · [COVER_MAX_HEIGHT] · [COVER_EDGE_SLOP] */
    private fun isDockedBar(r: Rect, screen: Rect): Boolean {
        val web = webRect ?: screen
        if (r.width() < web.width() * COVER_MIN_WIDTH) return false
        if (r.height() <= 0 || r.height() > screen.height() * COVER_MAX_HEIGHT) return false
        return r.top <= web.top + COVER_EDGE_SLOP || r.bottom >= web.bottom - COVER_EDGE_SLOP
    }

    /**
     * 광고 rect에서 덮개 띠를 잘라냄. 고정 헤더·푸터 밑으로 스크롤된 광고는 트리 좌표는 그대로인데
     * 눈에는 안 보임 — 그 자리에 테두리·가드가 남는 것을 막음.
     *  - 광고의 조상·자손인 띠는 덮개 아님 (광고를 품은 섹션 · 광고 안의 요소)
     *  - 다 잘리면 영역 제거 (shadow에 `covered:` 기록)
     *  - 실제로 잘라낸 띠만 [coverBands]로 내보냄 — 추적기가 스크롤 중 같은 띠로 잘라냄
     */
    private fun clipByCovers(screen: Rect) {
        if (covers.isEmpty() || regions.isEmpty()) return
        val web = webRect ?: screen
        val used = HashSet<Int>()
        var i = 0
        while (i < regions.size) {
            val r = Rect(regions[i])
            val anchorNode = anchors.getOrNull(i)?.raw()
            for ((ci, cover) in covers.withIndex()) {
                val (c, node) = cover
                if (!Rect.intersects(c, r) || r.contains(c)) continue
                if (anchorNode != null && isAncestor(node, anchorNode)) continue
                if (c.top <= web.top + COVER_EDGE_SLOP) r.top = maxOf(r.top, c.bottom)
                if (c.bottom >= web.bottom - COVER_EDGE_SLOP) r.bottom = minOf(r.bottom, c.top)
                used.add(ci)
            }
            if (r.height() <= 0) {
                shadow.add("covered:${reasons.getOrElse(i) { "?" }}")
                regions.removeAt(i); anchors.removeAt(i); reasons.removeAt(i)
            } else {
                regions[i] = r
                i++
            }
        }
        for (ci in used) {
            val c = covers[ci].first
            coverBands.add(
                if (c.top <= web.top + COVER_EDGE_SLOP) Rect(web.left, web.top, web.right, c.bottom)
                else Rect(web.left, c.top, web.right, web.bottom)
            )
        }
    }

    private fun isAncestor(candidate: AccessibilityNodeInfo, node: AccessibilityNodeInfo): Boolean {
        var p = node.parent
        var depth = 0
        while (p != null && depth < MAX_DEPTH) {
            if (p == candidate) return true
            p = p.parent
            depth++
        }
        return false
    }

    private fun notePageHost(node: AccessibilityNodeInfo) {
        if (!WebNode.isLink(node)) return   // 이미지 src는 CDN을 가리켜 사이트 판정 훼손
        val host = WebNode.targetHost(node) ?: return
        // 광고망은 물론, 광고와 기사를 같은 도메인으로 내보내는 추천 위젯도 표본에서 제외.
        // 광고 증거로는 불가(AdRules.mixedHosts)하지만 사이트 판정을 흔드는 것은 매한가지
        if (AdRules.isAdHost(host) || AdRules.isMixedHost(host)) return
        // 표본 한도 = **새 호스트를 더 담지 않음**, 세기 중단 아님.
        // 이미 아는 호스트의 횟수까지 멈추면 다수결이 한도 도달 시점의 값으로 고정
        val seen = hostCounts[host]
        if (seen == null && hostCounts.size >= HOST_SAMPLE_LIMIT) return
        hostCounts[host] = (seen ?: 0) + 1
        hostSamples++
        // 순회 도중의 same-site 판정에도 값이 필요 — 잠정값 먼저 채움
        if (pageHost == null) pageHost = host
    }

    private class Hit(val strategy: RegionResolver.Strategy, val grade: Grade, val reason: String)

    /** 세 축을 차례로 검사. 싼 것부터, 확실한 것부터 */
    private fun match(
        node: AccessibilityNodeInfo,
        web: Boolean,
        frameDepth: Int,
        nativeAllowed: Boolean
    ): Hit? {
        if (web && frameDepth == 0) notePageHost(node)

        // ── 축 2. HTML id (웹에서만) ──
        if (web) {
            val id = node.viewIdResourceName
            // ":id/" 포함 = 브라우저 자신의 UI 요소. HTML에서 온 id만 검사
            if (id != null && !id.contains(":id/")) {
                val lower = id.lowercase()
                for ((token, baseGrade) in AdRules.containerIds) {
                    if (token in lower) {
                        // 퍼블리셔가 지은 이름(L2)이라도 그 자리가 iframe이면 광고 확정.
                        // 남의 문서를 통째로 끼워 넣는 자리에 "ad"라는 이름이 붙은 것
                        val grade =
                            if (baseGrade == Grade.L2 && WebNode.isNestedDoc(node)) Grade.L1
                            else baseGrade
                        // id로 잡은 노드 = 그 자체가 광고 한 칸
                        return Hit(RegionResolver.Strategy.SELF, grade, "id:$token")
                    }
                }
            }
        }

        // ── 축 1. 라벨 ──
        if (web || nativeAllowed) {
            val text = node.text
            val desc = node.contentDescription
            if (!text.isNullOrEmpty() || !desc.isNullOrEmpty()) {
                if (isAdLabel("$text · $desc")) {
                    val strategy =
                        if (web) RegionResolver.Strategy.CLICKABLE_ANCESTOR
                        else RegionResolver.Strategy.CARD_ANCESTOR
                    return Hit(strategy, Grade.L1, "label")
                }
            }
        }

        // ── 축 3. 링크·이미지가 가리키는 광고망 도메인 (웹에서만) ──
        if (web && WebNode.canBearUrl(node)) {
            val host = WebNode.targetHost(node)
            if (host != null) {
                // 현재 페이지와 같은 사이트면 광고가 아니라 내부 기사 링크. 부정 증거 우선
                if (!WebNode.sameSite(host, pageHost) && AdRules.isAdHost(host)) {
                    return Hit(RegionResolver.Strategy.IFRAME_ANCESTOR, Grade.L1, "url:$host")
                }
            }
        }
        return null
    }

    /** L1 근거만 여기로 (L2는 [walk]에서 기록만 하고 통과) */
    private fun accept(node: AccessibilityNodeInfo, hit: Hit, screen: Rect) {
        // 표본 수집을 위해 순회가 이어지는 중일 수 있음. 영역은 여기서 중단
        if (regions.size >= MAX_REGIONS) return
        val region = RegionResolver.resolve(node, hit.strategy, screen) ?: return
        val r = region.rect
        // 이미 잡은 영역을 포함하거나 그 안에 들어가면 같은 광고
        if (regions.none { it.contains(r) || r.contains(it) }) {
            regions.add(r)
            anchors.add(Anchor.of(region.node, region.follow))
            reasons.add(hit.reason)
        }
    }

    /**
     * 광고 카드 전체가 한 노드로 합쳐져 설명 문구 안에 라벨이 섞여 오는 경우 존재
     * (유튜브 Litho, 인스타 피드) → 구분자로 쪼갠 **토큰이 정확히 광고 표기일 때만** 인정.
     * "광고 문의", "광고성 정보 수신 동의" 같은 문구의 광고 오인 방지
     */
    private fun isAdLabel(s: String): Boolean =
        s.lowercase()
            .replace(AdRules.zeroWidth, "")
            .split(AdRules.labelSeparators)
            .any { it.trim() in AdRules.labelTokens }
}
