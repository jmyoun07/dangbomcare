"""일일 입력 (핵심 엔드포인트) — 점수 산출 + GPT 호출"""
from __future__ import annotations

import uuid
from datetime import date, datetime, timedelta, timezone

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy import desc, func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.database import get_db
from app.deps import get_current_user_id
from app.models import (
    AIFeedback,
    DailyLog,
    LifestyleScore,
    MedicalGoal,
    StressData,
    User,
    UserSetting,
    WatchData,
)
from app.schemas import (
    AIFeedbackOut,
    DailyLogCreate,
    DailyLogOut,
    DailyLogResponse,
    ScoreBreakdown,
    ScoreOut,
)
from app.services import ai_service, csv_writer, score_service

router = APIRouter(prefix="/api/daily-logs", tags=["daily_logs"])


KST = timezone(timedelta(hours=9))

# [260707 / 260714 정리 B1] 신규 회고 3항목 — AI 입력용 한국어 라벨 (앱 아침 입력 화면 워딩과 동일)
#   [260720 개편] flour(밀가루) 삭제, other(기타) 추가. '기타' 선택 시 dinner_type_other 자유입력 텍스트 별도 저장.
# [260729 통합검증 P2] 구분자를 `/` → `·` 로 통일.
#   ai_prompts.py 는 이 라벨을 **문자열 그대로** 조건에 건다
#   (L89·L256·L296·L439-441·L589·L652: `dinner_type 에 "외식·배달"/"고기 위주"가 포함된 경우`).
#   payload 가 "외식/배달"을 보내고 있어서 그 규칙들이 문자 단위로는 절대 성립하지 않았다 —
#   이 프로젝트의 반복 버그 유형("payload 에 있는데 프롬프트가 모르는 필드")과 같은 계열.
#   digestion·post_meal_exercise 라벨은 이미 프롬프트와 완전 일치하고, dinner_type 만 어긋나 있었다.
#   ⚠️ 이 dict 는 AI payload 전용이다(유일 참조: 아래 ai_input["yesterday"]["dinner_type"]).
#     앱 화면 워딩·DB 값(enum 키)에는 영향이 없다.
_DINNER_TYPE_KO = {
    "home_korean": "집밥·한식",
    "eating_out": "외식·배달",
    "meat": "고기 위주",
    "light": "간단식",
    "other": "기타",
    "flour": "밀가루",  # legacy — 신규 선택 불가, 기존 데이터 렌더링용
}
_DIGESTION_KO = {"hungry": "배고픔(공복 취침)", "partial": "소화가 조금 됨", "full": "여전히 배부름(더부룩)"}
_POST_MEAL_EX_KO = {"none": "안 함", "10to30": "10~30분", "30to60": "30~60분", "60plus": "1시간 이상"}


def _can_edit(log_date: date) -> bool:
    """다음날 자정 전이면 수정 가능"""
    return log_date >= datetime.now(KST).date()


def _sleep_start_hour_kst(dt: "datetime | None") -> float | None:
    """[260727] 취침 시각 → KST 기준 24시간제 실수 (예: 23:30 → 23.5). None 가능.

    AI 프롬프트가 '몇 시에 주무셨는지' 코칭에 쓰는 입력값(ai_input.yesterday.sleep_start_hour).
    watch_data.sleep_start_at 은 timestamptz 라 보통 aware 로 오지만, naive 로 들어오면
    이미 KST 로 간주한다(csv_export._hm 과 동일 규칙).
    """
    if dt is None:
        return None
    try:
        if dt.tzinfo is not None:
            dt = dt.astimezone(KST)
        return round(dt.hour + dt.minute / 60.0, 2)
    except (AttributeError, ValueError, OSError, OverflowError):
        return None


# [260726][P0 의료 안전] 안전 오버라이드 헬퍼는 score_service.apply_safety_override 가 정본이다.
#   이 파일에도 같은 일을 하는 로컬 _apply_safety_override 가 있었으나, 200~250 mg/dL 구간 전용
#   안내(status 가 "normal" 이라 glucose_safety_message 가 None 을 주는 구간) 가 빠져 있어
#   POST/GET 응답만 빈 안전 문구로 나갈 수 있었다. → 삭제하고 공용 함수로 일원화.
#   (POST /api/daily-logs, GET /api/daily-logs/{log_date}, GET /api/ai-feedback/today 모두 동일 함수 사용)


# ============================================================
# 미션 진행도 / 연속 달성(streak) 계산 헬퍼
#   AIFeedback의 mission_progress/mission_completed/weekly_streak_days/monthly_streak_days
#   컬럼은 존재하나 지금까지 아무도 쓰지 않아 항상 default(0/False)였음 → 여기서 채운다.
# ============================================================

# 미션 type → 만점 인정 기준은 실제 GPT mission 구조(ai_prompts.py / ai_service.py)에 맞춤:
#   type ∈ {steps, sleep, alcohol, dinner_timing, hydration, stretching, stress_relief, general}
#   target dict 예: {"post_meal_walking_minutes":10, "water_glasses":8, "steps":4000,
#                    "sleep_start_before":"23:00", "dinner_before":"19:00",
#                    "no_food_after_dinner":true, "alcohol":false, "stretching_minutes":10}
_MEAL_TIMES = {"breakfast": 8.0, "lunch": 12.5, "dinner": 18.5}


async def _compute_log_streaks(
    db: AsyncSession, user_id: uuid.UUID, end_date: date
) -> tuple[int, int]:
    """end_date 기준 '공복혈당을 입력한 날'의 연속 기록 일수 계산.

    - weekly_streak_days: end_date에서 거꾸로 끊김 없이 이어진 기록일 수 (최대 7).
    - monthly_streak_days: 같은 방식이나 이번 달 1일까지만 카운트 (최대 end_date.day).

    '기록한 날' = DailyLog.fasting_glucose IS NOT NULL (점수 0/미입력 conflation 방지 — reports.py와 동일 정의).
    """
    month_start = end_date.replace(day=1)
    # 주간 윈도우(최근 7일)와 월초 중 더 이른 날부터 조회 (한 쿼리로 둘 다 커버)
    query_from = min(end_date - timedelta(days=6), month_start)

    result = await db.execute(
        select(DailyLog.log_date).where(
            DailyLog.user_id == user_id,
            DailyLog.fasting_glucose.is_not(None),
            DailyLog.log_date >= query_from,
            DailyLog.log_date <= end_date,
        )
    )
    recorded: set[date] = {row[0] for row in result.all()}

    # end_date부터 하루씩 뒤로 가며 연속으로 기록된 날 카운트
    weekly = 0
    for i in range(7):  # 최대 7일
        d = end_date - timedelta(days=i)
        if d in recorded:
            weekly += 1
        else:
            break

    monthly_cap = end_date.day  # 이번 달 1일~end_date 까지 가능 일수
    monthly = 0
    for i in range(monthly_cap):
        d = end_date - timedelta(days=i)
        if d < month_start:
            break
        if d in recorded:
            monthly += 1
        else:
            break

    return weekly, monthly


def _score_steps(target: dict, watch: "WatchData | None") -> float | None:
    """steps/additional_steps 목표 → 어제 워치 걸음수 대비 진행도(0..1). 신호 없으면 None."""
    if watch is None or watch.steps is None:
        return None
    goal_steps = target.get("steps") or target.get("additional_steps") or 6000
    try:
        goal_steps = float(goal_steps)
    except (TypeError, ValueError):
        goal_steps = 6000.0
    if goal_steps <= 0:
        return None
    return min(watch.steps / goal_steps, 1.0)


def _score_post_meal_walking(target: dict, watch: "WatchData | None") -> float | None:
    """post_meal_walking_minutes(+선택 after_meal) → 식후 걷기 충분도(0..1). 신호 없으면 None."""
    steps_hourly = watch.steps_hourly if watch else None
    statuses = score_service.post_meal_walking_status(steps_hourly, _MEAL_TIMES)
    if not statuses:
        return None
    after_meal = target.get("after_meal")  # 특정 끼니 지정 시 그 끼니만
    if after_meal and after_meal in statuses:
        considered = {after_meal: statuses[after_meal]}
    else:
        considered = statuses
    if not considered:
        return None
    sufficient = sum(1 for v in considered.values() if v.get("sufficient"))
    return min(sufficient / len(considered), 1.0)


def _score_sleep(target: dict, watch: "WatchData | None") -> float | None:
    """target_hours/sleep_start_before → 어제 워치 수면시간 대비 진행도(0..1). 신호 없으면 None.

    - target_hours: 목표 수면 시간(시) → 분 환산 기준.
    - sleep_start_before(취침 시각만 지정)만 있으면 권장 7시간(420분) 기준으로 채점.
    """
    if watch is None or watch.sleep_total_minutes is None:
        return None
    goal_min = 420.0
    if target.get("target_hours") is not None:
        try:
            goal_min = float(target["target_hours"]) * 60.0
        except (TypeError, ValueError):
            goal_min = 420.0
    if goal_min <= 0:
        return None
    return min(watch.sleep_total_minutes / goal_min, 1.0)


def _compute_mission_progress(
    mission_type: str | None,
    target: dict | None,
    payload: "DailyLogCreate",
    watch: "WatchData | None",
) -> tuple[float, bool]:
    """미션 target의 '측정 가능한 키'를 오늘 입력(payload, 어제 회고)+워치(어제)로 채점 → (progress 0..1, completed).

    payload: 오늘 들어온 회고(alcohol/dinner_before_7pm/no_food_after_dinner) — '어제 행동' 반영.
    watch:   어제 워치(steps/sleep_total_minutes/steps_hourly).

    ⚠️ mission_type 과 무관하게 target 에 존재하는 측정 가능 키별로 채점한다.
       (이전엔 type 으로 분기 → general/hydration/stretching/stress_relief 는 target 에
        steps/post_meal_walking_minutes/dinner_before 등이 있어도 항상 0/미완료였음.)
    각 키의 sub-progress(0..1)를 평균. 서버에서 관측 가능한 신호가 하나도 없으면 (0.0, False).
    water_glasses/stretching_minutes 등 서버 신호가 없는 자가보고 항목은 스킵.
    """
    target = target or {}
    checks: list[float] = []

    # --- 식후 걷기 (post_meal_walking_minutes, +선택 after_meal) ---
    if "post_meal_walking_minutes" in target or target.get("after_meal"):
        v = _score_post_meal_walking(target, watch)
        if v is not None:
            checks.append(v)

    # --- 걸음수 목표 (steps / additional_steps) ---
    if "steps" in target or "additional_steps" in target:
        v = _score_steps(target, watch)
        if v is not None:
            checks.append(v)

    # --- 수면 (target_hours / sleep_start_before) ---
    if "target_hours" in target or "sleep_start_before" in target:
        v = _score_sleep(target, watch)
        if v is not None:
            checks.append(v)

    # --- 음주 절제 (alcohol) — 회고 alcohol == target['alcohol'] 면 달성 ---
    if "alcohol" in target and payload.alcohol is not None:
        checks.append(1.0 if payload.alcohol == bool(target["alcohol"]) else 0.0)

    # --- 저녁 식사 시간 (dinner_before / deadline) → 회고 dinner_before_7pm ---
    if ("dinner_before" in target or "deadline" in target) and payload.dinner_before_7pm is not None:
        checks.append(1.0 if payload.dinner_before_7pm is True else 0.0)

    # --- 식후 금식 (no_food_after_dinner) → 회고 no_food_after_dinner == bool(target) ---
    # [260706 Codex] hours(5단계)만 보내는 신규 앱 호환 — 2h 이상 금식을 지킴(True)으로 derive
    _nf = payload.no_food_after_dinner
    if payload.no_food_after_dinner_hours is not None:
        _nf = payload.no_food_after_dinner_hours in ("2to3h", "3to4h", "4h_over")
    if target.get("no_food_after_dinner") is not None and _nf is not None:
        checks.append(1.0 if _nf == bool(target["no_food_after_dinner"]) else 0.0)

    # water_glasses/stretching_minutes 등 서버 관측 신호 없는 키는 의도적으로 스킵.

    if not checks:
        return 0.0, False
    progress = min(sum(checks) / len(checks), 1.0)
    return progress, progress >= 1.0


async def _update_previous_mission_completion(
    db: AsyncSession,
    user_id: uuid.UUID,
    log_date: date,
    payload: "DailyLogCreate",
    watch: "WatchData | None",
) -> None:
    """어제(=log_date-1) 피드백의 미션을 오늘 입력으로 사후 채점.

    오늘 들어온 회고/워치는 '어제 하루'를 반영하므로, 어제 출제된 미션의 달성 여부를
    오늘 입력 시점에 비로소 확정할 수 있다. → 어제 AIFeedback의 mission_progress/completed 갱신.
    """
    prev_date = log_date - timedelta(days=1)
    prev_fb_result = await db.execute(
        select(AIFeedback).where(
            AIFeedback.user_id == user_id,
            AIFeedback.feedback_date == prev_date,
        )
    )
    prev_fb = prev_fb_result.scalar_one_or_none()
    if prev_fb is None or not prev_fb.daily_mission:
        return
    progress, completed = _compute_mission_progress(
        prev_fb.mission_type, prev_fb.mission_target, payload, watch
    )
    prev_fb.mission_progress = progress
    prev_fb.mission_completed = completed


@router.post("", response_model=DailyLogResponse, status_code=201)
async def create_daily_log(
    payload: DailyLogCreate,
    user_id: uuid.UUID = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    """통합 입력: 공복혈당 + 회고 3개 → 점수 산출 → GPT 호출 → 응답

    GPT 캐싱: 같은 날짜에 데이터 변경 없으면 기존 ai_feedback 그대로 반환 (GPT 재호출 X).
    """

    # 1. daily_logs INSERT/UPDATE — 변경 여부 추적
    result = await db.execute(
        select(DailyLog).where(
            DailyLog.user_id == user_id,
            DailyLog.log_date == payload.log_date,
        )
    )
    log = result.scalar_one_or_none()

    # [260623] 식후 금식 시간(5단계) → 불리언 derive (AI/상관관계가 쓰는 no_food_after_dinner 호환).
    #   hours가 오면 2h 이상 금식을 '지킴(True)'으로 본다. hours 없으면 기존 불리언 그대로.
    nf_bool = payload.no_food_after_dinner
    if payload.no_food_after_dinner_hours is not None:
        nf_bool = payload.no_food_after_dinner_hours in ("2to3h", "3to4h", "4h_over")

    # 변경 감지: 신규 입력이거나 회고/혈당 필드 중 하나라도 다르면 GPT 재호출
    data_changed = (
        log is None
        or log.fasting_glucose != payload.fasting_glucose
        or log.alcohol != payload.alcohol
        or log.dinner_before_7pm != payload.dinner_before_7pm
        or log.no_food_after_dinner != nf_bool
        or log.no_food_after_dinner_hours != payload.no_food_after_dinner_hours
        or log.dinner_type != payload.dinner_type
        or log.dinner_type_other != payload.dinner_type_other
        or log.digestion != payload.digestion
        or log.post_meal_exercise != payload.post_meal_exercise
    )

    if log:
        # [우노 피드백 3] 과거 내용 입력하기(input_method="manual")는 잠금 우회 — 지난 날짜도 수정 가능.
        #   일반 아침/대체 입력은 기존대로 "다음날 자정 이후 수정 불가" 유지.
        if payload.input_method != "manual" and not _can_edit(log.log_date):
            raise HTTPException(
                status_code=403,
                detail={"error": "EDIT_LOCKED", "message": "다음날 자정 이후에는 수정할 수 없어요"},
            )
        log.fasting_glucose = payload.fasting_glucose
        log.fasting_glucose_time = payload.fasting_glucose_time
        log.alcohol = payload.alcohol
        log.dinner_before_7pm = payload.dinner_before_7pm
        log.no_food_after_dinner = nf_bool
        log.no_food_after_dinner_hours = payload.no_food_after_dinner_hours
        log.dinner_type = payload.dinner_type
        log.dinner_type_other = payload.dinner_type_other
        log.digestion = payload.digestion
        log.post_meal_exercise = payload.post_meal_exercise
        log.input_method = payload.input_method
    else:
        log = DailyLog(
            user_id=user_id,
            log_date=payload.log_date,
            fasting_glucose=payload.fasting_glucose,
            fasting_glucose_time=payload.fasting_glucose_time,
            alcohol=payload.alcohol,
            dinner_before_7pm=payload.dinner_before_7pm,
            no_food_after_dinner=nf_bool,
            no_food_after_dinner_hours=payload.no_food_after_dinner_hours,
            dinner_type=payload.dinner_type,
            dinner_type_other=payload.dinner_type_other,
            digestion=payload.digestion,
            post_meal_exercise=payload.post_meal_exercise,
            input_method=payload.input_method,
        )
        db.add(log)

    await db.flush()

    # 2. 워치 데이터 조회 — 어제 데이터 (회고 + 워치는 어제 / 공복혈당은 오늘)
    # 점수의 의미: "어제 어떻게 보냈는지 → 오늘 아침 컨디션이 어떤가"의 종합
    yesterday = payload.log_date - timedelta(days=1)
    watch_result = await db.execute(
        select(WatchData).where(
            WatchData.user_id == user_id,
            WatchData.log_date == yesterday,
        )
    )
    watch = watch_result.scalar_one_or_none()

    # 3. 스트레스 데이터 조회 — 어제 데이터
    stress_result = await db.execute(
        select(StressData).where(
            StressData.user_id == user_id,
            StressData.log_date == yesterday,
        )
    )
    stress = stress_result.scalar_one_or_none()

    # 4. 점수 산출
    # [PDF 2 — 개인 공복혈당 범위] 사용자 프로필 미리 로드 → 채점 기준선 + 아래 AI 입력에서 재사용.
    user_result = await db.execute(select(User).where(User.id == user_id))
    user = user_result.scalar_one_or_none()
    # [버그수정 500] 저녁 마감시각은 UserSetting 테이블에 있음(User 아님) → 별도 로드.
    #   기존 user.dinner_deadline_time 접근은 AttributeError → Internal Server Error 유발했음.
    setting_result = await db.execute(select(UserSetting).where(UserSetting.user_id == user_id))
    user_setting = setting_result.scalar_one_or_none()
    score_data = {
        "fasting_glucose": payload.fasting_glucose,
        "steps": watch.steps if watch else None,
        "steps_hourly": watch.steps_hourly if watch else None,
        "sleep_minutes": watch.sleep_total_minutes if watch else None,
        "alcohol": payload.alcohol,
        "dinner_before_7pm": payload.dinner_before_7pm,
        "no_food_after_dinner": nf_bool,
        # [260623] 신규 회고 4항목 → 점수 연동
        "no_food_after_dinner_hours": payload.no_food_after_dinner_hours,
        "dinner_type": payload.dinner_type,
        "digestion": payload.digestion,
        "post_meal_exercise": payload.post_meal_exercise,
        "stress_below_avg_ratio": stress.below_avg_ratio if stress else None,
        # [PDF 2] 개인 공복혈당 범위 — 설정 시 절대 기준(70~100) 대신 범위로 채점 (미설정=None → fallback)
        "glucose_normal_low": user.glucose_normal_low if user else None,
        "glucose_normal_high": user.glucose_normal_high if user else None,
        # 표준 식사 시각 (사용자 설정 추가 후 교체)
        "meal_times": {"breakfast": 8.0, "lunch": 12.5, "dinner": 18.5},
    }
    score_result = score_service.calculate_score(score_data)

    # 5. lifestyle_scores INSERT/UPDATE
    score_row_result = await db.execute(
        select(LifestyleScore).where(
            LifestyleScore.user_id == user_id,
            LifestyleScore.score_date == payload.log_date,
        )
    )
    lscore = score_row_result.scalar_one_or_none()

    bd = score_result["breakdown"]
    if lscore is None:
        lscore = LifestyleScore(
            user_id=user_id,
            score_date=payload.log_date,
        )
        db.add(lscore)

    lscore.total_score = score_result["total"]
    lscore.glucose_score = bd["glucose"]
    lscore.steps_score = bd["steps"]
    lscore.sleep_score = bd["sleep"]
    lscore.dinner_before_7pm_score = bd["dinner_before_7pm"]
    lscore.alcohol_score = bd["alcohol"]
    lscore.no_food_after_dinner_score = bd["no_food_after_dinner"]
    lscore.stress_score = bd["stress"]
    # [260623] 신규 3항목 점수
    lscore.dinner_type_score = bd["dinner_type"]
    lscore.digestion_score = bd["digestion"]
    lscore.post_meal_exercise_score = bd["post_meal_exercise"]
    lscore.post_meal_walking_bonus = score_result["post_meal_walking_bonus"]
    lscore.score_label = score_result["label"]
    lscore.has_missing_data = score_result["has_missing_data"]

    await db.flush()

    # 6. 어제 점수 (격려 계산용) — 코덱스 P1: "정확히 전날"만 비교 (이전엔 < log_date 의 가장 최근 = 3일 전일 수도 있었음)
    target_yesterday = payload.log_date - timedelta(days=1)
    yest_score_result = await db.execute(
        select(LifestyleScore).where(
            LifestyleScore.user_id == user_id,
            LifestyleScore.score_date == target_yesterday,
        )
    )
    yest_score = yest_score_result.scalar_one_or_none()
    change_from_yesterday = (
        (score_result["total"] - yest_score.total_score)
        if yest_score and yest_score.total_score is not None
        else 0
    )

    # 7. AI 호출 (Daily Feedback) — 캐싱: 데이터 변경 없으면 기존 응답 재사용
    fb_result = await db.execute(
        select(AIFeedback).where(
            AIFeedback.user_id == user_id,
            AIFeedback.feedback_date == payload.log_date,
        )
    )
    fb = fb_result.scalar_one_or_none()

    # [우노 피드백 3] 입력 날짜가 오늘(KST) 기준 7일 초과면 GPT 호출 생략 — 저장만 (비용 절감).
    #   과거 기록을 채워넣어도 일주일 이내 날짜에만 AI 피드백을 제공.
    #   ⚠️ KST 는 함수 하단(예산 분기)에서 지역 재할당되므로 여기선 inline tz 로 계산 (UnboundLocalError 회피).
    _today_kst = datetime.now(timezone(timedelta(hours=9))).date()
    feedback_allowed = (_today_kst - payload.log_date).days <= 7

    # [260722] AI 재생성 카운터 — log_date별 하드리밋(자정 리셋 없음).
    #   오늘=3회, 과거=2회. 진짜 GPT 응답만 카운트.
    #   소진 시 GPT 호출 스킵 + 기존 fb 그대로 응답 + _meta.model='regen_capped'.
    regen_limit = 3 if payload.log_date == _today_kst else 2
    current_count = fb.regen_count if fb else 0

    # [우노 피드백 5] 의료진이 설정한 '현재(최신)' 목표를 AI 입력에 주입하기 위해 미리 로드.
    #   medical_goals는 이력(사용자당 여러 행)이므로 created_at desc + limit(1)로 최신만. (scalar_one_or_none 크래시 방지)
    goal_result = await db.execute(
        select(MedicalGoal)
        .where(MedicalGoal.user_id == user_id)
        .order_by(MedicalGoal.created_at.desc())
        .limit(1)
    )
    goal = goal_result.scalar_one_or_none()

    # [우노 5] 의료진 목표가 '피드백 생성 이후'에 설정/변경됐으면 → 목표를 반영하려 재계산 1회.
    #   goal 은 위에서 최신 1건만 로드. goal.created_at > 기존 피드백 updated_at 이면 목표가 더 최신.
    #   (목표를 나중에 설정해도 그 날 피드백이 한 번 갱신되어 "공복혈당 목표치를 이루기 위해 ~" 워딩이 반영됨)
    goal_newer_than_feedback = (
        goal is not None
        and fb is not None
        and fb.updated_at is not None
        and goal.created_at is not None
        and goal.created_at > fb.updated_at
    )

    # 재사용 가능한 '진짜 GPT' 응답만 캐시 hit 으로 인정.
    #   fallback(키/네트워크 일시 실패)·budget_capped·skipped_old_date 로 저장된 응답은 재사용하지 않고
    #   다음 입력 때 GPT 를 재시도 → "일반 멘트 + (걷기/저녁/수면 중 랜덤) 미션"이 영구 고정되는 문제 방지.
    # [260722] cached / regen_capped 는 모델 문자열은 다르지만 그 자체가 '재사용된 진짜 GPT 응답'이므로
    #   재생성 카운트 증분에서는 제외한다(같은 응답 재제공에 카운트 소진 방지).
    _non_real_models = {None, "", "fallback", "budget_capped", "skipped_old_date", "cached", "regen_capped"}
    fb_is_real_gpt = fb is not None and fb.gpt_model not in _non_real_models

    # [피드백 260714 정리 · Codex 후속1] '목표치 미반영 시 캐시 무효화' 로직 폐지.
    #   기존엔 GPT 응답에 goal.target_fasting_glucose 숫자가 없으면 매번 재생성했으나,
    #   프롬프트 새 규칙("목표치는 마지막 한 줄만, 가끔")과 정면 충돌 → 매 요청마다 재호출·비용 낭비.
    #   사용자 원문 피드백 H2/AI7: "목표치보다는 평소 공복혈당 범위에 대해 주되게 피드백" — 강제 반영 X.

    # 캐시 hit 조건: data_changed=False + 진짜 GPT 응답 + 목표가 더 최신 아님 + message 존재
    cache_hit = (
        feedback_allowed
        and not data_changed
        and fb_is_real_gpt
        and not goal_newer_than_feedback
        and fb.daily_message is not None
        and fb.daily_message != ""
    )

    if not feedback_allowed:
        # 7일 초과 과거 입력 — GPT 미호출. 기존 피드백이 있으면 유지, 없으면 빈 피드백 반환(저장 X).
        ai_result = {
            "message": fb.daily_message if (fb and fb.daily_message) else "",
            "influencing_factors": (fb.influencing_factors if fb else None) or [],
            "encouragement": fb.encouragement if fb else None,
            "mission": {
                "text": fb.daily_mission,
                "type": fb.mission_type,
                "target": fb.mission_target,
                "tip": fb.mission_tip,
            } if (fb and fb.daily_mission) else {},
            "_meta": {"model": "skipped_old_date", "input_tokens": 0, "output_tokens": 0, "cost_krw": 0},
        }
        meta = ai_result["_meta"]
    elif cache_hit:
        # 기존 ai_feedback 그대로 사용 — GPT 호출 X (비용 절감 + 즉시 응답)
        ai_result = {
            "message": fb.daily_message,
            "influencing_factors": fb.influencing_factors or [],
            "encouragement": fb.encouragement,
            "mission": {
                "text": fb.daily_mission,
                "type": fb.mission_type,
                "target": fb.mission_target,
                "tip": fb.mission_tip,
            } if fb.daily_mission else {},
            "_meta": {"model": "cached", "input_tokens": 0, "output_tokens": 0, "cost_krw": 0},
        }
    elif current_count >= regen_limit:
        # [260722] AI 재생성 한도 소진 — GPT 호출 스킵. 기존 fb 그대로 응답 (INSERT/UPDATE도 스킵).
        #   fb 없으면 빈 응답. 이 브랜치에선 regen_count 증가·fb.gpt_model 덮어쓰기 모두 하지 않는다
        #   (원본 real-GPT 응답의 gpt_model 보존 → 다음 요청에서 data 변경 없으면 다시 cache_hit 회복).
        if fb is not None and fb.daily_message:
            ai_result = {
                "message": fb.daily_message,
                "influencing_factors": fb.influencing_factors or [],
                "encouragement": fb.encouragement,
                "mission": {
                    "text": fb.daily_mission,
                    "type": fb.mission_type,
                    "target": fb.mission_target,
                    "tip": fb.mission_tip,
                } if fb.daily_mission else {},
                "_meta": {"model": "regen_capped", "input_tokens": 0, "output_tokens": 0, "cost_krw": 0},
            }
        else:
            ai_result = {
                "message": "",
                "influencing_factors": [],
                "encouragement": None,
                "mission": {},
                "_meta": {"model": "regen_capped", "input_tokens": 0, "output_tokens": 0, "cost_krw": 0},
            }
    else:
        # [P0 코덱스 — 사용자별 일일 AI 한도 ₩50 강제]
        # 같은 사용자가 같은 날(KST) GPT 비용 합산이 한도 초과면 fallback 응답으로 차단.
        # KST 자정 기준 — 사용자 체감 일자와 일치 (UTC 자정은 KST 09:00이라 부적절)
        # [v1.0 버그 fix] 함수 내부 import 제거 — 모듈 상단에서 이미 import됨.
        #   내부 import가 있으면 Python이 timedelta를 로컬 변수로 인식 → line 101의 이른 사용에서
        #   UnboundLocalError 발생.
        KST = timezone(timedelta(hours=9))
        now_kst = datetime.now(KST)
        midnight_kst = datetime(now_kst.year, now_kst.month, now_kst.day, tzinfo=KST)
        midnight_utc = midnight_kst.astimezone(timezone.utc)
        daily_cost_query = await db.execute(
            select(func.coalesce(func.sum(AIFeedback.gpt_cost_krw), 0.0)).where(
                AIFeedback.user_id == user_id,
                AIFeedback.created_at >= midnight_utc,
            )
        )
        daily_cost_used = float(daily_cost_query.scalar() or 0.0)

        # [260705 피드백] 최근 7일 개인 평균 수면 — '수면 부족' 단정 방지 기준 (fallback·AI 프롬프트 공통).
        #   예: 평소 5시간 자는 분이 6.5시간 잤으면 '부족'이 아니라 '푹 잔 날'로 판단해야 함.
        avg_sleep_q = await db.execute(
            select(func.avg(WatchData.sleep_total_minutes)).where(
                WatchData.user_id == user_id,
                WatchData.log_date >= payload.log_date - timedelta(days=7),
                WatchData.log_date < payload.log_date,
                WatchData.sleep_total_minutes.isnot(None),
            )
        )
        _avg_sl = avg_sleep_q.scalar()
        avg_sleep_hours = round(float(_avg_sl) / 60.0, 1) if _avg_sl else None

        # [저녁 마감시간 동적화 · 260726] 예산초과 fallback 경로와 GPT 경로 **양쪽** 에서 필요하므로
        #   분기 밖에서 먼저 계산한다. 이전엔 else(GPT) 블록 안에만 있어서 fallback 경로는
        #   fallback_messages 의 하드코딩 '7시'를 그대로 썼다 — 당봄 버그 제보(7시30분 설정인데 '8시' 안내)의 한 갈래.
        _dl = user_setting.dinner_deadline_time if user_setting and user_setting.dinner_deadline_time else None
        if _dl:
            _ampm = "오전" if _dl.hour < 12 else "오후"
            _h12 = 12 if _dl.hour % 12 == 0 else _dl.hour % 12
            _dinner_deadline_label = f"{_ampm} {_h12}시" + (f" {_dl.minute}분" if _dl.minute else "")
        else:
            _dinner_deadline_label = None

        if daily_cost_used >= float(settings.daily_limit_per_user_krw):
            import logging
            logging.getLogger(__name__).warning(
                f"daily AI budget exceeded user={user_id} used={daily_cost_used}원 limit={settings.daily_limit_per_user_krw}원 → fallback 응답"
            )
            from app.services import fallback_messages
            fb_fallback = fallback_messages.get_fallback_daily(
                score=score_result["total"],
                glucose=payload.fasting_glucose,
                alcohol=payload.alcohol,
                dinner_before_7pm=payload.dinner_before_7pm,
                # [260706 Codex] hours만 보내는 신규 앱 호환 — derive된 nf_bool 사용
                no_food_after_dinner=nf_bool,
                sleep_hours=(watch.sleep_total_minutes / 60.0) if watch and watch.sleep_total_minutes else None,
                change_from_yesterday=change_from_yesterday,
                avg_sleep_hours=avg_sleep_hours,
                # [260726] 하드코딩 '7시' 대신 사용자가 설정한 마감 시각 사용
                dinner_deadline=_dinner_deadline_label,
                # [260728 당봄 피드백 2] 개인 평소 범위를 넘겨 '미만/안/초과'를 구분하게 한다.
                #   이걸 빼면 예산 초과일에만 절대기준(100~130)으로 되돌아가,
                #   평소 120~140인 분의 99 에 대해 "범위보다 낮다"는 핵심 정보를 못 준다.
                #   (GPT 예외 경로 ai_service.py 는 이미 넘기고 있음 — 두 경로를 맞춘다)
                glucose_normal_low=(user.glucose_normal_low if user else None),
                glucose_normal_high=(user.glucose_normal_high if user else None),
            )
            fb_fallback["_meta"] = {"model": "budget_capped", "input_tokens": 0, "output_tokens": 0, "cost_krw": 0}
            ai_result = fb_fallback
        else:
            # user 는 위(4. 점수 산출)에서 이미 로드됨 — 재조회 없이 재사용.

            # [저녁 마감시간 동적화] _dinner_deadline_label 은 위 분기 밖에서 이미 계산됨(중복 제거).

            ai_input = {
                "user": {
                    "age": user.age if user else None,
                    "gender": user.gender if user else None,
                },
                "today_glucose": payload.fasting_glucose,
                "yesterday": {
                    "alcohol": payload.alcohol,
                    "dinner_before_7pm": payload.dinner_before_7pm,
                    # [260706 Codex] hours만 보내는 신규 앱 호환 — derive된 nf_bool 사용
                    "no_food_after_dinner": nf_bool,
                    # [260707] 신규 회고 3항목 — 기록한 값은 AI 분석에도 반영 (한국어 라벨)
                    "dinner_type": (
                        [_DINNER_TYPE_KO.get(t, t) for t in payload.dinner_type]
                        if payload.dinner_type else None
                    ),
                    # [260722] '기타' 선택 시 사용자가 자유입력한 텍스트도 AI에 전달 (dinner_type에 '기타' 포함 시만 의미 있음)
                    "dinner_type_other": payload.dinner_type_other,
                    "digestion": _DIGESTION_KO.get(payload.digestion) if payload.digestion else None,
                    "post_meal_exercise": (
                        _POST_MEAL_EX_KO.get(payload.post_meal_exercise)
                        if payload.post_meal_exercise else None
                    ),
                    "steps": watch.steps if watch else None,
                    "sleep_hours": (watch.sleep_total_minutes / 60.0) if watch and watch.sleep_total_minutes else None,
                    "sleep_deep_minutes": watch.sleep_deep_minutes if watch else None,
                    # [260727] 프롬프트가 요구하는 수면 세부 단계 + 취침 시각.
                    #   이름/단위는 ai_prompts.py 문서화와 1:1 계약 — 임의로 바꾸지 말 것.
                    #   단위: *_minutes = 분, sleep_start_hour = KST 24시간제 실수(23:30 → 23.5).
                    #   watch 가 None(워치 미동기화)이면 전부 None.
                    "sleep_awake_minutes": watch.sleep_awake_minutes if watch else None,
                    "sleep_rem_minutes": watch.sleep_rem_minutes if watch else None,
                    "sleep_light_minutes": watch.sleep_light_minutes if watch else None,
                    "sleep_start_hour": _sleep_start_hour_kst(watch.sleep_start_at) if watch else None,
                    "stress_level": stress.morning_status if stress else None,
                    # [260705] 최근 7일 개인 평균 수면 — AI가 '부족' 판단 시 개인 기준으로 비교
                    "avg_sleep_hours_7d": avg_sleep_hours,
                },
                "score": {
                    "total": score_result["total"],
                    "yesterday_score": yest_score.total_score if yest_score else None,
                    "change_from_yesterday": change_from_yesterday,
                    # [우노] 전날 기록 존재 여부 — false면 AI가 '어제 대비' 점수 비교 없이 독립 피드백
                    #   (빈 날을 나중에 채워도 다음날 피드백을 재계산할 필요가 없게 → 비용 절감)
                    "yesterday_recorded": yest_score is not None,
                },
                # 코덱스 P1: 의료 안전 상태를 AI 입력에 전달 (미션·문구 조정용)
                "medical_safety": {
                    "glucose_status": score_result.get("glucose_safety_status"),
                    "message": score_result.get("glucose_safety_message"),
                    "suppress_mission": score_result.get("suppress_mission", False),
                },
                # [우노 피드백 5] 의료진 목표 — 있으면 "공복혈당 목표치를 이루기 위해 ~" 워딩 유도
                "goal": ({
                    "target_fasting_glucose": goal.target_fasting_glucose,
                    "target_hba1c": goal.target_hba1c,
                    "target_pancreas": goal.target_pancreas,
                    "target_period": goal.target_period,
                    "lifestyle_goals": goal.lifestyle_goals,
                } if goal else None),
                # [저녁 마감시간 동적화] AI가 '7시' 대신 이 시각 기준으로 저녁 식사 코칭
                "dinner_deadline": _dinner_deadline_label,
                # [PDF 2] 개인 공복혈당 범위 — 설정 시 AI가 '평소 범위 대비'로 코칭 가능 (코칭 심화는 추후)
                # [260722] null 정규화 — low/high 중 하나라도 None 이면 range 전체 None (부분 설정 방어).
                "glucose_normal_range": (
                    None
                    if (user is None
                        or user.glucose_normal_low is None
                        or user.glucose_normal_high is None)
                    else {"low": user.glucose_normal_low, "high": user.glucose_normal_high}
                ),
            }

            ai_result = await ai_service.call_daily_feedback(ai_input)
        meta = ai_result.get("_meta", {})

    # [코덱스 P2] cache_hit 분기를 포함해 모든 경로에서 meta 정의 보장 (불변식 명시)
    meta = ai_result.get("_meta", {})

    # [260726][P0 의료 안전] 안전 오버라이드는 여기서 ai_result/fb 를 파괴적으로 고치지 않는다.
    #   이전 구현은 fb.daily_message/mission/encouragement 를 안전문구로 덮어썼는데,
    #   regen_capped·7일초과 경로는 아래 저장 블록을 스킵하므로 혈당을 정상값으로 정정해도
    #   fb 가 복구되지 않아 "저혈당 40" 경고가 하루 종일 고착됐다.
    #   → fb 에는 원본 GPT 응답을 그대로 저장하고, 응답을 만들기 직전에
    #     score_service.apply_safety_override(저장된 log.fasting_glucose 기준)로 계산해 씌운다. (아래 참조)

    # ⚠️ 캐시 hit/miss 둘 다에서 mission 변수가 정의돼야 응답 빌드 가능
    # (코덱스 검토 P0: 캐시 히트 분기에서 mission 미정의 → 500 에러)
    mission = ai_result.get("mission") or {}

    # [260722] regen_capped 응답은 기존 fb 를 절대 덮어쓰지 않는다 (원본 real-GPT 콘텐츠 보존).
    if feedback_allowed and not cache_hit and meta.get("model") != "regen_capped":
        # 8. ai_feedbacks INSERT/UPDATE (캐시 미스에서만 — 7일 초과 과거 입력은 저장 스킵)

        if fb is None:
            fb = AIFeedback(
                user_id=user_id,
                feedback_date=payload.log_date,
            )
            db.add(fb)

        fb.daily_message = ai_result.get("message")
        fb.influencing_factors = ai_result.get("influencing_factors", [])
        fb.encouragement = ai_result.get("encouragement")
        fb.daily_mission = mission.get("text")
        fb.mission_type = mission.get("type")
        fb.mission_target = mission.get("target")
        fb.mission_tip = mission.get("tip")
        # 오늘의 미션은 '어제 행동'으로 채점되므로 출제 시점(오늘)엔 항상 0/미완료.
        #   → 내일 입력이 들어올 때 _update_previous_mission_completion 가 사후 채점.
        fb.mission_progress = 0.0
        fb.mission_completed = False
        fb.gpt_model = meta.get("model")
        fb.gpt_input_tokens = meta.get("input_tokens")
        fb.gpt_output_tokens = meta.get("output_tokens")
        fb.gpt_cost_krw = meta.get("cost_krw")
        # [260722] 진짜 GPT 응답만 재생성 카운트 증가 (fallback/budget_capped 등은 카운트 안 함).
        #   cache_hit/regen_capped 경로는 이 블록 자체가 스킵되므로 자연스레 제외.
        if meta.get("model") not in _non_real_models:
            fb.regen_count = current_count + 1

    # 9. 미션 진행도(어제 미션 사후 채점) + 연속 기록(streak) 기록
    #    - 어제 피드백 미션을 오늘 입력(어제 행동)으로 채점
    #    - 오늘 피드백 행에 주간/월간 연속 기록 일수 기록 (AIFeedback 행이 있을 때만)
    #    ⚠️ feedback_allowed(=GPT 출제 적격) 와 무관하게 항상 실행한다.
    #       백필/7일 초과 과거 입력에서도 어제 미션 완료/연속 기록이 stale 해지지 않도록.
    #       (오늘의 '신규' 미션 생성만 feedback_allowed 게이트에 남아 있음.)
    await _update_previous_mission_completion(
        db, user_id, payload.log_date, payload, watch
    )
    if fb is not None:
        weekly_days, monthly_days = await _compute_log_streaks(
            db, user_id, payload.log_date
        )
        fb.weekly_streak_days = weekly_days
        fb.weekly_streak_total = 7
        fb.monthly_streak_days = monthly_days
        # 응답으로 나가는 mission dict 에도 현재(출제 시점) 진행도를 미러링.
        if isinstance(mission, dict) and mission:
            mission["progress"] = fb.mission_progress
            mission["completed"] = fb.mission_completed

    await db.commit()
    await db.refresh(log)

    # ⭐ CSV 자동 저장 (의료진 공유용) — 실패해도 API 응답 막지 않음
    try:
        csv_writer.save_daily_log(user_id, log)
        csv_writer.save_score(user_id, lscore)
        # AI 피드백 — fb 는 캐시 미스에서만 갱신되지만 캐시 hit 시에도 기존 파일 있을 거니 매번 저장 시도
        if fb is not None:
            csv_writer.save_ai_feedback(user_id, fb)
    except Exception:
        # CSV 저장 실패는 무시 (로깅은 csv_writer 내부)
        pass

    # [260722] AI 재생성 카운터 — 응답 필드 계산 (post-write 상태 기준).
    #   진짜 GPT 응답이 쓰였으면 fb.regen_count 는 이미 current_count+1 로 증가된 상태.
    #   regen_capped/cache_hit/fallback 등은 카운트 변화 없음.
    _final_count = fb.regen_count if fb else 0
    _ai_regen_remaining = max(0, regen_limit - _final_count)
    _ai_regen_locked = _ai_regen_remaining <= 0

    # [260726][P0 의료 안전] 응답 직전에 '저장된' 공복혈당 기준으로 안전 오버라이드 적용.
    #   저장(fb)에는 원본 GPT 응답이 남고, 사용자에게 나가는 값만 안전 문구로 대체된다.
    _ai_out = score_service.apply_safety_override(
        {
            "message": ai_result.get("message", "") or "",
            "influencing_factors": ai_result.get("influencing_factors", []) or [],
            "encouragement": ai_result.get("encouragement"),
            "mission": mission,
        },
        log.fasting_glucose,
    )

    return DailyLogResponse(
        log=DailyLogOut(
            log_date=log.log_date,
            fasting_glucose=log.fasting_glucose,
            alcohol=log.alcohol,
            dinner_before_7pm=log.dinner_before_7pm,
            no_food_after_dinner=log.no_food_after_dinner,
            no_food_after_dinner_hours=log.no_food_after_dinner_hours,
            dinner_type=log.dinner_type,
            dinner_type_other=log.dinner_type_other,
            digestion=log.digestion,
            post_meal_exercise=log.post_meal_exercise,
            input_method=log.input_method,
            can_edit=_can_edit(log.log_date),
            created_at=log.created_at,
        ),
        score=ScoreOut(
            total=score_result["total"],
            label=score_result["label"],
            change_from_yesterday=change_from_yesterday,
            breakdown=ScoreBreakdown(**bd),
            valid_max=score_result.get("valid_max"),
        ),
        ai_feedback=AIFeedbackOut(
            message=_ai_out["message"],
            influencing_factors=_ai_out["influencing_factors"],
            encouragement=_ai_out["encouragement"],
            mission=_ai_out["mission"],
        ),
        ai_regen_remaining=_ai_regen_remaining,
        ai_regen_locked=_ai_regen_locked,
    )


@router.get("/{log_date}", response_model=DailyLogResponse)
async def get_daily_log(
    log_date: date,
    user_id: uuid.UUID = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    """일자별 입력 + 점수 + AI 피드백 — POST 응답과 동일한 구조"""
    result = await db.execute(
        select(DailyLog).where(
            DailyLog.user_id == user_id,
            DailyLog.log_date == log_date,
        )
    )
    log = result.scalar_one_or_none()
    if not log:
        raise HTTPException(status_code=404, detail={"error": "NOT_FOUND", "message": "기록 없음"})

    # 같은 날 점수
    score_row_result = await db.execute(
        select(LifestyleScore).where(
            LifestyleScore.user_id == user_id,
            LifestyleScore.score_date == log_date,
        )
    )
    lscore = score_row_result.scalar_one_or_none()

    # 어제 점수 (change_from_yesterday 계산용) — 코덱스 P1: "정확히 전날"만 비교
    target_prev = log_date - timedelta(days=1)
    yest_score_result = await db.execute(
        select(LifestyleScore).where(
            LifestyleScore.user_id == user_id,
            LifestyleScore.score_date == target_prev,
        )
    )
    yest_score = yest_score_result.scalar_one_or_none()
    change_from_yesterday = (
        (lscore.total_score - yest_score.total_score)
        if lscore and lscore.total_score is not None and yest_score and yest_score.total_score is not None
        else 0
    )

    score_out = None
    if lscore is not None:
        # ⚠️ null을 0으로 변환하면 "미입력"과 "0점"이 구분되지 않음 (FE에서 잘못 표시).
        # breakdown의 각 항목은 None 그대로 전달 → FE가 "미입력 / max"로 안내.
        # [260706 Codex] valid_max = 저장된 세부점수 중 관측된 항목의 만점 합 (부분 측정일 표시 근거)
        _stored = {
            "glucose": lscore.glucose_score,
            "steps": lscore.steps_score,
            "sleep": lscore.sleep_score,
            "dinner_before_7pm": lscore.dinner_before_7pm_score,
            "alcohol": lscore.alcohol_score,
            "no_food_after_dinner": lscore.no_food_after_dinner_score,
            "stress": lscore.stress_score,
            "dinner_type": lscore.dinner_type_score,
            "digestion": lscore.digestion_score,
            "post_meal_exercise": lscore.post_meal_exercise_score,
        }
        _valid_max = sum(score_service.WEIGHTS_MAX[k] for k, v in _stored.items() if v is not None)
        score_out = ScoreOut(
            total=lscore.total_score or 0,
            label=lscore.score_label or "",
            change_from_yesterday=change_from_yesterday,
            breakdown=ScoreBreakdown(
                glucose=lscore.glucose_score,
                steps=lscore.steps_score,
                steps_post_meal_bonus=lscore.post_meal_walking_bonus,
                sleep=lscore.sleep_score,
                dinner_before_7pm=lscore.dinner_before_7pm_score,
                alcohol=lscore.alcohol_score,
                no_food_after_dinner=lscore.no_food_after_dinner_score,
                stress=lscore.stress_score,
                dinner_type=lscore.dinner_type_score,
                digestion=lscore.digestion_score,
                post_meal_exercise=lscore.post_meal_exercise_score,
            ),
            valid_max=_valid_max if _valid_max > 0 else None,
        )

    # 같은 날 AI 피드백
    fb_result = await db.execute(
        select(AIFeedback).where(
            AIFeedback.user_id == user_id,
            AIFeedback.feedback_date == log_date,
        )
    )
    fb = fb_result.scalar_one_or_none()
    ai_out = None
    if fb is not None:
        # POST 응답과 동일하게 진행도/완료 미러링 + (ai_feedback.py/today 와 동일 shape) streak 포함.
        #   이전엔 progress/completed/streak 누락 → 같은 미션 카드가 GET 으로 받으면 항상 0%/미달성으로 보였음.
        mission = {
            "text": fb.daily_mission,
            "type": fb.mission_type,
            "target": fb.mission_target,
            "tip": fb.mission_tip,
            "progress": fb.mission_progress,
            "completed": fb.mission_completed,
            "streak": {
                "weekly_completed_days": fb.weekly_streak_days,
                "weekly_total_days": fb.weekly_streak_total,
                "monthly_completed_days": fb.monthly_streak_days,
            },
        } if fb.daily_mission else None
        # [260726][P0 의료 안전] 저장된 공복혈당 기준으로 안전 오버라이드 적용 (POST 응답과 동일 규칙).
        #   저장된 fb 는 원본 GPT 응답이므로, 위험 구간이면 여기서 안전 문구로 대체한다.
        #   혈당을 정상값으로 정정하면 이 판정이 다시 통과해 원래 피드백이 그대로 보인다.
        _safe = score_service.apply_safety_override(
            {
                "message": fb.daily_message or "",
                "influencing_factors": fb.influencing_factors or [],
                "encouragement": fb.encouragement,
                "mission": mission,
            },
            log.fasting_glucose,
        )
        ai_out = AIFeedbackOut(
            message=_safe["message"],
            influencing_factors=_safe["influencing_factors"],
            encouragement=_safe["encouragement"],
            mission=_safe["mission"],
        )

    # [260722] AI 재생성 카운터 — GET에도 노출 (앱이 캘린더에서 과거 날짜 조회 시 남은 횟수 확인용).
    _today_kst_get = datetime.now(timezone(timedelta(hours=9))).date()
    _regen_limit_get = 3 if log_date == _today_kst_get else 2
    _current_count_get = fb.regen_count if fb else 0
    _ai_regen_remaining_get = max(0, _regen_limit_get - _current_count_get)
    _ai_regen_locked_get = _ai_regen_remaining_get <= 0

    return DailyLogResponse(
        log=DailyLogOut(
            log_date=log.log_date,
            fasting_glucose=log.fasting_glucose,
            alcohol=log.alcohol,
            dinner_before_7pm=log.dinner_before_7pm,
            no_food_after_dinner=log.no_food_after_dinner,
            no_food_after_dinner_hours=log.no_food_after_dinner_hours,
            dinner_type=log.dinner_type,
            dinner_type_other=log.dinner_type_other,
            digestion=log.digestion,
            post_meal_exercise=log.post_meal_exercise,
            input_method=log.input_method,
            can_edit=_can_edit(log.log_date),
            created_at=log.created_at,
        ),
        score=score_out,
        ai_feedback=ai_out,
        ai_regen_remaining=_ai_regen_remaining_get,
        ai_regen_locked=_ai_regen_locked_get,
    )


@router.get("", response_model=list[dict])
async def list_daily_logs(
    from_: date = Query(..., alias="from"),
    to: date = Query(...),
    user_id: uuid.UUID = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    """기간 조회 — 캘린더용 (간단)"""
    log_result = await db.execute(
        select(DailyLog).where(
            DailyLog.user_id == user_id,
            DailyLog.log_date >= from_,
            DailyLog.log_date <= to,
        ).order_by(DailyLog.log_date)
    )
    logs = log_result.scalars().all()

    score_result = await db.execute(
        select(LifestyleScore).where(
            LifestyleScore.user_id == user_id,
            LifestyleScore.score_date >= from_,
            LifestyleScore.score_date <= to,
        )
    )
    scores = {s.score_date: s.total_score for s in score_result.scalars().all()}

    return [
        {
            "log_date": log.log_date.isoformat(),
            "has_glucose": log.fasting_glucose is not None,
            "has_reflection": log.alcohol is not None,
            "score": scores.get(log.log_date),
            # 당봄 #16 — 공복혈당 그래프용 실제 수치 + 회고 데이터 반환
            "fasting_glucose": log.fasting_glucose,
            "alcohol": log.alcohol,
            "dinner_before_7pm": log.dinner_before_7pm,
            "no_food_after_dinner": log.no_food_after_dinner,
            # [260623] 신규 회고 4항목도 목록 응답에 포함 (상세/캘린더 진입 전 shape 일관성)
            "no_food_after_dinner_hours": log.no_food_after_dinner_hours,
            "dinner_type": log.dinner_type,
            # [260722] '기타' 자유입력 텍스트 — 상세/캘린더 진입 전에도 노출되도록 목록 shape 에 포함
            "dinner_type_other": log.dinner_type_other,
            "digestion": log.digestion,
            "post_meal_exercise": log.post_meal_exercise,
        }
        for log in logs
    ]
