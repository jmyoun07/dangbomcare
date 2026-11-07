-- [260722] AI 재생성 카운터 — ai_feedbacks.regen_count
--   log_date별 하드리밋(자정 리셋 없음). 오늘=3회, 과거=2회.
--   진짜 GPT 응답만 카운트 (cache/fallback/budget_capped/skipped_old_date/regen_capped 제외).
--   소진 시 GPT 호출 스킵 + 기존 응답 유지 + _meta.model='regen_capped'로 응답.

ALTER TABLE public.ai_feedbacks
  ADD COLUMN IF NOT EXISTS regen_count INT NOT NULL DEFAULT 0;
