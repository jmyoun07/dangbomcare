"""AI 피드백 조회"""
from __future__ import annotations

import uuid
from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.deps import get_current_user_id
from app.models import AIFeedback, DailyLog
from app.schemas import AIFeedbackTodayResponse
from app.services import score_service

router = APIRouter(prefix="/api/ai-feedback", tags=["ai_feedback"])

KST = timezone(timedelta(hours=9))


@router.get("/today", response_model=AIFeedbackTodayResponse)
async def get_today_feedback(
    user_id: uuid.UUID = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    today = datetime.now(KST).date()
    result = await db.execute(
        select(AIFeedback).where(
            AIFeedback.user_id == user_id,
            AIFeedback.feedback_date == today,
        )
    )
    fb = result.scalar_one_or_none()
    if not fb:
        raise HTTPException(status_code=404, detail={"error": "NOT_FOUND", "message": "오늘 AI 해석 없음. 아침 입력을 완료해주세요."})

    # 코덱스 P2: daily_mission이 없으면 mission 자체를 None (빈 카드 방지)
    mission = (
        {
            "type": fb.mission_type,
            "text": fb.daily_mission,
            "target": fb.mission_target,
            "progress": fb.mission_progress,
            "completed": fb.mission_completed,
            "tip": fb.mission_tip,
        }
        if fb.daily_mission
        else None
    )

    # [260726][P0 의료 안전] 오늘 공복혈당 기준으로 안전 오버라이드 적용.
    #   ai_feedbacks 행에는 원본 GPT 응답만 저장되므로(파괴적 write 폐지), 노출 직전에 반드시 재판정해야 한다.
    #   이 호출이 빠지면 저혈당 날 홈 화면 AI 카드에 원본 GPT 문구와 운동 미션이 그대로 노출된다.
    today_log = (await db.execute(
        select(DailyLog).where(
            DailyLog.user_id == user_id,
            DailyLog.log_date == today,
        )
    )).scalar_one_or_none()
    safe = score_service.apply_safety_override(
        {
            "message": fb.daily_message,
            "influencing_factors": fb.influencing_factors,
            "encouragement": fb.encouragement,
            "mission": mission,
        },
        today_log.fasting_glucose if today_log else None,
    )
    # [260722] AI 재생성 카운터 — /today는 오늘 조회이므로 limit=3.
    #   앱은 remaining/locked 로 "수정은 최대 2회 가능합니다.(남은 횟수: N회)" 표시.
    regen_limit = 3  # feedback_date == today_kst (라우터 정의상 항상 오늘)
    current_count = fb.regen_count or 0
    ai_regen_remaining = max(0, regen_limit - current_count)
    ai_regen_locked = ai_regen_remaining <= 0

    return AIFeedbackTodayResponse(
        feedback_date=fb.feedback_date,
        daily_message=safe["message"],
        influencing_factors=safe["influencing_factors"],
        encouragement=safe["encouragement"],
        mission=safe["mission"],
        streak={
            "weekly_completed_days": fb.weekly_streak_days,
            "weekly_total_days": fb.weekly_streak_total,
            "monthly_completed_days": fb.monthly_streak_days,
        },
        ai_regen_remaining=ai_regen_remaining,
        ai_regen_locked=ai_regen_locked,
    )
