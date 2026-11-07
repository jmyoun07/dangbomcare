"""앱 설정 공개 엔드포인트 — 강제 업데이트 정책 (인증 불필요)

[260904] 한의원 문의(걸음수 미수신) 대응 라운드.
  앱은 시작(Splash) 시 이 엔드포인트를 읽어 자기 versionCode 가 min_version_code 보다 낮으면
  업데이트 화면에서 멈춘다. 값은 app_config 테이블(key/value) — Supabase 대시보드에서 바꾸면
  서버 재배포 없이 즉시 반영된다.

  fail-open 원칙: 테이블이 없거나 조회가 실패하면 "막지 않음"(min=0)을 돌려준다.
  앱도 이 호출이 실패/타임아웃이면 그냥 정상 진입한다. 업데이트 게이트 때문에 앱이 먹통이 되면 안 된다.
"""
from __future__ import annotations

import logging

from fastapi import APIRouter, Depends
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.models import AppConfig
from app.schemas import AppVersionPolicyOut

router = APIRouter(prefix="/api/app", tags=["app"])
logger = logging.getLogger(__name__)

_DEFAULT_STORE_URL = "https://play.google.com/store/apps/details?id=com.dangbomcare.app"
_DEFAULT_MESSAGE = "더 정확한 걸음·수면 기록을 위해 앱을 최신 버전으로 업데이트해 주세요."


def _to_int(v: str | None, default: int) -> int:
    try:
        return int(str(v).strip())
    except (TypeError, ValueError):
        return default


@router.get("/version-policy", response_model=AppVersionPolicyOut)
async def get_version_policy(db: AsyncSession = Depends(get_db)):
    """앱 최소/최신 버전 정책. 인증 없음(로그인 전 Splash 에서 호출)."""
    cfg: dict[str, str] = {}
    try:
        rows = await db.execute(select(AppConfig.key, AppConfig.value))
        cfg = {k: v for k, v in rows.all()}
    except Exception as e:  # 테이블 미생성·일시 장애 → 막지 않음
        logger.warning("app_config 조회 실패 — 업데이트 강제 없이 응답: %s", e)
        try:
            await db.rollback()
        except Exception:
            pass

    return AppVersionPolicyOut(
        min_version_code=_to_int(cfg.get("min_version_code"), 0),
        latest_version_code=_to_int(cfg.get("latest_version_code"), 0),
        store_url=cfg.get("store_url") or _DEFAULT_STORE_URL,
        message=cfg.get("update_message") or _DEFAULT_MESSAGE,
    )
