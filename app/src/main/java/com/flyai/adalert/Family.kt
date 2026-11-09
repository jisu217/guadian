package com.flyai.adalert

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.messaging.FirebaseMessaging

/**
 * 가족 계정과 프로필, 그리고 보호자에게 보여줄 기록.
 *
 * ## 계정이 가족 단위인 이유
 * 기기끼리 QR로 짝을 짓는 방식이 아니라 **계정 하나 안에 프로필 둘** — 두 폰이 같은 계정으로
 * 로그인한 뒤 각자 자기 프로필 선택. 어르신 폰의 조작이 "로그인 한 번, 고르기 한 번"으로 끝나는 구조.
 * QR 방식은 두 폰을 동시에 들고 카메라를 맞춰야 해서 도와줄 사람이 옆에 없으면 진행 불가.
 *
 * ## 남기는 것과 남기지 않는 것
 * 광고 판정은 판정 서버 담당. 여기에는 **무슨 일이 있었는지만** 누적.
 * Firestore 사용 이유는 단 하나 — 보호자 화면의 새로고침 없는 갱신.
 *
 * 주소는 사람이 어디를 다녔는지 그대로 드러내므로 **호스트까지만** 저장
 * (`blog.naver.com` 까지, 그 뒤 경로는 폐기). 보호자에게 필요한 것은
 * "위험한 곳에 갔다"이지 "무엇을 봤다"가 아님.
 */
object Family {

    private const val TAG = "Family"
    private const val PREFS = "family"

    class Profile(val id: String, val name: String, val role: String) {
        val isSenior get() = role == ROLE_SENIOR
    }

    const val ROLE_SENIOR = "senior"
    const val ROLE_GUARDIAN = "guardian"

    private val auth get() = FirebaseAuth.getInstance()
    private val db get() = FirebaseFirestore.getInstance()

    /** 로그인한 가족 계정. 이 값이 곧 가족의 열쇠 */
    val familyId: String? get() = auth.currentUser?.uid

    /** 화면에 보여줄 가족 코드 (여섯 자리 숫자) */
    val code: String? get() = auth.currentUser?.email?.removePrefix("f")?.substringBefore('@')

    /** 이 폰에서 가족을 만들었다면 그때의 비밀번호. 참여한 폰에서는 없음 */
    fun pin(ctx: Context): String? = prefs(ctx).getString("pin", null)

    // ── 로그인 ────────────────────────────────────────────────────────────────
    //
    // 이메일·비밀번호 입력 없음. 어르신 폰에서 이메일 주소를 오타 없이 치는 것부터가 벽이고,
    // 가족 공용 계정이라 개인 이메일을 넣을 이유도 없음. 대신 **숫자만** 입력:
    // 여섯 자리 가족 코드(앱이 생성) + 네 자리 비밀번호(가족이 결정).
    //
    // 파이어베이스는 이메일 형식 필수 → 코드로 주소 생성 —
    // 코드 483920이면 `f483920@adalert.app`. 사용자에게는 노출 없음.

    private fun emailOf(code: String) = "f$code@adalert.app"

    /** 파이어베이스 최소 길이(6자)를 넘기려고 코드와 결합. 외울 것은 네 자리뿐 */
    private fun passOf(code: String, pin: String) = "$code-$pin"

    /**
     * 가족 신규 생성(보호자 폰에서 한 번).
     *
     * **아이디와 비밀번호는 앱이 정해서 안내.** 사용자가 정하면 그 자리에서 하나를 지어내야 하고,
     * 나중에 어르신 폰에 넣을 때 무엇이었는지 다시 혼동. 화면에 적힌 두 숫자를 그대로
     * 옮겨 적기만 하는 편이 유리.
     * 같은 코드를 다른 가족이 쓰고 있을 수 있으므로 몇 번 재추첨.
     */
    fun createFamily(ctx: Context, done: (code: String?, pin: String?, error: String?) -> Unit) {
        val pin = (1000..9999).random().toString()
        fun attempt(left: Int) {
            val code = (100000..999999).random().toString()
            auth.createUserWithEmailAndPassword(emailOf(code), passOf(code, pin))
                .addOnSuccessListener {
                    // 만든 폰에 비밀번호 보관. 연결 코드 화면을 다시 열었을 때
                    // "그때 뭐였더라"가 되면 어르신 폰에 넣을 방법이 없음
                    prefs(ctx).edit().putString("pin", pin).apply()
                    stamp(code)
                    done(code, pin, null)
                }
                .addOnFailureListener { e ->
                    val taken = e.message?.contains("already in use") == true
                    if (taken && left > 0) attempt(left - 1) else done(null, null, message(e))
                }
        }
        attempt(4)
    }

    /**
     * 가족 문서에 **사람이 읽을 수 있는 표시** 기록.
     *
     * 문서 이름 = 로그인 계정의 uid — 보안 규칙이 `families/{문서이름} == 내 uid`로
     * 남의 가족을 막고 있어 변경 불가. 그런데 이 문서에 필드가 하나도 없어
     * 콘솔에서는 뜻 모를 글자 스무 자로만 표시(기울임꼴 = 빈 문서).
     * 하위 컬렉션만 있는 문서라도 필드 한 줄이면 어느 가족인지 바로 식별 가능.
     *
     * 필드 이름이 한글인 이유: **읽는 곳이 콘솔뿐**. 코드에서 이 값으로 검색하지 않음 —
     * 검색하게 되면 그때 영문 이름으로 변경 필요.
     */
    private fun stamp(code: String) {
        val id = familyId ?: return
        db.collection("families").document(id)
            .set(
                mapOf(
                    "가족아이디" to code,
                    "만든날짜" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                ),
                com.google.firebase.firestore.SetOptions.merge()
            )
            .addOnFailureListener { Log.w(TAG, "가족 표시를 남기지 못했다: $it") }
    }

    /** 기존 가족 참여(어르신 폰). 코드와 비밀번호만 필요 */
    fun joinFamily(code: String, pin: String, done: (String?) -> Unit) {
        if (code.length != 6) return done("가족 코드는 여섯 자리 숫자입니다.")
        if (pin.length < 4) return done("비밀번호 네 자리를 적어 주세요.")
        auth.signInWithEmailAndPassword(emailOf(code), passOf(code, pin))
            // 참여 시에도 한 번 기록. 이 표시가 생기기 전에 만든 가족은 문서가 비어 있는데,
            // 어르신 폰이 들어오는 순간 채워짐(merge라 덮어쓰기 없음)
            .addOnSuccessListener { stamp(code); done(null) }
            .addOnFailureListener { done(message(it)) }
    }

    fun signOut(ctx: Context) {
        auth.signOut()
        prefs(ctx).edit().clear().apply()
    }

    /**
     * 파이어베이스 오류 → 어르신도 읽을 수 있는 말로 변환.
     * 원문은 영어 기술 문구라 그대로 띄우면 무슨 일인지 파악 불가
     */
    private fun message(e: Exception): String {
        val m = e.message ?: return "연결에 실패했습니다. 잠시 뒤 다시 해보세요."
        return when {
            "password is invalid" in m || "credential is incorrect" in m ->
                "코드나 비밀번호가 맞지 않습니다."
            "no user record" in m -> "그런 가족 코드가 없습니다."
            "network" in m.lowercase() -> "인터넷 연결을 확인해 주세요."
            else -> "문제가 생겼습니다: $m"
        }
    }

    // ── 프로필 ────────────────────────────────────────────────────────────────

    private fun profilesRef() = db.collection("families")
        .document(familyId ?: "-").collection("profiles")

    fun profiles(done: (List<Profile>) -> Unit) {
        if (familyId == null) return done(emptyList())
        profilesRef().get()
            .addOnSuccessListener { snap ->
                done(snap.documents.map {
                    Profile(
                        it.id,
                        it.getString("name") ?: "이름 없음",
                        it.getString("role") ?: ROLE_SENIOR
                    )
                })
            }
            .addOnFailureListener { Log.w(TAG, "프로필 조회 실패: $it"); done(emptyList()) }
    }

    /**
     * 01-2([NameActivity])에서 받아 둔 이름으로 이 폰의 프로필을 세운다.
     *
     * 그 이름은 여태 이 폰의 [NameActivity.PREFS]에만 있었고 아무도 읽지 않았다 —
     * 프로필을 만드는 곳이 「누가 쓰는 폰인가요?」([ProfileActivity])뿐이었는데
     * 보호자도 어르신도 그 화면을 지나지 않는다. 그래서 홈이 「안녕하세요」에서 멈추고
     * 이름을 부르지 못했다.
     *
     * 가족에 들어간 **직후**에 부른다. 그 전에는 [familyId]가 없어 쓸 곳이 없다.
     * 이미 고른 프로필이 있으면 그대로 둔다 — 두 번 만들면 같은 사람이 둘이 된다.
     */
    fun claimProfile(ctx: Context, role: String, done: () -> Unit = {}) {
        if (current(ctx) != null) return done()
        val name = NameActivity.savedName(ctx as Activity)
        if (name.isBlank()) return done()
        addProfile(name, role) { p ->
            if (p != null) select(ctx, p)
            done()
        }
    }

    fun addProfile(name: String, role: String, done: (Profile?) -> Unit) {
        if (familyId == null) return done(null)
        val data = mapOf("name" to name, "role" to role, "createdAt" to FieldValue.serverTimestamp())
        profilesRef().add(data)
            .addOnSuccessListener { done(Profile(it.id, name, role)) }
            .addOnFailureListener { Log.w(TAG, "프로필 만들기 실패: $it"); done(null) }
    }

    // ── 이 폰이 누구인가 ──────────────────────────────────────────────────────

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * 이 폰에서 고른 프로필 기억. 앱을 다시 열 때마다 고르게 하면
     * 어르신에게는 그것부터가 벽 — 한 번 고르면 계속 그 프로필
     */
    fun select(ctx: Context, p: Profile) {
        prefs(ctx).edit()
            .putString("profileId", p.id)
            .putString("profileName", p.name)
            .putString("role", p.role)
            .apply()
        registerToken(ctx)
    }

    fun current(ctx: Context): Profile? {
        val pr = prefs(ctx)
        val id = pr.getString("profileId", null) ?: return null
        return Profile(
            id,
            pr.getString("profileName", "") ?: "",
            pr.getString("role", ROLE_SENIOR) ?: ROLE_SENIOR
        )
    }

    /**
     * 이 폰의 알림 주소(FCM 토큰)를 프로필에 기록.
     * 지금은 미사용 — 보호자 폰에 알림을 보내려면 서버가 이 값을 알아야 함
     */
    private fun registerToken(ctx: Context) {
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { saveToken(ctx, it) }
            .addOnFailureListener { Log.w(TAG, "토큰 조회 실패: $it") }
    }

    /** 알림 주소를 이 폰의 프로필에 기록 ([PushService]가 새 토큰을 받았을 때도 호출) */
    fun saveToken(ctx: Context, token: String) {
        val p = current(ctx) ?: return
        if (familyId == null) return
        profilesRef().document(p.id)
            .update(mapOf("fcmToken" to token, "tokenAt" to FieldValue.serverTimestamp()))
            .addOnFailureListener { Log.w(TAG, "토큰 저장 실패: $it") }
    }

    // ── 기록 ──────────────────────────────────────────────────────────────────

    private fun eventsRef() = db.collection("families")
        .document(familyId ?: "-").collection("events")

    /**
     * 위험한 곳 도착 또는 위험한 광고 클릭 차단 기록. **어르신 프로필일 때만** 저장.
     * 미로그인 시 아무 동작 없음 — 계정 없이도 앱 본래 기능은 그대로 동작
     */
    fun logEvent(
        ctx: Context,
        risk: String,
        reason: String,
        host: String?,
        type: String = "none",
        blocked: Boolean = false,
        /**
         * 보호자에게 보낼 문안. **null이면 기록만 남기고 알리지 않는다** —
         * 주의 판정처럼 "아직 알릴 일이 아닌" 기록이 있다 (spec.md Part 03).
         */
        alert: Alert? = null,
        /**
         * 억제·횟수를 세는 단위. 비우면 호스트를 쓴다. 설치 파일은 호스트가 아니라
         * **파일 이름**이 사건을 가른다
         */
        alertKey: String? = null,
    ) {
        val p = current(ctx) ?: return
        if (!p.isSenior || familyId == null) return
        val data = mapOf(
            "at" to FieldValue.serverTimestamp(),
            "risk" to risk,
            "type" to type,                  // 사칭·개인정보 요구 같은 유형
            "reason" to reason,
            "host" to (host ?: ""),          // 호스트까지만 — 경로 저장 없음
            "blocked" to blocked,
            "profileId" to p.id,
            "profileName" to p.name
        )
        // 문서 이름을 **미리** 받아 둔다 — 저장이 끝나기를 기다리지 않고 알림에 실어야
        // 보호자가 알림에서 바로 그 기록을 열 수 있다 ([PushService])
        val doc = eventsRef().document()
        doc.set(data)
            .addOnFailureListener { Log.w(TAG, "기록 실패: $it") }

        // 보호자 알림. **보낼 주소를 여기서 읽어 서버에 함께 전달** —
        // 서버가 파이어베이스를 읽게 하면 모든 가족의 기록을 볼 자격이 생기므로,
        // 그 자격을 만들지 않으려고 방향을 뒤집은 구조.
        //
        // 알릴지 말지는 **호출부가 문안을 붙였는지**로 정해진다. 등급으로 가르지 않는 이유 —
        // 같은 주의 등급이라도 안내만 본 것과 안내를 보고도 들어간 것은 다른 사건이다.
        if (alert != null) {
            notifyGuardians(p.name, alert, alertKey ?: host.orEmpty(), doc.id, repeatAlerts(ctx))
        }
    }

    // ── 보호자 알림 ───────────────────────────────────────────────────────────

    /**
     * 보호자에게 나가는 알림의 종류. spec.md Part 03의 사건 표와 1:1.
     *
     * 등급([logEvent]의 risk)과 따로 두는 이유 — 같은 고위험이라도 "설치 파일을 받았다"와
     * "설치를 막았다"는 보호자가 할 일이 다르고, 같은 주의 등급이라도 안내만 본 것과
     * 안내를 보고도 들어간 것은 다른 사건이다. 문안을 등급에서 뽑으면 그 구분이 사라진다.
     */
    enum class Alert {
        /** 위험 사이트 접속 차단 — 광고 마스크·가드·착지 판정 */
        DOMAIN,

        /** 위험할 수 있는 설치 파일을 내려받는 중 */
        APK_DOWNLOAD,

        /** 설치 화면까지 도달 — 막을 수 있는 마지막 자리 */
        APK_INSTALL,

        /** 위험으로 분류된 검색 결과를 눌렀다 (관문이 막음) */
        SEARCH,

        /** 주의 안내를 보고도 「그래도 보기」로 들어갔다 */
        PROCEED,
    }

    private const val HOUR_MS = 60 * 60 * 1000L
    private const val DAY_MS = 24 * HOUR_MS

    /** 한 시간 안에 같은 곳에서 이만큼 반복되면 요약 한 줄을 더 보낸다 (spec.md Part 03) */
    private const val REPEAT_SUMMARY_AT = 4

    /** 시간당 **새** 알림 상한. 넘으면 개별 알림 대신 폭주 요약 */
    private const val HOURLY_CAP = 3

    /** 폭주 요약을 다시 보내기까지의 최소 간격 */
    private const val FLOOD_MS = 10 * 60 * 1000L

    /**
     * 억제 창 — 같은 사건이 이 시간 안에 또 오면 **없던 일로 본다.**
     *
     * 값이 종류마다 다른 것은 사건이 반복되는 속도가 달라서다. 마스크는 손가락이 바로
     * 다시 닿고(10초), 설치 파일은 진행률마다 알림이 갱신되며(30초), 검색 결과는
     * 관문 시트를 닫고 다시 누르기까지 시간이 걸린다(1분).
     */
    private fun holdMs(alert: Alert) = when (alert) {
        Alert.DOMAIN -> 10 * 1000L
        Alert.SEARCH -> 60 * 1000L
        Alert.APK_DOWNLOAD, Alert.APK_INSTALL -> 30 * 1000L
        Alert.PROCEED -> 30 * 1000L
    }

    /**
     * 횟수를 **이어 세는** 창. 이 시간이 지나 다시 일어나면 그때부터 "1회"다.
     *
     * 주의만 하루인 것은 spec.md가 그렇게 정했기 때문이다 — 같은 곳을 계속 이용하는
     * 흐름은 하루 단위로 봐야 보이고, 차단은 그날 안에서도 한 시간이면 충분하다.
     */
    private fun countMs(alert: Alert) = if (alert == Alert.PROCEED) DAY_MS else HOUR_MS

    /**
     * 알림 줄의 이름표. 같은 이름표면 보호자 폰에서 **줄이 갱신**되므로 종류마다
     * 하나씩만 남는다. 통마다 새 줄이면 같은 내용이 트레이에 그대로 쌓인다.
     */
    private fun tagOf(alert: Alert) = when (alert) {
        Alert.DOMAIN -> "domain"
        Alert.APK_DOWNLOAD -> "apk-download"
        Alert.APK_INSTALL -> "apk-install"
        Alert.SEARCH -> "search"
        Alert.PROCEED -> "proceed"
    }

    /** 알림 한 통의 글. [PushService]가 제목 한 줄 + 본문 두 줄로 그린다 */
    private class Text(val title: String, val line1: String, val line2: String = "")

    /** spec.md Part 03의 문안 그대로. 여기 말고 다른 데서 짓지 않는다 */
    private fun textOf(alert: Alert, who: String) = when (alert) {
        Alert.DOMAIN -> Text(
            "위험한 사이트 접속을 차단했어요",
            "$who 님이 접속하려던 페이지가 위험 사이트로 확인되어 GuADian이 접속을 막았습니다.",
            "별도의 조치는 필요하지 않습니다.",
        )
        Alert.APK_DOWNLOAD -> Text(
            "위험한 앱 다운로드를 감지했어요",
            "$who 님의 휴대폰에서 위험할 수 있는 APK 파일 다운로드가 감지되었습니다.",
            "GuADian이 설치로 이어지지 않도록 보호하고 있습니다.",
        )
        Alert.APK_INSTALL -> Text(
            "위험한 앱 설치를 막았어요",
            "$who 님의 휴대폰에서 위험할 수 있는 앱 설치 시도가 감지되어 GuADian이 차단했습니다.",
        )
        Alert.SEARCH -> Text(
            "위험한 검색 결과를 막았어요",
            "$who 님이 위험 사이트로 분류된 검색 결과를 눌렀지만 GuADian이 접속을 차단했습니다.",
        )
        Alert.PROCEED -> Text(
            "주의가 필요한 사이트에 접속했어요",
            "한 번 확인해 주세요.",
        )
    }

    /**
     * 반복일 때 제목이 바뀌는 것은 주의뿐이다.
     *
     * 차단은 몇 번을 눌렀든 "막았다"가 사실이라 제목이 그대로고 숫자만 붙는다. 주의는
     * 다르다 — 두 번째부터는 "안내를 봤는데도 또 갔다"가 그 순간의 사실이다.
     */
    private fun repeatText(alert: Alert, who: String) =
        if (alert == Alert.PROCEED) Text(
            "주의 안내 후에도 사이트 접속을 시도했어요",
            "한 번 확인해 주세요.",
        ) else textOf(alert, who)

    /** 같은 곳에서 반복될 때의 요약. 낱낱 대신 **반복한다는 사실**을 말한다 */
    private fun repeatSummary(alert: Alert, who: String, count: Int) =
        if (alert == Alert.PROCEED) Text(
            "주의 사이트 이용이 반복되고 있어요",
            "$who 님이 같은 사이트에서 주의 안내를 여러 번 확인했지만 계속 이용했습니다.",
            "최근 한 시간 동안 ${count}회 · 필요하면 함께 확인해 주세요",
        ) else Text(
            "같은 위험 사이트 접근이 반복되고 있어요",
            "$who 님이 최근 한 시간 동안 같은 위험 사이트에 여러 번 접근했습니다.",
            "총 ${count}회 · GuADian이 모두 차단했어요 · 눌러서 자세히 확인해 주세요",
        )

    /** 여기저기서 한꺼번에 몰릴 때의 요약. 위 요약과 달리 **곳을 가리지 않는다** */
    private fun floodSummary(who: String, count: Int) = Text(
        "위험 알림이 잦아요",
        "$who 님 폰에서 최근 한 시간 동안 위험한 일이 여러 번 있었습니다.",
        "총 ${count}회 · 눌러서 확인해 주세요",
    )

    /** 지문별 최근 발생 시각. 하루까지만 보관 */
    private val hits = mutableMapOf<String, MutableList<Long>>()

    /** 새 알림을 보낸 시각들. 시간당 상한 판정용 — **갱신은 세지 않는다** */
    private val sentAt = mutableListOf<Long>()

    private var floodAt = 0L
    private var floodCount = 0

    /** 보낼 것이 정해진 알림 한 통 */
    private class Plan(val tag: String, val text: Text, val count: Int)

    /**
     * 보호자 프로필들의 알림 주소를 읽어 서버에 발송 요청.
     *
     * **중복 억제는 여기 한 자리.** 탐지 쪽에도 창이 여럿 있지만 그쪽 기준은 "화면에
     * 경고를 다시 그릴 것인가"라 서로 다르다.
     */
    private fun notifyGuardians(
        who: String, alert: Alert, key: String, eventId: String, repeatOn: Boolean,
    ) {
        val plan = plan(who, alert, key, repeatOn) ?: return
        profilesRef().whereEqualTo("role", ROLE_GUARDIAN).get()
            .addOnSuccessListener { snap ->
                val tokens = snap.documents.mapNotNull { it.getString("fcmToken") }
                if (tokens.isEmpty()) return@addOnSuccessListener
                val title =
                    if (plan.count > 1) "${plan.text.title} · ${plan.count}번" else plan.text.title
                // 묶인 줄은 **마지막 기록**을 가리킨다 — 여러 건을 한 줄로 접었으니 하나를
                // 골라야 하고, 그중 보호자가 먼저 볼 것은 방금 일어난 쪽이다
                tokens.forEach {
                    HttpJudge.notify(it, plan.tag, title, plan.text.line1, plan.text.line2, eventId)
                }
            }
            .addOnFailureListener { Log.w(TAG, "보호자 조회 실패: $it") }
    }

    /**
     * 이번 발생을 **어떤 줄로 내보낼지** 정한다. null이면 내보내지 않는다.
     *
     * 순서가 규칙 그대로다 — 억제 창 안이면 없던 일, 한 시간 안에 [REPEAT_SUMMARY_AT]번을
     * 채웠으면 반복 요약, 그 전의 재발이면 같은 줄의 숫자만, 처음이면 새 줄. 새 줄만
     * 시간당 상한([HOURLY_CAP])에 걸린다 — 갱신까지 세면 반복이 잦을수록 오히려 조용해진다.
     */
    @Synchronized
    private fun plan(who: String, alert: Alert, key: String, repeatOn: Boolean): Plan? {
        val now = SystemClock.uptimeMillis()
        val fp = "${alert.name}|$key"
        val list = hits.getOrPut(fp) { mutableListOf() }
        list.removeAll { now - it > DAY_MS }

        val last = list.lastOrNull()
        if (last != null && now - last < holdMs(alert)) {
            Log.i(TAG, "알림 억제 — 같은 사건 재발 $fp")
            return null
        }
        list.add(now)

        val hour = list.count { now - it <= HOUR_MS }
        val count = list.count { now - it <= countMs(alert) }

        // 같은 곳에서 한 시간 안에 반복 — 낱낱 대신 반복 요약 한 줄로 갈아탄다.
        // 보호자가 「반복 위험 알림」을 껐으면 이 줄만 접는다 ([AlertSettingsActivity])
        if (hour >= REPEAT_SUMMARY_AT) {
            if (!repeatOn) {
                Log.i(TAG, "반복 요약 생략 — 보호자가 끔")
                return null
            }
            Log.i(TAG, "반복 요약 — $fp ${hour}회")
            return Plan("${tagOf(alert)}-repeat", repeatSummary(alert, who, hour), 1)
        }

        // 창 안의 재발 — 새 줄이 아니라 이미 보낸 줄의 숫자만 올린다
        if (count > 1) return Plan(tagOf(alert), repeatText(alert, who), count)

        // 새 사건. 여기만 시간당 상한에 걸린다
        sentAt.removeAll { now - it > HOUR_MS }
        if (sentAt.size >= HOURLY_CAP) {
            floodCount++
            if (floodAt != 0L && now - floodAt < FLOOD_MS) return null
            floodAt = now
            Log.i(TAG, "알림 상한 초과 — 폭주 요약 (${floodCount}건)")
            return Plan("flood", floodSummary(who, floodCount), 1)
        }
        sentAt.add(now)
        if (sentAt.size == 1) {
            floodAt = 0L
            floodCount = 0
        }
        return Plan(tagOf(alert), textOf(alert, who), 1)
    }

    // ── 보호 설정 ─────────────────────────────────────────────────────────────

    /**
     * 가족이 함께 쓰는 보호 설정.
     *
     * ## 왜 가족 단위인가
     * 값을 정하는 사람과 값이 쓰이는 폰이 다르다. 「반복 위험 알림」은 보호자가 정하지만
     * 발송 여부는 **어르신 폰**이 판단하고([logEvent]), 「투터치」는 보호자 화면에도
     * 어르신 화면에도 있다. 각자 자기 폰에만 저장하면 어느 쪽도 상대의 값을 볼 수 없어,
     * 화면은 꺼져 있는데 동작은 그대로인 상태가 된다 — 고치기 전까지 실제로 그랬다.
     *
     * ## 로컬 거울
     * 읽는 쪽은 늘 **이 폰의 값**을 본다. 판정은 광고를 누른 그 순간에 끝나야 하는데
     * 그때 서버를 기다릴 수는 없다. 스냅샷([watchSettings])이 올 때마다 거울을 갱신하고,
     * 읽기는 거울에서만 한다. 투터치의 거울이 어르신 폰의 개인 설정과 **같은 키**인 것은
     * 일부러다 — 두 화면이 같은 값 하나를 만지게 하려는 것.
     */
    private fun settingsRef() = db.collection("families")
        .document(familyId ?: "-").collection("settings").document("guard")

    /** 거울 키 — 반복 위험 알림 (이 파일의 프로필 저장소) */
    private const val KEY_REPEAT_ALERTS = "repeatAlerts"

    /** 거울 키 — 투터치. [MeActivity]의 개인 설정과 같은 저장소·같은 이름 */
    private const val PREFS_DEVICE = "settings"
    private const val KEY_TWO_TOUCH = "twotouch"

    /** 반복 위험 알림이 켜져 있는지. 기본 켜짐 */
    fun repeatAlerts(ctx: Context) = prefs(ctx).getBoolean(KEY_REPEAT_ALERTS, true)

    /** 투터치가 켜져 있는지. 기본 켜짐 */
    fun twoTouch(ctx: Context) =
        ctx.getSharedPreferences(PREFS_DEVICE, Context.MODE_PRIVATE)
            .getBoolean(KEY_TWO_TOUCH, true)

    private fun mirror(ctx: Context, repeatAlerts: Boolean, twoTouch: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_REPEAT_ALERTS, repeatAlerts).apply()
        ctx.getSharedPreferences(PREFS_DEVICE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_TWO_TOUCH, twoTouch).apply()
    }

    /**
     * 설정 저장 — 거울을 먼저 고치고 가족 문서에 올린다.
     *
     * 순서가 중요하다. 서버 응답을 기다렸다가 거울을 고치면, 저장 직후 화면을 나갔을 때
     * 방금 끈 것이 아직 켜져 있는 것처럼 보인다.
     */
    fun saveSettings(ctx: Context, repeatAlerts: Boolean, twoTouch: Boolean) {
        mirror(ctx, repeatAlerts, twoTouch)
        if (familyId == null) return
        settingsRef()
            .set(mapOf(KEY_REPEAT_ALERTS to repeatAlerts, KEY_TWO_TOUCH to twoTouch))
            .addOnFailureListener { Log.w(TAG, "보호 설정 저장 실패: $it") }
    }

    /** 투터치 한 값만 올린다 — 어르신 폰의 개인 설정 화면에서 바꿨을 때 */
    fun saveTwoTouch(ctx: Context, on: Boolean) =
        saveSettings(ctx, repeatAlerts(ctx), on)

    /**
     * 가족 설정 구독. 값이 바뀔 때마다 거울 갱신.
     *
     * 어르신 폰에서는 접근성 서비스가 붙잡고 있다 — 보호자가 설정을 바꾼 순간 반영되어야
     * 하고, 앱을 다시 켤 때까지 기다리게 하면 껐다고 생각한 것이 계속 동작한다.
     */
    fun watchSettings(ctx: Context, onChange: () -> Unit = {}): ListenerRegistration? {
        if (familyId == null) return null
        return settingsRef().addSnapshotListener { snap, err ->
            if (err != null) {
                Log.w(TAG, "보호 설정 구독 실패: $err")
                return@addSnapshotListener
            }
            if (snap == null || !snap.exists()) return@addSnapshotListener
            val repeat = snap.getBoolean(KEY_REPEAT_ALERTS) ?: true
            val two = snap.getBoolean(KEY_TWO_TOUCH) ?: true
            if (repeat == repeatAlerts(ctx) && two == twoTouch(ctx)) return@addSnapshotListener
            Log.i(TAG, "보호 설정 갱신 — 반복알림=$repeat 투터치=$two")
            mirror(ctx, repeat, two)
            onChange()
        }
    }

    // ── 기록 조회 ─────────────────────────────────────────────────────────────

    class Event(
        val at: java.util.Date?,
        val risk: String,
        val type: String,
        val reason: String,
        val host: String,
        val blocked: Boolean,
        val who: String,
        /** 기록 문서의 이름. 보호자가 "확인 완료"를 누를 때 어느 줄인지 가리키는 용도 */
        val id: String = "",
        /** 보호자가 확인을 끝냈다고 표시한 기록 */
        val done: Boolean = false,
    ) {
        /**
         * 위험 유형 — spec.md가 정한 세 가지. 1. 위험 검색 2. 악성 도메인 3. "APK" 설치.
         * 서버가 붙이는 세부 유형(사칭·결제 유도·…)은 모두 "악성 도메인"으로 통합 —
         * 광고가 데려간 **곳**이 위험했다는 뜻
         */
        val typeLabel: String get() = kindOf(type)

        /**
         * **진행이 실제로 멈췄는가** — 차단 지표는 전부 이것으로 센다 (이슈 #46).
         *
         * [blocked]만 세면 안 되는 이유: 그 필드가 true인 경로는 마스크 탭·가드 차단
         * 둘뿐이라(광고가 이미 위험 표시된 뒤라야 함) 실사용에서 거의 안 쌓이고,
         * 제일 흔한 "착지 후 HIGH 쉴드(돌아가기만)"는 blocked=false로 남아 있었다.
         * 그 결과 리포트가 "차단 0건" 아래 "고위험 N건 자동 차단"을 나란히 보여줬다.
         *
         * 기록 쪽도 이제 HIGH 쉴드·APK를 blocked=true로 남기지만, **이미 쌓인 옛
         * 기록**(HIGH+false)이 그대로라 여기서 보정한다. 검색 탐지(type="search")는
         * 제외 — 막은 것이 아니라 알린 것이라 blocked=false가 의도다.
         */
        val stopped: Boolean get() = blocked || (risk == "HIGH" && type != "search")
    }

    const val KIND_SEARCH = "위험 검색"
    const val KIND_DOMAIN = "악성 도메인"
    const val KIND_APK = "APK 설치"
    val KINDS = listOf(KIND_SEARCH, KIND_DOMAIN, KIND_APK)

    /** 기록의 type 값 → 위험 유형 3종 */
    fun kindOf(type: String): String = when (type) {
        "search" -> KIND_SEARCH
        "apk" -> KIND_APK
        else -> KIND_DOMAIN
    }

    /** 기록 문서 한 줄을 [Event]로. 목록과 상세가 같은 해석을 쓰도록 한 자리에 둔다 */
    private fun toEvent(doc: com.google.firebase.firestore.DocumentSnapshot) = Event(
        doc.getTimestamp("at")?.toDate(),
        doc.getString("risk") ?: "",
        doc.getString("type") ?: "none",
        doc.getString("reason") ?: "",
        doc.getString("host") ?: "",
        doc.getBoolean("blocked") ?: false,
        doc.getString("profileName") ?: "",
        doc.id,
        doc.getBoolean("done") ?: false
    )

    /**
     * 기록 하나를 이름으로 읽는다. 알림에서 상세로 바로 들어갈 때 쓴다
     * ([EventDetailActivity]) — 알림에는 이름만 실려 있고 나머지는 여기서 가져온다.
     */
    fun event(id: String, onResult: (Event?) -> Unit) {
        if (familyId == null || id.isEmpty()) return onResult(null)
        eventsRef().document(id).get()
            .addOnSuccessListener { onResult(if (it.exists()) toEvent(it) else null) }
            .addOnFailureListener {
                Log.w(TAG, "기록 조회 실패: $it")
                onResult(null)
            }
    }

    /**
     * 보호자 화면에서 호출. 서버 재질의 없이 **바뀔 때마다** 콜백 —
     * 어르신 폰에서 방금 일어난 일이 보호자 화면에 그대로 반영
     */
    fun watchEvents(limit: Long = 50, onChange: (List<Event>) -> Unit): ListenerRegistration? {
        if (familyId == null) return null
        return eventsRef()
            .orderBy("at", Query.Direction.DESCENDING)
            .limit(limit)
            .addSnapshotListener { snap, err ->
                if (err != null) { Log.w(TAG, "기록 구독 실패: $err"); return@addSnapshotListener }
                onChange(snap?.documents.orEmpty().map { toEvent(it) })
            }
    }

    /**
     * 한 번만 읽기(주간 리포트). 구독이 필요 없는 화면까지 [watchEvents]로 붙들면
     * 화면을 떠난 뒤에도 연결 잔류
     */
    fun loadEvents(limit: Long = 300, done: (List<Event>) -> Unit) {
        if (familyId == null) return done(emptyList())
        eventsRef().orderBy("at", Query.Direction.DESCENDING).limit(limit).get()
            .addOnSuccessListener { snap ->
                done(snap.documents.map {
                    Event(
                        it.getTimestamp("at")?.toDate(),
                        it.getString("risk") ?: "",
                        it.getString("type") ?: "none",
                        it.getString("reason") ?: "",
                        it.getString("host") ?: "",
                        it.getBoolean("blocked") ?: false,
                        it.getString("profileName") ?: "",
                        it.id,
                        it.getBoolean("done") ?: false
                    )
                })
            }
            .addOnFailureListener { Log.w(TAG, "기록 조회 실패: $it"); done(emptyList()) }
    }

    /**
     * "확인 완료로 처리" (시안 10G). 보호자가 이 건은 끝났다고 표시.
     *
     * 삭제 대신 표시만 남기는 이유: 같은 사이트가 다시 나타났을 때 **전에도 있었다**를
     * 셀 수 있어야 함(반복 위험 사이트). 목록에서는 흐리게 표시
     */
    fun markDone(id: String, done: (Boolean) -> Unit = {}) {
        if (familyId == null || id.isEmpty()) return done(false)
        eventsRef().document(id).update("done", true)
            .addOnSuccessListener { done(true) }
            .addOnFailureListener { Log.w(TAG, "확인 표시 실패: $it"); done(false) }
    }
}
