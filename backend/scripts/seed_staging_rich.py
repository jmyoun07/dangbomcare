"""[staging] 실계정용 리치 시드 — 5월 1주(5/18~24) + 6월 한 달 + 7월 초(1~3,5).

높음(빨강)/낮음(파랑)/범위안(회색)/미기록일을 골고루 섞어 주간·월간 달력 데모용.
기존 5/17~7/6 데이터는 삭제 후 재삽입(멱등). 유저 행은 보존.

사용: DANGBOM_ENV=staging python scripts/seed_staging_rich.py <auth-user-uuid>
"""
import asyncio
import os
import sys
import uuid
from datetime import date, timedelta

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))  # 백엔드 루트

from sqlalchemy import delete  # noqa: E402

from app import models  # noqa: E402
from app.database import _get_engine, get_async_session_factory  # noqa: E402
from app.services import score_service  # noqa: E402
from scripts._staging_guard import assert_few_users, ensure_staging  # noqa: E402

BASE = date(2026, 5, 1)   # 패턴 인덱스 기준일 (결정적 — 랜덤 없음)

# 기록할 날짜들
MAY_WEEK = [date(2026, 5, 18) + timedelta(days=i) for i in range(7)]          # 월~일
JUNE = [date(2026, 6, 1) + timedelta(days=i) for i in range(30)]
JULY = [date(2026, 7, 1) + timedelta(days=i) for i in range(5)]  # 7/1~7/5 (7/4 포함 — 홈 '전날 대비' 박스 확인용)
MISSING = {date(2026, 6, 7), date(2026, 6, 15), date(2026, 6, 24)}            # 회고 미기록(워치만)

# 데모 보정 — 5월 주에 파랑 1일, 7월에 빨강 1일, 오늘(7/5)은 소화 2.5(74.5점) 유지
OVERRIDES = {
    date(2026, 5, 23): dict(g=92, d7=True, al=False, h="4h_over", dt=["light"], dg="hungry", pme="60plus"),
    date(2026, 7, 2): dict(g=147, d7=False, al=True, h="1h_under", dt=["eating_out", "meat"], dg="full", pme="none"),
    date(2026, 7, 5): dict(g=128, d7=True, al=False, h="2to3h", dt=["home_korean", "light"], dg="partial", pme="30to60"),
}


def day_spec(d: date) -> dict:
    """날짜별 회고/혈당 시나리오 — 인덱스 기반 결정적 패턴."""
    if d in OVERRIDES:
        return OVERRIDES[d]
    i = (d - BASE).days
    if i % 9 == 2:    # 높음(빨강): 나쁜 회고 조합
        return dict(g=138 + (i % 4) * 5, d7=False, al=(i % 18 == 2), h="1h_under" if i % 2 else "1to2h",
                    dt=["eating_out", "meat"] if i % 2 else ["meat"], dg="full", pme="none")
    if i % 11 == 5:   # 낮음(파랑): 좋은 회고 조합
        return dict(g=88 + (i % 4) * 3, d7=True, al=False, h="4h_over", dt=["light"], dg="hungry", pme="60plus")
    dg = "partial" if i % 4 == 1 else ("full" if i % 5 == 3 else "hungry")
    return dict(
        g=104 + (i * 7) % 25, d7=(i % 6 != 0), al=False,
        h=["2to3h", "3to4h", "4h_over"][i % 3],
        dt=[["home_korean"], ["home_korean", "light"], ["light"]][i % 3],
        dg=dg, pme=["10to30", "30to60", "60plus", "30to60"][i % 4],
    )


def sleep_of(d: date) -> int:
    if d == date(2026, 7, 5):
        return 410   # 오늘 74.5점 유지용
    return 360 + ((d - BASE).days * 37) % 130


def steps_of(d: date) -> int:
    if d == date(2026, 7, 5):
        return 8800  # 오늘 74.5점 유지용
    return 5500 + ((d - BASE).days * 533) % 5500


def watch_row(target, d: date) -> models.WatchData:
    sl, st = sleep_of(d), steps_of(d)
    deep, light, rem = int(sl * 0.25), int(sl * 0.5), int(sl * 0.2)
    return models.WatchData(
        user_id=target, log_date=d, steps=st, sleep_total_minutes=sl,
        sleep_deep_minutes=deep, sleep_light_minutes=light, sleep_rem_minutes=rem,
        sleep_awake_minutes=sl - deep - light - rem,
    )


async def main() -> None:
    ensure_staging()
    if len(sys.argv) < 2:
        print("사용: DANGBOM_ENV=staging python scripts/seed_staging_rich.py <auth-user-uuid>")
        sys.exit(1)
    target = uuid.UUID(sys.argv[1])

    Session = get_async_session_factory()
    try:
        async with Session() as db:
            await assert_few_users(db)
            user = await db.get(models.User, target)
            if user is None:
                print(f"[중단] users에 {target} 가 없습니다 — 앱에서 가입/온보딩 먼저.")
                sys.exit(1)
            low = user.glucose_normal_low or 100
            high = user.glucose_normal_high or 130
            print(f"[실계정 리치시드] {user.email} · 평소범위 {low}~{high}")

            # 기존 기간 데이터 전체 삭제 (5/17~7/6 — 이전 시드·워치 자동싱크 잔여 포함)
            d0, d1 = date(2026, 5, 17), date(2026, 7, 6)
            await db.execute(delete(models.DailyLog).where(
                models.DailyLog.user_id == target, models.DailyLog.log_date >= d0, models.DailyLog.log_date <= d1))
            await db.execute(delete(models.WatchData).where(
                models.WatchData.user_id == target, models.WatchData.log_date >= d0, models.WatchData.log_date <= d1))
            await db.execute(delete(models.LifestyleScore).where(
                models.LifestyleScore.user_id == target, models.LifestyleScore.score_date >= d0, models.LifestyleScore.score_date <= d1))

            # D-1 수면 앵커(요인 계산용): 5/17(5월주 시작 전날), 5/31(6/1 전날)
            db.add(watch_row(target, date(2026, 5, 17)))
            db.add(watch_row(target, date(2026, 5, 31)))
            # 미기록일: 워치만 (회고/점수 없음 → 달력 '·', 그래프 빈 날)
            for d in sorted(MISSING):
                db.add(watch_row(target, d))

            recorded = [d for d in (MAY_WEEK + JUNE + JULY) if d not in MISSING]
            counts = {"high": 0, "low": 0, "in": 0, "partial": 0}
            for d in recorded:
                x = day_spec(d)
                nf_bool = x["h"] in ("2to3h", "3to4h", "4h_over")
                db.add(models.DailyLog(
                    user_id=target, log_date=d, fasting_glucose=x["g"],
                    alcohol=x["al"], dinner_before_7pm=x["d7"], no_food_after_dinner=nf_bool,
                    no_food_after_dinner_hours=x["h"], dinner_type=x["dt"], digestion=x["dg"],
                    post_meal_exercise=x["pme"], input_method="morning",
                ))
                db.add(watch_row(target, d))
                r = score_service.calculate_score(dict(
                    fasting_glucose=x["g"], glucose_normal_low=low, glucose_normal_high=high,
                    steps=steps_of(d), sleep_minutes=sleep_of(d), alcohol=x["al"], dinner_before_7pm=x["d7"],
                    no_food_after_dinner=nf_bool, no_food_after_dinner_hours=x["h"],
                    dinner_type=x["dt"], digestion=x["dg"], post_meal_exercise=x["pme"],
                    stress_below_avg_ratio=0.6,
                ))
                b = r["breakdown"]
                db.add(models.LifestyleScore(
                    user_id=target, score_date=d, total_score=r["total"], score_label=r["label"],
                    glucose_score=b["glucose"], steps_score=b["steps"], sleep_score=b["sleep"],
                    dinner_before_7pm_score=b["dinner_before_7pm"], alcohol_score=b["alcohol"],
                    no_food_after_dinner_score=b["no_food_after_dinner"], stress_score=b["stress"],
                    dinner_type_score=b["dinner_type"], digestion_score=b["digestion"],
                    post_meal_exercise_score=b["post_meal_exercise"],
                    post_meal_walking_bonus=r["post_meal_walking_bonus"], has_missing_data=r["has_missing_data"],
                ))
                if x["g"] > high:
                    counts["high"] += 1
                elif x["g"] < low:
                    counts["low"] += 1
                else:
                    counts["in"] += 1
                if x["dg"] == "partial":
                    counts["partial"] += 1
            await db.commit()

        print(f"[OK] 리치 시드 완료 — 기록 {len(recorded)}일 "
              f"(높음 {counts['high']} · 낮음 {counts['low']} · 범위안 {counts['in']} · 소화2.5 {counts['partial']}일)")
        print(f"     5월 {MAY_WEEK[0]}~{MAY_WEEK[-1]} · 6월 한달(미기록 {sorted(x.day for x in MISSING)}일) · 7월 1~5일")
    finally:
        await _get_engine().dispose()


if __name__ == "__main__":
    if sys.platform == "win32":
        asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())
    asyncio.run(main())
