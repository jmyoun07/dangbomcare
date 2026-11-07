package com.dangbomcare.app.notification

import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.dangbomcare.app.MainActivity
import com.dangbomcare.app.R
import com.dangbomcare.app.data.ServiceLocator
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

/**
 * FCM 메시지 수신 + 토큰 갱신 처리 [v1.0 출시 fix - Codex 2차].
 *
 * - onNewToken: 새 토큰 발급 시 백엔드 PUT /api/users/me/fcm-token (로그인 상태에서만)
 * - onMessageReceived: data 또는 notification payload 둘 다 지원 (백엔드는 data로 channel 명시)
 *
 * 채널 매핑:
 *   - data.channel = "weekly_report" → CHANNEL_WEEKLY_REPORT (전용)
 *   - data.channel = "emergency" → CHANNEL_EMERGENCY (혈당/의료진 메시지)
 *   - 그 외 → CHANNEL_MORNING (기본)
 *
 * [260714 정리 Codex 후속5] "post_meal_walk" 채널은 폐지됨(식후 걷기 알림은 저녁 마감 알림에 통합).
 *   구 서버·테스트 발송에서 아직 post_meal_walk가 올 수 있으니, 매핑은 유지하되 morning_input으로
 *   폴백 처리(사용자에게 "정체불명" 알림처럼 뜨지 않게 무해한 채널에 실어 보냄).
 */
class DangbomCareMessagingService : FirebaseMessagingService() {

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        Log.d(TAG, "FCM new token received (length=${token.length})")
        // 로그인 상태일 때만 백엔드에 등록 (로그인 전이면 SplashScreen onAuthenticated 시점에 추가 등록 권장 — v1.1)
        ioScope.launch {
            try {
                val access = ServiceLocator.tokenStorage.getAccessTokenBlocking()
                if (access.isNullOrBlank()) {
                    Log.d(TAG, "skip — not logged in, token will be sent on next login")
                    return@launch
                }
                val result = ServiceLocator.userRepository.updateFcmToken(token)
                Log.d(TAG, "fcm token update result: ${result.javaClass.simpleName}")
            } catch (e: Exception) {
                Log.w(TAG, "fcm token update failed: ${e.message}")
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // notification payload(서버에서 title/body 미리 채움) 또는 data payload(앱이 직접 렌더링)
        val title = message.notification?.title
            ?: message.data["title"]
            ?: "당봄케어"
        val body = message.notification?.body
            ?: message.data["body"]
            ?: ""
        val channelKey = message.data["channel"] ?: "default"

        // [260714 정리 Codex 후속5] 폐지된 채널의 FCM은 명시적으로 무시 — 구 서버·테스트 발송 안전 차단.
        if (channelKey == "post_meal_walk") {
            Log.d(TAG, "ignore deprecated channel: post_meal_walk")
            return
        }
        //  NotificationHelper의 채널 ID 문자열과 동기화 (string 직접 매핑 — private const 우회).
        //  NotificationHelper.kt의 CHANNEL_* 상수를 변경하면 여기도 같이 업데이트 필요.
        val channelId = when (channelKey) {
            "weekly_report" -> "weekly_report"
            // [260714] post_meal_walk 폐지 — 구 서버 fallback 시 무해한 morning 채널로 표시
            "emergency" -> NotificationHelper.CHANNEL_EMERGENCY
            else -> "morning_input"
        }

        showNotification(title, body, channelId, channelKey)
    }

    private fun showNotification(title: String, body: String, channelId: String, channelKey: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this,
            channelKey.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val priority = when (channelKey) {
            "emergency" -> NotificationCompat.PRIORITY_HIGH
            "weekly_report" -> NotificationCompat.PRIORITY_DEFAULT
            else -> NotificationCompat.PRIORITY_DEFAULT
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(priority)
            .build()

        try {
            NotificationManagerCompat.from(this).notify(channelKey.hashCode(), notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS 권한 없음 — 시스템 정책상 무시 (앱이 권한 거부 처리 가능)
            Log.w(TAG, "notify permission denied: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "FcmMsgSvc"
    }
}
