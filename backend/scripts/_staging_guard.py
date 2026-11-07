"""staging 전용 스크립트 안전 가드 — 운영(.env) DB에 절대 안 붙게 막는다.

DANGBOM_ENV 미설정/production 이거나 app_env=production 이면 즉시 중단.
"""
import os
import sys

from app.config import settings


def _mask(url: str) -> str:
    try:
        after = url.split("://", 1)[1]
        creds, host = after.split("@", 1)
        return creds.split(":", 1)[0] + "@" + host.split("/", 1)[0]   # 비밀번호 버림
    except Exception:
        return "(unparseable)"


def ensure_staging() -> None:
    env = os.getenv("DANGBOM_ENV", "").strip().lower()
    if env in ("", "prod", "production") or settings.app_env.lower() == "production":
        print("[중단] 이 스크립트는 staging 전용입니다. 운영 DB 보호를 위해 실행을 막습니다.")
        print("  → DANGBOM_ENV=staging 로 실행했는지, .env.staging(APP_ENV=staging)이 있는지 확인하세요.")
        print(f"  (현재: DANGBOM_ENV={env or '(미설정)'}, app_env={settings.app_env}, db={_mask(settings.database_url)})")
        sys.exit(1)
    print(f"[STAGING] db={_mask(settings.database_url)}")


# ── DB 타깃 검증 (env 가드만으로는 '운영 DATABASE_URL을 .env.staging에 잘못 넣은 경우'를 못 막음) ──
async def assert_db_empty(conn) -> None:
    """init 전용 — 앱 핵심 테이블('users')이 이미 있으면 중단.

    create_all은 '빈 DB'에만 전체 스키마를 만든다(기존 테이블엔 신규 컬럼/CHECK를 추가 못 함).
    그래서 ① 구 스키마 재사용 ② 실수로 운영 URL 입력 둘 다 여기서 fail-closed로 잡힌다.
    """
    from sqlalchemy import text
    r = await conn.execute(text(
        "SELECT 1 FROM information_schema.tables "
        "WHERE table_schema='public' AND table_name='users' LIMIT 1"
    ))
    if r.first() is not None:
        raise SystemExit(
            "[중단] 이미 'users' 테이블이 있는 DB입니다. init_staging_db(create_all)은 '빈 staging DB' 전용입니다.\n"
            "  - 운영/기존 DB라면 절대 실행 금지 (스키마 변경은 migrations/ SQL로).\n"
            "  - staging을 다시 만들려면 빈 Supabase 프로젝트를 새로 쓰세요."
        )


async def assert_few_users(session, max_users: int = 3) -> None:
    """seed/verify 전용 — users가 많으면(=운영 의심) 중단."""
    from sqlalchemy import func, select
    from app import models
    n = (await session.execute(select(func.count()).select_from(models.User))).scalar() or 0
    if n > max_users:
        raise SystemExit(
            f"[중단] users 테이블에 {n}명이 있습니다(> {max_users}). 운영 DB로 의심되어 거부합니다. "
            f"db={_mask(settings.database_url)}"
        )
