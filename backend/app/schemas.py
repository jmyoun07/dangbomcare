"""당봄케어 Pydantic v2 입출력 스키마 (v1.9)"""
from __future__ import annotations

import uuid
from datetime import date, datetime, time
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, EmailStr, Field, field_validator, model_validator


# ===========================================
# Auth
# ===========================================
class SignupRequest(BaseModel):
    email: EmailStr
    password: str = Field(min_length=8)
    name: str | None = None


class LoginRequest(BaseModel):
    email: EmailStr
    password: str


class KakaoLoginRequest(BaseModel):
    kakao_access_token: str


class RefreshRequest(BaseModel):
    refresh_token: str


class AuthResponse(BaseModel):
    user_id: uuid.UUID
    email: str
    access_token: str
    refresh_token: str | None = None
    kakao_profile: dict[str, Any] | None = None


# ===========================================
# Users
# ===========================================
class UserSettingsOut(BaseModel):
    glucose_check_time: time
    dinner_deadline_time: time
    bedtime: time
    notif_morning: bool
    notif_morning_fallback: bool
    notif_dinner_deadline: bool
    notif_bedtime: bool
    # [DEPRECATED · 260714 정리 P2-11] '방해금지 시간' 기능 260623 피드백에서 폐지 (S2).
    #   DB 컬럼과 스키마 필드는 하위호환용으로 유지(구버전 앱이 여전히 참조 가능).
    #   신 앱은 무시. 향후 클라 마이그레이션 완료되면 제거 예정.
    notif_quiet_during_sleep: bool

    model_config = ConfigDict(from_attributes=True)


class UserConsentsOut(BaseModel):
    terms: bool
    privacy: bool
    sensitive_data: bool
    # [260726] 개인정보 및 민감정보 제3자 제공 동의 — 260723 최종안 기준 필수 4번째 동의.
    third_party: bool = False
    # [260728] 마케팅 활용 및 광고성 정보 수신 동의 — **선택** 항목.
    #   앱의 필수 동의 게이트(hasConsents) 판정에 절대 포함시키지 말 것.
    #   설정 → 약관/정책 토글의 현재 상태 표시에 사용한다.
    marketing: bool = False
    research_use: bool


# [260714] 매월 공복혈당 범위 자동 재정의 응답 스키마
# [피드백 260714·업데이트] 롤링 30일 → '지난 달력 한 달(예: 8월 진입 시 7월 데이터)'로 기준 변경.
#   임계 7일 → 10일, 최소 폭 10 → 15로 조정.
class GlucoseRangeSuggestion(BaseModel):
    current_low: int | None = None
    current_high: int | None = None
    suggested_low: int | None = None    # 지난 달 IQR(P25) — <10일이면 current 그대로
    suggested_high: int | None = None   # 지난 달 IQR(P75) — <10일이면 current 그대로
    based_on_days: int = 0              # 지난 달력 한 달 중 실제 공복혈당 기록 일수
    based_on_year: int | None = None    # 지난 달의 연도 (예: 2026)
    based_on_month: int | None = None   # 지난 달의 월 (1~12) — 앱이 "7월 한 달 동안" 표기용
    needs_recalc: bool = False          # 이번 달 아직 팝업 안 봤으면 true
    reason: str | None = None           # "monthly_review_full"(≥15일) / "monthly_review_partial"(10~14일) /
                                        # "insufficient_data"(<10일, 기존값 유지) / "already_checked_this_month"


class UserResponse(BaseModel):
    id: uuid.UUID
    email: str
    patient_number: str | None = None
    phone_number: str | None = None  # [260824] 온보딩 필수 항목(테스트 서버)
    name: str | None = None
    gender: str | None = None
    age: int | None = None  # birth_date 기반 자동 계산
    birth_date: date | None = None  # 새 흐름 (yyyy-mm-dd)
    height_cm: float | None = None
    weight_kg: float | None = None
    diabetes_diagnosed_at: date | None = None
    glucose_normal_low: int | None = None   # [PDF 2] 평소 공복혈당 범위 하한
    glucose_normal_high: int | None = None  # [PDF 2] 평소 공복혈당 범위 상한 (채점 기준선)
    onboarding_completed: bool
    settings: UserSettingsOut | None = None
    consents: UserConsentsOut | None = None
    # [260831] 의료진이 환자에게 공유하는 메모(관리자웹 admin_patient_flags.shared_memo).
    #   내부 전용 메모(memo)와는 별개 — 이 값만 앱 홈 화면에 노출된다.
    doctor_message: str | None = None
    doctor_message_at: datetime | None = None

    model_config = ConfigDict(from_attributes=True)


class UserUpdateRequest(BaseModel):
    name: str | None = None
    gender: Literal["M", "F"] | None = None
    age: int | None = Field(default=None, ge=1, lt=150)  # legacy
    birth_date: date | None = None  # 새 흐름 — 이걸 받으면 age 자동 계산
    height_cm: float | None = Field(default=None, ge=100, le=250)
    weight_kg: float | None = Field(default=None, ge=20, le=200)
    diabetes_diagnosed_at: date | None = None
    patient_number: str | None = None
    # [260824] 온보딩 필수 항목(테스트 서버) — 앱 ACCOUNT_INFO 화면에서 숫자만 전송(01012345678).
    #   형식만 검증하고 "필수" 강제는 클라이언트 쪽 UX 게이트에 맡긴다(이름/성별/생년월일과 동일 패턴 —
    #   이 엔드포인트는 온보딩 여러 단계가 공유해서 쓰므로, 여기서 항상 필수로 걸면 다른 단계 저장이 막힌다).
    phone_number: str | None = None
    onboarding_completed: bool | None = None
    # [PDF 2 — 개인 공복혈당 범위] 선택 입력. 둘 다 보내거나 둘 다 비우거나(=해제).
    glucose_normal_low: int | None = Field(default=None, ge=40, le=400)
    glucose_normal_high: int | None = Field(default=None, ge=40, le=400)

    @field_validator("phone_number")
    @classmethod
    def _check_phone_format(cls, v: str | None) -> str | None:
        if v is None:
            return v
        v = v.strip()
        if v == "":
            return None
        import re as _re
        if not _re.fullmatch(r"01[0-9]\d{7,8}", v):
            raise ValueError("휴대전화번호 형식이 올바르지 않아요 (예: 01012345678)")
        return v

    @model_validator(mode="after")
    def _check_glucose_range(self) -> "UserUpdateRequest":
        lo, hi = self.glucose_normal_low, self.glucose_normal_high
        if (lo is None) != (hi is None):
            raise ValueError("공복혈당 범위는 상·하한을 모두 입력하거나 모두 비워주세요")
        if lo is not None and hi is not None and lo > hi:
            raise ValueError("공복혈당 범위 하한은 상한보다 클 수 없어요")
        return self


class UserSettingsUpdateRequest(BaseModel):
    glucose_check_time: time | None = None
    dinner_deadline_time: time | None = None
    bedtime: time | None = None
    notif_morning: bool | None = None
    notif_morning_fallback: bool | None = None
    notif_dinner_deadline: bool | None = None
    notif_bedtime: bool | None = None
    notif_quiet_during_sleep: bool | None = None


class ConsentCreateRequest(BaseModel):
    terms_accepted: bool
    privacy_accepted: bool
    sensitive_data_accepted: bool
    # [260726] default False → None 으로 변경. 이 엔드포인트는 기존 행을 갱신(부분 갱신)하기도 하는데
    #   default False + 무조건 대입 조합이면, 필드를 안 보내는 구버전 앱 요청 한 번에
    #   이미 받아둔 동의(True)가 조용히 False 로 강등됐다. None = "미전달 → 기존 값 유지".
    #   (users.py create_consents 에서 None 처리)
    third_party_accepted: bool | None = None
    # [260728] 마케팅 동의(선택). None = 미전달 → 기존 값 유지 (구버전 앱은 이 필드를 보내지 않는다).
    #   선택 항목이므로 False(미체크)로 와도 온보딩은 정상 통과한다.
    marketing_accepted: bool | None = None
    research_use_accepted: bool | None = None


# [260728] 마케팅 동의 철회/재동의 전용 (설정 → 약관/정책 토글).
#   필수 동의 4개는 이 요청으로 절대 변경되지 않는다 — 바디에 필드 자체가 없다.
class MarketingConsentUpdateRequest(BaseModel):
    accepted: bool


class MarketingConsentOut(BaseModel):
    ok: bool = True
    marketing: bool                            # 반영된 현재 상태
    marketing_accepted_at: datetime | None = None   # 마지막 동의 시각
    marketing_revoked_at: datetime | None = None    # 마지막 철회 시각


class FcmTokenRequest(BaseModel):
    fcm_token: str


# [260904] 클라이언트 상태 보고 — 앱이 포그라운드 진입 시 PUT /api/users/me/client-status.
#   전부 optional: 앱이 값을 모르는 항목(예: 삼성헬스 미설치)은 null 로 보내고 그대로 저장.
#   어떤 조합이 와도 422 로 거부하지 않는다(진단용 best-effort 보고라 실패시키면 의미 없음).
class ClientStatusRequest(BaseModel):
    app_version_code: int | None = Field(default=None, ge=0)
    app_version_name: str | None = Field(default=None, max_length=32)
    battery_opt_ignored: bool | None = None
    shealth_installed: bool | None = None
    shealth_opt_ignored: bool | None = None
    health_source_status: str | None = Field(default=None, max_length=64)
    device_model: str | None = Field(default=None, max_length=128)
    os_sdk: int | None = Field(default=None, ge=0, le=999)


# [260904] 강제 업데이트 정책 — GET /api/app/version-policy (인증 없음)
class AppVersionPolicyOut(BaseModel):
    min_version_code: int      # 이 미만이면 앱이 업데이트 화면에서 멈춤 (0 = 강제 없음)
    latest_version_code: int   # 안내용
    store_url: str
    message: str


# ===========================================
# Medical Goals (우노 피드백 4 — 의료진 목표)
# ===========================================
class MedicalGoalOut(BaseModel):
    target_hba1c: float | None = None
    target_pancreas: float | None = None
    target_fasting_glucose: int | None = None
    target_period: str | None = None
    lifestyle_goals: str | None = None
    set_by: str | None = None
    created_at: datetime | None = None   # 이력 표시용 — 이 목표를 설정한 시점
    updated_at: datetime | None = None
    has_goal: bool = False  # 목표가 하나라도 설정됐는지 (홈 카드 표시 여부)

    model_config = ConfigDict(from_attributes=True)


class MedicalGoalUpdateRequest(BaseModel):
    # [피드백 260714] 의료진 이름(set_by) 입력 폼에 추가.
    #   과거엔 폼에서 안 받아 제외했으나, 이제 앱 화면에 이름 입력칸이 생기면서 함께 저장.
    target_hba1c: float | None = Field(default=None, ge=3, le=20)        # 당화혈색소 %
    target_pancreas: float | None = Field(default=None, ge=0, le=10000)  # 췌장수치 (단위 다양 → 넓게)
    target_fasting_glucose: int | None = Field(default=None, ge=40, le=400)  # 공복혈당 mg/dL
    target_period: str | None = Field(default=None, max_length=100)      # ISO 날짜(yyyy-MM-dd) — 캘린더 선택
    lifestyle_goals: str | None = Field(default=None, max_length=2000)   # 서술식 생활습관 목표
    set_by: str | None = Field(default=None, max_length=100)             # 입력 의료진 이름 (선택)


# ===========================================
# Daily Logs (핵심)
# ===========================================
class DailyLogCreate(BaseModel):
    log_date: date
    fasting_glucose: int = Field(ge=10, le=600)
    fasting_glucose_time: datetime | None = None
    # 코덱스 P1: 프론트는 회고를 Boolean? 으로 보냄 (미선택 가능). DB/점수/AI 모두 None 처리됨.
    alcohol: bool | None = None
    dinner_before_7pm: bool | None = None
    no_food_after_dinner: bool | None = None
    # [260623 피드백] 전날 회고 입력 4개 추가
    no_food_after_dinner_hours: Literal["1h_under", "1to2h", "2to3h", "3to4h", "4h_over"] | None = None
    # [260706 Codex] 리스트 원소도 enum 검증 — 임의 문자열이 DB에 저장되던 구멍 차단
    # [260720 개편] 저녁 유형 옵션: flour(밀가루) 제거 → other(기타) 추가. 자유텍스트는 dinner_type_other에 저장.
    #   home_korean(집밥 한식), light(간단식), eating_out(외식/배달), meat(고기 위주), other(기타·자유입력).
    #   점수: home_korean or light만 5점, 그 외 유형(eating_out/meat) 하나라도 포함 시 0점. other는 채점에서 제외.
    #   ※ legacy: 과거 사용자 DB에 "flour" 값이 남아있을 수 있음. 신규 create 요청에서는 Literal 검증으로 거부되며,
    #     기존 저장분 렌더링용 매핑은 앱(RecordsScreen/DailySummaryScreen)과 백엔드(_DINNER_TYPE_KO)에 별도 유지.
    dinner_type: list[Literal["home_korean", "light", "eating_out", "meat", "other"]] | None = None
    dinner_type_other: str | None = Field(default=None, max_length=100)  # dinner_type에 'other' 포함 시 자유입력 텍스트
    digestion: Literal["hungry", "partial", "full"] | None = None
    post_meal_exercise: Literal["none", "10to30", "30to60", "60plus"] | None = None
    input_method: Literal["morning", "fallback", "manual"] = "morning"

    # [v1.0 fix - Codex 2차] log_date 미래 차단.
    #   이전엔 상한 없어 2099-12-31 같은 비현실 입력으로 DB 저장 + 점수/CSV 생성됨.
    #   KST 기준 오늘까지 허용 (시연/입력 지연 1~2일 여유는 _can_edit에서 별도 처리).
    @field_validator("log_date")
    @classmethod
    def _log_date_not_future(cls, v: date) -> date:
        from datetime import datetime as _dt, timedelta as _td, timezone as _tz
        kst_today = _dt.now(_tz(_td(hours=9))).date()
        if v > kst_today:
            raise ValueError("log_date는 오늘 이후 날짜로 설정할 수 없어요.")
        return v


class DailyLogOut(BaseModel):
    log_date: date
    fasting_glucose: int | None = None
    alcohol: bool | None = None
    dinner_before_7pm: bool | None = None
    no_food_after_dinner: bool | None = None
    # [260623] 회고 신규 4항목
    no_food_after_dinner_hours: str | None = None
    dinner_type: list[str] | None = None
    dinner_type_other: str | None = None  # [260720] '기타' 선택 시 자유입력 텍스트
    digestion: str | None = None
    post_meal_exercise: str | None = None
    input_method: str | None = None
    can_edit: bool = True
    created_at: datetime

    model_config = ConfigDict(from_attributes=True)


class ScoreBreakdown(BaseModel):
    glucose: int | None = None
    steps: int | None = None
    steps_post_meal_bonus: int | None = None
    sleep: int | None = None
    dinner_before_7pm: int | None = None
    alcohol: int | None = None
    no_food_after_dinner: int | None = None
    stress: int | None = None
    # [260623] 점수 재배분 — 신규 3항목 (소화는 2.5점 가능 → float)
    dinner_type: int | None = None
    digestion: float | None = None
    post_meal_exercise: int | None = None


class ScoreOut(BaseModel):
    total: float  # [260623] 소화 2.5점 등 소수점 가능
    label: str
    change_from_yesterday: float | None = None
    breakdown: ScoreBreakdown
    # 측정된 항목의 만점 합 (참고용 진단값).
    # ⚠️ [260727 사용자 확정] 점수 표시의 **분모가 아니다**. 미측정 항목은 0점이고 만점은 항상 100 고정이다.
    #   원래는 "N/valid_max" 표시용으로 추가했으나(260706), 그렇게 쓰면 미측정이 실점 없이 넘어가
    #   확정 정책과 반대가 된다. 앱이 이 필드를 매핑하지 않는 것은 의도된 것 — 버그로 보고 '고치지' 말 것.
    #   워치 동기화 누락일을 진단할 때(측정 항목이 몇 개였는지)만 참고하라.
    valid_max: int | None = None


class MissionTarget(BaseModel):
    additional_steps: int | None = None
    post_meal_walking_minutes: int | None = None
    after_meal: str | None = None
    target_hours: float | None = None
    bedtime_by: str | None = None
    days_target: int | None = None
    deadline: str | None = None


class AIFeedbackOut(BaseModel):
    message: str
    influencing_factors: list[str]
    encouragement: str | None = None
    mission: dict[str, Any] | None = None

    model_config = ConfigDict(from_attributes=True)


class DailyLogResponse(BaseModel):
    log: DailyLogOut
    score: ScoreOut | None = None
    ai_feedback: AIFeedbackOut | None = None
    loading_message: str = "전날의 생활을 분석 중입니다..."
    # [260722] AI 재생성 카운터 — log_date별 하드리밋(오늘=3회, 과거=2회).
    #   진짜 GPT 응답만 카운트. 앱은 "수정은 최대 2회 가능합니다.(남은 횟수: N회)" 표시,
    #   locked=true면 "이 날 AI 재생성 한도 소진"으로 대체하고 수정 버튼 비활성화.
    ai_regen_remaining: int = 0
    ai_regen_locked: bool = False


# ===========================================
# Watch Data
# ===========================================
class HRSample(BaseModel):
    time: datetime
    bpm: int


class StepsHourly(BaseModel):
    hour: int
    steps: int


class WatchDataCreate(BaseModel):
    log_date: date
    # [P0 회귀] steps도 Optional — Health Connect 권한 누락 시 클라이언트가 null로 보냄.
    #   이전 default=0이면 정상 데이터가 0으로 덮어써짐 (partial update 로직 우회).
    steps: int | None = Field(default=None, ge=0, le=200_000)  # 하루 최대 20만보
    # 시간별 걸음 — 24개 element 리스트 (인덱스 = 시간 0~23, 값 = 걸음수)
    steps_hourly: list[int] | None = Field(default=None, max_length=24)

    sleep_total_minutes: int | None = Field(default=None, ge=0, le=1440)  # 0~24시간
    sleep_deep_minutes: int | None = Field(default=None, ge=0, le=1440)
    sleep_light_minutes: int | None = Field(default=None, ge=0, le=1440)
    sleep_rem_minutes: int | None = Field(default=None, ge=0, le=1440)
    sleep_awake_minutes: int | None = Field(default=None, ge=0, le=1440)
    sleep_start_at: datetime | None = None
    sleep_end_at: datetime | None = None

    avg_heart_rate: int | None = Field(default=None, ge=30, le=220)  # 의학적 정상 범위
    min_heart_rate: int | None = Field(default=None, ge=30, le=220)
    max_heart_rate: int | None = Field(default=None, ge=30, le=220)
    # 어떤 형식이든 받을 수 있도록 관대하게 (안드로이드 HeartRateSample 또는 dict)
    # 다만 길이는 제한 (1분 단위 24시간이라도 1440)
    heart_rate_samples: list | None = Field(default=None, max_length=2880)

    spo2_avg: float | None = Field(default=None, ge=70, le=100)
    spo2_min: float | None = Field(default=None, ge=70, le=100)

    last_synced_at: datetime | None = None


class WatchDataOut(BaseModel):
    log_date: date
    # [260726] models.WatchData.steps 의 default=0 을 제거(미측정=null 계약)하면서 함께 nullable 로.
    #   그대로 두면 걸음 미측정 행 조회 시 응답 검증 실패(500)가 난다.
    #   null = 미측정 (0보와 구분) — daily_summary/reports 응답과 동일한 규약.
    steps: int | None = None
    sleep: dict[str, Any] | None = None
    heart_rate: dict[str, Any] | None = None
    spo2: dict[str, Any] | None = None
    last_synced_at: datetime | None = None
    sync_status: str = "ok"  # ok / stale / missing


# ===========================================
# Scores
# ===========================================
class ScoreTodayResponse(BaseModel):
    score_date: date
    total_score: float  # [260623] 소화 2.5점 등 소수점 가능
    label: str
    breakdown: ScoreBreakdown
    yesterday_score: float | None = None
    score_change_arrow: str = "same"  # up / down / same
    weekly_avg: float | None = None
    has_missing_data: bool = False
    encouragement_message: str | None = None


# ===========================================
# AI Feedback
# ===========================================
class AIFeedbackTodayResponse(BaseModel):
    feedback_date: date
    daily_message: str | None = None
    influencing_factors: list[str] | None = None
    encouragement: str | None = None
    mission: dict[str, Any] | None = None
    streak: dict[str, int] | None = None
    # [260722] AI 재생성 카운터 — 오늘 조회 → limit=3, fb.regen_count 기반.
    #   앱은 "수정은 최대 2회 가능합니다.(남은 횟수: N회)" 표시,
    #   locked=true면 "이 날 AI 재생성 한도 소진"으로 대체.
    ai_regen_remaining: int = 0
    ai_regen_locked: bool = False


# ===========================================
# Reports
# ===========================================
class WeeklyReportResponse(BaseModel):
    week_start_date: date
    week_end_date: date
    avg_score: float | None = None
    score_change_vs_prev: int | None = None
    score_change_arrow: str = "same"
    metrics: dict[str, Any]
    daily_scores: list[float]  # [260706 Codex] 총점 Double화에 맞춤 (앱도 List<Double> 파싱)
    top_risk_factors: list[dict[str, Any]]
    correlation_insights: list[str] | None = None
    gpt_summary: str | None = None
    pdf_url: str | None = None


class MonthlyReportResponse(BaseModel):
    year: int
    month: int
    avg_score: float | None = None
    score_change_vs_prev: int | None = None
    metrics: dict[str, Any]
    weekly_scores: list[float]  # [260706 Codex] 주차 평균 소수 1자리 (앱 List<Double> 파싱)
    top_risk_factors: list[dict[str, Any]]
    correlation_insights: list[str] | None = None
    gpt_summary: str | None = None
    pdf_url: str | None = None


# ===========================================
# Daily Summary
# ===========================================
class DailySummaryResponse(BaseModel):
    date: date
    score: dict[str, Any]
    glucose: dict[str, Any]
    activity: dict[str, Any]
    reflection: dict[str, bool | None]


# ===========================================
# Error
# ===========================================
class ErrorResponse(BaseModel):
    error: str
    message: str
