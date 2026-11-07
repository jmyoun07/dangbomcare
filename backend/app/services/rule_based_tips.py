"""룰 기반 즉시 안내 메시지 — AI 호출 없이 시간대/상황별 멘트 생성.

AI 비용 절감 (당봄 피드백):
- 매일 점수 산출 시에만 AI 호출
- 일상 알림/팁은 룰 기반 (GPT 호출 X)

예시:
- 점심 후 1시간인데 걸음 수 적음 → "저녁 식후 산책 추천"
- 저녁 8시인데 식후 추가 음식 없이 잘 지킨 상태 → 격려
- 취침 직전 → "오늘 점수 확인하고 주무세요"
"""
from __future__ import annotations

from datetime import datetime, time, timedelta, timezone


KST = timezone(timedelta(hours=9))


def get_now_kst() -> datetime:
    return datetime.now(KST)


def get_contextual_tip(
    *,
    current_steps: int | None = None,
    current_hour: int | None = None,
    yesterday_alcohol: bool | None = None,
    yesterday_sleep_hours: float | None = None,
    fasting_glucose: int | None = None,
    meal_times: dict | None = None,
) -> str | None:
    """현재 시각/상황 기반 즉시 멘트 — AI 호출 없이 룰로 생성.

    None 반환 = 알릴 멘트 없음 (정상 상태).
    """
    hour = current_hour or get_now_kst().hour
    meal_times = meal_times or {"breakfast": 7.5, "lunch": 12.5, "dinner": 18.0}

    # ── 식후 산책 권장 (식후 30분~2시간 사이) ──
    # 아침 식후 (예: 7:30 식사 → 8:00~9:30 산책 권장)
    breakfast_hour = meal_times.get("breakfast", 7.5)
    if breakfast_hour + 0.5 <= hour <= breakfast_hour + 2:
        if (current_steps or 0) < 1000:
            return "🚶 아침 식사 후 10분 산책은 어떠세요? 혈당 안정에 도움됩니다."

    # 점심 식후
    lunch_hour = meal_times.get("lunch", 12.5)
    if lunch_hour + 0.5 <= hour <= lunch_hour + 2:
        if (current_steps or 0) < 3000:
            return "🚶 점심 식후 가벼운 산책으로 오후 컨디션을 챙겨보세요."

    # 저녁 식후
    dinner_hour = meal_times.get("dinner", 18.0)
    if dinner_hour + 0.5 <= hour <= dinner_hour + 2:
        if (current_steps or 0) < 5000:
            return "🚶 저녁 식후 10분 걷기로 혈당 스파이크를 막아보세요."

    # ── 어제 음주 시 오전 권장 ──
    if 9 <= hour <= 11 and yesterday_alcohol:
        return "💧 전날 음주가 있으셨네요. 오늘은 무리하지 마시고 가벼운 활동을 추천드려요."

    # ── 수면 부족 시 (어제 < 7시간) ──
    if 7 <= hour <= 10 and yesterday_sleep_hours and yesterday_sleep_hours < 7:
        return "😴 전날 수면이 부족했어요. 오늘은 무리하지 마시고 점심 식후 짧은 휴식을 권장해요."

    # ── 공복혈당 높음 (당뇨 목표 범위 100~130 초과) ──
    if 7 <= hour <= 9 and fasting_glucose and fasting_glucose > 130:
        return "🩸 오늘 공복혈당이 평소보다 높아요. 식사량을 조금 줄이고 식후 걷기를 권장해요."

    # ── 저녁 시간 — 7시 전 식사 권장 ──
    if 17 <= hour <= 19:
        return "🍚 저녁은 7시 전에 마치시면 혈당 안정에 도움됩니다."

    # ── 취침 1시간 전 ──
    if 21 <= hour <= 22:
        return "😴 이제 슬슬 잠자리 준비를 해주세요. 충분한 수면이 내일 혈당을 안정시켜요."

    return None


def get_today_summary_tip(
    *,
    score: int | None = None,
    glucose: int | None = None,
    sleep_hours: float | None = None,
    alcohol: bool | None = None,
) -> str | None:
    """점수 산출 후 표시할 한 줄 요약 — AI 호출 없이 룰로 생성.

    HomeScreen 등에서 score_card 옆에 부수적으로 표시 가능.
    """
    if score is None:
        return None

    if score >= 85:
        return "✨ 전날 정말 잘 보내셨어요. 이대로 유지해주세요!"
    elif score >= 70:
        if sleep_hours and sleep_hours < 7:
            return "👍 점수는 양호한데 수면을 좀 더 챙겨보세요."
        if alcohol:
            return "👍 점수는 좋아요. 다만 음주는 줄이시면 더 좋겠어요."
        return "👍 안정적인 컨디션이에요."
    elif score >= 50:
        return "💪 조금만 더 신경 쓰시면 회복할 수 있어요."
    else:
        return "🌱 오늘부터 다시 시작해보세요. 작은 습관이 큰 변화를 만들어요."
