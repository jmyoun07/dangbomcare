package com.dangbomcare.app.data.health

import android.app.Activity
import android.content.Context
import android.os.Build
import android.util.Log
import com.dangbomcare.app.data.api.HeartRateSample
import com.dangbomcare.app.data.api.WatchDataRequest
import com.samsung.android.sdk.health.data.HealthDataService
import com.samsung.android.sdk.health.data.HealthDataStore
import com.samsung.android.sdk.health.data.data.HealthDataPoint
import com.samsung.android.sdk.health.data.permission.AccessType
import com.samsung.android.sdk.health.data.permission.Permission
import com.samsung.android.sdk.health.data.request.DataType
import com.samsung.android.sdk.health.data.request.DataTypes
import com.samsung.android.sdk.health.data.request.LocalTimeFilter
import com.samsung.android.sdk.health.data.request.LocalTimeGroup
import com.samsung.android.sdk.health.data.request.LocalTimeGroupUnit
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Samsung Health Data SDK 직접 읽기 소스.
 *
 * 목적: Samsung Health → Health Connect 동기화가 불완전한(수면 누락·과거 미동기화) 폰에서
 *       삼성헬스 DB를 SDK로 직접 읽어 HC를 우회한다.
 *
 * 동작 조건: 삼성헬스 개발자 모드(데이터 읽기) ON (테스트) 또는 파트너 승인(배포) + SDK 권한 부여.
 * 조건 미충족 시 isReady()=false → 호출측(HealthConnectRepository)은 기존 HC 경로로 fallback.
 *
 * ⚠️ 반환 타입은 HealthConnectRepository 와 100% 동일 (WatchDataRequest, SleepDay 등) →
 *    화면/백엔드/모델은 무수정으로 SDK 데이터를 그대로 사용.
 */
class SamsungHealthSource(private val context: Context) {

    companion object {
        private const val TAG = "SHSource"
        private const val READY_CACHE_MS = 30_000L
        // 삼성헬스 앱 패키지명 — SDK 사용 가능 후보 판정(설치 여부)에 사용.
        private const val SAMSUNG_HEALTH_PKG = "com.sec.android.app.shealth"
        // SleepSegment/UI가 쓰는 Health Connect StageType 정수 상수에 매핑 (4=LIGHT,5=DEEP,6=REM,1=AWAKE)
        private const val HC_LIGHT = 4
        private const val HC_DEEP = 5
        private const val HC_REM = 6
        private const val HC_AWAKE = 1
    }

    private val store: HealthDataStore by lazy { HealthDataService.getStore(context) }
    private val zone: ZoneId get() = ZoneId.systemDefault()

    val requiredPermissions: Set<Permission> = setOf(
        Permission.of(DataTypes.STEPS, AccessType.READ),
        Permission.of(DataTypes.SLEEP, AccessType.READ),
        Permission.of(DataTypes.HEART_RATE, AccessType.READ),
        Permission.of(DataTypes.BLOOD_OXYGEN, AccessType.READ),
    )

    @Volatile private var cachedReady: Boolean = false
    @Volatile private var cachedAt: Long = 0L

    // [QA Fix #4] isPermissionFlowAvailable() 결과 캐시 — PackageManager + getStore() 동기 프로빙이
    //   UI 경로·매 isSdkDirectActive() 호출마다 돌아 메인스레드 jank 유발. 설치 상태는 거의 안 바뀌므로
    //   프로세스 수명 동안 1회만 계산하고 이후엔 캐시 반환. null = 아직 미계산.
    @Volatile private var cachedFlowAvailable: Boolean? = null

    /**
     * [SDK 자동화] 권한 요청 '전' 단계 — 이 기기에서 Samsung Health Data SDK 권한 흐름을 띄울
     * 후보(candidate)인지 가벼운 사전 점검. (번들 AAR엔 isSupported가 없어 아래 3가지로 추정.)
     *   1) Android API >= 29 (SDK 최소 요건)
     *   2) 삼성헬스 앱(com.sec.android.app.shealth) 설치됨
     *   3) HealthDataService.getStore(context) 가 throw 하지 않음 (SDK 초기화 가능)
     *
     * ⚠️ 제조사(Samsung)로 게이트하지 않음 — 일부러 관대하게. 실제 동작 가능 여부는 권한 부여 후
     *    isReady() 와 읽기 단계의 HC fallback 이 최종 판정/보정한다.
     * ⚠️ non-suspend (코루틴 밖 onboarding LaunchedEffect 등에서도 안전 호출). 예외는 모두 false 로 흡수.
     * [QA Fix #4] 결과는 프로세스 수명 동안 캐시 (PackageManager/getStore 프로빙은 한 번만).
     */
    fun isPermissionFlowAvailable(): Boolean {
        cachedFlowAvailable?.let { return it }
        val result = computeFlowAvailable()
        cachedFlowAvailable = result
        return result
    }

    private fun computeFlowAvailable(): Boolean {
        // 1) API 레벨
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        // 2) 삼성헬스 설치 여부
        val pkgInstalled = try {
            context.packageManager.getPackageInfo(SAMSUNG_HEALTH_PKG, 0)
            true
        } catch (_: Exception) {
            false
        }
        if (!pkgInstalled) return false
        // 3) SDK store 생성 가능 여부 (lazy store 오염 방지 위해 직접 호출)
        return try {
            HealthDataService.getStore(context)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** SDK 사용 가능 + 모든 권한 부여 여부. 삼성헬스 없음/개발자모드 OFF/권한 없음 → false. 30초 캐시. */
    suspend fun isReady(): Boolean = isReady(force = false)

    /**
     * [v1.0 진단용] force=true면 캐시 무시하고 즉시 SDK store에 권한 재조회.
     * 데이터 진단 화면에서 "권한 회수했는데 우리 앱이 갱신 안 되는" 케이스 해결.
     */
    suspend fun isReady(force: Boolean): Boolean {
        val now = System.currentTimeMillis()
        if (!force && cachedReady && now - cachedAt < READY_CACHE_MS) return true
        return try {
            val granted = store.getGrantedPermissions(requiredPermissions)
            val ok = granted.containsAll(requiredPermissions)
            cachedReady = ok
            cachedAt = now
            ok
        } catch (e: Exception) {
            Log.d(TAG, "isReady=false: ${e.javaClass.simpleName} ${e.message}")
            cachedReady = false
            false
        }
    }

    /** [v1.0 진단용] 부여된 SDK 권한 개수 (디버그 화면용). */
    suspend fun grantedPermissionsCount(): Int = try {
        store.getGrantedPermissions(requiredPermissions).size
    } catch (_: Exception) { 0 }

    /**
     * SDK 권한 요청 (삼성헬스 동의 화면). Activity 필요. 반환: 부여 완료 여부.
     * [QA Fix #5] Samsung SDK 경계 — Throwable까지 방어적으로 흡수(false)하되, CancellationException은
     *   재던져 구조적 동시성(타임아웃/scope 취소)이 정상 전파되게 한다(취소를 false로 삼키면 안 됨).
     */
    suspend fun requestPermissions(activity: Activity): Boolean = try {
        val granted = store.requestPermissions(requiredPermissions, activity)
        val ok = granted.containsAll(requiredPermissions)
        cachedReady = ok
        cachedAt = System.currentTimeMillis()
        ok
    } catch (ce: kotlinx.coroutines.CancellationException) {
        throw ce
    } catch (t: Throwable) {
        Log.w(TAG, "requestPermissions failed: ${t.message}", t)
        false
    }

    // ============================================================
    // 걸음수
    // ============================================================

    /** 특정 구간 총 걸음수 (집계). */
    /**
     * [v1.0 버그픽스] 반환 타입 Int → Int?.
     *   삼성헬스 SDK가 데이터 0건이면 null 반환 (0 아님!) → fetchWatchData가 백엔드에 null 전송 →
     *   백엔드/AI가 "데이터 없음"으로 정확히 인식. 워치 미연결 사용자에게 잘못된 "활동량 부족" 피드백 차단.
     *   휴식일(워치 차고 1보도 안 걸음)은 dataList에 0 record가 들어와서 sum=0 → 0 반환됨.
     */
    private suspend fun stepsTotal(start: LocalDateTime, end: LocalDateTime): Int? {
        if (!end.isAfter(start)) return null
        val req = DataType.StepsType.TOTAL.requestBuilder
            .setLocalTimeFilter(LocalTimeFilter.of(start, end))
            .build()
        val dataList = store.aggregateData(req).dataList
        if (dataList.isEmpty()) return null  // SDK 데이터 자체 없음 = 워치 미연결/미동기화
        return dataList.sumOf { (it.value ?: 0L) }.toInt()
    }

    /** 시간대별(0~23시) 걸음수 24개. */
    private suspend fun stepsHourly(date: LocalDate): List<Int> {
        val dayStart = date.atStartOfDay()
        val dayEnd = minOf(date.plusDays(1).atStartOfDay(), LocalDateTime.now())
        val hourly = IntArray(24)
        if (!dayEnd.isAfter(dayStart)) return hourly.toList()
        val req = DataType.StepsType.TOTAL.requestBuilder
            .setLocalTimeFilterWithGroup(
                LocalTimeFilter.of(dayStart, dayEnd),
                LocalTimeGroup.of(LocalTimeGroupUnit.HOURLY, 1),
            )
            .build()
        store.aggregateData(req).dataList.forEach { agg ->
            val h = agg.startTime.atZone(zone).hour.coerceIn(0, 23)
            hourly[h] = (agg.value ?: 0L).toInt()
        }
        return hourly.toList()
    }

    /** 기간 일별 걸음수. */
    suspend fun fetchStepsByDay(from: LocalDate, to: LocalDate): Map<LocalDate, Int> {
        val startDate = if (from <= to) from else to
        val endDate = if (from <= to) to else from
        val start = startDate.atStartOfDay()
        val end = minOf(endDate.plusDays(1).atStartOfDay(), LocalDateTime.now())
        if (!end.isAfter(start)) return emptyMap()
        return try {
            val req = DataType.StepsType.TOTAL.requestBuilder
                .setLocalTimeFilterWithGroup(
                    LocalTimeFilter.of(start, end),
                    LocalTimeGroup.of(LocalTimeGroupUnit.DAILY, 1),
                )
                .build()
            val map = mutableMapOf<LocalDate, Int>()
            store.aggregateData(req).dataList.forEach { agg ->
                val d = agg.startTime.atZone(zone).toLocalDate()
                val v = (agg.value ?: 0L).toInt()
                if (v > 0) map[d] = v
            }
            map.filterKeys { !it.isBefore(startDate) && !it.isAfter(endDate) }
        } catch (e: Exception) {
            Log.w(TAG, "fetchStepsByDay failed: ${e.message}")
            emptyMap()
        }
    }

    // ============================================================
    // 수면
    // ============================================================

    /**
     * 수면 포인트 1건의 단계별 분(min).
     *
     * 반환 [deep, light, rem, awake, totalSpan]
     *   - 0~3: 단계별 실측 합. 총 수면시간은 **호출부에서 이 4개의 합**으로 계산한다
     *          (260728 확정 — 화면의 4단계 분포와 총합이 어긋나지 않게).
     *   - 4 totalSpan: 세션(잠자리에 든~깬) 구간 길이. 총합 계산에는 쓰지 않지만
     *          ① 단계 분해가 아예 없는 날(stages null/빈배열 → 0~3 전부 0)의 **폴백값**,
     *          ② 단계 미판정 구간(실측 17~37분) 진단용으로 남긴다. 삭제하지 말 것.
     */
    private fun stageMinutes(p: HealthDataPoint): IntArray {
        // [deep, light, rem, awake, totalSpan]
        // out[0]=깊은잠 out[1]=얕은잠 out[2]=렘 out[3]=깬시간 out[4]=세션구간(폴백/진단) out[5]=단계 미분류
        val out = IntArray(6)
        val sessions = p.getValue(DataType.SleepType.SESSIONS) ?: return out
        sessions.forEach { s ->
            // out[4] = 세션 구간(폴백/진단 전용) — 단계 합과 독립적으로 누적된다.
            out[4] += Duration.between(s.startTime, s.endTime).toMinutes().toInt()
            s.stages?.forEach { st ->
                val m = Duration.between(st.startTime, st.endTime).toMinutes().toInt()
                when (st.stage) {
                    DataType.SleepType.StageType.DEEP -> out[0] += m
                    DataType.SleepType.StageType.LIGHT -> out[1] += m
                    DataType.SleepType.StageType.REM -> out[2] += m
                    DataType.SleepType.StageType.AWAKE -> out[3] += m
                    // ⭐ [260730 버그픽스] StageType.UNDEFINED = 잠은 잤으나 단계를 판정 못 한 구간.
                    //   삼성 SDK enum 실측(javap): {UNDEFINED, AWAKE, LIGHT, DEEP, REM} — HC 의
                    //   STAGE_TYPE_SLEEPING(단계 미분류)과 정확히 같은 성격이다. 이전엔 else 로 버려서
                    //   총 수면시간에서 사라졌다. 세부단계 없이 "잠"으로만 기록하는 기기 + AWAKE 를 조금
                    //   섞어 찍는 기기에서 stageSum=awake(예: 30분)라 폴백(stageSum==0)도 안 걸려
                    //   "7시간 → 30분"이 업로드되는 회귀가 났다(HC 경로가 260729에 고친 것과 동일 버그).
                    //   단계 미분류이므로 총합에만 반영하고 4단계 분포에는 넣지 않는다.
                    DataType.SleepType.StageType.UNDEFINED -> out[5] += m
                }
            }
        }
        return out
    }

    /** 낮잠 제외 규칙 (시작 9~17시 + 3시간 미만). */
    private fun isNap(start: LocalDateTime, durationMin: Long): Boolean =
        start.hour in 9..17 && durationMin < 180

    /** 특정 날짜 밤 수면 포인트들 (start가 [정오, 다음날 정오) + 낮잠 제외). */
    private suspend fun sleepPointsForNight(date: LocalDate): List<HealthDataPoint> {
        val windowStart = date.atTime(12, 0)
        val windowEnd = date.plusDays(1).atTime(12, 0)
        val req = DataTypes.SLEEP.readDataRequestBuilder
            .setLocalTimeFilter(LocalTimeFilter.of(windowStart.minusHours(6), windowEnd.plusHours(6)))
            .build()
        return store.readData(req).dataList.filter { p ->
            val s = p.startTime.atZone(zone).toLocalDateTime()
            val durMin = Duration.between(p.startTime, p.endTime).toMinutes()
            !s.isBefore(windowStart) && s.isBefore(windowEnd) && !isNap(s, durMin)
        }
    }

    private fun isoOrNull(instant: Instant?): String? = instant?.toString()

    /** 기간 일별 수면. */
    suspend fun fetchSleepByDay(from: LocalDate, to: LocalDate): Map<LocalDate, HealthConnectRepository.SleepDay> {
        val startDate = if (from <= to) from else to
        val endDate = if (from <= to) to else from
        val rangeStart = startDate.atStartOfDay().minusHours(6)
        val rangeEnd = endDate.plusDays(1).atStartOfDay().plusHours(14)
        return try {
            val req = DataTypes.SLEEP.readDataRequestBuilder
                .setLocalTimeFilter(LocalTimeFilter.of(rangeStart, rangeEnd))
                .build()
            val points = store.readData(req).dataList
            val byDate = mutableMapOf<LocalDate, MutableList<HealthDataPoint>>()
            points.forEach { p ->
                val s = p.startTime.atZone(zone).toLocalDateTime()
                val durMin = Duration.between(p.startTime, p.endTime).toMinutes()
                if (isNap(s, durMin)) return@forEach
                // 시작이 정오 이전(새벽) = 전날 밤 잠으로 매핑
                val mapped = if (s.hour < 12) s.toLocalDate().minusDays(1) else s.toLocalDate()
                byDate.getOrPut(mapped) { mutableListOf() }.add(p)
            }
            // [버그픽스 260616] 자정 넘는 잠 포착 위해 범위를 6h 넘겨 조회 → 요청 밖 날짜(예: 전 주 일요일) 키가 섞임.
            //   요청 [from,to] 안의 날짜만 반환해 요약카드 평균 오염 방지.
            byDate.filterKeys { !it.isBefore(startDate) && !it.isAfter(endDate) }.mapValues { (date, pts) ->
                var deep = 0; var light = 0; var rem = 0; var awake = 0; var sessionSpan = 0; var unstaged = 0
                pts.forEach { p ->
                    val sm = stageMinutes(p)
                    deep += sm[0]; light += sm[1]; rem += sm[2]; awake += sm[3]; sessionSpan += sm[4]; unstaged += sm[5]
                }
                // [260728/260730] 총 수면시간 = 4단계 합 + 단계 미분류(UNDEFINED). HC 경로와 동일 정의 —
                //   워치를 바꿔도 총 수면시간 정의가 안 바뀌게 한다. 미분류는 4단계 분포에는 안 들어간다.
                //   ⚠️ 단계 분해가 전혀 없는 날(stages null/빈배열)은 합이 0 → 세션 구간으로 폴백(회귀 방지).
                val stageSum = deep + light + rem + awake + unstaged
                val total = if (stageSum > 0) stageSum else sessionSpan
                if (stageSum == 0 && sessionSpan > 0) {
                    Log.w(TAG, "[sleep] $date 단계분해 없음 → 세션구간 폴백 total=${sessionSpan}분")
                }
                HealthConnectRepository.SleepDay(
                    date = date,
                    totalMinutes = total,
                    deepMinutes = deep,
                    lightMinutes = light,
                    remMinutes = rem,
                    awakeMinutes = awake,
                    sleepStart = pts.mapNotNull { it.startTime }.minOrNull(),
                    sleepEnd = pts.mapNotNull { it.endTime }.maxOrNull(),
                    unstagedMinutes = unstaged,
                )
            }.filterValues { it.totalMinutes > 0 }
                .filterKeys { !it.isBefore(startDate) && !it.isAfter(endDate) }
        } catch (e: Exception) {
            Log.w(TAG, "fetchSleepByDay failed: ${e.message}")
            emptyMap()
        }
    }

    /** 어젯밤 수면 시간선. */
    suspend fun fetchSleepTimeline(date: LocalDate): HealthConnectRepository.SleepTimeline? {
        return try {
            val pts = sleepPointsForNight(date)
            if (pts.isEmpty()) return null
            val refMidnight = date.atStartOfDay(zone).toInstant()
            val segments = mutableListOf<HealthConnectRepository.SleepSegment>()
            val starts = mutableListOf<Instant>()
            val ends = mutableListOf<Instant>()
            pts.forEach { p ->
                val sessions = p.getValue(DataType.SleepType.SESSIONS) ?: return@forEach
                sessions.forEach { s ->
                    s.startTime?.let { starts.add(it) }
                    s.endTime?.let { ends.add(it) }
                    s.stages?.forEach { st ->
                        val sm = ((st.startTime.epochSecond - refMidnight.epochSecond) / 60).toInt()
                        val em = ((st.endTime.epochSecond - refMidnight.epochSecond) / 60).toInt()
                        segments.add(HealthConnectRepository.SleepSegment(sm, em, stageToHc(st.stage)))
                    }
                }
            }
            if (segments.isEmpty() || starts.isEmpty() || ends.isEmpty()) return null
            val startMin = (((starts.minOrNull() ?: refMidnight).epochSecond - refMidnight.epochSecond) / 60).toInt()
            val endMin = (((ends.maxOrNull() ?: refMidnight).epochSecond - refMidnight.epochSecond) / 60).toInt()
            HealthConnectRepository.SleepTimeline(startMin, endMin, segments.sortedBy { it.startMin })
        } catch (e: Exception) {
            Log.w(TAG, "fetchSleepTimeline failed: ${e.message}")
            null
        }
    }

    private fun stageToHc(stage: DataType.SleepType.StageType): Int = when (stage) {
        DataType.SleepType.StageType.DEEP -> HC_DEEP
        DataType.SleepType.StageType.LIGHT -> HC_LIGHT
        DataType.SleepType.StageType.REM -> HC_REM
        DataType.SleepType.StageType.AWAKE -> HC_AWAKE
        else -> 0
    }

    // ============================================================
    // 심박수
    // ============================================================

    /** 구간 내 모든 심박 샘플 (time, bpm). */
    private suspend fun heartRateSamples(start: LocalDateTime, end: LocalDateTime): List<Pair<Instant, Int>> {
        if (!end.isAfter(start)) return emptyList()
        val req = DataTypes.HEART_RATE.readDataRequestBuilder
            .setLocalTimeFilter(LocalTimeFilter.of(start, end))
            .build()
        val points = store.readData(req).dataList
        val out = mutableListOf<Pair<Instant, Int>>()
        points.forEach { p ->
            val series = p.getValue(DataType.HeartRateType.SERIES_DATA)
            if (!series.isNullOrEmpty()) {
                series.forEach { hr -> out.add(hr.startTime to hr.heartRate.toInt()) }
            } else {
                val avg = p.getValue(DataType.HeartRateType.HEART_RATE)
                if (avg != null) out.add(p.startTime to avg.toInt())
            }
        }
        return out
    }

    suspend fun fetchHeartRateByDay(from: LocalDate, to: LocalDate): Map<LocalDate, HealthConnectRepository.HeartRateDay> {
        val startDate = if (from <= to) from else to
        val endDate = if (from <= to) to else from
        val start = startDate.atStartOfDay()
        val end = minOf(endDate.plusDays(1).atStartOfDay(), LocalDateTime.now())
        if (!end.isAfter(start)) return emptyMap()
        return try {
            val samples = heartRateSamples(start, end)
            samples.groupBy { it.first.atZone(zone).toLocalDate() }
                .mapValues { (date, list) ->
                    val bpms = list.map { it.second }
                    HealthConnectRepository.HeartRateDay(
                        date = date,
                        avgBpm = bpms.average().toInt(),
                        maxBpm = bpms.max(),
                        minBpm = bpms.min(),
                    )
                }.filterKeys { !it.isBefore(startDate) && !it.isAfter(endDate) }
        } catch (e: Exception) {
            Log.w(TAG, "fetchHeartRateByDay failed: ${e.message}")
            emptyMap()
        }
    }

    suspend fun fetchHeartRateHourly(date: LocalDate): List<Int?> {
        val start = date.atStartOfDay()
        val end = minOf(date.plusDays(1).atStartOfDay(), LocalDateTime.now())
        return try {
            val byHour = Array(24) { mutableListOf<Int>() }
            heartRateSamples(start, end).forEach { (instant, bpm) ->
                val h = instant.atZone(zone).hour.coerceIn(0, 23)
                byHour[h].add(bpm)
            }
            byHour.map { if (it.isEmpty()) null else it.average().toInt() }
        } catch (e: Exception) {
            Log.w(TAG, "fetchHeartRateHourly failed: ${e.message}")
            List(24) { null }
        }
    }

    // ============================================================
    // SpO2
    // ============================================================

    private suspend fun spo2(start: LocalDateTime, end: LocalDateTime): Pair<Double?, Double?> {
        if (!end.isAfter(start)) return null to null
        return try {
            val req = DataTypes.BLOOD_OXYGEN.readDataRequestBuilder
                .setLocalTimeFilter(LocalTimeFilter.of(start, end))
                .build()
            val values = mutableListOf<Double>()
            store.readData(req).dataList.forEach { p ->
                val series = p.getValue(DataType.BloodOxygenType.SERIES_DATA)
                if (!series.isNullOrEmpty()) {
                    series.forEach { values.add(it.oxygenSaturation.toDouble()) }
                } else {
                    val v = p.getValue(DataType.BloodOxygenType.OXYGEN_SATURATION)
                    if (v != null) values.add(v.toDouble())
                }
            }
            if (values.isEmpty()) null to null else values.average() to values.min()
        } catch (e: Exception) {
            Log.w(TAG, "spo2 failed: ${e.message}")
            null to null
        }
    }

    // ============================================================
    // 종합 (백엔드 sync 용)
    // ============================================================

    suspend fun fetchWatchData(targetDate: LocalDate): WatchDataRequest {
        val dayStart = targetDate.atStartOfDay()
        val dayEnd = minOf(targetDate.plusDays(1).atStartOfDay(), LocalDateTime.now())

        val total = try { stepsTotal(dayStart, dayEnd) } catch (e: Exception) { null }
        val hourly = try { if (total != null) stepsHourly(targetDate) else null } catch (e: Exception) { null }

        // 수면
        var sleepTotal: Int? = null; var deep: Int? = null; var light: Int? = null
        var rem: Int? = null; var awake: Int? = null
        var sleepStart: String? = null; var sleepEnd: String? = null
        try {
            val pts = sleepPointsForNight(targetDate)
            if (pts.isNotEmpty()) {
                var d = 0; var l = 0; var r = 0; var a = 0; var sessionSpan = 0; var unstaged = 0
                pts.forEach { p -> val sm = stageMinutes(p); d += sm[0]; l += sm[1]; r += sm[2]; a += sm[3]; sessionSpan += sm[4]; unstaged += sm[5] }
                // [260728/260730] 총 수면시간 = 4단계 합 + 단계 미분류(UNDEFINED). HC 경로와 동일 정의.
                //   단계 분해가 전혀 없는 날만 세션 구간으로 폴백
                //   (폴백 없으면 sleep_total_minutes=null → 백엔드 sleep_score 미측정 → 수면 20점 실점).
                val stageSum = d + l + r + a + unstaged
                if (stageSum == 0 && sessionSpan > 0) {
                    Log.w(TAG, "[sleep] $targetDate 단계분해 없음 → 세션구간 폴백 total=${sessionSpan}분")
                }
                val t = if (stageSum > 0) stageSum else sessionSpan
                sleepTotal = t.takeIf { it > 0 }
                deep = d.takeIf { it > 0 }; light = l.takeIf { it > 0 }
                rem = r.takeIf { it > 0 }; awake = a.takeIf { it > 0 }
                sleepStart = isoOrNull(pts.mapNotNull { it.startTime }.minOrNull())
                sleepEnd = isoOrNull(pts.mapNotNull { it.endTime }.maxOrNull())
            }
        } catch (e: Exception) { Log.w(TAG, "[sleep] ${e.message}") }

        // 심박
        var hrAvg: Int? = null; var hrMin: Int? = null; var hrMax: Int? = null
        val hrSamples = mutableListOf<HeartRateSample>()
        try {
            val samples = heartRateSamples(dayStart, dayEnd)
            if (samples.isNotEmpty()) {
                val bpms = samples.map { it.second }
                hrAvg = bpms.average().toInt(); hrMin = bpms.min(); hrMax = bpms.max()
                val step = (samples.size / 200).coerceAtLeast(1)
                samples.sortedBy { it.first }.forEachIndexed { i, s ->
                    if (i % step == 0) hrSamples.add(HeartRateSample(s.first.toString(), s.second))
                }
            }
        } catch (e: Exception) { Log.w(TAG, "[hr] ${e.message}") }

        val (spo2Avg, spo2Min) = try { spo2(dayStart, dayEnd) } catch (e: Exception) { null to null }

        return WatchDataRequest(
            logDate = targetDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
            steps = total,
            stepsHourly = hourly,
            sleepTotalMinutes = sleepTotal,
            sleepDeepMinutes = deep,
            sleepLightMinutes = light,
            sleepRemMinutes = rem,
            sleepAwakeMinutes = awake,
            sleepStartAt = sleepStart,
            sleepEndAt = sleepEnd,
            avgHeartRate = hrAvg,
            minHeartRate = hrMin,
            maxHeartRate = hrMax,
            heartRateSamples = hrSamples.ifEmpty { null },
            spo2Avg = spo2Avg,
            spo2Min = spo2Min,
        )
    }

    /** 최근 7일 SDK 데이터 존재 여부 (걸음 OR 수면). */
    suspend fun hasAnyData(): Boolean = try {
        val end = LocalDateTime.now()
        val start = end.minusDays(7)
        // [v1.0 버그픽스] stepsTotal이 Int? 반환으로 변경됨 — null이면 데이터 없음 = false
        ((stepsTotal(start, end) ?: 0) > 0) || run {
            val req = DataTypes.SLEEP.readDataRequestBuilder
                .setLocalTimeFilter(LocalTimeFilter.of(start, end)).build()
            store.readData(req).dataList.isNotEmpty()
        }
    } catch (e: Exception) { false }

    /** 검증/디버그용 요약. */
    suspend fun debugSummary(): String = try {
        val today = LocalDate.now()
        val wd = fetchWatchData(today)
        "걸음 ${wd.steps ?: "-"}보 / 수면 ${wd.sleepTotalMinutes ?: "-"}분 / 심박 ${wd.avgHeartRate ?: "-"}bpm / SpO2 ${wd.spo2Avg?.let { "%.0f".format(it) } ?: "-"}%"
    } catch (e: Exception) {
        "오류: ${e.javaClass.simpleName} ${e.message}"
    }
}
