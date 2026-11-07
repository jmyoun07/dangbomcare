-- ==========================================================
-- 마이그레이션: ai_feedbacks.mission_type CHECK에서 'hydration' 제거
-- 적용일: 2026-07-14 (260714 피드백 정리 · H7)
--
-- 배경:
--   260623 피드백에서 "물 마시기/수분 미션 완전 제외" 결정. 프롬프트(ai_prompts.py) +
--   서비스(ai_service.py valid_types)에서는 즉시 차단했으나 DB CHECK 제약에는 잔존.
--   조용히 남은 사문화 조항이라 이번 라운드에 정리.
--
-- 안전:
--   - 실제 hydration 저장 사례는 260623 이후 없음(프롬프트/서비스 차단).
--   - 만약 legacy 데이터가 남아있으면 아래 SELECT로 사전 확인 후 실행.
-- ==========================================================

-- 1) legacy 데이터 확인 (있으면 마이그레이션 전 결정 필요)
SELECT COUNT(*) AS legacy_hydration_count
FROM public.ai_feedbacks
WHERE mission_type = 'hydration';

-- 2) 기존 제약 삭제
ALTER TABLE public.ai_feedbacks
    DROP CONSTRAINT IF EXISTS ai_feedbacks_mission_type_check;

-- 3) 새 제약 추가 (hydration 제외한 7개)
ALTER TABLE public.ai_feedbacks
    ADD CONSTRAINT ai_feedbacks_mission_type_check CHECK (
        mission_type IS NULL OR mission_type IN (
            'steps', 'sleep', 'alcohol', 'dinner_timing',
            'stretching', 'stress_relief', 'general'
        )
    );

-- 4) 결과 확인
SELECT constraint_name, check_clause
FROM information_schema.check_constraints
WHERE constraint_name = 'ai_feedbacks_mission_type_check';
