package com.dangbomcare.app.data.health

import android.content.Context
import java.time.Instant
import java.time.ZoneId

/**
 * 워치 데이터 마지막 동기화 시각 보관 (SharedPreferences).
 * - 환자가 "왜 숫자가 안 오르지?" 불안해하는 걸 방지하기 위해 홈 상단에 표시.
 */
object WatchSyncTracker {

    private const val PREFS = "dangdangcare_watch_sync"
    private const val KEY = "last_sync_ms"
    private const val KEY_FULL_REFRESH_DAY = "last_full_refresh_epochday"
    private const val KEY_INITIAL_BACKFILL = "initial_backfill_done"  // [260609] 최초 1회 90일 백필 완료 여부

    /** 동기화 성공 시점 기록 (현재 시각). */
    fun mark(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY, System.currentTimeMillis()).apply()
    }

    /** 마지막 동기화 시각 (밀리초). 없으면 null. */
    fun lastSyncMs(context: Context): Long? {
        val v = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY, 0L)
        return if (v > 0) v else null
    }

    /**
     * [v1.0 — Codex 교차검증] 31일 "전체 새로고침"을 완료한 날짜 기록.
     *   삼성헬스가 과거 데이터를 HC에 늦게 넣어줘도 백엔드/점수/리포트에 반영되도록,
     *   하루 1회만 31일 전체 재동기화하고 그 외 주기는 rolling 재동기화하기 위한 상태.
     *   (lastSyncMs 와 분리 — MorningInput 등 다른 흐름이 mark() 해도 전체 새로고침을 막지 않게.)
     */
    fun markFullRefresh(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_FULL_REFRESH_DAY, java.time.LocalDate.now().toEpochDay()).apply()
    }

    /** 마지막 31일 전체 새로고침 날짜. 없으면 null. */
    fun lastFullRefreshDate(context: Context): java.time.LocalDate? {
        val v = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_FULL_REFRESH_DAY, 0L)
        return if (v > 0) java.time.LocalDate.ofEpochDay(v) else null
    }

    /**
     * [사용자 결정 260609] 최초 1회 90일 백필 완료 여부.
     *   가입/최초 동기화 때 Supabase에 90일을 한 번 채우고, 이후 자동 동기화는 7일 롤링만 한다.
     *   (표시는 SDK 직접읽기라 90일 이전도 화면엔 보임 — Supabase 저장만 90일 기준으로 시작)
     */
    fun isInitialBackfillDone(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_INITIAL_BACKFILL, false)

    /** 90일 백필 1회 성공 시 기록 → 이후 7일 롤링으로 전환. */
    fun markInitialBackfillDone(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_INITIAL_BACKFILL, true).apply()
    }

    /** "방금 전 동기화", "5분 전 동기화", "오늘 14:23 동기화" 등 시니어 친화 표기. */
    fun formatLastSync(context: Context): String {
        val ms = lastSyncMs(context) ?: return "아직 동기화 안 됨"
        val diff = System.currentTimeMillis() - ms
        return when {
            diff < 60_000 -> "방금 전 동기화"
            diff < 60 * 60_000 -> "${diff / 60_000}분 전 동기화"
            diff < 6 * 60 * 60_000 -> "${diff / (60 * 60_000)}시간 전 동기화"
            else -> {
                val t = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
                val today = Instant.now().atZone(ZoneId.systemDefault()).toLocalDate()
                val date = t.toLocalDate()
                val timeStr = "%d:%02d".format(t.hour, t.minute)
                val datePart = when {
                    date == today -> "오늘"
                    date == today.minusDays(1) -> "어제"
                    else -> "${t.monthValue}/${t.dayOfMonth}"
                }
                "$datePart $timeStr 동기화"
            }
        }
    }
}
