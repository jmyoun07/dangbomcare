"""주간/월간 리포트 (간단 버전 — v1.9 MVP)
PDF 생성은 별도 단계 (WeasyPrint, v1.x 폴리싱).
"""
from __future__ import annotations

import calendar
import logging
import re
import uuid

logger = logging.getLogger(__name__)
from datetime import date, datetime, timedelta, timezone

from fastapi import APIRouter, Depends, Header, HTTPException, Query
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.database import get_db
from app.deps import get_current_user_id
# [260824] AI 주간/월간 요약(gpt_summary) 복원 — 의료진 관리자웹·앱 모두에서 다시 사용.
#   260714에 폐지됐던 기능을 되살림 (WeeklyReport/MonthlyReport는 그때도 legacy 캐시용으로 남아있었음).
from app.models import (
    AIFeedback,
    DailyLog,
    GlucoseRangeHistory,
    LifestyleScore,
    MonthlyReport,
    StressData,
    User,
    WatchData,
    WeeklyReport,
)
from app.services import ai_service
from app.services.score_service import WEIGHTS_MAX

router = APIRouter(prefix="/api/reports", tags=["reports"])

KST = timezone(timedelta(hours=9))


# ── 주요 위험 요인 (당봄 #3) — LifestyleScore 세부 점수 기반 자동 분석 ──
# 각 항목의 평균 점수가 만점 대비 얼마나 손해인지 계산해 상위 N개를 반환.
_RISK_FIELDS = {
    # [260707] 공복혈당은 '결과값'이라 개선 포인트(행동 목표)에서 제외 — 달력/패턴카드가 개인범위로 다룸.
    #   (100~130 당뇨목표 범위인데 glucose_score 10<10.5로 '미달' 잡혀 '높은 날'로 오표기되던 문제 근본 해소)
    "steps": ("steps_score", "활동량"),
    "sleep": ("sleep_score", "수면"),
    "dinner_before_7pm": ("dinner_before_7pm_score", "이른 저녁"),
    "alcohol": ("alcohol_score", "음주"),
    "no_food_after_dinner": ("no_food_after_dinner_score", "식후 음식 섭취"),
    "stress": ("stress_score", "스트레스"),
    # [260706 Codex] 260623 신규 3항목도 위험 요인 분석에 포함 — 이 항목들만 계속 0점이어도
    #   top_risk_factors에 안 잡히던 누락 보완
    "dinner_type": ("dinner_type_score", "저녁식사 유형"),
    "digestion": ("digestion_score", "자기 전 소화"),
    "post_meal_exercise": ("post_meal_exercise_score", "식후 운동"),
}


# ── [260728 수면 배점 개편] 수면 '부족일' 판정은 점수가 아니라 실제 수면 시간으로 ──
# [260728 통합검증] 아래 근거를 확정 배점표(score_service.sleep_score 가 정본)로 갱신했다.
#   이전 주석은 검토 중이던 대칭안(h<3=10 / 3~4=12 / 4~5=14 / 5~6=16 / 6~7=18 …)을 인용해
#   실제 구현과 숫자가 달랐다 — 판정 로직은 그대로 맞지만 근거가 틀려 재검토 때 오판을 부른다.
# 확정 배점: 부족 6 / 8 / 11 / 14 / 17 · 적정(7~9h) 20 · 과다 18 / 16 / 14 / 12 / 10 (최저 6점).
#
# ① 점수 임계값(만점의 70% 미만 = 미달, 수면은 20*0.7=14)으로는 수면 부족 탐지가 새는다.
#    14점(5~6시간) → `14 < 14` False, 17점(6~7시간) → False → '미달 아님'으로 빠진다.
# ② 그 임계값은 반대로 과다수면을 '미달'로 끌어온다 — 12점(12~13시간), 10점(13시간 초과)은 14 미만.
#    앱의 수면 조언 문구는 방향이 하나뿐(수면 부족/적정 이탈)이라 과다수면을 여기에 섞으면
#    12시간 잔 사용자에게 **거짓 문장**이 나간다.
# ③ 게다가 같은 점수가 부족·과다 양쪽에 존재한다(14점 = 5~6시간 **또는** 11~12시간)
#    → 점수만으로는 방향 자체를 알 수 없다.
#
# → 수면만 점수 임계값을 쓰지 않고 **실제 수면 시간(분)** 으로 판정한다.
#   근거: 고객 문서 "수면 시간이 7시간 미만인 날에는 AI 코멘트에 (…) 경고 문구를 우선 노출".
#   즉 7시간 미만 = 개선 대상. 과다수면(9시간 초과)은 '부족'으로 세지 않는다.
#   (점수 기준 대안 — 만점 미달 전부 카운트/수면만 18점 미만 — 은 둘 다 과다수면을
#    '부족'으로 표시하거나 6~7시간을 놓쳐서 채택하지 않았다.)
# 다른 항목(걸음·식후금식 등)의 판정 규칙은 그대로 둔다.
SLEEP_DEFICIT_MINUTES = 7 * 60  # 420분 = 7시간. 이 미만인 날만 수면 '부족일'.


def _sleep_minutes_by_score_date(scores, watch_by_date: dict) -> dict:
    """점수일(score_date) → 그 점수에 실제로 쓰인 수면 분.

    점수의 시간 매핑은 "score_date 의 회고 + (score_date - 1) 의 워치"다
    (daily_logs.py / watch_data.py 둘 다 어제 워치로 채점). 그래서 D-1 워치를 본다.
    0분은 기록 결함이라 score_service.sleep_score 가 None(채점 제외)으로 보내므로 여기서도 제외.
    """
    out: dict = {}
    for s in scores:
        w = watch_by_date.get(s.score_date - timedelta(days=1))
        if w is not None and w.sleep_total_minutes:
            out[s.score_date] = w.sleep_total_minutes
    return out


def _sleep_deficit_days(scores, sleep_minutes_by_score_date: dict | None) -> int | None:
    """수면 부족일(7시간 미만) 수. 판정 불가면 None.

    None 반환 = 수면 분을 한 건도 못 구함 → 부족/과다 구분 불가.
      이때는 점수만 보고 '부족'이라 단정하지 않고 수면 위험요인 판정을 보류한다
      (정상 경로에선 sleep_score 가 있는 날은 그 워치 행도 조회 범위 안에 있으므로 발생하지 않음).
    """
    if not sleep_minutes_by_score_date:
        return None
    deficit = 0
    evaluable = 0
    for s in scores:
        if getattr(s, "sleep_score", None) is None:
            continue
        minutes = sleep_minutes_by_score_date.get(s.score_date)
        if minutes is None:
            continue
        evaluable += 1
        if minutes < SLEEP_DEFICIT_MINUTES:
            deficit += 1
    return deficit if evaluable else None


def _build_top_risk_factors(
    scores,
    *,
    top_n=3,
    min_observed=2,
    sleep_minutes_by_score_date: dict | None = None,
) -> list[dict]:
    """LifestyleScore 세부 점수에서 '평균 손해 점수'가 큰 항목을 위험 요인으로 추출.

    min_observed: 해당 항목이 관측된 날이 이 수 미만이면 신뢰 부족으로 제외.
    sleep_minutes_by_score_date: 점수일 → 수면 분 (_sleep_minutes_by_score_date 결과).
      수면 부족일은 점수가 아니라 이 값으로 판정한다 (위 SLEEP_DEFICIT_MINUTES 주석 참고).
    """
    factors = []
    for factor, (attr, label) in _RISK_FIELDS.items():
        max_score = WEIGHTS_MAX[factor]
        values = [getattr(s, attr) for s in scores if getattr(s, attr) is not None]
        if len(values) < min_observed:
            continue
        avg = sum(values) / len(values)
        avg_loss = max_score - avg
        if avg_loss <= 0:
            continue
        if factor == "sleep":
            # [260728] 수면만 예외 — 점수 임계값이 아니라 실제 수면 분(<420 = 7시간 미만)으로 판정.
            deficit_days = _sleep_deficit_days(scores, sleep_minutes_by_score_date)
            if not deficit_days:
                #   None → 수면 분 없음(판정 불가) / 0 → 손해가 전부 과다수면(9시간 초과) 쪽.
                #   둘 다 수면 위험요인으로 내보내지 않는다. 앱 조언이 "수면이 부족한 날이…"
                #   한 종류뿐이라 과다수면을 여기 실으면 거짓 문장이 되기 때문.
                continue
            low_days = deficit_days
        else:
            # 0/1 이진 항목(저녁/음주/저녁식사유형)은 만점 미달 = 미달일, 나머지는 70% 기준
            # ([260706] 식후금식은 5단계 차등이 되어 이진 목록에서 제외, dinner_type(0/5)은 이진 추가)
            threshold = max_score if factor in {"dinner_before_7pm", "alcohol", "dinner_type"} else max_score * 0.7
            low_days = sum(1 for v in values if v < threshold)
        low_label = "7시간 미만" if factor == "sleep" else "미달"
        factors.append({
            "factor": factor,
            "label": label,
            "avg_score": round(avg, 1),
            "max_score": max_score,
            "avg_loss_points": round(avg_loss, 1),
            "low_days": low_days,
            "observed_days": len(values),
            "reason": f"{label} 평균 {round(avg, 1)}/{max_score}점, 하루 약 {round(avg_loss, 1)}점 손해 · {len(values)}일 중 {low_days}일 {low_label}",
        })
    factors.sort(key=lambda x: (x["avg_loss_points"], x["low_days"]), reverse=True)
    return factors[:top_n]


# ── [260623 / 260714 정리 REP5] 공복혈당 달력 (리포트 주간/월간) ──
# 팝업 표시 목표: 3~5개 요인. 실제 감지된 요인이 적을 땐 신중한 일반 가능성(참고)로 3개 보장.
_GENERIC_HIGH_FACTORS: list[str] = [
    "기록되지 않은 늦은 간식·식후 추가 음식 가능성",  # [260722 codex HIGH2] "야식" 금지어 → 시니어 친화 표현
    "일시적인 스트레스·수면 리듬 변화",
    "전날 이후 컨디션 변동",
]
_GENERIC_LOW_FACTORS: list[str] = [
    "규칙적인 생활 리듬 유지",
    "안정적인 컨디션 유지",
    "정신적 안정",
]


def _pad_factors(factors: list[str], status: str) -> list[str]:
    """[260714 정리 REP5] 팝업 요인 3~5개 보장.
    실제 감지된 요인이 이미 3개↑ → 최대 5개까지 그대로.
    3개 미만이면 일반 참고 문구로 채워 3개 확보 (감지된 실제 요인이 앞에 옴).
    """
    result = [f for f in factors if f]
    if len(result) >= 3:
        return result[:5]
    generics = _GENERIC_HIGH_FACTORS if status == "high" else _GENERIC_LOW_FACTORS
    for g in generics:
        if len(result) >= 3:
            break
        if g not in result:
            result.append(g)
    return result[:5]


def _glucose_day_factors(
    log,
    sleep_minutes: int | None,
    steps: int | None,
    stress_status: str | None,
    status: str,
) -> list[str]:
    """범위 이탈일의 요인 추출 — 회고 + 그 전날 워치(수면·걸음·스트레스) 종합.

    [260705 R3] 회고만 되풀이하지 말고 워치 데이터(활동량·스트레스·수면)까지 함께 본다.
    high(평소보다 높음): 혈당을 올렸을 요인 / low(평소보다 낮음): 내렸을 요인. 최대 5개.
    """
    f: list[str] = []
    if status == "high":
        if log.dinner_before_7pm is False:
            f.append("늦은 저녁 식사")
        # [260728 고객 피드백 3 · 통합검증] 음주를 '혈당을 높인 요인'에서 제외.
        #   알코올은 간이 알코올을 처리하는 동안 밤사이 혈당을 끌어올리지 못하게 만들어
        #   다음날 아침 공복혈당을 **낮추는 쪽**으로 작용하는 것이 기본이다(low 분기에 이미 그렇게 있음).
        #   기존엔 high 분기에도 무조건 "음주"를 넣어서, 리포트 "🔴 혈당이 높았던 날의 공통점"에
        #   음주가 뜨고 있었다 — AI 피드백은 ai_prompts.py L83-89 로 고쳐 놓고 리포트만 반대로 말하는 모순.
        #   높은 쪽으로 귀속할 수 있는 건 **안주·식후 추가 음식의 탄수화물**뿐이므로,
        #   그 근거(식후 추가 섭취 / 외식·배달·고기 위주 저녁)가 실제로 있을 때만 그 이름으로 넣는다.
        #   (근거 판정식은 아래 두 분기와 동일하게 유지 — 기준이 갈리면 또 모순이 생긴다.)
        _late_extra_food = log.no_food_after_dinner_hours in ("1h_under", "1to2h") or (
            log.no_food_after_dinner_hours is None and log.no_food_after_dinner is False
        )
        _heavy_dinner = bool(
            log.dinner_type and any(t in ("eating_out", "meat") for t in log.dinner_type)
        )
        if log.alcohol is True and (_late_extra_food or _heavy_dinner):
            f.append("음주와 함께 든 안주·추가 음식")
        if log.no_food_after_dinner_hours in ("1h_under", "1to2h") or (
            log.no_food_after_dinner_hours is None and log.no_food_after_dinner is False
        ):
            f.append("식후 늦게까지 음식 섭취")
        # [피드백 260714 정리 · B1] 저녁유형 감점 대상
        # 260721 v1.1.3 flour 옵션 제거 — dinner_type에서 밀가루 제외
        if log.dinner_type and any(t in ("eating_out", "meat") for t in log.dinner_type):
            f.append("외식·배달·육류 저녁")
        if log.digestion == "full":
            f.append("배부른 상태로 취침")
        if log.post_meal_exercise == "none":
            f.append("식후 운동 없음")
        # [260705 R4] 워치 기반 요인 — 라벨에 날짜별 수치를 넣으면 '공통 이유' 집계가 쪼개져서 수치 제거
        #   (예: '활동량이 적었음(4,278보)'과 '(3,900보)'가 서로 다른 이유로 세어지던 문제)
        if steps is not None and steps < 5000:
            f.append("활동량이 적었음")
        if stress_status == "높음":
            f.append("스트레스가 높았음")
        # [260705] 7시간 살짝 못 미침을 '부족' 단정 금지 — 6시간 미만만 부족, 6~7시간은 '조금 짧음'
        if sleep_minutes is not None and sleep_minutes < 360:
            f.append("수면이 부족했음")
        elif sleep_minutes is not None and sleep_minutes < 420:
            f.append("수면이 조금 짧았음")
    elif status == "low":
        if log.post_meal_exercise in ("30to60", "60plus"):
            f.append("충분한 식후 운동")
        if log.dinner_type and all(t == "light" for t in log.dinner_type):
            f.append("가벼운 저녁 식사")
        if log.digestion == "hungry":
            f.append("배고픈 상태로 취침")
        if log.no_food_after_dinner_hours in ("3to4h", "4h_over"):
            f.append("긴 식후 공복 시간")
        # [260705 R4] 워치 기반 요인 — 집계용 고정 라벨(수치 제거)
        if steps is not None and steps >= 8000:
            f.append("활동량이 많았음")
        if sleep_minutes is not None and sleep_minutes >= 420:
            f.append("충분한 수면")
        if log.alcohol is True:
            f.append("음주 (야간 저혈당 유발 가능)")
    # [260714 정리 REP5 / Codex 후속2]
    #   여기선 '실제 감지된' raw 요인만 반환. 최대 5개 제한만 걸고, 일반 참고 문구(패딩)는
    #   호출부에서 팝업 표시용으로만 별도 적용해서 '공통 이유' 카운터 오염을 막는다.
    return f[:5]


def build_glucose_calendar(
    logs: list,
    watch_by_date: dict,
    usual_low: int | None,
    usual_high: int | None,
    stress_by_date: dict | None = None,   # [260705 R3] 전날 스트레스도 요인·분석에 반영
) -> dict:
    """[260623] 공복혈당 달력 데이터 — 일별 혈당/상태/요인 + 공통이유 top3 + 범위이탈 카운트.

    status: high(>평소 상한) / low(<평소 하한) / in_range / unknown.
    혈당 없는 날은 days에서 제외(프론트가 빈 셀).
    factors: 범위 이탈일만, 전날 저녁 회고 + 그 밤 수면(워치) 기반.

    [260728 확정①] 평소 범위(usual_low/high)가 없으면 높음/낮음/범위안 판정 자체가 불가하다.
      이전 동작: 그런 날을 전부 `in_range`로 내려 앱이 "범위 안 N일"이라고 단정했다 —
        범위를 입력하지 않은 사용자에게 사실이 아닌 판정을 보여주는 버그였다
        (당봄 260728 ②번 "99인데 범위 안에서 잘 유지"와 동일 계열의 사실 오류).
      현재 동작: 범위가 없으면 `unknown`(= '범위 없음', 판정 보류)으로 내리고
        in_range_count / high_count / low_count / out_of_range_count 어디에도 세지 않는다.
        사용자가 다음 달 범위를 지정하면 그 시점 이력으로 다시 판정된다.
      범위가 있는 사용자의 분류·집계는 이전과 100% 동일하다.
    """
    stress_by_date = stress_by_date or {}
    days: list[dict] = []
    # [260705 피드백] '혈당을 높인 요인'과 '낮춘 요인'을 분리 집계 (섞으면 음주 1일이 높인 건지 낮춘 건지 모름)
    high_counter: dict[str, int] = {}
    low_counter: dict[str, int] = {}
    counts = {"high": 0, "low": 0, "in_range": 0, "unknown": 0}
    day_metrics: list[tuple] = []   # (status, steps, sleep_min, stress_status) — 비교 분석용 (범위 안 날 포함)
    for log in sorted(logs, key=lambda l: l.log_date):
        g = log.fasting_glucose
        if g is None:
            continue
        # [260728 확정①] 범위 미설정 → in_range로 단정하지 않고 판정 보류(unknown).
        #   ⚠️ 순서 주의: 범위 유무를 먼저 걸러야 한다. 아래 else(=범위 안)는
        #   'usual_low/high가 둘 다 있고 그 사이인 날'만 도달해야 한다.
        if usual_low is None or usual_high is None:
            status = "unknown"
        elif g > usual_high:
            status = "high"
        elif g < usual_low:
            status = "low"
        else:
            status = "in_range"
        counts[status] += 1
        # 그 전날(회고가 가리키는 날) 워치·스트레스
        prev = log.log_date - timedelta(days=1)
        w = watch_by_date.get(prev)
        s = stress_by_date.get(prev)
        steps = w.steps if w else None
        sleep_min = w.sleep_total_minutes if w else None
        stress_status = s.morning_status if s else None
        day_metrics.append((status, steps, sleep_min, stress_status))
        display_factors: list[str] = []
        if status in ("high", "low"):
            # [Codex 후속2] raw = 실제 감지된 요인만 → '공통 이유' 카운터에 반영
            raw_factors = _glucose_day_factors(log, sleep_min, steps, stress_status, status)
            counter = high_counter if status == "high" else low_counter
            for fac in raw_factors:
                counter[fac] = counter.get(fac, 0) + 1
            # 팝업 표시용은 3~5개 보장 (padding). counter는 raw만 사용.
            display_factors = _pad_factors(raw_factors, status)
        days.append({
            "date": log.log_date.isoformat(),
            "glucose": g,
            "status": status,
            "factors": display_factors,
        })

    def _top3(counter: dict[str, int]) -> list[dict]:
        top = sorted(counter.items(), key=lambda kv: (kv[1], kv[0]), reverse=True)[:3]
        return [{"label": k, "count": v} for k, v in top]

    # [260705 R3] 실제 비교 분석 — 높았던/낮았던 날 vs 범위 안 날의 걸음·수면·스트레스 평균 비교.
    #   회고 나열이 아니라 "높았던 날은 평소(범위 안)보다 걸음이 적었다" 같은 데이터 기반 결론.
    def _avg(vals):
        vals = [v for v in vals if v is not None]
        return (sum(vals) / len(vals)) if vals else None

    in_steps = _avg([m[1] for m in day_metrics if m[0] == "in_range"])
    in_sleep = _avg([m[2] for m in day_metrics if m[0] == "in_range"])

    in_stress_n = sum(1 for m in day_metrics if m[0] == "in_range" and m[3] == "높음")

    def _analysis(status_key: str) -> str | None:
        rows = [m for m in day_metrics if m[0] == status_key]
        if not rows:
            return None
        st = _avg([m[1] for m in rows])
        sl = _avg([m[2] for m in rows])
        stress_high_n = sum(1 for m in rows if m[3] == "높음")
        segs: list[str] = []
        if st is not None and in_steps is not None and abs(st - in_steps) >= 1000:
            segs.append(f"걸음 {int(st):,}보 (범위 안이던 날 {int(in_steps):,}보)")
        if sl is not None and in_sleep is not None and abs(sl - in_sleep) >= 30:
            segs.append(f"수면 {sl / 60:.1f}시간 (범위 안이던 날 {in_sleep / 60:.1f}시간)")
        if stress_high_n > 0:
            # [코덱스 R3] 단순 카운트 대신 범위 안 날과 비교
            segs.append(f"스트레스 높음 {stress_high_n}일 (범위 안이던 날 {in_stress_n}일)")
        if not segs:
            return None
        label = "높았던" if status_key == "high" else "낮았던"
        return f"{label} 날 평균 — " + " · ".join(segs)

    return {
        "usual_range": (
            {"low": usual_low, "high": usual_high}
            if (usual_low is not None and usual_high is not None) else None
        ),
        "days": days,
        "high_reasons": _top3(high_counter),   # 혈당을 높였을 요인 (높았던 날들 기준)
        "low_reasons": _top3(low_counter),     # 혈당을 낮췄을 요인 (낮았던 날들 기준)
        "high_analysis": _analysis("high"),    # [260705 R3] 워치 데이터 비교 분석 문장
        "low_analysis": _analysis("low"),
        "in_range_count": counts["in_range"],
        "high_count": counts["high"],
        "low_count": counts["low"],
        "out_of_range_count": counts["high"] + counts["low"],
        # [260728 확정①] 평소 범위가 없어 판정 자체가 불가한 기록일 수.
        #   범위가 있으면 항상 0 → 범위 있는 사용자에겐 아무 변화 없음.
        #   범위가 없으면 recorded_days == unknown_count 이고 나머지 카운트는 전부 0이다.
        #   (앱: usual_range == null 이면 '범위 안/높음/낮음' 칩·문구를 띄우지 말 것)
        "unknown_count": counts["unknown"],
        "recorded_days": len(days),
    }


async def get_range_for_date(
    db: AsyncSession,
    user_id: uuid.UUID,
    target_date: date,
    period_end: date | None = None,
) -> tuple[int | None, int | None]:
    """[피드백 260719 H · Codex P1] target_date 시점에 유효했던 공복혈당 범위 반환.

    period_end 를 주면 target_date~period_end 를 '리포트 기간'으로 보고 규칙 2를 적용한다.
    (주간=week_start~week_end, 월간=month_start~month_end)

    반환 규칙:
      1) target_date 이전(포함) 이력이 있으면 → 그 이력값
      2) [260729 P1-B] 기간 시작 이전 이력이 없고, 기간 안(target_date~period_end)에
         발효된 이력이 있으면 → 그 중 '최초' 이력값
         소급이 아니라 '기간 내 발효'다. 이게 없으면 가입 당월/당주 리포트가 통째로
         unknown 이 된다: 8/5 가입 → effective_from=8/5 이력 1행 → 8/10 월간 리포트가
         get_range_for_date(8/1) 을 물어 (None,None) → 8/5~8/10 기록이 전부 unknown,
         범위 칩·요인 팝업이 9/1까지 하나도 안 뜬다. (주간도 동일)
      3) 이력이 아예 없으면(legacy, 백필 전) → users 현재값 fallback
      4) 이력은 있는데 기간 안에도 없으면 → (None, None)
         (기간 전체가 최초 설정 이전 = 그 시점엔 범위 정의 안 됨.
          현재 값을 소급 적용하지 않아 과거 리포트가 새 범위로 잘못 표시되는 것 방지)
         → 이 경우 build_glucose_calendar 가 [260728 확정①] 대로 unknown 을 유지한다.
    """
    res = await db.execute(
        select(GlucoseRangeHistory)
        .where(
            GlucoseRangeHistory.user_id == user_id,
            GlucoseRangeHistory.effective_from <= target_date,
        )
        .order_by(
            GlucoseRangeHistory.effective_from.desc(),
            GlucoseRangeHistory.saved_at.desc(),
        )
        .limit(1)
    )
    hist = res.scalar_one_or_none()
    if hist:
        return hist.range_low, hist.range_high

    # [260729 P1-B] 기간 내 최초 발효 이력 (가입 당월/당주 커버).
    #   effective_from <= target_date 는 위에서 이미 없다고 확인됐으므로 그 뒤 구간만 본다.
    if period_end is not None and period_end >= target_date:
        in_period_res = await db.execute(
            select(GlucoseRangeHistory)
            .where(
                GlucoseRangeHistory.user_id == user_id,
                GlucoseRangeHistory.effective_from > target_date,
                GlucoseRangeHistory.effective_from <= period_end,
            )
            .order_by(
                GlucoseRangeHistory.effective_from.asc(),
                GlucoseRangeHistory.saved_at.asc(),
            )
            .limit(1)
        )
        in_period = in_period_res.scalar_one_or_none()
        if in_period:
            return in_period.range_low, in_period.range_high

    # 이력이 target 이전에도, 기간 안에도 없음 → 아예 없는 건지 확인
    any_hist_res = await db.execute(
        select(GlucoseRangeHistory.id)
        .where(GlucoseRangeHistory.user_id == user_id)
        .limit(1)
    )
    if any_hist_res.scalar_one_or_none() is not None:
        # 이력 있지만 기간 안에 없음 → 소급 금지, None 반환 (달력은 unknown 유지)
        return None, None

    # 이력 자체가 없는 legacy 사용자 (백필 전 or 범위 설정 안 함) → 현재값 fallback
    user = await db.get(User, user_id)
    if user is None:
        return None, None
    return user.glucose_normal_low, user.glucose_normal_high


def build_weekly_fallback_summary(
    avg_score: float | None,
    daily_scores: list[int],
    avg_glucose: float | None,
    avg_steps: int | None,
    avg_sleep_hours: float | None,
    alcohol_days: int,
    dinner_days: int,
    late_eating_days: int,
) -> str | None:
    """워치 + 회고 데이터를 보고 자동 생성하는 주간 요약 (GPT 대체용)."""
    valid_scores = [s for s in daily_scores if s and s > 0]
    if not valid_scores:
        return None

    avg = avg_score or 0
    n_days = len(valid_scores)

    parts: list[str] = []

    # ── 1) 전반 평가 ──
    if avg >= 80:
        parts.append(f"이번 주 평균 {avg}점으로 매우 잘 관리하셨어요!")
    elif avg >= 70:
        parts.append(f"이번 주 평균 {avg}점, 좋은 흐름이에요.")
    elif avg >= 50:
        parts.append(f"이번 주 평균 {avg}점, 목표에 가까워지고 있어요.")   # [260705] '보통 수준' 금지 → 목표 근접 프레이밍
    else:
        parts.append(f"이번 주는 평균 {avg}점으로 컨디션이 평소보다 낮았네요.")

    # ── 2) 잘한 점 (최대 2개) ──
    # [P0 코덱스 — v1.8 용어 통일] "야식" → "식후 금식 미완료"
    pros: list[str] = []
    if dinner_days >= 5:
        pros.append(f"저녁 식사를 7시 전에 {dinner_days}일이나 마치신 점")
    if late_eating_days == 0 and n_days >= 3:
        pros.append("식후 금식을 깨끗하게 지키신 점")
    if alcohol_days == 0 and n_days >= 3:
        pros.append("음주 없이 보내신 점")
    if avg_steps and avg_steps >= 7000:
        pros.append(f"하루 평균 {avg_steps:,}보를 걸으신 점")
    if avg_sleep_hours and 7.0 <= avg_sleep_hours <= 9.0:
        pros.append(f"평균 수면 {avg_sleep_hours}시간으로 잘 주무신 점")

    if pros:
        parts.append("특히 " + ", ".join(pros[:2]) + "이 훌륭해요.")

    # ── 3) 개선점 (최대 2개) ──
    cons: list[str] = []
    if alcohol_days >= 2:
        cons.append(f"음주 {alcohol_days}일")
    if late_eating_days >= 2:
        cons.append(f"식후 금식 미완료 {late_eating_days}일")
    if dinner_days < 3 and n_days >= 5:
        cons.append(f"저녁 식사 7시 전 완료가 {dinner_days}일에 그친 점")
    if avg_glucose and avg_glucose > 130:
        cons.append(f"평균 공복혈당이 {int(avg_glucose)} mg/dL로 당뇨 목표 범위(100~130)보다 높은 점")
    if avg_sleep_hours and avg_sleep_hours < 6.0:
        cons.append(f"평균 수면이 {avg_sleep_hours}시간으로 부족한 점")
    if avg_steps and avg_steps < 4000:
        cons.append(f"하루 평균 {avg_steps:,}보로 활동량이 부족한 점")

    if cons:
        parts.append("다만 " + ", ".join(cons[:2]) + "이 다음 주 개선 포인트예요.")

    # ── 4) 다음 주 목표 1개 ──
    if alcohol_days >= 2:
        parts.append("다음 주는 음주 0~1일을 목표로 해보세요.")
    elif late_eating_days >= 2:
        parts.append("다음 주는 저녁 식사 후 금식을 5일 이상 도전해보세요.")
    elif dinner_days < 5 and n_days >= 5:
        parts.append("다음 주는 저녁 7시 전 식사 5일 이상을 목표로 해보세요.")
    elif avg_steps and avg_steps < 6000:
        parts.append("다음 주는 하루 7,000보 이상 걷기를 목표로 해보세요.")
    else:
        parts.append("이대로 다음 주도 유지해보세요!")

    return " ".join(parts)


@router.get("/weekly")
async def get_weekly_report(
    week_start: date = Query(..., description="월요일 날짜"),
    refresh: bool = Query(False, description="AI 주간 요약 강제 재생성 ('피드백 갱신하기' 버튼)"),
    user_id: uuid.UUID = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    return await generate_weekly_report_for_user(
        user_id=user_id, week_start=week_start, db=db, refresh=refresh
    )


async def generate_weekly_report_for_user(
    *,
    user_id: uuid.UUID,
    week_start: date,
    db: AsyncSession,
    refresh: bool = False,
) -> dict:
    """주간 리포트 생성 — GET /weekly 엔드포인트 + 배치 스케줄러가 공유.

    week_start(월요일)부터 7일 윈도우. refresh=True면 GPT 주간 요약을 강제 재생성.
    응답 dict 구조는 GET /weekly 와 동일.
    """
    week_end = week_start + timedelta(days=6)

    # 점수
    scores_result = await db.execute(
        select(LifestyleScore).where(
            LifestyleScore.user_id == user_id,
            LifestyleScore.score_date >= week_start,
            LifestyleScore.score_date <= week_end,
        ).order_by(LifestyleScore.score_date)
    )
    scores = scores_result.scalars().all()
    # [사용자 피드백] 일별 점수 변화 그래프가 비어있는 문제 해결.
    #   기존: 점수 있는 날만 daily_scores에 포함 → 1일만 입력하면 길이 1 → 클라이언트가 그래프 안 그림.
    #   개선: 7일 길이로 패딩 (월~일 인덱스 매핑). 점수 없는 날은 0 → 클라이언트는 0인 점 skip + 라벨만 표시.
    score_by_date = {s.score_date: (s.total_score or 0) for s in scores}
    daily_scores = [score_by_date.get(week_start + timedelta(days=i), 0) for i in range(7)]
    # 평균은 실제 점수 있는 날만 기준 (0점 제외)
    nonzero_scores = [s for s in daily_scores if s > 0]
    avg_score = round(sum(nonzero_scores) / len(nonzero_scores), 1) if nonzero_scores else None

    # 회고 카운트
    logs_result = await db.execute(
        select(DailyLog).where(
            DailyLog.user_id == user_id,
            DailyLog.log_date >= week_start,
            DailyLog.log_date <= week_end,
        )
    )
    logs = logs_result.scalars().all()
    alcohol_days = sum(1 for l in logs if l.alcohol is True)
    dinner_days = sum(1 for l in logs if l.dinner_before_7pm is True)
    late_eating_days = sum(1 for l in logs if l.no_food_after_dinner is False)

    # [피드백 260719 E] 신규 3항목 지표 (저녁유형/자기 전 소화/식후 운동)
    #   healthy_dinner_days: 저녁 유형이 '집밥 한식' 또는 '간단식'만 있는 날
    #   comfortable_digestion_days: 자기 전 소화가 '배고픔' 또는 '조금 됨' (full=배부름 제외)
    #   post_meal_exercise_days: 식후 운동 10분 이상 한 날 (10to30/30to60/60plus)
    healthy_dinner_days = sum(
        1 for l in logs
        if l.dinner_type and all(t in ("home_korean", "light") for t in l.dinner_type)
    )
    comfortable_digestion_days = sum(1 for l in logs if l.digestion in ("hungry", "partial"))
    post_meal_exercise_days = sum(1 for l in logs if l.post_meal_exercise in ("10to30", "30to60", "60plus"))

    avg_glucose_result = await db.execute(
        select(func.avg(DailyLog.fasting_glucose)).where(
            DailyLog.user_id == user_id,
            DailyLog.log_date >= week_start,
            DailyLog.log_date <= week_end,
        )
    )
    avg_glucose = avg_glucose_result.scalar()

    # 워치 평균 — [사용자 피드백 260616] '이번 주 평균'은 그 주(월~일) 자체의 워치 데이터로.
    #   (기존: 하루 앞당겨 쿼리 → 지난 주 일요일(예: 6/7) 수면이 이번 주(6/8~6/14)에 섞여 보임.
    #    상관관계 '어제 워치'는 아래 별도 쿼리(week_start-1)에서 처리하므로 지표 평균과 무관.)
    watch_from = week_start
    watch_to = week_end
    watch_result = await db.execute(
        select(
            func.avg(WatchData.steps),
            func.avg(WatchData.sleep_total_minutes),
        ).where(
            WatchData.user_id == user_id,
            WatchData.log_date >= watch_from,
            WatchData.log_date <= watch_to,
        )
    )
    avg_steps, avg_sleep_min = watch_result.one()

    avg_glucose_val = round(float(avg_glucose), 1) if avg_glucose else None
    avg_steps_val = int(avg_steps) if avg_steps else None
    avg_sleep_hours_val = round(float(avg_sleep_min) / 60, 1) if avg_sleep_min else None

    # [v1.0] 스트레스 적정 일수 — morning_status == "적정" 인 날 합계 (워치와 동일 hindsight 윈도우)
    # [코덱스 P1] 부가 지표 — 쿼리 실패 시 None으로 fallback (전체 리포트 깨지지 X)
    # [코덱스 P2] 한 쿼리로 total + relaxed 동시 집계 (FILTER 절)
    try:
        stress_q = await db.execute(
            select(
                func.count().label("total"),
                func.count().filter(StressData.morning_status == "적정").label("relaxed"),
            ).where(
                StressData.user_id == user_id,
                StressData.log_date >= watch_from,
                StressData.log_date <= watch_to,
            )
        )
        total_days_with_stress, relaxed_days = stress_q.one()
        stress_relaxed_days_val = int(relaxed_days or 0) if (total_days_with_stress or 0) > 0 else None
    except Exception:
        logger.exception("weekly stress_relaxed_days 계산 실패 — None으로 fallback")
        stress_relaxed_days_val = None

    # 코덱스 P2: 이전 주 평균 (주간 비교 화살표용 — 프론트 prev_week_avg_score 필드)
    prev_start = week_start - timedelta(days=7)
    prev_end = week_start - timedelta(days=1)
    prev_avg_raw = await db.scalar(
        select(func.avg(LifestyleScore.total_score)).where(
            LifestyleScore.user_id == user_id,
            LifestyleScore.score_date >= prev_start,
            LifestyleScore.score_date <= prev_end,
        )
    )
    prev_week_avg_score = round(float(prev_avg_raw), 1) if prev_avg_raw is not None else None

    # [사용자 피드백] 데이터 3일 미만이면 fallback도 안 보여주고 None — 클라이언트가 안내 멘트 표시
    # [코덱스 #7] 실제 '기록한 날' = 공복혈당을 입력한 날 수. (점수 0/누락을 입력일로 오인하던 conflation 방지)
    recorded_days = len([l for l in logs if l.fasting_glucose is not None])
    if recorded_days >= 3:
        # 워치 + 회고 기반 데이터-드리븐 fallback (GPT 실패 시 사용)
        fallback_summary = build_weekly_fallback_summary(
            avg_score=avg_score,
            daily_scores=daily_scores,
            avg_glucose=avg_glucose_val,
            avg_steps=avg_steps_val,
            avg_sleep_hours=avg_sleep_hours_val,
            alcohol_days=alcohol_days,
            dinner_days=dinner_days,
            late_eating_days=late_eating_days,
        )
    else:
        fallback_summary = None

    # ── 상관관계 인사이트 (당봄 #3) ──
    from app.services.correlation_service import build_correlation_insights, _LogPoint
    # 날짜별 점수/회고/워치 합치기
    score_by_date = {s.score_date: s.total_score for s in scores}
    watch_by_date_q = await db.execute(
        select(WatchData).where(
            WatchData.user_id == user_id,
            WatchData.log_date >= week_start - timedelta(days=1),  # 어제 워치도 포함
            WatchData.log_date <= week_end,
        )
    )
    watch_by_date = {w.log_date: w for w in watch_by_date_q.scalars().all()}

    # [QA 방어] 상관관계 인사이트는 부가 기능 — 어떤 예외도 리포트 전체(500)를 막지 않도록 best-effort.
    try:
        points = []
        for log in logs:
            w = watch_by_date.get(log.log_date - timedelta(days=1))  # 어제 워치
            points.append(_LogPoint(
                log_date=log.log_date,
                fasting_glucose=log.fasting_glucose,
                alcohol=log.alcohol,
                dinner_before_7pm=log.dinner_before_7pm,
                no_food_after_dinner=log.no_food_after_dinner,
                score=score_by_date.get(log.log_date),
                steps=w.steps if w else None,
                sleep_minutes=w.sleep_total_minutes if w else None,
            ))
        correlation_insights = build_correlation_insights(points)
    except Exception:
        correlation_insights = []

    # ── 주요 위험 요인 (당봄 #3) — 세부 점수 평균 손해 기준 상위 3개 ──
    # [260728] 수면 부족일은 점수(좌우 대칭이라 부족/과다 구분 불가)가 아니라 실제 수면 분으로 판정
    #   → 점수일(D)에 쓰인 워치(D-1) 수면 분을 넘긴다. watch_by_date 는 week_start-1 부터 조회돼 D-1 커버.
    top_risk_factors = _build_top_risk_factors(
        scores,
        top_n=3,
        min_observed=2,
        sleep_minutes_by_score_date=_sleep_minutes_by_score_date(scores, watch_by_date),
    )

    # ── GPT 주간 분석 — [260824 복원] 캐시. 완성된 주 + 3일 이상일 때만 '1회 생성 후 저장'.
    #   리포트 조회 시 온디맨드 생성 + WeeklyReport 캐시 재사용. refresh=true(피드백 갱신하기)일 때만 재생성.
    today_kst = datetime.now(KST).date()
    week_completed = week_end < today_kst
    can_refresh = week_completed and recorded_days >= 3  # '피드백 갱신하기' 버튼 노출 조건

    # "이번 주 N일 기록" 안내 — 완성된 주이고 7일 미만일 때 요약 맨 앞에 붙임 (생성 시점에 고정)
    days_note = (
        f"이번 주는 {recorded_days}일 기록해주셨어요. "
        if (week_completed and 0 < recorded_days < 7) else ""
    )

    # 기존 캐시(WeeklyReport) 로드
    cached_wr = (await db.execute(
        select(WeeklyReport).where(
            WeeklyReport.user_id == user_id,
            WeeklyReport.week_start_date == week_start,
        )
    )).scalar_one_or_none()

    # 캐시엔 'N일 기록' 노트 없이 raw 요약만 저장 → 노트는 응답 시점에 fresh로 prepend.
    #   며칠 더 채워 recorded_days가 바뀌어도 노트 숫자는 항상 최신 (요약 본문은 '갱신하기'로만 재생성).
    _note_re = r"^이번 주는 \d+일 기록해주셨어요\.\s*"
    raw_summary: str | None = None
    if not week_completed:
        raw_summary = None  # 진행 중인 주 — 분석 안 함
    elif recorded_days < 3:
        raw_summary = "기록이 3일 이상 쌓이면 주간 분석을 보여드려요." if recorded_days > 0 else None
    elif cached_wr is not None and cached_wr.gpt_summary and not refresh:
        raw_summary = re.sub(_note_re, "", cached_wr.gpt_summary)
    else:
        # 생성(refresh이거나 캐시 없음) — fallback + GPT, 일일 한도(₩50) 체크
        generated = fallback_summary
        if avg_score is not None:
            _midnight_kst = datetime(today_kst.year, today_kst.month, today_kst.day, tzinfo=KST)
            _midnight_utc = _midnight_kst.astimezone(timezone.utc)
            _cost_used = float((await db.execute(
                select(func.coalesce(func.sum(AIFeedback.gpt_cost_krw), 0.0)).where(
                    AIFeedback.user_id == user_id,
                    AIFeedback.created_at >= _midnight_utc,
                )
            )).scalar() or 0.0)
            if _cost_used < float(settings.daily_limit_per_user_krw):
                try:
                    gpt_payload = {
                        "week_start": week_start.isoformat(),
                        "week_end": week_end.isoformat(),
                        "avg_score": avg_score,
                        "prev_week_avg_score": prev_week_avg_score,
                        "daily_scores": daily_scores,
                        "recorded_days": recorded_days,
                        "metrics": {
                            "avg_glucose": avg_glucose_val,
                            "avg_steps": avg_steps_val,
                            "avg_sleep_hours": avg_sleep_hours_val,
                            "alcohol_days": alcohol_days,
                            "dinner_before_7pm_days": dinner_days,
                            "late_eating_days": late_eating_days,
                            "stress_relaxed_days": stress_relaxed_days_val,
                        },
                        "correlation_insights": correlation_insights,
                    }
                    gpt_result = await ai_service.call_weekly_report(gpt_payload)
                    if gpt_result and not gpt_result.get("_fallback"):
                        generated = gpt_result.get("summary") or fallback_summary
                except Exception:
                    logger.exception("[weekly report] GPT 호출 실패 — fallback 유지")
            else:
                logger.warning(f"[weekly report] AI budget exceeded user={user_id} → fallback")
        raw_summary = re.sub(_note_re, "", generated) if generated else fallback_summary

        # 캐시 저장(upsert, raw 요약) — 실패해도 응답엔 영향 없음
        if raw_summary:
            try:
                if cached_wr is None:
                    cached_wr = WeeklyReport(
                        user_id=user_id, week_start_date=week_start, week_end_date=week_end
                    )
                    db.add(cached_wr)
                cached_wr.gpt_summary = raw_summary
                cached_wr.avg_score = avg_score
                cached_wr.avg_glucose = avg_glucose_val
                cached_wr.avg_steps = avg_steps_val
                cached_wr.avg_sleep_hours = avg_sleep_hours_val
                cached_wr.alcohol_days = alcohol_days
                cached_wr.dinner_before_7pm_days = dinner_days
                cached_wr.late_eating_days = late_eating_days
                await db.commit()
            except Exception:
                await db.rollback()
                logger.exception("[weekly report] 캐시 저장 실패 — 응답엔 영향 없음")

    # 'N일 기록' 노트는 항상 fresh(현재 recorded_days 기준)로 raw 요약 앞에 붙인다.
    gpt_summary = (days_note + (raw_summary or "")).strip() or None

    # [260623] 공복혈당 달력 (주간) — 평소 범위 대비 일별 색·요인
    # [260705 R3] 전날 스트레스도 요인·비교분석에 사용 (D-1이라 week_start-1부터)
    _stress_rows = (await db.execute(
        select(StressData).where(
            StressData.user_id == user_id,
            StressData.log_date >= week_start - timedelta(days=1),
            StressData.log_date <= week_end,
        )
    )).scalars().all()
    _stress_by_date = {s.log_date: s for s in _stress_rows}
    # [피드백 260719 H · 260722 재확인] 주 시작일(월요일) 기준 유효 범위 조회.
    #   두 달 걸치는 주(예: 6/29~7/5)에도 주 시작이 6월이면 6월 범위 유지 — 사용자 요구.
    # [Codex P2 · TODO] glucose_calendar만 시점 범위 사용. lifestyle_scores의 glucose_score는
    #   여전히 저장 당시 users 현재값으로 계산됨(daily_logs.py 스코어링). 저장된 score를 소급
    #   재계산하는 건 이번 라운드 스콥 밖. avg_score/daily_scores는 이력과 어긋날 수 있음.
    # [260729 P1-B] period_end=week_end — 주 시작 이전 이력이 없으면 '그 주 안'의 최초 이력을 쓴다.
    #   (수요일 가입자의 가입 주가 통째로 unknown 이 되던 문제)
    _usual_low, _usual_high = await get_range_for_date(db, user_id, week_start, period_end=week_end)
    glucose_calendar = build_glucose_calendar(
        logs, watch_by_date,
        _usual_low,
        _usual_high,
        stress_by_date=_stress_by_date,
    )

    return {
        "week_start_date": week_start.isoformat(),
        "week_end_date": week_end.isoformat(),
        "avg_score": avg_score,
        "prev_week_avg_score": prev_week_avg_score,  # 코덱스 P2: 주간 비교용
        "recorded_days": recorded_days,   # [우노] 이번 주 기록 일수
        "can_refresh": can_refresh,        # [우노] '피드백 갱신하기' 버튼 노출 여부
        "glucose_calendar": glucose_calendar,   # [260623] 공복혈당 달력
        "metrics": {
            "avg_glucose": avg_glucose_val,
            "avg_steps": avg_steps_val,
            "avg_sleep_hours": avg_sleep_hours_val,
            "alcohol_days": alcohol_days,
            "dinner_before_7pm_days": dinner_days,
            "late_eating_days": late_eating_days,
            "stress_relaxed_days": stress_relaxed_days_val,
            # [피드백 260719 E] 신규 3항목 지표
            "healthy_dinner_days": healthy_dinner_days,
            "comfortable_digestion_days": comfortable_digestion_days,
            "post_meal_exercise_days": post_meal_exercise_days,
        },
        "daily_scores": daily_scores,
        "top_risk_factors": top_risk_factors,
        "correlation_insights": correlation_insights,
        "gpt_summary": gpt_summary,
    }


def build_monthly_fallback_summary(
    month: int,
    avg_score: float | None,
    prev_month_avg: float | None,
    best_day: dict | None,
    worst_day: dict | None,
    avg_glucose: float | None,
    avg_steps: int | None,
    alcohol_days: int,
    dinner_days: int,
    late_eating_days: int,
    days_with_data: int,
) -> str | None:
    """워치 + 회고 데이터를 보고 자동 생성하는 월간 요약 (GPT 대체용)."""
    if not avg_score or days_with_data < 1:
        return None

    parts: list[str] = []

    # ── 1) 전반 평가 ──
    if avg_score >= 80:
        parts.append(f"{month}월은 평균 {avg_score}점으로 매우 잘 관리하셨어요!")
    elif avg_score >= 70:
        parts.append(f"{month}월은 평균 {avg_score}점, 좋은 흐름을 유지하셨어요.")
    elif avg_score >= 50:
        parts.append(f"{month}월은 평균 {avg_score}점, 목표에 가까워지고 있어요.")   # [260705] '보통 수준' 금지
    else:
        parts.append(f"{month}월은 평균 {avg_score}점으로 컨디션이 평소보다 낮았네요.")

    # ── 2) 전월 대비 ──
    if prev_month_avg is not None:
        diff = round(avg_score - prev_month_avg, 1)
        if diff >= 3:
            parts.append(f"지난달({prev_month_avg}점)보다 {diff}점 올라 회복 흐름이에요.")
        elif diff <= -3:
            parts.append(f"지난달({prev_month_avg}점)보다 {abs(diff)}점 낮아졌어요.")

    # ── 3) 베스트 / 어려웠던 날 ──
    if best_day and worst_day and best_day["score"] - worst_day["score"] >= 15:
        b_date = best_day["date"][5:].replace("-", "/")  # "05/14"
        w_date = worst_day["date"][5:].replace("-", "/")
        parts.append(
            f"가장 좋았던 날은 {b_date}({best_day['score']}점), "
            f"어려웠던 날은 {w_date}({worst_day['score']}점)였어요."
        )

    # ── 4) 잘한 점 (목표 달성률 기준) ──
    # [P0 코덱스 — v1.8 용어 통일] "야식" → "식후 금식 미완료"
    pros: list[str] = []
    if dinner_days >= days_with_data * 0.6:
        pros.append(f"저녁 식사 7시 전 완료 {dinner_days}일")
    if alcohol_days <= days_with_data * 0.2:
        pros.append(f"음주 {alcohol_days}일로 절제")
    if late_eating_days <= days_with_data * 0.2:
        pros.append(f"식후 금식 미완료 {late_eating_days}일 이하")
    if avg_steps and avg_steps >= 7000:
        pros.append(f"하루 평균 {avg_steps:,}보")

    if pros:
        parts.append("특히 " + ", ".join(pros[:2]) + "이 인상적이에요.")

    # ── 5) 다음 달 추천 ──
    if alcohol_days > days_with_data * 0.3:
        parts.append("다음 달은 음주를 절반으로 줄이는 걸 목표로 해보세요.")
    elif late_eating_days > days_with_data * 0.3:
        parts.append("다음 달은 저녁 식사 후 금식을 늘려보세요.")
    elif dinner_days < days_with_data * 0.5:
        parts.append("다음 달은 저녁 7시 전 식사를 절반 이상 목표로 해보세요.")
    elif avg_glucose and avg_glucose > 130:
        parts.append("다음 달은 공복혈당 평균 130 이하(당뇨 목표 범위) 유지를 목표로 해보세요.")
    else:
        parts.append("이대로 다음 달도 유지해보세요!")

    return " ".join(parts)


@router.get("/monthly")
async def get_monthly_report(
    year: int = Query(...),
    month: int = Query(..., ge=1, le=12),
    refresh: bool = Query(False, description="AI 월간 요약 강제 재생성 ('피드백 갱신하기' 버튼)"),
    user_id: uuid.UUID = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    return await generate_monthly_report_for_user(
        user_id=user_id, year=year, month=month, db=db, refresh=refresh
    )


# [260826] get_weekly_report와 동일 패턴 — user_id를 JWT 대신 직접 받는 재사용 가능한 본체.
#   /api/admin/reports/monthly(관리자웹 "지금 생성하기")에서도 이 함수를 그대로 호출한다.
async def generate_monthly_report_for_user(
    *,
    user_id: uuid.UUID,
    year: int,
    month: int,
    db: AsyncSession,
    refresh: bool = False,
):
    _, last_day = calendar.monthrange(year, month)
    month_start = date(year, month, 1)
    month_end = date(year, month, last_day)

    # ── 점수 ──
    scores_result = await db.execute(
        select(LifestyleScore).where(
            LifestyleScore.user_id == user_id,
            LifestyleScore.score_date >= month_start,
            LifestyleScore.score_date <= month_end,
        ).order_by(LifestyleScore.score_date)
    )
    scores = scores_result.scalars().all()
    valid_scores = [(s.score_date, s.total_score) for s in scores if s.total_score]
    daily_scores = [s.total_score or 0 for s in scores]
    avg_score = round(sum(s for _, s in valid_scores) / len(valid_scores), 1) if valid_scores else None

    # ── 베스트 / 어려웠던 날 ──
    best_day = None
    worst_day = None
    if valid_scores:
        best = max(valid_scores, key=lambda x: x[1])
        worst = min(valid_scores, key=lambda x: x[1])
        best_day = {"date": best[0].isoformat(), "score": best[1]}
        worst_day = {"date": worst[0].isoformat(), "score": worst[1]}

    # ── 전월 평균 ──
    prev_month_start = (month_start - timedelta(days=1)).replace(day=1)
    prev_month_end = month_start - timedelta(days=1)
    prev_scores_result = await db.execute(
        select(func.avg(LifestyleScore.total_score)).where(
            LifestyleScore.user_id == user_id,
            LifestyleScore.score_date >= prev_month_start,
            LifestyleScore.score_date <= prev_month_end,
        )
    )
    prev_month_avg_raw = prev_scores_result.scalar()
    prev_month_avg = round(float(prev_month_avg_raw), 1) if prev_month_avg_raw else None

    # ── 주차별 점수 평균 ──
    weekly_scores = []
    current = month_start
    while current <= month_end:
        week_end = min(current + timedelta(days=6), month_end)
        week_scores_in = [s.total_score for s in scores if current <= s.score_date <= week_end and s.total_score]
        if week_scores_in:
            # [260706 Codex] 소수 1자리 유지 — 82.5가 82로 절단되던 것 (앱은 List<Double> 파싱이라 안전)
            weekly_scores.append(round(sum(week_scores_in) / len(week_scores_in), 1))
        else:
            weekly_scores.append(0)
        current = week_end + timedelta(days=1)

    # ── 회고 통계 ──
    logs_result = await db.execute(
        select(DailyLog).where(
            DailyLog.user_id == user_id,
            DailyLog.log_date >= month_start,
            DailyLog.log_date <= month_end,
        )
    )
    logs = logs_result.scalars().all()
    days_with_data = len([l for l in logs if l.fasting_glucose is not None])
    alcohol_days = sum(1 for l in logs if l.alcohol is True)
    dinner_days = sum(1 for l in logs if l.dinner_before_7pm is True)
    late_eating_days = sum(1 for l in logs if l.no_food_after_dinner is False)
    no_late_eating_days = sum(1 for l in logs if l.no_food_after_dinner is True)
    # [피드백 260719 E] 신규 3항목 지표 (월간)
    healthy_dinner_days = sum(
        1 for l in logs
        if l.dinner_type and all(t in ("home_korean", "light") for t in l.dinner_type)
    )
    comfortable_digestion_days = sum(1 for l in logs if l.digestion in ("hungry", "partial"))
    post_meal_exercise_days = sum(1 for l in logs if l.post_meal_exercise in ("10to30", "30to60", "60plus"))

    # ── 혈당 평균 + 분포 (Time In Range) ──
    glucoses = [l.fasting_glucose for l in logs if l.fasting_glucose]
    avg_glucose = round(sum(glucoses) / len(glucoses), 1) if glucoses else None

    # [260623 피드백] 당뇨인 3구간 — 정상(<100) / 당뇨 목표 범위(100~130) / 높음(>130). (키는 프론트 호환 유지)
    #   126~130이 '높음(관리필요)'으로 빠지던 문제(코덱스 P0) 수정 — impaired 상한을 130으로.
    glucose_dist = {
        "normal": sum(1 for g in glucoses if 70 <= g < 100),          # 정상 (<100)
        "impaired": sum(1 for g in glucoses if 100 <= g <= 130),      # 당뇨인 목표 범위 (100~130)
        "diabetic_managed": sum(1 for g in glucoses if 130 < g <= 180),  # 높음 (130~180)
        "diabetic_high": sum(1 for g in glucoses if g > 180),         # 매우 높음 (>180)
        "low": sum(1 for g in glucoses if g < 70),                    # 저혈당
    }

    # ── 워치 (상관관계용 — 어제 워치 포함): 점수 의미 "오늘 로그 + 어제 워치"라 하루 오프셋.
    #   월 평균 '지표'는 아래 metrics 쿼리에서 month_start~month_end로 별도 계산 (지난 달 말일 섞임 방지).
    watch_from = month_start - timedelta(days=1)
    watch_to = month_end - timedelta(days=1)
    watch_result = await db.execute(
        select(
            func.avg(WatchData.steps),
            func.avg(WatchData.sleep_total_minutes),
        ).where(
            WatchData.user_id == user_id,
            # [사용자 피드백 260616] 이번 달 평균 지표는 그 달(1일~말일) 워치로 (상관관계는 위 watch_from/to로 어제 워치 사용)
            WatchData.log_date >= month_start,
            WatchData.log_date <= month_end,
        )
    )
    avg_steps_raw, avg_sleep_min = watch_result.one()
    avg_steps = int(avg_steps_raw) if avg_steps_raw else None
    avg_sleep_hours = round(float(avg_sleep_min) / 60, 1) if avg_sleep_min else None

    # ── 상관관계 인사이트 (당봄 #3) — weekly와 동일 로직 (날짜별 점수 + 회고 + 어제 워치) ──
    from app.services.correlation_service import build_correlation_insights, _LogPoint
    score_by_date = {s.score_date: s.total_score for s in scores}
    watch_by_date_result = await db.execute(
        select(WatchData).where(
            WatchData.user_id == user_id,
            WatchData.log_date >= watch_from,  # 어제 워치 포함 (month_start - 1일)
            WatchData.log_date <= watch_to,
        )
    )
    watch_by_date = {w.log_date: w for w in watch_by_date_result.scalars().all()}
    # [QA 방어] 상관관계 인사이트는 부가 기능 — 어떤 예외도 리포트 전체(500)를 막지 않도록 best-effort.
    try:
        points = []
        for log in logs:
            w = watch_by_date.get(log.log_date - timedelta(days=1))  # 어제 워치
            points.append(_LogPoint(
                log_date=log.log_date,
                fasting_glucose=log.fasting_glucose,
                alcohol=log.alcohol,
                dinner_before_7pm=log.dinner_before_7pm,
                no_food_after_dinner=log.no_food_after_dinner,
                score=score_by_date.get(log.log_date),
                steps=w.steps if w else None,
                sleep_minutes=w.sleep_total_minutes if w else None,
            ))
        correlation_insights = build_correlation_insights(points)
    except Exception:
        correlation_insights = []

    # ── 주요 위험 요인 (당봄 #3) — 월간은 관측 5일 이상일 때만 ──
    # [260728] 수면 부족일 판정용 실제 수면 분 전달 (weekly와 동일).
    #   watch_by_date 는 month_start-1 ~ month_end-1 이라 점수일 D의 D-1 을 그대로 커버.
    top_risk_factors = _build_top_risk_factors(
        scores,
        top_n=3,
        min_observed=5,
        sleep_minutes_by_score_date=_sleep_minutes_by_score_date(scores, watch_by_date),
    )

    # [v1.0] 스트레스 적정 일수 — 한 달 중 morning_status == "적정" 인 날 합계
    # [코덱스 P1+P2] try/except + 단일 쿼리 (FILTER 절)
    try:
        m_stress_q = await db.execute(
            select(
                func.count().label("total"),
                func.count().filter(StressData.morning_status == "적정").label("relaxed"),
            ).where(
                StressData.user_id == user_id,
                StressData.log_date >= watch_from,
                StressData.log_date <= watch_to,
            )
        )
        m_total, m_relaxed = m_stress_q.one()
        m_stress_relaxed_days_val = int(m_relaxed or 0) if (m_total or 0) > 0 else None
    except Exception:
        logger.exception("monthly stress_relaxed_days 계산 실패 — None으로 fallback")
        m_stress_relaxed_days_val = None

    # ── 목표 달성률 (3개 핵심) — 코덱스 P2: days_with_data=0 시 100% 오해 방지
    if days_with_data == 0:
        goal_achievements = {
            "dinner_before_7pm": {"achieved": 0, "total": 0, "rate": 0},
            "no_alcohol": {"achieved": 0, "total": 0, "rate": 0},
            "no_late_eating": {"achieved": 0, "total": 0, "rate": 0},
        }
    else:
        total = days_with_data
        goal_achievements = {
            "dinner_before_7pm": {
                "achieved": dinner_days,
                "total": total,
                "rate": round(dinner_days / total * 100),
            },
            "no_alcohol": {
                "achieved": total - alcohol_days,
                "total": total,
                "rate": round((total - alcohol_days) / total * 100),
            },
            "no_late_eating": {
                "achieved": no_late_eating_days,
                "total": total,
                "rate": round(no_late_eating_days / total * 100),
            },
        }

    # [우노] 월간 요약/AI는 최소 10일 기록(약 2주)이 있어야 (4~5일로 월간 분석 방지)
    fallback_summary = None
    if days_with_data >= 10:
        fallback_summary = build_monthly_fallback_summary(
            month=month,
            avg_score=avg_score,
            prev_month_avg=prev_month_avg,
            best_day=best_day,
            worst_day=worst_day,
            avg_glucose=avg_glucose,
            avg_steps=avg_steps,
            alcohol_days=alcohol_days,
            dinner_days=dinner_days,
            late_eating_days=late_eating_days,
            days_with_data=days_with_data,
        )

    # ── GPT 월간 분석 — [260824 복원] 캐시. 10일 이상일 때 '1회 생성 후 저장', refresh=true에만 재생성.
    #   월간은 gpt-4o(비용↑)라 매 조회마다 재호출하지 않도록 MonthlyReport에 캐시한다.
    can_refresh = days_with_data >= 10  # '피드백 갱신하기' 버튼 노출 조건
    cached_mr = (await db.execute(
        select(MonthlyReport).where(
            MonthlyReport.user_id == user_id,
            MonthlyReport.year == year,
            MonthlyReport.month == month,
        )
    )).scalar_one_or_none()

    if days_with_data < 10:
        gpt_summary = fallback_summary  # 10일 미만 — GPT 미호출 (보통 None)
    elif cached_mr is not None and cached_mr.gpt_summary and not refresh:
        gpt_summary = cached_mr.gpt_summary  # 캐시 사용 — GPT 재호출 X
    else:
        # 생성(refresh이거나 캐시 없음) — fallback + GPT, 일일 한도(₩50) 체크
        gpt_summary = fallback_summary
        if avg_score is not None:
            _now_kst2 = datetime.now(KST)
            _midnight_kst2 = datetime(_now_kst2.year, _now_kst2.month, _now_kst2.day, tzinfo=KST)
            _midnight_utc2 = _midnight_kst2.astimezone(timezone.utc)
            _cost_used2 = float((await db.execute(
                select(func.coalesce(func.sum(AIFeedback.gpt_cost_krw), 0.0)).where(
                    AIFeedback.user_id == user_id,
                    AIFeedback.created_at >= _midnight_utc2,
                )
            )).scalar() or 0.0)
            if _cost_used2 >= float(settings.daily_limit_per_user_krw):
                logger.warning(f"[monthly report] AI budget exceeded user={user_id} → fallback")
            else:
                try:
                    gpt_payload = {
                        "year": year,
                        "month": month,
                        "avg_score": avg_score,
                        "prev_month_avg_score": prev_month_avg,
                        "weekly_scores": weekly_scores,
                        "best_day": best_day,
                        "worst_day": worst_day,
                        "glucose_distribution": glucose_dist,
                        "goal_achievements": goal_achievements,
                        "metrics": {
                            "avg_glucose": avg_glucose,
                            "avg_steps": avg_steps,
                            "avg_sleep_hours": avg_sleep_hours,
                            "alcohol_days": alcohol_days,
                            "dinner_before_7pm_days": dinner_days,
                            "late_eating_days": late_eating_days,
                            "stress_relaxed_days": m_stress_relaxed_days_val,
                            "days_with_data": days_with_data,
                            "total_days": last_day,
                        },
                    }
                    gpt_result = await ai_service.call_monthly_report(gpt_payload)
                    if gpt_result and not gpt_result.get("_fallback"):
                        gpt_summary = gpt_result.get("summary") or fallback_summary
                except Exception:
                    logger.exception("[monthly report] GPT 호출 실패 — fallback 유지")
        # 캐시 저장(upsert) — 실패해도 응답엔 영향 없음
        if gpt_summary:
            try:
                if cached_mr is None:
                    cached_mr = MonthlyReport(user_id=user_id, year=year, month=month)
                    db.add(cached_mr)
                cached_mr.gpt_summary = gpt_summary
                cached_mr.avg_score = avg_score
                cached_mr.avg_glucose = avg_glucose
                cached_mr.avg_steps = avg_steps
                cached_mr.avg_sleep_hours = avg_sleep_hours
                cached_mr.alcohol_days = alcohol_days
                cached_mr.dinner_before_7pm_days = dinner_days
                cached_mr.late_eating_days = late_eating_days
                cached_mr.weekly_scores = weekly_scores
                await db.commit()
            except Exception:
                await db.rollback()
                logger.exception("[monthly report] 캐시 저장 실패 — 응답엔 영향 없음")

    # [260623] 공복혈당 달력 (월간) — 평소 범위 대비 일별 색·요인
    # [260705 R3] 전날 스트레스도 요인·비교분석에 사용 (D-1이라 month_start-1부터)
    _stress_rows = (await db.execute(
        select(StressData).where(
            StressData.user_id == user_id,
            StressData.log_date >= month_start - timedelta(days=1),
            StressData.log_date <= month_end,
        )
    )).scalars().all()
    _stress_by_date = {s.log_date: s for s in _stress_rows}
    # [피드백 260719 H] 해당 월 1일 기준 유효 범위 조회 (월간은 두 달 걸치는 문제 없음)
    # [260729 P1-B] period_end=month_end — 1일 이전 이력이 없으면 '그 달 안'의 최초 이력을 쓴다.
    #   (가입 당월 리포트가 통째로 unknown 이 되던 문제)
    _usual_low, _usual_high = await get_range_for_date(db, user_id, month_start, period_end=month_end)
    glucose_calendar = build_glucose_calendar(
        logs, watch_by_date,
        _usual_low,
        _usual_high,
        stress_by_date=_stress_by_date,
    )

    return {
        "year": year,
        "month": month,
        "avg_score": avg_score,
        "can_refresh": can_refresh,   # [우노] '피드백 갱신하기' 버튼 노출 여부
        "prev_month_avg_score": prev_month_avg,
        "weekly_scores": weekly_scores,
        "glucose_calendar": glucose_calendar,   # [260623] 공복혈당 달력 (월간)
        "best_day": best_day,
        "worst_day": worst_day,
        "glucose_distribution": glucose_dist,
        "goal_achievements": goal_achievements,
        "metrics": {
            "avg_glucose": avg_glucose,
            "avg_steps": avg_steps,
            "avg_sleep_hours": avg_sleep_hours,
            "alcohol_days": alcohol_days,
            "dinner_before_7pm_days": dinner_days,
            "late_eating_days": late_eating_days,
            "stress_relaxed_days": m_stress_relaxed_days_val,
            # [피드백 260719 E] 신규 3항목 지표 (월간)
            "healthy_dinner_days": healthy_dinner_days,
            "comfortable_digestion_days": comfortable_digestion_days,
            "post_meal_exercise_days": post_meal_exercise_days,
            "days_with_data": days_with_data,
            "total_days": last_day,
        },
        "top_risk_factors": top_risk_factors,
        "correlation_insights": correlation_insights,
        "gpt_summary": gpt_summary,
    }


# ─────────────────────────── 관리자웹 전용 트리거 ───────────────────────────
# [260826] gpt_summary는 원래 "환자가 앱에서 리포트 화면을 열면" 생성·캐시된다.
#   관리자웹은 Supabase를 직접 읽기만 해서, 환자가 아직 그 주/달을 안 열어봤으면
#   완료된 주/충분한 기록이 있어도 요약이 계속 비어 있었다(실사용 확인됨). 의료진이
#   "지금 생성하기" 버튼으로 직접 트리거할 수 있도록 관리자 전용 엔드포인트를 추가한다.
#   관리자웹은 환자 JWT가 없으므로 공유 비밀키(X-Admin-Key)로 인증한다.
def _verify_admin_key(x_admin_key: str | None) -> None:
    if not settings.admin_api_key or x_admin_key != settings.admin_api_key:
        raise HTTPException(status_code=403, detail="관리자 인증 실패")


@router.post("/admin/weekly")
async def admin_generate_weekly_report(
    user_id: uuid.UUID,
    week_start: date,
    refresh: bool = Query(False),
    db: AsyncSession = Depends(get_db),
    x_admin_key: str | None = Header(None, alias="X-Admin-Key"),
):
    _verify_admin_key(x_admin_key)
    return await generate_weekly_report_for_user(
        user_id=user_id, week_start=week_start, db=db, refresh=refresh
    )


@router.post("/admin/monthly")
async def admin_generate_monthly_report(
    user_id: uuid.UUID,
    year: int,
    month: int,
    refresh: bool = Query(False),
    db: AsyncSession = Depends(get_db),
    x_admin_key: str | None = Header(None, alias="X-Admin-Key"),
):
    _verify_admin_key(x_admin_key)
    return await generate_monthly_report_for_user(
        user_id=user_id, year=year, month=month, db=db, refresh=refresh
    )
