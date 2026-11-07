# 당당케어 운영 환경 설정 가이드 — 이메일 인증 (SMTP)

데모/베타 단계에서는 백엔드 `dev-auto-confirm` 엔드포인트로 이메일 인증을 자동 처리하고 있습니다. 정식 운영 단계에서는 반드시 **실제 SMTP를 설정**하여 환자에게 인증 메일을 발송해야 합니다.

---

## 왜 필요한가?

1. **법적 요건**: 의료 데이터를 다루는 앱에서 가입자 이메일 소유 확인은 본인 인증의 일부.
2. **보안**: 타인이 임의 이메일로 가입하여 환자 데이터에 접근하는 것 방지.
3. **계정 복구**: 비밀번호 재설정, 계정 잠금 해제 등의 절차.
4. **현재 우회 방식의 한계**: `dev-auto-confirm`은 이메일 소유 검증 없이 즉시 인증 → 운영에서는 보안 위험.

---

## 권장: SendGrid (가장 빠른 설정 — 30분)

### 1단계: SendGrid 가입

1. https://sendgrid.com 접속 → "Start for Free"
2. 이메일/비밀번호로 회원가입
3. 이메일 인증 메일 확인 → 인증 링크 클릭
4. 사용 목적 설문 (Transactional Email 선택, Korean 회사 정보 입력)

**무료 플랜**: 영구 100통/day. 데모 + 초기 운영(환자 5~10명)에 충분.

### 2단계: Sender Authentication (발송 도메인 등록)

발송자 이메일을 인증해야 메일이 정상 발송됨. **두 가지 옵션**:

#### 옵션 A: Single Sender Verification (5분, 가장 빠름)
- Settings → Sender Authentication → Single Sender Verification → Create New Sender
- 다음 정보 입력:
  - From Name: `당당케어`
  - From Email: `noreply@dangbom.co.kr` (또는 본인 도메인의 이메일)
  - Reply To: `noreply@dangbom.co.kr` (또는 한의원 대표 이메일)
  - Company Address: 당봄한의원 주소
- 이메일에 도착한 인증 링크 클릭

#### 옵션 B: Domain Authentication (DNS 설정 필요, 24시간 이내 활성)
- 당봄한의원 도메인 (예: dangbom.co.kr) 소유 시 권장
- SPF, DKIM 레코드 DNS에 추가 → 발송 도달율 ↑

### 3단계: API Key 발급

- Settings → API Keys → Create API Key
- Name: `dangdangcare-supabase-smtp`
- API Key Permissions: **Full Access** (또는 Mail Send만)
- "Create & View" → **API Key 복사** (한 번만 표시되니 즉시 저장)

### 4단계: Supabase에 SMTP 등록

1. Supabase Dashboard (https://supabase.com/dashboard) → 당당케어 프로젝트
2. **Authentication → Settings → Custom SMTP Server** (또는 "Set up custom SMTP" 배너 클릭)
3. 다음 입력:
   - **Enable Custom SMTP**: ✅ ON
   - **Sender email**: `noreply@dangbom.co.kr` (Step 2에서 인증한 이메일)
   - **Sender name**: `당당케어`
   - **Host**: `smtp.sendgrid.net`
   - **Port number**: `587`
   - **Min interval between emails**: `60` seconds
   - **Username**: `apikey` (글자 그대로 입력)
   - **Password**: (Step 3에서 발급받은 API Key 붙여넣기)
4. **Save changes**

### 5단계: 이메일 템플릿 한국어화 (선택)

Supabase → Authentication → Email Templates → "Confirm sign up" → 한국어로 수정:

```html
<h2>당당케어 가입을 환영합니다</h2>
<p>아래 링크를 눌러 이메일 인증을 완료해주세요.</p>
<p><a href="{{ .ConfirmationURL }}">이메일 인증하기</a></p>
<p>본인이 가입하지 않으셨다면 이 메일을 무시해주세요.</p>
<br>
<p>— 당봄한의원 · 당당케어 운영팀</p>
```

### 6단계: 우회 비활성화 (중요)

운영 환경에서는 `dev-auto-confirm` 엔드포인트로 인증 자동 우회되는 동작을 **반드시 꺼야** 합니다.

**Railway Dashboard** → 프로젝트 → Variables:
- `AUTO_CONFIRM_EMAIL=false` 환경변수 추가
- Save → 자동 재배포

이제 가입 시:
- 안드로이드 앱 → Supabase signup 호출
- Supabase가 SendGrid로 인증 메일 발송
- 환자가 메일함에서 인증 링크 클릭
- 그 후 앱에서 로그인 가능

### 7단계: 테스트

1. 신규 이메일로 가입 시도
2. 받은편지함 (또는 스팸함) 확인
3. 인증 링크 클릭
4. 앱에서 로그인 → 정상 진행

---

## 대안: Amazon SES (대규모 운영 시)

월 10,000통 이상 또는 환자 수 1,000명+ 운영 시 고려.

- 비용: $0.10 / 1000 통 (≈ 130원)
- 설정: AWS 계정 + IAM credential 발급 + Verified domain
- 시간: 약 1~2시간 (DNS verify 포함)
- SMTP 설정 동일 (Host만 `email-smtp.ap-northeast-2.amazonaws.com`)

---

## 대안: Resend (개발자 친화)

- 무료 3,000통/월 (월 100 통/day 평균)
- 설정: Resend.com 가입 → API Key → Supabase SMTP에 등록
- Host: `smtp.resend.com`
- Port: `465` (SSL) 또는 `587`

---

## Troubleshooting

| 증상 | 원인 | 해결 |
|---|---|---|
| 메일 안 도착 | Sender 미인증 | Step 2 다시 진행 |
| 스팸함으로 감 | SPF/DKIM 미설정 | Domain Authentication (옵션 B) |
| 인증 링크가 만료됨 | Token 만료 (1시간) | Supabase Auth → Settings → Token expiry 늘리기 |
| `over_email_send_rate_limit` 에러 | SMTP 미설정 + 무료 plan rate limit | SMTP 등록 후 해결 |
| Naver/Daum 에서만 안 옴 | 발송 도달율 문제 | Domain Authentication 필수 |

---

## 체크리스트 (운영 전환 시)

- [ ] SendGrid (또는 SES/Resend) 가입 + API Key
- [ ] Sender 이메일 인증 (당봄한의원 도메인 권장)
- [ ] Supabase Custom SMTP 등록 + Save
- [ ] 이메일 템플릿 한국어화 (Confirm signup, Reset password 등)
- [ ] Railway 환경변수: `AUTO_CONFIRM_EMAIL=false`
- [ ] 신규 가입 테스트 (실제 이메일로 인증 흐름 확인)
- [ ] 스팸함 도달율 점검 (Gmail / Naver / Daum 각각)

---

## 비용 추정 (월 기준)

| 환자 수 | 가입 메일 | 비밀번호 재설정 | 알림 메일 | 추정 통수 | SendGrid Free | SES |
|---|---|---|---|---|---|---|
| 50명 | 50 | 5 | 0 | 55 | 무료 | $0.01 |
| 200명 | 200 | 20 | 0 | 220 | 무료 (100/day 한도 내) | $0.02 |
| 500명 | 500 | 50 | 1000 (주간 알림) | 1,550 | 무료 한도 초과 | $0.16 |
| 2,000명 | 2,000 | 200 | 4,000 | 6,200 | 유료 ($20/월) | $0.62 |

500명 이하라면 SendGrid Free 충분. 운영 단계에서 환자 늘면 SES로 전환 권장.

---

## 비상시 (SMTP 일시 장애)

만약 SMTP가 잠시 안 되는 상황이라면:
1. Railway에 `AUTO_CONFIRM_EMAIL=true` 일시 켜기 → dev-auto-confirm으로 가입 처리
2. SMTP 복구 후 다시 `false`로

또는 Supabase SQL Editor에서 특정 이메일 강제 인증:
```sql
UPDATE auth.users 
SET email_confirmed_at = NOW(), confirmed_at = NOW()
WHERE email = '문제생긴이메일@example.com';
```

---

문서 작성일: 2026-05-17  
관련 코드: `backend/app/routers/auth.py` (dev-auto-confirm 엔드포인트)
