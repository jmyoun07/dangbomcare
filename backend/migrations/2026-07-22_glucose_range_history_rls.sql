-- ============================================================
-- glucose_range_history RLS 활성화
-- ============================================================
-- 이전에 이 테이블은 RLS 비활성 상태로 생성됨.
-- 백엔드는 postgres direct 연결이라 RLS 우회하지만,
-- Supabase anon key로 PostgREST 호출 시 무방비 노출되는 위험이 있음.
-- users, daily_logs와 동일하게 RLS + 사용자별 policy 적용.
--
-- [260722 재실행 안전] DROP POLICY IF EXISTS 선행으로 idempotent 화.
--   PostgreSQL 16 미만에서 CREATE POLICY IF NOT EXISTS 미지원이라
--   DROP → CREATE 순서로 재실행 시에도 duplicate_object 에러 회피.

ALTER TABLE public.glucose_range_history ENABLE ROW LEVEL SECURITY;

-- 자기 이력만 SELECT
DROP POLICY IF EXISTS glucose_range_history_own_select ON public.glucose_range_history;
CREATE POLICY glucose_range_history_own_select
    ON public.glucose_range_history
    FOR SELECT
    USING (auth.uid() = user_id);

-- 자기 이력만 INSERT (백엔드는 service role이라 bypass, 앱은 anon으로 직접 INSERT 안 함)
DROP POLICY IF EXISTS glucose_range_history_own_insert ON public.glucose_range_history;
CREATE POLICY glucose_range_history_own_insert
    ON public.glucose_range_history
    FOR INSERT
    WITH CHECK (auth.uid() = user_id);

-- UPDATE/DELETE 정책 부재는 의도됨:
--   RLS 활성 + 정책 없음 = default deny → 앱 anon이 직접 수정 불가.
--   백엔드 service role은 postgres 직결이라 bypass — 수정 필요 시 백엔드 경유.

-- 확인
SELECT tablename, rowsecurity
FROM pg_tables
WHERE schemaname = 'public' AND tablename = 'glucose_range_history';

-- 롤백용 (필요 시 주석 해제)
-- DROP POLICY IF EXISTS glucose_range_history_own_select ON public.glucose_range_history;
-- DROP POLICY IF EXISTS glucose_range_history_own_insert ON public.glucose_range_history;
-- ALTER TABLE public.glucose_range_history DISABLE ROW LEVEL SECURITY;
