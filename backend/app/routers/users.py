"""사용자 정보 / 설정 / 동의 / FCM 토큰"""
from __future__ import annotations

import uuid

from fastapi import APIRouter, Depends, HTTPException
from fastapi.security import HTTPAuthorizationCredentials
from sqlalchemy import select, text
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.deps import _verify_token, get_current_user_id
from app.deps import security as _bearer_scheme
from app.models import DailyLog, GlucoseRangeHistory, User, UserConsent, UserSetting
from app.schemas import (
    ClientStatusRequest,
    ConsentCreateRequest,
    FcmTokenRequest,
    GlucoseRangeSuggestion,
    MarketingConsentOut,
    MarketingConsentUpdateRequest,
    UserConsentsOut,
    UserResponse,
    UserSettingsOut,
    UserSettingsUpdateRequest,
    UserUpdateRequest,
)

router = APIRouter(prefix="/api/users", tags=["users"])


# ==========================================================
# [260727] auth 트리거 실패 계정용 placeholder 이메일 + JWT email claim 동기화
#   Supabase auth 트리거가 실패하면 public.users 행이 없는 계정이 생긴다.
#   그 계정이 온보딩을 진행하려면 아래 두 경로(PUT /me, POST /me/consents)가
#   users 행을 직접 만들어야 하는데, users.email 은 UNIQUE NOT NULL 이라
#   상수 이메일을 쓰면 두 번째 사용자부터 users_email_key 위반(500)이 난다.
#   → user_id 파생 placeholder 로 충돌 자체를 제거하고,
#     실제 이메일은 아래 _sync_email_from_jwt 로 나중에 덮어쓴다.
# ==========================================================
_PLACEHOLDER_EMAIL_DOMAIN = "@placeholder.dangbomcare.app"
_LEGACY_PLACEHOLDER_EMAIL = "unknown@dangdang.care"  # 260726 이전 코드가 쓰던 상수 이메일


def _placeholder_email(user_id: uuid.UUID) -> str:
    return f"pending-{user_id}{_PLACEHOLDER_EMAIL_DOMAIN}"


def _is_placeholder_email(email: str | None) -> bool:
    """placeholder(임시) 이메일인지 — 실제 이메일은 절대 덮어쓰지 않기 위한 판별."""
    if not email:
        return True
    e = email.strip().lower()
    return e == _LEGACY_PLACEHOLDER_EMAIL or (
        e.startswith("pending-") and e.endswith(_PLACEHOLDER_EMAIL_DOMAIN)
    )


async def _jwt_email_claim(
    creds: HTTPAuthorizationCredentials = Depends(_bearer_scheme),
) -> str | None:
    """Supabase access token 의 email claim (없거나 실패하면 None).

    get_current_user_id 는 sub(uuid)만 돌려주고 app/deps.py 는 이번 수정 범위 밖이라,
    같은 토큰을 여기서 한 번 더 '검증해서' 꺼낸다(서명 검증 없는 디코딩은 쓰지 않는다).
    JWKS/secret 은 캐시되므로 추가 비용은 무시할 수준.
    카카오 등 email 이 없는 계정은 None → 동기화 스킵.
    이 함수는 절대 예외를 올리지 않는다 — 이메일 동기화 실패가 본 요청을 깨면 안 된다.
    """
    try:
        claims = _verify_token(creds.credentials) or {}
        email = (claims.get("email") or "").strip().lower()
    except Exception:
        return None
    # 최소 sanity check (users.email 컬럼에 쓰레기값이 들어가지 않도록)
    if not email or "@" not in email or len(email) > 254:
        return None
    return email


async def _sync_email_from_jwt(db: AsyncSession, user: User, jwt_email: str | None) -> None:
    """placeholder 이메일을 JWT 의 실제 이메일로 1회 교정.

    - placeholder 일 때만 덮어쓴다(auth 트리거가 정상 기록한 실제 이메일은 건드리지 않음).
    - users.email 은 UNIQUE 라, 다른 행(예: 탈퇴 대기 30일 중인 옛 행)이 같은 이메일을
      쥐고 있으면 교정을 포기한다. 여기서 IntegrityError(500)를 내면 온보딩 프로필 저장이
      통째로 막혀 원래 고치려던 하드락이 재발한다.
    """
    if not jwt_email or not _is_placeholder_email(user.email):
        return
    if jwt_email == (user.email or "").strip().lower():
        return
    conflict = (
        await db.execute(select(User.id).where(User.email == jwt_email, User.id != user.id))
    ).first()
    if conflict is not None:
        return
    user.email = jwt_email


@router.get("/me", response_model=UserResponse)
async def get_me(
    user_id: uuid.UUID = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    result = await db.execute(select(User).where(User.id == user_id))
    user = result.scalar_one_or_none()
    if not user:
        raise HTTPException(status_code=404, detail={"error": "NOT_FOUND", "message": "사용자 정보가 없습니다"})

    settings_result = await db.execute(select(UserSetting).where(UserSetting.user_id == user_id))
    user_settings = settings_result.scalar_one_or_none()

    consents_result = await db.execute(select(UserConsent).where(UserConsent.user_id == user_id))
    consents = consents_result.scalar_one_or_none()

    # [260831] 의료진이 남긴 공유 메모 — admin_patient_flags는 관리자웹(Supabase 직접 접근) 전용
    #   테이블이라 SQLAlchemy 모델이 없다. raw SQL로 딱 이 두 컬럼만 조회한다.
    flag_row = (await db.execute(
        text("SELECT shared_memo, shared_memo_updated_at FROM admin_patient_flags WHERE patient_id = :uid"),
        {"uid": str(user_id)},
    )).mappings().first()
    doctor_message = flag_row["shared_memo"] if flag_row else None
    doctor_message_at = flag_row["shared_memo_updated_at"] if flag_row else None

    # birth_date에서 age 자동 계산 (없으면 DB의 age column fallback)
    # [코덱스 P0 #3] age가 비정상 범위(0 또는 150+)면 None — 호출자가 다시 입력 유도
    effective_age = _age_from_birth(user.birth_date) if user.birth_date else user.age
    if effective_age is not None and not (1 <= effective_age < 150):
        effective_age = None
    return UserResponse(
        id=user.id,
        email=user.email,
        patient_number=user.patient_number,
        phone_number=user.phone_number,
        name=user.name,
        gender=user.gender,
        age=effective_age,
        birth_date=user.birth_date,
        height_cm=user.height_cm,
        weight_kg=user.weight_kg,
        diabetes_diagnosed_at=user.diabetes_diagnosed_at,
        glucose_normal_low=user.glucose_normal_low,
        glucose_normal_high=user.glucose_normal_high,
        onboarding_completed=user.onboarding_completed,
        doctor_message=doctor_message,
        doctor_message_at=doctor_message_at,
        settings=UserSettingsOut.model_validate(user_settings) if user_settings else None,
        consents=UserConsentsOut(
            terms=consents.terms_accepted,
            privacy=consents.privacy_accepted,
            sensitive_data=consents.sensitive_data_accepted,
            third_party=consents.third_party_accepted,
            # [260728] 선택 항목 — 설정 화면 토글의 현재 상태 표시용.
            #   앱은 이 값을 필수 동의 게이트(hasConsents) 판정에 넣지 않는다.
            marketing=consents.marketing_accepted,
            research_use=consents.research_use_accepted,
        ) if consents else None,
    )


def _age_from_birth(birth: date) -> int:
    """생년월일 → 만 나이 (한국 기준 만 나이, 년도 차이에서 생일 전이면 -1).
    [코덱스 P1 #4] KST 기준으로 통일 — 서버 timezone과 무관."""
    from datetime import datetime as _dt
    from zoneinfo import ZoneInfo
    today = _dt.now(ZoneInfo("Asia/Seoul")).date()
    return today.year - birth.year - ((today.month, today.day) < (birth.month, birth.day))


@router.put("/me", response_model=UserResponse)
async def update_me(
    payload: UserUpdateRequest,
    user_id: uuid.UUID = Depends(get_current_user_id),
    jwt_email: str | None = Depends(_jwt_email_claim),
    db: AsyncSession = Depends(get_db),
):
    result = await db.execute(select(User).where(User.id == user_id))
    user = result.scalar_one_or_none()
    if not user:
        # 첫 진입 시 자동 생성 (Supabase Auth만 있고 우리 DB 레코드 없을 때).
        # [260726] 여기는 온보딩 프로필 저장 경로라 409로 끊으면 가입 자체가 막힌다 → 자동 생성은 유지.
        #   대신 이메일을 상수("unknown@dangdang.care")로 쓰면 users.email 이 UNIQUE NOT NULL 이라
        #   auth 트리거가 실패한 사용자가 둘 이상 이 경로를 타는 순간 users_email_key 위반 → 500.
        #   → user_id 파생 유니크 placeholder 로 충돌 자체를 제거한다.
        user = User(id=user_id, email=_placeholder_email(user_id))
        db.add(user)
        await db.flush()

    # [260727] placeholder → 실제 이메일 교정.
    #   이전 주석은 "실제 이메일은 이후 동기화 시 덮어써진다"고 적혀 있었으나
    #   백엔드 어디에도 users.email 을 다시 쓰는 코드가 없어 사실이 아니었다(placeholder 영구 잔존).
    #   → 주석을 고치는 대신, JWT email claim 으로 실제 덮어쓰는 경로를 여기에 만든다.
    await _sync_email_from_jwt(db, user, jwt_email)

    update_data = payload.model_dump(exclude_unset=True)
    # [코덱스 P0 #2] birth_date를 single source of truth로 — payload age는 무시.
    #   birth_date 보내면 항상 자동 계산. DB에 이미 birth_date 있는 user는 매번 age 동기화.
    if "birth_date" in update_data:
        update_data.pop("age", None)

    # [피드백 260719] 공복혈당 범위 변경 감지 — 이력 남기기 위해 이전 값 캡처.
    _old_low = user.glucose_normal_low
    _old_high = user.glucose_normal_high

    for field, value in update_data.items():
        setattr(user, field, value)

    # [피드백 260719 G] 공복혈당 범위 변경 시 이력 row 추가.
    #   리포트에서 시점별 범위 조회에 사용 (users.glucose_normal_low/high는 현재 값만 담김).
    #   [Codex P1-b · TODO] 사용자가 범위를 null로 '해제'하는 경우 이력에 안 남음
    #     (현재 GlucoseRangeHistory.range_low/high가 NOT NULL). 앱에는 명시적 '해제' UI가
    #     없어 실무 위험은 낮음. 향후 해제 지원 시 nullable column + 상태 row 도입 검토.
    _new_low = update_data.get("glucose_normal_low")
    _new_high = update_data.get("glucose_normal_high")
    if _new_low is not None and _new_high is not None and (_new_low != _old_low or _new_high != _old_high):
        from datetime import datetime as _dt3
        from zoneinfo import ZoneInfo as _ZI
        today_kst = _dt3.now(_ZI("Asia/Seoul")).date()
        db.add(GlucoseRangeHistory(
            user_id=user_id,
            range_low=_new_low,
            range_high=_new_high,
            effective_from=today_kst,
        ))

    effective_birth = update_data.get("birth_date") or user.birth_date
    if effective_birth:
        try:
            age_val = _age_from_birth(effective_birth)
            # [코덱스 P0 #3] CHECK constraint (1 <= age < 150) 위반 사전 차단
            if not (1 <= age_val < 150):
                raise HTTPException(
                    status_code=422,
                    detail={
                        "error": "INVALID_BIRTH_DATE",
                        "message": f"생년월일이 올바르지 않아요 (계산된 나이 {age_val}세는 허용 범위(1~149) 밖입니다)",
                    },
                )
            user.age = age_val
        except HTTPException:
            raise
        except Exception:
            pass

    try:
        await db.flush()
        await db.commit()
    except IntegrityError as e:
        await db.rollback()
        # [260824] phone_number UNIQUE 충돌 — 같은 번호로 이미 가입된 계정이 있음
        if "phone_number" in str(e.orig):
            raise HTTPException(
                status_code=409,
                detail={"error": "PHONE_NUMBER_TAKEN", "message": "이미 가입된 휴대전화번호예요."},
            )
        raise
    return await get_me(user_id=user_id, db=db)


@router.put("/me/settings", response_model=UserSettingsOut)
async def update_settings(
    payload: UserSettingsUpdateRequest,
    user_id: uuid.UUID = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    result = await db.execute(select(UserSetting).where(UserSetting.user_id == user_id))
    settings_row = result.scalar_one_or_none()

    if not settings_row:
        settings_row = UserSetting(user_id=user_id)
        db.add(settings_row)

    for field, value in payload.model_dump(exclude_unset=True).items():
        setattr(settings_row, field, value)

    await db.flush()
    await db.commit()
    await db.refresh(settings_row)
    return UserSettingsOut.model_validate(settings_row)


@router.post("/me/consents")
async def create_consents(
    payload: ConsentCreateRequest,
    user_id: uuid.UUID = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    result = await db.execute(select(UserConsent).where(UserConsent.user_id == user_id))
    existing = result.scalar_one_or_none()

    # [260727] public.users 행이 없으면 여기서 자동 생성한다 (409 로 끊지 않는다).
    #   왜 409 가 아니라 자동 생성인가:
    #     앱은 GET /me 404 를 '신규 사용자'로 보고 곧장 동의 화면으로 보낸다(NavGraph.kt).
    #     즉 이 엔드포인트는 users 행이 없을 때만 도달하는 화면의 유일한 저장 API 인데,
    #     여기서 409(USER_NOT_READY)를 주면 앱에 그 코드를 처리하는 분기가 하나도 없어
    #     auth 트리거가 실패한 계정은 첫 화면에서 영구히 막히고 자가 복구가 불가능해진다(하드락).
    #     → 이 경로가 사실상 유일한 복구 경로이므로 users 행을 만들어 스스로 복구시킨다.
    #   과거 버그(상수 이메일 "unknown@dangdang.care")는 users.email UNIQUE 위반으로 두 번째
    #   사용자부터 500 이 났다 → user_id 파생 placeholder 로 충돌 자체를 제거한다.
    #   (여기서는 JWT 의 실제 이메일을 쓰지 않는다. 실제 이메일은 UNIQUE 충돌 가능성이 있어
    #    이 하드락 복구 경로에서 500 위험을 만든다. 교정은 이후 PUT /me 의 _sync_email_from_jwt 담당.)
    user_check = (await db.execute(select(User).where(User.id == user_id))).scalar_one_or_none()
    if not user_check:
        db.add(User(id=user_id, email=_placeholder_email(user_id)))
        await db.flush()

    # [260726] 부분 갱신 방어 — 바디에 없는 동의 필드(None)는 기존 값을 유지한다.
    #   기본값 False 를 무조건 대입하면, 필드를 안 보내는 구버전 앱 요청 한 번에
    #   이미 받아둔 동의(True)가 조용히 False 로 강등된다.
    # [260728] 마케팅 동의(선택)는 True 로 저장할 때만 동의 시각을 남긴다.
    #   미전달(None)이면 아무것도 건드리지 않는다(구버전 앱 호환).
    from datetime import datetime, timezone
    _now = datetime.now(timezone.utc)

    if existing:
        existing.terms_accepted = payload.terms_accepted
        existing.privacy_accepted = payload.privacy_accepted
        existing.sensitive_data_accepted = payload.sensitive_data_accepted
        if payload.third_party_accepted is not None:
            existing.third_party_accepted = payload.third_party_accepted
        if payload.marketing_accepted is not None:
            _was_marketing = bool(existing.marketing_accepted)
            existing.marketing_accepted = payload.marketing_accepted
            if payload.marketing_accepted:
                # 동의 화면에서 방금 체크한 행위이므로 '마지막 동의 시각'을 갱신
                existing.marketing_accepted_at = _now
            elif _was_marketing:
                # True → False: 동의 화면 재진입에서 체크를 해제한 경우 = 철회
                existing.marketing_revoked_at = _now
            # False → False 는 동의한 적이 없으므로 철회가 아니다 → 시각 기록 안 함
        if payload.research_use_accepted is not None:
            existing.research_use_accepted = payload.research_use_accepted
    else:
        db.add(UserConsent(
            user_id=user_id,
            terms_accepted=payload.terms_accepted,
            privacy_accepted=payload.privacy_accepted,
            sensitive_data_accepted=payload.sensitive_data_accepted,
            # 신규 행에서는 미전달(None) = 아직 동의 안 받음 → False (컬럼이 NOT NULL)
            third_party_accepted=bool(payload.third_party_accepted),
            marketing_accepted=bool(payload.marketing_accepted),
            # 신규 가입이라 철회 이력은 없다 → revoked_at 은 NULL 유지
            marketing_accepted_at=_now if payload.marketing_accepted else None,
            research_use_accepted=bool(payload.research_use_accepted),
        ))

    await db.commit()
    return {"ok": True}


# ==========================================================
# [260728] 마케팅 동의(선택) 철회 / 재동의 — 설정 → 약관·정책 토글 전용
#   우노 문서: "이용자는 언제든지 앱 설정 ... 을 통해 마케팅 활용 및 광고성 정보 수신 동의를
#   철회할 수 있습니다" → 온보딩 이후에도 켜고 끌 수 있는 경로가 필요.
#
#   왜 POST /me/consents 를 재사용하지 않는가:
#     그 엔드포인트는 필수 4개를 바디로 받아 덮어쓴다. 설정 토글이 그걸 호출하면
#     실수/구버전 바디 한 번으로 이미 받아둔 필수 동의가 바뀔 수 있다.
#     → 이 엔드포인트의 바디에는 필수 동의 필드가 아예 없어 구조적으로 변경이 불가능하다.
#       (terms/privacy/sensitive_data/third_party 는 이 함수에서 읽지도, 쓰지도 않는다)
# ==========================================================
@router.patch("/me/consents/marketing", response_model=MarketingConsentOut)
async def update_marketing_consent(
    payload: MarketingConsentUpdateRequest,
    user_id: uuid.UUID = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    from datetime import datetime, timezone

    user = (await db.execute(select(User).where(User.id == user_id))).scalar_one_or_none()
    if not user:
        raise HTTPException(status_code=404, detail={"error": "NOT_FOUND", "message": "사용자 없음"})

    row = (await db.execute(select(UserConsent).where(UserConsent.user_id == user_id))).scalar_one_or_none()
    now = datetime.now(timezone.utc)

    # user_consents 행이 없을 때 (정상 흐름에서는 온보딩 POST /me/consents 가 먼저 만든다.
    #   설정 화면은 온보딩 이후에만 도달하므로 실제로는 거의 없는 경로):
    #   · accepted=False → 이미 '동의 안 함' 상태이므로 아무것도 만들지 않고 현재 상태만 반환(멱등).
    #                      필수 동의가 전부 false 인 빈 행을 만들어 두는 것은 사실만 흐린다.
    #   · accepted=True  → 기록할 실제 동의가 생겼으므로 행을 만든다. 필수 4개는 아직 받지 않았으니
    #                      false 로 둔다(사실에 부합). 앱 게이트는 개별 bool 로 판정하므로
    #                      (NavGraph.kt / SplashScreen.kt) 이 행 때문에 온보딩이 건너뛰어지지 않는다.
    if row is None:
        if not payload.accepted:
            return MarketingConsentOut(marketing=False)
        row = UserConsent(
            user_id=user_id,
            terms_accepted=False,
            privacy_accepted=False,
            sensitive_data_accepted=False,
            third_party_accepted=False,
            marketing_accepted=True,
            marketing_accepted_at=now,
            research_use_accepted=False,
        )
        db.add(row)
    elif payload.accepted:
        row.marketing_accepted = True
        row.marketing_accepted_at = now
    else:
        _was_accepted = bool(row.marketing_accepted)
        row.marketing_accepted = False
        # 실제 True → False 전이만 '철회'다. 이미 꺼진 상태에서 또 끄면 시각을 덮어쓰지 않는다.
        if _was_accepted:
            row.marketing_revoked_at = now

    await db.commit()
    await db.refresh(row)
    return MarketingConsentOut(
        marketing=row.marketing_accepted,
        marketing_accepted_at=row.marketing_accepted_at,
        marketing_revoked_at=row.marketing_revoked_at,
    )


@router.put("/me/fcm-token")
async def update_fcm_token(
    payload: FcmTokenRequest,
    user_id: uuid.UUID = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    result = await db.execute(select(User).where(User.id == user_id))
    user = result.scalar_one_or_none()
    if not user:
        raise HTTPException(status_code=404, detail={"error": "NOT_FOUND", "message": "사용자 없음"})
    user.fcm_token = payload.fcm_token
    await db.commit()
    return {"ok": True}


@router.put("/me/client-status")
async def update_client_status(
    payload: ClientStatusRequest,
    user_id: uuid.UUID = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    """[260904] 앱 버전·배터리 최적화·Health Connect 상태 보고 (앱 포그라운드 진입 시).

    걸음수 미수신 문의가 왔을 때 "구버전인지 / 최적화가 켜져 있는지 / 권한이 없는지"를
    DB 에서 바로 가려내기 위한 진단 정보. 값이 없는 항목은 null 로 저장(덮어씀 — 최신 상태가 정본).
    사용자 행이 없으면(동의 전) 조용히 무시 — 진단용이라 가입 흐름을 막을 이유가 없다.
    """
    result = await db.execute(select(User).where(User.id == user_id))
    user = result.scalar_one_or_none()
    if not user:
        return {"ok": False, "reason": "USER_NOT_READY"}
    for field, value in payload.model_dump().items():
        setattr(user, field, value)
    from datetime import datetime as _dt_cs, timezone as _tz_cs
    user.client_status_at = _dt_cs.now(_tz_cs.utc)
    await db.commit()
    return {"ok": True}


@router.delete("/me")
async def request_account_deletion(
    user_id: uuid.UUID = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    """[P0 코덱스 — 회원 탈퇴 30일 지연 삭제]
    개인정보처리방침/약관에 30일 보관 약속 → 즉시 삭제하면 정책 위반.
    이 시점에는 deletion_requested_at/deletion_scheduled_at만 설정.
    실제 삭제는 별도 cron으로 deletion_scheduled_at <= now 인 user 일괄 처리.
    """
    from datetime import datetime, timedelta, timezone
    result = await db.execute(select(User).where(User.id == user_id))
    user = result.scalar_one_or_none()
    if not user:
        raise HTTPException(status_code=404, detail={"error": "NOT_FOUND", "message": "사용자 없음"})

    if user.deletion_requested_at is not None:
        return {
            "ok": True,
            "message": "이미 탈퇴 신청이 접수되어 있습니다.",
            "deletion_scheduled_at": user.deletion_scheduled_at.isoformat() if user.deletion_scheduled_at else None,
        }

    now = datetime.now(timezone.utc)
    user.deletion_requested_at = now
    user.deletion_scheduled_at = now + timedelta(days=30)
    await db.commit()

    return {
        "ok": True,
        "message": "회원 탈퇴가 접수되었습니다. 30일 후 모든 데이터가 영구 삭제됩니다.",
        "deletion_scheduled_at": user.deletion_scheduled_at.isoformat(),
    }


# ==========================================================
# [260714] 매월 자동 공복혈당 범위 재정의 — [피드백 260714·업데이트]
#   기준: '지난 달력 한 달' 데이터 (예: 8월 첫 진입 시 7월 1일~7월 31일)
#   임계: <10일 = 기존 범위 유지 / ≥10일 = 새 범위 계산
#   최소 폭: 15 mg/dL (권장 범위가 너무 좁으면 중앙값 ±7~8로 확장)
#   이상치: IQR(P25~P75) 방식 자체가 극단값을 자동 제외
#
#   앱은 이번 달 첫 진입 시 needs_recalc=true 응답을 받아 팝업을 띄우고,
#   저장·나중에 눌러 ack 호출 → 이번 달엔 다시 안 뜸.
# ==========================================================
@router.get("/me/glucose-range-suggestion", response_model=GlucoseRangeSuggestion)
async def get_glucose_range_suggestion(
    user_id: uuid.UUID = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    from datetime import datetime, date, timedelta
    from zoneinfo import ZoneInfo

    user_row = await db.execute(select(User).where(User.id == user_id))
    user = user_row.scalar_one_or_none()
    if not user:
        raise HTTPException(status_code=404, detail={"error": "NOT_FOUND", "message": "사용자 없음"})

    # 지난 달력 한 달 범위 계산 (KST 기준)
    today_kst = datetime.now(ZoneInfo("Asia/Seoul")).date()
    if today_kst.month == 1:
        prev_year, prev_month = today_kst.year - 1, 12
    else:
        prev_year, prev_month = today_kst.year, today_kst.month - 1
    prev_start = date(prev_year, prev_month, 1)
    # 지난 달 마지막 날 = 이번 달 1일 - 1일
    this_month_start = date(today_kst.year, today_kst.month, 1)
    prev_end = this_month_start - timedelta(days=1)

    # 지난 달 공복혈당 fetch
    rows = await db.execute(
        select(DailyLog.fasting_glucose)
        .where(
            DailyLog.user_id == user_id,
            DailyLog.log_date >= prev_start,
            DailyLog.log_date <= prev_end,
            DailyLog.fasting_glucose.isnot(None),
        )
    )
    values = sorted(int(v) for v in rows.scalars().all() if v is not None)
    days = len(values)

    def _pct(values: list[int], p: float) -> int:
        # 단순 선형 백분위수 (numpy 없이).
        if not values:
            return 0
        n = len(values)
        rank = p * (n - 1)
        lo_i = int(rank)
        hi_i = min(lo_i + 1, n - 1)
        frac = rank - lo_i
        return int(round(values[lo_i] + (values[hi_i] - values[lo_i]) * frac))

    # 이번 달 이미 확인했는지 (last_check 타임스탬프가 이번 달인지)
    last_check = user.glucose_range_last_check_at
    already_this_month = (
        last_check is not None
        and last_check.astimezone(ZoneInfo("Asia/Seoul")).year == today_kst.year
        and last_check.astimezone(ZoneInfo("Asia/Seoul")).month == today_kst.month
    )

    # [260722] 이전에 이번 달 신규 가입자 스킵 로직 제거.
    #   사용자 요구: 신규 가입자에게도 팝업을 띄워 "지난달 기록 부족" 안내 (Case C 결).
    #   → 지난달 데이터 0일이면 아래 days < 10 분기가 자연스럽게 Case C 문구 처리.
    #   기존 범위 미설정 케이스는 그 아래에서 별도 스킵.

    # 10일 미만: 기존 범위(회원가입 시 값 or 지난 달 값) 유지 — 팝업은 뜨지만 계산은 안 함
    if days < 10:
        # [Codex 리뷰 #4] 지난 달 데이터 부족 + 기존 범위 미설정이면 팝업 띄워도 저장할 값이 없음.
        #   dismiss 시 ack만 찍히고 범위는 여전히 미설정으로 남음 → 팝업 스킵이 옳음.
        if user.glucose_normal_low is None and user.glucose_normal_high is None:
            return GlucoseRangeSuggestion(
                current_low=None,
                current_high=None,
                based_on_days=days,
                based_on_year=prev_year,
                based_on_month=prev_month,
                needs_recalc=False,
                reason="no_data_no_range",
            )
        return GlucoseRangeSuggestion(
            current_low=user.glucose_normal_low,
            current_high=user.glucose_normal_high,
            suggested_low=user.glucose_normal_low,
            suggested_high=user.glucose_normal_high,
            based_on_days=days,
            based_on_year=prev_year,
            based_on_month=prev_month,
            needs_recalc=not already_this_month,
            reason="already_checked_this_month" if already_this_month else "insufficient_data",
        )

    # 10일 이상: 이상치 제거 후 '평소 범위' 계산
    # 1) IQR 1.5배 밖의 극단값 제거 (표준적인 tukey fence 이상치 판정)
    q1_raw = _pct(values, 0.25)
    q3_raw = _pct(values, 0.75)
    iqr_raw = q3_raw - q1_raw
    lower_fence = q1_raw - 1.5 * iqr_raw
    upper_fence = q3_raw + 1.5 * iqr_raw
    cleaned = [v for v in values if lower_fence <= v <= upper_fence] or values

    # 2) 이상치 제거된 값으로 P25/P75 재계산 = '평소' 범위
    suggested_low = _pct(cleaned, 0.25)
    suggested_high = _pct(cleaned, 0.75)

    # 3) [피드백 260719 우노] 폭 10~15 강제 제한 제거.
    #    이유: 공복혈당 절대값이 큰 환자(200+)는 자연 범위 갭이 30~50까지 큼.
    #          인슐린 사용자도 변동성이 큼 → 인공 clamp 하면 오히려 오작동.
    #    → P25/P75(이상치 제외) 자연값 그대로 반환. 절대 경계(40/400)만 유지.
    suggested_low = max(40, suggested_low)
    suggested_high = min(400, suggested_high)
    if suggested_low > suggested_high:
        suggested_low, suggested_high = suggested_high, suggested_low

    # ≥15일 = 정식 계산 / 10~14일 = 부분 데이터 안내
    if already_this_month:
        reason = "already_checked_this_month"
    elif days >= 15:
        reason = "monthly_review_full"
    else:
        reason = "monthly_review_partial"

    return GlucoseRangeSuggestion(
        current_low=user.glucose_normal_low,
        current_high=user.glucose_normal_high,
        suggested_low=suggested_low,
        suggested_high=suggested_high,
        based_on_days=days,
        based_on_year=prev_year,
        based_on_month=prev_month,
        needs_recalc=not already_this_month,
        reason=reason,
    )


@router.post("/me/glucose-range-check-ack")
async def ack_glucose_range_check(
    user_id: uuid.UUID = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    """사용자가 이번 달 재정의 팝업을 확인(저장 or 나중에)한 뒤 호출 — 이 달엔 팝업 다시 안 뜨게."""
    from datetime import datetime, timezone
    row = await db.execute(select(User).where(User.id == user_id))
    user = row.scalar_one_or_none()
    if not user:
        raise HTTPException(status_code=404, detail={"error": "NOT_FOUND", "message": "사용자 없음"})
    user.glucose_range_last_check_at = datetime.now(timezone.utc)
    await db.commit()
    return {"ok": True}


@router.post("/me/cancel-deletion")
async def cancel_account_deletion(
    user_id: uuid.UUID = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    """[P0 회원 탈퇴 30일 정책] 30일 내 탈퇴 취소 (복구)"""
    result = await db.execute(select(User).where(User.id == user_id))
    user = result.scalar_one_or_none()
    if not user:
        raise HTTPException(status_code=404, detail={"error": "NOT_FOUND", "message": "사용자 없음"})

    if user.deletion_requested_at is None:
        return {"ok": True, "message": "탈퇴 신청이 없습니다."}

    user.deletion_requested_at = None
    user.deletion_scheduled_at = None
    await db.commit()
    return {"ok": True, "message": "회원 탈퇴가 취소되었습니다."}
