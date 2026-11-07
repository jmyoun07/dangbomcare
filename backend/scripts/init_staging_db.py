"""[staging] 빈 DB에 전체 스키마 생성 — 모델 기준 create_all (마이그레이션 불필요).

신규 컬럼(소화/저녁유형/식후운동 등)도 모델에 이미 있으니 한 방에 최신 스키마가 만들어진다.
사용: 백엔드 폴더에서
    DANGBOM_ENV=staging python scripts/init_staging_db.py     (bash)
    $env:DANGBOM_ENV='staging'; python scripts/init_staging_db.py   (PowerShell)
"""
import asyncio
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))  # 백엔드 루트

from app import models  # noqa: F401,E402  (모든 모델을 Base.metadata에 등록)
from app.database import Base, _get_engine  # noqa: E402
from scripts._staging_guard import assert_db_empty, ensure_staging  # noqa: E402


async def main() -> None:
    ensure_staging()
    engine = _get_engine()
    try:
        async with engine.begin() as conn:
            await assert_db_empty(conn)   # 빈 DB 아니면 중단(운영/구스키마 오접속 차단)
            await conn.run_sync(Base.metadata.create_all)
        print(f"[OK] 전체 테이블 생성 완료 — {len(Base.metadata.tables)}개:")
        for t in Base.metadata.tables:
            print(f"     - {t}")
        print("다음: DANGBOM_ENV=staging python scripts/seed_staging.py")
    finally:
        await engine.dispose()


if __name__ == "__main__":
    if sys.platform == "win32":
        asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())
    asyncio.run(main())
