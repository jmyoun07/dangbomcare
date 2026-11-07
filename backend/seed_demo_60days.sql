-- ==========================================================
-- 당봄케어 데모용 두 달치(60일) 시드 데이터
-- 공복혈당 / 워치 / 스트레스 / 점수 / 피드백
-- ==========================================================
-- 사용법:
--   1. 앱에서 신규 회원가입 → 온보딩 완료
--   2. Supabase → SQL Editor → New Query → 이 SQL 전체 붙여넣기 → Run
--      (기본: 가장 최근 가입 사용자. 특정 이메일 강제하려면 L86 근처 수정)
--   3. 앱 재시작 → 60일치 raw 데이터 확인
--   4. 실제 AI 피드백 보려면 → 앱에서 오늘 아침 회고 입력
--      (홈 화면 카드는 오늘 회고를 저장할 때 백엔드가 GPT 호출해서 생성)
--   5. 실제 주간/월간 리포트 보려면 → 앱에서 리포트 탭 진입
--      (원시 데이터로 실시간 계산: 평균점수/공복혈당 달력/위험 요인/상관관계)
--
-- 다양성 패턴 (60일):
--   최근 31일 = 원본 seed_demo_full_month.sql 그대로
--   31~60일 = 최근 31일 패턴을 그대로 반복(29일치)
-- ==========================================================

DO $$
DECLARE
    test_user_id UUID;
    target_date DATE;
    day_offset INT;
    glucose_value INT;
    pattern_idx INT;
    has_alcohol BOOLEAN;
    dinner_before_7 BOOLEAN;
    no_late_food BOOLEAN;
    steps_v INT;
    sleep_total INT;
    sleep_deep INT;
    sleep_light INT;
    sleep_rem INT;
    sleep_awake INT;
    sleep_start TIMESTAMPTZ;
    sleep_end TIMESTAMPTZ;
    hr_avg INT;
    hr_min INT;
    hr_max INT;
    spo2_avg_v FLOAT;
    spo2_min_v FLOAT;
    stress_morning TEXT;
    stress_afternoon TEXT;
    stress_evening TEXT;
    below_avg FLOAT;
    variability FLOAT;
    total_score_v INT;
    glucose_score_v INT;
    steps_score_v INT;
    sleep_score_v INT;
    dinner_score_v INT;
    alcohol_score_v INT;
    no_food_score_v INT;
    stress_score_v INT;
    walking_bonus INT;
    score_label_v TEXT;
    -- 60일 패턴 배열 — 인덱스 1=오늘-1일, 60=오늘-60일
    -- 0:매우좋음 1:좋음 2:경계 3:약간높음 4:매우높음 5:미입력
    patterns INT[] := ARRAY[
        1, 2, 2, 0, 1, 3, 4,   -- 1~7일 전 (최근 1주)
        2, 1, 2, 3, 0, 2, 4,   -- 8~14일 전
        1, 3, 2, 0, 1, 5, 2,   -- 15~21일 전
        3, 2, 1, 5, 0, 2, 3,   -- 22~28일 전
        1, 2, 2,               -- 29~31일 전
        2, 3, 1, 0, 2, 4, 3,   -- 32~38일 전 (한 달 전 첫 주 — 관리 시작 초반)
        2, 3, 3, 1, 2, 5, 4,   -- 39~45일 전
        3, 2, 4, 1, 3, 2, 5,   -- 46~52일 전
        4, 3, 2, 3, 2, 1, 3,   -- 53~59일 전
        2                      -- 60일 전
    ];
BEGIN
    -- ──────────────────────────────────────────────────────────
    -- 대상 사용자 선택: 가장 최근 가입한 사용자
    -- (특정 이메일 강제하려면 아래 주석 처리된 쿼리 사용)
    -- ──────────────────────────────────────────────────────────
    SELECT id INTO test_user_id
    FROM public.users
    ORDER BY created_at DESC
    LIMIT 1;

    -- 특정 이메일로 강제하려면 위 SELECT 주석 처리 후 아래 활성화:
    -- SELECT id INTO test_user_id
    -- FROM public.users
    -- WHERE email = 'dlrudtn1211+demo@gmail.com';

    IF test_user_id IS NULL THEN
        RAISE EXCEPTION '사용자가 없습니다. Supabase Dashboard에서 먼저 계정을 만들고 로그인하세요.';
    END IF;

    RAISE NOTICE '대상 user_id: %', test_user_id;

    -- ──────────────────────────────────────────────────────────
    -- 1. users — 프로필 정보 채우기 (온보딩 완료 상태로)
    -- ──────────────────────────────────────────────────────────
    UPDATE public.users
    SET
        name = COALESCE(name, '김데모'),
        gender = COALESCE(gender, 'M'),
        age = COALESCE(age, 62),
        -- [v1.0] birth_date 추가 — SplashScreen hasAccountInfo 체크에 필요
        birth_date = COALESCE(birth_date, DATE '1964-03-15'),  -- 62세 기준
        height_cm = COALESCE(height_cm, 168.0),
        weight_kg = COALESCE(weight_kg, 71.5),
        diabetes_diagnosed_at = COALESCE(diabetes_diagnosed_at, CURRENT_DATE - INTERVAL '3 years'),
        onboarding_completed = TRUE,
        updated_at = NOW()
    WHERE id = test_user_id;

    -- ──────────────────────────────────────────────────────────
    -- 2. user_consents — 동의 정보
    -- ──────────────────────────────────────────────────────────
    -- [260726] third_party_accepted 누락 시 DEFAULT FALSE 로 들어가 데모 계정이 동의 화면에 갇힌다
    --   (제3자 제공 동의는 필수 4번째 동의). 명시적으로 TRUE.
    --   ⚠️ ON CONFLICT DO NOTHING 이므로, 이미 동의 행이 있는 계정은 이 SQL 로 갱신되지 않는다.
    INSERT INTO public.user_consents (user_id, terms_accepted, privacy_accepted, sensitive_data_accepted, third_party_accepted, research_use_accepted)
    VALUES (test_user_id, TRUE, TRUE, TRUE, TRUE, FALSE)
    ON CONFLICT (user_id) DO NOTHING;

    -- ──────────────────────────────────────────────────────────
    -- 3. user_settings — 기본 시간 설정
    -- ──────────────────────────────────────────────────────────
    INSERT INTO public.user_settings (user_id, glucose_check_time, dinner_deadline_time, bedtime)
    VALUES (test_user_id, '07:00', '19:00', '23:00')
    ON CONFLICT (user_id) DO NOTHING;

    -- ──────────────────────────────────────────────────────────
    -- 4. 기존 데모 데이터 정리 (재실행 가능하도록)
    -- ──────────────────────────────────────────────────────────
    DELETE FROM public.ai_feedbacks WHERE user_id = test_user_id;
    DELETE FROM public.lifestyle_scores WHERE user_id = test_user_id;
    DELETE FROM public.stress_data WHERE user_id = test_user_id;
    DELETE FROM public.watch_data WHERE user_id = test_user_id;
    DELETE FROM public.daily_logs WHERE user_id = test_user_id AND input_method = 'manual';
    DELETE FROM public.weekly_reports WHERE user_id = test_user_id;
    DELETE FROM public.monthly_reports WHERE user_id = test_user_id;

    -- ──────────────────────────────────────────────────────────
    -- 5. 31일치 데이터 루프
    -- ──────────────────────────────────────────────────────────
    FOR day_offset IN 1..60 LOOP
        target_date := CURRENT_DATE - day_offset;
        pattern_idx := patterns[day_offset];

        -- 패턴별 데이터 결정
        CASE pattern_idx
        WHEN 0 THEN  -- 매우 좋음
            glucose_value := 70 + (random() * 15)::int;
            has_alcohol := FALSE;
            dinner_before_7 := TRUE;
            no_late_food := TRUE;
            steps_v := 9500 + (random() * 2500)::int;
            sleep_total := 480 + (random() * 60)::int;  -- 8~9시간
            stress_morning := '적정';
            stress_afternoon := '적정';
            stress_evening := '적정';
            below_avg := 0.05 + random() * 0.10;
            variability := 0.6 + random() * 0.2;
            hr_avg := 68 + (random() * 6)::int;
            walking_bonus := 5;
        WHEN 1 THEN  -- 좋음
            glucose_value := 86 + (random() * 14)::int;
            has_alcohol := random() < 0.10;
            dinner_before_7 := random() < 0.80;
            no_late_food := random() < 0.85;
            steps_v := 7500 + (random() * 2000)::int;
            sleep_total := 420 + (random() * 60)::int;  -- 7~8시간
            stress_morning := '적정';
            stress_afternoon := CASE WHEN random() < 0.7 THEN '적정' ELSE '주의' END;
            stress_evening := '적정';
            below_avg := 0.10 + random() * 0.15;
            variability := 0.5 + random() * 0.2;
            hr_avg := 70 + (random() * 6)::int;
            walking_bonus := CASE WHEN random() < 0.4 THEN 3 ELSE 0 END;
        WHEN 2 THEN  -- 경계
            glucose_value := 100 + (random() * 25)::int;
            has_alcohol := random() < 0.20;
            dinner_before_7 := random() < 0.50;
            no_late_food := random() < 0.55;
            steps_v := 5000 + (random() * 2000)::int;
            sleep_total := 360 + (random() * 60)::int;  -- 6~7시간
            stress_morning := CASE WHEN random() < 0.5 THEN '적정' ELSE '주의' END;
            stress_afternoon := CASE WHEN random() < 0.5 THEN '주의' ELSE '적정' END;
            stress_evening := CASE WHEN random() < 0.6 THEN '적정' ELSE '주의' END;
            below_avg := 0.20 + random() * 0.20;
            variability := 0.4 + random() * 0.2;
            hr_avg := 74 + (random() * 6)::int;
            walking_bonus := 0;
        WHEN 3 THEN  -- 약간 높음
            glucose_value := 126 + (random() * 20)::int;
            has_alcohol := random() < 0.45;
            dinner_before_7 := random() < 0.30;
            no_late_food := random() < 0.30;
            steps_v := 3500 + (random() * 1500)::int;
            sleep_total := 300 + (random() * 60)::int;  -- 5~6시간
            stress_morning := '주의';
            stress_afternoon := CASE WHEN random() < 0.5 THEN '주의' ELSE '높음' END;
            stress_evening := '주의';
            below_avg := 0.35 + random() * 0.20;
            variability := 0.3 + random() * 0.2;
            hr_avg := 78 + (random() * 8)::int;
            walking_bonus := 0;
        WHEN 4 THEN  -- 매우 높음
            glucose_value := 146 + (random() * 20)::int;
            has_alcohol := TRUE;
            dinner_before_7 := FALSE;
            no_late_food := FALSE;
            steps_v := 2000 + (random() * 1500)::int;
            sleep_total := 240 + (random() * 60)::int;  -- 4~5시간
            stress_morning := '높음';
            stress_afternoon := '높음';
            stress_evening := CASE WHEN random() < 0.5 THEN '높음' ELSE '주의' END;
            below_avg := 0.55 + random() * 0.20;
            variability := 0.2 + random() * 0.2;
            hr_avg := 82 + (random() * 10)::int;
            walking_bonus := 0;
        ELSE  -- 5: 미입력 (워치 미착용·휴가)
            -- 모든 데이터 NULL — 아예 INSERT 스킵
            CONTINUE;
        END CASE;

        -- 수면 단계별 분포 (전체의 비율)
        sleep_deep := (sleep_total * (0.13 + random() * 0.07))::int;
        sleep_rem := (sleep_total * (0.18 + random() * 0.07))::int;
        sleep_awake := (sleep_total * (0.03 + random() * 0.04))::int;
        sleep_light := sleep_total - sleep_deep - sleep_rem - sleep_awake;

        sleep_start := target_date::TIMESTAMPTZ + interval '23 hours' + interval '30 minutes' * random();
        sleep_end := sleep_start + (sleep_total || ' minutes')::interval + interval '15 minutes' * random();

        hr_min := hr_avg - 10 - (random() * 5)::int;
        hr_max := hr_avg + 30 + (random() * 20)::int;
        spo2_avg_v := 96.0 + random() * 2.0;
        spo2_min_v := spo2_avg_v - 2.0 - random() * 2.0;

        -- [v1.0] 점수 계산 — DB 컬럼 제약 맞춤 (각 카테고리별 max 값 다름)
        --   glucose 0-25, steps 0-20, sleep 0-20, dinner 0-10, alcohol 0-10,
        --   no_food 0-10, stress 0-5 (총합 100점)
        glucose_score_v := GREATEST(0, LEAST(25,
            CASE
                WHEN glucose_value <= 99 THEN 25
                WHEN glucose_value <= 125 THEN 20 - ((glucose_value - 100) / 5)::int
                WHEN glucose_value <= 145 THEN 14 - ((glucose_value - 126) / 4)::int
                ELSE 6 - ((glucose_value - 146) / 4)::int
            END
        ));
        steps_score_v := LEAST(20, (steps_v / 500)::int);
        sleep_score_v := LEAST(20, (sleep_total / 24)::int);
        dinner_score_v := CASE WHEN dinner_before_7 THEN 10 ELSE 5 END;
        alcohol_score_v := CASE WHEN has_alcohol THEN 5 ELSE 10 END;
        no_food_score_v := CASE WHEN no_late_food THEN 10 ELSE 5 END;
        stress_score_v := CASE
            WHEN stress_morning = '높음' OR stress_evening = '높음' THEN 2
            WHEN stress_morning = '주의' OR stress_evening = '주의' THEN 4
            ELSE 5
        END;

        total_score_v := LEAST(100, (
            glucose_score_v + steps_score_v + sleep_score_v +
            dinner_score_v + alcohol_score_v + no_food_score_v + stress_score_v
        ) + walking_bonus);

        score_label_v := CASE
            WHEN total_score_v >= 80 THEN '좋음'
            WHEN total_score_v >= 60 THEN '보통'
            ELSE '개선 가능'
        END;

        -- INSERT daily_logs
        INSERT INTO public.daily_logs (
            user_id, log_date, fasting_glucose, fasting_glucose_time,
            alcohol, dinner_before_7pm, no_food_after_dinner, input_method
        ) VALUES (
            test_user_id, target_date, glucose_value,
            (target_date::TIMESTAMPTZ + interval '7 hours' + interval '40 minutes' * random()),
            has_alcohol, dinner_before_7, no_late_food, 'manual'
        ) ON CONFLICT (user_id, log_date) DO UPDATE SET
            fasting_glucose = EXCLUDED.fasting_glucose,
            alcohol = EXCLUDED.alcohol,
            dinner_before_7pm = EXCLUDED.dinner_before_7pm,
            no_food_after_dinner = EXCLUDED.no_food_after_dinner;

        -- INSERT watch_data
        INSERT INTO public.watch_data (
            user_id, log_date, steps, sleep_total_minutes,
            sleep_deep_minutes, sleep_light_minutes, sleep_rem_minutes, sleep_awake_minutes,
            sleep_start_at, sleep_end_at,
            avg_heart_rate, min_heart_rate, max_heart_rate,
            spo2_avg, spo2_min
        ) VALUES (
            test_user_id, target_date, steps_v, sleep_total,
            sleep_deep, sleep_light, sleep_rem, sleep_awake,
            sleep_start, sleep_end,
            hr_avg, hr_min, hr_max,
            spo2_avg_v, spo2_min_v
        );

        -- INSERT stress_data
        INSERT INTO public.stress_data (
            user_id, log_date, morning_status, afternoon_status, evening_status,
            below_avg_ratio, avg_variability
        ) VALUES (
            test_user_id, target_date, stress_morning, stress_afternoon, stress_evening,
            below_avg, variability
        );

        -- INSERT lifestyle_scores
        INSERT INTO public.lifestyle_scores (
            user_id, score_date, total_score,
            glucose_score, steps_score, sleep_score,
            dinner_before_7pm_score, alcohol_score, no_food_after_dinner_score, stress_score,
            post_meal_walking_bonus, score_label, has_missing_data
        ) VALUES (
            test_user_id, target_date, total_score_v,
            glucose_score_v, steps_score_v, sleep_score_v,
            dinner_score_v, alcohol_score_v, no_food_score_v, stress_score_v,
            walking_bonus, score_label_v, FALSE
        );

    END LOOP;

    -- ──────────────────────────────────────────────────────────
    -- 6. AI 피드백 / 주간·월간 리포트는 시드하지 않음
    --   - ai_feedbacks: 홈 화면은 오늘 날짜만 조회. 오늘 아침 회고를
    --     앱에서 실제 입력 → 백엔드가 GPT 호출해 실제 문구 생성.
    --   - weekly/monthly_reports: 리포트 라우터가 이 테이블을 이미
    --     참조 안 함 (Codex 후속3, gpt_summary 폐지). 원시 데이터
    --     (daily_logs·watch_data·stress_data·lifestyle_scores)로 리포트
    --     화면이 실시간 계산.
    -- ──────────────────────────────────────────────────────────

    RAISE NOTICE '✅ 데모 데이터 시드 완료 (원시 데이터만)';
    RAISE NOTICE '- daily_logs / watch_data / stress_data / lifestyle_scores: 최대 60일치';
    RAISE NOTICE '   (패턴 5(미입력)로 지정된 날은 스킵됨)';
    RAISE NOTICE '- AI 피드백/리포트는 앱에서 실제 사용 시 자동 생성/계산됨';
END $$;


-- ==========================================================
-- 검증 — 최근 7일 + 패턴 분포
-- ==========================================================
SELECT
    log_date,
    fasting_glucose,
    CASE
        WHEN fasting_glucose IS NULL THEN '미입력'
        WHEN fasting_glucose < 70 THEN '저혈당'
        WHEN fasting_glucose <= 99 THEN '정상'
        WHEN fasting_glucose <= 125 THEN '경계'
        WHEN fasting_glucose <= 145 THEN '약간높음'
        ELSE '매우높음'
    END AS 혈당상태,
    alcohol AS 음주,
    dinner_before_7pm AS "7시전식사",
    no_food_after_dinner AS "야식없음"
FROM public.daily_logs
WHERE user_id = (SELECT id FROM public.users ORDER BY created_at DESC LIMIT 1)
  AND log_date >= CURRENT_DATE - 7
ORDER BY log_date DESC;

SELECT
    score_date,
    total_score,
    score_label,
    glucose_score,
    steps_score,
    sleep_score
FROM public.lifestyle_scores
WHERE user_id = (SELECT id FROM public.users ORDER BY created_at DESC LIMIT 1)
  AND score_date >= CURRENT_DATE - 7
ORDER BY score_date DESC;
