"""주간 리포트 자동 발송 스케줄러 (당봄 #16 — Phase 5).

⚠️ 현재 미연결 (NOT YET WIRED) — v1.0 출시 후 운영 단계에서 활성화 예정.

동작 (예정):
1. 매주 일요일 23:00 — 모든 활성 사용자에 대해 주간 리포트 생성
2. 매주 월요일 08:00 — 푸시 알림 발송 ("지난주 건강 리포트가 도착했습니다")

활성화 방법 (v1.0 출시 후):
- 별도 async SessionLocal 도입 (현재 database.py는 async only)
- generate_weekly_report_for_user 구현 (routers/reports.py에 추가)
- systemd timer 또는 APScheduler 등록

cron 설정 예시:
0 23 * * 0 /usr/bin/python -m app.batch.weekly_report_scheduler generate
0 8 * * 1 /usr/bin/python -m app.batch.weekly_report_scheduler notify
"""
from __future__ import annotations

import asyncio
import logging
import sys
from datetime import date, datetime, timedelta

from sqlalchemy import text

from ..database import get_async_session_factory
from ..services.push_service import PushPayload, send_to_user

logger = logging.getLogger(__name__)


async def generate_weekly_reports() -> int:
    """모든 활성 사용자에 대해 지난주 리포트를 미리 생성·캐싱.

    Returns:
        생성 성공한 리포트 수
    """
    today = date.today()
    # 막 끝난 주(월~일)의 마지막 일요일을 포함적으로 계산.
    #   weekday(): 월=0 … 일=6. (weekday()+1) % 7 → 가장 최근 일요일까지의 일수(일요일 당일=0).
    #   ⚠️ 이전 버그: today - (weekday()+1) 은 일요일 실행 시 '지난주' 일요일(7일 전)을 가리켜
    #      막 끝난 주가 아니라 그 전 주를 리포트했음.
    days_since_sunday = (today.weekday() + 1) % 7
    last_sunday = today - timedelta(days=days_since_sunday)  # 막 끝난 주의 일요일 (일요일 실행=오늘)
    last_monday = last_sunday - timedelta(days=6)            # 그 주 월요일 (week_start)

    logger.info(f"주간 리포트 생성 시작: {last_monday} ~ {last_sunday}")

    success_count = 0
    async with get_async_session_factory()() as db:
        # [P1 코덱스] users 테이블에 deleted_at 컬럼은 없음 (soft-delete는 deletion_requested_at).
        # 회원 탈퇴 신청한 사용자(deletion_requested_at IS NOT NULL)는 리포트 생성 제외.
        result = await db.execute(
            text("""
                SELECT DISTINCT u.id
                FROM users u
                JOIN daily_logs dl ON dl.user_id = u.id
                WHERE dl.log_date BETWEEN :start AND :end
                  AND u.deletion_requested_at IS NULL
            """),
            {"start": last_monday, "end": last_sunday}
        )
        users = result.fetchall()

        # 주간 리포트 생성 함수 (reports.py — generate_weekly_report_for_user는 week_start만 받음)
        from ..routers.reports import generate_weekly_report_for_user
        for (user_id,) in users:
            try:
                await generate_weekly_report_for_user(
                    user_id=user_id,
                    week_start=last_monday,
                    db=db,
                    refresh=False,
                )
                success_count += 1
            except Exception as e:
                logger.error(f"사용자 {user_id} 리포트 생성 실패: {e}")
                # 단일 세션을 전 사용자가 공유 → 실패한 사용자가 세션을 오염시키면
                #   이후 사용자 전원이 PendingRollbackError로 연쇄 실패. 사용자별로 롤백 후 계속.
                await db.rollback()

        await db.commit()

    logger.info(f"주간 리포트 생성 완료: {success_count}명")
    return success_count


async def notify_weekly_reports() -> int:
    """월요일 아침 — 주간 리포트 도착 푸시 알림 발송.

    Returns:
        발송 성공 수
    """
    logger.info("주간 리포트 푸시 알림 발송 시작")

    success_count = 0
    async with get_async_session_factory()() as db:
        # 최근 일주일 내 데이터 입력한 사용자 = 활성 사용자
        # [v1.0 fix - Codex 2차] users.user_id 컬럼 없음(id), u.deleted_at 없음(deletion_requested_at).
        #   이전 쿼리는 ProgrammingError로 주간 리포트 발송 전체 실패. 138번 라인 패턴과 통일.
        result = await db.execute(
            text("""
                SELECT DISTINCT u.id
                FROM users u
                JOIN daily_logs dl ON dl.user_id = u.id
                WHERE dl.log_date >= CURRENT_DATE - INTERVAL '7 days'
                  AND u.fcm_token IS NOT NULL
                  AND u.deletion_requested_at IS NULL
            """)
        )
        users = result.fetchall()

        payload = PushPayload(
            title="📊 주간 리포트가 도착했어요",
            body="지난주 건강 패턴을 분석한 리포트가 준비됐어요. 원장님께 보여드릴 준비를 하세요.",
            data={"type": "weekly_report", "action": "open_reports"},
            channel="weekly_report",
        )

        for (user_id,) in users:
            if await send_to_user(user_id, payload, db_session=db):
                success_count += 1

    logger.info(f"주간 리포트 푸시 발송 완료: {success_count}명")
    return success_count


# ============================================================
# [피드백 260714 정리 · S1] 식후 걷기 알림 폐지
#   앱 알림 설정에서도 [260623 피드백] 저녁 식사 마감 알림 멘트에 통합되면서 항목 제거됨.
#   백엔드 발송 경로도 제거 → 앱에 UI 없는 알림이 계속 오는 문제 해결.
#   CLI 진입점의 post_meal_walk 커맨드도 폐기.
# ============================================================


# ============================================================
# CLI 진입점
# ============================================================
if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")

    if len(sys.argv) < 2:
        print("Usage: python -m app.batch.weekly_report_scheduler <generate|notify>")
        sys.exit(1)

    cmd = sys.argv[1]
    if cmd == "generate":
        n = asyncio.run(generate_weekly_reports())
        print(f"Generated {n} reports")
    elif cmd == "notify":
        n = asyncio.run(notify_weekly_reports())
        print(f"Notified {n} users")
    else:
        print(f"Unknown command: {cmd}")
        sys.exit(1)
