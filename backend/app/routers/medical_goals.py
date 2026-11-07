"""의료진 목표 (우노 피드백 4) — 현재/이력 조회 + 저장(append-only 이력).

PIN 잠금은 클라이언트에서 처리한다(환자는 보기 전용). 백엔드는 인증된 본인 토큰으로
목표를 '새 이력 row'로 저장한다. "현재 목표" = 가장 최근 row. 과거 목표는 /history 로 조회.
5번(AI 피드백)은 daily_logs 에서 가장 최근 목표를 읽어 프롬프트에 주입한다.
"""
from __future__ import annotations

import uuid

from fastapi import APIRouter, Depends
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.deps import get_current_user_id
from app.models import MedicalGoal
from app.schemas import MedicalGoalOut, MedicalGoalUpdateRequest

router = APIRouter(prefix="/api/medical-goals", tags=["medical_goals"])

# 비교/저장에 쓰는 5개 '목표' 항목. set_by(의료진 이름)는 콘텐츠 판정/중복 판정에서 제외 —
# 이름만 바뀐 경우 새 이력 안 만들고, 이름이 있어도 목표가 비어있으면 '내용 없음'으로 취급.
_GOAL_FIELDS = ("target_hba1c", "target_pancreas", "target_fasting_glucose", "target_period", "lifestyle_goals")


def _to_out(goal: MedicalGoal | None) -> MedicalGoalOut:
    if goal is None:
        return MedicalGoalOut(has_goal=False)
    has = any(
        [
            goal.target_hba1c is not None,
            goal.target_pancreas is not None,
            goal.target_fasting_glucose is not None,
            bool(goal.target_period),
            bool(goal.lifestyle_goals),
        ]
    )
    return MedicalGoalOut(
        target_hba1c=goal.target_hba1c,
        target_pancreas=goal.target_pancreas,
        target_fasting_glucose=goal.target_fasting_glucose,
        target_period=goal.target_period,
        lifestyle_goals=goal.lifestyle_goals,
        set_by=goal.set_by,
        created_at=goal.created_at,
        updated_at=goal.updated_at,
        has_goal=has,
    )


async def _latest(db: AsyncSession, user_id: uuid.UUID) -> MedicalGoal | None:
    res = await db.execute(
        select(MedicalGoal)
        .where(MedicalGoal.user_id == user_id)
        .order_by(MedicalGoal.created_at.desc())
        .limit(1)
    )
    return res.scalar_one_or_none()


@router.get("", response_model=MedicalGoalOut)
async def get_medical_goal(
    user_id: uuid.UUID = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    """현재 목표 = 가장 최근 이력 (홈/현재 표시용, PIN 불필요)."""
    return _to_out(await _latest(db, user_id))


@router.get("/history", response_model=list[MedicalGoalOut])
async def get_medical_goal_history(
    user_id: uuid.UUID = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    """과거 목표 이력 — 최신순([0]=현재). 앱에서 ◀▶로 조회."""
    res = await db.execute(
        select(MedicalGoal)
        .where(MedicalGoal.user_id == user_id)
        .order_by(MedicalGoal.created_at.desc())
    )
    return [_to_out(g) for g in res.scalars().all()]


@router.put("", response_model=MedicalGoalOut)
async def create_medical_goal(
    payload: MedicalGoalUpdateRequest,
    user_id: uuid.UUID = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    """의료진 입력 — 매 저장마다 새 이력 row 추가 (과거 목표 보존). 클라이언트 PIN 확인 후 호출."""
    data = payload.model_dump()  # 5개 목표 항목 + set_by(의료진 이름)
    latest = await _latest(db, user_id)

    # 내용이 전혀 없고(전부 None/blank) 이전 이력도 없으면 빈 row 만들지 않음
    has_content = any(
        [
            data.get("target_hba1c") is not None,
            data.get("target_pancreas") is not None,
            data.get("target_fasting_glucose") is not None,
            bool(data.get("target_period")),
            bool(data.get("lifestyle_goals")),
        ]
    )
    if not has_content and latest is None:
        return _to_out(None)

    # 직전 이력과 5개 목표 항목이 완전히 동일하면 새 이력 안 만듦.
    # [Codex 리뷰 #2] 목표는 같고 set_by(의료진 이름)만 바뀐 경우 — 새 이력은 안 만들되
    #   최신 row의 set_by는 갱신해서 앱에서 이름 표시가 즉시 반영되게 함.
    #   기존엔 그대로 return _to_out(latest)라 이름 변경이 silently dropped 됐음.
    if latest is not None and all(getattr(latest, k) == data.get(k) for k in _GOAL_FIELDS):
        new_set_by = data.get("set_by")
        if new_set_by != latest.set_by:
            latest.set_by = new_set_by
            await db.commit()
            await db.refresh(latest)
        return _to_out(latest)

    goal = MedicalGoal(user_id=user_id, **data)
    db.add(goal)
    await db.flush()
    await db.commit()
    await db.refresh(goal)
    return _to_out(goal)
