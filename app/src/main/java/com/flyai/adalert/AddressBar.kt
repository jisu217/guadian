package com.flyai.adalert

import android.view.accessibility.AccessibilityNodeInfo

/**
 * 브라우저 주소창에서 **현재 페이지의 전체 주소** 읽기.
 *
 * [AdScanner]가 역산하는 `pageHost`와 목적 상이. 그쪽은 "이 문서가 어느 사이트 것인가"를
 * 최상위 링크 다수결로 추정한 값 — 광고 판정의 안전장치로는 충분하나,
 * **경로 없음 + 링크 없는 페이지에서는 아예 빈 값.**
 * 하필 그 "링크가 거의 없는 단일 페이지 + 입력 폼"이 피싱 랜딩페이지의 생김새 →
 * 위험도 판정에 넘길 주소로 사용 불가.
 *
 * 따라서 브라우저 UI를 직접 읽음. 대신 **못 읽으면 null 반환** —
 * 호출 측이 `pageHost`로 폴백하므로 이 함수 실패 ≠ 기능 중단.
 *
 * ## 읽히는 시점
 * 주소창은 스크롤로 툴바가 숨으면 트리에서 통째로 소멸 → 아무 때나 호출 시 잦은 실패.
 * 단, 이 함수의 사용 시점은 **강제 이동 직후** — 새 페이지 로드 직후라 툴바가 내려온 상태.
 * 노리는 그 한순간에만 읽히면 충분.
 *
 * ## 브라우저별 읽히는 값 (2026-08-11 실측)
 * - 크롬 계열(`url_bar`): 스킴 포함 전체 주소.
 * - 삼성 인터넷(`location_bar_edit_text`): **호스트만**. 경로 미제공.
 *   (삼성 인터넷은 웹 본문 자체가 트리에 미노출 → 여기까지가 한계)
 */
object AddressBar {

    /**
     * 주소창 뷰의 id 조각. 부분 일치 탐색 —
     * 실제 값은 `com.android.chrome:id/url_bar`처럼 패키지가 앞에 붙는 형태.
     */
    private val barIds = listOf(
        "url_bar",                          // 크롬·엣지·웨일 등 크로미움 계열
        "location_bar_edit_text",           // 삼성 인터넷
        "mozac_browser_toolbar_url_view",   // 파이어폭스
    )

    /**
     * 주소창은 브라우저 UI라 트리의 얕은 곳에 위치. 전체 탐색 불필요 → 상한 설정.
     * 못 찾으면 null, 호출 측 폴백 → 조용한 실패도 위험 없음.
     */
    private const val MAX_DEPTH = 12
    private const val MAX_NODES = 400

    /**
     * @return 스킴 포함 전체 주소. 못 읽었으면 null.
     *   삼성 인터넷처럼 호스트만 오는 브라우저에서는 `https://<host>` 형태.
     */
    fun urlOf(root: AccessibilityNodeInfo?): String? {
        if (root == null) return null
        val raw = find(root, 0, intArrayOf(0)) ?: return null
        return normalize(raw)
    }

    private fun find(node: AccessibilityNodeInfo, depth: Int, budget: IntArray): String? {
        if (depth > MAX_DEPTH || budget[0] >= MAX_NODES) return null
        budget[0]++

        val id = node.viewIdResourceName
        if (id != null && barIds.any { it in id }) {
            val text = node.text?.toString()?.trim()
            // 사용자가 주소창 편집 중이면 입력 중인 글자가 읽힘 — 페이지 주소 아님
            if (!text.isNullOrEmpty() && !node.isFocused) return text
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            find(child, depth + 1, budget)?.let { return it }
        }
        return null
    }

    /**
     * 주소창 표시는 사람이 읽기 좋게 다듬어진 형태 → 그대로는 주소 아님.
     * 크롬은 `https://` 숨김, 방향 제어 문자 앞뒤 부착 사례 있음.
     */
    private fun normalize(raw: String): String? {
        var s = raw.trim().trim('‎', '‏', '‪', '‬')
        if (s.isEmpty()) return null
        // 주소 대신 검색어가 들어 있는 경우 필터
        if (' ' in s && "://" !in s) return null
        if ("://" !in s) s = "https://$s"
        val host = WebNode.hostOf(s) ?: return null
        // **점 없음 = 도메인 아님.**
        // id 부분 일치 탐색이라 브라우저가 아닌 앱의 뷰에도 매칭 —
        // 실측(2026-08-15, 쿠팡 앱): 글자가 `webview`인 노드가 잡혀 호스트 `webview`,
        // 그 값이 그대로 사이트 기록에 들어가 `site null -> webview` 기록.
        // 이 값은 NavigationGuard의 사이트 판정에 사용 → 가짜가 섞이면 오탐.
        return if ('.' in host) s else null
    }
}
