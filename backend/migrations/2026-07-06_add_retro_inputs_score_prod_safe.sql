-- [260706] 2026-06-29_add_retro_inputs_score.sql 의 '운영 적용용' 하드닝 버전 (Codex 검토 반영)
-- 차이점:
--   · lock_timeout / statement_timeout 설정 — 락 대기로 서비스가 멈추는 것 방지 (락 못 잡으면 실패 후 재시도)
--   · CHECK 제약은 '없을 때만' 추가 (재실행 시 drop/add 재검증 락 방지) + NOT VALID → VALIDATE 2단계
--   · total_score 타입 변경은 integer일 때만 실행 (이미 double이면 스킵)
-- 실행 전 preflight (아래 SELECT 3개가 모두 0행이어야 함):
--   SELECT count(*) FROM daily_logs WHERE no_food_after_dinner_hours IS NOT NULL
--     AND no_food_after_dinner_hours NOT IN ('1h_under','1to2h','2to3h','3to4h','4h_over');
--   SELECT count(*) FROM daily_logs WHERE digestion IS NOT NULL AND digestion NOT IN ('hungry','partial','full');
--   SELECT count(*) FROM daily_logs WHERE post_meal_exercise IS NOT NULL AND post_meal_exercise NOT IN ('none','10to30','30to60','60plus');

SET lock_timeout = '3s';
SET statement_timeout = '120s';

-- ── daily_logs: 회고 신규 컬럼 4개 (metadata-only, 즉시 완료) ──
ALTER TABLE daily_logs ADD COLUMN IF NOT EXISTS no_food_after_dinner_hours TEXT;
ALTER TABLE daily_logs ADD COLUMN IF NOT EXISTS dinner_type JSON;
ALTER TABLE daily_logs ADD COLUMN IF NOT EXISTS digestion TEXT;
ALTER TABLE daily_logs ADD COLUMN IF NOT EXISTS post_meal_exercise TEXT;

-- ── CHECK 제약: 없을 때만 NOT VALID로 추가 후 VALIDATE (기존 행 검증을 짧은 락으로 분리) ──
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'daily_logs_nofood_hours_check') THEN
    ALTER TABLE daily_logs ADD CONSTRAINT daily_logs_nofood_hours_check
      CHECK (no_food_after_dinner_hours IS NULL OR no_food_after_dinner_hours IN ('1h_under','1to2h','2to3h','3to4h','4h_over')) NOT VALID;
    ALTER TABLE daily_logs VALIDATE CONSTRAINT daily_logs_nofood_hours_check;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'daily_logs_digestion_check') THEN
    ALTER TABLE daily_logs ADD CONSTRAINT daily_logs_digestion_check
      CHECK (digestion IS NULL OR digestion IN ('hungry','partial','full')) NOT VALID;
    ALTER TABLE daily_logs VALIDATE CONSTRAINT daily_logs_digestion_check;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'daily_logs_post_meal_exercise_check') THEN
    ALTER TABLE daily_logs ADD CONSTRAINT daily_logs_post_meal_exercise_check
      CHECK (post_meal_exercise IS NULL OR post_meal_exercise IN ('none','10to30','30to60','60plus')) NOT VALID;
    ALTER TABLE daily_logs VALIDATE CONSTRAINT daily_logs_post_meal_exercise_check;
  END IF;
END $$;

-- ── lifestyle_scores: 점수 신규 컬럼 3개 ──
ALTER TABLE lifestyle_scores ADD COLUMN IF NOT EXISTS dinner_type_score INTEGER;
ALTER TABLE lifestyle_scores ADD COLUMN IF NOT EXISTS digestion_score DOUBLE PRECISION;
ALTER TABLE lifestyle_scores ADD COLUMN IF NOT EXISTS post_meal_exercise_score INTEGER;

-- ── total_score: integer일 때만 DOUBLE PRECISION으로 (테이블 재작성 락 — 새벽/저녁 저트래픽 시간대 권장) ──
DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_name = 'lifestyle_scores' AND column_name = 'total_score' AND data_type = 'integer'
  ) THEN
    ALTER TABLE lifestyle_scores ALTER COLUMN total_score TYPE DOUBLE PRECISION;
  END IF;
END $$;
