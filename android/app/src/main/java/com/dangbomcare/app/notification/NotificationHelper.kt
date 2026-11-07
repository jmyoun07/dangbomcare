package com.dangbomcare.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.dangbomcare.app.MainActivity
import com.dangbomcare.app.R

/**
 * 알림 채널 + 알림 표시 helper.
 *
 * 채널 6개 (당봄 #13 — 심야 무음 모드 정책):
 * - morning: 공복혈당 측정 알림 (07:00)
 * - dinner: 저녁 식사 마감 (19:00)
 * - bedtime: 취침 알림 (23:00)
 * - post_meal_walk: 식후 걷기 (식사 후 30분)
 * - weekly_report: 주간 리포트 도착 (월요일 08:00)
 * - emergency: 응급 알림 (혈당 위험·의료진 응급 메시지) — 취침 중에도 노출
 */
object NotificationHelper {

    private const val CHANNEL_MORNING = "morning_input"
    private const val CHANNEL_DINNER = "dinner_deadline"
    private const val CHANNEL_BEDTIME = "bedtime"
    private const val CHANNEL_POST_MEAL_WALK = "post_meal_walk"
    private const val CHANNEL_WEEKLY_REPORT = "weekly_report"
    const val CHANNEL_EMERGENCY = "emergency"  // 공개 (FCM 서비스에서 사용)

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannels(listOf(
                NotificationChannel(
                    CHANNEL_MORNING, "공복혈당 측정 알림",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "매일 아침 공복혈당 측정을 알려드려요"
                },
                NotificationChannel(
                    CHANNEL_DINNER, "저녁 식사 마감",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "저녁 식사 완료를 알려드려요"
                },
                NotificationChannel(
                    CHANNEL_BEDTIME, "취침 알림",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "취침 시각이 다가왔어요"
                },
                NotificationChannel(
                    CHANNEL_POST_MEAL_WALK, "식후 걷기 알림",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "식사 후 10분 산책으로 혈당 안정"
                },
                NotificationChannel(
                    CHANNEL_WEEKLY_REPORT, "주간 리포트",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "월요일 아침 지난주 건강 리포트가 도착해요"
                },
                NotificationChannel(
                    CHANNEL_EMERGENCY, "응급 알림",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "혈당 알림·의료진 메시지 (취침 중에도 알림)"
                    enableVibration(true)
                    enableLights(true)
                }
            ))
        }
    }

    fun show(context: Context, type: AlarmType) {
        val (channelId, title, body, smallIcon) = when (type) {
            AlarmType.MORNING -> Quadruple(
                CHANNEL_MORNING,
                "🌅 아침 입력 시각이에요",
                "오늘 공복혈당 측정 후 입력해주세요. AI 코치가 분석해드릴게요.",
                R.mipmap.ic_launcher
            )
            AlarmType.DINNER -> Quadruple(
                CHANNEL_DINNER,
                "🍚 저녁 식사 마감 시각",
                // [260623 피드백] 폐지한 '식후 걷기 알림' 멘트를 저녁 마감 알림에 통합
                "저녁 식사 맛있게 하셨나요? 이제 마치시고 식후 최소 10분 걷기 해주세요. 식후 금식도 혈당 관리에 도움돼요.",
                R.mipmap.ic_launcher
            )
            AlarmType.BEDTIME -> Quadruple(
                CHANNEL_BEDTIME,
                "😴 취침 시각이에요",
                "충분한 수면(7~9시간)이 혈당 관리에 큰 도움이 됩니다.",
                R.mipmap.ic_launcher
            )
            AlarmType.POST_MEAL_WALK -> Quadruple(
                CHANNEL_POST_MEAL_WALK,  // 전용 채널 (Codex 검토)
                "🚶 식후 산책 시각",
                "식후 10분 산책이 혈당 스파이크를 줄여줍니다.",
                R.mipmap.ic_launcher
            )
            AlarmType.WEEKLY_REPORT -> Quadruple(
                CHANNEL_WEEKLY_REPORT,  // 전용 채널 (Codex 검토)
                "📊 주간 리포트가 도착했어요",
                "지난주 건강 패턴을 분석한 리포트가 준비됐어요.",
                R.mipmap.ic_launcher
            )
            AlarmType.DND -> Quadruple(
                CHANNEL_BEDTIME,
                "🌙 방해금지 시간",
                "취침 시간 동안 알림을 무음 처리합니다.",
                R.mipmap.ic_launcher
            )
            // EMERGENCY 제거됨 — FCM push 전용 (정기 schedule 버그로 자정 알림 떴던 이슈)
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            context, type.requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 알림 타입별 priority 차등
        val priority = when (type) {
            AlarmType.MORNING -> NotificationCompat.PRIORITY_HIGH
            AlarmType.DINNER, AlarmType.POST_MEAL_WALK, AlarmType.WEEKLY_REPORT -> NotificationCompat.PRIORITY_DEFAULT
            AlarmType.BEDTIME, AlarmType.DND -> NotificationCompat.PRIORITY_LOW
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(smallIcon)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(priority)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(type.requestCode, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS 권한 없을 때 — 무시
        }
    }

    private data class Quadruple<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
}

enum class AlarmType(val requestCode: Int, val prefKey: String) {
    MORNING(1001, "alarm_morning"),
    DINNER(1002, "alarm_dinner"),
    BEDTIME(1003, "alarm_bedtime"),
    // [DEPRECATED · 260714 정리] POST_MEAL_WALK: 저녁 마감 알림 멘트에 통합됨 (S1).
    //   DND: 방해금지 시간 폐지 (S2). 둘 다 DEPRECATED_ALARMS set(AlarmScheduler.kt)에 등록되어
    //   신규 예약은 안 되고 기존 예약만 취소됨. Enum 자체는 하위호환(구 예약 취소)용으로 유지.
    POST_MEAL_WALK(1004, "alarm_post_meal_walk"),
    WEEKLY_REPORT(1005, "alarm_weekly_report"),
    DND(1006, "dnd_during_sleep")
    // EMERGENCY 제거됨 — FCM push로 즉시 발송하는 응급 알림이라 정기 schedule 대상 X.
    // 기본 hour=0이라 매일 자정 알림 떠버리는 버그로 제거.
    // CHANNEL_EMERGENCY 채널은 유지 (FCM service에서 직접 NotificationCompat.Builder로 사용).
}
