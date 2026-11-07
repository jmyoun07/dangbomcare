-- ==========================================================
-- 당당케어 (DangDangCare) v1.9 Supabase PostgreSQL DDL
-- 실행: Supabase Dashboard → SQL Editor → New Query → 붙여넣기
-- ==========================================================
-- ⚠️ [260726] 베이스라인 전용 파일입니다.
--   이 DDL 은 '빈 DB 를 v1.9 시점 스키마로 만드는' 용도이며, 이후 변경분을 전부 담고 있지 않습니다.
--   운영/스테이징 DB 에 반영할 때는 이 파일만 실행하지 말고,
--   반드시 migrations/ 디렉터리의 마이그레이션을 순서대로 전량 적용하세요.
--   (CREATE TABLE IF NOT EXISTS 라서 기존 DB 에서는 컬럼 추가가 적용되지 않습니다.)
-- ==========================================================

-- UUID 확장 (Supabase는 기본 활성화되어 있지만 안전하게)
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ==========================================================
-- 1. users (Supabase auth.users와 1:1)
-- ==========================================================
CREATE TABLE IF NOT EXISTS public.users (
    id UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    email VARCHAR UNIQUE NOT NULL,
    patient_number VARCHAR UNIQUE,
    name VARCHAR,
    gender VARCHAR CHECK (gender IN ('M', 'F')),
    age INT CHECK (age > 0 AND age < 150),  -- legacy — birth_date에서 자동 derive
    birth_date DATE,                         -- 새 필드 (yyyy-mm-dd) — primary source
    height_cm FLOAT CHECK (height_cm BETWEEN 100 AND 250),
    weight_kg FLOAT CHECK (weight_kg BETWEEN 20 AND 200),
    diabetes_diagnosed_at DATE,
    onboarding_completed BOOLEAN DEFAULT FALSE,
    fcm_token VARCHAR,
    -- [P0 회원탈퇴 30일 정책] soft-delete 컬럼
    deletion_requested_at TIMESTAMPTZ,
    deletion_scheduled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- idempotent: 기존 DB에 컬럼 없으면 추가
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='users' AND column_name='deletion_requested_at') THEN
        ALTER TABLE public.users ADD COLUMN deletion_requested_at TIMESTAMPTZ;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='users' AND column_name='deletion_scheduled_at') THEN
        ALTER TABLE public.users ADD COLUMN deletion_scheduled_at TIMESTAMPTZ;
    END IF;
END $$;

-- ==========================================================
-- 2. user_consents
-- ==========================================================
CREATE TABLE IF NOT EXISTS public.user_consents (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL UNIQUE REFERENCES public.users(id) ON DELETE CASCADE,
    terms_accepted BOOLEAN NOT NULL,
    privacy_accepted BOOLEAN NOT NULL,
    sensitive_data_accepted BOOLEAN NOT NULL,
    -- [260726] 개인정보·민감정보 제3자 제공 동의 (당봄한의원 의료진 열람) — 260723 최종안 기준 필수 4번째 동의.
    --   베이스라인에 누락돼 있어 fresh install 스키마가 models.py 와 어긋났다.
    third_party_accepted BOOLEAN NOT NULL DEFAULT FALSE,
    -- [260728] 마케팅 활용 및 광고성 정보 수신 동의 — **선택** 항목(5번째).
    --   ⚠️ 필수 동의 게이트(앱 NavGraph/SplashScreen 의 hasConsents 판정)에 절대 포함시키지 말 것.
    --      섞이면 기존 가입자 전원이 동의 화면으로 되돌아간다(260726에 실제로 발생한 사고).
    --   시각 컬럼이 따로 있는 이유: 우노 문서가 수집 항목에 "동의·철회 시각"을 명시했고,
    --      accepted_at 은 필수 동의 시각 하나뿐이라 설정 토글로 반복되는 동의/철회를 담을 수 없다.
    marketing_accepted BOOLEAN NOT NULL DEFAULT FALSE,
    marketing_accepted_at TIMESTAMPTZ NULL,   -- 마지막 동의 시각 (동의한 적 없으면 NULL)
    marketing_revoked_at TIMESTAMPTZ NULL,    -- 마지막 철회 시각 (철회한 적 없으면 NULL)
    research_use_accepted BOOLEAN DEFAULT FALSE,
    accepted_at TIMESTAMPTZ DEFAULT NOW()
);

-- ==========================================================
-- 3. user_settings
-- ==========================================================
CREATE TABLE IF NOT EXISTS public.user_settings (
    user_id UUID PRIMARY KEY REFERENCES public.users(id) ON DELETE CASCADE,
    glucose_check_time TIME DEFAULT '07:00',
    dinner_deadline_time TIME DEFAULT '19:00',
    bedtime TIME DEFAULT '23:00',
    notif_morning BOOLEAN DEFAULT TRUE,
    notif_morning_fallback BOOLEAN DEFAULT TRUE,
    notif_dinner_deadline BOOLEAN DEFAULT TRUE,
    notif_bedtime BOOLEAN DEFAULT FALSE,
    notif_quiet_during_sleep BOOLEAN DEFAULT TRUE,
    timezone VARCHAR DEFAULT 'Asia/Seoul',
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- ==========================================================
-- 4. daily_logs (핵심 — 공복혈당 + 회고)
-- ==========================================================
CREATE TABLE IF NOT EXISTS public.daily_logs (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    log_date DATE NOT NULL,
    fasting_glucose INT CHECK (fasting_glucose IS NULL OR (fasting_glucose BETWEEN 10 AND 600)),
    fasting_glucose_time TIMESTAMPTZ,
    alcohol BOOLEAN,
    dinner_before_7pm BOOLEAN,
    no_food_after_dinner BOOLEAN,
    input_method VARCHAR CHECK (input_method IS NULL OR input_method IN ('morning', 'fallback', 'manual')),
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    CONSTRAINT uq_daily_logs_user_date UNIQUE (user_id, log_date)
);
CREATE INDEX IF NOT EXISTS idx_daily_logs_user_date ON public.daily_logs (user_id, log_date);

-- ==========================================================
-- 5. watch_data (v1.9: SpO2 + steps_hourly)
-- ==========================================================
CREATE TABLE IF NOT EXISTS public.watch_data (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    log_date DATE NOT NULL,
    steps INT DEFAULT 0,
    steps_hourly JSONB,
    sleep_total_minutes INT,
    sleep_deep_minutes INT,
    sleep_light_minutes INT,
    sleep_rem_minutes INT,
    sleep_awake_minutes INT,
    sleep_start_at TIMESTAMPTZ,
    sleep_end_at TIMESTAMPTZ,
    avg_heart_rate INT,
    min_heart_rate INT,
    max_heart_rate INT,
    heart_rate_samples JSONB,
    spo2_avg FLOAT,
    spo2_min FLOAT,
    last_synced_at TIMESTAMPTZ DEFAULT NOW(),
    synced_at TIMESTAMPTZ DEFAULT NOW(),
    CONSTRAINT uq_watch_data_user_date UNIQUE (user_id, log_date)
);
CREATE INDEX IF NOT EXISTS idx_watch_data_user_date ON public.watch_data (user_id, log_date);

-- ==========================================================
-- 6. stress_data (HR 변동성 기반 자체 추정 — v1.9)
-- ==========================================================
CREATE TABLE IF NOT EXISTS public.stress_data (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    log_date DATE NOT NULL,
    morning_status VARCHAR CHECK (morning_status IS NULL OR morning_status IN ('적정', '주의', '높음')),
    afternoon_status VARCHAR CHECK (afternoon_status IS NULL OR afternoon_status IN ('적정', '주의', '높음')),
    evening_status VARCHAR CHECK (evening_status IS NULL OR evening_status IN ('적정', '주의', '높음')),
    below_avg_ratio FLOAT,
    avg_variability FLOAT,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    CONSTRAINT uq_stress_data_user_date UNIQUE (user_id, log_date)
);

-- ==========================================================
-- 7. lifestyle_scores (100점 — v1.9 배점)
-- ==========================================================
CREATE TABLE IF NOT EXISTS public.lifestyle_scores (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    score_date DATE NOT NULL,
    total_score INT,
    glucose_score INT,
    steps_score INT,
    sleep_score INT,
    dinner_before_7pm_score INT,
    alcohol_score INT,
    no_food_after_dinner_score INT,
    stress_score INT,
    post_meal_walking_bonus INT DEFAULT 0,
    score_label VARCHAR CHECK (score_label IS NULL OR score_label IN ('좋음', '보통', '개선 가능', '측정 권장 (혈당 미입력)', '데이터 부족')),
    has_missing_data BOOLEAN DEFAULT FALSE,
    calculated_at TIMESTAMPTZ DEFAULT NOW(),
    CONSTRAINT uq_lifestyle_scores_user_date UNIQUE (user_id, score_date)
);
CREATE INDEX IF NOT EXISTS idx_scores_user_date ON public.lifestyle_scores (user_id, score_date);

-- ==========================================================
-- 8. ai_feedbacks
-- ==========================================================
CREATE TABLE IF NOT EXISTS public.ai_feedbacks (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    feedback_date DATE NOT NULL,
    daily_message TEXT,
    influencing_factors JSONB,
    encouragement VARCHAR,
    daily_mission TEXT,
    -- [260714 정리 H7] hydration 미션 폐지. fresh install에서 회귀 방지를 위해 이 DDL에서도 제거.
    mission_type VARCHAR CHECK (mission_type IS NULL OR mission_type IN ('steps', 'sleep', 'alcohol', 'dinner_timing', 'stretching', 'stress_relief', 'general')),
    mission_target JSONB,
    mission_progress FLOAT DEFAULT 0,
    mission_completed BOOLEAN DEFAULT FALSE,
    mission_tip VARCHAR,
    gpt_model VARCHAR,
    gpt_input_tokens INT,
    gpt_output_tokens INT,
    gpt_cost_krw FLOAT,
    weekly_streak_days INT DEFAULT 0,
    weekly_streak_total INT DEFAULT 7,
    monthly_streak_days INT DEFAULT 0,
    -- [260726] AI 재생성 카운터 (log_date별 하드리밋) — 베이스라인에 누락돼 있어 models.py 와 어긋났다.
    regen_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    CONSTRAINT uq_ai_feedbacks_user_date UNIQUE (user_id, feedback_date)
);
CREATE INDEX IF NOT EXISTS idx_feedbacks_user_date ON public.ai_feedbacks (user_id, feedback_date);

-- ==========================================================
-- 9. weekly_reports
-- ==========================================================
CREATE TABLE IF NOT EXISTS public.weekly_reports (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    week_start_date DATE NOT NULL,
    week_end_date DATE NOT NULL,
    avg_score FLOAT,
    score_change_vs_prev INT,
    avg_glucose FLOAT,
    avg_steps FLOAT,
    avg_sleep_hours FLOAT,
    alcohol_days INT,
    dinner_before_7pm_days INT,
    late_eating_days INT,
    top_risk_factors JSONB,
    correlation_insights JSONB,
    gpt_summary TEXT,
    gpt_model VARCHAR,
    gpt_cost_krw FLOAT,
    pdf_url VARCHAR,
    pdf_generated_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    CONSTRAINT uq_weekly_reports_user_date UNIQUE (user_id, week_start_date)
);

-- ==========================================================
-- 10. monthly_reports
-- ==========================================================
CREATE TABLE IF NOT EXISTS public.monthly_reports (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    year INT NOT NULL,
    month INT NOT NULL CHECK (month BETWEEN 1 AND 12),
    avg_score FLOAT,
    score_change_vs_prev INT,
    avg_glucose FLOAT,
    avg_steps FLOAT,
    avg_sleep_hours FLOAT,
    alcohol_days INT,
    dinner_before_7pm_days INT,
    late_eating_days INT,
    weekly_scores JSONB,
    top_risk_factors JSONB,
    correlation_insights JSONB,
    gpt_summary TEXT,
    gpt_model VARCHAR,
    gpt_cost_krw FLOAT,
    pdf_url VARCHAR,
    pdf_generated_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    CONSTRAINT uq_monthly_reports_user_year_month UNIQUE (user_id, year, month)
);

-- ==========================================================
-- 11. doctor_notes (v1.x 의료진 어드민 — 테이블만 미리)
-- ==========================================================
CREATE TABLE IF NOT EXISTS public.doctor_notes (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    patient_id UUID NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    doctor_email VARCHAR NOT NULL,
    note_date DATE NOT NULL,
    note_text TEXT NOT NULL,
    visible_to_patient BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_doctor_notes_patient ON public.doctor_notes (patient_id, note_date);

-- ==========================================================
-- 12. medical_goals (우노 피드백 4 — 의료진 진료 시 목표 설정)
--   PIN 잠금은 클라이언트 처리. 환자는 SELECT(보기)만, 쓰기는 백엔드(service role) 경유.
-- ==========================================================
CREATE TABLE IF NOT EXISTS public.medical_goals (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,  -- 사용자당 여러 행(이력)
    target_hba1c FLOAT,                 -- 당화혈색소 % (예: 5.2)
    target_pancreas FLOAT,              -- 췌장수치
    target_fasting_glucose INT,         -- 공복혈당 mg/dL (예: 120)
    target_period VARCHAR,              -- 목표 시점 ISO 날짜 (yyyy-MM-dd)
    lifestyle_goals TEXT,               -- 서술식 생활습관 목표
    set_by VARCHAR,                     -- 입력 의료진 (선택, 향후 의료진 웹)
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_medical_goals_user ON public.medical_goals (user_id, created_at DESC);

-- ==========================================================
-- RLS (Row Level Security) 정책 — 사용자는 본인 데이터만 접근
-- ==========================================================
ALTER TABLE public.users ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.user_consents ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.user_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.daily_logs ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.watch_data ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.stress_data ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.lifestyle_scores ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.ai_feedbacks ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.weekly_reports ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.monthly_reports ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.doctor_notes ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.medical_goals ENABLE ROW LEVEL SECURITY;

-- users: 본인만 SELECT/UPDATE
DROP POLICY IF EXISTS "users_select_own" ON public.users;
CREATE POLICY "users_select_own" ON public.users FOR SELECT USING (auth.uid() = id);
DROP POLICY IF EXISTS "users_update_own" ON public.users;
CREATE POLICY "users_update_own" ON public.users FOR UPDATE USING (auth.uid() = id);
DROP POLICY IF EXISTS "users_insert_own" ON public.users;
CREATE POLICY "users_insert_own" ON public.users FOR INSERT WITH CHECK (auth.uid() = id);

-- user_consents
DROP POLICY IF EXISTS "consents_all_own" ON public.user_consents;
CREATE POLICY "consents_all_own" ON public.user_consents FOR ALL USING (auth.uid() = user_id);

-- user_settings
DROP POLICY IF EXISTS "settings_all_own" ON public.user_settings;
CREATE POLICY "settings_all_own" ON public.user_settings FOR ALL USING (auth.uid() = user_id);

-- daily_logs
DROP POLICY IF EXISTS "daily_logs_all_own" ON public.daily_logs;
CREATE POLICY "daily_logs_all_own" ON public.daily_logs FOR ALL USING (auth.uid() = user_id);

-- watch_data
DROP POLICY IF EXISTS "watch_data_all_own" ON public.watch_data;
CREATE POLICY "watch_data_all_own" ON public.watch_data FOR ALL USING (auth.uid() = user_id);

-- stress_data
DROP POLICY IF EXISTS "stress_data_all_own" ON public.stress_data;
CREATE POLICY "stress_data_all_own" ON public.stress_data FOR ALL USING (auth.uid() = user_id);

-- lifestyle_scores
DROP POLICY IF EXISTS "scores_all_own" ON public.lifestyle_scores;
CREATE POLICY "scores_all_own" ON public.lifestyle_scores FOR ALL USING (auth.uid() = user_id);

-- ai_feedbacks
DROP POLICY IF EXISTS "feedbacks_all_own" ON public.ai_feedbacks;
CREATE POLICY "feedbacks_all_own" ON public.ai_feedbacks FOR ALL USING (auth.uid() = user_id);

-- weekly_reports
DROP POLICY IF EXISTS "weekly_reports_all_own" ON public.weekly_reports;
CREATE POLICY "weekly_reports_all_own" ON public.weekly_reports FOR ALL USING (auth.uid() = user_id);

-- monthly_reports
DROP POLICY IF EXISTS "monthly_reports_all_own" ON public.monthly_reports;
CREATE POLICY "monthly_reports_all_own" ON public.monthly_reports FOR ALL USING (auth.uid() = user_id);

-- doctor_notes (환자는 visible_to_patient=true인 것만 조회)
DROP POLICY IF EXISTS "doctor_notes_patient_view" ON public.doctor_notes;
CREATE POLICY "doctor_notes_patient_view" ON public.doctor_notes
    FOR SELECT USING (auth.uid() = patient_id AND visible_to_patient = TRUE);

-- medical_goals (환자는 본인 목표 '조회'만 — 쓰기는 백엔드 service role 경유, 환자 수정 불가)
DROP POLICY IF EXISTS "medical_goals_select_own" ON public.medical_goals;
CREATE POLICY "medical_goals_select_own" ON public.medical_goals
    FOR SELECT USING (auth.uid() = user_id);

-- ==========================================================
-- 트리거: auth.users 생성 시 public.users 자동 생성
-- ==========================================================
CREATE OR REPLACE FUNCTION public.handle_new_user()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO public.users (id, email)
    VALUES (NEW.id, NEW.email)
    ON CONFLICT (id) DO NOTHING;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

DROP TRIGGER IF EXISTS on_auth_user_created ON auth.users;
CREATE TRIGGER on_auth_user_created
    AFTER INSERT ON auth.users
    FOR EACH ROW EXECUTE FUNCTION public.handle_new_user();

-- ==========================================================
-- 완료 메시지
-- ==========================================================
DO $$
BEGIN
    RAISE NOTICE '✅ 당당케어 v1.9 DDL 적용 완료 (11 tables + RLS + auth trigger)';
END $$;
