"""일일 종합 ([리포트] [일일] 탭)

날짜 계약 (Date Contract)
─────────────────────────────────────
- 점수(score)는 "오늘 점수 = 어제 생활"이라 daily_logs에서 어제 워치 기준으로 계산됨 → LifestyleScore 저장 (그대로 사용).
- [260623 피드백] 이 화면의 표시용 데이터는 점수 산출 기준과 맞춘다:
  - score / DailyLog (공복혈당, 회고) → summary_date
  - WatchData / StressData (수면/걸음/스트레스) → summary_date - 1 (전날 = 그날 공복혈당을 만든 생활)

근거: 공복혈당은 '전날 생활'의 결과이므로, 그 옆에 보이는 걸음/수면/스트레스도 전날 것이어야 일관됨.
예) 6/4 화면 = 6/4 공복혈당/회고(전날 저녁 내용) + 6/3 워치 데이터. (점수 48도 6/3 생활 기준으로 산출)
"""
from __future__ import annotations

import uuid
from datetime import date, timedelta

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.deps import get_current_user_id
from app.models import DailyLog, LifestyleScore, StressData, WatchData

router = APIRouter(prefix="/api/daily-summary", tags=["daily_summary"])


@router.get("/{summary_date}")
async def get_daily_summary(
    summary_date: date,
    user_id: uuid.UUID = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    # [260623 피드백] 기록 화면의 걸음/수면/스트레스는 '전날(summary_date-1)' 데이터를 보여준다.
    #   이유: 전날 생활이 그날 아침 공복혈당·점수를 만들기 때문 → 화면 데이터를 점수 산출 기준과 일치시킴.
    #   (점수/공복혈당/회고는 summary_date 기준 유지. 회고는 아침 입력 시점상 이미 '전날 저녁' 내용.)
    watch_date = summary_date - timedelta(days=1)

    score_result = await db.execute(
        select(LifestyleScore).where(
            LifestyleScore.user_id == user_id,
            LifestyleScore.score_date == summary_date,
        )
    )
    score = score_result.scalar_one_or_none()

    log_result = await db.execute(
        select(DailyLog).where(
            DailyLog.user_id == user_id,
            DailyLog.log_date == summary_date,
        )
    )
    log = log_result.scalar_one_or_none()

    # WatchData: 전날(watch_date) 데이터 조회
    watch_result = await db.execute(
        select(WatchData).where(
            WatchData.user_id == user_id,
            WatchData.log_date == watch_date,
        )
    )
    watch = watch_result.scalar_one_or_none()

    # StressData: 전날(watch_date) 데이터 조회
    stress_result = await db.execute(
        select(StressData).where(
            StressData.user_id == user_id,
            StressData.log_date == watch_date,
        )
    )
    stress = stress_result.scalar_one_or_none()

    # [v1.0 피드백] 데이터가 전혀 없어도 404 대신 200으로 nulls 반환.
    #   → 사용자가 캘린더에서 과거 날짜를 눌렀을 때 "기록 없음"이 아니라
    #     날짜 헤더 + 각 카드의 sync 안내가 보여서 워치 데이터를 동기화할 수 있게 함.

    glucose_value = log.fasting_glucose if log else None
    # [260623 피드백] 당뇨인 3구간 — 정상(<100) / 당뇨 목표 범위(100~130) / 높음(>130).
    #   당뇨약 복용 중인 환자에겐 100~130이 목표 범위라 '공복혈당 장애(전단계)' 표현은 부적절.
    glucose_status = "정상"
    if glucose_value is not None:
        if glucose_value < 70:
            glucose_status = "낮음"
        elif glucose_value < 100:
            glucose_status = "정상"
        elif glucose_value <= 130:
            glucose_status = "당뇨 목표 범위"
        else:
            glucose_status = "높음"

    return {
        "date": summary_date.isoformat(),
        "summary_date": summary_date.isoformat(),  # 점수/회고/공복혈당 기준일
        "watch_date": watch_date.isoformat(),  # 워치/스트레스 기준일 (= summary_date - 1)
        "score": {
            "total": score.total_score if score else None,
            "label": score.score_label if score else None,
            "breakdown": {
                "glucose": score.glucose_score if score else None,
                "steps": score.steps_score if score else None,
                "sleep": score.sleep_score if score else None,
                "dinner_before_7pm": score.dinner_before_7pm_score if score else None,
                "alcohol": score.alcohol_score if score else None,
                "no_food_after_dinner": score.no_food_after_dinner_score if score else None,
                "stress": score.stress_score if score else None,
                "dinner_type": score.dinner_type_score if score else None,
                "digestion": score.digestion_score if score else None,
                "post_meal_exercise": score.post_meal_exercise_score if score else None,
            },
        } if score else None,
        "glucose": {
            "value": glucose_value,
            "status": glucose_status,
            "target_range": [100, 130],  # [260623] 당뇨인 목표 범위(100~130). 저혈당 하한(70)·정상(<100)은 status로 구분.
            "input_time": log.fasting_glucose_time.isoformat() if log and log.fasting_glucose_time else None,
        } if log else None,
        "activity": {
            "steps": watch.steps if watch else None,
            "sleep_hours": round(watch.sleep_total_minutes / 60, 1) if watch and watch.sleep_total_minutes else None,
            "sleep_stages": {
                "deep_minutes": watch.sleep_deep_minutes,
                "light_minutes": watch.sleep_light_minutes,
                "rem_minutes": watch.sleep_rem_minutes,
                "awake_minutes": watch.sleep_awake_minutes,
                "labels_senior": {
                    "deep": "체력 회복 잠",
                    "light": "얕은 잠",
                    "rem": "뇌 휴식하는 잠",
                    "awake": "자다 깬 시간",
                },
            } if watch and watch.sleep_total_minutes else None,
            "stress": stress.morning_status if stress else None,
            "spo2_avg": watch.spo2_avg if watch else None,
            "spo2_min": watch.spo2_min if watch else None,
        } if (watch or stress) else None,  # [코덱스 P1] 스트레스만 있는 날도 activity 생성 (스트레스 누락 방지)
        "reflection": {
            "alcohol": log.alcohol if log else None,
            "dinner_before_7pm": log.dinner_before_7pm if log else None,
            "no_food_after_dinner": log.no_food_after_dinner if log else None,
            # [260623] 신규 회고 4항목
            "no_food_after_dinner_hours": log.no_food_after_dinner_hours if log else None,
            "dinner_type": log.dinner_type if log else None,
            # [260720] '기타' 선택 시 자유입력 텍스트
            "dinner_type_other": log.dinner_type_other if log else None,
            "digestion": log.digestion if log else None,
            "post_meal_exercise": log.post_meal_exercise if log else None,
        } if log else None,
    }
