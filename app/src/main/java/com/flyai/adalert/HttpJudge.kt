package com.flyai.adalert

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 판정 서버(server/main.py) 호출 [Judge].
 *
 * 앱의 역할: 주소 하나 POST → {risk, reason, advice} 수신이 전부.
 * 페이지 열기·LLM 호출·API 키 보유는 전부 서버 쪽 —
 * 악성 페이지를 사용자 폰이 직접 요청하지 않음, 키는 APK에 한 글자도 없음.
 *
 * ## 응답은 NDJSON 두 줄까지 (`stream: true`)
 * 서버가 등급을 확정하는 즉시 `{"early":true,"risk":"LOW","site":…}` 한 줄, 설명 문장까지 다 쓰면
 * 결론 한 줄. 저위험은 쉴드를 걷는 것이 전부라 첫 줄에서 걷고([onEarly]) 둘째 줄로 기록·캐시.
 * 중·고위험·캐시·실패는 결론 한 줄만. 한 줄씩 읽으므로 서버가 줄을 흘리는 대로 도착
 *
 * 실패(네트워크 없음·시간 초과·서버 오류) → [Risk.UNKNOWN].
 * 차단·열기 결정은 여기서 없음 — UNKNOWN은 쉴드에서
 * "확인하지 못했습니다 + 선택지"로 표시, 사용자가 결정.
 */
class HttpJudge : Judge {

    companion object {
        private const val TAG = "HttpJudge"

        /** 판정 서버 주소 (애저 서울, adalert-rg). 배포·연결 방식 변경 시 이 한 줄만 수정 */
        private const val BASE_URL = "https://adalert-judge.azurewebsites.net"

        /**
         * 서버 대기 상한. 쉴드의 12초 타임아웃보다 안쪽 필수 —
         * 여기서 늦게 실패하면 UNKNOWN 화면조차 쉴드 타임아웃에 선점당함.
         */
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val READ_TIMEOUT_MS = 10_000   // 에이전트 판정(서버 예산 7.5초) 대기 가능 길이

        /**
         * 보호자 폰 알림 전송을 서버에 요청.
         *
         * **보낼 주소는 앱이 실어 보냄.** 서버가 파이어베이스에서 주소를 찾는 구조면
         * 서버가 모든 가족의 기록을 읽을 자격을 갖게 됨 → 그 자격 자체를 만들지 않음.
         * 실패 시 조용히 무시 — 기록은 이미 파이어베이스에 저장, 보호자가 앱을 열면
         * 알림 없이도 표시.
         *
         * 글은 조각으로 보냄. 서버가 문장을 합치면 보호자 폰이 그 결과를 그대로 그릴
         * 수밖에 없어, 알림 모양을 바꾸려면 서버를 배포해야 함. 조각으로 넘기면 모양은
         * [PushService]의 몫으로 남음.
         *
         * @param tag 알림 줄의 이름표. 같은 이름표면 새 줄이 아니라 **그 줄이 갱신**
         * @param eventId 이 알림이 가리키는 기록. 비어 있으면 보호자 폰이 목록을 연다
         */
        fun notify(
            token: String, tag: String, title: String,
            line1: String, line2: String, eventId: String,
        ) {
            Thread {
                runCatching {
                    val conn = URL("$BASE_URL/notify").openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.connectTimeout = CONNECT_TIMEOUT_MS
                    conn.readTimeout = READ_TIMEOUT_MS
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use {
                        it.write(
                            JSONObject()
                                .put("token", token)
                                .put("tag", tag)
                                .put("title", title)
                                .put("line1", line1)
                                .put("line2", line2)
                                .put("eventId", eventId)
                                .toString().toByteArray()
                        )
                    }
                    Log.i(TAG, "알림 요청 -> ${conn.inputStream.bufferedReader().readText()}")
                }.onFailure { Log.w(TAG, "알림 요청 실패: ${it.javaClass.simpleName}") }
            }.start()
        }
    }

    /**
     * 요청마다 스레드. 예전엔 하나였는데(클릭당 판정 1회 전제) 지금은 겹침 —
     * 클릭 즉시 요청과 도착지 재요청([AdDetectService.ClickJudge]), 20초마다 도는 [peek]가
     * 한 줄에 서면 앞 요청(최대 10초)이 끝날 때까지 뒤 요청이 **출발도 못 함**. 실측(2026-08-23)
     * 옛 서버 17초 판정 뒤에 선 요청이 그만큼 늦게 나감
     */
    private val executor = Executors.newCachedThreadPool()
    private val main = Handler(Looper.getMainLooper())

    override fun judge(url: String, clickUrl: String?, onEarly: ((Verdict) -> Unit)?, done: (Verdict) -> Unit) {
        executor.execute {
            val verdict = call(url, clickUrl) { early -> main.post { onEarly?.invoke(early) } }
            main.post { done(verdict) }
        }
    }

    private fun call(url: String, clickUrl: String?, early: (Verdict) -> Unit): Verdict {
        return try {
            val conn = URL("$BASE_URL/judge").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/x-ndjson")
            conn.outputStream.use {
                val body = JSONObject().put("url", url).put("stream", true)
                // 트래커 href 동봉 — 서버가 광고 식별키를 뽑아 판정과 같이 저장
                if (clickUrl != null) body.put("click_url", clickUrl)
                it.write(body.toString().toByteArray())
            }

            // 한 줄씩 — 선행 통보 줄은 즉시 전달, 마지막 줄이 결론
            var last: JSONObject? = null
            conn.inputStream.bufferedReader().use { reader: BufferedReader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    val json = JSONObject(line)
                    if (json.optBoolean("early")) {
                        if (json.optString("risk") == "LOW") {
                            early(Verdict(Risk.LOW, "", "",
                                site = json.optString("site", "").takeIf { it.isNotEmpty() },
                                at = json.optString("at", "").takeIf { it.isNotEmpty() }))
                        }
                    } else {
                        last = json
                    }
                }
            }
            val json = last ?: JSONObject()
            val risk = when (json.optString("risk")) {
                "LOW" -> Risk.LOW
                "MEDIUM" -> Risk.MEDIUM
                "HIGH" -> Risk.HIGH
                else -> Risk.UNKNOWN
            }
            Verdict(
                risk = risk,
                reason = json.optString("reason", "안전한지 확인하지 못했습니다."),
                advice = json.optString("advice", "잘 모르겠으면 돌아가세요."),
                site = json.optString("site", "").takeIf { it.isNotEmpty() },
                at = json.optString("at", "").takeIf { it.isNotEmpty() },
                type = json.optString("type", "none"),
            )
        } catch (e: Exception) {
            Log.w(TAG, "판정 서버 호출 실패: ${e.javaClass.simpleName}: ${e.message}")
            Verdict(Risk.UNKNOWN, "안전한지 확인하지 못했습니다.", "잘 모르겠으면 돌아가세요.")
        }
    }

    /**
     * 판정 완료 사이트들의 위험도 **조회 전용** (서버 /peek — LLM·페이지 열기 없음).
     * 화면에 뜬 광고의 테두리 색 사전 결정용.
     *
     * [done]은 **이 실행기의 스레드에서** 호출 — 호출 측이 자기 스레드로 전환 필요.
     * 실패 시 빈 맵. 사전 표시는 없어도 기능이 죽지 않는 장식 → 조용한 실패 허용.
     */
    fun peek(sites: List<String>, done: (Map<String, String>) -> Unit) {
        executor.execute {
            val out = try {
                val conn = URL("$BASE_URL/peek").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use {
                    it.write(
                        JSONObject().put("urls", org.json.JSONArray(sites)).toString().toByteArray()
                    )
                }
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                buildMap {
                    for (s in sites) {
                        val r = json.optString(s, "")
                        if (r == "LOW" || r == "MEDIUM" || r == "HIGH") put(s, r)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "peek 실패: ${e.javaClass.simpleName}")
                emptyMap()
            }
            done(out)
        }
    }
}
