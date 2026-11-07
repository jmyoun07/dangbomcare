"""당봄케어 SQLAlchemy 2.0 모델 (v1.9)
11 테이블 + Supabase PostgreSQL + RLS
"""
from __future__ import annotations

import uuid
from datetime import date, datetime, time

from sqlalchemy import (
    JSON,
    Boolean,
    CheckConstraint,
    Date,
    DateTime,
    Float,
    ForeignKey,
    Index,
    Integer,
    String,
    Text,
    Time,
    UniqueConstraint,
    func,
)
from sqlalchemy.dialects.postgresql import UUID as PG_UUID
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.database import Base


def gen_uuid() -> uuid.UUID:
    return uuid.uuid4()


# ===========================================
# 1. users
# ===========================================
class User(Base):
    __tablename__ = "users"

    id: Mapped[uuid.UUID] = mapped_column(PG_UUID(as_uuid=True), primary_key=True)
    email: Mapped[str] = mapped_column(String, unique=True, nullable=False)
    patient_number: Mapped[str | None] = mapped_column(String, unique=True, nullable=True)
    # [260824] 온보딩 필수 항목(테스트 서버) — 첫 로그인 시 ACCOUNT_INFO 화면에서 필수 입력.
    #   기존 사용자는 백필 안 해서 NULL 허용 + UNIQUE(동일 번호 중복 가입 방지).
    phone_number: Mapped[str | None] = mapped_column(String, unique=True, nullable=True)
    name: Mapped[str | None] = mapped_column(String, nullable=True)
    gender: Mapped[str | None] = mapped_column(String, nullable=True)
    age: Mapped[int | None] = mapped_column(Integer, nullable=True)  # birth_date에서 derive (legacy)
    birth_date: Mapped[date | None] = mapped_column(Date, nullable=True)  # 새 흐름 — 이걸 primary로 사용
    height_cm: Mapped[float | None] = mapped_column(Float, nullable=True)
    weight_kg: Mapped[float | None] = mapped_column(Float, nullable=True)
    diabetes_diagnosed_at: Mapped[date | None] = mapped_column(Date, nullable=True)
    # [PDF 2 — 개인 공복혈당 범위] 평소 공복혈당 범위 (예 130~150). 채점 기준선.
    #   둘 다 NULL이면 기존 절대 기준(70~100)으로 fallback. 의료진 목표(110)와는 별개.
    glucose_normal_low: Mapped[int | None] = mapped_column(Integer, nullable=True)
    glucose_normal_high: Mapped[int | None] = mapped_column(Integer, nullable=True)
    # [260714] 매월 자동 재정의 팝업이 뜬 마지막 시각 — 이 달에 이미 확인했는지 판정용
    glucose_range_last_check_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    onboarding_completed: Mapped[bool] = mapped_column(Boolean, default=False)
    fcm_token: Mapped[str | None] = mapped_column(String, nullable=True)
    # [260904] 클라이언트 상태 — 앱이 포그라운드 진입 시 PUT /me/client-status 로 보고.
    #   NULL = 아직 보고한 적 없음(= 1.1.19 미만 구버전 앱). 걸음수 미수신 문의의 원인 분류용
    #   (앱 버전 / 배터리 최적화 / Health Connect 권한·가용성). 마이그레이션: 2026-09-04_client_status_and_app_config.sql
    app_version_code: Mapped[int | None] = mapped_column(Integer, nullable=True)
    app_version_name: Mapped[str | None] = mapped_column(String, nullable=True)
    battery_opt_ignored: Mapped[bool | None] = mapped_column(Boolean, nullable=True)   # 당봄케어 배터리 최적화 예외
    shealth_installed: Mapped[bool | None] = mapped_column(Boolean, nullable=True)     # 삼성헬스 설치
    shealth_opt_ignored: Mapped[bool | None] = mapped_column(Boolean, nullable=True)   # 삼성헬스 최적화 예외(참고용)
    health_source_status: Mapped[str | None] = mapped_column(String, nullable=True)    # sdk_direct|hc_ok|hc_partial:n/m|hc_no_permission|hc_update_required|hc_unavailable
    device_model: Mapped[str | None] = mapped_column(String, nullable=True)
    os_sdk: Mapped[int | None] = mapped_column(Integer, nullable=True)
    client_status_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    # [P0 회원탈퇴 30일 정책] 즉시 삭제 금지. soft-delete 후 30일 뒤 cron으로 영구 삭제
    deletion_requested_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    deletion_scheduled_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now(), onupdate=func.now())

    __table_args__ = (
        CheckConstraint("gender IN ('M', 'F')", name="users_gender_check"),
        CheckConstraint("age > 0 AND age < 150", name="users_age_check"),
        CheckConstraint("height_cm BETWEEN 100 AND 250", name="users_height_check"),
        CheckConstraint("weight_kg BETWEEN 20 AND 200", name="users_weight_check"),
        # [PDF 2] 공복혈당 범위: 둘 다 NULL 이거나, 둘 다 유효(40~400, low ≤ high)
        CheckConstraint(
            "(glucose_normal_low IS NULL AND glucose_normal_high IS NULL) "
            "OR (glucose_normal_low IS NOT NULL AND glucose_normal_high IS NOT NULL "
            "AND glucose_normal_low >= 40 AND glucose_normal_high <= 400 "
            "AND glucose_normal_low <= glucose_normal_high)",
            name="users_glucose_range_check",
        ),
    )


# ===========================================
# 0. app_config (260904 신설) — 키/값 앱 설정
#   강제 업데이트 정책(min_version_code 등). 백엔드는 읽기만 하고,
#   값 변경은 Supabase 대시보드에서(재배포 없이 반영). RLS 활성·정책 없음(anon 차단).
# ===========================================
class AppConfig(Base):
    __tablename__ = "app_config"

    key: Mapped[str] = mapped_column(Text, primary_key=True)
    value: Mapped[str] = mapped_column(Text, nullable=False)
    description: Mapped[str | None] = mapped_column(Text, nullable=True)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())


# ===========================================
# 1-a. glucose_range_history (260719 신설)
#   매월/변경 시점의 공복혈당 평소 범위를 이력으로 남김.
#   리포트에서 users의 현재 값이 아닌, 그 시점 유효 범위를 조회하기 위함.
#   effective_from = 이 범위가 유효하기 시작한 날짜.
# ===========================================
class GlucoseRangeHistory(Base):
    __tablename__ = "glucose_range_history"

    id: Mapped[uuid.UUID] = mapped_column(PG_UUID(as_uuid=True), primary_key=True, default=gen_uuid)
    user_id: Mapped[uuid.UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False
    )
    range_low: Mapped[int] = mapped_column(Integer, nullable=False)
    range_high: Mapped[int] = mapped_column(Integer, nullable=False)
    effective_from: Mapped[date] = mapped_column(Date, nullable=False)
    saved_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())

    __table_args__ = (
        CheckConstraint(
            "range_low >= 40 AND range_high <= 400 AND range_low <= range_high",
            name="glucose_range_history_bounds_check",
        ),
        Index("idx_glucose_range_history_user_date", "user_id", "effective_from"),
    )


# ===========================================
# 2. user_consents
# ===========================================
class UserConsent(Base):
    __tablename__ = "user_consents"

    id: Mapped[uuid.UUID] = mapped_column(PG_UUID(as_uuid=True), primary_key=True, default=gen_uuid)
    user_id: Mapped[uuid.UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False, unique=True
    )
    terms_accepted: Mapped[bool] = mapped_column(Boolean, nullable=False)
    privacy_accepted: Mapped[bool] = mapped_column(Boolean, nullable=False)
    sensitive_data_accepted: Mapped[bool] = mapped_column(Boolean, nullable=False)
    # [260726] 개인정보 및 민감정보 제3자 제공 동의 (당봄한의원 의료진 열람). 260723 최종안 기준 필수.
    #   기존 가입자는 실제로 받은 적이 없으므로 default False — 앱이 동의 화면으로 재유도한다.
    third_party_accepted: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False, server_default="false")
    research_use_accepted: Mapped[bool] = mapped_column(Boolean, default=False)
    accepted_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    # [260728] 마케팅 활용 및 광고성 정보 수신 동의 — 우노 260728 추가분. **선택** 항목.
    #   동의하지 않아도 가입·기본 서비스에 제한이 없다 → 필수 동의 게이트에 넣지 말 것.
    #   기존 가입자는 받은 적이 없으므로 default False. 설정 화면 토글로 언제든 철회/재동의.
    marketing_accepted: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False, server_default="false")
    # 우노 문서가 수집 항목에 '동의·철회 시각'을 명시 → accepted_at(필수 동의 시각 하나) 으로는
    #   반복되는 마케팅 동의/철회를 담을 수 없어 별도 컬럼. 각각 '마지막' 시각. 없으면 NULL.
    marketing_accepted_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True, default=None)
    marketing_revoked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True, default=None)


# ===========================================
# 3. user_settings
# ===========================================
class UserSetting(Base):
    __tablename__ = "user_settings"

    user_id: Mapped[uuid.UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), primary_key=True
    )
    glucose_check_time: Mapped[time] = mapped_column(Time, default=time(7, 0))
    dinner_deadline_time: Mapped[time] = mapped_column(Time, default=time(19, 0))
    bedtime: Mapped[time] = mapped_column(Time, default=time(23, 0))
    notif_morning: Mapped[bool] = mapped_column(Boolean, default=True)
    notif_morning_fallback: Mapped[bool] = mapped_column(Boolean, default=True)
    notif_dinner_deadline: Mapped[bool] = mapped_column(Boolean, default=True)
    notif_bedtime: Mapped[bool] = mapped_column(Boolean, default=False)
    # [DEPRECATED · 260714 정리 P2-11] '방해금지 시간' 260623에서 폐지 (S2). 하위호환 목적으로 컬럼 유지.
    notif_quiet_during_sleep: Mapped[bool] = mapped_column(Boolean, default=True)
    timezone: Mapped[str] = mapped_column(String, default="Asia/Seoul")
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now(), onupdate=func.now())


# ===========================================
# 4. daily_logs (핵심)
# ===========================================
class DailyLog(Base):
    __tablename__ = "daily_logs"

    id: Mapped[uuid.UUID] = mapped_column(PG_UUID(as_uuid=True), primary_key=True, default=gen_uuid)
    user_id: Mapped[uuid.UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False
    )
    log_date: Mapped[date] = mapped_column(Date, nullable=False)
    fasting_glucose: Mapped[int | None] = mapped_column(Integer, nullable=True)
    fasting_glucose_time: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)

    # v1.9: 회고 — 7시 전 식사 / 식후 금식 / 음주
    alcohol: Mapped[bool | None] = mapped_column(Boolean, nullable=True)
    dinner_before_7pm: Mapped[bool | None] = mapped_column(Boolean, nullable=True)
    no_food_after_dinner: Mapped[bool | None] = mapped_column(Boolean, nullable=True)

    # [260623 피드백] 전날 회고 입력 4개 추가 (점수 재배분 연동)
    #   no_food_after_dinner_hours: 식후 금식 시간 5단계 (no_food_after_dinner 불리언은 이 값에서 derive)
    no_food_after_dinner_hours: Mapped[str | None] = mapped_column(String, nullable=True)
    dinner_type: Mapped[list | None] = mapped_column(JSON, nullable=True)  # 저녁식사 유형 (중복 선택 리스트)
    # [260720 개편] 저녁 유형에서 'other'(기타) 선택 시 자유입력 텍스트 저장.
    dinner_type_other: Mapped[str | None] = mapped_column(String, nullable=True)
    digestion: Mapped[str | None] = mapped_column(String, nullable=True)   # 자기 전 소화 상태
    post_meal_exercise: Mapped[str | None] = mapped_column(String, nullable=True)  # 식후 운동

    input_method: Mapped[str | None] = mapped_column(String, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now(), onupdate=func.now())

    __table_args__ = (
        UniqueConstraint("user_id", "log_date", name="uq_daily_logs_user_date"),
        CheckConstraint("fasting_glucose IS NULL OR (fasting_glucose >= 10 AND fasting_glucose <= 600)", name="daily_logs_glucose_check"),
        CheckConstraint("input_method IS NULL OR input_method IN ('morning', 'fallback', 'manual')", name="daily_logs_input_method_check"),
        # [260623] 신규 회고 항목 enum 무결성 (dinner_type 은 JSON 리스트라 CHECK 제외)
        CheckConstraint("no_food_after_dinner_hours IS NULL OR no_food_after_dinner_hours IN ('1h_under','1to2h','2to3h','3to4h','4h_over')", name="daily_logs_nofood_hours_check"),
        CheckConstraint("digestion IS NULL OR digestion IN ('hungry','partial','full')", name="daily_logs_digestion_check"),
        CheckConstraint("post_meal_exercise IS NULL OR post_meal_exercise IN ('none','10to30','30to60','60plus')", name="daily_logs_post_meal_exercise_check"),
        Index("idx_daily_logs_user_date", "user_id", "log_date"),
    )


# ===========================================
# 5. watch_data (v1.9: SpO2 + steps_hourly 포함)
# ===========================================
class WatchData(Base):
    __tablename__ = "watch_data"

    id: Mapped[uuid.UUID] = mapped_column(PG_UUID(as_uuid=True), primary_key=True, default=gen_uuid)
    user_id: Mapped[uuid.UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False
    )
    log_date: Mapped[date] = mapped_column(Date, nullable=False)

    # [260726] default=0 제거. 앱/백엔드 계약은 "steps=null = 미측정"인데, 신규 행 생성 시
    #   ORM 기본값 0 이 INSERT 되고 watch_data.py 의 partial update 는 0 을 되돌리지 않는다.
    #   → score_service 가 '미측정(None, 채점 제외)'을 '0보(0점 확정)'로 오인했다.
    #   nullable 유지를 위해 타입도 Mapped[int | None] 로 (Mapped[int] 는 NOT NULL 을 함의).
    steps: Mapped[int | None] = mapped_column(Integer, nullable=True)
    steps_hourly: Mapped[list | None] = mapped_column(JSON, nullable=True)

    sleep_total_minutes: Mapped[int | None] = mapped_column(Integer, nullable=True)
    sleep_deep_minutes: Mapped[int | None] = mapped_column(Integer, nullable=True)
    sleep_light_minutes: Mapped[int | None] = mapped_column(Integer, nullable=True)
    sleep_rem_minutes: Mapped[int | None] = mapped_column(Integer, nullable=True)
    sleep_awake_minutes: Mapped[int | None] = mapped_column(Integer, nullable=True)
    sleep_start_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    sleep_end_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)

    avg_heart_rate: Mapped[int | None] = mapped_column(Integer, nullable=True)
    min_heart_rate: Mapped[int | None] = mapped_column(Integer, nullable=True)
    max_heart_rate: Mapped[int | None] = mapped_column(Integer, nullable=True)
    heart_rate_samples: Mapped[list | None] = mapped_column(JSON, nullable=True)

    spo2_avg: Mapped[float | None] = mapped_column(Float, nullable=True)
    spo2_min: Mapped[float | None] = mapped_column(Float, nullable=True)

    last_synced_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    synced_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())

    __table_args__ = (
        UniqueConstraint("user_id", "log_date", name="uq_watch_data_user_date"),
        Index("idx_watch_data_user_date", "user_id", "log_date"),
    )


# ===========================================
# 6. stress_data (자체 추정)
# ===========================================
class StressData(Base):
    __tablename__ = "stress_data"

    id: Mapped[uuid.UUID] = mapped_column(PG_UUID(as_uuid=True), primary_key=True, default=gen_uuid)
    user_id: Mapped[uuid.UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False
    )
    log_date: Mapped[date] = mapped_column(Date, nullable=False)
    morning_status: Mapped[str | None] = mapped_column(String, nullable=True)
    afternoon_status: Mapped[str | None] = mapped_column(String, nullable=True)
    evening_status: Mapped[str | None] = mapped_column(String, nullable=True)
    below_avg_ratio: Mapped[float | None] = mapped_column(Float, nullable=True)
    avg_variability: Mapped[float | None] = mapped_column(Float, nullable=True)  # v1.9: 자체 추정 변동성
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())

    __table_args__ = (
        UniqueConstraint("user_id", "log_date", name="uq_stress_data_user_date"),
        CheckConstraint("morning_status IS NULL OR morning_status IN ('적정', '주의', '높음')", name="stress_morning_check"),
        CheckConstraint("afternoon_status IS NULL OR afternoon_status IN ('적정', '주의', '높음')", name="stress_afternoon_check"),
        CheckConstraint("evening_status IS NULL OR evening_status IN ('적정', '주의', '높음')", name="stress_evening_check"),
    )


# ===========================================
# 7. lifestyle_scores
# ===========================================
class LifestyleScore(Base):
    __tablename__ = "lifestyle_scores"

    id: Mapped[uuid.UUID] = mapped_column(PG_UUID(as_uuid=True), primary_key=True, default=gen_uuid)
    user_id: Mapped[uuid.UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False
    )
    score_date: Mapped[date] = mapped_column(Date, nullable=False)
    total_score: Mapped[float | None] = mapped_column(Float, nullable=True)  # [260623] 소화 2.5점 등 소수점 허용
    glucose_score: Mapped[int | None] = mapped_column(Integer, nullable=True)
    steps_score: Mapped[int | None] = mapped_column(Integer, nullable=True)
    sleep_score: Mapped[int | None] = mapped_column(Integer, nullable=True)
    dinner_before_7pm_score: Mapped[int | None] = mapped_column(Integer, nullable=True)
    alcohol_score: Mapped[int | None] = mapped_column(Integer, nullable=True)
    no_food_after_dinner_score: Mapped[int | None] = mapped_column(Integer, nullable=True)
    stress_score: Mapped[int | None] = mapped_column(Integer, nullable=True)
    # [260623 피드백] 점수 재배분 — 신규 3항목
    dinner_type_score: Mapped[int | None] = mapped_column(Integer, nullable=True)
    digestion_score: Mapped[float | None] = mapped_column(Float, nullable=True)  # [260623] 소화 중간 2.5점
    post_meal_exercise_score: Mapped[int | None] = mapped_column(Integer, nullable=True)
    post_meal_walking_bonus: Mapped[int] = mapped_column(Integer, default=0)
    score_label: Mapped[str | None] = mapped_column(String, nullable=True)
    has_missing_data: Mapped[bool] = mapped_column(Boolean, default=False)
    calculated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())

    __table_args__ = (
        UniqueConstraint("user_id", "score_date", name="uq_lifestyle_scores_user_date"),
        # [P0 DB 무결성] score_service.py의 when_label()이 반환하는 모든 라벨 포함
        CheckConstraint(
            "score_label IS NULL OR score_label IN "
            "('좋음', '보통', '개선 가능', '측정 권장 (혈당 미입력)', '데이터 부족')",
            name="lifestyle_score_label_check"
        ),
        Index("idx_scores_user_date", "user_id", "score_date"),
    )


# ===========================================
# 8. ai_feedbacks
# ===========================================
class AIFeedback(Base):
    __tablename__ = "ai_feedbacks"

    id: Mapped[uuid.UUID] = mapped_column(PG_UUID(as_uuid=True), primary_key=True, default=gen_uuid)
    user_id: Mapped[uuid.UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False
    )
    feedback_date: Mapped[date] = mapped_column(Date, nullable=False)
    daily_message: Mapped[str | None] = mapped_column(Text, nullable=True)
    influencing_factors: Mapped[list | None] = mapped_column(JSON, nullable=True)
    encouragement: Mapped[str | None] = mapped_column(String, nullable=True)
    daily_mission: Mapped[str | None] = mapped_column(Text, nullable=True)
    mission_type: Mapped[str | None] = mapped_column(String, nullable=True)
    mission_target: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    mission_progress: Mapped[float] = mapped_column(Float, default=0)
    mission_completed: Mapped[bool] = mapped_column(Boolean, default=False)
    mission_tip: Mapped[str | None] = mapped_column(String, nullable=True)
    gpt_model: Mapped[str | None] = mapped_column(String, nullable=True)
    gpt_input_tokens: Mapped[int | None] = mapped_column(Integer, nullable=True)
    gpt_output_tokens: Mapped[int | None] = mapped_column(Integer, nullable=True)
    gpt_cost_krw: Mapped[float | None] = mapped_column(Float, nullable=True)
    weekly_streak_days: Mapped[int] = mapped_column(Integer, default=0)
    weekly_streak_total: Mapped[int] = mapped_column(Integer, default=7)
    monthly_streak_days: Mapped[int] = mapped_column(Integer, default=0)
    # [260722] AI 재생성 카운터 — log_date별 하드리밋(자정 리셋 없음).
    #   오늘=3회, 과거=2회. 진짜 GPT 응답만 카운트(cache/fallback/budget_capped/skipped_old_date/regen_capped 제외).
    regen_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0, server_default="0")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now(), onupdate=func.now())

    __table_args__ = (
        UniqueConstraint("user_id", "feedback_date", name="uq_ai_feedbacks_user_date"),
        # [P0 DB 무결성 / 260714 정리 H7] ai_service.py valid_types와 동일 (hydration 폐지)
        #   260623 피드백에서 '물 마시기/수분 미션' 완전 제외 결정 이후 프롬프트·서비스는 이미 차단.
        #   DB CHECK에도 잔존이 있어 이번 라운드에 제거 — 새 마이그레이션 필요.
        CheckConstraint(
            "mission_type IS NULL OR mission_type IN ("
            "'steps', 'sleep', 'alcohol', 'dinner_timing', "
            "'stretching', 'stress_relief', 'general'"
            ")",
            name="ai_feedbacks_mission_type_check"
        ),
        Index("idx_feedbacks_user_date", "user_id", "feedback_date"),
    )


# ===========================================
# 9. weekly_reports
# ===========================================
class WeeklyReport(Base):
    __tablename__ = "weekly_reports"

    id: Mapped[uuid.UUID] = mapped_column(PG_UUID(as_uuid=True), primary_key=True, default=gen_uuid)
    user_id: Mapped[uuid.UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False
    )
    week_start_date: Mapped[date] = mapped_column(Date, nullable=False)
    week_end_date: Mapped[date] = mapped_column(Date, nullable=False)
    avg_score: Mapped[float | None] = mapped_column(Float, nullable=True)
    score_change_vs_prev: Mapped[int | None] = mapped_column(Integer, nullable=True)
    avg_glucose: Mapped[float | None] = mapped_column(Float, nullable=True)
    avg_steps: Mapped[float | None] = mapped_column(Float, nullable=True)
    avg_sleep_hours: Mapped[float | None] = mapped_column(Float, nullable=True)
    alcohol_days: Mapped[int | None] = mapped_column(Integer, nullable=True)
    dinner_before_7pm_days: Mapped[int | None] = mapped_column(Integer, nullable=True)
    late_eating_days: Mapped[int | None] = mapped_column(Integer, nullable=True)
    top_risk_factors: Mapped[list | None] = mapped_column(JSON, nullable=True)
    correlation_insights: Mapped[list | None] = mapped_column(JSON, nullable=True)
    gpt_summary: Mapped[str | None] = mapped_column(Text, nullable=True)
    gpt_model: Mapped[str | None] = mapped_column(String, nullable=True)
    gpt_cost_krw: Mapped[float | None] = mapped_column(Float, nullable=True)
    pdf_url: Mapped[str | None] = mapped_column(String, nullable=True)
    pdf_generated_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())

    __table_args__ = (
        UniqueConstraint("user_id", "week_start_date", name="uq_weekly_reports_user_date"),
    )


# ===========================================
# 10. monthly_reports
# ===========================================
class MonthlyReport(Base):
    __tablename__ = "monthly_reports"

    id: Mapped[uuid.UUID] = mapped_column(PG_UUID(as_uuid=True), primary_key=True, default=gen_uuid)
    user_id: Mapped[uuid.UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False
    )
    year: Mapped[int] = mapped_column(Integer, nullable=False)
    month: Mapped[int] = mapped_column(Integer, nullable=False)
    avg_score: Mapped[float | None] = mapped_column(Float, nullable=True)
    score_change_vs_prev: Mapped[int | None] = mapped_column(Integer, nullable=True)
    avg_glucose: Mapped[float | None] = mapped_column(Float, nullable=True)
    avg_steps: Mapped[float | None] = mapped_column(Float, nullable=True)
    avg_sleep_hours: Mapped[float | None] = mapped_column(Float, nullable=True)
    alcohol_days: Mapped[int | None] = mapped_column(Integer, nullable=True)
    dinner_before_7pm_days: Mapped[int | None] = mapped_column(Integer, nullable=True)
    late_eating_days: Mapped[int | None] = mapped_column(Integer, nullable=True)
    weekly_scores: Mapped[list | None] = mapped_column(JSON, nullable=True)
    top_risk_factors: Mapped[list | None] = mapped_column(JSON, nullable=True)
    correlation_insights: Mapped[list | None] = mapped_column(JSON, nullable=True)
    gpt_summary: Mapped[str | None] = mapped_column(Text, nullable=True)
    gpt_model: Mapped[str | None] = mapped_column(String, nullable=True)
    gpt_cost_krw: Mapped[float | None] = mapped_column(Float, nullable=True)
    pdf_url: Mapped[str | None] = mapped_column(String, nullable=True)
    pdf_generated_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())

    __table_args__ = (
        UniqueConstraint("user_id", "year", "month", name="uq_monthly_reports_user_year_month"),
        CheckConstraint("month >= 1 AND month <= 12", name="monthly_month_check"),
    )


# ===========================================
# 11. doctor_notes (v1.x 의료진 어드민용 — 테이블만 미리)
# ===========================================
class DoctorNote(Base):
    __tablename__ = "doctor_notes"

    id: Mapped[uuid.UUID] = mapped_column(PG_UUID(as_uuid=True), primary_key=True, default=gen_uuid)
    patient_id: Mapped[uuid.UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False
    )
    doctor_email: Mapped[str] = mapped_column(String, nullable=False)
    note_date: Mapped[date] = mapped_column(Date, nullable=False)
    note_text: Mapped[str] = mapped_column(Text, nullable=False)
    visible_to_patient: Mapped[bool] = mapped_column(Boolean, default=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now(), onupdate=func.now())

    __table_args__ = (
        Index("idx_doctor_notes_patient", "patient_id", "note_date"),
    )


# ===========================================
# 12. medical_goals (우노 피드백 4 — 의료진이 진료 시 설정하는 목표 / 이력)
#   - PIN 잠금은 클라이언트에서 처리 (환자는 홈에서 보기 전용).
#   - [이력] append-only — 매 저장마다 새 row. "현재 목표" = 가장 최근(created_at desc).
#     → 과거 목표를 ◀▶로 조회 가능. 수치 목표 3개 + 목표 시점 + 생활습관 목표(서술식).
# ===========================================
class MedicalGoal(Base):
    __tablename__ = "medical_goals"

    id: Mapped[uuid.UUID] = mapped_column(PG_UUID(as_uuid=True), primary_key=True, default=gen_uuid)
    user_id: Mapped[uuid.UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False
    )
    # 수치 목표
    target_hba1c: Mapped[float | None] = mapped_column(Float, nullable=True)            # 당화혈색소 % (예: 5.2)
    target_pancreas: Mapped[float | None] = mapped_column(Float, nullable=True)         # 췌장수치
    target_fasting_glucose: Mapped[int | None] = mapped_column(Integer, nullable=True)  # 공복혈당 mg/dL (예: 120)
    target_period: Mapped[str | None] = mapped_column(String, nullable=True)            # 목표 시점 ISO 날짜(yyyy-MM-dd)
    # 생활습관 목표 — 서술식 (저녁 식사 시간, 생활관리 안 되는 부분 등 자유 입력)
    lifestyle_goals: Mapped[str | None] = mapped_column(Text, nullable=True)
    set_by: Mapped[str | None] = mapped_column(String, nullable=True)                   # 입력 의료진 (선택, 향후 의료진 웹)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now(), onupdate=func.now())

    __table_args__ = (
        Index("idx_medical_goals_user", "user_id", "created_at"),
    )
