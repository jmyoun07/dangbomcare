-- ==========================================================
-- 당당케어 마이그레이션 — medical_goals (우노 피드백 4·5)
-- 실행: Supabase Dashboard → SQL Editor → New Query → 붙여넣기 → Run
-- 기존 v1.9 DB에 의료진 목표 테이블만 추가. 여러 번 실행해도 안전(idempotent).
-- ==========================================================

CREATE TABLE IF NOT EXISTS public.medical_goals (
    user_id UUID PRIMARY KEY REFERENCES public.users(id) ON DELETE CASCADE,
    target_hba1c FLOAT,                 -- 당화혈색소 % (예: 5.2)
    target_pancreas FLOAT,              -- 췌장수치
    target_fasting_glucose INT,         -- 공복혈당 mg/dL (예: 120)
    target_period VARCHAR,              -- 목표 시점 (예: "3개월 뒤")
    lifestyle_goals TEXT,               -- 서술식 생활습관 목표
    set_by VARCHAR,                     -- 입력 의료진 (선택)
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- RLS: 환자는 본인 목표 '조회'만 가능. 쓰기는 백엔드(service role)만 → 환자 앱에서 수정 불가.
ALTER TABLE public.medical_goals ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS "medical_goals_select_own" ON public.medical_goals;
CREATE POLICY "medical_goals_select_own" ON public.medical_goals
    FOR SELECT USING (auth.uid() = user_id);

DO $$
BEGIN
    RAISE NOTICE '✅ medical_goals 테이블 적용 완료 (우노 피드백 4·5)';
END $$;
