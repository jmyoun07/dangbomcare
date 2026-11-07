# 당봄케어 (Dangbom Care)

당뇨 환자용 건강관리 서비스. 스마트워치에서 걸음·수면·심박 데이터를 받아 혈당·식사·운동 기록과
함께 분석하고, 일일/주간 AI 리포트와 한의원 연계 미션을 제공한다.

저장소 하나에 앱·백엔드·관리자 웹 세 구성요소를 모았다.

## 구성

| 폴더 | 내용 | 스택 |
|---|---|---|
| `android/` | 환자용 안드로이드 앱 (v1.1.19, versionCode 61) | Kotlin, Jetpack Compose, 삼성 Health Data SDK, Health Connect, Retrofit |
| `backend/` | REST API 서버 | FastAPI, SQLAlchemy(asyncpg), Supabase Postgres, OpenAI, FCM 푸시 |
| `admin-web/` | 의료진용 관리자 대시보드 | Next.js 15, Supabase, Tailwind v4, Recharts |

## 주요 기능

- **워치 연동** — 삼성 Health Data SDK / Health Connect로 걸음·수면·심박·산소포화도 수집.
  워치 기종을 특정하지 않고 환자가 쓰는 기기 전반을 지원
- **혈당·식사·운동·복약 기록** — 수동 입력 + 워치 자동 수집 병행
- **AI 리포트** — 일일/주간 단위로 실제 수치 기반 개선 포인트 생성 (OpenAI)
- **건강 점수** — 항목별 가중 점수 산출 및 추이 그래프
- **한의원 연계** — 동의 기반 제3자 제공, 의료진이 관리자 웹에서 환자 추이 조회
- **강제 업데이트 게이트** — 서버 버전 정책에 따라 구버전 클라이언트 차단

## 빌드

### android/
`local.properties`(SDK 경로, API 주소, Supabase 키), `app/google-services.json`,
릴리스 서명키가 필요하다. 세 파일 모두 저장소에 없다.
```
./gradlew assembleDebug
```

### backend/
```
pip install -r requirements.txt
uvicorn app.main:app --reload
```
환경변수는 `.env.example` 참고. DB 마이그레이션은 Alembic 없이 `migrations/*.sql` 수동 실행.

### admin-web/
```
npm install --legacy-peer-deps
npm run dev
```

## 저장소에 포함하지 않은 것

이 저장소는 **포트폴리오용 복제본**이다. 운영에 쓰이는 저장소는 따로 있다.
보안상 아래는 제외했다.

| 제외 | 이유 |
|---|---|
| 앱 서명키 `.jks`, `local.properties`, `google-services.json` | 빌드 시크릿 (원본 저장소도 미포함) |
| `.env` | DB 비밀번호·service_role 키·OpenAI 키 |
| `backend/HANDOFF.md` | 운영 서버 공인 IP 및 `ssh root@` 접속·배포 절차 |
| `backend/seed_demo_account.sql` | 운영 DB에 바로 로그인 가능한 데모 계정 아이디/비밀번호 |
| `android/HANDOFF.md`, `admin-web/HANDOFF.md` | 운영 Supabase 프로젝트 ref 및 시크릿 배치 구조 |

`admin-web/.env.example`에는 키 값은 없으나 운영 Supabase 프로젝트 URL이 적혀 있다.
저장소를 공개로 전환할 경우 이 값을 플레이스홀더로 바꾸는 편이 좋다.
