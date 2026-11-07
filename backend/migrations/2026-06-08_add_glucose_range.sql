-- ==========================================================
-- 마이그레이션: users 테이블에 개인 공복혈당 범위 컬럼 추가
-- 적용일: 2026-06-08 (PDF 2 — 개인 공복혈당 범위 기반 채점)
--
-- 변경 사유:
--   당뇨 환자는 절대 정상치(70~100)를 넘을 수밖에 없으므로, 사용자가 설정한
--   '평소 공복혈당 범위'(예 130~150)를 기준으로 채점한다.
--   범위 상한 이하 = 평상시 = 만점, 상한 초과한 만큼만 감점.
--   (low/high 모두 NULL이면 기존 절대 기준으로 fallback → 기존 사용자 무영향)
--
--   ※ 의료진 목표(medical_goals.target_fasting_glucose, 예 110)와는 별개 개념:
--      목표 = AI 코칭의 지향점 / 범위 = 라이프스타일 점수의 채점 기준선.
--
-- 적용 방법 (Supabase SQL Editor 또는 psql):
--   이 파일 전체를 복사해서 한 번 실행. IF NOT EXISTS / DROP IF EXISTS 라 여러 번 실행해도 안전.
--   ⚠️ 반드시 백엔드 새 코드 배포 '전에' 먼저 실행 (컬럼이 있어야 새 코드가 정상 동작).
-- ==========================================================

-- 1) 컬럼 추가 (NULL 허용 — 미설정 사용자는 기존 절대 기준 채점)
ALTER TABLE public.users
    ADD COLUMN IF NOT EXISTS glucose_normal_low  INTEGER;
ALTER TABLE public.users
    ADD COLUMN IF NOT EXISTS glucose_normal_high INTEGER;

-- 2) 유효성 제약 — 둘 다 NULL 이거나, 둘 다 유효(40~400, low ≤ high)
ALTER TABLE public.users
    DROP CONSTRAINT IF EXISTS users_glucose_range_check;
ALTER TABLE public.users
    ADD CONSTRAINT users_glucose_range_check CHECK (
        (glucose_normal_low IS NULL AND glucose_normal_high IS NULL)
        OR (
            glucose_normal_low  IS NOT NULL AND glucose_normal_high IS NOT NULL
            AND glucose_normal_low  >= 40 AND glucose_normal_high <= 400
            AND glucose_normal_low  <= glucose_normal_high
        )
    );

-- 3) 결과 확인
SELECT
    COUNT(*)                                           AS total_users,
    COUNT(glucose_normal_high)                         AS users_with_range
FROM public.users;
