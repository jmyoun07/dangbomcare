package com.dangbomcare.app.data.api

import com.google.gson.annotations.SerializedName

// ===========================
// 공통
// ===========================
data class ErrorResponse(
    val error: String,
    val message: String
)

// ===========================
// 사용자
// ===========================
data class UserMeResponse(
    val id: String,
    val email: String,
    @SerializedName("patient_number") val patientNumber: String?,
    val name: String?,
    val gender: String?,
    val age: Int?,                                                    // birth_date에서 자동 계산
    @SerializedName("birth_date") val birthDate: String?,             // 새 필드 (yyyy-MM-dd)
    @SerializedName("height_cm") val heightCm: Double?,
    @SerializedName("weight_kg") val weightKg: Double?,
    @SerializedName("diabetes_diagnosed_at") val diabetesDiagnosedAt: String?,
    @SerializedName("glucose_normal_low") val glucoseNormalLow: Int? = null,    // [PDF 2] 평소 공복혈당 범위 하한
    @SerializedName("glucose_normal_high") val glucoseNormalHigh: Int? = null,  // [PDF 2] 평소 공복혈당 범위 상한
    @SerializedName("onboarding_completed") val onboardingCompleted: Boolean,
    // [P1 260727] 저녁 마감·공복혈당 측정·취침 시각의 정본은 서버 user_settings 다.
    //   백엔드 UserResponse 는 예전부터 settings 를 내려주고 있었는데(users.py get_me) 앱 모델에
    //   필드가 없어 Gson 이 통째로 버렸다 → 재로그인 후 로컬(알림·리포트)과 서버(AI 미션)가
    //   서로 다른 시각을 표시. NavGraph postLoginRouting 에서 이 값으로 로컬을 하이드레이션한다.
    val settings: UserSettingsOut? = null,
    val consents: UserConsentsOut? = null                              // 이미 동의했는지 확인 (재로그인 흐름)
)

/**
 * 백엔드 UserSettingsOut (app/schemas.py) 대응.
 *   - 시각 3종은 Pydantic `datetime.time` → JSON 직렬화가 항상 "HH:MM:SS" (예: "19:30:00").
 *   - 백엔드 스키마에서는 모두 non-null 이지만, 구버전 서버·필드 누락 시 Gson 이 null 을 넣으므로
 *     앱에서는 전부 nullable 로 받고 사용처에서 null 이면 로컬 값을 유지한다.
 */
data class UserSettingsOut(
    @SerializedName("glucose_check_time") val glucoseCheckTime: String? = null,      // 공복혈당 측정(아침) 알림
    @SerializedName("dinner_deadline_time") val dinnerDeadlineTime: String? = null,  // 저녁 식사 마감
    val bedtime: String? = null,
    @SerializedName("notif_morning") val notifMorning: Boolean? = null,
    @SerializedName("notif_morning_fallback") val notifMorningFallback: Boolean? = null,
    @SerializedName("notif_dinner_deadline") val notifDinnerDeadline: Boolean? = null,
    @SerializedName("notif_bedtime") val notifBedtime: Boolean? = null,
    // [DEPRECATED] '방해금지 시간'은 260623 피드백에서 폐지 — 하위호환용으로 스키마에만 남아 있음. 앱은 무시.
    @SerializedName("notif_quiet_during_sleep") val notifQuietDuringSleep: Boolean? = null
)

data class UserConsentsOut(
    val terms: Boolean,
    val privacy: Boolean,
    @SerializedName("sensitive_data") val sensitiveData: Boolean,
    // [260726] 개인정보 및 민감정보 제3자 제공 동의 (260723 최종안 기준 필수 4번째 동의).
    //   구버전 서버 응답에는 없으므로 기본값 false — 이 경우 앱이 동의 화면으로 재유도한다.
    @SerializedName("third_party") val thirdParty: Boolean = false,
    @SerializedName("research_use") val researchUse: Boolean,
    // [260728 우노 마케팅 동의] 선택 동의 — 동의하지 않아도 온보딩/기본 서비스 이용에 제한이 없다.
    //   백엔드 UserConsentsOut 는 `_accepted` 접미를 떼는 규칙(terms/privacy/sensitive_data/third_party)이라
    //   기본 키는 "marketing". 다만 구현이 컬럼명 그대로 내려줄 가능성에 대비해 alternate 로 방어.
    //   구버전 서버 응답에는 필드가 없으므로 기본값 false(=수신 거부됨)로 표시.
    @SerializedName(value = "marketing", alternate = ["marketing_accepted"])
    val marketing: Boolean = false
)

data class UserUpdateRequest(
    val name: String? = null,
    val gender: String? = null,
    val age: Int? = null,                                              // legacy — birth_date 보낼 때는 백엔드가 자동 계산
    @SerializedName("birth_date") val birthDate: String? = null,       // 새 필드 (yyyy-MM-dd)
    @SerializedName("height_cm") val heightCm: Double? = null,
    @SerializedName("weight_kg") val weightKg: Double? = null,
    @SerializedName("patient_number") val patientNumber: String? = null,
    @SerializedName("diabetes_diagnosed_at") val diabetesDiagnosedAt: String? = null,
    @SerializedName("glucose_normal_low") val glucoseNormalLow: Int? = null,    // [PDF 2] 평소 공복혈당 범위 하한
    @SerializedName("glucose_normal_high") val glucoseNormalHigh: Int? = null,  // [PDF 2] 평소 공복혈당 범위 상한
    @SerializedName("onboarding_completed") val onboardingCompleted: Boolean? = null
)

data class UserConsentsRequest(
    @SerializedName("terms_accepted") val termsAccepted: Boolean,
    @SerializedName("privacy_accepted") val privacyAccepted: Boolean,
    @SerializedName("sensitive_data_accepted") val sensitiveDataAccepted: Boolean,
    // [260726] 개인정보 및 민감정보 제3자 제공 동의 — 이제 실제로 서버에 저장된다.
    @SerializedName("third_party_accepted") val thirdPartyAccepted: Boolean = false,
    @SerializedName("research_use_accepted") val researchUseAccepted: Boolean = false,
    // [260728 우노 마케팅 동의] 선택 — 온보딩 동의 화면에서 체크 여부를 그대로 보낸다.
    //   체크 안 해도 온보딩은 통과하므로 필수 4종과 달리 라우팅 게이트에는 넣지 않는다.
    //   null = "이 앱 버전은 마케팅 동의를 보내지 않음"(구버전 호환) → 서버가 기존 값을 유지.
    @SerializedName("marketing_accepted") val marketingAccepted: Boolean? = null
)

/**
 * [260728 우노 마케팅 동의] 설정 → 약관/정책 토글로 보내는 철회/재동의 요청 바디.
 *   PATCH api/users/me/consents/marketing  body: {"accepted": true|false}
 */
data class MarketingConsentRequest(
    val accepted: Boolean
)

data class UserSettingsRequest(
    @SerializedName("glucose_check_time") val glucoseCheckTime: String? = null,
    @SerializedName("dinner_deadline_time") val dinnerDeadlineTime: String? = null,
    val bedtime: String? = null,
    @SerializedName("notif_morning") val notifMorning: Boolean? = null,
    @SerializedName("notif_dinner_deadline") val notifDinnerDeadline: Boolean? = null,
    @SerializedName("notif_bedtime") val notifBedtime: Boolean? = null
)

data class FcmTokenRequest(
    @SerializedName("fcm_token") val fcmToken: String
)

/**
 * [260904] 클라이언트 상태 보고 — PUT api/users/me/client-status (백엔드 ClientStatusRequest 와 1:1).
 *   한의원 문의(걸음수 미수신)의 원인을 DB 에서 가려내기 위한 진단값. 앱 버전·배터리 최적화 예외·
 *   Health Connect 권한/가용성·기기. 모르는 값은 null 로 보낸다(백엔드도 전부 optional).
 */
data class ClientStatusRequest(
    @SerializedName("app_version_code") val appVersionCode: Int,
    @SerializedName("app_version_name") val appVersionName: String,
    @SerializedName("battery_opt_ignored") val batteryOptIgnored: Boolean?,
    @SerializedName("shealth_installed") val shealthInstalled: Boolean?,
    @SerializedName("shealth_opt_ignored") val shealthOptIgnored: Boolean?,
    // sdk_direct | hc_ok | hc_partial:n/m | hc_no_permission | hc_update_required | hc_unavailable | error:<type>
    @SerializedName("health_source_status") val healthSourceStatus: String?,
    @SerializedName("device_model") val deviceModel: String?,
    @SerializedName("os_sdk") val osSdk: Int?
)

/**
 * [260904] 강제 업데이트 정책 — GET api/app/version-policy (인증 없음).
 *   minVersionCode > BuildConfig.VERSION_CODE 면 Splash 가 업데이트 화면으로 보낸다.
 *   Gson 은 누락 필드를 0/null 로 채우므로 구버전 서버(필드 없음)여도 0 → 막지 않음.
 */
data class AppVersionPolicy(
    @SerializedName("min_version_code") val minVersionCode: Int = 0,
    @SerializedName("latest_version_code") val latestVersionCode: Int = 0,
    @SerializedName("store_url") val storeUrl: String? = null,
    val message: String? = null
)

// ===========================
// 의료진 목표 (우노 피드백 4·5) — 진료 시 의료진이 설정, 환자는 보기 전용
// ===========================
// [피드백 260714] 매월 자동 공복혈당 범위 재정의 응답
// 기준: 지난 달력 한 달 데이터 (예: 8월 진입 시 7월 1~31일)
// reason: "monthly_review_full"(≥15일) / "monthly_review_partial"(10~14일) /
//         "insufficient_data"(<10일, 기존값 유지) / "already_checked_this_month"
data class GlucoseRangeSuggestion(
    @SerializedName("current_low") val currentLow: Int? = null,
    @SerializedName("current_high") val currentHigh: Int? = null,
    @SerializedName("suggested_low") val suggestedLow: Int? = null,
    @SerializedName("suggested_high") val suggestedHigh: Int? = null,
    @SerializedName("based_on_days") val basedOnDays: Int = 0,
    @SerializedName("based_on_year") val basedOnYear: Int? = null,
    @SerializedName("based_on_month") val basedOnMonth: Int? = null,
    @SerializedName("needs_recalc") val needsRecalc: Boolean = false,
    val reason: String? = null,
)

data class MedicalGoalResponse(
    @SerializedName("target_hba1c") val targetHba1c: Double? = null,            // 당화혈색소 %
    @SerializedName("target_pancreas") val targetPancreas: Double? = null,      // 췌장수치
    @SerializedName("target_fasting_glucose") val targetFastingGlucose: Int? = null,  // 공복혈당 mg/dL
    @SerializedName("target_period") val targetPeriod: String? = null,          // "3개월 뒤" 등
    @SerializedName("lifestyle_goals") val lifestyleGoals: String? = null,      // 서술식 생활습관 목표
    @SerializedName("set_by") val setBy: String? = null,
    @SerializedName("created_at") val createdAt: String? = null,                // 이력 표시용 (설정 시점)
    @SerializedName("updated_at") val updatedAt: String? = null,
    @SerializedName("has_goal") val hasGoal: Boolean = false
)

data class MedicalGoalRequest(
    @SerializedName("target_hba1c") val targetHba1c: Double? = null,
    @SerializedName("target_pancreas") val targetPancreas: Double? = null,
    @SerializedName("target_fasting_glucose") val targetFastingGlucose: Int? = null,
    @SerializedName("target_period") val targetPeriod: String? = null,
    @SerializedName("lifestyle_goals") val lifestyleGoals: String? = null,
    // [피드백 260714] set_by(의료진 이름) 화면 입력 폼에 노출 — 백엔드 schema에서도 필드 열어둠.
    @SerializedName("set_by") val setBy: String? = null
)

// ===========================
// 일일 로그 (공복혈당 + 회고)
// ===========================
data class DailyLogCreateRequest(
    @SerializedName("log_date") val logDate: String,           // YYYY-MM-DD
    @SerializedName("fasting_glucose") val fastingGlucose: Int,
    @SerializedName("fasting_glucose_time") val fastingGlucoseTime: String? = null,
    val alcohol: Boolean? = null,
    @SerializedName("dinner_before_7pm") val dinnerBefore7pm: Boolean? = null,
    @SerializedName("no_food_after_dinner") val noFoodAfterDinner: Boolean? = null,
    // [260623] 전날 회고 입력 4개 추가
    @SerializedName("no_food_after_dinner_hours") val noFoodAfterDinnerHours: String? = null,
    @SerializedName("dinner_type") val dinnerType: List<String>? = null,
    // [v1.1.3] 저녁 유형 "기타" 자유 입력 텍스트 (dinnerType에 "other" 포함 시 사용)
    @SerializedName("dinner_type_other") val dinnerTypeOther: String? = null,
    val digestion: String? = null,
    @SerializedName("post_meal_exercise") val postMealExercise: String? = null,
    @SerializedName("input_method") val inputMethod: String = "morning"
)

data class ScoreBreakdown(
    val glucose: Int? = null,
    val steps: Int? = null,
    @SerializedName("steps_post_meal_bonus") val stepsPostMealBonus: Int? = null,
    val sleep: Int? = null,
    @SerializedName("dinner_before_7pm") val dinnerBefore7pm: Int? = null,
    val alcohol: Int? = null,
    @SerializedName("no_food_after_dinner") val noFoodAfterDinner: Int? = null,
    val stress: Int? = null,
    // [260623] 점수 재배분 — 신규 3항목 (소화는 2.5점 가능 → Double)
    @SerializedName("dinner_type") val dinnerType: Int? = null,
    val digestion: Double? = null,
    @SerializedName("post_meal_exercise") val postMealExercise: Int? = null
)

data class MissionInfo(
    val type: String? = null,
    val text: String? = null,
    val target: Map<String, Any>? = null,
    val progress: Double = 0.0,
    val completed: Boolean = false,
    val tip: String? = null
)

data class StreakInfo(
    @SerializedName("weekly_completed_days") val weeklyCompletedDays: Int = 0,
    @SerializedName("weekly_total_days") val weeklyTotalDays: Int = 7,
    @SerializedName("monthly_completed_days") val monthlyCompletedDays: Int = 0
)

data class AIFeedbackToday(
    // [버그 수정] 두 응답 형태를 모두 수용:
    //   - 점수 엔드포인트(/scores/today/feedback) → "feedback_date" + "daily_message"
    //   - 일일 로그 엔드포인트(/daily-logs/{date}) → "feedback_date" 없음 + "message"
    //   기록화면은 daily-logs 응답을 쓰므로 message 가 daily_message 로 안 들어오면 피드백이 숨겨졌었음.
    @SerializedName("feedback_date") val feedbackDate: String? = null,
    @SerializedName(value = "daily_message", alternate = ["message"]) val dailyMessage: String?,
    @SerializedName("influencing_factors") val influencingFactors: List<String>?,
    val encouragement: String?,
    val mission: MissionInfo?,
    val streak: StreakInfo? = null,
    // [v1.1.4-rate] AI 재생성 카운터 — 오늘=3회 / 과거=2회 하드리밋. 서버 구버전 대응 기본 0/false.
    @SerializedName("ai_regen_remaining") val aiRegenRemaining: Int = 0,
    @SerializedName("ai_regen_locked") val aiRegenLocked: Boolean = false
)

data class DailyLogResponse(
    val log: DailyLogInner?,
    val score: ScoreInner?,
    @SerializedName("ai_feedback") val aiFeedback: AIFeedbackToday?,
    // [v1.1.4-rate] AI 재생성 카운터 — POST/GET /daily-logs 응답 최상위 필드. 서버 구버전 대응 기본 0/false.
    @SerializedName("ai_regen_remaining") val aiRegenRemaining: Int = 0,
    @SerializedName("ai_regen_locked") val aiRegenLocked: Boolean = false
)

data class DailyLogInner(
    val id: String?,
    @SerializedName("log_date") val logDate: String,
    @SerializedName("fasting_glucose") val fastingGlucose: Int?,
    val alcohol: Boolean?,
    @SerializedName("dinner_before_7pm") val dinnerBefore7pm: Boolean?,
    @SerializedName("no_food_after_dinner") val noFoodAfterDinner: Boolean?,
    // [260623] 신규 회고 4항목
    @SerializedName("no_food_after_dinner_hours") val noFoodAfterDinnerHours: String? = null,
    @SerializedName("dinner_type") val dinnerType: List<String>? = null,
    // [v1.1.3] 저녁 유형 "기타" 자유 입력 텍스트
    @SerializedName("dinner_type_other") val dinnerTypeOther: String? = null,
    val digestion: String? = null,
    @SerializedName("post_meal_exercise") val postMealExercise: String? = null
)

data class ScoreInner(
    // ⚠️ 백엔드 ScoreOut 스키마는 'total' (not 'total_score') — 이전 매핑 오류 수정
    // [260623] 소화 2.5점 등 소수점 가능 → Double
    @SerializedName(value = "total", alternate = ["total_score"]) val totalScore: Double?,
    val label: String?,
    @SerializedName(value = "change_from_yesterday") val changeFromYesterday: Double? = null,
    val breakdown: ScoreBreakdown?
)

// ===========================
// 점수
// ===========================
data class ScoreTodayResponse(
    @SerializedName("score_date") val scoreDate: String,
    @SerializedName("total_score") val totalScore: Double,
    val label: String,
    val breakdown: ScoreBreakdown,
    @SerializedName("yesterday_score") val yesterdayScore: Double?,
    @SerializedName("score_change_arrow") val scoreChangeArrow: String?,
    @SerializedName("weekly_avg") val weeklyAvg: Double?,
    @SerializedName("has_missing_data") val hasMissingData: Boolean = false,
    @SerializedName("encouragement_message") val encouragementMessage: String?
)

data class ScoreListItem(
    @SerializedName("score_date") val scoreDate: String,
    @SerializedName("total_score") val totalScore: Double?,
    val label: String?,
    // [260623 피드백] 캘린더 셀에 공복혈당 수치 표시용 (색은 점수 기반 유지)
    @SerializedName("fasting_glucose") val fastingGlucose: Int? = null
)

// ===========================
// 워치
// ===========================
data class WatchDataRequest(
    @SerializedName("log_date") val logDate: String,
    // ⚠️ 코덱스 P0: 0보 ≠ 미측정. 워치 미착용·권한 없음·읽기 실패는 null이어야 함.
    val steps: Int? = null,
    @SerializedName("steps_hourly") val stepsHourly: List<Int>? = null,
    @SerializedName("sleep_total_minutes") val sleepTotalMinutes: Int? = null,
    @SerializedName("sleep_deep_minutes") val sleepDeepMinutes: Int? = null,
    @SerializedName("sleep_light_minutes") val sleepLightMinutes: Int? = null,
    @SerializedName("sleep_rem_minutes") val sleepRemMinutes: Int? = null,
    @SerializedName("sleep_awake_minutes") val sleepAwakeMinutes: Int? = null,
    @SerializedName("sleep_start_at") val sleepStartAt: String? = null,
    @SerializedName("sleep_end_at") val sleepEndAt: String? = null,
    @SerializedName("avg_heart_rate") val avgHeartRate: Int? = null,
    @SerializedName("min_heart_rate") val minHeartRate: Int? = null,
    @SerializedName("max_heart_rate") val maxHeartRate: Int? = null,
    @SerializedName("heart_rate_samples") val heartRateSamples: List<HeartRateSample>? = null,
    @SerializedName("spo2_avg") val spo2Avg: Double? = null,
    @SerializedName("spo2_min") val spo2Min: Double? = null
)

data class HeartRateSample(
    val time: String,
    val bpm: Int
)

// ⚠️ 백엔드 WatchDataOut 은 nested 구조 (sleep / heart_rate / spo2 가 각각 dict)
// 이전 flat 구조는 매핑이 깨져 있었음 — 코덱스 검토 P1 수정.
data class WatchDataResponse(
    @SerializedName("log_date") val logDate: String,
    val steps: Int?,
    val sleep: WatchSleep? = null,
    @SerializedName("heart_rate") val heartRate: WatchHeartRate? = null,
    val spo2: WatchSpo2? = null,
    @SerializedName("sync_status") val syncStatus: String?,
    @SerializedName("last_synced_at") val lastSyncedAt: String?
)

data class WatchSleep(
    @SerializedName("total_minutes") val totalMinutes: Int?,
    @SerializedName("deep_minutes") val deepMinutes: Int?,
    @SerializedName("light_minutes") val lightMinutes: Int?,
    @SerializedName("rem_minutes") val remMinutes: Int?,
    @SerializedName("awake_minutes") val awakeMinutes: Int?,
    @SerializedName("start_at") val startAt: String? = null,
    @SerializedName("end_at") val endAt: String? = null,
    @SerializedName("labels_senior") val labelsSenior: Map<String, String>? = null
)

data class WatchHeartRate(
    val avg: Int? = null,
    val min: Int? = null,
    val max: Int? = null
)

data class WatchSpo2(
    val avg: Double? = null,
    val min: Double? = null
)

// 하위호환 — SummaryActivity 등에서 사용 중
data class SleepStages(
    @SerializedName("deep_minutes") val deepMinutes: Int?,
    @SerializedName("light_minutes") val lightMinutes: Int?,
    @SerializedName("rem_minutes") val remMinutes: Int?,
    @SerializedName("awake_minutes") val awakeMinutes: Int?,
    @SerializedName("labels_senior") val labelsSenior: Map<String, String>? = null
)

// ===========================
// 일일 종합
// ===========================
data class DailySummaryResponse(
    val date: String,
    val score: SummaryScore?,
    val glucose: SummaryGlucose?,
    val activity: SummaryActivity?,
    val reflection: SummaryReflection?
)

data class SummaryScore(
    val total: Double?,   // [260623] 소화 2.5점 → 총점도 소수 가능
    val label: String?,
    val breakdown: ScoreBreakdown?
)

data class SummaryGlucose(
    val value: Int?,
    val status: String?,
    @SerializedName("target_range") val targetRange: List<Int>?,
    @SerializedName("input_time") val inputTime: String?
)

data class SummaryActivity(
    val steps: Int?,
    @SerializedName("sleep_hours") val sleepHours: Double?,
    @SerializedName("sleep_stages") val sleepStages: SleepStages?,
    val stress: String?
)

data class SummaryReflection(
    val alcohol: Boolean?,
    @SerializedName("dinner_before_7pm") val dinnerBefore7pm: Boolean?,
    @SerializedName("no_food_after_dinner") val noFoodAfterDinner: Boolean?,
    // [260623] 신규 회고 4항목
    @SerializedName("no_food_after_dinner_hours") val noFoodAfterDinnerHours: String? = null,
    @SerializedName("dinner_type") val dinnerType: List<String>? = null,
    // [v1.1.3] 저녁 유형 "기타" 자유 입력 텍스트
    @SerializedName("dinner_type_other") val dinnerTypeOther: String? = null,
    val digestion: String? = null,
    @SerializedName("post_meal_exercise") val postMealExercise: String? = null
)

// ===========================
// 리포트
// ===========================
// [버그픽스] 백엔드 top_risk_factors 는 문자열이 아니라 객체 배열(factor/label/reason/...).
//   앱이 List<String>으로 받아서, 데이터가 쌓여 위험요인이 생기면 역직렬화 실패 → 리포트 전체가 안 뜨던 버그.
data class RiskFactor(
    val factor: String? = null,
    val label: String? = null,
    @SerializedName("avg_score") val avgScore: Double? = null,
    @SerializedName("max_score") val maxScore: Int? = null,
    @SerializedName("avg_loss_points") val avgLossPoints: Double? = null,
    @SerializedName("low_days") val lowDays: Int? = null,
    @SerializedName("observed_days") val observedDays: Int? = null,
    val reason: String? = null
)

data class WeeklyReportResponse(
    @SerializedName("week_start_date") val weekStartDate: String,
    @SerializedName("week_end_date") val weekEndDate: String,
    @SerializedName("avg_score") val avgScore: Double?,
    @SerializedName("prev_week_avg_score") val prevWeekAvgScore: Double?,
    val metrics: WeeklyMetrics?,
    @SerializedName("daily_scores") val dailyScores: List<Double>?,   // [260623] 소화 2.5 → 일별 총점 소수 가능 (List<Int>면 응답 전체 파싱 실패)
    @SerializedName("top_risk_factors") val topRiskFactors: List<RiskFactor>?,
    @SerializedName("correlation_insights") val correlationInsights: List<String>?,
    @SerializedName("gpt_summary") val gptSummary: String?,
    @SerializedName("recorded_days") val recordedDays: Int? = null,   // [우노] 이번 주 기록 일수
    @SerializedName("can_refresh") val canRefresh: Boolean = false,    // [우노] '피드백 갱신하기' 버튼 노출
    @SerializedName("milestones") val milestones: List<MilestoneInfo>? = null,
    @SerializedName("glucose_calendar") val glucoseCalendar: GlucoseCalendar? = null   // [260623] 공복혈당 달력
)

data class MilestoneInfo(
    val type: String,
    val title: String,
    val message: String,
    val priority: Int = 1
)

data class WeeklyMetrics(
    @SerializedName("avg_glucose") val avgGlucose: Double?,
    @SerializedName("avg_steps") val avgSteps: Int?,
    @SerializedName("avg_sleep_hours") val avgSleepHours: Double?,
    @SerializedName("alcohol_days") val alcoholDays: Int?,
    @SerializedName("dinner_before_7pm_days") val dinnerBefore7pmDays: Int?,
    @SerializedName("late_eating_days") val lateEatingDays: Int?,
    // [v1.0] 스트레스 적정 일수 — 워치 HRV에서 morning_status == "적정"인 날 합계
    @SerializedName("stress_relaxed_days") val stressRelaxedDays: Int? = null,
    // [피드백 260719 E] 신규 3항목 지표 (저녁유형·자기 전 소화·식후 운동)
    @SerializedName("healthy_dinner_days") val healthyDinnerDays: Int? = null,
    @SerializedName("comfortable_digestion_days") val comfortableDigestionDays: Int? = null,
    @SerializedName("post_meal_exercise_days") val postMealExerciseDays: Int? = null,
)

data class MonthlyReportResponse(
    val year: Int,
    val month: Int,
    @SerializedName("avg_score") val avgScore: Double?,
    @SerializedName("can_refresh") val canRefresh: Boolean = false,   // [우노] '피드백 갱신하기' 버튼 노출
    @SerializedName("prev_month_avg_score") val prevMonthAvgScore: Double?,
    @SerializedName("weekly_scores") val weeklyScores: List<Double>?,   // [260623] 소수 안전 (주차 평균이 소수일 수 있음)
    @SerializedName("best_day") val bestDay: DayHighlight?,
    @SerializedName("worst_day") val worstDay: DayHighlight?,
    @SerializedName("glucose_distribution") val glucoseDistribution: GlucoseDistribution?,
    @SerializedName("goal_achievements") val goalAchievements: GoalAchievements?,
    val metrics: MonthlyMetrics?,
    @SerializedName("top_risk_factors") val topRiskFactors: List<RiskFactor>?,
    @SerializedName("correlation_insights") val correlationInsights: List<String>?,
    @SerializedName("gpt_summary") val gptSummary: String?,
    @SerializedName("glucose_calendar") val glucoseCalendar: GlucoseCalendar? = null   // [260623] 공복혈당 달력
)

data class DayHighlight(
    val date: String,
    val score: Double   // [260623] best/worst day 점수도 소수 가능
)

data class GlucoseDistribution(
    val low: Int = 0,
    val normal: Int = 0,
    val impaired: Int = 0,
    @SerializedName("diabetic_managed") val diabeticManaged: Int = 0,
    @SerializedName("diabetic_high") val diabeticHigh: Int = 0
)

data class GoalRate(
    val achieved: Int,
    val total: Int,
    val rate: Int
)

data class GoalAchievements(
    @SerializedName("dinner_before_7pm") val dinnerBefore7pm: GoalRate?,
    @SerializedName("no_alcohol") val noAlcohol: GoalRate?,
    @SerializedName("no_late_eating") val noLateEating: GoalRate?
)

data class MonthlyMetrics(
    @SerializedName("avg_glucose") val avgGlucose: Double?,
    @SerializedName("avg_steps") val avgSteps: Int?,
    @SerializedName("avg_sleep_hours") val avgSleepHours: Double?,
    @SerializedName("alcohol_days") val alcoholDays: Int?,
    @SerializedName("dinner_before_7pm_days") val dinnerBefore7pmDays: Int?,
    @SerializedName("late_eating_days") val lateEatingDays: Int?,
    // [v1.0] 스트레스 적정 일수 (HRV morning_status == "적정")
    @SerializedName("stress_relaxed_days") val stressRelaxedDays: Int? = null,
    // [피드백 260719 E] 신규 3항목 지표
    @SerializedName("healthy_dinner_days") val healthyDinnerDays: Int? = null,
    @SerializedName("comfortable_digestion_days") val comfortableDigestionDays: Int? = null,
    @SerializedName("post_meal_exercise_days") val postMealExerciseDays: Int? = null,
    @SerializedName("days_with_data") val daysWithData: Int?,
    @SerializedName("total_days") val totalDays: Int?
)

// ===========================
// [260623] 공복혈당 달력 (리포트 주간/월간)
// ===========================
data class GlucoseCalendar(
    // Gson은 Kotlin 기본값을 보장 안 함(누락 시 null) → 컬렉션은 nullable + 사용처에서 orEmpty()
    // [260728 확정①] usual_range == null = '평소 공복혈당 범위 미설정'.
    //   이때 in_range_count 를 믿으면 안 된다(구버전 백엔드는 전체 일수를 여기 담아 보낸다).
    //   → 화면은 '범위 안'을 단정하지 않고 recorded_days 만 쓴다 (ReportsScreen.hasUsualRange()).
    @SerializedName("usual_range") val usualRange: UsualRange? = null,
    val days: List<GlucoseDay>? = null,
    // [260705] 높인/낮춘 요인 분리 + 상태별 일수 (섞인 common_reasons 폐기)
    @SerializedName("high_reasons") val highReasons: List<CommonReason>? = null,
    @SerializedName("low_reasons") val lowReasons: List<CommonReason>? = null,
    // [260705 R3] 워치 데이터 비교 분석 문장 (높/낮았던 날 vs 범위 안 날 — 걸음·수면·스트레스)
    @SerializedName("high_analysis") val highAnalysis: String? = null,
    @SerializedName("low_analysis") val lowAnalysis: String? = null,
    @SerializedName("in_range_count") val inRangeCount: Int = 0,
    @SerializedName("high_count") val highCount: Int = 0,
    @SerializedName("low_count") val lowCount: Int = 0,
    @SerializedName("out_of_range_count") val outOfRangeCount: Int = 0,
    @SerializedName("recorded_days") val recordedDays: Int = 0
)

data class UsualRange(val low: Int, val high: Int)

data class GlucoseDay(
    val date: String,                 // ISO yyyy-MM-dd
    val glucose: Int,
    // high / low / in_range / unknown(평소 범위 미설정 — 판정 불가)
    // [260728] enum·sealed 로 좁히지 않는다. 백엔드가 새 값(unknown 등)을 추가하면 역직렬화가 깨져
    //   리포트 전체가 안 뜨는 사고가 이 프로젝트에서 실제로 있었다(top_risk_factors 건).
    //   nullable + 기본값으로 두고, 화면은 아는 값만 분기하고 나머지는 중립 처리한다.
    val status: String? = null,
    val factors: List<String>? = null
)

data class CommonReason(val label: String, val count: Int)

// ===========================
// Supabase 인증
// ===========================
data class SupabaseSignInRequest(
    val email: String,
    val password: String
)

/**
 * Supabase Auth 응답 — 두 가지 형식 모두 지원
 *
 * 1) 이메일 인증 OFF (또는 로그인 성공) — 세션 포함:
 *    { access_token, refresh_token, expires_in, user: {id, email} }
 *
 * 2) 이메일 인증 ON (회원가입 직후) — User 객체가 top-level:
 *    { id, email, confirmation_sent_at, ... }
 *    이 경우 access_token, refresh_token, user 필드는 없음
 */
data class SupabaseAuthResponse(
    @SerializedName("access_token") val accessToken: String? = null,
    @SerializedName("refresh_token") val refreshToken: String? = null,
    @SerializedName("expires_in") val expiresIn: Int? = null,
    val user: SupabaseUser? = null,
    // 이메일 인증 대기 응답: User 객체가 top-level로 옴
    val id: String? = null,
    val email: String? = null,
    @SerializedName("confirmation_sent_at") val confirmationSentAt: String? = null,
    // [v1.0] 중복 가입 감지 — Supabase는 보안상 이미 가입된 메일에도 200을 반환하지만 identities를 빈 배열로 줌
    // top-level로 오는 경우 (이메일 인증 대기 응답)와 user 내부로 오는 경우 둘 다 처리하기 위해 양쪽에 둠
    val identities: List<SupabaseIdentity>? = null,
    @SerializedName("email_confirmed_at") val emailConfirmedAt: String? = null
) {
    /** user.id (wrapped) 또는 id (top-level) 중 존재하는 값 반환 */
    val userId: String? get() = user?.id ?: id
    /** 세션이 발급되었는지 (access_token 있는지) */
    val hasSession: Boolean get() = !accessToken.isNullOrBlank()
    /** [v1.0] 중복 가입 감지: identities가 비어있으면 (top-level 또는 user 내부) 이미 가입된 이메일 */
    val isExistingUser: Boolean
        get() {
            val topLevelEmpty = identities?.isEmpty() == true
            val nestedEmpty = user?.identities?.isEmpty() == true
            return topLevelEmpty || nestedEmpty
        }
}

data class SupabaseUser(
    val id: String,
    val email: String?,
    // [v1.0] 중복 가입 감지 — 기존 사용자에게 signUp 호출 시 Supabase는 보안상 200을 반환하지만 identities를 빈 배열로 돌려줌
    val identities: List<SupabaseIdentity>? = null,
    // 이메일 인증 완료 시각 (null이면 미인증)
    @SerializedName("email_confirmed_at") val emailConfirmedAt: String? = null
)

data class SupabaseIdentity(
    val id: String? = null,
    @SerializedName("identity_id") val identityId: String? = null,
    val provider: String? = null
)

data class AutoConfirmRequest(
    val email: String
)

// [v1.0] 이메일 중복 체크 응답
data class CheckEmailResponse(
    val exists: Boolean,
    val confirmed: Boolean
)

data class SupabaseError(
    @SerializedName("error_description") val errorDescription: String?,
    val msg: String?,
    val error: String?
)

// ===========================
// CSV 내보내기
// ===========================
data class CsvFileListResponse(
    @SerializedName("user_id") val userId: String,
    val count: Int,
    val files: List<CsvFileItem>
)

data class CsvFileItem(
    val name: String,
    @SerializedName("size_bytes") val sizeBytes: Long,
    @SerializedName("modified_at") val modifiedAt: String?,
    val type: String,     // watch / log / score / stress / ai
    val month: String?,   // 2026-05
)
