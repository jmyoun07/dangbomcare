package com.dangbomcare.app.data.api

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

interface ApiService {

    // ===== 인증 보조 (DEV/DEMO) =====
    @POST("api/auth/dev-auto-confirm")
    suspend fun devAutoConfirm(@Body req: AutoConfirmRequest): Response<Map<String, Any>>

    // [v1.0] 이메일 중복 체크 (회원가입 화면 실시간 검증)
    @GET("api/auth/check-email")
    suspend fun checkEmail(@retrofit2.http.Query("email") email: String): Response<CheckEmailResponse>

    // ===== 사용자 =====
    @GET("api/users/me")
    suspend fun getMe(): Response<UserMeResponse>

    @PUT("api/users/me")
    suspend fun updateMe(@Body req: UserUpdateRequest): Response<UserMeResponse>

    @DELETE("api/users/me")
    suspend fun deleteMe(): Response<Map<String, Any>>

    // [P0 코덱스 — 30일 회원탈퇴 정책] 탈퇴 취소 (30일 내 복구)
    @POST("api/users/me/cancel-deletion")
    suspend fun cancelAccountDeletion(): Response<Map<String, Any>>

    @POST("api/users/me/consents")
    suspend fun postConsents(@Body req: UserConsentsRequest): Response<Map<String, Any>>

    // [260728 우노 마케팅 동의] 선택 동의 철회/재동의 (설정 → 약관/정책 토글).
    //   ※ 260728 통합검증에서 실제 호출로 백엔드와 대조 완료 (200 OK, 404 아님).
    //     백엔드: users.py @router.patch("/me/consents/marketing") + MarketingConsentUpdateRequest(accepted: bool)
    //     PATCH api/users/me/consents/marketing   body: {"accepted": bool}
    //     → 경로·메서드·바디 중 하나라도 바꾸면 런타임 404/422 다. 백엔드와 함께 바꿀 것.
    //   응답 본문은 파싱하지 않는다(ResponseBody): 서버가 {} / 갱신된 consents / 204 무엇을 주더라도
    //   토글은 성공 여부만 필요하고, Gson 파싱 실패가 "저장 실패"로 오인되면 UI 가 잘못 롤백된다.
    @PATCH("api/users/me/consents/marketing")
    suspend fun updateMarketingConsent(@Body req: MarketingConsentRequest): Response<ResponseBody>

    @PUT("api/users/me/settings")
    suspend fun updateSettings(@Body req: UserSettingsRequest): Response<Map<String, Any>>

    @PUT("api/users/me/fcm-token")
    suspend fun updateFcmToken(@Body req: FcmTokenRequest): Response<Map<String, Any>>

    // [260904] 앱 버전·배터리 최적화·Health Connect 상태 보고 (걸음수 미수신 원인 분류용).
    //   백엔드: users.py @router.put("/me/client-status") + ClientStatusRequest. users 테이블 9컬럼에 저장.
    @PUT("api/users/me/client-status")
    suspend fun updateClientStatus(@Body req: ClientStatusRequest): Response<Map<String, Any>>

    // ===== 앱 설정 (인증 없음) =====
    // [260904] 강제 업데이트 정책 — Splash 에서 호출. 실패/타임아웃이면 그냥 통과(fail-open).
    //   백엔드: app_config.py @router.get("/version-policy") ← app_config 테이블(min_version_code 등)
    @GET("api/app/version-policy")
    suspend fun getVersionPolicy(): Response<AppVersionPolicy>

    // [피드백 260714] 매월 자동 공복혈당 범위 재정의 — 팝업 트리거 + 확인 표시
    @GET("api/users/me/glucose-range-suggestion")
    suspend fun getGlucoseRangeSuggestion(): Response<GlucoseRangeSuggestion>

    @POST("api/users/me/glucose-range-check-ack")
    suspend fun ackGlucoseRangeCheck(): Response<Map<String, Any>>

    // ===== 의료진 목표 (우노 피드백 4·5) =====
    @GET("api/medical-goals")
    suspend fun getMedicalGoal(): Response<MedicalGoalResponse>

    // [우노 4] 과거 목표 이력 (최신순, [0]=현재)
    @GET("api/medical-goals/history")
    suspend fun getMedicalGoalHistory(): Response<List<MedicalGoalResponse>>

    @PUT("api/medical-goals")
    suspend fun updateMedicalGoal(@Body req: MedicalGoalRequest): Response<MedicalGoalResponse>

    // ===== 일일 로그 (핵심) =====
    @POST("api/daily-logs")
    suspend fun createDailyLog(@Body req: DailyLogCreateRequest): Response<DailyLogResponse>

    @GET("api/daily-logs/{date}")
    suspend fun getDailyLog(@Path("date") date: String): Response<DailyLogResponse>

    @GET("api/daily-logs")
    suspend fun listDailyLogs(
        @Query("from") from: String,
        @Query("to") to: String
    ): Response<List<DailyLogInner>>

    // ===== 워치 =====
    @POST("api/watch-data")
    suspend fun postWatchData(@Body req: WatchDataRequest): Response<Map<String, Any>>

    @GET("api/watch-data/{date}")
    suspend fun getWatchData(@Path("date") date: String): Response<WatchDataResponse>

    // ===== 점수 =====
    @GET("api/scores/today")
    suspend fun getTodayScore(): Response<ScoreTodayResponse>

    @GET("api/scores/{date}")
    suspend fun getScoreByDate(@Path("date") date: String): Response<Map<String, Any>>

    @GET("api/scores")
    suspend fun listScores(
        @Query("from") from: String,
        @Query("to") to: String
    ): Response<List<ScoreListItem>>

    // ===== AI 피드백 =====
    @GET("api/ai-feedback/today")
    suspend fun getTodayFeedback(): Response<AIFeedbackToday>

    // ===== 리포트 =====
    @GET("api/reports/weekly")
    suspend fun getWeeklyReport(
        @Query("week_start") weekStart: String,
        @Query("refresh") refresh: Boolean = false
    ): Response<WeeklyReportResponse>

    @GET("api/reports/monthly")
    suspend fun getMonthlyReport(
        @Query("year") year: Int,
        @Query("month") month: Int,
        @Query("refresh") refresh: Boolean = false
    ): Response<MonthlyReportResponse>

    // ===== 일일 종합 =====
    @GET("api/daily-summary/{date}")
    suspend fun getDailySummary(@Path("date") date: String): Response<DailySummaryResponse>

    // ===== CSV 내보내기 (의료진 공유 / 개인 백업용) =====
    @GET("api/csv/files")
    suspend fun listCsvFiles(): Response<CsvFileListResponse>

    // [통합 CSV] 모든 데이터를 하루 1행으로 합친 한 파일 (의료진 공유용)
    @Streaming
    @GET("api/csv/full")
    suspend fun downloadFullCsv(): Response<ResponseBody>

    // [통합 엑셀] 같은 데이터를 .xlsx 로 (서버에 openpyxl 없으면 CSV로 폴백)
    @Streaming
    @GET("api/csv/full.xlsx")
    suspend fun downloadFullXlsx(): Response<ResponseBody>

    @Streaming
    @GET("api/csv/files/{filename}")
    suspend fun downloadCsvFile(@Path("filename") filename: String): Response<ResponseBody>
}
