"""당봄케어 백엔드 설정 (pydantic-settings 기반)"""
import logging
import os

from pydantic_settings import BaseSettings, SettingsConfigDict


# [staging 분리] 실행 환경별로 어떤 .env를 읽을지 선택.
#   DANGBOM_ENV 미설정 또는 "production" → .env            (운영 — 기존과 100% 동일, NCP 무변경)
#   DANGBOM_ENV=staging                  → .env.staging     (예: staging Supabase)
#   DANGBOM_ENV=local                    → .env.local
# ⚠️ 폴백 없음: .env.staging이 없으면 .env로 떨어지지 않고 그대로 미로드 → DATABASE_URL 빈값 →
#    엔진 생성 시 즉시 에러(fail-closed). staging 실수로 '운영 DB' 붙는 사고를 원천 차단.
def _resolve_env_file() -> tuple[str, ...]:
    name = os.getenv("DANGBOM_ENV", "").strip().lower()
    if not name or name in ("prod", "production"):
        return (".env",)
    return (f".env.{name}",)


_ENV_FILE = _resolve_env_file()


class Settings(BaseSettings):
    # App
    app_name: str = "DangbomCare"
    # [P0 보안] production-safe default — .env에서 명시적으로 변경
    app_env: str = "production"
    app_debug: bool = False
    app_port: int = 8000

    # Supabase
    supabase_url: str = ""
    supabase_anon_key: str = ""
    supabase_service_key: str = ""
    supabase_jwt_secret: str = ""

    # Database
    database_url: str = ""

    # OpenAI
    openai_api_key: str = ""
    openai_daily_model: str = "gpt-4o-mini"
    openai_weekly_model: str = "gpt-4o"
    openai_monthly_model: str = "gpt-4o"
    daily_limit_per_user_krw: int = 50

    # Firebase
    fcm_service_account_json_base64: str = ""

    # Kakao OAuth
    kakao_rest_api_key: str = ""

    # Sentry
    sentry_dsn: str = ""

    # [260826] 관리자웹 → 백엔드 관리자 전용 엔드포인트(AI 리포트 "지금 생성하기") 인증.
    #   관리자웹은 환자 JWT가 없어(Supabase service-role로 직접 DB만 접근) 공유 비밀키로 구분한다.
    admin_api_key: str = ""

    # CORS
    cors_origins: str = "http://localhost:3000,http://localhost:8080"

    # Timezone
    timezone: str = "Asia/Seoul"

    model_config = SettingsConfigDict(
        # [P1 보안] 상위 ../.env fallback 제거 — 운영 환경에서 의도치 않은 .env 로드 방지
        # [staging 분리] DANGBOM_ENV에 따라 .env / .env.staging / .env.local 선택 (_resolve_env_file)
        env_file=_ENV_FILE,
        env_file_encoding="utf-8",
        case_sensitive=False,
        extra="ignore",
    )

    @property
    def cors_origins_list(self) -> list[str]:
        return [o.strip() for o in self.cors_origins.split(",") if o.strip()]


# 싱글톤
settings = Settings()


# [v1.0 보안 B8] production 환경에서 필수 환경변수 빈 값이면 startup abort.
# supabase_url이 빈 값이면 JWT issuer 검증 우회 가능 → 절대 production에서 비워두면 안 됨.
def _validate_production_env() -> None:
    if settings.app_env != "production":
        return
    required = {
        "SUPABASE_URL": settings.supabase_url,
        "SUPABASE_JWT_SECRET": settings.supabase_jwt_secret,
        "DATABASE_URL": settings.database_url,
    }
    missing = [name for name, val in required.items() if not val or not val.strip()]
    if missing:
        raise RuntimeError(
            f"[STARTUP ABORT] production 환경에서 필수 환경변수가 비어있습니다: {', '.join(missing)}. "
            f"NCP 서버 /opt/dangdangcare-backend/.env를 확인하세요."
        )


_validate_production_env()


# [staging 분리] DANGBOM_ENV(파일 선택) ↔ app_env(동작 모드) 정합성 검사 — FastAPI 앱 startup에도 적용.
#   DANGBOM_ENV=staging 인데 app_env=production 이면(=.env.staging 누락/APP_ENV 빠짐) 중단.
#   → '운영 DB에 staging 의도로 잘못 붙는' 사고를 startup에서 fail-closed로 차단.
def _validate_env_consistency() -> None:
    selected = os.getenv("DANGBOM_ENV", "").strip().lower()
    if selected and selected not in ("prod", "production") and settings.app_env.lower() == "production":
        raise RuntimeError(
            f"[STARTUP ABORT] DANGBOM_ENV={selected} 인데 app_env=production 입니다. "
            f"'{_ENV_FILE[0]}' 가 없거나 그 파일에 APP_ENV={selected} 가 빠졌을 수 있습니다. "
            f"운영 DB 오접속 방지를 위해 시작을 중단합니다."
        )


_validate_env_consistency()


# [staging 분리] 어떤 환경/DB에 붙었는지 시작 로그 — 운영 오접속 사고를 눈으로 즉시 확인.
#   비밀번호는 절대 출력 안 함(user@host만). project-id는 staging/운영 구분용으로 노출.
def _safe_db_id(url: str) -> str:
    try:
        after = url.split("://", 1)[1]      # user:pass@host:port/db
        creds, hostpart = after.split("@", 1)
        user = creds.split(":", 1)[0]       # 비밀번호 버림
        host = hostpart.split("/", 1)[0]
        return f"{user}@{host}"
    except Exception:
        return "(unparseable)"


def _log_active_env() -> None:
    # 운영은 조용히(INFO), staging/local은 눈에 띄게(WARNING) — '운영에 잘못 붙었나' 즉시 확인용.
    is_prod = settings.app_env.lower() == "production"
    logging.getLogger(__name__).log(
        logging.INFO if is_prod else logging.WARNING,
        "[ENV] app_env=%s · env_file=%s · db=%s",
        settings.app_env, _ENV_FILE[0], _safe_db_id(settings.database_url),
    )


_log_active_env()
