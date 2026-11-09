package com.flyai.adalert

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * 보호자 폰의 알림 수신 자리.
 *
 * 앱이 화면에 떠 있을 때는 [EventListActivity]가 이미 실시간 갱신 — 알림 없이도 보임.
 * 이 서비스의 필요 시점: **보호자가 앱을 보고 있지 않을 때**.
 *
 * 알림 클릭 → 보호 현황으로 바로 이동 — 무슨 일이 있었는지가 거기 있기 때문.
 *
 * ## 서버가 `notification`이 아니라 `data`로 보내는 이유
 * 안드로이드는 `notification` 페이로드가 실린 메시지를 **앱이 백그라운드일 때 시스템이
 * 직접** 트레이에 그린다. 그때 [onMessageReceived]는 호출조차 되지 않으므로 아래의
 * 채널·이름표·이동 경로가 통째로 무시됐다 — 정작 이 서비스가 필요한 그 시점에.
 * `data`만 실어 보내면 앱이 항상 깨어나 여기서 그린다 (`server/main.py`의 `/notify`).
 *
 * ## 같은 유형은 새 줄이 아니라 갱신
 * 통마다 새 알림을 만들면 어르신이 광고를 여러 번 만졌을 때 보호자 트레이가 같은 내용으로
 * 도배된다. 이름표(`tag`)를 유형 단위로 받아 그 줄을 고쳐 쓴다 — 트레이에 남는 줄은
 * 유형 수(위험 검색·악성 도메인·APK 설치·주의·요약)가 상한이다.
 * 몇 번 있었는지는 보내는 쪽이 제목에 실어 준다 ([Family]).
 */
class PushService : FirebaseMessagingService() {

    private companion object {
        const val TAG = "PushService"
        const val CHANNEL = "guardian"

        /** 트레이에서 한 덩어리로 묶이도록. 여러 유형이 동시에 와도 접힌 채로 보임 */
        const val GROUP = "guardian_alerts"

        /** 이름표가 다르면 다른 줄이므로 알림 번호는 하나로 충분 */
        const val ID = 1
    }

    /**
     * 토큰은 앱 재설치·폰 교체 시 새로 발급. 새로 받으면 프로필에 기록 —
     * 없으면 보호자 폰이 바뀐 뒤로 알림이 조용히 사라짐.
     */
    override fun onNewToken(token: String) {
        Family.saveToken(this, token)
    }

    override fun onMessageReceived(msg: RemoteMessage) {
        val data = msg.data
        // notification 페이로드는 이제 오지 않지만, 옛 서버가 남아 있는 환경에서도
        // 알림이 사라지지는 않도록 폴백은 남긴다
        val title = data["title"] ?: msg.notification?.title ?: "광고 알리미"
        val line1 = data["line1"] ?: msg.notification?.body.orEmpty()
        val line2 = data["line2"].orEmpty()
        val tag = data["tag"] ?: CHANNEL
        Log.i(TAG, "알림 받음: $title (tag=$tag)")

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "보호 알림", NotificationManager.IMPORTANCE_HIGH)
        )
        // 알림 클릭 시 이동. 기록 이름이 실려 있으면 **그 기록**으로 바로,
        // 없으면(요약 알림·옛 서버) 목록으로. 알림을 지워도 기록은 거기에 유지.
        //
        // 상세 화면은 기록 한 줄 전체를 인자로 받지만 알림에는 이름만 있다 —
        // 나머지는 그 화면이 이름으로 읽어 온다 ([EventDetailActivity], [Family.event]).
        val eventId = data["eventId"].orEmpty()
        val target = if (eventId.isEmpty()) {
            Intent(this, EventListActivity::class.java)
        } else {
            Intent(this, EventDetailActivity::class.java).putExtra("eventId", eventId)
        }
        val open = PendingIntent.getActivity(
            this, tag.hashCode(),
            target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val body = if (line2.isEmpty()) line1 else "$line1\n$line2"
        nm.notify(
            tag, ID,
            Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(line1)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setGroup(GROUP)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        )
    }
}
