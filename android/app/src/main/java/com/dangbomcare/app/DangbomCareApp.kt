package com.dangbomcare.app

import android.app.Application
import com.dangbomcare.app.data.ServiceLocator
import com.dangbomcare.app.data.health.WatchSyncWorker
import com.dangbomcare.app.notification.AlarmScheduler
import com.dangbomcare.app.notification.NotificationHelper
import com.dangbomcare.app.notification.registerFcmTokenIfLoggedIn

class DangbomCareApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(applicationContext)
        NotificationHelper.createChannels(applicationContext)
        // [버그 fix] 매일 자정 "긴급 건강 알림"이 뜨던 EMERGENCY alarm cleanup.
        //   AlarmType에서 EMERGENCY 제거했지만, 폰에 기존 등록된 PendingIntent는 남아있음.
        //   request_code=1007 (구 EMERGENCY)로 만든 PendingIntent를 한 번 cancel.
        cleanupLegacyEmergencyAlarm(applicationContext)
        // 활성화된 알람만 재예약 (앱 콜드 스타트 시 누락 방지)
        AlarmScheduler.rescheduleAllEnabled(applicationContext)
        // 사용자 피드백 — 워치 자동 동기화 (30분 주기, 이미 등록돼 있으면 유지).
        // [Codex 검토] MainActivity가 아니라 Application onCreate에 두는 게 일관적임.
        WatchSyncWorker.schedule(applicationContext)
        // [v1.0 fix - Codex 2차] FCM 토큰 등록 — onNewToken은 토큰 갱신 시에만 호출.
        //   매번 앱 시작 시 현재 토큰 fetch → 로그인 상태면 백엔드 PUT.
        //   토큰이 폰별 고정이라 매번 PUT해도 백엔드는 같은 값 받음 (트래픽 미미).
        registerFcmTokenIfLoggedIn()
    }

    private fun cleanupLegacyEmergencyAlarm(ctx: android.content.Context) {
        try {
            val intent = android.content.Intent(ctx, com.dangbomcare.app.notification.AlarmReceiver::class.java).apply {
                putExtra(com.dangbomcare.app.notification.AlarmReceiver.EXTRA_TYPE, "EMERGENCY")
            }
            val pi = android.app.PendingIntent.getBroadcast(
                ctx, 1007, intent,
                android.app.PendingIntent.FLAG_NO_CREATE or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            if (pi != null) {
                (ctx.getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager).cancel(pi)
                pi.cancel()
            }
        } catch (_: Throwable) { /* best-effort cleanup */ }
    }
}
