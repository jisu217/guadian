package com.flyai.adalert

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 어르신 화면의 숫자 — 홈(E01·H1) '오늘의 보호'와 안심 요약(11U)의 공통 셈법.
 *
 * 숫자 두 종류.
 *  - **막아준 일**: 가족 기록([Family.Event]) 집계. 어르신 폰이 올린 것을 서버에서 읽음.
 *  - **광고 표시**: 기록에 없음 — 파란 테두리는 위험이 아니라 서버 미전송.
 *    이 폰에만 날짜별 집계 — [AdDetectService]가 테두리를 새로 그릴 때 [countAd] 호출.
 *
 * '이번 주' = [ReportActivity]와 같은 **최근 7일** (달력 주 아님).
 */
object SeniorStats {

    private const val PREFS = "senior_stats"
    private const val KEY_ADS = "ads:"
    private val day = SimpleDateFormat("yyyyMMdd", Locale.US)

    /** 기록의 위험 유형별 분류. 유형마다 문구가 달라 분리 */
    class Tally(
        /** 위험 검색·악성 도메인 — "위험한 곳으로 갈 뻔한 일" */
        val redirected: Int,
        /** APK 설치 — "위험한 설치" */
        val apk: Int,
        /**
         * 아직 아무도 확인하지 않은 일. 보호자가 기록을 열어 「확인 완료」를 누르면 준다
         * ([Family.markDone]). 어르신 홈이 파란 카드(H1-B)와 빨간 카드(H1-R)를 가르는 값 —
         * 같은 하루라도 확인이 끝났으면 안심시키고, 남았으면 보러 가게 한다.
         */
        val unchecked: Int = 0,
    ) {
        val total get() = redirected + apk
    }

    /** [from] 이후 기록의 유형별 집계 */
    fun tally(events: List<Family.Event>, from: Date): Tally {
        val hit = events.filter { it.at?.after(from) == true }
        val apk = hit.count { it.typeLabel == Family.KIND_APK }
        return Tally(hit.size - apk, apk, hit.count { !it.done })
    }

    /** 오늘 0시 */
    fun todayStart(): Date = startOf(0)

    /** 6일 전 0시 — 오늘까지 7일 */
    fun weekStart(): Date = startOf(6)

    private fun startOf(daysAgo: Int): Date = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, -daysAgo)
    }.time

    // ── 광고 표시 카운터 ──────────────────────────────────────────────────────

    /** 광고 테두리 **새로** 그릴 때 1회. 오늘 칸 +1, 7일 지난 칸 삭제 */
    fun countAd(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = KEY_ADS + day.format(Date())
        val keep = (0..6).map { KEY_ADS + day.format(startOf(it)) }.toSet()
        prefs.edit().apply {
            putInt(key, prefs.getInt(key, 0) + 1)
            prefs.all.keys.filter { it.startsWith(KEY_ADS) && it !in keep }.forEach { remove(it) }
        }.apply()
    }

    fun adsToday(ctx: Context): Int = adsSince(ctx, 0)

    fun adsThisWeek(ctx: Context): Int = adsSince(ctx, 6)

    private fun adsSince(ctx: Context, daysAgo: Int): Int {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return (0..daysAgo).sumOf { prefs.getInt(KEY_ADS + day.format(startOf(it)), 0) }
    }
}
