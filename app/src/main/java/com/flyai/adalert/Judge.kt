package com.flyai.adalert

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 광고가 데려간 페이지의 위험 판정.
 *
 * 이 파일은 **판정 주체가 아님.** 판정은 서버 담당, 여기는 그 결과를 화면에 쓸 수 있는 모양으로
 * 가공하는 자리. 지금은 서버가 없어 [StubJudge]가 대신 응답.
 * 서버 연결 시 [Judge] 구현 하나만 교체 — [Shield]와 [AdDetectService]는 수정 없음.
 *
 * ## 캐시 열쇠가 URL이 아니라 **호스트**인 이유
 * 광고 랜딩 주소는 누를 때마다 변동 — 구글 클릭 ID(`gclid`)와 캠페인 파라미터가 매번 새로 붙음.
 * 주소 전체를 열쇠로 쓰면 같은 페이지를 몇 번 들어가도 캐시 적중 0회.
 *
 *     advertiser.com/event?gclid=Cj0KCQiA3O...&utm_campaign=0813
 *     advertiser.com/event?gclid=Cj0KCQjw9Y...&utm_campaign=0813   ← 같은 페이지
 *
 * 게다가 위험 판정의 본질은 "이 페이지가 위험한가"가 아니라 **"이 사이트가 위험한가"**.
 * 사기 사이트는 어느 경로로 들어가도 사기, 정상 쇼핑몰은 어느 페이지든 정상.
 */
enum class Risk {
    /** 그냥 보게 둠. 쉴드는 곧바로 제거 */
    LOW,

    /** 설명 표시 후 **사용자가 선택** */
    MEDIUM,

    /** 설명 표시 후 돌아가기만 제공 */
    HIGH,

    /**
     * 판정 미수신 (네트워크 없음·시간 초과·서버 오류).
     *
     * 이때 막을지 열지가 설계에서 제일 조심스러운 선택. 막는 쪽이면 지하철에서 신호가
     * 끊길 때마다 멀쩡한 페이지가 차단되어 사용자가 앱을 꺼버림. 조용히 열면
     * 위험한 페이지가 아무 표시 없이 통과. 그래서 **[MEDIUM]과 같은 모양** —
     * 확인 실패를 알리고 선택은 사용자에게 위임.
     */
    UNKNOWN,
}

/**
 * @param risk     위험도
 * @param reason   판정 이유. 어르신이 읽을 문장이라 짧고 구체적이어야 함
 * @param advice   권장 행동. 버튼 밑에 한 줄로 표시
 * @param fromCache 이번 판정이 캐시에서 나왔는지 여부 (로그·시연 확인용)
 */
data class Verdict(
    val risk: Risk,
    val reason: String,
    val advice: String,
    val fromCache: Boolean = false,
    /**
     * 판정이 실제로 붙은 사이트 — 서버가 리다이렉트를 끝까지 따라간 **최종 도착지**의
     * 등록 도메인. 입력 주소는 매체의 클릭 집계 링크인 경우가 많아 그 도메인과 다름.
     * 사전 표시(테두리 색)는 이 값으로 기억해야 다른 광고에서 같은 광고주 식별 가능.
     */
    val site: String? = null,
    /**
     * 서버가 실제로 도착한 등록 도메인. [site]와 달리 공용 호스팅(imweb.me 등)이어도 채워짐.
     * 용도는 하나 — 클릭 즉시 보낸 요청의 답이 폰이 도착한 곳의 판정인지 대조([AdDetectService.ClickJudge]).
     * 캐시·사전 표시 열쇠로는 쓰지 않음(공용 호스팅을 열쇠로 쓰면 남의 판정을 상속)
     */
    val at: String? = null,
    /**
     * 이 등급의 원인 — 사칭·개인정보 요구·투자 권유 같은 **유형**.
     * 어르신 화면에는 미사용(읽을 것은 [reason] 한 문장이면 충분).
     * 보호자 기록에서 "무슨 일이었는지"를 한눈에 보여주는 용도.
     */
    val type: String = "none",
)

/** 판정 실제 수행 주체. 서버 연결 시 이 인터페이스만 새로 구현 */
interface Judge {
    /**
     * @param url      확보한 전체 주소. 주소창을 못 읽었으면 `https://<추측한 호스트>` 형태
     * @param clickUrl **누른 광고의 href** (트래커 주소). [url]은 리다이렉트가 끝난 도착지라
     *   트래커의 광고 식별번호 없음 — 서버가 "이 광고 = 이 판정" 매핑을 남기려면
     *   클릭 순간의 주소가 따로 필요. 없으면 null.
     * @param onEarly **저위험 선행 통보.** 서버가 등급을 확정하는 즉시(설명 문장을 다 쓰기 전) 한 번 호출 —
     *   [Verdict.risk]는 [Risk.LOW], [Verdict.site]는 서버가 따라간 도착 사이트, 문장은 비어 있음.
     *   저위험은 쉴드를 걷는 것이 전부라 문장을 기다릴 이유가 없음(1~2초). 이어서 [done]이 반드시 옴.
     *   중·고위험·캐시·실패에는 오지 않음. null이면 통보 없음. **메인 스레드에서** 호출
     * @param done 판정 완료 시 호출. **메인 스레드에서** 호출 필수
     */
    fun judge(url: String, clickUrl: String? = null, onEarly: ((Verdict) -> Unit)? = null, done: (Verdict) -> Unit)
}

/**
 * 서버 연결 전까지 쓰는 가짜 판정.
 *
 * 실제 호출처럼 **일부러 지연** 응답. 즉답이면 쉴드가 깜빡하고 지나가서
 * "분석 중" 화면의 실제 모습 확인 불가.
 */
class StubJudge : Judge {

    private val handler = Handler(Looper.getMainLooper())

    private companion object {
        const val TAG = "Judge"
        /**
         * 서버 왕복을 흉내 낸 지연.
         *
         * 처음 2.5초 → 통째로 사용자 대기 시간(실측: 클릭 후 쉴드까지 0.8초,
         * 그 뒤 판정까지 2.5초). 아무 일도 안 하면서 버리는 시간이라 단축.
         * 0이 아닌 이유: "확인 중" 화면이 한 프레임도 안 보이면 제대로 뜨는지 확인 불가
         */
        const val FAKE_DELAY_MS = 400L
    }

    /**
     * 시연·시험용 고정 판정. 호스트에 이 조각이 들어 있으면 그 위험도로 응답.
     * 서버 연결 시 통째로 사라질 표
     */
    private val canned = listOf(
        "coupang.com" to Risk.LOW,
        "11st.co.kr" to Risk.LOW,
        "hear.com" to Risk.MEDIUM,
    )

    override fun judge(url: String, clickUrl: String?, onEarly: ((Verdict) -> Unit)?, done: (Verdict) -> Unit) {
        val host = WebNode.hostOf(url) ?: ""
        val risk = canned.firstOrNull { it.first in host }?.second ?: Risk.LOW
        Log.i(TAG, "stub judge url=$url host=$host -> $risk")
        val site = WebNode.siteOf(host)
        // 서버처럼 저위험은 등급 먼저, 문장은 조금 뒤
        if (risk == Risk.LOW && onEarly != null) {
            handler.postDelayed({ onEarly(Verdict(Risk.LOW, "", "", site = site)) }, FAKE_DELAY_MS / 2)
        }
        handler.postDelayed({ done(verdictFor(risk, host).copy(site = site)) }, FAKE_DELAY_MS)
    }

    private fun verdictFor(risk: Risk, host: String) = when (risk) {
        Risk.LOW -> Verdict(risk, "특별히 위험한 점을 찾지 못했습니다.", "그대로 보셔도 됩니다.")
        Risk.MEDIUM -> Verdict(
            risk,
            "$host 는 개인정보를 입력받는 광고 페이지입니다.",
            "이름·전화번호를 넣기 전에 한 번 더 확인하세요."
        )
        Risk.HIGH -> Verdict(
            risk,
            "$host 는 다른 회사를 사칭하고 있습니다.",
            "돌아가세요. 여기에 정보를 넣으면 안 됩니다."
        )
        Risk.UNKNOWN -> Verdict(risk, "안전한지 확인하지 못했습니다.", "잘 모르겠으면 돌아가세요.")
    }
}

/**
 * 판정 앞에 붙는 호스트 단위 캐시.
 *
 * 같은 사이트 재방문 시 서버 질의 없음. 광고주 풀이 넓지 않아 실제로 적중률 양호 —
 * 뉴스 사이트를 몇 번 오가면 쿠팡·11번가·애드센스 계열 반복 출현.
 *
 * 앱 종료 시 사라지는 메모리 캐시. 장기 보관은 서버 DB의 몫 — 여기서는 하지 않음.
 */
class CachingJudge(
    private val inner: Judge,
    private val limit: Int = 100,
) : Judge {

    private companion object { const val TAG = "Judge" }

    /** 접근 순서 유지 — 가장 오래된 것부터 폐기 */
    private val cache = LinkedHashMap<String, Verdict>()

    override fun judge(url: String, clickUrl: String?, onEarly: ((Verdict) -> Unit)?, done: (Verdict) -> Unit) {
        val key = key(url)
        if (key == null) {
            inner.judge(url, clickUrl, onEarly, done)
            return
        }
        cache[key]?.let {
            Log.i(TAG, "cache hit $key -> ${it.risk}")
            // 캐시 적중이어도 호출 측에는 똑같이 콜백으로 반환.
            // 어떤 때는 즉시, 어떤 때는 나중에 돌아오면 호출 측이 두 갈래로 분기
            done(it.copy(fromCache = true))
            // 클릭 주소가 있으면 서버에도 통보 — 화면 응답은 캐시가 대신해도
            // "이 광고 = 이 판정" 매핑은 서버 DB에만 누적. 응답은 폐기
            if (clickUrl != null) inner.judge(url, clickUrl) { }
            return
        }
        inner.judge(url, clickUrl, onEarly) { verdict ->
            // 판정 미수신은 사이트의 성질이 아니라 그때의 사정 — 캐시 저장 금지.
            // 열쇠는 입력 주소가 아니라 서버가 답한 **판정이 붙은 사이트**(리다이렉트 끝 도착지).
            // 입력이 트래커(cyad1.nate.com)면 입력 열쇠(nate.com) 밑에 광고주 판정이 남아
            // 그 매체의 모든 광고가 상속 — 클릭 즉시 요청으로 트래커 입력이 잦아져 실제 위험.
            // 서버가 사이트를 안 주면(공용 사이트·미확정) 저장 없음
            val at = verdict.site
            if (verdict.risk != Risk.UNKNOWN && at != null) put(at, verdict)
            done(verdict)
        }
    }

    /**
     * `www.`·`m.` 같은 접두사 차이를 다른 사이트로 세면 캐시 분산.
     * [NavigationGuard]가 사이트를 셀 때 쓰는 것과 같은 함수로 기준 통일
     */
    private fun key(url: String): String? = WebNode.hostOf(url)?.let { WebNode.siteOf(it) }

    private fun put(key: String, verdict: Verdict) {
        cache.remove(key)
        cache[key] = verdict
        while (cache.size > limit) {
            val oldest = cache.keys.firstOrNull() ?: break
            cache.remove(oldest)
        }
    }
}
