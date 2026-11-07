-- ==========================================================
-- 마이그레이션: users 테이블에 공복혈당 범위 재확인 타임스탬프 컬럼 추가
-- 적용일: 2026-07-14 (매월 자동 재정의 팝업 — 260714 피드백)
--
-- 변경 사유:
--   매월 1일 이후 앱을 처음 열 때 최근 30일 공복혈당 IQR 기반 권장 범위를 팝업으로
--   보여주고, 사용자가 수정·확정한다. 팝업이 매번 뜨지 않도록 마지막 확인 시각을 저장.
--
-- 적용 방법 (Supabase SQL Editor):
--   전체를 복사해서 한 번 실행. IF NOT EXISTS 라 여러 번 실행해도 안전.
--   ⚠️ 반드시 백엔드 새 코드 배포 '전에' 먼저 실행.
-- ==========================================================

ALTER TABLE public.users
    ADD COLUMN IF NOT EXISTS glucose_range_last_check_at TIMESTAMPTZ;

-- 결과 확인
SELECT
    COUNT(*)                                  AS total_users,
    COUNT(glucose_range_last_check_at)        AS users_with_check_ts
FROM public.users;
