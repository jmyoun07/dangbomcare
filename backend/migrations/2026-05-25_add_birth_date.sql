-- ==========================================================
-- 마이그레이션: users 테이블에 birth_date 컬럼 추가
-- 적용일: 2026-05-25 (v1.9 회원가입 흐름 재설계)
--
-- 변경 사유:
--   사용자 피드백 — "연령(int) 한 칸만 받지 말고 생년월일로 받자.
--   매년 한 살 추가되는 걸 사용자가 다시 수정해야 하는 부담 제거."
--
-- 적용 방법 (Supabase SQL Editor 또는 psql):
--   이 파일 전체를 복사해서 한 번 실행. IF NOT EXISTS 라 여러 번 실행해도 안전.
-- ==========================================================

-- 1) birth_date 컬럼 추가 (NULL 허용)
ALTER TABLE public.users
    ADD COLUMN IF NOT EXISTS birth_date DATE;

-- 2) 기존 사용자 birth_date 추정값 채움 (1월 1일 기준)
--    정확한 생년월일은 사용자가 본인 프로필에서 직접 수정 가능
UPDATE public.users
SET birth_date = make_date(
    EXTRACT(YEAR FROM CURRENT_DATE)::INT - COALESCE(age, 40),
    1, 1
)
WHERE birth_date IS NULL AND age IS NOT NULL;

-- 3) 결과 확인
SELECT
    COUNT(*) AS total_users,
    COUNT(birth_date) AS users_with_birth_date,
    COUNT(*) FILTER (WHERE age IS NOT NULL AND birth_date IS NULL) AS legacy_age_only
FROM public.users;
