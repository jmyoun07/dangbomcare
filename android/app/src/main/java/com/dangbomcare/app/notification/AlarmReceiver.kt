package com.dangbomcare.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * AlarmManager가 정해진 시각에 호출하는 receiver.
 * Intent extra "alarm_type" (MORNING/DINNER/BEDTIME)에 따라 알림 표시 + 다음 날 재예약.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val typeName = intent.getStringExtra(EXTRA_TYPE) ?: return
        val type = runCatching { AlarmType.valueOf(typeName) }.getOrNull() ?: return

        // [260623] 폐지된 알림(식후 걷기·방해금지)은 발화해도 표시하지 않고 취소.
        //   앱 시작 시 rescheduleAllEnabled로 정리되지만, 그 전에 기존 예약분이 먼저 울릴 수 있어 방어.
        if (type == AlarmType.POST_MEAL_WALK || type == AlarmType.DND) {
            AlarmScheduler.cancel(context, type)
            return
        }

        // [회귀 P1] 사용자가 설정한 시각의 알림은 quiet hours 우회 (예: 05:30 MORNING)
        //   hour만 비교하면 05:50 설정인데 05:05에 울려도 우회 (오발송 위험).
        //   현재 시각과 설정 시각의 분 단위 차이 ±10분 이내일 때만 "사용자 설정 시각" 판정.
        //   [v1.0 버그픽스] 자정 wrap-around 처리 — 23:30 설정인데 00:00에 발사 시 rawDiff=1410이 아니라 30으로.
        val (configHour, configMin) = AlarmScheduler.getHourMinute(context, type)
        val now = java.time.LocalTime.now()
        val nowMin = now.hour * 60 + now.minute
        val configTotalMin = configHour * 60 + configMin
        val rawDiff = kotlin.math.abs(nowMin - configTotalMin)
        val diffMin = kotlin.math.min(rawDiff, 1440 - rawDiff)  // 24h wrap-around
        val isUserConfiguredTime = diffMin <= 10
        val isQuietHours = (now.hour >= 23 || now.hour < 6)
        if (isQuietHours && !isUserConfiguredTime && type != AlarmType.BEDTIME) {
            AlarmScheduler.reschedule(context, type)
            return
        }

        // [v1.0 버그픽스] inexact alarm + Doze 모드로 알림이 한참 늦게 발사되는 stale notification 방지.
        //   예: SCHEDULE_EXACT_ALARM 권한 미허용 → setAndAllowWhileIdle fallback →
        //   아침 7시 MORNING 알람이 저녁 20시에 한꺼번에 발사되는 현상.
        //   60분 이상 차이나면 표시 안 하고 다음 날 재예약만 수행.
        //   BEDTIME/DND는 정확 시각보다 ±60분 벗어나도 의미가 있어 예외 (긴 윈도).
        val isStaleTrigger = diffMin > 60 &&
            type != AlarmType.BEDTIME &&
            type != AlarmType.DND
        if (isStaleTrigger) {
            android.util.Log.w(
                "AlarmReceiver",
                "stale alarm suppressed: type=$type, config=$configHour:$configMin, now=${now.hour}:${now.minute}, diff=${diffMin}min"
            )
            AlarmScheduler.reschedule(context, type)
            return
        }

        NotificationHelper.show(context, type)
        // 다음 날 동일 시각 재예약
        AlarmScheduler.reschedule(context, type)
    }

    companion object {
        const val EXTRA_TYPE = "alarm_type"
    }
}
