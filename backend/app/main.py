"""당봄케어 백엔드 메인 (FastAPI) — v1.0.0"""
from __future__ import annotations

import logging

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from fastapi.exceptions import RequestValidationError
from sqlalchemy import text

from app.config import settings
from app.database import _get_engine
from app.routers import (
    ai_feedback,
    app_config,
    auth,
    csv_export,
    daily_logs,
    daily_summary,
    medical_goals,
    reports,
    scores,
    users,
    watch_data,
)

logging.basicConfig(level=logging.INFO if settings.app_env == "production" else logging.DEBUG)
logger = logging.getLogger(__name__)


_is_production = settings.app_env == "production" and not settings.app_debug

# [P1 보안] production에서 /docs, /redoc, /openapi.json 비활성화
app = FastAPI(
    title="당봄케어 API",
    version="1.0.0",
    description="당봄한의원 환자 전용 당뇨 관리 앱 (당봄케어) 백엔드",
    docs_url=None if _is_production else "/docs",
    redoc_url=None if _is_production else "/redoc",
    openapi_url=None if _is_production else "/openapi.json",
)

# CORS
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origins_list,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


# 라우터 등록
app.include_router(auth.router)
app.include_router(users.router)
app.include_router(daily_logs.router)
app.include_router(watch_data.router)
app.include_router(scores.router)
app.include_router(ai_feedback.router)
app.include_router(reports.router)
app.include_router(daily_summary.router)
app.include_router(medical_goals.router)
app.include_router(csv_export.router)
app.include_router(app_config.router)  # [260904] GET /api/app/version-policy (강제 업데이트, 인증 없음)


@app.exception_handler(RequestValidationError)
async def validation_exception_handler(request, exc):
    """입력 검증 실패 시 일관된 에러 형식"""
    errors = exc.errors()
    first_error = errors[0] if errors else {}
    loc = first_error.get("loc", [])
    msg = first_error.get("msg", "Invalid input")

    field = loc[-1] if loc else "unknown"

    error_code = "INVALID_INPUT"
    error_message = f"{field}: {msg}"

    if "fasting_glucose" in str(loc):
        error_code = "GLUCOSE_OUT_OF_RANGE"
        error_message = "공복혈당은 10~600 mg/dL 범위로 입력해주세요"
    elif "height_cm" in str(loc):
        error_code = "HEIGHT_OUT_OF_RANGE"
        error_message = "키는 100~250cm 범위로 입력해주세요"
    elif "weight_kg" in str(loc):
        error_code = "WEIGHT_OUT_OF_RANGE"
        error_message = "체중은 20~200kg 범위로 입력해주세요"

    # [P1 보안] _detail 필드는 입력값을 노출하므로 production에서 제거
    response_content: dict = {"error": error_code, "message": error_message}
    if settings.app_debug:
        response_content["_detail"] = errors
    return JSONResponse(
        status_code=422,
        content=response_content,
    )


@app.get("/")
async def root():
    return {
        "app": settings.app_name,
        "version": "1.0.0",
        "env": settings.app_env,
        "docs": "/docs",
    }


@app.get("/healthz")
async def health_check():
    """[P1] systemd가 실제 가용성을 감지하려면 DB ping 포함."""
    db_ok = False
    try:
        engine = _get_engine()
        async with engine.connect() as conn:
            await conn.execute(text("SELECT 1"))
        db_ok = True
    except Exception as e:
        logger.error(f"healthz DB ping failed: {e}")

    status_code = 200 if db_ok else 503
    return JSONResponse(
        status_code=status_code,
        content={
            "status": "ok" if db_ok else "degraded",
            "db": "ok" if db_ok else "down",
        },
    )
