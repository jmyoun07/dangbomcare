package com.dangbomcare.app.data.health

import android.content.Context
import com.dangbomcare.app.data.ServiceLocator
import com.dangbomcare.app.data.api.WatchDataRequest
import com.dangbomcare.app.data.repo.ApiResult
import kotlinx.coroutines.CancellationException
import java.time.LocalDate

/**
 * 워치 동기화 공통 액션 — 홈 진입 자동 동기화 / 설정 '워치 동기화' 행이 함께 쓰는 안전 동기화 로직.
 *
 * MorningInputViewModel·WatchSyncWorker·runWatchBackfill 과 동일한 가드를 한 곳에 모음:
 *   1) canReadWatchData() (SDK 직접읽기 활성 OR HC 권한) 일 때만 진행
 *   2) 오늘 + 어제 데이터 fetch (오늘=실시간 카드, 어제=점수 채점)
 *   3) [Codex P0 데이터손실] all-null(steps/sleep/hr/spo2 전부 null)이면 POST 스킵 — 빈 값으로 정상 데이터 덮어쓰기 방지
 *   4) POST 성공이 1건이라도 있으면 WatchSyncTracker.mark()
 *
 * 호출 측에서 IO 디스패처 + try/catch 로 감싸 사용 (이 함수도 내부에서 예외를 삼키지 않고 일부는 전파).
 */
object WatchSyncActions {

    /** 오늘+어제 동기화 결과. */
    data class Result(
        /** 워치를 읽을 수 있는 상태였는지(canReadWatchData). false면 권한/SDK 미가용으로 아무것도 안 함. */
        val readable: Boolean,
        /** POST 성공한 날 수 (0이면 보낸 게 없음 — all-null 스킵 또는 실패). */
        val posted: Int,
        /** all-null 로 스킵한 날 수. */
        val skipped: Int,
        /** POST 실패한 날 수. */
        val failed: Int,
    ) {
        val anyPosted: Boolean get() = posted > 0
    }

    private fun isAllNull(d: WatchDataRequest): Boolean =
        d.steps == null && d.sleepTotalMinutes == null &&
            d.avgHeartRate == null && d.spo2Avg == null

    /**
     * 오늘 + 어제 워치 데이터를 백엔드로 안전하게 POST.
     *
     * @param healthRepo 화면이 이미 들고 있는 인스턴스 재사용 가능(없으면 ServiceLocator 사용).
     * @return 동기화 결과(readable/posted/skipped/failed).
     */
    suspend fun syncTodayAndYesterday(
        context: Context,
        healthRepo: HealthConnectRepository = ServiceLocator.healthConnectRepository,
    ): Result {
        // 1) 워치 접근 가능 여부 — MorningInput/수동 동기화와 동일 게이트.
        if (!healthRepo.canReadWatchData()) {
            return Result(readable = false, posted = 0, skipped = 0, failed = 0)
        }

        val watchRepo = ServiceLocator.watchDataRepository
        // [코덱스 P1] KST 기준 — 자정 전후/해외 timezone에서 '오늘/어제'가 어긋나지 않게 (TodayActivityCard와 동일).
        // [260726] 인라인 ZoneId.of("Asia/Seoul") → 공용 상수로 교체. 워커·HC 조회 경계와 같은 기준을 쓰기 위함.
        val today = LocalDate.now(HealthConnectRepository.KST)
        val days = listOf(today.minusDays(1), today)  // 과거→현재 순 (어제 먼저, 오늘 나중)

        var posted = 0
        var skipped = 0
        var failed = 0
        for (date in days) {
            try {
                val data = healthRepo.fetchWatchData(date)
                // [Codex P0 데이터손실] all-null → 빈 값 전송하면 서버의 정상 데이터를 덮어쓸 수 있음 → 스킵.
                if (isAllNull(data)) {
                    skipped++
                    continue
                }
                if (watchRepo.post(data) is ApiResult.Success) posted++ else failed++
            } catch (ce: CancellationException) {
                throw ce  // 취소는 삼키지 말고 전파 (화면 이탈 등)
            } catch (_: Exception) {
                failed++
            }
        }

        if (posted > 0) WatchSyncTracker.mark(context)
        return Result(readable = true, posted = posted, skipped = skipped, failed = failed)
    }

    /**
     * 최근 [days]일 + 오늘까지(총 days+1일) 워치 데이터를 백엔드로 안전하게 백필(backfill) POST.
     *
     * 설정 '워치 동기화' 행이 호출 — 가벼운 오늘+어제(syncTodayAndYesterday)와 달리,
     * "지난 기록이 안 보이면 최근 N일 다시 가져오기"용 전체 재동기화(수 초 소요).
     * WatchSyncWorker 의 range 로직(과거→현재 순, all-null 스킵, 1건이라도 성공 시 mark())과 동일.
     *
     * @param days 며칠 전부터 가져올지(기본 90). 실제로는 days..0(오늘 포함)으로 (days+1)일 처리.
     * @return 동기화 결과(readable/posted/skipped/failed).
     */
    suspend fun syncRange(
        context: Context,
        healthRepo: HealthConnectRepository = ServiceLocator.healthConnectRepository,
        days: Int = 90,
    ): Result {
        // 1) 워치 접근 가능 여부 — 오늘+어제 경로와 동일 게이트.
        if (!healthRepo.canReadWatchData()) {
            return Result(readable = false, posted = 0, skipped = 0, failed = 0)
        }

        val watchRepo = ServiceLocator.watchDataRepository
        // [코덱스 P1] KST 기준 — 자정 전후/해외 timezone에서 '오늘' 경계가 어긋나지 않게.
        // [260726] 인라인 ZoneId.of("Asia/Seoul") → 공용 상수로 교체 (워커·HC 조회 경계와 동일 기준).
        val today = LocalDate.now(HealthConnectRepository.KST)

        var posted = 0
        var skipped = 0
        var failed = 0
        // WatchSyncWorker 와 동일하게 과거 → 현재 순(days downTo 0)으로 POST (시간 순서대로 쌓이게).
        for (i in days downTo 0) {
            val date = today.minusDays(i.toLong())
            try {
                val data = healthRepo.fetchWatchData(date)
                // [Codex P0 데이터손실] all-null → 빈 값 전송하면 서버의 정상 데이터를 덮어쓸 수 있음 → 스킵.
                if (isAllNull(data)) {
                    skipped++
                    continue
                }
                if (watchRepo.post(data) is ApiResult.Success) posted++ else failed++
            } catch (ce: CancellationException) {
                throw ce  // 취소는 삼키지 말고 전파 (화면 이탈 등)
            } catch (_: Exception) {
                failed++
            }
        }

        if (posted > 0) WatchSyncTracker.mark(context)
        return Result(readable = true, posted = posted, skipped = skipped, failed = failed)
    }
}
