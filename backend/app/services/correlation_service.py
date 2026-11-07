"""주간/월간 상관관계 인사이트 — 데이터 기반 자동 분석.

당봄 #3 — "식후 금식 지킨 날 vs 안 지킨 날" 같은 환자 친화 인과관계 문구.

[사용자 피드백] 점수(score)로 비교하면 워치 미착용 등으로 수면/스트레스 점수가 빠져
왜곡됨. → '실제 공복혈당(mg/dL)' 차이로 비교한다. 같은 레코드 기준:
  LogPoint(D) = 오늘(D) 아침 공복혈당 + 어제(D-1) 저녁 회고 + 어제(D-1) 워치.
  즉 "어제 행동/수면/활동 ↔ 오늘 아침 공복혈당" 이 한 레코드에 정렬돼 있어 같은-날 비교가 맞음.

GPT 안 쓰고 통계로 처리 (비용 0, 일관성 ↑).
"""
from __future__ import annotations

from dataclasses import dataclass


@dataclass
class _LogPoint:
    log_date: object  # date
    fasting_glucose: int | None
    alcohol: bool | None
    dinner_before_7pm: bool | None
    no_food_after_dinner: bool | None
    score: int | None
    steps: int | None
    sleep_minutes: int | None


def _avg(xs: list[float | int]) -> float:
    return sum(xs) / len(xs) if xs else 0.0


# 의미 있는 공복혈당 차이 (mg/dL). 측정 노이즈를 넘어서는 수준.
_MIN_GLUCOSE_DIFF = 5
_MIN_GROUP = 2  # 각 그룹 최소 표본 (1번 측정 우연 방지)


def _glucose_lower_insight(
    emoji: str, good_label: str, bad_label: str,
    glu_good: list[int], glu_bad: list[int],
) -> str | None:
    """좋은 습관 그룹(glu_good)의 공복혈당이 나쁜 그룹(glu_bad)보다 의미 있게 낮으면 문구 반환."""
    if len(glu_good) < _MIN_GROUP or len(glu_bad) < _MIN_GROUP:
        return None
    lo, hi = _avg(glu_good), _avg(glu_bad)
    diff = hi - lo  # 양수 = 좋은 습관 날이 더 낮음
    if diff < _MIN_GLUCOSE_DIFF:
        return None
    return (
        f"{emoji} {good_label} 아침 공복혈당이 평균 {int(lo)} mg/dL로, "
        f"{bad_label}(평균 {int(hi)})보다 약 {int(diff)} 낮았어요."
    )


def build_correlation_insights(points: list[_LogPoint]) -> list[str]:
    """일자별 데이터 → '실제 공복혈당' 기준 상관관계 문장들 (상위 3개).

    최소 데이터: 공복혈당 있는 날 5일 이상.
    """
    insights: list[str] = []
    valid = [p for p in points if p.fasting_glucose is not None]
    if len(valid) < 5:
        return insights

    # ── 1) 음주 — 음주한 날 공복혈당이 더 '높을' 때만 경고 ──
    #   (당뇨 환자가 음주를 긍정 신호로 오해하지 않도록, 낮은 경우는 출력 X)
    #
    # ⚠️ [260729 통합검증 P1] 관측값은 그대로 두되 **원인 귀속 문구를 바꿨다.**
    #   기존 문구는 "음주한 날 공복혈당이 … 높았어요."로 끝나 사실상 '술 → 다음날 공복혈당 상승'을
    #   말했는데, 이 프로젝트는 260728 에 그 인과를 **의학적 오류로 확정하고 금지**했다:
    #     · ai_prompts.py L260-263 : "술을 드시면 다음날 공복혈당이 높아지는 경향" = ❌ 금지 예시
    #     · reports.py `_glucose_day_factors` : high 분기에서 '음주'를 제거하고,
    #       안주·식후 추가 음식 근거가 있을 때만 "음주와 함께 든 안주·추가 음식"으로 넣도록 바꿈
    #   그런데 **같은 리포트 응답**의 correlation_insights 는 손대지 않아, 한 화면에서
    #   "혈당이 높았던 날의 공통점"에는 음주가 없는데 "이번 주 패턴 발견"에는 음주가 상승 요인으로
    #   떴다 — 이 프로젝트의 반복 실패 패턴("한 층만 고치면 다른 층이 반대로 말한다") 재발.
    #   → 통계 관측은 유지(데이터 사실이다), 대신 원인을 술 자체로 단정하지 않고 리포트·프롬프트와
    #     같은 귀속 대상(안주·식후 추가 음식)을 관찰형으로 덧붙인다. 양 추정("많이 드셔서")은 금지.
    alc_y = [p.fasting_glucose for p in valid if p.alcohol is True]
    alc_n = [p.fasting_glucose for p in valid if p.alcohol is False]
    if len(alc_y) >= _MIN_GROUP and len(alc_n) >= _MIN_GROUP:
        diff = _avg(alc_y) - _avg(alc_n)  # 양수 = 음주한 날이 더 높음
        if diff >= _MIN_GLUCOSE_DIFF:
            insights.append(
                f"🍷 음주하신 날 아침 공복혈당이 평균 {int(_avg(alc_y))} mg/dL로, "
                f"안 마신 날(평균 {int(_avg(alc_n))})보다 약 {int(diff)} 높았어요. "
                "술 자체보다는 함께 드신 안주나 식사 후 추가로 드신 음식의 영향일 수 있어요."
            )

    # ── 2) 식후 금식 — 지킨 날 공복혈당이 더 낮으면 도움 ──
    fast_y = [p.fasting_glucose for p in valid if p.no_food_after_dinner is True]
    fast_n = [p.fasting_glucose for p in valid if p.no_food_after_dinner is False]
    m = _glucose_lower_insight("🌙", "식후 금식을 지킨 날", "안 지킨 날", fast_y, fast_n)
    if m:
        insights.append(m)

    # ── 3) 이른 저녁 ──
    # ⚠️ [260729 통합검증 P2] 하드코딩 '7시' 제거. 컬럼명은 legacy 로 dinner_before_7pm 이지만
    #   의미는 "**사용자가 설정한 저녁 마감 시각** 전에 마쳤는가"다(마감을 20시로 둔 분에게
    #   "7시 전에 드신 날"은 사실과 다르다). 260726 에 daily 경로에서 임의 시각을 전부 없앴는데
    #   이 문장만 남아 있었다. 마감 시각 라벨은 이 함수에 넘어오지 않으므로 시각을 말하지 않는다.
    din_y = [p.fasting_glucose for p in valid if p.dinner_before_7pm is True]
    din_n = [p.fasting_glucose for p in valid if p.dinner_before_7pm is False]
    m = _glucose_lower_insight("🍚", "저녁을 마감 시각 전에 마치신 날", "늦게 드신 날", din_y, din_n)
    if m:
        insights.append(m)

    # ── 4) 수면 (어제 워치 ↔ 오늘 공복혈당) ──
    slp_good = [p.fasting_glucose for p in valid if p.sleep_minutes is not None and p.sleep_minutes >= 7 * 60]
    slp_short = [p.fasting_glucose for p in valid if p.sleep_minutes is not None and p.sleep_minutes < 7 * 60]
    m = _glucose_lower_insight("😴", "7시간 이상 주무신 날", "부족한 날", slp_good, slp_short)
    if m:
        insights.append(m)

    # ── 5) 활동량 (어제 워치 ↔ 오늘 공복혈당) ──
    stp_high = [p.fasting_glucose for p in valid if p.steps is not None and p.steps >= 6000]
    stp_low = [p.fasting_glucose for p in valid if p.steps is not None and p.steps < 4000]
    m = _glucose_lower_insight("🚶", "6,000보 이상 걸으신 날", "적게 걸은 날", stp_high, stp_low)
    if m:
        insights.append(m)

    # 효과 크기(혈당 차이) 큰 순 상위 3개 — 메시지 끝의 '약 N 낮/높았어요'의 N
    import re as _re

    def _effect_size(s: str) -> int:
        nums = _re.findall(r"(\d+)", s)
        return int(nums[-1]) if nums else 0

    insights.sort(key=_effect_size, reverse=True)
    return insights[:3]
