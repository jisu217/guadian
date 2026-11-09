package com.flyai.adalert

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * "여기가 광고다" 근거 노드 → **테두리 사각형** 결정.
 *
 * 근거 노드 ≠ 실제 광고 영역이 보통. 웹의 "광고" 글자는 배너 구석의 작은 텍스트,
 * 인스타의 "Sponsored"는 게시물 헤더 안. 근거에서 광고 한 칸까지 거슬러 오르는 방식.
 *
 * 상수·조건은 실기기에서 하나씩 맞춘 값 — 근거 없는 변경 금지.
 */
object RegionResolver {

    enum class Strategy {
        /** 노드 자체 = 광고 한 칸 (광고망 id로 잡은 컨테이너) */
        SELF,

        /** 광고망 링크 → 그 링크를 담은 광고 프레임까지 상승 */
        IFRAME_ANCESTOR,

        /** 웹 광고 = 링크. 라벨을 감싼 가장 가까운 클릭 가능 조상 = 광고 한 칸 */
        CLICKABLE_ANCESTOR,

        /** 네이티브 앱 피드 광고: 카드 하나 = 광고 한 칸 */
        CARD_ANCESTOR
    }

    private const val MAX_CLIMB = 12

    /**
     * 광고 한 칸의 영역 + 그 영역의 근거 노드.
     *
     * 노드를 함께 반환하는 이유 두 가지.
     *  1. **위치 추적** — 스크롤 중 광고는 "무엇"은 그대로, "어디"만 변경. 이 노드 좌표만
     *     다시 읽으면 트리 전체 재탐색 불필요.
     *  2. **생존 확인** — 광고가 아직 화면에 있는지 확인. 이쪽이 더 중요.
     *
     * [follow]=false: 좌표가 합성된 것(전체 화면 승격, 피드 항목 아래로 확장)이라 노드 추적 금지.
     * 그래도 노드는 유지 — 2번에 필요. 없으면 릴스 넘긴 뒤에도 다음 영상 위에 테두리 잔존.
     */
    class Region(val rect: Rect, val node: AccessibilityNodeInfo?, val follow: Boolean = true)

    fun resolve(marker: AccessibilityNodeInfo, strategy: Strategy, screen: Rect): Region? =
        when (strategy) {
            Strategy.SELF -> Region(Rect().also { marker.getBoundsInScreen(it) }, marker)
            Strategy.IFRAME_ANCESTOR -> frameOf(marker, screen) ?: clickableOf(marker, screen)
            // 클릭 가능 조상 없음 → 라벨을 담은 광고 프레임 = 광고.
            // 이 폴백 없으면 `<iframe title="advertisement">` 형태 광고에서
            // 라벨을 찾고도 영역 미확보 → **그대로 폐기** (경향신문 실측)
            Strategy.CLICKABLE_ANCESTOR ->
                clickableOf(marker, screen) ?: frameOf(marker, screen) ?: frameHolderOf(marker, screen)
            Strategy.CARD_ANCESTOR -> cardOf(marker, screen)
        }

    /**
     * 광고망 링크를 담은 광고 프레임 탐색.
     * 광고는 보통 광고사 iframe 안에 통째로 존재 → 그 프레임 = 광고 영역.
     * 프레임이 화면 절반 초과 → 광고 한 칸이 아닌 문서 전체 — 포기.
     */
    private fun frameOf(marker: AccessibilityNodeInfo, screen: Rect): Region? {
        val r = Rect()
        var cur: AccessibilityNodeInfo? = marker.parent
        var up = 0
        while (cur != null && up < MAX_CLIMB) {
            cur.getBoundsInScreen(r)
            if (r.height() > screen.height() * 0.5) return null
            if (WebNode.isFrame(cur)) {
                return if (r.width() > 0 && r.height() > 0) Region(Rect(r), cur) else null
            }
            cur = cur.parent
            up++
        }
        return null
    }

    /**
     * 라벨의 **형제**로 광고 프레임이 놓인 경우: 둘을 함께 담은 상자 = 광고 한 칸.
     *
     * [frameOf]는 라벨의 조상 중 프레임 탐색. 그러나 라벨이 프레임 **안**이 아닌 **옆**에
     * 놓이는 배치 존재 — 광고 상자 안에 "AD" 글자와 광고 iframe을 나란히 넣는 형태.
     * 라벨 위로 프레임 없음 + 클릭 대상은 iframe 안쪽 상품이라 조상에 없음
     * → 라벨을 찾고도 영역 미확보로 **그대로 폐기**.
     *
     * 실측(2026-08-13, 서울경제 기사 안 쿠팡 광고): `ad_innerView2`[115,984][964,1690] 아래에
     * 텍스트 `AD`와 `chromeRole=iframe`이 형제로 나란히 배치. 그 상자 = 화면에 보이는 광고 한 칸.
     *
     * "광고 라벨 + 남의 문서(iframe)를 같은 상자에" 조건은 좁음 — 기사 본문이 우연히 걸릴
     * 모양 아님. 그래도 마지막 폴백 전용, 크기 상한은 그대로 적용.
     */
    private fun frameHolderOf(marker: AccessibilityNodeInfo, screen: Rect): Region? {
        val r = Rect()
        var cur: AccessibilityNodeInfo? = marker.parent
        var up = 0
        while (cur != null && up < MAX_CLIMB) {
            cur.getBoundsInScreen(r)
            if (r.height() > screen.height() * 0.5) return null
            if (r.width() > 0 && r.height() > 0 && hasFrameChild(cur)) return Region(Rect(r), cur)
            cur = cur.parent
            up++
        }
        return null
    }

    /** 바로 아래 자식 중 남의 문서(iframe) 존재 여부 */
    private fun hasFrameChild(node: AccessibilityNodeInfo): Boolean {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (WebNode.isNestedDoc(child)) return true
        }
        return false
    }

    /**
     * 라벨을 감싼 가장 가까운 클릭 가능 조상.
     * 웹 문서에는 "광고 카드" 구조 없음 → 부모를 계속 오르면 문서 전체 포착.
     * 광고 한 칸이라기엔 너무 큰 곳까지 오르면 포기 — 그런 광고는 대개 id로 별도 포착.
     */
    private fun clickableOf(marker: AccessibilityNodeInfo, screen: Rect): Region? {
        val r = Rect()
        var cur: AccessibilityNodeInfo? = marker
        var up = 0
        while (cur != null && up < MAX_CLIMB) {
            cur.getBoundsInScreen(r)
            if (r.height() > screen.height() * 0.5) return null
            if (cur.isClickable) return Region(Rect(r), cur)
            cur = cur.parent
            up++
        }
        return null
    }

    /**
     * 라벨 노드에서 가장 가까운 광고 카드 컨테이너(폭 70% 이상, 높이 8~85%) 탐색.
     *
     * 스크롤 피드 자체는 카드 아님 → 피드에 닿으면 탐색 중단. 그때까지 카드 없으면
     * 라벨이 속한 피드 항목부터 피드 아래 끝까지를 광고 영역으로 간주.
     * (인스타그램은 광고 게시물의 헤더·본문이 각각 별도 피드 항목이라 카드 조상 없음)
     *
     * 카드 미발견 또는 영역이 화면의 75% 이상 → 전체 화면 광고로 간주.
     */
    private fun cardOf(marker: AccessibilityNodeInfo, screen: Rect): Region {
        val r = Rect()
        var cur: AccessibilityNodeInfo? = marker   // 병합 노드는 라벨 노드 자신 = 광고 카드
        var item: Rect? = null                     // 직전에 지나온 조상 = 피드의 항목
        var card: AccessibilityNodeInfo? = null    // 카드로 확정된 노드 (스크롤 추적용)
        var up = 0
        while (cur != null && up < MAX_CLIMB) {
            cur.getBoundsInScreen(r)
            if (cur.isScrollable) {
                // 피드 항목을 아래로 늘린 합성 좌표 → 노드 추적 금지.
                // 다만 근거였던 라벨은 유지 — 광고 소멸 감지에 필요
                return Region(
                    item?.apply { bottom = maxOf(bottom, r.bottom) } ?: Rect(screen),
                    marker, follow = false
                )
            }
            // 화면 가장자리에 걸쳐 잘려 보이는 카드: 최소 높이 조건 면제
            val clipped = r.top <= screen.height() * 0.06 || r.bottom >= screen.height() * 0.94
            if (r.width() >= screen.width() * 0.7 && r.height() <= screen.height() * 0.85 &&
                (r.height() >= screen.height() * 0.08 || clipped) &&
                // 툴바는 광고 한 칸 아님. 화면 맨 아래에 붙어 위의 clipped 예외를
                // 통과하므로 이름으로 필터 필요 (인스타 스토리 광고 실측)
                !AdRules.isChromeContainer(cur.viewIdResourceName)
            ) {
                card = cur
                break
            }
            item = Rect(r)
            cur = cur.parent
            up++
        }
        val region = if (cur != null) Rect(r) else Rect(screen)
        if (region.height() >= screen.height() * 0.75) {
            // 화면 전체 = 광고(스토리·릴스). 좌표는 화면에 고정, 근거 노드는 계속 유지 —
            // 다음 항목으로 넘어간 것을 감지하는 유일한 방법
            region.set(screen)
            return Region(region, marker, follow = false)
        }
        return Region(region, card)
    }
}
