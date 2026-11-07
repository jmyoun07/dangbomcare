"""CSV 자동 저장 서비스 — 의료진 데이터 공유용

핵심 컨셉:
- 모든 데이터 저장 시점 (워치 / 일일 로그 / 점수 / AI 피드백) 에 동일 데이터를 CSV 로도 저장
- 사용자별 / 데이터 타입별 / 월별 파일 분리
  예) /data/csv/{user_id}/watch_2026-05.csv
- Append-only — 중복 방지를 위해 (user_id, log_date) 키로 기존 행 삭제 후 추가
- Excel 한글 깨짐 방지 — UTF-8 BOM 포함
- Railway 환경: persistent volume 미설정 시 /tmp/dangdangcare_csv 폴백

데이터 타입:
- watch:   워치 데이터 (걸음/수면/심박/SpO2)
- log:     일일 로그 (공복혈당 + 회고 3개)
- score:   생활 점수 (총점 + 7개 breakdown)
- stress:  스트레스 추정 (시간대별 상태)
- ai:      AI 피드백 — ⚠️ 파일로 저장하지 않는다 (260729 P1-A).
           안전 오버라이드(공복혈당 기준)가 응답 시점 판정이라 파일로 굳힐 수 없어서,
           내려줄 때 DB 에서 만든다. 상세 근거는 save_ai_feedback 위 주석 참조.

의료진은 GET /api/csv/* 엔드포인트로 다운로드.
"""
from __future__ import annotations

import csv
import logging
import os
import threading
import uuid
from datetime import date, datetime
from pathlib import Path
from typing import Any, Iterable

logger = logging.getLogger(__name__)

# 저장 디렉토리 — 환경변수로 오버라이드 가능 (Railway volume 마운트 경로)
CSV_BASE_DIR = Path(os.environ.get("DANGDANG_CSV_DIR", "/tmp/dangdangcare_csv"))

# 파일별 동시 쓰기 락 (스레드 안전 — uvicorn worker 내부 동시 요청 대비)
_locks: dict[str, threading.Lock] = {}
_locks_master = threading.Lock()

# UTF-8 BOM — Excel 한글 깨짐 방지
UTF8_BOM = "﻿"


def _get_lock(file_path: Path) -> threading.Lock:
    """파일별 락 — 스레드 동시 쓰기 race condition 방지"""
    key = str(file_path)
    with _locks_master:
        if key not in _locks:
            _locks[key] = threading.Lock()
        return _locks[key]


def _user_dir(user_id: uuid.UUID | str) -> Path:
    """사용자별 디렉토리 (예: /data/csv/{user_id}/)"""
    d = CSV_BASE_DIR / str(user_id)
    d.mkdir(parents=True, exist_ok=True)
    return d


def _file_path(user_id: uuid.UUID | str, data_type: str, log_date: date | None = None) -> Path:
    """파일 경로: {user_dir}/{type}_{YYYY-MM}.csv  (월별 분리 → 파일 크기 관리)"""
    if log_date is None:
        log_date = datetime.now().date()
    fname = f"{data_type}_{log_date.year:04d}-{log_date.month:02d}.csv"
    return _user_dir(user_id) / fname


# ──────────────────────────────────────────────
# 핵심 헬퍼
# ──────────────────────────────────────────────
def _coerce(v: Any) -> str:
    """모든 값을 안전한 CSV 문자열로 변환.

    [Codex 보안 검토 H7] CSV Formula Injection 방어 적용:
    Excel/Numbers/LibreOffice에서 `=`, `+`, `-`, `@`, `\\t`, `\\r` 로 시작하는 셀은
    수식으로 실행됨 (예: `=cmd|'/c calc'!A1` → 명령 실행 가능).
    의료진이 CSV 열 때 안전하도록 위험 접두어 앞에 작은따옴표 prefix.
    """
    if v is None:
        return ""
    if isinstance(v, bool):
        return "1" if v else "0"
    if isinstance(v, (date, datetime)):
        return v.isoformat()
    if isinstance(v, float):
        return f"{v:.2f}"
    if isinstance(v, (list, dict)):
        # JSON 데이터는 한 줄 문자열로
        import json
        s = json.dumps(v, ensure_ascii=False, separators=(",", ":"))
        return _sanitize_formula(s)
    return _sanitize_formula(str(v))


def _sanitize_formula(s: str) -> str:
    """CSV Formula Injection 방어: 위험 접두어 셀 앞에 ' 추가."""
    if not s:
        return s
    if s[0] in ("=", "+", "-", "@", "\t", "\r"):
        return "'" + s
    return s


def _upsert_row(
    file_path: Path,
    headers: list[str],
    row: dict[str, Any],
    unique_key: str = "log_date",
) -> None:
    """기존 같은 unique_key 행 제거 후 새 행 추가 (멱등성)"""
    lock = _get_lock(file_path)
    with lock:
        try:
            file_path.parent.mkdir(parents=True, exist_ok=True)

            # 기존 데이터 읽기 (있으면)
            existing_rows: list[dict[str, str]] = []
            if file_path.exists():
                with file_path.open("r", encoding="utf-8-sig", newline="") as f:
                    reader = csv.DictReader(f)
                    existing_rows = [r for r in reader if r.get(unique_key) != _coerce(row.get(unique_key))]

            # 새 행 추가 (날짜순 정렬 유지)
            existing_rows.append({h: _coerce(row.get(h)) for h in headers})
            existing_rows.sort(key=lambda r: r.get(unique_key, ""))

            # 전체 재기록 (BOM 포함)
            with file_path.open("w", encoding="utf-8", newline="") as f:
                f.write(UTF8_BOM)
                writer = csv.DictWriter(f, fieldnames=headers)
                writer.writeheader()
                writer.writerows(existing_rows)
        except Exception as e:
            # CSV 저장 실패가 API 응답을 막으면 안 됨 — 로깅만
            logger.warning(
                "CSV upsert 실패 file=%s err=%s: %s",
                file_path, type(e).__name__, e
            )


# ──────────────────────────────────────────────
# 데이터 타입별 공개 API
# ──────────────────────────────────────────────
WATCH_HEADERS = [
    "log_date", "steps",
    "sleep_total_min", "sleep_deep_min", "sleep_light_min", "sleep_rem_min", "sleep_awake_min",
    "sleep_start_at", "sleep_end_at",
    "hr_avg", "hr_min", "hr_max",
    "spo2_avg", "spo2_min",
    "last_synced_at",
    "steps_hourly_json",  # JSON: [hour0_steps, hour1_steps, ...]
]


def save_watch_data(user_id: uuid.UUID, wd: Any) -> None:
    """watch_data POST 시점 호출 — WatchData 모델 객체 받음"""
    file_path = _file_path(user_id, "watch", wd.log_date)
    row = {
        "log_date": wd.log_date,
        "steps": wd.steps,
        "sleep_total_min": wd.sleep_total_minutes,
        "sleep_deep_min": wd.sleep_deep_minutes,
        "sleep_light_min": wd.sleep_light_minutes,
        "sleep_rem_min": wd.sleep_rem_minutes,
        "sleep_awake_min": wd.sleep_awake_minutes,
        "sleep_start_at": wd.sleep_start_at,
        "sleep_end_at": wd.sleep_end_at,
        "hr_avg": wd.avg_heart_rate,
        "hr_min": wd.min_heart_rate,
        "hr_max": wd.max_heart_rate,
        "spo2_avg": wd.spo2_avg,
        "spo2_min": wd.spo2_min,
        "last_synced_at": wd.last_synced_at,
        "steps_hourly_json": wd.steps_hourly,
    }
    _upsert_row(file_path, WATCH_HEADERS, row)


LOG_HEADERS = [
    "log_date", "fasting_glucose", "fasting_glucose_time",
    "alcohol", "dinner_before_7pm", "no_food_after_dinner",
    "input_method", "created_at",
]


def save_daily_log(user_id: uuid.UUID, log: Any) -> None:
    """daily_logs POST 시점 호출 — 공복혈당 + 회고 3개"""
    file_path = _file_path(user_id, "log", log.log_date)
    row = {
        "log_date": log.log_date,
        "fasting_glucose": log.fasting_glucose,
        "fasting_glucose_time": log.fasting_glucose_time,
        "alcohol": log.alcohol,
        "dinner_before_7pm": log.dinner_before_7pm,
        "no_food_after_dinner": log.no_food_after_dinner,
        "input_method": log.input_method,
        "created_at": log.created_at,
    }
    _upsert_row(file_path, LOG_HEADERS, row)


SCORE_HEADERS = [
    "score_date", "total_score", "score_label", "has_missing_data",
    "glucose_score", "steps_score", "sleep_score",
    "dinner_before_7pm_score", "alcohol_score",
    "no_food_after_dinner_score", "stress_score",
    "post_meal_walking_bonus",
]


def save_score(user_id: uuid.UUID, lscore: Any) -> None:
    """점수 산출 시점 호출 — daily_logs POST 후 호출"""
    file_path = _file_path(user_id, "score", lscore.score_date)
    row = {
        "score_date": lscore.score_date,
        "total_score": lscore.total_score,
        "score_label": lscore.score_label,
        "has_missing_data": lscore.has_missing_data,
        "glucose_score": lscore.glucose_score,
        "steps_score": lscore.steps_score,
        "sleep_score": lscore.sleep_score,
        "dinner_before_7pm_score": lscore.dinner_before_7pm_score,
        "alcohol_score": lscore.alcohol_score,
        "no_food_after_dinner_score": lscore.no_food_after_dinner_score,
        "stress_score": lscore.stress_score,
        "post_meal_walking_bonus": lscore.post_meal_walking_bonus,
    }
    _upsert_row(file_path, SCORE_HEADERS, row, unique_key="score_date")


STRESS_HEADERS = [
    "log_date", "morning_status", "afternoon_status", "evening_status",
    "below_avg_ratio", "avg_variability",
]


def save_stress(user_id: uuid.UUID, stress: Any) -> None:
    """스트레스 추정 시점 호출 — watch_data POST 후 호출"""
    file_path = _file_path(user_id, "stress", stress.log_date)
    row = {
        "log_date": stress.log_date,
        "morning_status": stress.morning_status,
        "afternoon_status": stress.afternoon_status,
        "evening_status": stress.evening_status,
        "below_avg_ratio": stress.below_avg_ratio,
        "avg_variability": stress.avg_variability,
    }
    _upsert_row(file_path, STRESS_HEADERS, row)


# ──────────────────────────────────────────────
# AI 피드백 — ⚠️ 디스크에 쓰지 않는다 (P1-A / 260729)
# ──────────────────────────────────────────────
# [260729][P0 의료 안전 · P1-A] ai_*.csv 파일 쓰기를 중단했다.
#
#   왜 파일로 못 남기나:
#     ai_feedbacks 행에는 '원본 GPT 응답'만 저장되고, 환자에게 나가는 문구는
#     응답 시점에 score_service.apply_safety_override(그날 공복혈당) 로 결정된다
#     (score_service.py:140-152 / 260726 결정). 공복혈당은 나중에 정정될 수 있어서
#     — 오타 40 mg/dL → 정상값 정정 — '쓰는 시점'에 오버라이드를 굳혀 파일로 남기면
#     정정해도 저혈당 경고가 파일에 영구 고착된다. 260726에 fb 행 write 방식을 버린
#     이유가 정확히 이것이고, 같은 실수를 CSV 층에서 반복할 수 없다.
#     반대로 '원본'을 그대로 남기면(= 종전 동작) 저혈당 날에도 원본 GPT 문구 +
#     운동 미션 + 격려가 파일에 남아 GET /api/csv/files/{filename} 로 그대로
#     환자·의료진에게 나간다. 어느 쪽으로도 파일은 정답이 될 수 없다.
#
#   → 정답은 '파일을 두지 않고, 내려줄 때 DB 에서 만들면서 오버라이드를 태우는 것'.
#     생성/서빙은 app/routers/csv_export.py 의 _ai_csv_text() 가 담당한다.
#     (ai_feedbacks 가 원본 단일 출처이므로 파일은 100% 재생성 가능한 캐시일 뿐이다.)
def save_ai_feedback(user_id: uuid.UUID, fb: Any) -> None:
    """AI 피드백 CSV 저장 — 의도적 no-op + 과거 파일 정리.

    호출부(daily_logs.py)는 그대로 두고 여기서 흡수한다.
    과거 버전이 남긴 ai_*.csv (안전 오버라이드가 안 태워진 원본 문구) 를 함께 삭제해
    디스크에 남은 과거분도 정리한다 — 내용은 ai_feedbacks 에서 언제든 재생성된다.
    """
    purge_ai_files(user_id)


def purge_ai_files(user_id: uuid.UUID | str) -> int:
    """[260729 P1-A] 디스크에 남은 legacy ai_*.csv 제거. 삭제한 개수 반환.

    안전 오버라이드가 적용되지 않은 원본 GPT 문구/미션/격려가 들어 있는 파일이라
    남겨두면 언젠가 다시 서빙될 위험이 있다. 데이터 손실이 아니다 —
    ai_feedbacks 테이블이 원본이고, 내보내기는 거기서 재생성한다.
    """
    removed = 0
    try:
        user_dir = CSV_BASE_DIR / str(user_id)
        if not user_dir.exists():
            return 0
        for p in user_dir.glob("ai_*.csv"):
            try:
                p.unlink()
                removed += 1
                logger.info("legacy ai CSV 삭제(원본 문구 노출 방지) file=%s", p)
            except OSError as e:
                logger.warning("legacy ai CSV 삭제 실패 file=%s err=%s", p, e)
    except Exception as e:  # 정리 실패가 API 를 막으면 안 됨
        logger.warning("purge_ai_files 실패 user=%s err=%s: %s", user_id, type(e).__name__, e)
    return removed


# ──────────────────────────────────────────────
# 다운로드/조회 헬퍼
# ──────────────────────────────────────────────
def list_user_files(user_id: uuid.UUID | str) -> list[dict[str, Any]]:
    """사용자의 CSV 파일 목록 (의료진 다운로드 화면용) — 디스크 파일만.

    ⚠️ [260729 P1-A] ai_* 는 제외한다. AI 피드백은 디스크에 두지 않고
       csv_export 라우터가 DB 에서 안전 오버라이드를 적용해 만들어 목록에 합친다.
       (purge 가 실패해 파일이 남아 있어도 목록에 새어나가지 않게 하는 2차 방어)
    """
    user_dir = _user_dir(user_id)
    if not user_dir.exists():
        return []
    files = []
    for p in sorted(user_dir.glob("*.csv")):
        if p.name.startswith("ai_"):
            continue
        try:
            stat = p.stat()
            files.append({
                "name": p.name,
                "size_bytes": stat.st_size,
                "modified_at": datetime.fromtimestamp(stat.st_mtime).isoformat(),
                "type": p.stem.split("_")[0],  # watch / log / score / stress / ai
                "month": p.stem.split("_", 1)[1] if "_" in p.stem else "",
            })
        except OSError:
            continue
    return files


def get_file_path(user_id: uuid.UUID | str, filename: str) -> Path | None:
    """파일 경로 반환 — path traversal 차단 위해 디렉토리 검증"""
    user_dir = _user_dir(user_id)
    # 파일명 sanitize: '..' / '/' 차단
    if "/" in filename or "\\" in filename or ".." in filename:
        return None
    # [260729 P1-A] ai_* 는 디스크에서 절대 서빙하지 않는다.
    #   과거 버전이 남긴 파일에는 안전 오버라이드 미적용 원본 문구가 들어 있다.
    #   AI 피드백 다운로드는 csv_export 라우터가 DB 에서 재생성해 처리한다.
    if filename.startswith("ai_"):
        return None
    p = user_dir / filename
    # 상위 디렉토리로 빠져나가지 못하게 확인
    try:
        p.resolve().relative_to(user_dir.resolve())
    except ValueError:
        return None
    if not p.exists() or not p.is_file():
        return None
    return p
