"""인증 보조 엔드포인트 — Supabase Auth 보완용.

⚠️ DEV/DEMO 전용:
- /dev-auto-confirm: SMTP 미설정 환경에서 회원가입 직후 이메일 인증을 자동 처리.
  운영 단계에서는 SMTP 설정 후 반드시 비활성화.
  보안 규칙: AUTO_CONFIRM_EMAIL=true 가 명시적으로 설정된 경우에만 작동.
              APP_ENV=production 이면 환경변수와 무관하게 항상 404 반환.
"""
from __future__ import annotations

import logging
import os

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, EmailStr
from sqlalchemy import text

from app.config import settings
from app.database import _get_session_factory
from app.rate_limit import rate_limit

router = APIRouter(prefix="/api/auth", tags=["auth"])
logger = logging.getLogger(__name__)


class AutoConfirmRequest(BaseModel):
    email: EmailStr


@router.get("/check-email", dependencies=[Depends(rate_limit(30, 60))])
async def check_email(email: str):
    """[v1.0] 이메일 중복 확인 — 회원가입 실시간 검증용.

    응답: { "exists": bool }

    [코덱스 P0/P1 검토 반영]
      - 의료 앱 특성상 이메일 enumeration은 PHI(보호대상건강정보) 추정 위험.
      - 'confirmed' 필드 제거: '미인증 가입자' 식별 → 피싱 표적 가능성 차단.
      - 'exists'는 v1.0 UX 요구로 유지 (시니어 친화 — 가입 후 에러보다 사전 안내).
      - 출시 시 nginx rate limit 필수: 분당 30회/IP 정도로 자동화 enumeration 차단.
      - v1.1: 가입 confirm 화면으로 통합해 사전 노출 자체를 제거 검토.
    """
    email_norm = (email or "").strip().lower()
    if not email_norm:
        return {"exists": False}

    session_factory = _get_session_factory()
    async with session_factory() as db:
        result = await db.execute(
            text("SELECT 1 FROM auth.users WHERE email = :email LIMIT 1"),
            {"email": email_norm},
        )
        row = result.fetchone()
        return {"exists": row is not None}


@router.post("/dev-auto-confirm", dependencies=[Depends(rate_limit(10, 60))])
async def dev_auto_confirm(payload: AutoConfirmRequest):
    """
    회원가입 직후 호출. Supabase Free Tier built-in SMTP가 Naver/Daum 등
    한국 ESP로 메일을 보내지 못하는 문제를 회피하기 위한 데모용 우회.

    [Codex 보안 검토 반영]
    - 기본값을 `false`로 변경 (이전 기본 `true`는 운영 환경에서 이메일 인증 우회 위험)
    - production 환경에서는 환경변수와 무관하게 항상 404 반환
    - 활성화하려면 .env에 AUTO_CONFIRM_EMAIL=true 명시 + APP_ENV != production
    """
    # production 환경에서는 항상 endpoint 자체 비공개 처리
    if settings.app_env == "production":
        raise HTTPException(404, {"error": "NOT_FOUND", "message": "Not Found"})

    # 비프로덕션이라도 명시적 opt-in 없으면 차단
    if os.getenv("AUTO_CONFIRM_EMAIL", "false").lower() not in ("true", "1", "yes"):
        raise HTTPException(404, {"error": "NOT_FOUND", "message": "Not Found"})

    email = str(payload.email).strip().lower()
    if not email:
        raise HTTPException(400, {"error": "BAD_REQUEST", "message": "email is required"})

    session_factory = _get_session_factory()
    async with session_factory() as db:
        # 사용자가 실제로 존재하는지 먼저 확인 (임의 이메일 confirm 방지)
        result = await db.execute(
            text("SELECT id, email_confirmed_at FROM auth.users WHERE email = :email"),
            {"email": email},
        )
        row = result.fetchone()
        if row is None:
            raise HTTPException(404, {"error": "NOT_FOUND", "message": "해당 이메일로 가입된 사용자가 없습니다."})

        # 이미 인증된 경우 noop
        if row[1] is not None:
            return {"ok": True, "already_confirmed": True}

        await db.execute(
            text("UPDATE auth.users SET email_confirmed_at = NOW(), confirmed_at = NOW() WHERE email = :email"),
            {"email": email},
        )
        await db.commit()
        logger.info(f"[auto-confirm] {email} 이메일 인증 완료")

    return {"ok": True, "already_confirmed": False}
