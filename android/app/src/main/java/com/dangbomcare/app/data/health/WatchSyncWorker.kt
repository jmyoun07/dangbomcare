package com.dangbomcare.app.data.health

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dangbomcare.app.data.ServiceLocator
import com.dangbomcare.app.data.repo.ApiResult
import kotlinx.coroutines.flow.firstOrNull
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * 백그라운드 워치 데이터 자동 동기화 워커 (사용자 피드백 — "자동 동기화가 되어야 함").
 *
 * - 30분 주기로 실행 (WorkManager 최소 단위 15분 이상)
 * - 네트워크 연결 필요
 * - HC 권한(백그라운드 read 포함) / 액세스 토큰 없으면 즉시 성공 반환 (다음 차례에 재시도)
 * - 오늘 + 어제 데이터 모두 백엔드로 POST
 * - 성공 시 WatchSyncTracker.mark() 호출 → 홈/설정 화면 라벨 갱신
 *
 * [Codex 검토 반영]
 *   • Doze/App Standby 환경에선 30분 보장 X → 앱 진입·로그인 직후 OneTimeWorkRequest로 보강
 *   • non-transient 실패(권한·토큰 없음)는 Result.retry()가 아니라 success() skip 처리
 *   • READ_HEALTH_DATA_IN_BACKGROUND 권한 필수 (AndroidManifest 등록 완료)
 */
class WatchSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext

        // 1) 액세스 토큰 (로그인 안 됐으면 skip)
        val tokenStorage = ServiceLocator.tokenStorage
        if (tokenStorage.getAccessTokenBlocking().isNullOrBlank()) {
            Log.d(TAG, "skip — not logged in")
            return Result.success()
        }

        // [v1.0 데모용] 데모 계정은 시드 데이터 유지를 위해 sync 스킵
        //   [Codex 검토 v1.0] 기존 ".demo@" contains 매칭은 일반 사용자(예: john.demo@naver.com) 영구 차단 위험.
        //   → "dangdangcare.demo" prefix 정확 매칭으로 좁힘 (시연용 이메일만 차단).
        val currentEmail: String? = try {
            tokenStorage.emailFlow.firstOrNull()
        } catch (_: Exception) { null }
        if (!currentEmail.isNullOrBlank() && currentEmail.lowercase().startsWith("dangdangcare.demo")) {
            // [v1.0 fix - Codex 2차] release logcat에 이메일 노출 차단 — DEBUG 빌드만 상세 log
            if (com.dangbomcare.app.BuildConfig.DEBUG) {
                Log.d(TAG, "skip — demo account ($currentEmail), preserving seed data")
            } else {
                Log.d(TAG, "skip — demo account, preserving seed data")
            }
            return Result.success()
        }

        // 2) 워치 접근 — SDK 직접 읽기 활성이면 HC 권한 없이 진행(fetchWatchData가 SDK로 읽음).
        //    아니면 기존대로 HC 가용 + 권한 전체(background-read 포함) 필요.
        val hc = ServiceLocator.healthConnectRepository
        val sdkOn = try { hc.isSdkDirectActive() } catch (_: Exception) { false }
        if (!sdkOn) {
            if (!hc.isSdkAvailable()) {
                Log.d(TAG, "skip — HC unavailable & SDK off")
                return Result.success()
            }
            val perms = try { hc.checkPermissions() } catch (e: Exception) { null }
            if (perms == null || !perms.all) {
                // [Codex 검토] background read 권한이 한 항목이라도 빠지면 SecurityException 위험 →
                // any가 아니라 all 기준으로 gating. 사용자가 다음 권한 부여 시 enqueueImmediateSync()로 트리거됨.
                Log.d(TAG, "skip — HC permissions incomplete (need all incl. background-read)")
                return Result.success()
            }
        }

        // 3) 데이터 fetch + POST
        //   [v1.0 버그픽스] 신규 사용자는 첫 sync 시 30일치 backfill (이전엔 어제+오늘만 →
        //   가입 직후 일주일/월간 리포트가 텅 비어보이던 문제).
        //   2회차부터는 어제+오늘만 (기존 동작 유지, 가벼움).
        // [v1.0 — Codex 교차검증] 원샷 백필 한계 보완.
        //   기존: 첫 sync만 30일, 이후엔 어제+오늘만 → 삼성헬스가 과거 데이터를 HC에 "늦게" 넣어주면
        //   백엔드/점수/리포트에 영영 반영 안 됨(재설치해야만 백필 재실행)이던 버그.
        //   [260609 사용자 결정] 최초 1회 90일 백필(Supabase 초기 채움) + 이후 매 동기화 7일 롤링(자동 범위=1주).
        //   ⚠️ 전제: 백엔드 post는 partial-update(필드 null이면 기존 값 유지)여야 재전송이 안전·멱등.
        //   데이터가 전혀 없는 날(all-null)은 POST 스킵 — 빈 값 덮어쓰기 방지 + 부하 감소.
        // [260726] 날짜 기준 시간대 통일 — 백엔드 log_date가 KST 고정이므로 여기서도 KST로 산출.
        //   기존 LocalDate.now()(기기 로컬)는 해외 체류·기기 시각 오류 시 WatchSyncActions(KST) 경로와
        //   같은 물리 데이터를 서로 다른 log_date로 기록했다. (전제: 사용자의 생활 시간대 = KST, 국내 전용 서비스)
        val today = LocalDate.now(HealthConnectRepository.KST)
        val initialDone = WatchSyncTracker.isInitialBackfillDone(ctx)
        val syncDays = if (!initialDone) 90 else 7
        Log.d(TAG, if (!initialDone) "초기 백필 — ${syncDays + 1}일 (최초 1회)" else "rolling sync — ${syncDays + 1}일")
        val watchRepo = ServiceLocator.watchDataRepository
        return try {
            // 과거 → 현재 순으로 POST (시간 순서대로 백엔드에 쌓이게)
            var successCount = 0
            var failCount = 0
            var skipped = 0
            for (i in syncDays downTo 0) {
                val date = today.minusDays(i.toLong())
                val data = hc.fetchWatchData(date)
                // 그 날 HC에 데이터가 전혀 없으면(all-null) 스킵 — 빈 값으로 덮어쓰지 않음.
                if (data.steps == null && data.sleepTotalMinutes == null &&
                    data.avgHeartRate == null && data.spo2Avg == null) {
                    skipped++
                    continue
                }
                val result = watchRepo.post(data)
                if (result is ApiResult.Success) successCount++ else failCount++
            }
            if (successCount > 0) {
                WatchSyncTracker.mark(ctx)
                // 90일 백필 1회 성공 → 이후 7일 롤링으로 전환.
                //   (IOException 등으로 중간에 throw되면 여기 도달 못 함 → 아래 catch에서 retry → 다음에 다시 90일.)
                // [260726] "완료" 확정 조건 강화 — 전부 성공(failCount == 0) + 워커가 stop되지 않았을 때만.
                //   기존엔 successCount > 0 만 보고 확정했다. 하위(safeCall·HealthConnectRepository)가 예외를 삼켜
                //   실행 한도로 stop 돼도 루프가 끝까지 돌았고, 앞부분만 성공해도 완료 플래그가 기록됐다.
                //   → 이후 영구히 7일 롤링만 돌아 8~90일 전 데이터는 재설치 전엔 복구 불가였음.
                if (!initialDone && failCount == 0 && !isStopped) WatchSyncTracker.markInitialBackfillDone(ctx)
            }
            Log.d(TAG, "sync done: ok=$successCount fail=$failCount skip(빈날)=$skipped / 범위 ${syncDays + 1}일")
            // [260726] 전부 실패(성공 0 + 실패 有)면 WorkManager 백오프 재시도로 넘긴다.
            //   아래 catch의 Result.retry()는 하위에서 예외를 전부 삼켜 사실상 도달하지 않아
            //   failCount가 91이어도 success()로 끝나 재시도 기회가 없었다.
            if (failCount > 0 && successCount == 0) Result.retry() else Result.success()
        } catch (ce: kotlinx.coroutines.CancellationException) {
            // [260726] 취소는 삼키지 말고 전파 (safeCall이 더 이상 CancellationException을 Error로 바꾸지 않음).
            //   여기서 잡아 success()로 끝내면 stop된 백필이 정상 종료로 위장된다.
            throw ce
        } catch (e: Exception) {
            // 네트워크 예외만 retry — 그 외(SerializationException 등)는 skip
            Log.e(TAG, "sync failed: ${e.message}", e)
            if (e is java.io.IOException) Result.retry() else Result.success()
        }
    }

    companion object {
        const val TAG = "WatchSyncWorker"
        const val UNIQUE_NAME = "watch_sync_periodic"
        const val UNIQUE_ONE_TIME = "watch_sync_one_time"  // 즉시 1회 트리거용

        private fun networkConstraints(): Constraints =
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

        /**
         * 앱 시작 시점(Application.onCreate)에서 호출.
         * 이미 등록돼 있으면 유지 (KEEP), 없으면 새로 enqueue.
         * 주기: 30분 (WorkManager 최소 15분 이상이라 의도된 값)
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<WatchSyncWorker>(
                repeatInterval = 30, repeatIntervalTimeUnit = TimeUnit.MINUTES,
                flexTimeInterval = 10, flexTimeIntervalUnit = TimeUnit.MINUTES,
            )
                .setConstraints(networkConstraints())
                .addTag(TAG)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /**
         * [Codex 검토 P0 — rescheduleNow는 즉시 실행 보장 X]
         * 로그인 직후·권한 부여 직후 등에 "지금 즉시 1회" 동기화하고 싶을 때 호출.
         * 별도 OneTimeWorkRequest를 enqueueUniqueWork(REPLACE)로 등록 — 기존 periodic은 영향 X.
         */
        fun enqueueImmediateSync(context: Context) {
            val request = OneTimeWorkRequestBuilder<WatchSyncWorker>()
                .setConstraints(networkConstraints())
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_ONE_TIME,
                ExistingWorkPolicy.REPLACE,  // 직전 1회 호출이 미실행 상태면 교체
                request,
            )
        }

        /**
         * @deprecated [Codex 검토] periodic UPDATE는 enqueue time을 보존 → 즉시 실행 보장 X.
         * 새 코드에서는 [enqueueImmediateSync] 를 사용하세요.
         */
        @Deprecated(
            "periodic UPDATE는 즉시 실행 보장 X — enqueueImmediateSync() 사용",
            ReplaceWith("enqueueImmediateSync(context)")
        )
        fun rescheduleNow(context: Context) {
            enqueueImmediateSync(context)
        }
    }
}
