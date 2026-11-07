"""[staging] 검증용 가짜 데이터 시드 — 테스트 유저 1명 + 지난주 7일치.

채우는 것:
  - User (평소 공복혈당 100~130)
  - DailyLog 7일 (高/低/범위안 혈당 + 회고 4항목, 소화 'partial' 포함 → 2.5 검증)
  - WatchData 8일 (그 전날 밤 수면 = 고혈당 요인용)
  - LifestyleScore 7일 (실제 score_service로 계산 → 소화 2.5가 총점에 반영되는지 확인)
재실행 안전: 같은 테스트 유저를 지우고 다시 넣는다(멱등).

사용: DANGBOM_ENV=staging python scripts/seed_staging.py
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

TEST_USER = uuid.UUID("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
LOW, HIGH = 100, 130

# 월~일 7일 시나리오 (glucose 기준: <100 낮음, 100~130 범위안, >130 높음)
DAYS = [
    # glucose, dinner7, alcohol, hours,        dinner_type,                  digestion, pme,       sleep
    dict(g=112, d7=True,  al=False, h="3to4h",   dt=["home_korean"],            dg="hungry",  pme="30to60", sl=430),
    dict(g=145, d7=False, al=True,  h="1h_under", dt=["eating_out", "meat"],     dg="full",    pme="none",   sl=350),  # 높음
    dict(g=108, d7=True,  al=False, h="2to3h",   dt=["home_korean", "light"],   dg="partial", pme="10to30", sl=420),  # 소화 2.5
    dict(g=152, d7=False, al=False, h="1to2h",   dt=["meat"],                   dg="full",    pme="none",   sl=300),  # 높음
    dict(g=120, d7=True,  al=False, h="4h_over",  dt=["home_korean"],            dg="hungry",  pme="60plus", sl=440),
    dict(g=92,  d7=True,  al=False, h="4h_over",  dt=["light"],                  dg="hungry",  pme="60plus", sl=460),  # 낮음
    dict(g=128, d7=True,  al=False, h="2to3h",   dt=["home_korean", "light"],   dg="partial", pme="30to60", sl=410),  # 소화 2.5
]


async def main() -> None:
    ensure_staging()
    # 인자 없으면 스크립트 전용 가짜 유저(지난주). UUID 인자를 주면 '실계정 모드'(이번 주·오늘 포함).
    #   사용: DANGBOM_ENV=staging python scripts/seed_staging.py <auth-user-uuid>
    target = uuid.UUID(sys.argv[1]) if len(sys.argv) > 1 else TEST_USER
    today = date.today()
    Session = get_async_session_factory()
    async with Session() as db:
        await assert_few_users(db)   # 운영 DB(유저 다수)면 시드 거부

        if target == TEST_USER:
            week_start = today - timedelta(days=today.weekday()) - timedelta(days=7)   # 지난주 월요일(7일 모두 과거)
            low, high = LOW, HIGH
            # 멱등: 기존 테스트 유저 제거(cascade로 로그/워치/점수 같이 삭제)
            await db.execute(delete(models.User).where(models.User.id == TEST_USER))
            await db.flush()
            db.add(models.User(
                id=TEST_USER, email="staging-test@dangbom.local", name="스테이징테스터",
                glucose_normal_low=LOW, glucose_normal_high=HIGH, onboarding_completed=True,
            ))
            await db.flush()   # user 먼저 INSERT 확정 → 자식(FK) 삽입 시 위반 방지
        else:
            # 실계정 모드 — 유저 행은 보존(온보딩 정보 유지), '이번 주(월~일, 오늘 포함)'로 시드
            #   → 홈(오늘 점수)·리포트 기본 뷰(이번 주 달력)에 바로 보임
            week_start = today - timedelta(days=today.weekday())
            user = await db.get(models.User, target)
            if user is None:
                print(f"[중단] users에 {target} 가 없습니다 — 앱에서 가입/온보딩을 먼저 마치세요.")
                sys.exit(1)
            low = user.glucose_normal_low or LOW
            high = user.glucose_normal_high or HIGH
            # 기존 기간 데이터 삭제(워치 자동싱크 행 등과 unique 충돌 방지) — 워치는 전날(D-1)부터
            d0, d1 = week_start - timedelta(days=1), week_start + timedelta(days=6)
            await db.execute(delete(models.DailyLog).where(
                models.DailyLog.user_id == target, models.DailyLog.log_date >= d0, models.DailyLog.log_date <= d1))
            await db.execute(delete(models.WatchData).where(
                models.WatchData.user_id == target, models.WatchData.log_date >= d0, models.WatchData.log_date <= d1))
            await db.execute(delete(models.LifestyleScore).where(
                models.LifestyleScore.user_id == target, models.LifestyleScore.score_date >= d0, models.LifestyleScore.score_date <= d1))
            print(f"[실계정 모드] {user.email} · 평소범위 {low}~{high} · {week_start} ~ {week_start + timedelta(days=6)}")

        # 첫날(월) 고혈당 요인의 '전날 밤 수면'을 위해 week_start-1 워치도 추가
        db.add(models.WatchData(user_id=target, log_date=week_start - timedelta(days=1), steps=5000, sleep_total_minutes=360))

        for i, x in enumerate(DAYS):
            log_date = week_start + timedelta(days=i)
            nf_bool = x["h"] in ("2to3h", "3to4h", "4h_over")  # 식후금식 불리언(2시간↑이면 True)
            db.add(models.DailyLog(
                user_id=target, log_date=log_date, fasting_glucose=x["g"],
                alcohol=x["al"], dinner_before_7pm=x["d7"], no_food_after_dinner=nf_bool,
                no_food_after_dinner_hours=x["h"], dinner_type=x["dt"], digestion=x["dg"],
                post_meal_exercise=x["pme"], input_method="morning",
            ))
            # 수면 단계 대략 분할(깊25/얕50/렘20/깸5%) — daily_summary 단계 표시용
            sl = x["sl"]
            db.add(models.WatchData(
                user_id=target, log_date=log_date, steps=7000 + i * 300, sleep_total_minutes=sl,
                sleep_deep_minutes=int(sl * 0.25), sleep_light_minutes=int(sl * 0.5),
                sleep_rem_minutes=int(sl * 0.2),
                sleep_awake_minutes=sl - int(sl * 0.25) - int(sl * 0.5) - int(sl * 0.2),
            ))

            r = score_service.calculate_score(dict(
                fasting_glucose=x["g"], glucose_normal_low=low, glucose_normal_high=high,
                steps=7000 + i * 300, sleep_minutes=x["sl"], alcohol=x["al"], dinner_before_7pm=x["d7"],
                no_food_after_dinner=nf_bool, no_food_after_dinner_hours=x["h"],
                dinner_type=x["dt"], digestion=x["dg"], post_meal_exercise=x["pme"],
                stress_below_avg_ratio=0.6,
            ))
            b = r["breakdown"]
            db.add(models.LifestyleScore(
                user_id=target, score_date=log_date, total_score=r["total"], score_label=r["label"],
                glucose_score=b["glucose"], steps_score=b["steps"], sleep_score=b["sleep"],
                dinner_before_7pm_score=b["dinner_before_7pm"], alcohol_score=b["alcohol"],
                no_food_after_dinner_score=b["no_food_after_dinner"], stress_score=b["stress"],
                dinner_type_score=b["dinner_type"], digestion_score=b["digestion"],
                post_meal_exercise_score=b["post_meal_exercise"],
                post_meal_walking_bonus=r["post_meal_walking_bonus"], has_missing_data=r["has_missing_data"],
            ))
        await db.commit()

    await _get_engine().dispose()
    print(f"[OK] 시드 완료 — 유저={target}")
    print(f"     주간: {week_start} ~ {week_start + timedelta(days=6)}")
    if target == TEST_USER:
        print("다음: DANGBOM_ENV=staging python scripts/verify_staging.py")


if __name__ == "__main__":
    if sys.platform == "win32":
        asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())
    asyncio.run(main())
