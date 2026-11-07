-- ==========================================================
-- 마이그레이션: glucose_range_history 테이블 신설 + 초기 백필
-- 적용일: 2026-07-19 (v1.1.1 피드백 — 260719)
--
-- 배경:
--   지금까지는 users.glucose_normal_low/high만 저장(현재 값 덮어씀) → 리포트 조회 시
--   과거 시점 리포트에도 현재 범위가 표시되는 문제.
--   → 이력 테이블로 시점별 유효 범위 추적. 리포트는 이력 조회로 그 시점 범위 사용.
--
-- 안전:
--   IF NOT EXISTS / IF EXISTS 사용해서 여러 번 실행해도 안전.
-- ==========================================================

-- 1) 테이블 생성
CREATE TABLE IF NOT EXISTS public.glucose_range_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    range_low INT NOT NULL,
    range_high INT NOT NULL,
    effective_from DATE NOT NULL,
    saved_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT glucose_range_history_bounds_check CHECK (
        range_low >= 40 AND range_high <= 400 AND range_low <= range_high
    )
);

-- 2) 인덱스 (조회 최적화)
CREATE INDEX IF NOT EXISTS idx_glucose_range_history_user_date
    ON public.glucose_range_history (user_id, effective_from);

-- 3) 백필 — 이미 range 설정된 사용자들에게 초기 이력 자동 삽입.
--    effective_from = users.created_at 날짜 (이 사용자의 계정 생성 시점부터 이 범위였다고 가정).
--    이미 이력 있는 사용자는 스킵.
INSERT INTO public.glucose_range_history (user_id, range_low, range_high, effective_from, saved_at)
SELECT
    u.id,
    u.glucose_normal_low,
    u.glucose_normal_high,
    COALESCE(u.created_at::date, CURRENT_DATE),
    COALESCE(u.created_at, NOW())
FROM public.users u
WHERE u.glucose_normal_low IS NOT NULL
  AND u.glucose_normal_high IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM public.glucose_range_history h WHERE h.user_id = u.id
  );

-- 4) 결과 확인
SELECT
    COUNT(DISTINCT user_id) AS users_with_history,
    COUNT(*) AS total_history_rows
FROM public.glucose_range_history;
