package com.dangbomcare.app.data.repo

import com.dangbomcare.app.data.api.*

/**
 * Repository = ApiService 호출을 sealed Result로 감싸 ViewModel에 단순한 인터페이스 제공.
 */
sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Error(val code: String, val message: String) : ApiResult<Nothing>()
}

private suspend fun <T> safeCall(block: suspend () -> retrofit2.Response<T>): ApiResult<T> {
    return try {
        val res = block()
        if (res.isSuccessful) {
            res.body()?.let { ApiResult.Success(it) } ?: ApiResult.Error("EMPTY", "응답 비어있음")
        } else {
            val errBody = res.errorBody()?.string().orEmpty()
            // 백엔드 에러 형식: {"detail": {"error": "...", "message": "..."}} 또는 {"error": "...", "message": "..."}
            val code = parseErrorCode(errBody) ?: "HTTP_${res.code()}"
            val msg = parseErrorMessage(errBody) ?: res.message().orEmpty().ifBlank { "요청 실패" }
            ApiResult.Error(code, msg)
        }
    } catch (ce: kotlinx.coroutines.CancellationException) {
        // [260726] 코루틴 취소는 "네트워크 오류"가 아니다 — 반드시 일반 catch보다 먼저 잡아 그대로 전파.
        //   기존엔 CancellationException까지 ApiResult.Error("NETWORK")로 바꿔서, 호출자(WatchSyncWorker 90일 백필)가
        //   stop 된 뒤에도 루프를 끝까지 돌며 실패를 정상 집계 → 백필 "완료" 플래그를 잘못 기록하는 원인이 됐다.
        throw ce
    } catch (e: Exception) {
        ApiResult.Error("NETWORK", e.message ?: "네트워크 오류")
    }
}

private fun parseErrorCode(body: String): String? {
    return Regex("\"error\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.getOrNull(1)
}

private fun parseErrorMessage(body: String): String? {
    // 1) FastAPI 422: {"detail":[{"loc":[...,X],"msg":"..."}]}
    val msgMatch = Regex("\"msg\"\\s*:\\s*\"([^\"]+)\"").find(body)
    if (msgMatch != null) {
        val msg = msgMatch.groupValues[1]
        val locMatch = Regex("\"loc\"\\s*:\\s*\\[[^\\]]*?([^\",\\[\\]]+?)\\s*\\]").find(body)
        val locTail = locMatch?.groupValues?.getOrNull(1)?.trim('"', ' ')
        return if (locTail != null) "$locTail: $msg" else msg
    }
    // 2) 우리 백엔드 정의 에러: {"message":"..."}
    return Regex("\"message\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.getOrNull(1)
}

class UserRepository(private val api: ApiService) {
    suspend fun getMe(): ApiResult<UserMeResponse> = safeCall { api.getMe() }
    suspend fun updateMe(req: UserUpdateRequest): ApiResult<UserMeResponse> = safeCall { api.updateMe(req) }
    suspend fun deleteMe(): ApiResult<Map<String, Any>> = safeCall { api.deleteMe() }
    suspend fun postConsents(req: UserConsentsRequest) = safeCall { api.postConsents(req) }

    /**
     * [260728 우노 마케팅 동의] 선택 동의 철회/재동의.
     *   safeCall 을 쓰지 않는 이유: safeCall 은 body == null 을 ApiResult.Error("EMPTY") 로 본다.
     *   이 엔드포인트는 204/빈 200 을 줄 수 있고, 그걸 실패로 오인하면 UI 토글이 서버와 어긋난 채
     *   원복돼(=실제로는 저장됐는데 "실패"로 표시) 사용자가 수신 거부한 줄 알게 된다.
     *   → HTTP 상태만으로 판정하고 본문은 닫아서 버린다.
     * @return 성공 시 서버에 반영된 값(요청값)
     */
    suspend fun updateMarketingConsent(accepted: Boolean): ApiResult<Boolean> {
        return try {
            val res = api.updateMarketingConsent(MarketingConsentRequest(accepted))
            if (res.isSuccessful) {
                res.body()?.close()
                ApiResult.Success(accepted)
            } else {
                val errBody = res.errorBody()?.string().orEmpty()
                ApiResult.Error(
                    parseErrorCode(errBody) ?: "HTTP_${res.code()}",
                    parseErrorMessage(errBody) ?: res.message().orEmpty().ifBlank { "요청 실패" }
                )
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (e: Exception) {
            ApiResult.Error("NETWORK", e.message ?: "네트워크 오류")
        }
    }
    suspend fun updateSettings(req: UserSettingsRequest) = safeCall { api.updateSettings(req) }
    suspend fun updateFcmToken(token: String) = safeCall { api.updateFcmToken(FcmTokenRequest(token)) }
    // [260904] 클라이언트 상태 보고 / 강제 업데이트 정책
    suspend fun reportClientStatus(req: ClientStatusRequest) = safeCall { api.updateClientStatus(req) }
    suspend fun versionPolicy(): ApiResult<AppVersionPolicy> = safeCall { api.getVersionPolicy() }
}

class DailyLogRepository(private val api: ApiService) {
    suspend fun create(req: DailyLogCreateRequest): ApiResult<DailyLogResponse> = safeCall { api.createDailyLog(req) }
    suspend fun get(date: String): ApiResult<DailyLogResponse> = safeCall { api.getDailyLog(date) }
    suspend fun list(from: String, to: String): ApiResult<List<DailyLogInner>> = safeCall { api.listDailyLogs(from, to) }
}

class WatchDataRepository(private val api: ApiService) {
    suspend fun post(req: WatchDataRequest) = safeCall { api.postWatchData(req) }
    suspend fun get(date: String): ApiResult<WatchDataResponse> = safeCall { api.getWatchData(date) }
}

/** 의료진 목표 (우노 피드백 4·5) — 조회/이력/저장. PIN 잠금은 화면에서 처리. */
class MedicalGoalRepository(private val api: ApiService) {
    suspend fun get(): ApiResult<MedicalGoalResponse> = safeCall { api.getMedicalGoal() }
    suspend fun history(): ApiResult<List<MedicalGoalResponse>> = safeCall { api.getMedicalGoalHistory() }
    suspend fun update(req: MedicalGoalRequest): ApiResult<MedicalGoalResponse> = safeCall { api.updateMedicalGoal(req) }
}

class ScoreRepository(private val api: ApiService) {
    suspend fun today(): ApiResult<ScoreTodayResponse> = safeCall { api.getTodayScore() }
    suspend fun byDate(date: String) = safeCall { api.getScoreByDate(date) }
    suspend fun list(from: String, to: String): ApiResult<List<ScoreListItem>> = safeCall { api.listScores(from, to) }
    suspend fun todayFeedback(): ApiResult<AIFeedbackToday> = safeCall { api.getTodayFeedback() }
    suspend fun dailySummary(date: String): ApiResult<DailySummaryResponse> = safeCall { api.getDailySummary(date) }
}

class ReportRepository(private val api: ApiService) {
    suspend fun weekly(weekStart: String, refresh: Boolean = false): ApiResult<WeeklyReportResponse> = safeCall { api.getWeeklyReport(weekStart, refresh) }
    suspend fun monthly(year: Int, month: Int, refresh: Boolean = false): ApiResult<MonthlyReportResponse> = safeCall { api.getMonthlyReport(year, month, refresh) }
}

/**
 * CSV 내보내기 — 백엔드에 자동 저장된 가공 데이터 다운로드 (의료진 공유 / 개인 백업).
 * Health Connect raw 데이터는 별도로 HealthDataCsvExporter 사용.
 */
class CsvExportRepository(private val api: ApiService) {
    suspend fun listFiles(): ApiResult<CsvFileListResponse> = safeCall { api.listCsvFiles() }

    /**
     * 단일 CSV 파일 다운로드 후 byte 배열로 반환.
     * 호출자는 받은 byte를 파일로 저장하거나 공유 인텐트로 전달.
     */
    suspend fun downloadFile(filename: String): ApiResult<ByteArray> {
        return try {
            val res = api.downloadCsvFile(filename)
            if (res.isSuccessful) {
                val bytes = res.body()?.bytes()
                if (bytes != null) ApiResult.Success(bytes)
                else ApiResult.Error("EMPTY", "파일 내용이 비어있어요")
            } else {
                ApiResult.Error("HTTP_${res.code()}", res.message().orEmpty().ifBlank { "다운로드 실패" })
            }
        } catch (e: Exception) {
            ApiResult.Error("NETWORK", e.message ?: "네트워크 오류")
        }
    }

    /** [통합 CSV] 모든 데이터를 하루 1행으로 합친 한 파일 다운로드. */
    suspend fun downloadFull(): ApiResult<ByteArray> {
        return try {
            val res = api.downloadFullCsv()
            if (res.isSuccessful) {
                val bytes = res.body()?.bytes()
                if (bytes != null) ApiResult.Success(bytes)
                else ApiResult.Error("EMPTY", "파일 내용이 비어있어요")
            } else {
                ApiResult.Error("HTTP_${res.code()}", res.message().orEmpty().ifBlank { "다운로드 실패" })
            }
        } catch (e: Exception) {
            ApiResult.Error("NETWORK", e.message ?: "네트워크 오류")
        }
    }

    /** [통합 엑셀] 모든 데이터를 하루 1행으로 합친 .xlsx 다운로드. */
    suspend fun downloadFullXlsx(): ApiResult<ByteArray> {
        return try {
            val res = api.downloadFullXlsx()
            if (res.isSuccessful) {
                val bytes = res.body()?.bytes()
                if (bytes != null) ApiResult.Success(bytes)
                else ApiResult.Error("EMPTY", "파일 내용이 비어있어요")
            } else {
                ApiResult.Error("HTTP_${res.code()}", res.message().orEmpty().ifBlank { "다운로드 실패" })
            }
        } catch (e: Exception) {
            ApiResult.Error("NETWORK", e.message ?: "네트워크 오류")
        }
    }
}
