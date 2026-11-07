"""[staging] 시드한 데이터로 '공복혈당 달력' + '소화 2.5 총점'을 검증 출력.

build_glucose_calendar(달력 핵심 로직)를 staging DB의 실제 데이터로 호출해
  - 일자별 혈당/상태(high/low/in_range)/요인
  - 공통 요인 TOP3, 범위 벗어난 N/총일
  - LifestyleScore.total/ digestion_score (소화 2.5가 총점에 반영되는지)
를 콘솔에 보여준다. (HTTP/인증 없이 DB만으로 검증)

사용: DANGBOM_ENV=staging python scripts/verify_staging.py
"""
import asyncio
import os
import sys
import uuid
from datetime import date, timedelta

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))  # 백엔드 루트

from sqlalchemy import select  # noqa: E402

from app import models  # noqa: E402
from app.database import _get_engine, get_async_session_factory  # noqa: E402
from app.routers.reports import build_glucose_calendar  # noqa: E402
from scripts._staging_guard import assert_few_users, ensure_staging  # noqa: E402

TEST_USER = uuid.UUID("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")


async def main() -> None:
    ensure_staging()
    today = date.today()
    week_start = today - timedelta(days=today.weekday()) - timedelta(days=7)
    week_end = week_start + timedelta(days=6)
    Session = get_async_session_factory()
    async with Session() as db:
        await assert_few_users(db)   # 운영 DB면 읽기 거부(프라이버시)
        user = await db.get(models.User, TEST_USER)
        if user is None:
            print("[중단] 테스트 유저가 없습니다. 먼저 seed_staging.py 를 실행하세요.")
            sys.exit(1)
        logs = (await db.execute(
            select(models.DailyLog).where(
                models.DailyLog.user_id == TEST_USER,
                models.DailyLog.log_date >= week_start,
                models.DailyLog.log_date <= week_end,
            )
        )).scalars().all()
        watch = {w.log_date: w for w in (await db.execute(
            select(models.WatchData).where(models.WatchData.user_id == TEST_USER)
        )).scalars().all()}
        scores = (await db.execute(
            select(models.LifestyleScore).where(models.LifestyleScore.user_id == TEST_USER)
            .order_by(models.LifestyleScore.score_date)
        )).scalars().all()

    cal = build_glucose_calendar(logs, watch, user.glucose_normal_low, user.glucose_normal_high)

    print("=" * 56)
    print(f"공복혈당 달력 검증 — {week_start} ~ {week_end}")
    print(f"평소 범위: {cal['usual_range']}  ·  범위 벗어남: {cal['out_of_range_count']}/{cal['recorded_days']}일")
    print("-" * 56)
    print(" 날짜         혈당  상태       요인")
    for d in cal["days"]:
        print(f" {d['date']}  {d['glucose']:>3}  {d['status']:<9} {d['factors']}")
    print("-" * 56)
    print(f" 높음 {cal['high_count']}일 · 낮음 {cal['low_count']}일 · 범위 안 {cal['in_range_count']}일")
    print(" 혈당을 높인 요인 TOP3:")
    for r in cal["high_reasons"]:
        print(f"   - {r['label']} · {r['count']}일")
    print(" 혈당을 낮춘 요인 TOP3:")
    for r in cal["low_reasons"]:
        print(f"   - {r['label']} · {r['count']}일")
    print("=" * 56)
    print(" 소화 2.5 → 총점 반영 확인 (LifestyleScore):")
    print("  날짜          total   소화점수")
    for s in scores:
        mark = "  ← 소화 partial(2.5)" if s.digestion_score == 2.5 else ""
        print(f"  {s.score_date}   {s.total_score:>5}    {s.digestion_score}{mark}")
    print("=" * 56)
    print("✓ status 색(high=빨강/low=파랑/in_range=회색), 요인, 공통이유, 2.5 총점이 위처럼 나오면 정상.")

    await _get_engine().dispose()


if __name__ == "__main__":
    if sys.platform == "win32":
        asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())
    asyncio.run(main())
