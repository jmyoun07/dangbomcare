"""점수 조회"""
from __future__ import annotations

import uuid
from datetime import date, datetime, timedelta, timezone

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy import desc, func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.deps import get_current_user_id
from app.models import DailyLog, LifestyleScore
from app.schemas import ScoreBreakdown, ScoreTodayResponse
from app.services.score_service import glucose_safety_status, should_suppress_mission

router = APIRouter(prefix="/api/scores", tags=["scores"])

KST = timezone(timedelta(hours=9))


@router.get("/today", response_model=ScoreTodayResponse)
async def get_today_score(
    user_id: uuid.UUID = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    today = datetime.now(KST).date()

    result = await db.execute(
        select(LifestyleScore).where(
            LifestyleScore.user_id == user_id,
            LifestyleScore.score_date == today,
        )
    )
    today_score = result.scalar_one_or_none()

    yest_result = await db.execute(
        select(LifestyleScore).where(
            LifestyleScore.user_id == user_id,
            LifestyleScore.score_date == today - timedelta(days=1),
        )
    )
    yest_score = yest_result.scalar_one_or_none()

    # 주간 평균
    week_start = today - timedelta(days=7)
    weekly_avg_result = await db.execute(
        select(func.avg(LifestyleScore.total_score)).where(
            LifestyleScore.user_id == user_id,
            LifestyleScore.score_date >= week_start,
            LifestyleScore.score_date <= today,
        )
    )
    weekly_avg = weekly_avg_result.scalar()

    if not today_score:
        raise HTTPException(status_code=404, detail={"error": "NOT_FOUND", "message": "오늘 점수 산출 전. 아침 입력을 완료해주세요."})

    # ⚠️ 코덱스 P1: total_score == 0 도 정상값이므로 `is not None` 으로 체크 (truthiness bug 수정)
    change = (
        today_score.total_score - yest_score.total_score
        if (yest_score and yest_score.total_score is not None and today_score.total_score is not None)
        else None
    )
    arrow = (
        "up" if (change is not None and change > 0)
        else "down" if (change is not None and change < 0)
        else "same"
    )

    encouragement = None
    if change is not None and change >= 5:
        encouragement = f"전날보다 {change:g}점 올랐어요. 훌륭해요!"  # :g — 소수점 .0 제거

    # ── [260729 통합검증 P1] 의료 안전 격려 억제 ───────────────────────────
    #   `apply_safety_override`(score_service.py L171-173)는 "비정상 혈당에 '점수 올랐어요'
    #   격려가 새어나가지 않도록" encouragement 를 None 으로 만드는 방어층을 명시적으로 둔다.
    #   그런데 이 엔드포인트는 AI 피드백을 거치지 않고 LifestyleScore 만으로 **같은 문구를
    #   독립 생성**해서 그 방어층 밖에 있었다. AI 문구를 노출하는 다른 6개 경로
    #   (POST/GET daily-logs, GET ai-feedback/today, CSV 3종)는 전부 override 를 태운다.
    #
    #   재현: 어제 워치 미동기화(수면20+걸음10+스트레스5 = 35점 실점) → 오늘 워치 동기화 +
    #     공복혈당 260 mg/dL. change=+30 이라 점수 카드는 "전날보다 30점 올랐어요. 훌륭해요!",
    #     바로 옆 AI 카드는 "⚠️ 공복혈당 260 mg/dL : 평소보다 매우 높게 측정됐어요."
    #   → 같은 화면에서 정반대 신호. 판정은 반드시 override 와 **같은 함수**로 한다
    #     (기준이 갈리면 또 층마다 다른 말을 한다).
    if encouragement is not None:
        glu_result = await db.execute(
            select(DailyLog.fasting_glucose).where(
                DailyLog.user_id == user_id,
                DailyLog.log_date == today,
            )
        )
        _fg = glu_result.scalar_one_or_none()
        _status = glucose_safety_status(_fg)
        if should_suppress_mission(_status, _fg) or (_status is not None and _status != "normal"):
            encouragement = None

    return ScoreTodayResponse(
        score_date=today_score.score_date,
        total_score=today_score.total_score or 0,
        label=today_score.score_label or "보통",
        breakdown=ScoreBreakdown(
            glucose=today_score.glucose_score,
            steps=today_score.steps_score,
            steps_post_meal_bonus=today_score.post_meal_walking_bonus,
            sleep=today_score.sleep_score,
            dinner_before_7pm=today_score.dinner_before_7pm_score,
            alcohol=today_score.alcohol_score,
            no_food_after_dinner=today_score.no_food_after_dinner_score,
            stress=today_score.stress_score,
            # [260623] 점수 재배분 신규 3항목 (상세 화면 '오늘' 뷰가 이 엔드포인트 사용)
            dinner_type=today_score.dinner_type_score,
            digestion=today_score.digestion_score,
            post_meal_exercise=today_score.post_meal_exercise_score,
        ),
        yesterday_score=yest_score.total_score if yest_score else None,
        score_change_arrow=arrow,
        weekly_avg=float(weekly_avg) if weekly_avg else None,
        has_missing_data=today_score.has_missing_data,
        encouragement_message=encouragement,
    )


@router.get("/{score_date}")
async def get_score_by_date(
    score_date: date,
    user_id: uuid.UUID = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    result = await db.execute(
        select(LifestyleScore).where(
            LifestyleScore.user_id == user_id,
            LifestyleScore.score_date == score_date,
        )
    )
    s = result.scalar_one_or_none()
    if not s:
        raise HTTPException(status_code=404, detail={"error": "NOT_FOUND", "message": "점수 없음"})
    return {
        "score_date": s.score_date.isoformat(),
        "total_score": s.total_score,
        "label": s.score_label,
        "breakdown": {
            "glucose": s.glucose_score,
            "steps": s.steps_score,
            "sleep": s.sleep_score,
            "dinner_before_7pm": s.dinner_before_7pm_score,
            "alcohol": s.alcohol_score,
            "no_food_after_dinner": s.no_food_after_dinner_score,
            "stress": s.stress_score,
            # [260623] 점수 재배분 신규 3항목
            "dinner_type": s.dinner_type_score,
            "digestion": s.digestion_score,
            "post_meal_exercise": s.post_meal_exercise_score,
        },
    }


@router.get("")
async def list_scores(
    from_: date = Query(..., alias="from"),
    to: date = Query(...),
    user_id: uuid.UUID = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    result = await db.execute(
        select(LifestyleScore).where(
            LifestyleScore.user_id == user_id,
            LifestyleScore.score_date >= from_,
            LifestyleScore.score_date <= to,
        ).order_by(LifestyleScore.score_date)
    )
    scores = result.scalars().all()

    # [260623 피드백] 캘린더 셀에 점수 대신 '공복혈당' 수치를 표시 → 같은 기간 DailyLog 공복혈당도 함께 반환.
    #   (색은 프론트에서 점수 기반 유지. score_date == 공복혈당 측정일이라 같은 날짜로 매핑.)
    log_result = await db.execute(
        select(DailyLog.log_date, DailyLog.fasting_glucose).where(
            DailyLog.user_id == user_id,
            DailyLog.log_date >= from_,
            DailyLog.log_date <= to,
        )
    )
    glucose_map = {row[0]: row[1] for row in log_result.all()}

    # [코덱스 P1] 점수 행이 없어도 공복혈당이 입력된 날은 캘린더에 혈당이 보이도록 union 반환.
    #   (점수 미산출/실패한 날도 혈당 숫자는 표시. 색은 점수 기반이라 점수 없으면 무색.)
    scores_by_date = {s.score_date: s for s in scores}
    all_dates = sorted(set(scores_by_date) | set(glucose_map))
    return [
        {
            "score_date": d.isoformat(),
            "total_score": scores_by_date[d].total_score if d in scores_by_date else None,
            "label": scores_by_date[d].score_label if d in scores_by_date else None,
            "fasting_glucose": glucose_map.get(d),
        }
        for d in all_dates
    ]
