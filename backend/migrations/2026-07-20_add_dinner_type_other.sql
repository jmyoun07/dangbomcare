-- [260720 개편] 저녁 유형 옵션 개편: flour(밀가루) 제거 → other(기타) 추가.
--   '기타' 선택 시 자유입력 텍스트를 별도 컬럼에 저장.
--   dinner_type(JSON 리스트)에 CHECK 제약이 없어 별도 마이그레이션 없이 값만 갱신하면 됨.
--   RLS는 daily_logs 정책 그대로 상속(별도 정책 불필요).

ALTER TABLE public.daily_logs
  ADD COLUMN IF NOT EXISTS dinner_type_other TEXT;
