package com.dangbomcare.app.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import java.util.Calendar

/**
 * 매일 정해진 시각에 로컬 알림을 띄우는 AlarmManager 래퍼.
 *
 * - 매일 반복 (다음 날 동일 시각 재예약)
 * - 사용자가 ON/OFF 가능 (SharedPreferences로 상태 저장)
 * - 기본 시각: 아침 07:00 / 저녁 19:00 / 취침 23:00
 */
object AlarmScheduler {

    private const val PREFS = "dangdangcare_alarms"
    private const val DEFAULT_MORNING_HOUR = 7
    private const val DEFAULT_DINNER_HOUR = 19
    private const val DEFAULT_BEDTIME_HOUR = 23

    // [260623 피드백] 폐지된 알림 — '식후 걷기'는 저녁 마감 알림 멘트에 통합, '방해금지'는 제거.
    //   isEnabled 기본값이 ON이라 기존 사용자에겐 예약돼 있을 수 있어, 항상 취소하고 재예약하지 않는다.
    private val DEPRECATED_ALARMS = setOf(AlarmType.POST_MEAL_WALK, AlarmType.DND)

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 사용자 설정 시각 (없으면 기본값)
     *
     *  [260722] DINNER default 를 `life_pattern.supper + 30분` 으로 dynamic 계산.
     *   기존 버그: LifePatternRow "저녁식사 시각" default = "18:00", 그런데 AlarmScheduler
     *     DEFAULT_DINNER_HOUR = 19 로 불일치. 사용자가 저녁식사 시각을 명시적으로 편집·저장하기
     *     전에는 설정화면(18:00) vs 알림화면(19:00) 이 어긋나 보임.
     *   해결: dangdangcare_alarms 에 값 없으면 supper + 30 을 반환. 명시 저장된 값이 있으면
     *     그 값이 우선(getInt 두 번째 인자는 fallback 만).
     */
    fun getHourMinute(context: Context, type: AlarmType): Pair<Int, Int> {
        val p = prefs(context)
        val (defaultHour, defaultMinute) = when (type) {
            AlarmType.MORNING -> DEFAULT_MORNING_HOUR to 0
            AlarmType.DINNER -> {
                val lifePrefs = context.getSharedPreferences("life_pattern", Context.MODE_PRIVATE)
                val supper = lifePrefs.getString("supper", "18:00") ?: "18:00"
                try {
                    val parts = supper.split(":").map { it.toInt() }
                    val totalMin = parts[0] * 60 + parts[1] + 30
                    ((totalMin / 60) % 24) to (totalMin % 60)
                } catch (_: Exception) {
                    DEFAULT_DINNER_HOUR to 0
                }
            }
            AlarmType.BEDTIME -> DEFAULT_BEDTIME_HOUR to 0
            AlarmType.POST_MEAL_WALK -> 13 to 0   // 점심 후 13시 기본
            AlarmType.WEEKLY_REPORT -> 8 to 0     // 월요일 8시
            AlarmType.DND -> 23 to 0              // 23시 ~ 익일 기상까지
        }
        val hour = p.getInt("${type.prefKey}_hour", defaultHour)
        val minute = p.getInt("${type.prefKey}_minute", defaultMinute)
        return hour to minute
    }

    /**
     * [저녁 마감시간 동적화] 사용자가 설정한 저녁식사 마감 시각을 "오후 N시"(분 있으면 N시 M분)로.
     *   회고 질문·기록 표시·알림에서 하드코딩 '7시' 대신 이걸 써서, 설정 변경이 전 화면에 반영되게 함.
     */
    fun dinnerDeadlineLabel(context: Context): String {
        val (h, m) = getHourMinute(context, AlarmType.DINNER)
        val ampm = if (h < 12) "오전" else "오후"
        val h12 = when { h == 0 -> 12; h <= 12 -> h; else -> h - 12 }
        return if (m == 0) "$ampm ${h12}시" else "$ampm ${h12}시 ${m}분"
    }

    /**
     * [저녁 마감시간 동적화] 점수/리포트의 짧은 라벨용 — "7시"/"8시 30분"(오전·오후 생략, 12시간제).
     *   '7시 전 저녁' 같은 컴팩트 라벨에서 하드코딩 '7' 대신 설정값을 노출하기 위함.
     */
    fun dinnerHourLabel(context: Context): String {
        val (h, m) = getHourMinute(context, AlarmType.DINNER)
        val h12 = when { h == 0 -> 12; h <= 12 -> h; else -> h - 12 }
        return if (m == 0) "${h12}시" else "${h12}시 ${m}분"
    }

    /** [저녁 마감시간 동적화] 저녁 알림 시각의 '시'(24시간제) — 알림 발화 시간대 계산용. */
    fun dinnerHour(context: Context): Int = getHourMinute(context, AlarmType.DINNER).first

    fun setTime(context: Context, type: AlarmType, hour: Int, minute: Int) {
        prefs(context).edit().apply {
            putInt("${type.prefKey}_hour", hour)
            putInt("${type.prefKey}_minute", minute)
            apply()
        }
        if (isEnabled(context, type)) schedule(context, type)
        // [260623] 방해금지(DND) 알림 폐지 — 취침시각 변경 시 DND 연동 제거.
    }

    /**
     * [P1 260727] 서버(user_settings) → 로컬 하이드레이션. 시각의 정본은 **서버**다.
     *
     * 배경: 저녁 마감 시각의 정본은 백엔드 user_settings.dinner_deadline_time 인데,
     *   앱의 알림 발화·회고 라벨·리포트 조언은 전부 이 로컬 prefs 만 봤다.
     *   signOut() 이 'dangdangcare_alarms'/'life_pattern' 을 clear 하는데, 재로그인 시
     *   onboarding_completed=true 면 온보딩(=로컬에 시각을 써주는 유일한 경로)이 스킵되어
     *   로컬 시각이 영영 기본값에 머물렀다 → "홈 AI 미션 19:30 / 리포트·알림 18:30" 이중 표시.
     *
     * 정책:
     *   1. 서버 값이 있으면 서버로 덮어쓴다(로그인 직후 1회).
     *   2. 서버 값이 null 이거나 파싱 불가면 **로컬 값을 유지**한다 (null 로 지우지 않는다).
     *   3. setTime() 이 내부에서 isEnabled 인 알람을 재스케줄하므로 실제 발화 시각도 함께 바뀐다.
     *   4. 알림 ON/OFF(notif_*)는 하이드레이션하지 않는다 — 앱은 토글을 서버로 보내지 않아
     *      서버 값이 항상 가입 시 기본값(notif_bedtime=false)이라, 덮어쓰면 사용자가 켠 알림이 꺼진다.
     *
     * @return 실제로 변경된 항목 수(로그/디버깅용)
     */
    fun hydrateFromServer(
        context: Context,
        glucoseCheckTime: String?,
        dinnerDeadlineTime: String?,
        bedtime: String?
    ): Int {
        var changed = 0

        fun applyOne(type: AlarmType, raw: String?) {
            val parsed = parseHourMinute(raw) ?: return   // null/형식오류 → 로컬 유지
            val (h, m) = parsed
            val (curH, curM) = getHourMinute(context, type)
            // prefs 에 명시값이 없으면(로그아웃 clear 직후 등) 값이 같아도 명시적으로 기록해 둔다.
            //   그래야 이후 life_pattern 변경 등으로 default 계산식이 흔들려도 서버 값이 유지된다.
            val hasExplicit = prefs(context).contains("${type.prefKey}_hour")
            if (hasExplicit && curH == h && curM == m) return
            setTime(context, type, h, m)   // prefs 저장 + (ON 이면) 재스케줄
            changed++
        }

        applyOne(AlarmType.MORNING, glucoseCheckTime)
        applyOne(AlarmType.DINNER, dinnerDeadlineTime)
        applyOne(AlarmType.BEDTIME, bedtime)

        // 저녁식사 시각(life_pattern.supper)은 서버에 대응 컬럼이 없다.
        //   설정 화면 규칙(supper + 30분 = 저녁 마감)의 역으로 되살려 "저녁식사 18:00 / 마감 19:30" 같은
        //   화면 내 모순을 막는다. 단, 로컬에 명시값이 있으면 사용자가 직접 고른 값이므로 건드리지 않는다.
        parseHourMinute(dinnerDeadlineTime)?.let { (h, m) ->
            val lifePrefs = context.getSharedPreferences("life_pattern", Context.MODE_PRIVATE)
            if (!lifePrefs.contains("supper")) {
                val total = ((h * 60 + m - 30) % (24 * 60) + 24 * 60) % (24 * 60)
                lifePrefs.edit()
                    .putString("supper", "%02d:%02d".format(total / 60, total % 60))
                    .apply()
                changed++
            }
        }
        return changed
    }

    /**
     * 서버 시각 문자열 → (시, 분).
     *   백엔드 UserSettingsOut 의 시각 필드는 Pydantic `datetime.time` 이라 항상 "HH:MM:SS"("19:30:00")로
     *   직렬화된다. "HH:MM" / "HH:MM:SS.ffffff" 도 허용하고, 범위를 벗어나거나 형식이 다르면 null.
     */
    internal fun parseHourMinute(raw: String?): Pair<Int, Int>? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        val parts = s.split(":")
        if (parts.size < 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h to m
    }

    fun isEnabled(context: Context, type: AlarmType): Boolean =
        prefs(context).getBoolean("${type.prefKey}_enabled", true)  // 기본 ON

    fun setEnabled(context: Context, type: AlarmType, enabled: Boolean) {
        prefs(context).edit().putBoolean("${type.prefKey}_enabled", enabled).apply()
        if (enabled) schedule(context, type) else cancel(context, type)
    }

    fun schedule(context: Context, type: AlarmType) {
        // [260623] 폐지된 알림은 예약하지 않고 취소만 (식후 걷기 → 저녁 마감 알림에 통합, 방해금지 제거)
        if (type in DEPRECATED_ALARMS) { cancel(context, type); return }
        val (hour, minute) = getHourMinute(context, type)
        val triggerAt = Calendar.getInstance().apply {
            timeInMillis = System.currentTimeMillis()
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)

            // [취침 알림 30분 전 발사] 저장된 취침 시각에서 30분 앞당겨서 알림 예약.
            //   "오늘 시각 지남 판정" 이전에 오프셋을 적용해야 자정 넘김 케이스도 정확히 내일로 넘어감.
            if (type == AlarmType.BEDTIME) {
                add(Calendar.MINUTE, -30)
            }

            // [사용자 피드백] 주간 리포트 알림은 월요일에만 (매일 X)
            if (type == AlarmType.WEEKLY_REPORT) {
                val currentDow = get(Calendar.DAY_OF_WEEK)
                var daysUntilMonday = (Calendar.MONDAY - currentDow + 7) % 7
                // 오늘 월요일인데 시각 지남 → 다음 주 월요일
                if (daysUntilMonday == 0 && timeInMillis <= System.currentTimeMillis()) {
                    daysUntilMonday = 7
                }
                if (daysUntilMonday > 0) {
                    add(Calendar.DAY_OF_MONTH, daysUntilMonday)
                }
            } else {
                // 일반 알림: 오늘 해당 시각이 이미 지났으면 내일로
                //   [HIGH 버그 수정] BEDTIME 취침 00:00~00:15 저장 + 현재 23:45~23:59 케이스처럼
                //   -30분 오프셋 적용 후 add(1일) 해도 여전히 과거인 상황이 존재 → while로 미래까지 반복.
                while (timeInMillis <= System.currentTimeMillis()) {
                    add(Calendar.DAY_OF_MONTH, 1)
                }
            }
        }.timeInMillis

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = buildPendingIntent(context, type)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                } else {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
        } catch (e: SecurityException) {
            // exact alarm 권한 없는 경우 → inexact로 fallback
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
    }

    fun reschedule(context: Context, type: AlarmType) {
        // [260623] 폐지된 알림은 항상 취소 (앱 재시작/부팅 시 기존 예약 정리)
        if (type in DEPRECATED_ALARMS) { cancel(context, type); return }
        if (isEnabled(context, type)) schedule(context, type)
    }

    fun rescheduleAllEnabled(context: Context) {
        AlarmType.values().forEach { reschedule(context, it) }
    }

    fun cancel(context: Context, type: AlarmType) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(buildPendingIntent(context, type))
    }

    private fun buildPendingIntent(context: Context, type: AlarmType): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(AlarmReceiver.EXTRA_TYPE, type.name)
        }
        return PendingIntent.getBroadcast(
            context, type.requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
