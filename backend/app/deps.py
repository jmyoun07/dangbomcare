"""의존성 — Supabase JWT 검증 + 현재 사용자

지원:
- 신규 비대칭(ES256/RS256) — Supabase의 새로운 JWT Signing Keys (JWKS 사용)
- 레거시 HS256 — SUPABASE_JWT_SECRET 사용 (구 시스템 호환)
"""
import uuid

import jwt
from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from jwt import PyJWKClient
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.database import get_db


security = HTTPBearer()

# JWKS 엔드포인트 — 신규 비대칭 키 검증용
_jwks_client: PyJWKClient | None = None


def _get_jwks_client() -> PyJWKClient:
    """JWKS 클라이언트 lazy init + 캐싱 (1시간)"""
    global _jwks_client
    if _jwks_client is None:
        jwks_url = f"{settings.supabase_url.rstrip('/')}/auth/v1/.well-known/jwks.json"
        _jwks_client = PyJWKClient(jwks_url, cache_keys=True, lifespan=3600)
    return _jwks_client


def _verify_token(token: str) -> dict:
    """JWT 검증 — 신규(ES256/RS256) → 레거시(HS256) 폴백"""
    # 헤더에서 알고리즘 추출
    try:
        header = jwt.get_unverified_header(token)
        alg = header.get("alg", "")
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail={"error": "INVALID_TOKEN", "message": f"토큰 헤더 파싱 실패: {e}"},
        )

    # 1) 신규 비대칭 키 (ES256, RS256, EdDSA 등)
    if alg in ("ES256", "RS256", "EdDSA"):
        try:
            jwks_client = _get_jwks_client()
            signing_key = jwks_client.get_signing_key_from_jwt(token).key
            # [P1 보안] issuer 검증 + 필수 claim require 추가 (HS256 경로와 동일 수준)
            supabase_url = (settings.supabase_url or "").rstrip("/")
            return jwt.decode(
                token,
                signing_key,
                algorithms=[alg],
                audience="authenticated",
                issuer=f"{supabase_url}/auth/v1" if supabase_url else None,
                options={"require": ["exp", "iat", "aud", "sub"]},
            )
        except jwt.ExpiredSignatureError:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail={"error": "TOKEN_EXPIRED", "message": "토큰이 만료되었습니다"},
            )
        except Exception as e:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail={"error": "INVALID_TOKEN", "message": f"비대칭 JWT 검증 실패: {e}"},
            )

    # 2) 레거시 HS256
    if alg == "HS256":
        # [Codex 보안 검토 H11] secret 빈값 방어 — 환경변수 누락 시 인증 우회 위험.
        # 빈 secret으로 jwt.decode를 호출하면 일부 라이브러리는 검증을 건너뛸 수 있음.
        secret = settings.supabase_jwt_secret
        if not secret or len(secret) < 16:
            raise HTTPException(
                status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
                detail={"error": "SERVER_CONFIG", "message": "JWT secret이 설정되지 않았습니다 (관리자에게 문의)"},
            )

        try:
            # [Codex H11] issuer 검증 추가 (Supabase 표준 issuer 형태)
            expected_issuer = f"{settings.supabase_url.rstrip('/')}/auth/v1"
            return jwt.decode(
                token,
                secret,
                algorithms=["HS256"],
                audience="authenticated",
                issuer=expected_issuer,
                options={"require": ["exp", "iat", "aud", "sub"]},
            )
        except jwt.ExpiredSignatureError:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail={"error": "TOKEN_EXPIRED", "message": "토큰이 만료되었습니다"},
            )
        except jwt.InvalidTokenError as e:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail={"error": "INVALID_TOKEN", "message": f"HS256 JWT 검증 실패: {e}"},
            )

    raise HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail={"error": "INVALID_TOKEN", "message": f"지원하지 않는 알고리즘: {alg}"},
    )


async def get_current_user_id(
    creds: HTTPAuthorizationCredentials = Depends(security),
) -> uuid.UUID:
    """Supabase JWT 검증 → user_id 반환"""
    payload = _verify_token(creds.credentials)

    sub = payload.get("sub")
    if not sub:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail={"error": "INVALID_TOKEN", "message": "토큰에 사용자 ID 없음"},
        )

    try:
        return uuid.UUID(sub)
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail={"error": "INVALID_TOKEN", "message": "사용자 ID 형식 오류"},
        )


# 편의 별칭
CurrentUser = Depends(get_current_user_id)
DBSession = Depends(get_db)


__all__ = ["get_current_user_id", "get_db", "CurrentUser", "DBSession", "AsyncSession"]
