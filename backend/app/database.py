"""SQLAlchemy 2.0 async + Supabase PostgreSQL"""
from collections.abc import AsyncGenerator
from typing import Optional

from sqlalchemy.ext.asyncio import AsyncEngine, AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.orm import DeclarativeBase

from app.config import settings


class Base(DeclarativeBase):
    """모든 모델의 베이스 클래스"""
    pass


# 지연 초기화 — DATABASE_URL이 빈 값일 때도 import는 성공해야 함
_engine: Optional[AsyncEngine] = None
_session_factory: Optional[async_sessionmaker[AsyncSession]] = None


def _get_engine() -> AsyncEngine:
    global _engine
    if _engine is None:
        if not settings.database_url:
            raise RuntimeError(
                "DATABASE_URL이 설정되지 않았습니다. .env 파일에 다음 형식으로 추가해주세요:\n"
                "DATABASE_URL=postgresql+asyncpg://postgres.PROJECT_ID:PASSWORD@aws-1-ap-northeast-2.pooler.supabase.com:5432/postgres"
            )
        # [P0 보안] production에서는 SQL echo 강제 차단 (사용자/건강 데이터 평문 노출 방지)
        is_production = settings.app_env.lower() == "production"
        # [P1 코덱스] Supabase free tier 60 connection 한도 + workers=2 고려
        # workers=2 × (pool=3 + overflow=2) = 최대 10 connection (안전 마진 큼)
        _engine = create_async_engine(
            settings.database_url,
            echo=settings.app_debug and not is_production,
            pool_pre_ping=True,
            pool_size=3,
            max_overflow=2,
            pool_timeout=10,
            pool_recycle=300,
        )
    return _engine


def _get_session_factory() -> async_sessionmaker[AsyncSession]:
    global _session_factory
    if _session_factory is None:
        _session_factory = async_sessionmaker(
            _get_engine(),
            class_=AsyncSession,
            expire_on_commit=False,
            autoflush=False,
        )
    return _session_factory


def get_async_session_factory() -> async_sessionmaker[AsyncSession]:
    """배치/서비스에서 FastAPI 의존성 밖에서 세션을 직접 열 때 사용.

    (sync sessionmaker는 없음 — 드라이버가 asyncpg async-only)
    """
    return _get_session_factory()


async def get_db() -> AsyncGenerator[AsyncSession, None]:
    """FastAPI 의존성 — DB 세션 주입"""
    session_factory = _get_session_factory()
    async with session_factory() as session:
        try:
            yield session
            await session.commit()
        except Exception:
            await session.rollback()
            raise
        finally:
            await session.close()
