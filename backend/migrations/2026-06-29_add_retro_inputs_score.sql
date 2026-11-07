-- [260623 피드백] 전날 회고 입력항목 4개 추가 + 점수 재배분
-- 적용: Supabase SQL 에디터 또는 psql 로 실행 (재실행 안전: IF NOT EXISTS / DROP IF EXISTS)

-- ── daily_logs: 회고 신규 컬럼 4개 ──
ALTER TABLE daily_logs ADD COLUMN IF NOT EXISTS no_food_after_dinner_hours TEXT;  -- 식후 금식 시간 5단계
ALTER TABLE daily_logs ADD COLUMN IF NOT EXISTS dinner_type JSON;                 -- 저녁식사 유형 (중복 선택 리스트). 모델 generic JSON과 일치(create_all=json), steps_hourly 등 기존 JSON 컬럼과 동일 타입 → staging(create_all)·운영(migration) 스키마 일치.
ALTER TABLE daily_logs ADD COLUMN IF NOT EXISTS digestion TEXT;                   -- 자기 전 소화 상태
ALTER TABLE daily_logs ADD COLUMN IF NOT EXISTS post_meal_exercise TEXT;          -- 식후 운동

ALTER TABLE daily_logs DROP CONSTRAINT IF EXISTS daily_logs_nofood_hours_check;
ALTER TABLE daily_logs ADD CONSTRAINT daily_logs_nofood_hours_check
  CHECK (no_food_after_dinner_hours IS NULL OR no_food_after_dinner_hours IN ('1h_under','1to2h','2to3h','3to4h','4h_over'));

ALTER TABLE daily_logs DROP CONSTRAINT IF EXISTS daily_logs_digestion_check;
ALTER TABLE daily_logs ADD CONSTRAINT daily_logs_digestion_check
  CHECK (digestion IS NULL OR digestion IN ('hungry','partial','full'));

ALTER TABLE daily_logs DROP CONSTRAINT IF EXISTS daily_logs_post_meal_exercise_check;
ALTER TABLE daily_logs ADD CONSTRAINT daily_logs_post_meal_exercise_check
  CHECK (post_meal_exercise IS NULL OR post_meal_exercise IN ('none','10to30','30to60','60plus'));

-- ── lifestyle_scores: 점수 신규 컬럼 3개 ──
ALTER TABLE lifestyle_scores ADD COLUMN IF NOT EXISTS dinner_type_score INTEGER;
ALTER TABLE lifestyle_scores ADD COLUMN IF NOT EXISTS digestion_score DOUBLE PRECISION;  -- 소화 중간 2.5점 허용
ALTER TABLE lifestyle_scores ADD COLUMN IF NOT EXISTS post_meal_exercise_score INTEGER;
-- 소화 2.5점 등 소수점 합산을 반영하려면 total_score 도 소수 허용으로 (SQLAlchemy Float = DOUBLE PRECISION)
ALTER TABLE lifestyle_scores ALTER COLUMN total_score TYPE DOUBLE PRECISION;
