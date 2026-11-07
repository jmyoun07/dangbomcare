package com.dangbomcare.app.data.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.dangbomcare.app.data.api.HeartRateSample
import com.dangbomcare.app.data.api.WatchDataRequest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
// [260726] 공개 진입점을 IO 디스패처로 옮기기 위한 import (Main 스레드 프레임 드랍 방지)
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Galaxy Fit3 → Samsung Health → Health Connect → 이 클래스 → 백엔드 /api/watch-data
 */
class HealthConnectRepository(private val context: Context) {

    companion object {
        private const val TAG = "HCRepo"

        /**
         * [260726] 앱 전역 기준 시간대 — 날짜 산출(log_date)과 Health Connect 조회 경계를 모두 KST로 통일.
         *
         * ⚠️ 전제: 사용자의 실제 생활 시간대가 KST 다 (국내 전용 서비스, 백엔드 log_date 도 KST 고정).
         *   기존엔 경로마다 달랐다 — WatchSyncWorker=기기 로컬, WatchSyncActions=KST, HC 조회=systemDefault().
         *   그래서 해외 체류·기기 시각 오류 시 같은 물리 데이터가 서로 다른 log_date 로 기록되고,
         *   KST 로 고른 날짜를 systemDefault 경계로 읽어 하루 어긋난 구간을 집계하는 문제가 있었다.
         */
        val KST: ZoneId = ZoneId.of("Asia/Seoul")

        // v1.9에서 실제 사용하는 권한만 (Google Play 정책 — 사용 안 하는 권한 요청 금지)
        val PERMISSIONS = setOf(
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
            HealthPermission.getReadPermission(OxygenSaturationRecord::class),
            // 30일 이전 과거 데이터 접근 — 권한 부여 시점 이전 데이터를 읽으려면 필수
            HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY,
            // [Codex 검토 위험] 백그라운드 자동 동기화 워커에서 HC read 가능하려면 필수
            HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND,
        )

        val PERMS_CONTRACT = PermissionController.createRequestPermissionResultContract()

        /**
         * Samsung Health Data SDK 직접 읽기 소스 (ServiceLocator.init에서 1회 주입).
         * companion(static)이라 모든 HealthConnectRepository 인스턴스가 공유 →
         * 화면들이 new HealthConnectRepository(context)를 만들어도 SDK 경로가 적용됨.
         * SDK 준비되면 각 fetch가 SDK 우선, 아니면 HC fallback.
         * → Samsung Health → HC 동기화 불완전(수면 누락·과거 미동기) 폰 대응.
         */
        var samsungSource: SamsungHealthSource? = null

        // ───────────────────────────────────────────────────────────────
        // [대표님 전용 SDK 테스트] 기기별 'SDK 직접 읽기' opt-in 플래그 (SharedPreferences).
        //   기본 false → 모든 일반 환자는 기존 Health Connect 경로 (동작 변화 0).
        //   대표님 폰에서만 진단 화면(설정→워치 데이터 점검) 토글로 ON → 그 기기에서만 SDK 직접 읽기.
        //   Samsung Partner 정식 승인 후엔 이 플래그를 기본 true로 풀어 전체 적용 가능.
        private const val SDK_FLAG_PREFS = "dangdang_sdk_flags"
        private const val KEY_SDK_DIRECT_READ = "sdk_direct_read_enabled"

        fun isSdkDirectReadEnabled(context: Context): Boolean =
            context.getSharedPreferences(SDK_FLAG_PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_SDK_DIRECT_READ, false)

        fun setSdkDirectReadEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(SDK_FLAG_PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_SDK_DIRECT_READ, enabled).apply()
        }
    }

    /**
     * 이 기기에서 SDK 직접 읽기가 실제로 활성인지 (UI 권한 안내·동기화 게이트가 SDK를 인지하도록 public).
     *
     * [SDK 자동화] 기기가 SDK 후보(삼성헬스 설치 + API29+ + store 초기화 가능)면 자동으로 SDK 경로 후보.
     *   → 옵트인(7-tap) 없이도, 삼성헬스 SDK 권한만 부여되면(isReady=true) 데이터 읽기가 SDK 우선.
     *   - candidate = isPermissionFlowAvailable()  (주 판정)
     *               OR DEBUG 빌드          (개발자 디버깅용 — 기존 동작 유지)
     *               OR opt-in 플래그 ON     (레거시 수동 오버라이드 / 킬스위치는 아니지만 강제 ON 경로)
     *   - candidate 아니면 즉시 false → HC fallback.
     *   - candidate 여도 SDK 미준비(isReady=false: 개발자모드 OFF·미승인·권한 미부여)면 false → HC fallback.
     *     → '실제 동작 가능'의 최종 게이트는 여전히 isReady(). 따라서 후보 기기라도 못 읽으면 HC로 안전 폴백.
     */
    // [260726] SharedPreferences 읽기 + 삼성 SDK IPC(isReady) → Main 스레드에서 돌면 안 됨 → IO로.
    suspend fun isSdkDirectActive(): Boolean = withContext(Dispatchers.IO) {
        val candidate = samsungSource?.isPermissionFlowAvailable() == true ||
            com.dangbomcare.app.BuildConfig.DEBUG ||
            isSdkDirectReadEnabled(context)
        if (!candidate) return@withContext false
        try { samsungSource?.isReady() == true } catch (_: Exception) { false }
    }

    /**
     * [SDK 자동화 — 읽기 폴백용] HC로 워치 데이터를 읽을 수 있는가 (SDK 경로와 무관, 비재귀).
     *   fetchWatchData 등에서 'Samsung이 활성이지만 결과가 비었거나 실패' 했을 때
     *   HC에 데이터가 있으면 폴백하기 위한 게이트. isSdkDirectActive()를 거치지 않아 재귀/루프 없음.
     */
    private suspend fun canReadHealthConnect(): Boolean =
        try { isSdkAvailable() && checkPermissions().any } catch (_: Exception) { false }

    /**
     * [SDK 자동화 — 읽기 폴백 판정] all-null = 워치 데이터 없음(steps/sleep/hr/spo2 전부 null).
     *   WatchSyncActions.isAllNull 과 동일 기준 — SDK 결과가 비었을 때 HC 폴백 트리거에 사용.
     */
    private fun isAllNull(d: WatchDataRequest): Boolean =
        d.steps == null && d.sleepTotalMinutes == null &&
            d.avgHeartRate == null && d.spo2Avg == null

    /**
     * [SDK 자동화 — 부분 결과 보강] Samsung 결과에 null 필드가 하나라도 있으면 true.
     *   → Samsung이 일부 필드(예: 걸음만)만 채웠을 때 HC로 나머지를 메우기 위한 판정.
     *   logDate는 항상 채워지므로 데이터 필드만 본다.
     */
    private fun hasAnyNullField(d: WatchDataRequest): Boolean =
        d.steps == null || d.stepsHourly == null ||
            d.sleepTotalMinutes == null || d.sleepDeepMinutes == null ||
            d.sleepLightMinutes == null || d.sleepRemMinutes == null ||
            d.sleepAwakeMinutes == null || d.sleepStartAt == null || d.sleepEndAt == null ||
            d.avgHeartRate == null || d.minHeartRate == null || d.maxHeartRate == null ||
            d.heartRateSamples == null || d.spo2Avg == null || d.spo2Min == null

    /**
     * [SDK 자동화 — 부분 결과 병합] 필드별로 Samsung 우선, 없으면 HC로 채움(samsung.field ?: hc.field).
     *   logDate는 Samsung 것 유지(둘 다 동일 targetDate). 차트가 아닌 일일 종합 결과 병합에 사용.
     *
     * ⚠️ [260728 통합검증] **수면 블록만은 필드별 병합을 하지 않는다.**
     *   총 수면시간이 '4단계 합'으로 정의된 이후 `total == deep+light+rem+awake` 가 계약이 됐다.
     *   그런데 이 병합은 `hasAnyNullField` 가 true 면(예: spo2·stepsHourly 만 비어도) 실행되고,
     *   그때 Samsung 의 어느 한 단계가 0이라 null 로 올라오면(`takeIf { it > 0 }`) 그 자리에
     *   **HC 가 다른 세션 그룹핑으로 계산한 값**이 끼워진다 → total(삼성) ≠ 단계합(삼성3개+HC1개).
     *   화면 분포와 총합이 어긋나고, AI 오라벨 가드의 ⓥ 거부권(단계 실제값 대조)도 남의 소스 값으로
     *   판정하게 된다. 두 소스가 같은 규칙을 쓰더라도 세션 선택이 달라 값은 같지 않다.
     *   → 수면 7필드는 **한 소스에서 통째로** 가져온다. Samsung 에 수면이 있으면 Samsung 것 전부,
     *     없으면 HC 것 전부. 이러면 어느 쪽을 쓰든 계약이 깨지지 않는다.
     */
    private fun mergeWatchData(samsung: WatchDataRequest, hc: WatchDataRequest): WatchDataRequest {
        val sleep = if (samsung.sleepTotalMinutes != null) samsung else hc
        return WatchDataRequest(
            logDate = samsung.logDate,
            steps = samsung.steps ?: hc.steps,
            stepsHourly = samsung.stepsHourly ?: hc.stepsHourly,
            sleepTotalMinutes = sleep.sleepTotalMinutes,
            sleepDeepMinutes = sleep.sleepDeepMinutes,
            sleepLightMinutes = sleep.sleepLightMinutes,
            sleepRemMinutes = sleep.sleepRemMinutes,
            sleepAwakeMinutes = sleep.sleepAwakeMinutes,
            sleepStartAt = sleep.sleepStartAt,
            sleepEndAt = sleep.sleepEndAt,
            avgHeartRate = samsung.avgHeartRate ?: hc.avgHeartRate,
            minHeartRate = samsung.minHeartRate ?: hc.minHeartRate,
            maxHeartRate = samsung.maxHeartRate ?: hc.maxHeartRate,
            heartRateSamples = samsung.heartRateSamples ?: hc.heartRateSamples,
            spo2Avg = samsung.spo2Avg ?: hc.spo2Avg,
            spo2Min = samsung.spo2Min ?: hc.spo2Min,
        )
    }

    /**
     * [SDK 자동화 — 차트 병합] 요청 범위 [from, to]의 모든 날짜가 keys에 들어있는지.
     *   true면 Samsung이 범위를 완전히 덮어 HC 추가 읽기가 불필요(효율).
     */
    private fun coversAllDays(keys: Set<LocalDate>, from: LocalDate, to: LocalDate): Boolean {
        val start = if (from <= to) from else to
        val end = if (from <= to) to else from
        var d = start
        while (!d.isAfter(end)) {
            if (d !in keys) return false
            d = d.plusDays(1)
        }
        return true
    }

    /**
     * [SDK 자동화 — 차트 병합] 키별 병합: Samsung 값이 있으면 유지, 없는 키만 HC로 채움.
     *   둘 중 하나가 null이면 나머지를 그대로 사용(빈 맵 포함). 차트 fetch들 공통 사용.
     */
    private fun <K, V> mergeByKey(samsung: Map<K, V>?, hc: Map<K, V>?): Map<K, V> {
        if (samsung.isNullOrEmpty()) return hc ?: emptyMap()
        if (hc.isNullOrEmpty()) return samsung
        val out = HashMap<K, V>(hc)   // HC를 바닥에 깔고
        out.putAll(samsung)           // Samsung으로 덮어써 Samsung 우선
        return out
    }

    /**
     * 워치 데이터를 읽을 수 있는 상태인지 = SDK 직접 읽기 활성 OR Health Connect 권한 보유.
     *   '워치 권한 필요' 안내·자동 동기화 게이트는 반드시 이걸 기준으로 판단해야
     *   SDK 사용자(대표님 등)에게 'HC 권한 필요'를 잘못 띄우거나 동기화를 막지 않음.
     *   일반 환자(SDK 비활성)는 기존 HC 권한 기준 그대로 동작(무변화).
     */
    // [260726] HC 권한 조회(IPC)를 포함 → IO로.
    suspend fun canReadWatchData(): Boolean = withContext(Dispatchers.IO) {
        if (isSdkDirectActive()) return@withContext true
        try { isSdkAvailable() && checkPermissions().any } catch (_: Exception) { false }
    }

    // HC와 상호배타 — 각 fetch에서 SDK/HC 둘 중 하나만 실행 (isSdkDirectActive로 위임).
    private suspend fun useSamsung(): Boolean = isSdkDirectActive()

    fun isSdkAvailable(): Boolean {
        return HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    }

    fun getSdkStatus(): Int = HealthConnectClient.getSdkStatus(context)

    private fun client(): HealthConnectClient = HealthConnectClient.getOrCreate(context)

    // [260726] Health Connect 권한 조회는 바인더 IPC → IO 디스패처에서.
    suspend fun checkPermissions(): PermStatus = withContext(Dispatchers.IO) {
        try {
            val granted = client().permissionController.getGrantedPermissions()
            PermStatus(
                all = granted.containsAll(PERMISSIONS),
                any = granted.isNotEmpty(),
                grantedCount = granted.size,
                totalCount = PERMISSIONS.size
            )
        } catch (e: Exception) {
            PermStatus(false, false, 0, PERMISSIONS.size)
        }
    }

    data class PermStatus(val all: Boolean, val any: Boolean, val grantedCount: Int, val totalCount: Int)

    /** 현재 부여된 HC 권한 집합 (실패 시 빈 집합). */
    // [260726] 권한 조회 IPC → IO 디스패처에서.
    suspend fun grantedPermissions(): Set<String> = withContext(Dispatchers.IO) {
        try {
            client().permissionController.getGrantedPermissions()
        } catch (_: Exception) { emptySet() }
    }

    /**
     * [우노] 포그라운드 90일 백필 가능 판정 = 기본 read 권한 + 과거 기록(HISTORY) 권한.
     *   백그라운드(IN_BACKGROUND) 권한은 앱이 떠 있는 동안의 수동 복구엔 불필요 → 제외.
     *   (시니어가 백그라운드 토글을 못 켜도 '데이터 다시 가져오기'는 동작하게.)
     */
    fun canBackfillWith(granted: Set<String>): Boolean {
        val needed = PERMISSIONS - HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND
        return granted.containsAll(needed)
    }

    suspend fun canReadForBackfill(): Boolean = canBackfillWith(grantedPermissions())

    /**
     * [v1.0] Health Connect에 최근 7일 사이 워치 데이터가 한 건이라도 있는지 확인.
     *   - Samsung Health → HC 동기화가 안 되는 사용자(특히 한국 Samsung 폰)에게 인앱 가이드를 보여주기 위함.
     *   - 걸음수 OR 수면 OR 심박수 중 하나라도 있으면 true.
     */
    // [260726] HC 조회 3종(IPC+역직렬화) → IO 디스패처에서. 날짜 경계도 KST 통일.
    suspend fun hasAnyHCData(): Boolean = withContext(Dispatchers.IO) {
        val rangeStart = LocalDate.now(KST).minusDays(7).atStartOfDay(KST).toInstant()
        val rangeEnd = Instant.now()
        val filter = TimeRangeFilter.between(rangeStart, rangeEnd)
        val hc = try { client() } catch (_: Exception) { return@withContext false }

        suspend fun any(block: suspend () -> Int): Boolean =
            try { block() > 0 } catch (_: Exception) { false }
        // 걸음·심박은 손상 레코드(시작≥종료)/페이지 한계로 readRecords가 실패할 수 있어 집계로 확인.
        //   → 걸음만/심박만 있는 사용자가 손상 레코드 때문에 "데이터 없음"으로 오판되는 것 방지.
        suspend fun aggHasLong(metric: androidx.health.connect.client.aggregate.AggregateMetric<Long>): Boolean =
            try { (hc.aggregate(AggregateRequest(setOf(metric), filter))[metric] ?: 0L) > 0L } catch (_: Exception) { false }

        if (aggHasLong(StepsRecord.COUNT_TOTAL)) return@withContext true
        if (aggHasLong(HeartRateRecord.BPM_AVG)) return@withContext true
        if (any { hc.readRecords(ReadRecordsRequest(SleepSessionRecord::class, filter)).records.size }) return@withContext true
        false
    }

    /**
     * 어제 자정~오늘 현재까지의 워치 데이터를 모두 읽어서 백엔드 WatchDataRequest로 패킹.
     * @param targetDate 보내려는 log_date (보통 어제)
     */
    // [260726] 기준일은 KST (백엔드 log_date 와 동일 기준). 무거운 HC/SDK 읽기·가공이라 IO 디스패처에서 실행.
    suspend fun fetchWatchData(
        targetDate: LocalDate = LocalDate.now(KST).minusDays(1),
    ): WatchDataRequest = withContext(Dispatchers.IO) {
        // [SDK 자동화 — 읽기 폴백/병합] Samsung이 활성이면 SDK 우선.
        //   - SDK 결과가 완전(모든 필드 non-null)하면 그대로 반환(HC 추가 읽기 없음 — 효율).
        //   - SDK 결과에 null 필드가 하나라도 있고 + HC 권한이 있으면 → HC를 읽어 '필드별 병합'
        //     (samsung.field ?: hc.field). Samsung이 걸음만 줬을 때 수면/심박/SpO2를 HC가 채워 무손실.
        //   - SDK 결과가 all-null이거나 throw면 사실상 전부 HC 값으로 채워짐(병합 결과 = HC).
        //   - HC를 못 읽으면 SDK 결과 그대로 반환(기존 동작 유지 — 폴백/병합 없음과 동일).
        if (useSamsung()) samsungSource?.let { src ->
            val sdkResult = try {
                src.fetchWatchData(targetDate)
            } catch (e: Exception) {
                Log.w(TAG, "[SDK] fetchWatchData 실패 → HC 폴백 검토: ${e.javaClass.simpleName} ${e.message}")
                null
            }
            // SDK가 모든 필드를 채웠으면 HC 읽기 불필요 — 그대로 반환.
            if (sdkResult != null && !hasAnyNullField(sdkResult)) return@withContext sdkResult
            // 여기 도달 = SDK 결과에 null 필드 있음(부분/all-null) 또는 throw.
            if (!canReadHealthConnect()) {
                return@withContext sdkResult ?: WatchDataRequest(logDate = targetDate.format(DateTimeFormatter.ISO_LOCAL_DATE))
            }
            // HC 읽기로 빈 필드 보강. HC 읽기가 실패하면 절대 throw하지 말고 SDK 결과를 반환.
            val hcResult = try {
                fetchWatchDataFromHc(targetDate)
            } catch (e: Exception) {
                Log.w(TAG, "[SDK] HC 병합 읽기 실패 → SDK 결과 유지: ${e.javaClass.simpleName} ${e.message}")
                null
            }
            return@withContext when {
                hcResult == null -> sdkResult ?: WatchDataRequest(logDate = targetDate.format(DateTimeFormatter.ISO_LOCAL_DATE))
                sdkResult == null -> hcResult  // SDK throw → 전부 HC
                else -> {
                    Log.d(TAG, "[SDK] 부분 결과 HC 병합: $targetDate")
                    mergeWatchData(sdkResult, hcResult)
                }
            }
        }
        fetchWatchDataFromHc(targetDate)
    }

    /**
     * Health Connect 단독 읽기 (SDK 경로와 무관, 비재귀).
     *   fetchWatchData에서 'Samsung 없음' 또는 'Samsung 부분 결과 보강' 시 호출.
     */
    private suspend fun fetchWatchDataFromHc(targetDate: LocalDate): WatchDataRequest {
        // [260726] 조회 경계를 KST로 고정 — 날짜(log_date)를 KST로 고르므로 읽는 구간도 같은 기준이어야
        //   해외 체류·기기 시각 오류 시 "하루 어긋난 구간"을 집계하지 않는다. (전제: 생활 시간대 = KST)
        val zone = KST
        val dayStart = targetDate.atStartOfDay(zone).toInstant()
        val dayEnd = targetDate.plusDays(1).atStartOfDay(zone).toInstant()
        val now = Instant.now()
        // [v1.0 회귀 fix — 대표님 폰] now 클램핑은 미래 시각 조회 방지용인데, 폰 시각이 비정상(과거)이면
        //   rangeEnd가 dayStart보다 이전이 되어 TimeRangeFilter가 "startTime must be before endTime" 던짐
        //   → 걸음/심박/SpO2 전부 0. 수면은 now 안 써서 정상이라 "걸음만 0" 증상.
        //   해결: rangeEnd가 dayStart보다 이후가 아니면 dayEnd로 fallback (HC는 미래 데이터 없으니 안전).
        val rangeEndRaw = if (dayEnd.isAfter(now)) now else dayEnd
        val rangeEnd = if (rangeEndRaw.isAfter(dayStart)) rangeEndRaw else dayEnd

        // [v1.1 TODO - Codex 검토] 일시적 권한 거부(SecurityException) 시 "데이터 없음"과 구분 필요.
        //   현재: null 반환 → 백엔드 full replace로 어제 좋은 데이터 덮어쓸 위험 (드물지만 위험).
        //   v1.1 정식 fix 방향: WatchDataRequest schema에 `data_explicitly_missing` 필드 추가, 백엔드는
        //   이 신호 있을 때만 erase, 없으면 partial update 유지. 클라이언트 fetchWatchData가 records.isEmpty
        //   인 경우 true, SecurityException 발생 케이스는 false로 보냄.
        //   v1.0에선 호출처 20곳+ try-catch 누락 risk가 더 크므로 현행 유지 (드문 케이스).

        val hc = client()

        // 걸음수 — 핵심 로직 (2026-05-19 재수정):
        //
        // ⚠️ Samsung Health(shealth)는 시간대별 raw 데이터를 가지고 있어도 Health Connect로는
        // "일일 통합 1개" 레코드만 동기화. 균등 분배할 수밖에 없어 식후 걷기 분석 불가.
        //
        // ✅ 해결: Android 시스템 + Google Fit source는 시간대별 단기 레코드를 그대로 보내고 있음!
        //    → 총합 = 가장 큰 source (보통 shealth, 가장 최신)
        //    → 시간대별 분포 = 단기 레코드가 가장 많은 source (android 또는 fitness)
        //    → 시간대별 합을 총합에 맞춰 비례 조정
        //
        // [v1.0 버그픽스] records.isEmpty() 가드 — 워치 미연결/동기화 안 된 사용자에게
        // steps=0을 전송해서 AI가 "활동량 부족"으로 오해하던 문제 차단.
        // 데이터 없음 = null (수면/심박/SpO2 패턴과 통일) → 백엔드는 None 처리 → 점수/피드백 제외.
        // ① 총합 = HealthConnect 집계 API. [v1.0 버그픽스 — 대표님 폰]
        //   개별 readRecords는 손상 레코드(시작≥종료) 만나면 통째로 실패("startTime must be before endTime")하고
        //   1000건 페이지 한계로 활동 많은 날 잘림. 집계는 둘 다 영향 없음(차트 fetchStepsByDay와 동일 방식).
        //   데이터 없음/0 = null (수면·심박·SpO2와 통일) → 백엔드 None 처리 → 점수/피드백 제외.
        val stepsTotal: Int? = try {
            val agg = hc.aggregate(
                AggregateRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(dayStart, rangeEnd)
                )
            )
            agg[StepsRecord.COUNT_TOTAL]?.toInt()?.takeIf { it > 0 }
        } catch (e: SecurityException) {
            Log.w(TAG, "[steps] 권한 없음: ${e.message}"); null
        } catch (e: Exception) {
            Log.w(TAG, "[steps] 집계 실패: ${e.javaClass.simpleName} - ${e.message}"); null
        }

        // ② 시간대별 분포(식후 걷기 분석용) — 개별 단기 레코드 필요. 손상 레코드로 실패해도 위 총합엔 영향 없게 분리.
        val stepsHourly: List<Int>? = if (stepsTotal == null) null else try {
            val total: Int = stepsTotal
            val records = hc.readRecords(
                ReadRecordsRequest(StepsRecord::class, TimeRangeFilter.between(dayStart, rangeEnd))
            ).records
            if (records.isEmpty()) null else {
                val bySource = records.groupBy { it.metadata.dataOrigin.packageName }
                val totalSource = bySource.values.maxByOrNull { recs -> recs.sumOf { it.count.toInt() } }

                // 단기 레코드(<60분)가 가장 많은 source 로 시간대별 분포 추정
                val shortThreshold = 60 * 60L  // 60분 (초)
                val hourlySource = bySource.entries
                    .map { (src, recs) ->
                        val shortRecords = recs.filter {
                            (it.endTime.epochSecond - it.startTime.epochSecond) < shortThreshold
                        }
                        src to shortRecords
                    }
                    .filter { it.second.isNotEmpty() }
                    .maxByOrNull { it.second.size }
                    ?.second
                    ?: totalSource.orEmpty()

                val hourly = IntArray(24)
                hourlySource.forEach { r ->
                    val sZ = r.startTime.atZone(zone)
                    val eZ = r.endTime.atZone(zone)
                    if (sZ.hour == eZ.hour && sZ.toLocalDate() == eZ.toLocalDate()) {
                        hourly[sZ.hour] += r.count.toInt()
                    } else {
                        // 시간대를 걸치면 시간 비례 분배 (자정 포함)
                        val totalSec = (r.endTime.epochSecond - r.startTime.epochSecond).coerceAtLeast(1L)
                        var cursor = r.startTime
                        var remaining = r.count.toInt()
                        while (cursor.isBefore(r.endTime)) {
                            val cZ = cursor.atZone(zone)
                            val nextHour = cZ.withMinute(0).withSecond(0).withNano(0).plusHours(1).toInstant()
                            val sliceEnd = if (nextHour.isBefore(r.endTime)) nextHour else r.endTime
                            val sliceSec = (sliceEnd.epochSecond - cursor.epochSecond).coerceAtLeast(0L)
                            val portion = (r.count.toInt().toLong() * sliceSec / totalSec).toInt()
                            val isLast = !sliceEnd.isBefore(r.endTime)
                            val alloc = if (isLast) remaining else portion
                            if (cZ.toLocalDate() == targetDate) {
                                val h = cZ.hour.coerceIn(0, 23)
                                hourly[h] += alloc
                            }
                            remaining -= alloc
                            cursor = sliceEnd
                        }
                    }
                }

                // 시간대별 합을 "집계 총합"에 맞춰 비례 조정 (두 값 source가 달라도 합이 총합과 일치하게)
                val hourlySum = hourly.sum()
                if (hourlySum > 0 && total > 0 && hourlySum != total) {
                    val ratio = total.toDouble() / hourlySum
                    for (i in 0 until 24) hourly[i] = (hourly[i] * ratio).toInt()
                    val newSum = hourly.sum()
                    if (newSum != total) {
                        val maxIdx = hourly.indices.maxByOrNull { hourly[it] } ?: 0
                        hourly[maxIdx] = (hourly[maxIdx] + (total - newSum)).coerceAtLeast(0)
                    }
                }
                hourly.toList()
            }
        } catch (e: Exception) {
            Log.w(TAG, "[steps] 시간대별 읽기 실패(총합은 집계로 확보됨): ${e.javaClass.simpleName} - ${e.message}")
            null
        }

        // 수면 (해당 날짜 + 다음날 새벽까지 — 보통 23~7시)
        var sleepTotal: Int? = null
        var sleepDeep: Int? = null
        var sleepLight: Int? = null
        var sleepRem: Int? = null
        var sleepAwake: Int? = null
        var sleepStart: String? = null
        var sleepEnd: String? = null

        try {
            // 🛌 사용자 피드백 — "21일 새벽 3:21~8:00 수면은 사실 5/20에 잔 것이므로 5/20 밤 수면으로 표시"
            // 새 매핑 규칙: "targetDate 밤 수면" = startTime이 [targetDate 정오, 다음날 정오) 안인 세션
            //   • 21일 3:21 시작 → 20일 12:00 ~ 21일 12:00 윈도우 안 → 5/20 밤 ✅
            //   • 21일 22:00 시작 → 21일 12:00 ~ 22일 12:00 안 → 5/21 밤 ✅
            // 이전: endTime의 날짜로 매핑 (= 기상한 날) → 새벽 기상 시 다음날 데이터로 잡히는 버그.
            val nightWindowStart = targetDate.atTime(12, 0).atZone(zone).toInstant()
            val nightWindowEnd = targetDate.plusDays(1).atTime(12, 0).atZone(zone).toInstant()
            // readRecords 범위는 늦잠(다음날 오후까지)도 포함하도록 buffer
            val sleepStartRange = nightWindowStart
            val sleepEndRange = nightWindowEnd.plus(java.time.Duration.ofHours(6))
            val allSessions = hc.readRecords(
                ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(sleepStartRange, sleepEndRange))
            ).records

            // 잠자리에 든 시각(startTime)이 nightWindow 안인 세션만 "targetDate 밤"으로 인정
            val sessions = allSessions.filter {
                !it.startTime.isBefore(nightWindowStart) && it.startTime.isBefore(nightWindowEnd)
            }.filter { s ->
                // 사용자 피드백 (5/26): 오후 4~5시 1시간 낮잠이 오늘 수면으로 잡힘
                // → 시작이 낮 시간대(9~18시) + 길이가 3시간 미만이면 일별 수면 집계에서 제외
                val startHour = s.startTime.atZone(zone).toLocalDateTime().hour
                val durMin = (s.endTime.epochSecond - s.startTime.epochSecond) / 60
                !(startHour in 9..17 && durMin < 180)
            }

            // ⭐ Samsung Health는 잠을 여러 세션으로 쪼개 기록 (화장실 등 깨어남)
            // 모든 세션 합산해야 실제 수면 시간 나옴
            if (sessions.isNotEmpty()) {
                // 가장 늦은 종료 시각 ≈ 기상 시각으로 보고, 가까운 세션들을 "이번 밤 잠"으로 묶음
                val sortedByEnd = sessions.sortedByDescending { it.endTime }
                val nightSessions = mutableListOf(sortedByEnd[0])
                // 직전 세션의 종료와 다음 세션의 시작 간격 4시간 이하면 같은 밤 잠으로 묶음
                for (i in 1 until sortedByEnd.size) {
                    val prevStart = nightSessions.last().startTime
                    val cur = sortedByEnd[i]
                    val gap = (prevStart.epochSecond - cur.endTime.epochSecond) / 3600.0
                    if (gap >= 0 && gap <= 4) {
                        nightSessions.add(cur)
                    } else break
                }

                var sessionSpanMin = 0
                var deepMin = 0
                var lightMin = 0
                var remMin = 0
                var awakeMin = 0
                // [260729] STAGE_TYPE_SLEEPING(2) 전용 누적기 — 총합에만 들어가고 단계 분포에는 안 들어간다.
                //   deep/light/rem 어디에도 넣으면 안 되는 이유: SLEEPING 은 "잠은 잤다"만 알고 어느 단계인지 모른다.
                var unstagedMin = 0
                nightSessions.forEach { s ->
                    sessionSpanMin += ((s.endTime.epochSecond - s.startTime.epochSecond) / 60).toInt()
                    s.stages.forEach { stage ->
                        val mins = ((stage.endTime.epochSecond - stage.startTime.epochSecond) / 60).toInt()
                        // ⚠️ 공식 SleepSessionRecord.StageType 상수 (Codex 검토에서 수정)
                        // 4=LIGHT, 5=DEEP, 6=REM, 1=AWAKE, 7=AWAKE_IN_BED, 2=SLEEPING(단계 미분류)
                        when (stage.stage) {
                            SleepSessionRecord.STAGE_TYPE_DEEP -> deepMin += mins       // 5
                            SleepSessionRecord.STAGE_TYPE_LIGHT -> lightMin += mins     // 4
                            SleepSessionRecord.STAGE_TYPE_REM -> remMin += mins         // 6
                            SleepSessionRecord.STAGE_TYPE_AWAKE,                        // 1
                            SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> awakeMin += mins  // 7
                            // ⭐ [260729 버그픽스] 세부 단계를 안 나누고 "잠"으로만 기록하는 기기(타사 워치 다수)가 있다.
                            //   이 분기가 없으면 SLEEPING 구간이 통째로 버려진다. AWAKE 를 같이 기록하는 기기는
                            //   stageSum = awake(예: 25분) 라 폴백(stageSum==0)도 안 걸려 "7시간 30분 → 25분"이 업로드됐다.
                            //   → 수면부족 경고 오진. 단계 미분류이므로 총합에만 반영한다.
                            SleepSessionRecord.STAGE_TYPE_SLEEPING -> unstagedMin += mins  // 2
                            // STAGE_TYPE_OUT_OF_BED(3) / STAGE_TYPE_UNKNOWN(0) 은 의도적 제외 — 잠이 아니다.
                        }
                    }
                }
                // [260728 확정] 총 수면시간 = 4단계 합(체력회복잠 + 얕은잠 + 렘 + 깬 시간) + 단계 미분류(SLEEPING).
                //   센서가 어느 단계로도 판정하지 못한 구간(실측 17~37분)이 총합에 섞이지 않게 함.
                //   삼성 SDK 경로(SamsungHealthSource.stageMinutes)와 동일 정의 — 워치를 바꿔도 정의가 안 바뀌게.
                //   (삼성 SDK enum 에도 StageType.UNDEFINED = 단계 미분류가 있어 260730에 같은 처리로 맞췄다.)
                // ⚠️ 폴백 필수: 단계 분해가 없는 날(s.stages 없음/빈 배열)은 합이 0 이 된다.
                //   그대로 0을 올리면 백엔드 sleep_score 가 미측정 처리 → 수면 20점을 그냥 잃는 회귀.
                //   → 단계 합이 0이면 세션 구간으로 폴백한다.
                val stageSumMin = deepMin + lightMin + remMin + awakeMin + unstagedMin
                if (unstagedMin > 0) {
                    Log.w(TAG, "[sleep] 단계 미분류(SLEEPING) ${unstagedMin}분 포함 → 총 ${stageSumMin}분 (4단계 합 ${stageSumMin - unstagedMin}분, $targetDate)")
                }
                val totalMin = if (stageSumMin > 0) {
                    stageSumMin
                } else {
                    Log.w(TAG, "[sleep] 단계 데이터 없음 → 총 수면시간을 세션 구간으로 폴백: ${sessionSpanMin}분 (세션 ${nightSessions.size}개, $targetDate)")
                    sessionSpanMin
                }
                // 0 = 미측정 계약 통일 (단계별과 동일하게 0은 null 로 올린다 — 삼성 SDK 경로와 일치)
                sleepTotal = totalMin.takeIf { it > 0 }
                sleepDeep = deepMin.takeIf { it > 0 }
                sleepLight = lightMin.takeIf { it > 0 }
                sleepRem = remMin.takeIf { it > 0 }
                sleepAwake = awakeMin.takeIf { it > 0 }
                sleepStart = nightSessions.minBy { it.startTime }.startTime.toString()
                sleepEnd = nightSessions.maxBy { it.endTime }.endTime.toString()
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "[sleep] 권한 없음: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "[sleep] 읽기 실패: ${e.javaClass.simpleName} - ${e.message}")
        }

        // 심박수 — [v1.0] 평균/최저/최고는 집계 API로 계산 (개별 레코드 역직렬화 실패·페이지 한계 경로 회피).
        //   백엔드로 보낼 샘플(차트용)은 개별 레코드가 필요해 readRecords로 따로 — 실패해도 위 통계엔 영향 없게 분리.
        var hrAvg: Int? = null
        var hrMin: Int? = null
        var hrMax: Int? = null
        val hrSamples = mutableListOf<HeartRateSample>()
        try {
            val agg = hc.aggregate(
                AggregateRequest(
                    metrics = setOf(HeartRateRecord.BPM_AVG, HeartRateRecord.BPM_MIN, HeartRateRecord.BPM_MAX),
                    timeRangeFilter = TimeRangeFilter.between(dayStart, rangeEnd)
                )
            )
            hrAvg = agg[HeartRateRecord.BPM_AVG]?.toInt()
            hrMin = agg[HeartRateRecord.BPM_MIN]?.toInt()
            hrMax = agg[HeartRateRecord.BPM_MAX]?.toInt()
        } catch (e: SecurityException) {
            Log.w(TAG, "[heartrate] 권한 없음: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "[heartrate] 집계 실패: ${e.javaClass.simpleName} - ${e.message}")
        }
        // 샘플(백엔드 차트 전송용) — 개별 레코드 필요. 손상 레코드로 실패해도 위 평균/최저/최고는 유지.
        if (hrAvg != null) try {
            val records = hc.readRecords(
                ReadRecordsRequest(HeartRateRecord::class, TimeRangeFilter.between(dayStart, rangeEnd))
            ).records
            val allSamples = records.flatMap { it.samples }
            if (allSamples.isNotEmpty()) {
                val step = (allSamples.size / 200).coerceAtLeast(1)
                allSamples.sortedBy { it.time }.forEachIndexed { i, s ->
                    if (i % step == 0) {
                        hrSamples.add(HeartRateSample(s.time.toString(), s.beatsPerMinute.toInt()))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "[heartrate] 샘플 읽기 실패(통계는 집계로 확보됨): ${e.javaClass.simpleName} - ${e.message}")
        }

        // SpO2 (수면 무호흡 추정용 — v1.9는 수집만)
        var spo2Avg: Double? = null
        var spo2Min: Double? = null
        try {
            val records = hc.readRecords(
                ReadRecordsRequest(OxygenSaturationRecord::class, TimeRangeFilter.between(dayStart, rangeEnd))
            ).records
            if (records.isNotEmpty()) {
                val values = records.map { it.percentage.value }
                spo2Avg = values.average()
                spo2Min = values.min()
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "[spo2] 권한 없음: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "[spo2] 읽기 실패: ${e.javaClass.simpleName} - ${e.message}")
        }

        return WatchDataRequest(
            logDate = targetDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
            steps = stepsTotal,
            stepsHourly = stepsHourly,
            sleepTotalMinutes = sleepTotal,
            sleepDeepMinutes = sleepDeep,
            sleepLightMinutes = sleepLight,
            sleepRemMinutes = sleepRem,
            sleepAwakeMinutes = sleepAwake,
            sleepStartAt = sleepStart,
            sleepEndAt = sleepEnd,
            avgHeartRate = hrAvg,
            minHeartRate = hrMin,
            maxHeartRate = hrMax,
            heartRateSamples = hrSamples.ifEmpty { null },
            spo2Avg = spo2Avg,
            spo2Min = spo2Min
        )
    }

    /**
     * 기간별 일별 걸음수 (활동 상세 화면 — 삼성헬스 스타일).
     * @param daysBack 오늘 포함 며칠 (예: 7 = 오늘 + 어제 ~ 6일 전)
     * @return 날짜 → 걸음수 Map (데이터 없는 날은 빠짐)
     *
     * ⭐ Codex P1: 두 가지 개선
     *  1) 날짜별로 best source 선택 (기간 전체 동일 source가 아닌 — 워치 교체/소스 변경 대응)
     *  2) 자정 넘어가는 레코드는 시간 비례 분배 (예: 23:30~00:30 → 어제 50%, 오늘 50%)
     */
    suspend fun fetchStepsByDay(daysBack: Int): Map<LocalDate, Int> {
        // 코덱스 B-부분: 위임 → fetchStepsByDay(from, to) 로 일관 처리
        // [260726] '오늘' 기준은 KST (다른 경로와 날짜 산출 통일). 위임 대상이 IO에서 실행됨.
        val today = LocalDate.now(KST)
        return fetchStepsByDay(today.minusDays((daysBack - 1).toLong()), today)
    }

    /**
     * 임의 범위 from~to (inclusive) 의 일별 걸음수.
     * 좌우 화살표로 과거 주/월간 이동 시 사용 (미래 endDate hoist 대비).
     */
    // [260726] 차트용 다일치 집계 — 무거우므로 IO 디스패처에서 실행 (홈/상세 진입 프레임 드랍 방지).
    suspend fun fetchStepsByDay(from: LocalDate, to: LocalDate): Map<LocalDate, Int> = withContext(Dispatchers.IO) {
        // [SDK 자동화 — 읽기 폴백/병합] SDK 우선. Samsung이 요청 범위의 모든 날짜를 채우면 그대로 반환(HC 읽기 없음).
        //   일부 날짜만 있으면(또는 throw/비었으면) HC를 읽어 빠진 날짜를 키별로 병합(Samsung 값 유지). 차트 공백 방지.
        if (useSamsung()) samsungSource?.let { src ->
            val sdk = try { src.fetchStepsByDay(from, to) } catch (_: Exception) { null }
            if (!sdk.isNullOrEmpty() && coversAllDays(sdk.keys, from, to)) return@withContext sdk
            if (!canReadHealthConnect()) return@withContext sdk ?: emptyMap()
            val hc = try { fetchStepsByDayFromHc(from, to) } catch (_: Exception) { null }
            return@withContext mergeByKey(sdk, hc)
        }
        fetchStepsByDayFromHc(from, to)
    }

    private suspend fun fetchStepsByDayFromHc(from: LocalDate, to: LocalDate): Map<LocalDate, Int> {
        val startDate = if (from <= to) from else to
        val endDate = if (from <= to) to else from

        return try {
            // [v1.0 버그픽스 — 7일/31일 차트 최근 날짜 0보·31일 전체 0]
            //   기존엔 readRecords로 개별 레코드를 읽어 날짜별 합산했는데 두 가지 문제가 있었다:
            //   ① 기본 페이지 1000건 한계 — android·Google Fit이 1분마다 걸음 레코드를 만들어 며칠치면 1000건 초과 →
            //      첫 페이지(과거 날짜)만 오고 최근 날짜(5/30·5/31)가 통째로 잘려 0보로 표시.
            //   ② 과거 구간에 손상된 레코드(start≥end)가 있으면 역직렬화 중 "startTime must be before endTime"
            //      예외 → 31일 차트 전체가 0.
            //   해결: HC 집계 API(aggregateGroupByPeriod)로 날짜별 합계를 직접 받는다. 개별 레코드를 안 읽으니
            //   페이지 한계·손상 레코드 영향이 없고, 소스 중복(삼성헬스/Google Fit/안드로이드)도 HC가 우선순위로 자동 정리.
            //   (period 슬라이서는 LocalDateTime 기반 필터 필요 — atStartOfDay()로 로컬 자정 경계 사용.)
            // [260726] KST 통일 예외 지점: aggregateGroupByPeriod(Period 슬라이서)는 HC API 제약상
            //   LocalDateTime 기반 필터만 허용 → 버킷 경계는 기기 로컬 자정으로 남는다(Instant/KST 지정 불가).
            //   국내(KST) 기기에선 동일하고, 이 값은 차트 표시용이라 log_date 기록에는 영향 없음.
            val buckets = client().aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(
                        startDate.atStartOfDay(),
                        endDate.plusDays(1).atStartOfDay()
                    ),
                    timeRangeSlicer = java.time.Period.ofDays(1)
                )
            )
            val filtered = mutableMapOf<LocalDate, Int>()
            buckets.forEach { bucket ->
                val date = bucket.startTime.toLocalDate()
                val count = (bucket.result[StepsRecord.COUNT_TOTAL] ?: 0L).toInt()
                if (count > 0 && !date.isBefore(startDate) && !date.isAfter(endDate)) {
                    filtered[date] = count
                }
            }

            // 오늘은 HC 직접 집계로 덮어쓰기 (period 슬라이서와 미세 차이 가능).
            //   ⚠️ fetchWatchData(today)는 Samsung→HC 오케스트레이터를 재진입(useSamsung/isSdkDirectActive)하므로
            //   이 "HC 전용" 헬퍼에서 호출하면 Samsung 데이터가 섞일 수 있음 → 병합 의미가 깨짐.
            //   대신 위 ①과 동일한 HC aggregate(StepsRecord.COUNT_TOTAL)를 오늘 날짜 범위에 직접 적용(HC만 터치).
            // [260726] '오늘' 판정은 KST (호출자 범위 산출과 동일 기준). 단, 아래 aggregate 경계는 위 버킷과
            //   같은 로컬 자정(LocalDateTime)을 그대로 써서 버킷 값과 기준이 어긋나지 않게 유지한다.
            val today = LocalDate.now(KST)
            if (!today.isBefore(startDate) && !today.isAfter(endDate)) {
                try {
                    val todaySteps = client().aggregate(
                        AggregateRequest(
                            metrics = setOf(StepsRecord.COUNT_TOTAL),
                            timeRangeFilter = TimeRangeFilter.between(
                                today.atStartOfDay(),
                                today.plusDays(1).atStartOfDay()
                            )
                        )
                    )[StepsRecord.COUNT_TOTAL]?.toInt() ?: 0
                    if (todaySteps > 0) filtered[today] = todaySteps
                } catch (_: Exception) { /* keep aggregated value */ }
            }
            filtered
        } catch (e: Exception) {
            Log.w(TAG, "[stepsByDay] FAILED $startDate~$endDate: ${e.javaClass.simpleName} - ${e.message}")
            emptyMap()
        }
    }

    /**
     * 일별 수면 데이터 (수면 상세 화면 — 삼성헬스 스타일).
     * 각 날짜 = "그날 새벽까지의 수면" 으로 매핑 (예: 5/17 23:00~5/18 07:00 → 5/18에 매핑)
     */
    data class SleepDay(
        val date: LocalDate,
        val totalMinutes: Int,
        val deepMinutes: Int,
        val lightMinutes: Int,
        val remMinutes: Int,
        val awakeMinutes: Int,
        val sleepStart: Instant?,
        val sleepEnd: Instant?,
        // [260729] 단계 미분류 수면(Health Connect STAGE_TYPE_SLEEPING=2) 분량.
        //   totalMinutes 에는 포함되지만 deep/light/rem/awake 어디에도 안 들어간다
        //   (어느 단계인지 기기가 안 알려줌). 삼성 SDK 경로는 해당 상수가 없어 항상 0.
        //   ⚠️ 이 값이 크면 4단계 스택 막대 합 < totalMinutes 가 된다(막대 윗부분이 빈다).
        val unstagedMinutes: Int = 0
    )

    suspend fun fetchSleepByDay(daysBack: Int): Map<LocalDate, SleepDay> {
        // 코덱스 B-부분: 위임 → fetchSleepByDay(from, to) 로 일관 처리
        // [260726] '오늘' 기준 KST 통일.
        val today = LocalDate.now(KST)
        return fetchSleepByDay(today.minusDays((daysBack - 1).toLong()), today)
    }

    /**
     * 임의 범위 from~to (inclusive) 의 일별 수면.
     * 좌우 화살표로 과거 주/월간 이동 시 사용 (미래 endDate hoist 대비).
     */
    // [260726] 수면 세션 읽기·그룹핑이 무거움 → IO 디스패처에서 실행.
    suspend fun fetchSleepByDay(from: LocalDate, to: LocalDate): Map<LocalDate, SleepDay> = withContext(Dispatchers.IO) {
        // [SDK 자동화 — 읽기 폴백/병합] SDK 우선. 범위 전 날짜 채우면 그대로, 아니면 HC로 빠진 날짜 병합(Samsung 우선).
        if (useSamsung()) samsungSource?.let { src ->
            val sdk = try { src.fetchSleepByDay(from, to) } catch (_: Exception) { null }
            if (!sdk.isNullOrEmpty() && coversAllDays(sdk.keys, from, to)) return@withContext sdk
            if (!canReadHealthConnect()) return@withContext sdk ?: emptyMap()
            val hc = try { fetchSleepByDayFromHc(from, to) } catch (_: Exception) { null }
            return@withContext mergeByKey(sdk, hc)
        }
        fetchSleepByDayFromHc(from, to)
    }

    private suspend fun fetchSleepByDayFromHc(from: LocalDate, to: LocalDate): Map<LocalDate, SleepDay> {
        // [260726] 조회·매핑 경계 KST 통일 (전제: 생활 시간대 = KST).
        val zone = KST
        val startDate = if (from <= to) from else to
        val endDate = if (from <= to) to else from
        // 범위: 시작일 0시 - 6시간 ~ 종료일 24시 + 14시 (수면 종료 기준일에 매핑)
        val rangeStart = startDate.atStartOfDay(zone).minusHours(6).toInstant()
        val rangeEnd = endDate.plusDays(1).atStartOfDay(zone).plusHours(14).toInstant()
        if (!rangeEnd.isAfter(rangeStart)) return emptyMap()

        return try {
            val sessions = client().readRecords(
                ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(rangeStart, rangeEnd))
            ).records

            // [사용자 피드백] 잠 시작 시각이 정오 이전 (새벽~오전) = 전날 밤 잠으로 매핑
            //   예: 5/21 03:00 시작한 잠 → 5/20의 밤 수면
            //   SleepDayTimelineCard 매핑 룰과 동일하게 통일.
            val sortedByEnd = sessions.sortedBy { it.endTime }
            val dayToSessions = mutableMapOf<LocalDate, MutableList<SleepSessionRecord>>()

            sortedByEnd.forEach { s ->
                val startLdt = s.startTime.atZone(zone).toLocalDateTime()
                // 사용자 피드백 (5/26): 낮 시간대 짧은 수면(낮잠) 은 일별 집계에서 제외
                //   시작이 9~18시 + 길이 3시간 미만 → skip
                val durMin = (s.endTime.epochSecond - s.startTime.epochSecond) / 60
                if (startLdt.hour in 9..17 && durMin < 180) {
                    return@forEach
                }
                val mappedDate = if (startLdt.hour < 12) {
                    startLdt.toLocalDate().minusDays(1)
                } else {
                    startLdt.toLocalDate()
                }
                dayToSessions.getOrPut(mappedDate) { mutableListOf() }.add(s)
            }

            // [버그픽스 260616] 조회 범위를 6h 넘겨 잡으므로 요청 밖 날짜 키 제거 (요약카드 평균 오염 방지).
            dayToSessions.filterKeys { !it.isBefore(startDate) && !it.isAfter(endDate) }.mapValues { (date, dayList) ->
                // 같은 밤 잠 = 종료 가장 늦은 세션부터 4시간 이내 모음
                val sortedDesc = dayList.sortedByDescending { it.endTime }
                val grouped = mutableListOf(sortedDesc[0])
                for (i in 1 until sortedDesc.size) {
                    val prevStart = grouped.last().startTime
                    val cur = sortedDesc[i]
                    val gap = (prevStart.epochSecond - cur.endTime.epochSecond) / 3600.0
                    if (gap in 0.0..4.0) grouped.add(cur) else break
                }

                var sessionSpanMin = 0; var deep = 0; var light = 0; var rem = 0; var awake = 0
                // [260729] 단계 미분류(SLEEPING) 전용 — 총합에만 반영, 4단계 분포에는 안 넣는다.
                var unstaged = 0
                grouped.forEach { s ->
                    sessionSpanMin += ((s.endTime.epochSecond - s.startTime.epochSecond) / 60).toInt()
                    s.stages.forEach { st ->
                        val m = ((st.endTime.epochSecond - st.startTime.epochSecond) / 60).toInt()
                        // ⚠️ 공식 상수 — 4=LIGHT, 5=DEEP, 6=REM, 2=SLEEPING(단계 미분류) (Codex 검토 수정)
                        when (st.stage) {
                            SleepSessionRecord.STAGE_TYPE_DEEP -> deep += m
                            SleepSessionRecord.STAGE_TYPE_LIGHT -> light += m
                            SleepSessionRecord.STAGE_TYPE_REM -> rem += m
                            SleepSessionRecord.STAGE_TYPE_AWAKE,
                            SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> awake += m
                            // ⭐ [260729 버그픽스] 세부 단계 없이 "잠"으로만 기록하는 기기 대응 (fetchWatchDataFromHc와 동일).
                            SleepSessionRecord.STAGE_TYPE_SLEEPING -> unstaged += m
                            // OUT_OF_BED(3) / UNKNOWN(0) 은 의도적 제외.
                        }
                    }
                }
                // [260728 확정] 총 수면시간 = 4단계 합 + 단계 미분류(SLEEPING). 합이 0이면 세션 구간 폴백
                //   (fetchWatchDataFromHc와 동일). 차트/상세 화면과 업로드 값이 어긋나지 않게 같은 식을 쓴다.
                val stageSumMin = deep + light + rem + awake + unstaged
                val totalMin = if (stageSumMin > 0) {
                    stageSumMin
                } else {
                    Log.w(TAG, "[sleepByDay] 단계 데이터 없음 → 총 수면시간을 세션 구간으로 폴백: ${sessionSpanMin}분 (세션 ${grouped.size}개, $date)")
                    sessionSpanMin
                }
                SleepDay(
                    date = date,
                    totalMinutes = totalMin,
                    deepMinutes = deep,
                    lightMinutes = light,
                    remMinutes = rem,
                    awakeMinutes = awake,
                    sleepStart = grouped.minByOrNull { it.startTime }?.startTime,
                    sleepEnd = grouped.maxByOrNull { it.endTime }?.endTime,
                    unstagedMinutes = unstaged
                )
            }.filterValues { it.totalMinutes > 0 }
                // 코덱스 B-부분 보강: 세션 end date 매핑이 요청 범위 밖일 수 있어 안전 필터
                .filterKeys { date -> !date.isBefore(startDate) && !date.isAfter(endDate) }
        } catch (e: Exception) { emptyMap() }
    }

    /**
     * 일별 평균 심박 (스트레스 추정용 — 우리는 자체 스트레스 계산).
     * 심박이 높을수록 스트레스 ↑ 가정.
     */
    data class HeartRateDay(
        val date: LocalDate,
        val avgBpm: Int,
        val maxBpm: Int,
        val minBpm: Int
    )

    suspend fun fetchHeartRateByDay(daysBack: Int): Map<LocalDate, HeartRateDay> {
        // 코덱스 일관성: 위임 → fetchHeartRateByDay(from, to)
        // [260726] '오늘' 기준 KST 통일.
        val today = LocalDate.now(KST)
        return fetchHeartRateByDay(today.minusDays((daysBack - 1).toLong()), today)
    }

    /**
     * 임의 범위 from~to (inclusive) 의 일별 평균 심박.
     * 좌우 화살표로 과거 주/월간 이동 시 사용.
     */
    // [260726] 다일치 심박 집계 — IO 디스패처에서 실행.
    suspend fun fetchHeartRateByDay(from: LocalDate, to: LocalDate): Map<LocalDate, HeartRateDay> = withContext(Dispatchers.IO) {
        // [SDK 자동화 — 읽기 폴백/병합] SDK 우선. 범위 전 날짜 채우면 그대로, 아니면 HC로 빠진 날짜 병합(Samsung 우선).
        if (useSamsung()) samsungSource?.let { src ->
            val sdk = try { src.fetchHeartRateByDay(from, to) } catch (_: Exception) { null }
            if (!sdk.isNullOrEmpty() && coversAllDays(sdk.keys, from, to)) return@withContext sdk
            if (!canReadHealthConnect()) return@withContext sdk ?: emptyMap()
            val hc = try { fetchHeartRateByDayFromHc(from, to) } catch (_: Exception) { null }
            return@withContext mergeByKey(sdk, hc)
        }
        fetchHeartRateByDayFromHc(from, to)
    }

    private suspend fun fetchHeartRateByDayFromHc(from: LocalDate, to: LocalDate): Map<LocalDate, HeartRateDay> {
        val startDate = if (from <= to) from else to
        val endDate = if (from <= to) to else from

        return try {
            // [v1.0] 걸음 7일/31일과 동일하게 집계 API 사용 — 개별 심박 레코드를 안 읽으므로
            //   ① 페이지 1000건 한계로 최근 날짜 잘림 없음, ② 손상 레코드 영향 없음, ③ 폰 시각 비정상 영향 없음(LocalDateTime 기반).
            //   BPM_AVG = 그날 모든 심박 샘플 평균 = 기존 readRecords 평균과 동일 값 (일일 화면과 일관성 유지).
            // [260726] KST 통일 예외 지점: Period 슬라이서는 HC API 제약상 LocalDateTime 필터만 허용 →
            //   버킷 경계는 기기 로컬 자정 유지(차트 표시용, log_date 기록과 무관).
            val buckets = client().aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(
                    metrics = setOf(HeartRateRecord.BPM_AVG, HeartRateRecord.BPM_MAX, HeartRateRecord.BPM_MIN),
                    timeRangeFilter = TimeRangeFilter.between(
                        startDate.atStartOfDay(),
                        endDate.plusDays(1).atStartOfDay()
                    ),
                    timeRangeSlicer = java.time.Period.ofDays(1)
                )
            )
            val result = mutableMapOf<LocalDate, HeartRateDay>()
            buckets.forEach { bucket ->
                val date = bucket.startTime.toLocalDate()
                val avg = bucket.result[HeartRateRecord.BPM_AVG]
                if (avg != null && avg > 0 && !date.isBefore(startDate) && !date.isAfter(endDate)) {
                    val mx = bucket.result[HeartRateRecord.BPM_MAX] ?: avg
                    val mn = bucket.result[HeartRateRecord.BPM_MIN] ?: avg
                    result[date] = HeartRateDay(
                        date = date,
                        avgBpm = avg.toInt(),
                        maxBpm = mx.toInt(),
                        minBpm = mn.toInt()
                    )
                }
            }
            result
        } catch (e: Exception) {
            Log.w(TAG, "[hrByDay] FAILED $startDate~$endDate: ${e.javaClass.simpleName} - ${e.message}")
            emptyMap()
        }
    }

    /**
     * 시간대별 평균 심박 (24개 — 0시~23시).
     * 일일 스트레스 화면 — 시간대별 변화를 라인 그래프로.
     * @param targetDate 보통 오늘
     */
    // [260726] 기준일 KST + 시간대별 집계는 IO 디스패처에서.
    suspend fun fetchHeartRateHourly(
        targetDate: LocalDate = LocalDate.now(KST),
    ): List<Int?> = withContext(Dispatchers.IO) {
        // [SDK 자동화 — 읽기 폴백/병합] SDK 우선. 24시간 전부 채우면 그대로, 빈 시간 있으면 HC로 시간별 병합(Samsung 우선).
        if (useSamsung()) samsungSource?.let { src ->
            val sdk = try { src.fetchHeartRateHourly(targetDate) } catch (_: Exception) { null }
            // 모든 시간이 채워졌으면 HC 읽기 불필요.
            if (sdk != null && sdk.all { it != null }) return@withContext sdk
            if (!canReadHealthConnect()) return@withContext sdk ?: List(24) { null }
            if (sdk == null || sdk.none { it != null }) {
                // SDK 비었음/throw → 전부 HC.
                return@withContext try { fetchHeartRateHourlyFromHc(targetDate) } catch (_: Exception) { List(24) { null } }
            }
            val hc = try { fetchHeartRateHourlyFromHc(targetDate) } catch (_: Exception) { null }
                ?: return@withContext sdk
            // 시간별 병합: Samsung 값 우선, null인 시간만 HC로 채움.
            return@withContext List(24) { h -> sdk.getOrNull(h) ?: hc.getOrNull(h) }
        }
        fetchHeartRateHourlyFromHc(targetDate)
    }

    private suspend fun fetchHeartRateHourlyFromHc(targetDate: LocalDate): List<Int?> {
        // [260726] 하루 경계·시간대 버킷 매핑 KST 통일.
        val zone = KST
        val dayStart = targetDate.atStartOfDay(zone).toInstant()
        val dayEnd = targetDate.plusDays(1).atStartOfDay(zone).toInstant()
        val now = Instant.now()
        // [v1.0 회귀 fix — 대표님 폰] 폰 시각 비정상 시 end <= dayStart → 시간대별 심박 빈 결과.
        val endRaw = if (dayEnd.isAfter(now)) now else dayEnd
        val end = if (endRaw.isAfter(dayStart)) endRaw else dayEnd

        return try {
            // [v1.0] 1시간 단위 집계 — 개별 레코드 역직렬화 실패·페이지 한계(심박은 하루 2000건+ 가능) 회피.
            //   Duration 슬라이서는 Instant 기반 필터 사용. 각 버킷의 BPM_AVG = 그 시간대 평균.
            val buckets = client().aggregateGroupByDuration(
                AggregateGroupByDurationRequest(
                    metrics = setOf(HeartRateRecord.BPM_AVG),
                    timeRangeFilter = TimeRangeFilter.between(dayStart, end),
                    timeRangeSlicer = Duration.ofHours(1)
                )
            )
            val out = arrayOfNulls<Int>(24)
            buckets.forEach { b ->
                val h = b.startTime.atZone(zone).hour.coerceIn(0, 23)
                b.result[HeartRateRecord.BPM_AVG]?.let { out[h] = it.toInt() }
            }
            out.toList()
        } catch (e: Exception) {
            Log.w(TAG, "[hrHourly] 집계 실패: ${e.javaClass.simpleName} - ${e.message}")
            List(24) { null }
        }
    }

    /**
     * 어젯밤 수면 시간선 — 시간순으로 단계 변화 (세그먼트 리스트).
     * 수면 상세 화면 — 가로 시간선 차트.
     */
    data class SleepSegment(val startMin: Int, val endMin: Int, val stage: Int)
    data class SleepTimeline(
        val sleepStartMin: Int,  // 0~24시간*60 (자정 기준 분, 음수 = 전날)
        val sleepEndMin: Int,
        val segments: List<SleepSegment>
    )

    // [260726] 기준일 KST + 수면 세션 읽기/단계 정렬은 IO 디스패처에서.
    suspend fun fetchSleepTimeline(
        targetDate: LocalDate = LocalDate.now(KST),
    ): SleepTimeline? = withContext(Dispatchers.IO) {
        // [SDK 자동화 — 읽기 폴백] SDK 우선. 타임라인은 한 밤의 원자적 결과라 키별 병합이 부적합
        //   (두 소스의 단계 경계가 달라 겹침 발생) → Samsung 있으면 그대로, 없으면(또는 throw) HC로 폴백.
        if (useSamsung()) samsungSource?.let { src ->
            val sdk = try { src.fetchSleepTimeline(targetDate) } catch (_: Exception) { null }
            if (sdk != null) return@withContext sdk
            if (!canReadHealthConnect()) return@withContext null
            return@withContext try { fetchSleepTimelineFromHc(targetDate) } catch (_: Exception) { null }
        }
        fetchSleepTimelineFromHc(targetDate)
    }

    private suspend fun fetchSleepTimelineFromHc(targetDate: LocalDate): SleepTimeline? {
        // [260726] 밤 윈도우·자정 기준 분 환산 경계 KST 통일 (fetchWatchDataFromHc와 동일 기준).
        val zone = KST
        // 🛌 사용자 피드백 — fetchWatchData와 동일 매핑 (startTime이 [정오, 다음날 정오) 안)
        val nightWindowStart = targetDate.atTime(12, 0).atZone(zone).toInstant()
        val nightWindowEnd = targetDate.plusDays(1).atTime(12, 0).atZone(zone).toInstant()
        val rangeStart = nightWindowStart
        val rangeEnd = nightWindowEnd.plus(java.time.Duration.ofHours(6))
        return try {
            val allSessions = client().readRecords(
                ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(rangeStart, rangeEnd))
            ).records
            val sessions = allSessions
                .filter { !it.startTime.isBefore(nightWindowStart) && it.startTime.isBefore(nightWindowEnd) }
                .filter { s ->
                    // 사용자 피드백 (5/26): 낮 시간대 짧은 수면(낮잠) 은 타임라인에서 제외
                    //   시작 9~18시 + 길이 3시간 미만 → skip
                    val startHour = s.startTime.atZone(zone).toLocalDateTime().hour
                    val durMin = (s.endTime.epochSecond - s.startTime.epochSecond) / 60
                    !(startHour in 9..17 && durMin < 180)
                }
                .sortedByDescending { it.endTime }
            if (sessions.isEmpty()) return null

            // 가장 늦게 끝난 세션 기준 4시간 이내 인접 세션도 모음 (멀티세션 잠 합산)
            val night = mutableListOf(sessions[0])
            for (i in 1 until sessions.size) {
                val prevStart = night.last().startTime
                val cur = sessions[i]
                val gap = (prevStart.epochSecond - cur.endTime.epochSecond) / 3600.0
                if (gap in 0.0..4.0) night.add(cur) else break
            }

            val earliestStart = night.minOf { it.startTime }
            val latestEnd = night.maxOf { it.endTime }
            // targetDate 자정 기준으로 변환 (음수 = 전날)
            val refMidnight = targetDate.atStartOfDay(zone).toInstant()
            val startMin = ((earliestStart.epochSecond - refMidnight.epochSecond) / 60).toInt()
            val endMin = ((latestEnd.epochSecond - refMidnight.epochSecond) / 60).toInt()

            // 모든 단계를 시간순으로
            val segments = night.flatMap { s ->
                s.stages.map { st ->
                    val sm = ((st.startTime.epochSecond - refMidnight.epochSecond) / 60).toInt()
                    val em = ((st.endTime.epochSecond - refMidnight.epochSecond) / 60).toInt()
                    SleepSegment(sm, em, st.stage)
                }
            }.sortedBy { it.startMin }
            SleepTimeline(startMin, endMin, segments)
        } catch (e: Exception) { null }
    }

    /** 화면 디버그용 — 현재 데이터 사람이 보기 좋게 출력 */
    suspend fun fetchSummaryText(): String {
        val data = fetchWatchData()
        val sb = StringBuilder()
        sb.appendLine("📅 ${data.logDate}")
        sb.appendLine("🚶 걸음수: ${data.steps}보")
        sb.appendLine("😴 수면: ${data.sleepTotalMinutes ?: 0}분 (깊은잠 ${data.sleepDeepMinutes ?: 0}분, REM ${data.sleepRemMinutes ?: 0}분)")
        sb.appendLine("❤️ 심박: 평균 ${data.avgHeartRate ?: "?"} BPM (샘플 ${data.heartRateSamples?.size ?: 0}개)")
        sb.appendLine("🫁 SpO2: 평균 ${"%.1f".format(data.spo2Avg ?: 0.0)}%")
        return sb.toString()
    }

    // ==============================================
    // 종합 진단 fetch — v1.9 사용 데이터 5종 (디버그 화면용)
    // ==============================================
    data class RawDataDiagnostic(
        val type: String,                  // 데이터 타입명
        val permissionGranted: Boolean,    // 권한 허용 여부
        val recordCount: Int,              // 가져온 레코드 수
        val sources: List<String>,         // 데이터 소스 패키지명 리스트
        val sample: String,                // 첫 레코드 샘플
        val timeRange: String,             // 조회 시간 범위 (사용자 확인용)
        val error: String? = null,         // 오류 메시지
    )

    /**
     * v1.9에서 실제 사용하는 5종 데이터 raw 진단.
     * 각 카드에 정확한 시간 범위 표시 — 사용자가 어떤 일자 데이터인지 확실히 확인 가능.
     */
    // [260726] 진단 화면용 대량 raw 읽기·문자열 조립 → IO 디스패처에서. 기준일·경계도 KST 통일.
    suspend fun fetchAllRawData(
        targetDate: LocalDate = LocalDate.now(KST).minusDays(1),
    ): List<RawDataDiagnostic> = withContext(Dispatchers.IO) {
        val zone = KST
        val dayStart = targetDate.atStartOfDay(zone).toInstant()
        val dayEnd = targetDate.plusDays(1).atStartOfDay(zone).toInstant()
        val now = Instant.now()
        // [v1.0 회귀 fix — 대표님 폰] 폰 시각 비정상(과거) 시 rangeEnd <= dayStart → "startTime must be
        //   before endTime" 에러 → 걸음 진단 실패. dayEnd로 fallback해서 정상 범위 보장.
        val rangeEndRaw = if (dayEnd.isAfter(now)) now else dayEnd
        val rangeEnd = if (rangeEndRaw.isAfter(dayStart)) rangeEndRaw else dayEnd
        val filter = TimeRangeFilter.between(dayStart, rangeEnd)
        val dayRangeLabel = "${targetDate} 00:00 ~ ${targetDate.plusDays(1)} 00:00"

        val results = mutableListOf<RawDataDiagnostic>()

        val granted = try {
            client().permissionController.getGrantedPermissions()
        } catch (e: Exception) {
            emptySet()
        }

        // 1. 걸음 — 모든 source × 모든 시간 슬라이스 종합 진단
        results.add(diagnose("🚶 StepsRecord (걸음) — 종합 진단", granted, dayRangeLabel) {
            // [v1.0 — 대표님 폰] 개별 레코드 읽기는 손상 레코드(시작≥종료) 만나면 실패.
            //   그 경우 집계 총합으로 "차트·점수는 정상"임을 안내 (오류만 떠서 데이터 없다고 오해하는 것 방지).
            val recs = try {
                client().readRecords(ReadRecordsRequest(StepsRecord::class, filter)).records
            } catch (e: Exception) {
                val aggTotal = try {
                    client().aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), filter))[StepsRecord.COUNT_TOTAL]?.toInt()
                } catch (_: Exception) { null }
                return@diagnose Triple(
                    if (aggTotal != null && aggTotal > 0) 1 else 0,
                    emptyList<String>(),
                    buildString {
                        appendLine("⚠️ 개별 레코드 읽기 실패")
                        appendLine("   ${e.message?.take(80)}")
                        appendLine("   (Health Connect에 손상된 걸음 레코드 — 시작시각 ≥ 종료시각 — 가 있어요)")
                        appendLine()
                        if (aggTotal != null && aggTotal > 0) {
                            appendLine("✅ 집계로는 정상: 총 ${"%,d".format(aggTotal)}보")
                            appendLine("   → 걸음 차트·점수는 집계 방식이라 정상 표시됩니다.")
                        } else {
                            appendLine("ℹ️ 집계로도 걸음 데이터 없음")
                        }
                    }.trim()
                )
            }
            val bySource = recs.groupBy { it.metadata.dataOrigin.packageName }
            val totalBySource = bySource.mapValues { (_, rs) -> rs.sumOf { it.count.toInt() } }
            val maxSource = totalBySource.maxByOrNull { it.value }

            Triple(
                recs.size,
                recs.map { it.metadata.dataOrigin.packageName }.distinct(),
                buildString {
                    appendLine("📌 source별 합계:")
                    totalBySource.entries.sortedByDescending { it.value }.forEach { (src, total) ->
                        val marker = if (src == maxSource?.key) "★" else " "
                        val shortSrc = src.takeLast(28)
                        appendLine("  $marker $shortSrc: ${"%,d".format(total)}보 (${bySource[src]?.size}개 레코드)")
                    }
                    appendLine()

                    // ⭐ 각 source의 raw 레코드 상세 (시간대별 데이터 어디서 들어오는지 확인)
                    bySource.entries.sortedByDescending { totalBySource[it.key] ?: 0 }.forEach { (src, records) ->
                        val shortSrc = src.takeLast(35)
                        appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━")
                        appendLine("📦 [$shortSrc]")
                        appendLine("   레코드 ${records.size}개, 총 ${totalBySource[src]}보")
                        appendLine()

                        val sorted = records.sortedBy { it.startTime }
                        val daily = sorted.count { (it.endTime.epochSecond - it.startTime.epochSecond) >= 23 * 3600 }
                        val short = sorted.count { (it.endTime.epochSecond - it.startTime.epochSecond) < 3600 }
                        appendLine("   분류: 🚨일일통합 ${daily}개 / ✓단기 ${short}개 / 기타 ${records.size - daily - short}개")

                        sorted.take(10).forEach { r ->
                            val sZ = r.startTime.atZone(zone)
                            val eZ = r.endTime.atZone(zone)
                            val startStr = String.format("%02d:%02d:%02d", sZ.hour, sZ.minute, sZ.second)
                            val endStr = String.format("%02d:%02d:%02d", eZ.hour, eZ.minute, eZ.second)
                            val durMin = (r.endTime.epochSecond - r.startTime.epochSecond) / 60
                            val tag = when {
                                durMin >= 23 * 60 -> "🚨"
                                durMin >= 60 -> "⏰"
                                else -> "✓"
                            }
                            appendLine("   $tag $startStr~$endStr (${durMin}분, ${r.count}보)")
                        }
                        if (records.size > 10) appendLine("   ... +${records.size - 10}개")
                        appendLine()
                    }

                    appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━")
                    appendLine("⏱ aggregateGroupByDuration 시도 (다양한 시간 단위):")
                    appendLine()

                    // 다양한 시간 슬라이스로 시도
                    listOf(
                        "1시간" to Duration.ofHours(1),
                        "30분" to Duration.ofMinutes(30),
                        "15분" to Duration.ofMinutes(15),
                        "5분" to Duration.ofMinutes(5),
                    ).forEach { (label, slicer) ->
                        try {
                            val aggResp = client().aggregateGroupByDuration(
                                androidx.health.connect.client.request.AggregateGroupByDurationRequest(
                                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                                    timeRangeFilter = TimeRangeFilter.between(dayStart, rangeEnd),
                                    timeRangeSlicer = slicer,
                                )
                            )
                            val nonZeroBuckets = aggResp.count { (it.result[StepsRecord.COUNT_TOTAL] ?: 0L) > 0 }
                            val total = aggResp.sumOf { (it.result[StepsRecord.COUNT_TOTAL] ?: 0L) }
                            val values = aggResp.mapNotNull { it.result[StepsRecord.COUNT_TOTAL]?.toInt() }.filter { it > 0 }
                            val isUniform = values.size >= 5 && values.let {
                                val mn = it.min(); val mx = it.max()
                                mn > 0 && (mx - mn).toDouble() / mn < 0.1
                            }
                            val verdict = if (isUniform) "🚨균등(=일일통합)" else "✓다양함"
                            appendLine("  [$label] 버킷 ${aggResp.size}개 / 비-0 ${nonZeroBuckets}개 / 합 ${total}보 $verdict")
                        } catch (e: Exception) {
                            appendLine("  [$label] 실패: ${e.message?.take(40)}")
                        }
                    }
                    appendLine()
                    appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━")
                    appendLine("✅ 새 알고리즘 결과 (실제 앱에서 사용):")
                    appendLine()

                    // 단기 레코드 가장 많은 source 선택
                    val shortThreshold = 60 * 60L
                    val hourlySourceEntry = bySource.entries
                        .map { (src, recs) ->
                            src to recs.filter {
                                (it.endTime.epochSecond - it.startTime.epochSecond) < shortThreshold
                            }
                        }
                        .filter { it.second.isNotEmpty() }
                        .maxByOrNull { it.second.size }

                    if (hourlySourceEntry == null) {
                        appendLine("⚠️ 단기 레코드 source 없음 — 일일 통합만 있음")
                    } else {
                        val (chosenSrc, chosenRecs) = hourlySourceEntry
                        appendLine("📌 선택된 시간대별 source: ${chosenSrc.takeLast(28)}")
                        appendLine("   단기 레코드 ${chosenRecs.size}개")
                        appendLine()

                        val hourly2 = IntArray(24)
                        chosenRecs.forEach { r ->
                            val hour = r.startTime.atZone(zone).hour.coerceIn(0, 23)
                            hourly2[hour] += r.count.toInt()
                        }
                        val hourlySum2 = hourly2.sum()
                        val total2 = totalBySource.values.maxOrNull() ?: 0
                        // 비례 조정
                        if (hourlySum2 > 0 && total2 > 0 && hourlySum2 != total2) {
                            val ratio = total2.toDouble() / hourlySum2
                            for (i in 0 until 24) hourly2[i] = (hourly2[i] * ratio).toInt()
                        }

                        appendLine("⏱ 시간대별 분포 (총 ${"%,d".format(total2)}보 비례 조정):")
                        for (h in 0 until 24) {
                            if (hourly2[h] > 0) {
                                val bar = "█".repeat((hourly2[h] / 100).coerceAtMost(20).coerceAtLeast(1))
                                appendLine("  ${"%02d".format(h)}시: ${"%5d".format(hourly2[h])}보  $bar")
                            }
                        }
                    }
                    appendLine()
                    appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━")
                    appendLine("💡 위 \"새 알고리즘 결과\"가 실제 앱에서 식후 걷기 분석에 사용됩니다")
                }.trim()
            )
        })

        // 2. 수면 (자정 넘어가는 케이스 처리 — 해당 일자에 깬 수면)
        // ⭐ 화면 일자 = 깬 날짜 (기상 일자) 기준. "${date} 수면" = "${date-1} 밤에 누워서 ${date} 아침에 깬 수면"
        val prevDay = targetDate.minusDays(1)
        results.add(diagnose("😴 SleepSessionRecord (수면)", granted,
            "${prevDay} 18:00 ~ ${targetDate.plusDays(1)} 14:00 (${prevDay} 밤 ~ ${targetDate} 아침)"
        ) {
            val sleepStart = targetDate.atStartOfDay(zone).minusHours(6).toInstant()
            val sleepEnd = targetDate.plusDays(1).atStartOfDay(zone).plusHours(14).toInstant()
            val wideFilter = TimeRangeFilter.between(sleepStart, sleepEnd)
            client().readRecords(ReadRecordsRequest(SleepSessionRecord::class, wideFilter)).records.let { recs ->
                // "이 화면 = 어느 날 밤 수면인지" 자동 추정
                val nightSessions = recs.sortedByDescending { it.endTime }.filter {
                    val endDate = it.endTime.atZone(zone).toLocalDate()
                    endDate == targetDate
                }
                val earliestStart = nightSessions.minByOrNull { it.startTime }?.startTime
                val latestEnd = nightSessions.maxByOrNull { it.endTime }?.endTime
                // [260728 확정] 총 수면 = 4단계 합(체력회복잠+얕은잠+렘+깬시간) + 단계 미분류(SLEEPING),
                //   합이 0이면 세션 구간 폴백.
                //   업로드(fetchWatchDataFromHc)·차트(fetchSleepByDayFromHc)와 같은 식 — 진단 화면이 다른 값을 보이면 디버깅이 꼬인다.
                //   (세션 구간도 같이 찍어 "판정 안 된 구간" 크기와 폴백 발생을 눈으로 확인)
                val sessionSpanMins = nightSessions.sumOf { (it.endTime.epochSecond - it.startTime.epochSecond) / 60 }
                val fourStageMins = nightSessions.sumOf { s ->
                    s.stages.sumOf { st ->
                        when (st.stage) {
                            SleepSessionRecord.STAGE_TYPE_DEEP,
                            SleepSessionRecord.STAGE_TYPE_LIGHT,
                            SleepSessionRecord.STAGE_TYPE_REM,
                            SleepSessionRecord.STAGE_TYPE_AWAKE,
                            SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED ->
                                (st.endTime.epochSecond - st.startTime.epochSecond) / 60
                            else -> 0L
                        }
                    }
                }
                // ⭐ [260729] 단계 미분류(SLEEPING=2) — 세부 단계 없이 "잠"으로만 기록하는 기기 분량. 총합에만 반영.
                val unstagedMins = nightSessions.sumOf { s ->
                    s.stages.sumOf { st ->
                        if (st.stage == SleepSessionRecord.STAGE_TYPE_SLEEPING)
                            (st.endTime.epochSecond - st.startTime.epochSecond) / 60
                        else 0L
                    }
                }
                val stageSumMins = fourStageMins + unstagedMins
                val totalMins = if (stageSumMins > 0) stageSumMins else sessionSpanMins

                Triple(
                    recs.size,
                    recs.map { it.metadata.dataOrigin.packageName }.distinct(),
                    buildString {
                        appendLine("📌 \"${targetDate}\" 화면에 표시되는 수면 = \"${prevDay} 밤\"에 누워서 \"${targetDate} 아침\"에 깬 수면")
                        appendLine()
                        if (earliestStart != null && latestEnd != null) {
                            val sZ = earliestStart.atZone(zone)
                            val eZ = latestEnd.atZone(zone)
                            appendLine("🛏 누운 시각:   ${sZ.toLocalDate()} ${String.format("%02d:%02d", sZ.hour, sZ.minute)}")
                            appendLine("☀ 깬 시각:    ${eZ.toLocalDate()} ${String.format("%02d:%02d", eZ.hour, eZ.minute)}")
                            appendLine("⏱ 총 수면:    ${totalMins / 60}시간 ${totalMins % 60}분 (${nightSessions.size}개 세션)")
                            appendLine("   ├ 4단계 합:    ${fourStageMins}분 (체력회복+얕은+렘+깸)")
                            appendLine("   ├ 단계 미분류: ${unstagedMins}분" +
                                if (unstagedMins > 0) " ← SLEEPING(2). 세부 단계 없이 '잠'으로만 기록하는 기기. 총합엔 포함, 단계 분포엔 미포함"
                                else "")
                            appendLine("   ├ 합계:        ${stageSumMins}분 ← 업로드/차트가 쓰는 정의")
                            appendLine("   └ 세션 구간:   ${sessionSpanMins}분" +
                                if (stageSumMins <= 0) " (단계 데이터 없음 → 폴백 사용됨)"
                                else if (sessionSpanMins > stageSumMins) " (어느 stage 에도 안 잡힌 ${sessionSpanMins - stageSumMins}분은 총합에서 제외)"
                                else "")
                        } else {
                            appendLine("⚠️ ${targetDate}에 깬 수면이 없음")
                        }
                        appendLine()
                        appendLine("📋 조회 범위 내 모든 수면 세션 (${recs.size}개):")
                        recs.sortedBy { it.startTime }.forEach { s ->
                            val sZ = s.startTime.atZone(zone)
                            val eZ = s.endTime.atZone(zone)
                            val startStr = "${String.format("%02d-%02d", sZ.monthValue, sZ.dayOfMonth)} ${String.format("%02d:%02d", sZ.hour, sZ.minute)}"
                            val endStr = "${String.format("%02d-%02d", eZ.monthValue, eZ.dayOfMonth)} ${String.format("%02d:%02d", eZ.hour, eZ.minute)}"
                            val mins = (s.endTime.epochSecond - s.startTime.epochSecond) / 60
                            val endDate = s.endTime.atZone(zone).toLocalDate()
                            val tag = if (endDate == targetDate) "★" else " "
                            appendLine("  $tag $startStr ~ $endStr ($mins 분, ${s.stages.size}단계)")
                        }
                        appendLine()
                        appendLine("★ = ${targetDate} 화면에 합산되는 세션")
                    }.trim()
                )
            }
        })

        // 3. 심박 (해당 일자 0:00~23:59)
        results.add(diagnose("❤️ HeartRateRecord (심박)", granted, dayRangeLabel) {
            client().readRecords(ReadRecordsRequest(HeartRateRecord::class, filter)).records.let { recs ->
                val totalSamples = recs.sumOf { it.samples.size }
                val allSamples = recs.flatMap { it.samples }.sortedBy { it.time }
                val first = allSamples.firstOrNull()
                val last = allSamples.lastOrNull()
                Triple(
                    totalSamples,
                    recs.map { it.metadata.dataOrigin.packageName }.distinct(),
                    buildString {
                        if (first != null && last != null) {
                            appendLine("📌 측정 시간: ${first.time.atZone(zone).toLocalTime()} ~ ${last.time.atZone(zone).toLocalTime()}")
                            appendLine("📌 평균: ${allSamples.map { it.beatsPerMinute }.average().toInt()} BPM")
                            appendLine("📌 최저/최고: ${allSamples.minOf { it.beatsPerMinute }} / ${allSamples.maxOf { it.beatsPerMinute }} BPM")
                            appendLine()
                            appendLine("📋 처음 3개:")
                            allSamples.take(3).forEach { s ->
                                appendLine("  • ${s.time.atZone(zone).toLocalTime()}: ${s.beatsPerMinute} BPM")
                            }
                        }
                    }.trim()
                )
            }
        })

        // 4. HRV (해당 일자)
        results.add(diagnose("📈 HeartRateVariabilityRmssdRecord (HRV)", granted, dayRangeLabel) {
            client().readRecords(ReadRecordsRequest(HeartRateVariabilityRmssdRecord::class, filter)).records.let { recs ->
                Triple(
                    recs.size,
                    recs.map { it.metadata.dataOrigin.packageName }.distinct(),
                    recs.take(3).joinToString("\n") { "  • ${it.time.atZone(zone).toLocalTime()}: ${"%.1f".format(it.heartRateVariabilityMillis)}ms" }
                )
            }
        })

        // 5. SpO2 (해당 일자)
        results.add(diagnose("🫁 OxygenSaturationRecord (SpO2)", granted, dayRangeLabel) {
            client().readRecords(ReadRecordsRequest(OxygenSaturationRecord::class, filter)).records.let { recs ->
                Triple(
                    recs.size,
                    recs.map { it.metadata.dataOrigin.packageName }.distinct(),
                    recs.take(5).joinToString("\n") { "  • ${it.time.atZone(zone).toLocalTime()}: ${"%.1f".format(it.percentage.value)}%" }
                )
            }
        })

        // 로그 출력
        results.forEach { r ->
            Log.d(TAG, "[${if (r.permissionGranted) "✓" else "✗"}] ${r.type}: ${r.recordCount}개 (${r.timeRange}) ${r.error ?: ""}")
        }

        results
    }

    private suspend fun diagnose(
        type: String,
        granted: Set<String>,
        timeRange: String,
        block: suspend () -> Triple<Int, List<String>, String>,
    ): RawDataDiagnostic {
        return try {
            val (count, sources, sample) = block()
            RawDataDiagnostic(
                type = type,
                permissionGranted = true,
                recordCount = count,
                sources = sources,
                sample = sample,
                timeRange = timeRange,
            )
        } catch (e: SecurityException) {
            RawDataDiagnostic(
                type = type,
                permissionGranted = false,
                recordCount = 0,
                sources = emptyList(),
                sample = "",
                timeRange = timeRange,
                error = "권한 없음",
            )
        } catch (e: Exception) {
            RawDataDiagnostic(
                type = type,
                permissionGranted = true,
                recordCount = 0,
                sources = emptyList(),
                sample = "",
                timeRange = timeRange,
                error = "오류: ${e.message?.take(80)}",
            )
        }
    }
}
