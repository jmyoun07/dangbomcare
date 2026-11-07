-- ==========================================================
-- 당당케어 마이그레이션 — medical_goals 이력(append-only) 전환 (우노 4: 과거 목표 ◀▶)
-- 실행: Supabase Dashboard → SQL Editor → New Query → 붙여넣기 → Run
-- 기존 medical_goals(1:1, user_id PK)를 '사용자당 여러 행(이력)' 테이블로 전환.
-- 여러 번 실행해도 안전(idempotent). 기존 데이터는 그대로 보존됨(가장 오래된 이력으로 남음).
-- ==========================================================

-- 1) id PK 추가 + user_id PK 해제 → 사용자당 여러 행(이력) 허용
ALTER TABLE public.medical_goals ADD COLUMN IF NOT EXISTS id UUID DEFAULT uuid_generate_v4();
UPDATE public.medical_goals SET id = uuid_generate_v4() WHERE id IS NULL;
ALTER TABLE public.medical_goals ALTER COLUMN id SET NOT NULL;
ALTER TABLE public.medical_goals DROP CONSTRAINT IF EXISTS medical_goals_pkey;
ALTER TABLE public.medical_goals ADD PRIMARY KEY (id);
ALTER TABLE public.medical_goals ALTER COLUMN user_id SET NOT NULL;

-- 2) 최신/이력 조회용 인덱스 (user_id + created_at desc)
CREATE INDEX IF NOT EXISTS idx_medical_goals_user ON public.medical_goals (user_id, created_at DESC);

-- RLS(환자 본인 조회만)는 기존 정책(medical_goals_select_own) 그대로 유효.

DO $$
BEGIN
    RAISE NOTICE '✅ medical_goals 이력 테이블 전환 완료 (우노 4 과거 목표 ◀▶)';
END $$;
