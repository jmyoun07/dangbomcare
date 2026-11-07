package com.dangbomcare.app.data

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import com.dangbomcare.app.BuildConfig
import com.dangbomcare.app.data.api.ClientStatusRequest
import com.dangbomcare.app.data.health.BatteryOptimization
import com.dangbomcare.app.data.repo.ApiResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * [260904] 클라이언트 상태 보고 — 앱 버전 · 배터리 최적화 예외 · Health Connect 권한/가용성 · 기기.
 *
 * 왜: 260903 한의원 문의(환자 여러 명 걸음수 미수신). 운영 DB 를 보니 문의 환자 5명은 watch_data 행이
 *   한 건도 없었는데, 원인이 "구버전 앱인지 / 배터리 최적화인지 / HC 권한이 없는지"를 서버에서
 *   구분할 방법이 전혀 없었다(앱은 이 값을 폰 안에서만 판단). → 포그라운드 진입 때마다 서버에 보고한다.
 *
 * 호출: MainActivity.onResume (동기화 트리거와 같은 자리). 로그인 전이면 아무것도 안 한다.
 * 절약: 상태 지문(버전·최적화·HC상태)이 지난 보고와 같고 6시간이 안 지났으면 요청을 보내지 않는다.
 *   지문이 바뀌면(예: 시스템 설정에서 최적화 해제하고 돌아옴) 즉시 보고 → 관리자 웹에서 바로 보인다.
 * 실패: 전부 best-effort. 예외는 삼키고 다음 진입에 다시 시도.
 */
object ClientStatusReporter {

    private const val TAG = "ClientStatus"
    private const val PREFS = "client_status_prefs"
    private const val KEY_FP = "last_fingerprint"
    private const val KEY_AT = "last_reported_ms"
    private const val MIN_INTERVAL_MS = 6 * 60 * 60 * 1000L

    fun reportIfDue(context: Context, scope: CoroutineScope) {
        val app = context.applicationContext
        scope.launch(Dispatchers.IO) {
            try {
                report(app)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "report failed: ${t.message}")
            }
        }
    }

    suspend fun report(context: Context) {
        val token = ServiceLocator.tokenStorage.getAccessTokenBlocking()
        if (token.isNullOrBlank()) return

        val req = collect(context)
        val fingerprint = listOf(
            req.appVersionCode, req.batteryOptIgnored, req.shealthInstalled,
            req.shealthOptIgnored, req.healthSourceStatus,
        ).joinToString("|")

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val unchanged = prefs.getString(KEY_FP, null) == fingerprint
        if (unchanged && now - prefs.getLong(KEY_AT, 0L) < MIN_INTERVAL_MS) return

        val res = ServiceLocator.userRepository.reportClientStatus(req)
        if (res is ApiResult.Success) {
            prefs.edit().putString(KEY_FP, fingerprint).putLong(KEY_AT, now).apply()
            Log.d(TAG, "reported: $fingerprint")
        } else if (res is ApiResult.Error) {
            Log.w(TAG, "report rejected: ${res.code} ${res.message}")
        }
    }

    /** 현재 상태 수집 (IO 에서 호출 — HC 권한 조회·삼성 SDK IPC 포함). */
    suspend fun collect(context: Context): ClientStatusRequest {
        val hc = ServiceLocator.healthConnectRepository
        // 워치 데이터 읽기 경로 상태 — WatchSyncWorker 의 게이트(SDK 직접읽기 OR HC 권한 전부)와 같은 기준.
        val healthStatus = try {
            if (hc.isSdkDirectActive()) {
                "sdk_direct"
            } else {
                when (hc.getSdkStatus()) {
                    HealthConnectClient.SDK_UNAVAILABLE -> "hc_unavailable"
                    HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "hc_update_required"
                    else -> {
                        val p = hc.checkPermissions()
                        when {
                            p.all -> "hc_ok"
                            p.any -> "hc_partial:${p.grantedCount}/${p.totalCount}"  // 워커는 skip, 홈 배너도 안 뜸
                            else -> "hc_no_permission"
                        }
                    }
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            "error:${e.javaClass.simpleName}"
        }

        val shInstalled = BatteryOptimization.isSamsungHealthInstalled(context)
        return ClientStatusRequest(
            appVersionCode = BuildConfig.VERSION_CODE,
            appVersionName = BuildConfig.VERSION_NAME,
            batteryOptIgnored = BatteryOptimization.isIgnoring(context),
            shealthInstalled = shInstalled,
            // 다른 패키지의 최적화 상태는 기기에 따라 항상 false 를 줄 수 있음 → 참고용 (BatteryOptimization 주석 참조)
            shealthOptIgnored = if (shInstalled) BatteryOptimization.isSamsungHealthIgnoring(context) else null,
            healthSourceStatus = healthStatus.take(64),
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(128),
            osSdk = Build.VERSION.SDK_INT,
        )
    }
}
