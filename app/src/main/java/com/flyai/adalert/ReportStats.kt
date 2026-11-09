package com.flyai.adalert

import java.util.Calendar
import java.util.Date

/**
 * 주간 리포트(11G-1·2)와 상세 분석(11G-3·4) 공용 **집계 로직**.
 *
 * 두 파일에서 "이번 주" 뜻이 어긋나면 리포트 2건이 상세 분석에서 3건이 되는 사고 →
 * 기간 자르는 규칙·묶는 규칙을 한 곳에 집중.
 * [Family]는 변경 없음 — 기록 읽기는 그쪽, 집계는 여기.
 */
object ReportStats {

    /** 시안의 순서 — 월요일부터 */
    val DAYS = listOf("월", "화", "수", "목", "금", "토", "일")

    /**
     * [weeksAgo]주 전 구간의 시작 날짜(그날 0시).
     *
     * 달력상 '이번 주'(월요일부터)가 아니라 **최근 7일** 기준. 월요일 아침은 달력 기준으로
     * 빈 리포트 — 보호자가 알고 싶은 것은 "요즘 어땠나"이지 "이번 달력 주"가 아님.
     * 시안의 날짜 범위도 7일.
     */
    fun weekStart(weeksAgo: Int): Date {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        c.add(Calendar.DAY_OF_YEAR, -6 - 7 * weeksAgo)
        return c.time
    }

    /**
     * [weeksAgo]주 전 구간의 기록. 0 = 이번 주(시작 이후 전부), 1 = 지난주(시작 이후 ·
     * 이번 주 시작 전). 시각 없는 기록(서버 시각 미부착)은 집계 제외.
     */
    fun week(all: List<Family.Event>, weeksAgo: Int): List<Family.Event> {
        val from = weekStart(weeksAgo)
        val to = if (weeksAgo == 0) null else weekStart(weeksAgo - 1)
        return all.filter { e ->
            val at = e.at ?: return@filter false
            at.after(from) && (to == null || at.before(to))
        }
    }

    /** 최근 4주의 건수 — 앞이 오래된 쪽(3주 전), 끝이 이번 주. 시안 11G-4의 막대 네 개 */
    fun fourWeeks(all: List<Family.Event>): IntArray =
        IntArray(4) { i -> week(all, 3 - i).size }

    /** 요일별 건수(월~일). 시안 11G-2의 막대 일곱 개 */
    fun byDay(week: List<Family.Event>): IntArray {
        val counts = IntArray(7)
        week.forEach { e ->
            val c = Calendar.getInstance().apply { time = e.at ?: return@forEach }
            counts[(c.get(Calendar.DAY_OF_WEEK) + 5) % 7]++
        }
        return counts
    }

    // ── 위험도 ────────────────────────────────────────────────────────────────

    /** 높을수록 위험. 같은 곳의 기록을 한 줄로 줄일 때 가장 나쁜 것 선택용 */
    fun rank(risk: String) = when (risk) { "HIGH" -> 2; "MEDIUM" -> 1; else -> 0 }

    /** 시안 11G-1·11G-2의 말 — 고위험 · 중위험 · 광고 */
    fun riskLabel(risk: String) = when (risk) {
        "HIGH" -> "고위험"; "MEDIUM" -> "중위험"; else -> "광고"
    }

    /** 위험도의 색 — 고위험 빨강 · 중위험 주황 · 나머지 회색(시안 11G-3의 '분석 한계' 막대색) */
    fun riskInk(risk: String) = when (risk) {
        "HIGH" -> Look.DANGER; "MEDIUM" -> Look.WARN; else -> Look.INK_MUTED
    }

    // ── 사이트별로 묶기 (시안 11G-3) ───────────────────────────────────────────

    /** 한 곳(호스트)에서 난 기록 묶음 */
    class Site(val name: String, val events: List<Family.Event>) {
        val count get() = events.size
        /** 가장 나쁜 등급 */
        val worst: String get() = events.maxByOrNull { rank(it.risk) }?.risk ?: "LOW"
        /** 보호자 미확인 건수 */
        val todo get() = events.count { !it.done }
        /** 가장 많이 나온 유형 (위험 검색 / 악성 도메인 / APK 설치) */
        val kind: String get() = events.groupingBy { it.typeLabel }.eachCount()
            .maxByOrNull { it.value }?.key ?: Family.KIND_DOMAIN

        /**
         * 판단 근거 — 같은 이유는 한 줄로 합쳐 횟수 집계. 위험한 것부터.
         * 이유 빈 기록은 유형 이름으로 대체.
         */
        class Reason(val risk: String, val kind: String, val text: String, val times: Int)

        val reasons: List<Reason>
            get() = events
                .groupBy { Triple(it.risk, it.typeLabel, it.reason.ifBlank { it.typeLabel }) }
                .map { (k, v) -> Reason(k.first, k.second, k.third, v.size) }
                .sortedWith(compareByDescending<Reason> { rank(it.risk) }.thenByDescending { it.times })
    }

    /**
     * 이번 주 기록의 곳별 묶음. 주소 없는 기록(검색 결과에서 발견)은
     * 유형 이름으로 묶음 — 버리면 검색에서 난 위험이 화면에서 사라짐.
     * 정렬: 많이 난 곳 → 같은 수면 더 위험한 곳.
     */
    fun sites(week: List<Family.Event>): List<Site> =
        week.groupBy { it.host.ifBlank { it.typeLabel } }
            .map { (name, list) -> Site(name, list) }
            .sortedWith(compareByDescending<Site> { it.count }.thenByDescending { rank(it.worst) })

    /** 두 번 이상 나온 곳 중 가장 많이 나온 곳 (시안 11G-2 '반복해서 위험이 발생한 출처') */
    fun repeated(week: List<Family.Event>): Site? =
        week.filter { it.host.isNotBlank() }
            .groupBy { it.host }
            .map { (name, list) -> Site(name, list) }
            .filter { it.count >= 2 }
            .maxWithOrNull(compareBy<Site> { it.count }.thenBy { rank(it.worst) })
}
