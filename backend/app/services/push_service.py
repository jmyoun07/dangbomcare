"""FCM 푸시 알림 서비스 (당봄 #16, #18 — Phase 5).

기능:
1. send_fcm() — 단일 발송 (즉시)
2. send_with_quiet_hours() — 심야 무음 모드 적용 (취침~기상 동안 무음 채널)
3. send_to_user() — 사용자 ID로 발송 (DB에서 fcm_token 조회)

의존성: pyfcm 또는 firebase-admin
설치: pip install firebase-admin

활성화 단계:
1. Firebase 콘솔에서 프로젝트 생성
2. 서비스 계정 JSON 키 다운로드 → /backend/firebase-key.json
3. 환경 변수 FIREBASE_KEY_PATH 설정
4. 본 파일의 _firebase_initialized 가 True 되도록 설정
"""
from __future__ import annotations

import json
import logging
import os
from dataclasses import dataclass
from datetime import datetime, time, timezone
from typing import TYPE_CHECKING, Optional

if TYPE_CHECKING:
    from sqlalchemy.ext.asyncio import AsyncSession

logger = logging.getLogger(__name__)

# Firebase 초기화 (활성화 후 사용)
_firebase_initialized = False
_firebase_app = None

try:
    import firebase_admin
    from firebase_admin import credentials, messaging
    _FIREBASE_AVAILABLE = True
except ImportError:
    _FIREBASE_AVAILABLE = False
    logger.warning("firebase-admin 미설치 — FCM 비활성화. pip install firebase-admin")


def init_firebase(key_path: Optional[str] = None) -> bool:
    """Firebase Admin SDK 초기화. 앱 시작 시 한 번 호출.

    Args:
        key_path: 서비스 계정 JSON 경로 (None이면 환경변수에서)

    Returns:
        True if successful
    """
    global _firebase_initialized, _firebase_app

    if not _FIREBASE_AVAILABLE:
        return False
    if _firebase_initialized:
        return True

    path = key_path or os.environ.get("FIREBASE_KEY_PATH")
    if not path or not os.path.exists(path):
        logger.warning(f"Firebase 키 파일 없음: {path} — FCM 비활성화")
        return False

    try:
        cred = credentials.Certificate(path)
        _firebase_app = firebase_admin.initialize_app(cred)
        _firebase_initialized = True
        logger.info("Firebase Admin SDK 초기화 완료")
        return True
    except Exception as e:
        logger.error(f"Firebase 초기화 실패: {e}")
        return False


@dataclass
class PushPayload:
    """푸시 알림 페이로드"""
    title: str
    body: str
    data: Optional[dict] = None
    channel: str = "default"     # default | urgent | silent
    urgent: bool = False         # 심야 무음 우회 (응급 알림)


def send_fcm(
    fcm_token: str,
    payload: PushPayload,
) -> bool:
    """FCM 단일 발송.

    Returns:
        True if successful, False if Firebase not initialized or failure
    """
    if not _firebase_initialized:
        logger.warning(f"Firebase 미초기화 — 발송 스킵: {payload.title}")
        return False

    try:
        # 채널 선택 (안드로이드 클라이언트에서 처리)
        android_config = messaging.AndroidConfig(
            priority="high" if payload.urgent else "normal",
            notification=messaging.AndroidNotification(
                channel_id=payload.channel,
                sound="default" if payload.urgent else None,
            ),
        )

        message = messaging.Message(
            notification=messaging.Notification(
                title=payload.title,
                body=payload.body,
            ),
            data={k: str(v) for k, v in (payload.data or {}).items()},
            token=fcm_token,
            android=android_config,
        )
        message_id = messaging.send(message)
        logger.info(f"FCM 발송 성공: {message_id}")
        return True
    except Exception as e:
        logger.error(f"FCM 발송 실패: {e}")
        return False


# ============================================================
# 심야 무음 모드 (당봄 #13)
# ============================================================
def is_quiet_hours(
    now: datetime,
    bedtime: time = time(23, 0),
    wake_time: time = time(7, 0),
) -> bool:
    """현재 시각이 사용자 취침~기상 사이인지 판정.

    Args:
        now: 현재 시각 (사용자 시간대 기준)
        bedtime: 취침 시각 (default 23:00)
        wake_time: 기상 시각 (default 07:00)

    Returns:
        True if within quiet hours
    """
    current = now.time()

    # 자정 넘어가는 경우 (예: 23:00 ~ 07:00)
    if bedtime > wake_time:
        return current >= bedtime or current < wake_time
    # 같은 날 내 (예: 13:00 ~ 18:00 -- 드문 케이스)
    return bedtime <= current < wake_time


def send_with_quiet_hours(
    fcm_token: str,
    payload: PushPayload,
    user_bedtime: Optional[time] = None,
    user_wake_time: Optional[time] = None,
    user_timezone_offset_hours: int = 9,  # KST
) -> bool:
    """심야 무음 모드 적용 발송.

    취침 시각이면 발송 보류 (큐에 추가 또는 무음 채널로 전송).
    응급 알림(payload.urgent=True)은 항상 발송.

    Returns:
        True if successful or queued
    """
    # 응급 알림은 항상 즉시 발송
    if payload.urgent:
        return send_fcm(fcm_token, payload)

    # 사용자 시간대 기준 현재 시각
    bedtime = user_bedtime or time(23, 0)
    wake = user_wake_time or time(7, 0)
    now_user = datetime.now(timezone.utc).astimezone(
        timezone(timedelta_hours(user_timezone_offset_hours))
    )

    if is_quiet_hours(now_user, bedtime, wake):
        # 무음 채널로 전송 (알림은 도착하되 소리/진동 X)
        # 또는 큐에 적재하여 기상 시각에 발송 (별도 스케줄러 필요)
        logger.info(f"심야 무음 모드 — 무음 채널 전송: {payload.title}")
        silent_payload = PushPayload(
            title=payload.title,
            body=payload.body,
            data=payload.data,
            channel="silent",
            urgent=False,
        )
        return send_fcm(fcm_token, silent_payload)

    # 일반 발송
    return send_fcm(fcm_token, payload)


# ============================================================
# 사용자 ID로 발송 (DB 조회)
# ============================================================
async def send_to_user(
    user_id: str,
    payload: PushPayload,
    db_session: "AsyncSession | None" = None,  # SQLAlchemy async session
) -> bool:
    """사용자 ID로 푸시 발송 — DB에서 fcm_token + 취침 시각 조회.

    Args:
        user_id: 사용자 UUID
        payload: 알림 내용
        db_session: SQLAlchemy async 세션 (None이면 새로 열기)

    Returns:
        True if successful
    """
    # 세션 미주입 시 — async 세션 팩토리로 직접 열고 재귀 호출.
    # (database.py는 async-only — sync SessionLocal 없음)
    if db_session is None:
        from ..database import get_async_session_factory
        session_factory = get_async_session_factory()
        async with session_factory() as session:
            return await send_to_user(user_id, payload, db_session=session)

    # [v1.0 fix - Codex 2차] users.user_id 컬럼 없음(id), bedtime은 user_settings에, wake_time은 미존재.
    #   기상 시각 ≈ glucose_check_time(평소 측정 시각)으로 매핑. 미설정 사용자는 NULL → Python default 07:00 사용.
    from sqlalchemy import text
    result = await db_session.execute(
        text("""
            SELECT u.fcm_token, s.bedtime, s.glucose_check_time
            FROM users u
            LEFT JOIN user_settings s ON s.user_id = u.id
            WHERE u.id = :user_id AND u.fcm_token IS NOT NULL
        """),
        {"user_id": user_id}
    )
    row = result.fetchone()

    if not row:
        logger.warning(f"FCM 토큰 없음: {user_id}")
        return False

    fcm_token, bedtime, wake_time = row
    return send_with_quiet_hours(
        fcm_token,
        payload,
        user_bedtime=bedtime,
        user_wake_time=wake_time,
    )


# 헬퍼: timedelta with hours
def timedelta_hours(hours: int):
    from datetime import timedelta
    return timedelta(hours=hours)
