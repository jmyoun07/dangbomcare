"""워치 데이터 동기화 (Health Connect → 백엔드)"""
from __future__ import annotations

import uuid
from datetime import date, datetime, timedelta, timezone

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import delete, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.deps import get_current_user_id
from app.models import AIFeedback, DailyLog, LifestyleScore, StressData, User, WatchData
from app.schemas import WatchDataCreate, WatchDataOut
from app.services import csv_writer, score_service, stress_service

router = APIRouter(prefix="/api/watch-data", tags=["watch_data"])

KST = timezone(timedelta(hours=9))


@router.post("", status_code=201)
async def upsert_watch_data(
    payload: WatchDataCreate,
    user_id: uuid.UUID = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    """워치 데이터 업로드 (Health Connect에서 fetch한 데이터)"""
    result = await db.execute(
        select(WatchData).where(
            WatchData.user_id == user_id,
            WatchData.log_date == payload.log_date,
        )
    )
    wd = result.scalar_one_or_none()

    if wd is None:
        wd = WatchData(user_id=user_id, log_date=payload.log_date)
        db.add(wd)

    # ⚠️ Codex 보안 검토 (Critical #4): partial update 적용
    # Health Connect 권한 부분 누락 / 일시 동기화 실패 시 일부 필드만 비어 있을 수 있음.
    # 이전엔 그대로 덮어써서 기존에 저장된 좋은 데이터가 None/0으로 손실됐음.
    # 이제는 payload에 값이 있을 때만 갱신, None이면 기존 값 보존.
    #
    # [v1.0 회귀 fix — 2026-06-01]
    # 이전 fix (걸음수 full replace)가 다른 회귀 야기 — 신규 sync에서 일부 날짜 HC 데이터 없을 때
    # null 전송이 백엔드의 기존 좋은 데이터를 덮어쓰는 부작용.
    # 테스터 시나리오: 앱 재설치 → backfill 30일 → 일부 날짜 HC에 데이터 없음 → null 전송 →
    # 이전에 잘 들어있던 걸음 데이터 손실. → 다시 partial update로 복구.
    # (첫 sync 0 영구 보존 문제는 v1.1에 WatchDataRequest schema에
    #  data_explicitly_missing 필드 추가 방식으로 정식 해결.)
    if payload.steps is not None:
        wd.steps = payload.steps
    if payload.steps_hourly is not None:
        wd.steps_hourly = payload.steps_hourly

    # 수면: 모든 필드가 None일 가능성 (워치 안 차고 잠) — 부분 갱신
    if payload.sleep_total_minutes is not None:
        wd.sleep_total_minutes = payload.sleep_total_minutes
    if payload.sleep_deep_minutes is not None:
        wd.sleep_deep_minutes = payload.sleep_deep_minutes
    if payload.sleep_light_minutes is not None:
        wd.sleep_light_minutes = payload.sleep_light_minutes
    if payload.sleep_rem_minutes is not None:
        wd.sleep_rem_minutes = payload.sleep_rem_minutes
    if payload.sleep_awake_minutes is not None:
        wd.sleep_awake_minutes = payload.sleep_awake_minutes
    if payload.sleep_start_at is not None:
        wd.sleep_start_at = payload.sleep_start_at
    if payload.sleep_end_at is not None:
        wd.sleep_end_at = payload.sleep_end_at

    # 심박: HRV 권한 누락 가능 — 부분 갱신
    if payload.avg_heart_rate is not None:
        wd.avg_heart_rate = payload.avg_heart_rate
    if payload.min_heart_rate is not None:
        wd.min_heart_rate = payload.min_heart_rate
    if payload.max_heart_rate is not None:
        wd.max_heart_rate = payload.max_heart_rate
    if payload.heart_rate_samples is not None:
        wd.heart_rate_samples = payload.heart_rate_samples

    # SpO2: 자주 누락 — 부분 갱신
    if payload.spo2_avg is not None:
        wd.spo2_avg = payload.spo2_avg
    if payload.spo2_min is not None:
        wd.spo2_min = payload.spo2_min

    # last_synced_at은 항상 갱신 (이번 동기화 시각)
    wd.last_synced_at = payload.last_synced_at or datetime.now(KST)

    await db.flush()

    # 스트레스 자체 추정 (심박수 변동성)
    if payload.heart_rate_samples:
        time_buckets = stress_service.estimate_stress_by_time_of_day(payload.heart_rate_samples)
        overall = stress_service.estimate_stress_from_hr(payload.heart_rate_samples)

        stress_result = await db.execute(
            select(StressData).where(
                StressData.user_id == user_id,
                StressData.log_date == payload.log_date,
            )
        )
        stress = stress_result.scalar_one_or_none()
        if stress is None:
            stress = StressData(user_id=user_id, log_date=payload.log_date)
            db.add(stress)

        stress.morning_status = time_buckets.get("morning_status")
        stress.afternoon_status = time_buckets.get("afternoon_status")
        stress.evening_status = time_buckets.get("evening_status")
        stress.below_avg_ratio = overall.get("below_avg_ratio")
        stress.avg_variability = overall.get("avg_variability")

    await db.flush()

    # ── 같은 날짜 daily_log 있으면 점수 자동 재산출 (워치 데이터 반영) ──
    # 점수의 시간 매핑: daily_log(오늘) + watch_data(어제)
    # 그러므로 watch가 어제 데이터면, 오늘 날짜의 daily_log를 재산출 대상으로 함
    score_recalculated = False
    post_meal_bonus_calc = 0  # 코덱스 P1: 재계산 시점에 보너스 캡쳐 (응답 정확)
    next_day = payload.log_date + timedelta(days=1)
    log_result = await db.execute(
        select(DailyLog).where(
            DailyLog.user_id == user_id,
            DailyLog.log_date == next_day,
        )
    )
    next_day_log = log_result.scalar_one_or_none()
    if next_day_log and next_day_log.fasting_glucose is not None:
        # 스트레스 데이터 (같은 날짜 = 어제)
        stress_q = await db.execute(
            select(StressData).where(
                StressData.user_id == user_id,
                StressData.log_date == payload.log_date,
            )
        )
        stress = stress_q.scalar_one_or_none()

        # [PDF 2] 개인 공복혈당 범위 — 채점 기준선 (워치 데이터 수신 시 점수 재산출에도 동일 적용)
        user_q = await db.execute(select(User).where(User.id == user_id))
        user_row = user_q.scalar_one_or_none()
        score_data = {
            "fasting_glucose": next_day_log.fasting_glucose,
            "steps": wd.steps,
            "steps_hourly": wd.steps_hourly,
            "sleep_minutes": wd.sleep_total_minutes,
            "alcohol": next_day_log.alcohol,
            "dinner_before_7pm": next_day_log.dinner_before_7pm,
            "no_food_after_dinner": next_day_log.no_food_after_dinner,
            # [260623] 신규 회고 4항목 — 누락 시 워치 싱크가 총점에서 최대 20점(식사유형5+소화5+식후운동10)을
            #   날리고 식후금식을 bool fallback으로 깎아버려 아침 입력값과 어긋남. daily_logs.py와 동일하게 포함.
            "no_food_after_dinner_hours": next_day_log.no_food_after_dinner_hours,
            "dinner_type": next_day_log.dinner_type,
            "digestion": next_day_log.digestion,
            "post_meal_exercise": next_day_log.post_meal_exercise,
            "stress_below_avg_ratio": stress.below_avg_ratio if stress else None,
            "glucose_normal_low": user_row.glucose_normal_low if user_row else None,
            "glucose_normal_high": user_row.glucose_normal_high if user_row else None,
            "meal_times": {"breakfast": 8.0, "lunch": 12.5, "dinner": 18.5},
        }
        score_result = score_service.calculate_score(score_data)

        # lifestyle_scores 업데이트 (점수 카드 = 오늘 날짜)
        score_q = await db.execute(
            select(LifestyleScore).where(
                LifestyleScore.user_id == user_id,
                LifestyleScore.score_date == next_day,
            )
        )
        lscore = score_q.scalar_one_or_none()
        if lscore is not None:
            bd = score_result["breakdown"]
            lscore.total_score = score_result["total"]
            lscore.glucose_score = bd["glucose"]
            lscore.steps_score = bd["steps"]
            lscore.sleep_score = bd["sleep"]
            lscore.dinner_before_7pm_score = bd["dinner_before_7pm"]
            lscore.alcohol_score = bd["alcohol"]
            lscore.no_food_after_dinner_score = bd["no_food_after_dinner"]
            lscore.stress_score = bd["stress"]
            # [260623] 신규 3항목 점수도 함께 갱신 (안 하면 total과 breakdown 합이 어긋남)
            lscore.dinner_type_score = bd["dinner_type"]
            lscore.digestion_score = bd["digestion"]
            lscore.post_meal_exercise_score = bd["post_meal_exercise"]
            lscore.post_meal_walking_bonus = score_result["post_meal_walking_bonus"]
            lscore.score_label = score_result["label"]
            lscore.has_missing_data = score_result["has_missing_data"]
            score_recalculated = True
            post_meal_bonus_calc = score_result["post_meal_walking_bonus"]

    # [사용자 피드백] AI 피드백이 자동 동기화 (30분 주기) 때마다 삭제되어
    # 홈 화면에서 AI 피드백 카드가 사라지는 문제 발생.
    # 이전 정책 (점수 재계산 시 AI 삭제 → 다음 호출에 재생성)은
    # 자동 동기화가 켜진 환경에선 카드가 항상 비어 보이는 부작용.
    #
    # 새 정책: AI 피드백은 다음 "아침 입력" 시에만 갱신.
    # 자동 동기화의 부분 점수 재계산은 AI 문구와 작은 차이 허용.
    # (의료진 공유 시점에서는 score만 신뢰, AI 문구는 reflection-time best-effort)
    _ = score_recalculated  # 임시: stale AI 삭제 비활성화 (위 주석 참조)

    await db.commit()
    await db.refresh(wd)

    # ⭐ CSV 자동 저장 (의료진 공유용) — 실패해도 API 응답 막지 않음
    try:
        csv_writer.save_watch_data(user_id, wd)
        # 스트레스 데이터도 같이 저장 (생성됐다면)
        if payload.heart_rate_samples:
            stress_q2 = await db.execute(
                select(StressData).where(
                    StressData.user_id == user_id,
                    StressData.log_date == payload.log_date,
                )
            )
            stress_row = stress_q2.scalar_one_or_none()
            if stress_row:
                csv_writer.save_stress(user_id, stress_row)
        # 점수 재산출됐다면 score CSV 도 갱신
        if score_recalculated and next_day_log:
            csv_writer.save_score(user_id, lscore)
    except Exception:
        # CSV 저장 실패는 무시 (로깅은 csv_writer 내부에서 함)
        pass

    return {
        "id": str(wd.id),
        "synced_at": wd.last_synced_at.isoformat() if wd.last_synced_at else None,
        # 코덱스 P1: 재계산된 식후 걷기 보너스를 응답에 정확히 반영 (이전엔 항상 0이었음)
        "post_meal_walking_bonus": post_meal_bonus_calc,
        "score_recalculated": score_recalculated,
    }


@router.get("/{log_date}", response_model=WatchDataOut)
async def get_watch_data(
    log_date: date,
    user_id: uuid.UUID = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    result = await db.execute(
        select(WatchData).where(
            WatchData.user_id == user_id,
            WatchData.log_date == log_date,
        )
    )
    wd = result.scalar_one_or_none()
    if not wd:
        raise HTTPException(status_code=404, detail={"error": "NOT_FOUND", "message": "워치 데이터 없음"})

    # 동기화 상태
    now = datetime.now(KST)
    if wd.last_synced_at:
        last_sync = wd.last_synced_at.replace(tzinfo=KST) if wd.last_synced_at.tzinfo is None else wd.last_synced_at
        diff_h = (now - last_sync).total_seconds() / 3600
        sync_status = "stale" if diff_h > 1 else "ok"
    else:
        sync_status = "missing"

    return WatchDataOut(
        log_date=wd.log_date,
        steps=wd.steps,
        sleep={
            "total_minutes": wd.sleep_total_minutes,
            "deep_minutes": wd.sleep_deep_minutes,
            "light_minutes": wd.sleep_light_minutes,
            "rem_minutes": wd.sleep_rem_minutes,
            "awake_minutes": wd.sleep_awake_minutes,
            "start_at": wd.sleep_start_at.isoformat() if wd.sleep_start_at else None,
            "end_at": wd.sleep_end_at.isoformat() if wd.sleep_end_at else None,
            "labels_senior": {
                "deep": "체력 회복 잠",
                "light": "얕은 잠",
                "rem": "뇌 휴식하는 잠",
                "awake": "자다 깬 시간",
            },
        } if wd.sleep_total_minutes else None,
        heart_rate={
            "avg": wd.avg_heart_rate,
            "min": wd.min_heart_rate,
            "max": wd.max_heart_rate,
        } if wd.avg_heart_rate else None,
        spo2={
            "avg": wd.spo2_avg,
            "min": wd.spo2_min,
        } if wd.spo2_avg else None,
        last_synced_at=wd.last_synced_at,
        sync_status=sync_status,
    )
