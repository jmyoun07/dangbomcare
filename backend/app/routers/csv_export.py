"""데이터 내보내기 API — 의료진 / 사용자 백업용

엔드포인트:
- GET /api/csv/full.xlsx       ⭐ 모든 데이터를 하루 1행으로 합친 엑셀 파일 (openpyxl 없으면 CSV로 폴백)
- GET /api/csv/full            동일 데이터를 CSV로
- GET /api/csv/files           (구) 타입별 CSV 파일 목록
- GET /api/csv/files/{filename}(구) 특정 CSV 파일 다운로드

보안: 본인 데이터만 접근 가능 (user_id 인증 필수).

[P0 의료 안전] AI 문구가 나가는 모든 응답은 score_service.apply_safety_override 를 태운다.
  - /full, /full.xlsx : _gather_export 에서 적용
  - /files/{ai_*.csv} : 디스크 파일을 서빙하지 않고 DB 에서 재생성하며 적용 (260729 P1-A)
  저혈당(45 mg/dL) 날에 원본 GPT 문구·운동 미션·격려가 파일로 나가면
  환자가 그 파일을 그대로 의료진에게 전달한다 — 여기가 마지막 방어선이다.
"""
from __future__ import annotations

import csv as _csv
import io
import json
import re
import uuid
from datetime import date, datetime, timedelta, timezone

from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import FileResponse, Response
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.deps import get_current_user_id
from app.models import (
    AIFeedback,
    DailyLog,
    LifestyleScore,
    MedicalGoal,
    StressData,
    User,
    WatchData,
)
from app.services import csv_writer, score_service

router = APIRouter(prefix="/api/csv", tags=["csv_export"])

_KST = timezone(timedelta(hours=9))
_DOW_KO = ["월", "화", "수", "목", "금", "토", "일"]

_HEADER = [
    "날짜", "요일", "공복혈당", "음주", "7시전저녁", "식후금식",
    "걸음수", "수면시간", "깊은잠", "얕은잠", "렘수면", "깬시간", "취침시각", "기상시각",
    "평균심박", "최소심박", "최대심박", "SpO2평균", "SpO2최소", "스트레스",
    "총점", "점수등급", "혈당점수", "걸음점수", "수면점수", "저녁점수", "음주점수", "금식점수", "스트레스점수", "식후걷기보너스",
    # [260727][P0 의료 안전] AI 문구는 2열로 분리한다.
    #   - "AI 요약(원본)"   : ai_feedbacks 에 저장된 GPT 원문 (판단 근거용)
    #   - "실제 노출 문구"  : 그 날짜 공복혈당으로 안전 오버라이드를 적용한, 환자가 실제로 본 문구
    #   저혈당/고혈당 위기일에는 앱이 원문 대신 안전 안내를 보여주므로 두 값이 다르다.
    # [260729 P1-A] 미션/격려/영향요인도 '실제 노출' 값으로 바꿨다.
    #   종전에는 원본을 그대로 실어서, 같은 행 안에서 "실제 노출 문구=즉시 단당류 섭취"인데
    #   "미션=식후 30분 걷기 / 격려=훌륭해요!" 가 나란히 찍혔다 — 한 행이 서로 반대로 말한다.
    #   안전 대체가 걸린 날은 이 3열이 비고 "안전대체" 열이 O 가 된다.
    "AI 요약(원본)", "실제 노출 문구", "미션(실제 노출)", "격려(실제 노출)", "영향요인(실제 노출)",
    "안전대체",
]


def _ox(v) -> str:
    if v is None:
        return ""
    return "O" if v else "X"


def _num(v):
    if v is None:
        return ""
    if isinstance(v, float):
        return "%g" % v
    return v


def _mins(m) -> str:
    if m is None:
        return ""
    try:
        m = int(m)
        return f"{m // 60}시간 {m % 60}분"
    except (TypeError, ValueError):
        return ""


def _hm(dt) -> str:
    if dt is None:
        return ""
    try:
        if dt.tzinfo is not None:
            dt = dt.astimezone(_KST)
        return dt.strftime("%H:%M")
    except Exception:
        return ""


def _safe(s) -> str:
    """CSV Formula Injection 방어 — 위험 접두어 앞에 '."""
    s = "" if s is None else str(s)
    if s and s[0] in ("=", "+", "-", "@", "\t", "\r"):
        return "'" + s
    return s


def _shown_ai(fb, fasting_glucose: int | None) -> dict:
    """[260727 → 260729 확장] 그 날 환자가 '실제로 본' AI 피드백을 재구성한다.

    ai_feedbacks 행에는 원본 GPT 응답만 저장되고, 앱으로 나가는 값은 응답 시점에
    score_service.apply_safety_override(그날 공복혈당) 로 결정된다
    (POST /api/daily-logs · GET /api/daily-logs/{date} · GET /api/ai-feedback/today 와 동일 규칙).
    내보내기도 반드시 같은 함수를 태워야 '환자가 본 것'과 '의료진이 받는 파일'이 일치한다.

    return: {message, encouragement, mission_text, factors, overridden}
      overridden=True 면 그 날은 저혈당/고혈당으로 GPT 문구·미션·격려가 전부 대체됐다.
    """
    raw_mission = {"text": fb.daily_mission} if fb.daily_mission else None
    out = score_service.apply_safety_override(
        {
            "message": fb.daily_message or "",
            "influencing_factors": fb.influencing_factors or [],
            "encouragement": fb.encouragement,
            "mission": raw_mission,
        },
        fasting_glucose,
    )
    mission = out.get("mission") or None
    return {
        "message": out.get("message") or "",
        "encouragement": out.get("encouragement") or "",
        "mission_text": (mission or {}).get("text") or "",
        "factors": out.get("influencing_factors") or [],
        # 미션·격려가 사라졌거나 문구가 바뀌었으면 안전 대체가 걸린 날이다.
        #   (격려만 지우는 apply_safety_override 방어층 분기도 잡아야 '빈칸인데 X' 가 안 생긴다)
        "overridden": (raw_mission is not None and mission is None)
        or (fb.encouragement and not out.get("encouragement"))
        or (out.get("message") or "") != (fb.daily_message or ""),
    }


def _join_factors(factors) -> str:
    if not factors:
        return ""
    try:
        return ", ".join(str(x) for x in factors)
    except Exception:
        return str(factors)


async def _gather_export(db: AsyncSession, user_id: uuid.UUID):
    """내보내기용 데이터 수집 → (메타행, 헤더, 데이터행). CSV/XLSX 공통."""
    user = (await db.execute(select(User).where(User.id == user_id))).scalar_one_or_none()
    goal = (
        await db.execute(
            select(MedicalGoal)
            .where(MedicalGoal.user_id == user_id)
            .order_by(MedicalGoal.created_at.desc())
            .limit(1)
        )
    ).scalar_one_or_none()

    logs = {x.log_date: x for x in (await db.execute(select(DailyLog).where(DailyLog.user_id == user_id))).scalars().all()}
    watches = {x.log_date: x for x in (await db.execute(select(WatchData).where(WatchData.user_id == user_id))).scalars().all()}
    stresses = {x.log_date: x for x in (await db.execute(select(StressData).where(StressData.user_id == user_id))).scalars().all()}
    scores = {x.score_date: x for x in (await db.execute(select(LifestyleScore).where(LifestyleScore.user_id == user_id))).scalars().all()}
    feedbacks = {x.feedback_date: x for x in (await db.execute(select(AIFeedback).where(AIFeedback.user_id == user_id))).scalars().all()}

    all_dates = sorted(set(logs) | set(watches) | set(stresses) | set(scores) | set(feedbacks))

    # ── 메타 (환자 정보 + 현재 목표) ──
    meta: list[list] = [["[환자 정보]"]]
    if user is not None:
        meta += [
            ["이름", _safe(user.name)],
            ["생년월일", _safe(user.birth_date.isoformat() if user.birth_date else "")],
            ["성별", {"M": "남", "F": "여"}.get(user.gender, "")],
            ["키(cm)", _num(user.height_cm)],
            ["체중(kg)", _num(user.weight_kg)],
            ["당뇨 진단일", _safe(user.diabetes_diagnosed_at.isoformat() if user.diabetes_diagnosed_at else "")],
        ]
    meta.append([])
    meta.append(["[현재 의료진 목표]"])
    if goal is not None:
        meta += [
            ["당화혈색소 목표(%)", _num(goal.target_hba1c)],
            ["췌장수치 목표", _num(goal.target_pancreas)],
            ["공복혈당 목표(mg/dL)", _num(goal.target_fasting_glucose)],
            ["목표 시점", _safe(goal.target_period)],
            ["생활습관 목표", _safe(goal.lifestyle_goals)],
        ]
    else:
        meta.append(["(설정된 목표 없음)"])
    meta.append([])

    # ── 데이터 행 (하루 1행) ──
    rows: list[list] = []
    for d in all_dates:
        lg = logs.get(d)
        wd = watches.get(d)
        st = stresses.get(d)
        sc = scores.get(d)
        fb = feedbacks.get(d)

        # [260727][P0 의료 안전] 환자가 실제로 본 문구·미션·격려 계산 (_shown_ai 참조).
        shown_msg = ""
        shown_mission = ""
        shown_enc = ""
        shown_infl = ""
        overridden = ""
        if fb is not None:
            _shown = _shown_ai(fb, lg.fasting_glucose if lg else None)
            shown_msg = _shown["message"]
            shown_mission = _shown["mission_text"]
            shown_enc = _shown["encouragement"]
            shown_infl = _join_factors(_shown["factors"])
            overridden = "O" if _shown["overridden"] else "X"

        rows.append([
            d.isoformat(),
            _DOW_KO[d.weekday()],
            _num(lg.fasting_glucose) if lg else "",
            _ox(lg.alcohol) if lg else "",
            _ox(lg.dinner_before_7pm) if lg else "",
            _ox(lg.no_food_after_dinner) if lg else "",
            _num(wd.steps) if wd else "",
            _mins(wd.sleep_total_minutes) if wd else "",
            _mins(wd.sleep_deep_minutes) if wd else "",
            _mins(wd.sleep_light_minutes) if wd else "",
            _mins(wd.sleep_rem_minutes) if wd else "",
            _mins(wd.sleep_awake_minutes) if wd else "",
            _hm(wd.sleep_start_at) if wd else "",
            _hm(wd.sleep_end_at) if wd else "",
            _num(wd.avg_heart_rate) if wd else "",
            _num(wd.min_heart_rate) if wd else "",
            _num(wd.max_heart_rate) if wd else "",
            _num(wd.spo2_avg) if wd else "",
            _num(wd.spo2_min) if wd else "",
            _safe(st.morning_status) if st else "",
            _num(sc.total_score) if sc else "",
            _safe(sc.score_label) if sc else "",
            _num(sc.glucose_score) if sc else "",
            _num(sc.steps_score) if sc else "",
            _num(sc.sleep_score) if sc else "",
            _num(sc.dinner_before_7pm_score) if sc else "",
            _num(sc.alcohol_score) if sc else "",
            _num(sc.no_food_after_dinner_score) if sc else "",
            _num(sc.stress_score) if sc else "",
            _num(sc.post_meal_walking_bonus) if sc else "",
            _safe(fb.daily_message) if fb else "",
            _safe(shown_msg),
            _safe(shown_mission),
            _safe(shown_enc),
            _safe(shown_infl),
            overridden,
        ])
    return meta, _HEADER, rows


def _build_csv(meta, header, rows) -> str:
    buf = io.StringIO()
    w = _csv.writer(buf)
    for m in meta:
        w.writerow(m)
    w.writerow(header)
    for r in rows:
        w.writerow(r)
    return buf.getvalue()


def _build_xlsx(meta, header, rows) -> bytes:
    """openpyxl로 진짜 .xlsx 생성. 미설치면 ImportError → 호출부에서 CSV 폴백."""
    from openpyxl import Workbook
    from openpyxl.styles import Font, PatternFill

    wb = Workbook()
    ws = wb.active
    ws.title = "당봄케어 데이터"
    for m in meta:
        ws.append(m)
    header_row = ws.max_row + 1
    ws.append(header)
    for cell in ws[header_row]:
        cell.font = Font(bold=True)
        cell.fill = PatternFill("solid", fgColor="E8F5E9")
    for r in rows:
        ws.append(r)
    ws.freeze_panes = f"A{header_row + 1}"
    bio = io.BytesIO()
    wb.save(bio)
    return bio.getvalue()


@router.get("/full.xlsx")
async def download_full_xlsx(
    user_id: uuid.UUID = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    """⭐ 모든 데이터를 하루 1행으로 합친 엑셀 파일. openpyxl 없으면 CSV로 폴백."""
    meta, header, rows = await _gather_export(db, user_id)
    today = datetime.now(_KST).date().isoformat()
    try:
        data = _build_xlsx(meta, header, rows)
        media = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        filename = f"dangbomcare_{today}.xlsx"
    except ImportError:
        data = _build_csv(meta, header, rows).encode("utf-8-sig")
        media = "text/csv; charset=utf-8"
        filename = f"dangbomcare_{today}.csv"
    return Response(
        content=data,
        media_type=media,
        headers={
            "Content-Disposition": f'attachment; filename="{filename}"',
            "Cache-Control": "no-store",
        },
    )


@router.get("/full")
async def download_full_csv(
    user_id: uuid.UUID = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    """모든 데이터를 하루 1행으로 합친 CSV (BOM 포함)."""
    meta, header, rows = await _gather_export(db, user_id)
    csv_text = _build_csv(meta, header, rows)
    filename = f"dangbomcare_{datetime.now(_KST).date().isoformat()}.csv"
    return Response(
        content=csv_text.encode("utf-8-sig"),
        media_type="text/csv; charset=utf-8",
        headers={
            "Content-Disposition": f'attachment; filename="{filename}"',
            "Cache-Control": "no-store",
        },
    )


# ──────────────────────────────────────────────
# (구) 타입별 파일 — 하위호환
#
# [260729][P0 의료 안전 · P1-A] ai_YYYY-MM.csv 만은 디스크 파일이 아니라
#   ai_feedbacks + daily_logs 에서 '요청 시점에' 만든다.
#   csv_writer.save_ai_feedback 이 파일 쓰기를 중단했고(거기 주석에 근거),
#   get_file_path / list_user_files 도 ai_ 를 차단하므로 이 경로가 유일한 출구다.
# ──────────────────────────────────────────────
_AI_FILE_RE = re.compile(r"^ai_(\d{4})-(\d{2})\.csv$")

AI_EXPORT_HEADERS = [
    "feedback_date",
    "fasting_glucose",       # 안전 오버라이드 판정의 근거값 (그 날 저장된 공복혈당)
    "safety_override",       # O = 저혈당/고혈당으로 GPT 문구·미션·격려가 대체된 날
    "daily_message_raw",     # GPT 원문 (판단 근거용 — 안전 대체일에는 환자에게 안 나갔다)
    "shown_message",         # 환자가 실제로 본 문구
    "shown_encouragement",   # 실제 노출 격려 (대체일에는 빈칸)
    "shown_mission",         # 실제 노출 미션 (대체일에는 빈칸 = 미션 없음)
    "mission_type",
    "mission_target",
    "mission_tip",
    "shown_influencing_factors",
    "gpt_model", "gpt_input_tokens", "gpt_output_tokens", "gpt_cost_krw",
]


def _month_bounds(year: int, month: int) -> tuple[date, date]:
    start = date(year, month, 1)
    end = (date(year + 1, 1, 1) if month == 12 else date(year, month + 1, 1)) - timedelta(days=1)
    return start, end


def _ai_csv_text(fbs, glucose_by_date: dict) -> str:
    """AI 피드백 CSV 본문 생성 — 안전 오버라이드 적용 후의 값만 '노출' 열에 넣는다."""
    buf = io.StringIO()
    w = _csv.writer(buf)
    w.writerow(AI_EXPORT_HEADERS)
    for fb in sorted(fbs, key=lambda x: x.feedback_date):
        g = glucose_by_date.get(fb.feedback_date)
        shown = _shown_ai(fb, g)
        suppressed = shown["mission_text"] == ""
        w.writerow([
            fb.feedback_date.isoformat(),
            _num(g),
            "O" if shown["overridden"] else "X",
            _safe(fb.daily_message),
            _safe(shown["message"]),
            _safe(shown["encouragement"]),
            _safe(shown["mission_text"]),
            # 미션이 대체로 사라진 날에는 미션 메타(type/target/tip)도 함께 비운다.
            #   남겨두면 "미션 없음"인데 target={'steps':4000} 이 찍혀 또 모순된다.
            "" if suppressed else _safe(fb.mission_type),
            "" if suppressed else _safe(
                json.dumps(fb.mission_target, ensure_ascii=False, separators=(",", ":"))
                if fb.mission_target else ""
            ),
            "" if suppressed else _safe(fb.mission_tip),
            _safe(_join_factors(shown["factors"])),
            _safe(fb.gpt_model),
            _num(fb.gpt_input_tokens),
            _num(fb.gpt_output_tokens),
            _num(fb.gpt_cost_krw),
        ])
    return buf.getvalue()


async def _load_ai_month(
    db: AsyncSession,
    user_id: uuid.UUID,
    start: date | None = None,
    end: date | None = None,
):
    """(AIFeedback 목록, {log_date: fasting_glucose}) 조회. 범위 없으면 전체."""
    fb_q = select(AIFeedback).where(AIFeedback.user_id == user_id)
    lg_q = select(DailyLog.log_date, DailyLog.fasting_glucose).where(DailyLog.user_id == user_id)
    if start is not None:
        fb_q = fb_q.where(AIFeedback.feedback_date >= start)
        lg_q = lg_q.where(DailyLog.log_date >= start)
    if end is not None:
        fb_q = fb_q.where(AIFeedback.feedback_date <= end)
        lg_q = lg_q.where(DailyLog.log_date <= end)
    fbs = (await db.execute(fb_q)).scalars().all()
    glucose_by_date = {d: g for d, g in (await db.execute(lg_q)).all()}
    return fbs, glucose_by_date


@router.get("/files")
async def list_csv_files(
    user_id: uuid.UUID = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    # 디스크 파일 (watch / log / score / stress) — ai_ 는 list_user_files 가 제외한다.
    files = csv_writer.list_user_files(user_id)

    # AI 피드백 항목은 DB 에서 월별로 합성 (size 는 실제 내려갈 내용 기준).
    fbs, glucose_by_date = await _load_ai_month(db, user_id)
    by_month: dict[tuple[int, int], list] = {}
    for fb in fbs:
        by_month.setdefault((fb.feedback_date.year, fb.feedback_date.month), []).append(fb)
    for (y, m), rows in by_month.items():
        text = _ai_csv_text(rows, glucose_by_date)
        modified = max(
            (r.updated_at or r.created_at for r in rows if (r.updated_at or r.created_at)),
            default=None,
        )
        if modified is not None and modified.tzinfo is not None:
            modified = modified.astimezone(_KST).replace(tzinfo=None)
        files.append({
            "name": f"ai_{y:04d}-{m:02d}.csv",
            "size_bytes": len(text.encode("utf-8-sig")),
            "modified_at": modified.isoformat() if modified is not None else None,
            "type": "ai",
            "month": f"{y:04d}-{m:02d}",
        })
    files.sort(key=lambda f: f["name"])
    return {"user_id": str(user_id), "count": len(files), "files": files}


@router.get("/files/{filename}")
async def download_csv_file(
    filename: str,
    user_id: uuid.UUID = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    # ── AI 피드백: 디스크 대신 DB 에서 재생성 (안전 오버라이드 적용) ──
    m = _AI_FILE_RE.match(filename)
    if m:
        year, month = int(m.group(1)), int(m.group(2))
        # [260729 통합검증 P2] 연도도 함께 검증한다. 정규식이 \d{4} 라 0000/9999 가 통과하는데
        #   _month_bounds 는 date(0,1,1) / date(10000,1,1) 에서 ValueError 를 내고 아무도 잡지 않아
        #   `GET /api/csv/files/ai_0000-01.csv` 가 404 가 아니라 **500** 이었다(실측).
        if not 1 <= month <= 12 or not date.min.year <= year < date.max.year:
            raise HTTPException(
                status_code=404,
                detail={"error": "NOT_FOUND", "message": f"파일을 찾을 수 없어요: {filename}"},
            )
        start, end = _month_bounds(year, month)
        fbs, glucose_by_date = await _load_ai_month(db, user_id, start, end)
        if not fbs:
            raise HTTPException(
                status_code=404,
                detail={"error": "NOT_FOUND", "message": f"파일을 찾을 수 없어요: {filename}"},
            )
        return Response(
            content=_ai_csv_text(fbs, glucose_by_date).encode("utf-8-sig"),
            media_type="text/csv; charset=utf-8",
            headers={
                "Content-Disposition": f'attachment; filename="{filename}"',
                "Cache-Control": "no-store",
            },
        )

    file_path = csv_writer.get_file_path(user_id, filename)
    if file_path is None:
        raise HTTPException(
            status_code=404,
            detail={"error": "NOT_FOUND", "message": f"파일을 찾을 수 없어요: {filename}"},
        )
    return FileResponse(
        path=str(file_path),
        media_type="text/csv; charset=utf-8",
        filename=filename,
        headers={
            "Content-Disposition": f'attachment; filename="{filename}"',
            "Cache-Control": "no-store",
        },
    )
