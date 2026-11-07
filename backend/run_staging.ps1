# 당봄케어 백엔드 — STAGING 실행 스크립트 (Windows PowerShell)
# DANGBOM_ENV=staging 로 .env.staging 을 읽어 로컬에서 기동한다. 운영(.env)에는 절대 안 붙는다.
# 사용: 백엔드 폴더에서  ./run_staging.ps1

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

if (-not (Test-Path ".env.staging")) {
    Write-Host "[중단] .env.staging 이 없습니다." -ForegroundColor Red
    Write-Host "  → .env.staging.example 을 .env.staging 으로 복사한 뒤 staging Supabase 값으로 채우세요." -ForegroundColor Yellow
    Write-Host "    Copy-Item .env.staging.example .env.staging" -ForegroundColor Yellow
    exit 1
}

$env:DANGBOM_ENV = "staging"
Write-Host "[STAGING] DANGBOM_ENV=staging · .env.staging 로 기동합니다 (운영 무관)." -ForegroundColor Cyan
Write-Host "  시작 로그의 [ENV] 줄에서 db= 값이 staging 프로젝트인지 꼭 확인하세요." -ForegroundColor Cyan

# 가상환경이 있으면 활성화 (없으면 무시)
if (Test-Path ".\venv\Scripts\Activate.ps1") { . .\venv\Scripts\Activate.ps1 }

# 8001 포트(운영 8000과 분리). reload 로 코드수정 즉시 반영.
python -m uvicorn app.main:app --reload --port 8001
