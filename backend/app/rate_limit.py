"""간단한 in-memory rate limiter.

[v1.0 보안 B3] check-email enumeration, signup brute force 방어.
nginx 측 rate limit 없는 환경에서도 동작.

주의:
- 단일 프로세스 메모리에 카운터 보관 — workers > 1이면 통계 분산됨
  (uvicorn workers=2면 실효 limit이 2배). 보수적으로 절반 값 설정 권장.
- nginx 뒤에서 동작 시 X-Forwarded-For 헤더로 실제 client IP 확인.
"""
from __future__ import annotations

import time
from collections import defaultdict, deque
from typing import Callable

from fastapi import HTTPException, Request

from app.config import settings

# IP별 요청 timestamp 큐
_request_log: dict[str, deque] = defaultdict(deque)


def _get_client_ip(request: Request) -> str:
    """nginx 뒤에서 실제 client IP 추출."""
    forwarded = request.headers.get("x-forwarded-for")
    if forwarded:
        return forwarded.split(",")[0].strip()
    if request.client:
        return request.client.host
    return "unknown"


def rate_limit(max_requests: int, window_seconds: int) -> Callable:
    """FastAPI Depends로 사용하는 rate limit factory.

    예: dependencies=[Depends(rate_limit(30, 60))]  → 분당 30회/IP
    """
    def dependency(request: Request) -> None:
        # production 외 환경은 우회 (개발 편의)
        if settings.app_env != "production":
            return

        ip = _get_client_ip(request)
        now = time.time()
        log = _request_log[ip]

        # 윈도우 밖 timestamp 제거
        while log and log[0] < now - window_seconds:
            log.popleft()

        if len(log) >= max_requests:
            raise HTTPException(
                status_code=429,
                detail={
                    "error": "RATE_LIMIT",
                    "message": "요청이 너무 많습니다. 잠시 후 다시 시도해주세요.",
                },
            )

        log.append(now)

    return dependency
