-- ==========================================================
-- [260904] users 클라이언트 상태 9컬럼 + app_config(강제 업데이트 정책) 테이블
-- ==========================================================
-- 배경:
--   260903 한의원 문의 — 환자 여러 명이 걸음수가 안 들어온다. 원인을 가리려면
--   "어떤 앱 버전을 쓰는지 / 배터리 최적화 예외인지 / Health Connect 권한이 있는지"를
--   알아야 하는데, 지금까지 앱은 이 값을 어디에도 보고하지 않았다(폰 안에서만 판단).
--   → 앱이 포그라운드 진입 시 PUT /api/users/me/client-status 로 보고하고 users 에 저장한다.
--     (별도 테이블이 아니라 users 컬럼인 이유: 관리자 웹이 users 를 그대로 읽으므로 조인 없이 보임)
--
--   강제 업데이트: 앱은 시작 시 GET /api/app/version-policy 로 min_version_code 를 읽어
--   자기 versionCode 가 그보다 낮으면 업데이트 화면에서 멈춘다.
--   값은 app_config 테이블에 두어 서버 재배포 없이 Supabase 대시보드에서 바꿀 수 있다.
--   min_version_code = 0 이면 아무도 막지 않는다(초기값). 올릴 때만 강제.
--
-- 전부 nullable 추가 컬럼이라 구버전 앱/코드에 영향 없음. 재실행 안전(IF NOT EXISTS).
-- ==========================================================

-- 1) users — 클라이언트 상태 (앱이 보고, NULL = 아직 보고한 적 없음 = 구버전 앱)
ALTER TABLE public.users ADD COLUMN IF NOT EXISTS app_version_code    INTEGER     NULL;  -- 예: 61
ALTER TABLE public.users ADD COLUMN IF NOT EXISTS app_version_name    TEXT        NULL;  -- 예: '1.1.19'
ALTER TABLE public.users ADD COLUMN IF NOT EXISTS battery_opt_ignored BOOLEAN     NULL;  -- 당봄케어가 배터리 최적화 예외인가
ALTER TABLE public.users ADD COLUMN IF NOT EXISTS shealth_installed   BOOLEAN     NULL;  -- 삼성헬스 설치 여부
ALTER TABLE public.users ADD COLUMN IF NOT EXISTS shealth_opt_ignored BOOLEAN     NULL;  -- 삼성헬스 최적화 예외(참고용, 기기따라 조회 불가)
ALTER TABLE public.users ADD COLUMN IF NOT EXISTS health_source_status TEXT       NULL;  -- sdk_direct | hc_ok | hc_partial:n/m | hc_no_permission | hc_update_required | hc_unavailable
ALTER TABLE public.users ADD COLUMN IF NOT EXISTS device_model        TEXT        NULL;  -- 예: 'samsung SM-S911N'
ALTER TABLE public.users ADD COLUMN IF NOT EXISTS os_sdk              INTEGER     NULL;  -- Android API 레벨 (예: 34)
ALTER TABLE public.users ADD COLUMN IF NOT EXISTS client_status_at    TIMESTAMPTZ NULL;  -- 마지막 보고 시각

-- 2) app_config — 키/값 설정 (강제 업데이트 정책)
CREATE TABLE IF NOT EXISTS public.app_config (
    key         TEXT PRIMARY KEY,
    value       TEXT NOT NULL,
    description TEXT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 초기값: 아무도 막지 않음(min=0). 강제하려면 min_version_code 값을 올린다.
INSERT INTO public.app_config (key, value, description) VALUES
    ('min_version_code',    '0',  '이 versionCode 미만 앱은 실행 시 업데이트 화면에서 멈춤. 0 = 강제 안 함'),
    ('latest_version_code', '61', '현재 스토어 최신 versionCode (안내용)'),
    ('store_url',           'https://play.google.com/store/apps/details?id=com.dangbomcare.app', '업데이트 버튼이 여는 주소'),
    ('update_message',      '더 정확한 걸음·수면 기록을 위해 앱을 최신 버전으로 업데이트해 주세요.', '업데이트 화면 안내 문구')
ON CONFLICT (key) DO NOTHING;

-- RLS: 정책 없이 활성화 = anon/authenticated 로는 읽기·쓰기 모두 거부.
--   백엔드는 postgres 직결(우회)이라 영향 없음. 값 수정은 Supabase 대시보드 Table Editor/SQL 로.
ALTER TABLE public.app_config ENABLE ROW LEVEL SECURITY;

-- 확인
SELECT column_name, data_type
FROM information_schema.columns
WHERE table_schema = 'public' AND table_name = 'users'
  AND column_name IN ('app_version_code','app_version_name','battery_opt_ignored','shealth_installed',
                      'shealth_opt_ignored','health_source_status','device_model','os_sdk','client_status_at')
ORDER BY column_name;

SELECT key, value FROM public.app_config ORDER BY key;
