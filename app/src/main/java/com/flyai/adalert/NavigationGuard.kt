package com.flyai.adalert

import android.util.Log

/**
 * 광고가 사용자를 다른 사이트나 다른 앱으로 강제로 끌고 갔을 때 "뒤로 가기" 표시.
 *
 * 이전 구현: 크롬의 주소창 뷰 id(`url_bar`)와 탭 목록 id(`tab_list_recycler`, `hub_toolbar`)를
 * 직접 탐색 → 다른 브라우저에서는 주소를 읽지 못해 **오류도 없이 조용히 무동작.**
 * 지금: 브라우저 UI를 전혀 보지 않고, 스캐너가 웹 문서 뿌리에서 읽어온 주소만 사용.
 *
 * 주소 변경의 원인은 강제 이동 외에 세 가지 — 모두 걸러내야 함.
 *  - 같은 사이트 안에서 기사를 눌러 이동 → 호스트가 그대로라 애초에 미해당
 *  - 되돌아가는 중 → 거쳐 온 사이트를 기억해 두고 직전 사이트로 돌아가면 알림 없음
 *    (없으면 뒤로 가기를 누른 그 페이지에서 버튼이 또 떠서 무한 반복)
 *  - 사용자가 탭 목록에서 다른 창 선택 → 그 창의 주소는 원래 다른 사이트
 */
class NavigationGuard {

    private companion object {
        const val TAG = "NavGuard"
        const val PROMPT_MS = 12_000L
        /**
         * 되돌아가기로 안내를 끄는 데 필요한 최소 경과. 안내 시작 후 이 안에 출발지로 돌아온 것은
         * 사용자 동작이 아니라 주소창 잡음 — 크롬이 광고를 새 탭으로 열 때 주소창이 0.4초쯤 원래
         * 사이트를 다시 보임(실측 2026-08-24 nate → tworld). 그때 안내를 끄면 착지 페이지의 쉴드가
         * 걷힌 채로 남음. 페이지를 읽고 돌아오는 데 1.5초는 걸림
         */
        const val BACK_MIN_MS = 1_500L
        /** 웹이 안 보이던 화면(탭 목록·새 탭)을 거친 직후의 주소 변화 = 탭 전환으로 간주 */
        const val TAB_SWITCH_WINDOW_MS = 5_000L
        /**
         * 그 "웹이 안 보이던 구간"을 탭 목록으로 인정하는 최소 길이.
         *
         * 웹이 잠깐 사라지는 것만으로는 탭 전환과 **그냥 페이지 이동** 구분 불가.
         * 크롬은 같은 탭에서 다음 페이지로 넘어갈 때도 접근성 트리를 통째로 비움.
         * 실측(2026-08-13): 연합뉴스TV → hear.com 광고 이동의 공백 약 600ms,
         * 쿠팡앱으로 넘어갈 때 390ms. 그걸 탭 전환으로 읽어 **강제 이동 안내 누락**,
         * 게다가 siteStack 초기화로 되돌아 나올 때 거꾸로 안내 표시.
         *
         * 탭 목록은 사용자가 눈으로 훑고 고르는 화면이라 체류 시간이 훨씬 김.
         */
        const val TAB_SWITCH_MIN_BLANK_MS = 1_500L
        const val STACK_LIMIT = 10
        /**
         * "방금까지 웹을 보고 있었다"로 인정하는 시간.
         *
         * 직전 스캔 한 번만으로는 부족 — **크롬은 앱을 넘기기 전에 접근성 트리에서
         * 웹 콘텐츠를 먼저 제거.** 실측(2026-08-13, 연합뉴스TV → 쿠팡앱, 3회):
         * `웹이 사라짐` 기록 후 191·273·200ms 뒤에 패키지 변경. 그 빈 스캔 때문에
         * 강제 이동이 세 번 모두 조용히 무시됨.
         */
        const val WEB_MEMORY_MS = 5_000L
        /**
         * "아직 한 번도 없었다"를 0으로 두면 안 되는 이유: 시각은 부팅 이후 경과 시간이라
         * 부팅 직후 5초 동안 `now - 0 < 5초`가 참 → 그 사이의 강제 이동 전부 탭 전환으로 오인.
         */
        const val NEVER = Long.MIN_VALUE
    }

    private val siteStack = mutableListOf<String>()
    /**
     * 앱 사이를 오간 자취. 사이트 기록([siteStack])과 **같은 이유로** 필요.
     *
     * 넘어간 앱에 웹이 없을 때만 알리던 동안에는 되돌아 나오는 길이 저절로 걸러짐
     * (쇼핑앱 → 브라우저는 웹이 보이니 조건에서 탈락). 그 조건을 걷어낸 지금은
     * 나오는 길도 "앱이 바뀌었고 웹에서 왔다"에 그대로 걸려 안내 재표시.
     * 거쳐 온 앱을 기억해 두고 되돌아가는 것이면 알림 없음.
     */
    private val appStack = mutableListOf<String>()
    private var lastSite: String? = null
    private var promptUntil = 0L
    /** 지금 살아 있는 안내의 시작 시각. [BACK_MIN_MS] 판정용 */
    private var promptStartedAt = 0L
    /** 지금 이어지고 있는 "웹이 안 보이는" 구간의 시작. NEVER면 웹이 보이는 중 */
    private var blankStart = NEVER
    /** 그 구간에서 마지막으로 웹이 안 보인다고 확인한 시각 */
    private var blankLast = NEVER
    /** 방금 끝난 공백 구간이 끝난 시각과 길이 */
    private var blankEndAt = NEVER
    private var blankMs = 0L
    private var prevPkg: String? = null
    /** 마지막으로 웹 콘텐츠를 본 시각. 시각이어야 하는 이유는 [WEB_MEMORY_MS] 참고 */
    private var lastWebAt = NEVER

    /**
     * 스캔은 200ms마다 반복, 대부분 변화 없음. 같은 줄을 계속 찍으면
     * 정작 봐야 할 전환이 묻히므로 상태가 실제로 달라졌을 때만 기록.
     */
    private var lastLog = ""

    private fun trace(what: String) {
        if (what == lastLog) return
        lastLog = what
        Log.i(TAG, what)
    }

    /**
     * 안내 종류 — "앱에서 빠져나가기"(광고가 다른 앱을 열었음) 또는 "페이지 되돌아가기".
     * 판단은 스캔 스레드, 읽기는 버튼을 누른 메인 스레드.
     */
    @Volatile
    var leavesApp = false
        private set

    /**
     * 스캔 한 번마다 호출.
     * @return "뒤로 가기" 안내를 지금 띄워야 하면 true
     */
    fun update(pkg: String, pageHost: String?, sawWeb: Boolean, now: Long): Boolean {
        val appChanged = pkg != prevPkg
        // **이번 스캔의 sawWeb 반영 전에** 읽어야 함. 아니면 넘어간 앱이
        // 스스로 웹을 보여주는 것만으로 "웹에서 왔다"로 오인
        val cameFromWeb = lastWebAt != NEVER && now - lastWebAt < WEB_MEMORY_MS

        if (appChanged) {
            siteStack.clear()
            lastSite = null
            blankStart = NEVER
            blankEndAt = NEVER

            // 자취가 비어 있으면 떠나온 앱부터 바닥에 깔아 둠 (되돌아올 자리)
            if (appStack.isEmpty()) prevPkg?.let { appStack.add(it) }
            // 맨 위(지금 떠나는 앱)를 뺀 어딘가에 있으면 되돌아가는 길.
            // 한 칸 아래만 보지 않는 이유: 앱이 스스로 닫히며 두 칸을 건너뛰고 돌아오는 경우 존재
            var backTo = -1
            for (i in 0 until appStack.size - 1) if (appStack[i] == pkg) backTo = i

            when {
                backTo >= 0 -> {
                    while (appStack.size > backTo + 1) appStack.removeAt(appStack.size - 1)
                    promptUntil = 0
                    trace("app $prevPkg -> $pkg : goingBack stack=$appStack")
                }
                // 광고가 브라우저에서 곧바로 다른 앱을 열어버린 경우(쇼핑앱 등).
                // 홈 화면·최근 앱·알림창을 거쳐 사용자가 직접 연 것은 그 화면들이
                // AdRules.isIgnoredPackage에 걸려 [reset]으로 기록이 지워지므로
                // cameFromWeb이 false → 여기 도달 없음.
                //
                // 넘어간 앱에 웹이 없을 것(!sawWeb)을 조건에 두었더니 오히려
                // **노려야 할 대상을 걸러냄.** 쿠팡·11번가 같은 쇼핑앱은 껍데기만
                // 네이티브고 내용은 WebView라, 앱이 이미 떠 있는 상태로 복귀하면
                // 첫 스캔부터 웹이 보임. 그래서 콜드 스타트(스플래시 먼저)인
                // 첫 진입만 안내가 뜨고 두 번째부터는 조용히 실패.
                cameFromWeb && !isLauncher(pkg) -> {
                    appStack.add(pkg)
                    leavesApp = true
                    promptUntil = now + PROMPT_MS
                    promptStartedAt = now
                    trace("app $prevPkg -> $pkg : PROMPT (앱으로 강제 이동) stack=$appStack")
                }
                else -> {
                    appStack.add(pkg)
                    promptUntil = 0
                    trace("app $prevPkg -> $pkg : no prompt (cameFromWeb=$cameFromWeb)")
                }
            }
            if (appStack.size > STACK_LIMIT) appStack.removeAt(0)
        } else {
            // 웹이 안 보이는 구간의 **길이** 측정. 시작 시각만으로는 부족 —
            // 탭 목록을 30초 들여다보다 다른 탭을 고르면 그 30초가 흘러 창(5초) 초과,
            // 반대로 끝난 시각만 보면 200ms짜리 페이지 이동과 구분 불가
            if (!sawWeb) {
                if (blankStart == NEVER) blankStart = now
                blankLast = now
            } else if (blankStart != NEVER) {
                blankMs = blankLast - blankStart
                blankEndAt = blankLast
                blankStart = NEVER
                trace("$pkg: 웹이 ${blankMs}ms 동안 안 보였다")
            }

            val site = pageHost?.let { WebNode.siteOf(it) }
            if (sawWeb && site != null && site != lastSite) {
                // 되돌아가는 길인지 — **바로 아래 한 칸만 보면 안 됨.**
                // 광고는 경유지를 거쳐 착지하므로(실측: nate → dable.io → naver 블로그)
                // 기록에 중간 홉이 남는데, 뒤로 가기는 그 홉을 건너뛰고 출발지로 한 번에 복귀.
                // "직전 칸"만 보면 돌아온 것을 새 강제 이동으로 읽어
                // 원래 보던 기사에서 "뒤로 가기" 재표시(사용자 보고). 앱 자취(appStack)는
                // 같은 이유로 이미 어디에 있든 탐색 — 사이트도 동일하게 통일
                var backTo = -1
                for (i in 0 until siteStack.size - 1) if (siteStack[i] == site) backTo = i
                val goingBack = backTo >= 0
                // 공백이 **직후**이면서 **충분히 길었을** 때만 탭 전환으로 간주
                val sinceBlank = if (blankEndAt == NEVER) Long.MAX_VALUE else now - blankEndAt
                val tabSwitch =
                    sinceBlank < TAB_SWITCH_WINDOW_MS && blankMs >= TAB_SWITCH_MIN_BLANK_MS
                val why = when {
                    goingBack -> "goingBack"
                    tabSwitch -> "tabSwitch(공백 ${blankMs}ms, ${sinceBlank}ms 전)"
                    lastSite != null -> "PROMPT (사이트 강제 이동, 공백 ${blankMs}ms)"
                    else -> "첫 사이트"
                }
                trace("site $lastSite -> $site : $why stack=$siteStack")
                when {
                    goingBack -> {
                        while (siteStack.size > backTo + 1) siteStack.removeAt(siteStack.size - 1)
                        // 안내 종료 — 앱 되돌아가기(위)와 동일. 남겨 두면 12초 안에 시스템 뒤로가기로
                        // 복귀한 출발지가 강제 이동으로 읽혀 쉴드 재표시 (실측 2026-08-24: 쿠팡 → 네이트).
                        // 단 안내 직후의 복귀는 주소창 잡음이라 유지 ([BACK_MIN_MS])
                        if (now - promptStartedAt >= BACK_MIN_MS) promptUntil = 0
                    }
                    tabSwitch -> { siteStack.clear(); siteStack.add(site) }
                    lastSite != null -> {
                        siteStack.add(site)
                        leavesApp = false
                        promptUntil = now + PROMPT_MS
                        promptStartedAt = now
                    }
                    else -> siteStack.add(site)
                }
                if (siteStack.size > STACK_LIMIT) siteStack.removeAt(0)
                lastSite = site
                // 다 쓴 표시는 삭제. 안 지우면 한 번 탭 목록을 거친 뒤로는 **그 뒤의 이동이
                // 전부 탭 전환으로 흡수** — 5초 안에 다음 사이트로 넘어가는 한 계속
                blankEndAt = NEVER
            }
        }

        prevPkg = pkg
        if (sawWeb) lastWebAt = now
        return now < promptUntil
    }

    /** 감지 대상이 아닌 화면으로 나갔을 때 기록 정리 */
    fun reset() {
        if (prevPkg != null) trace("reset() — 기록 삭제 (직전 $prevPkg)")
        siteStack.clear()
        appStack.clear()
        lastSite = null
        promptUntil = 0
        blankStart = NEVER
        blankEndAt = NEVER
        prevPkg = null
        // 홈·최근 앱·알림창을 거쳐 사용자가 직접 연 앱은 강제 이동 아님.
        // 그 화면들이 여기로 오므로, 웹을 봤다는 기억도 함께 단절
        lastWebAt = NEVER
    }

    /** 사용자가 "그냥 두기"를 눌렀거나 안내를 실행했을 때 */
    fun dismiss() {
        promptUntil = 0
    }

    private fun isLauncher(pkg: String) =
        pkg.contains("launcher") || pkg.contains("home") || pkg == "android"
}
