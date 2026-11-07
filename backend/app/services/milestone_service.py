"""마일스톤 / 실시간 점수 유도 서비스 (당봄 #12).

기능:
1. detect_milestones() — 주간 패턴에서 축하 알림 트리거 감지
   - 3일/5일/7일 연속 목표 달성
   - 지난주 대비 +10% 활동량 증가
   - 점수 신기록 달성
2. realtime_score_hint() — 현재 걸음 수 기반 잔여 점수 안내
   - "2,800보 더 걸으면 +6점 올라가요" 같은 멘트

GPT 안 쓰고 룰 기반 (비용 0).
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import date, timedelta


@dataclass
class DailyPoint:
    log_date: date
    steps: int | None
    score: int | None
    glucose: int | None
    sleep_minutes: int | None


# ============================================================
# 마일스톤 감지
# ============================================================
def detect_milestones(
    this_week: list[DailyPoint],
    last_week: list[DailyPoint] | None = None,
    all_time_best_score: int | None = None,
) -> list[dict]:
    """주간 패턴에서 축하할 만한 마일스톤 감지.

    return: [
        {
            "type": "streak_3" | "streak_5" | "streak_7" | "weekly_steps_up" | "score_record",
            "title": "🎯 3일 연속 목표 달성!",
            "message": "좋은 출발이에요. 이대로만 가세요!",
            "priority": 1 (높을수록 중요),
        }, ...
    ]
    """
    milestones: list[dict] = []

    # ── 1) 연속 목표 달성 (마지막 N일 연속 6000보+ 또는 70점+) ──
    sorted_this = sorted(this_week, key=lambda p: p.log_date, reverse=True)
    consecutive = 0
    for p in sorted_this:
        steps_ok = p.steps is not None and p.steps >= 6000
        score_ok = p.score is not None and p.score >= 70
        if steps_ok or score_ok:
            consecutive += 1
        else:
            break

    if consecutive >= 7:
        milestones.append({
            "type": "streak_7",
            "title": "🏆 7일 연속 목표 달성!",
            "message": "한 주 내내 꾸준하셨어요. 최고예요!",
            "priority": 3,
        })
    elif consecutive >= 5:
        milestones.append({
            "type": "streak_5",
            "title": "🏆 5일 연속 목표 달성!",
            "message": "이번 주 최고예요. 주말도 화이팅!",
            "priority": 2,
        })
    elif consecutive >= 3:
        milestones.append({
            "type": "streak_3",
            "title": "🎯 3일 연속 목표 달성!",
            "message": "좋은 출발이에요. 이대로만 가세요!",
            "priority": 1,
        })

    # ── 2) 지난주 대비 활동량 +10% 이상 ──
    if last_week:
        this_steps = [p.steps for p in this_week if p.steps is not None]
        last_steps = [p.steps for p in last_week if p.steps is not None]
        if this_steps and last_steps and len(this_steps) >= 4 and len(last_steps) >= 4:
            this_avg = sum(this_steps) / len(this_steps)
            last_avg = sum(last_steps) / len(last_steps)
            if last_avg > 0:
                ratio = (this_avg - last_avg) / last_avg
                if ratio >= 0.10:
                    pct = int(ratio * 100)
                    milestones.append({
                        "type": "weekly_steps_up",
                        "title": f"📈 지난주보다 활동량 {pct}% 증가!",
                        "message": f"평균 {int(last_avg):,}보 → {int(this_avg):,}보, 정말 멋져요.",
                        "priority": 2,
                    })

    # ── 3) 점수 신기록 ──
    this_max_score = max((p.score for p in this_week if p.score is not None), default=None)
    if this_max_score and all_time_best_score is not None and this_max_score > all_time_best_score:
        milestones.append({
            "type": "score_record",
            "title": "🌟 점수 신기록 달성!",
            "message": f"역대 최고 {this_max_score}점을 기록하셨어요.",
            "priority": 3,
        })

    # 우선순위 높은 순으로 정렬 후 상위 2개만
    milestones.sort(key=lambda m: -m["priority"])
    return milestones[:2]


# ============================================================
# 실시간 점수 유도 멘트 (당봄 #12)
# ============================================================
def realtime_score_hint(
    current_steps: int | None,
    current_hour: int,
    goal_steps: int = 8000,
) -> dict | None:
    """현재 걸음 수 기반 실시간 잔여 점수 멘트 생성.

    Args:
        current_steps: 현재까지 걸음 수 (워치 실시간 데이터)
        current_hour: 현재 시각 (0~23) — 시간대별 차등 멘트
        goal_steps: 목표 (기본 8000보)

    Returns:
        {
            "current": 5200,
            "remaining": 2800,
            "potential_score_gain": 6,
            "message": "지금까지 5,200보 · 2,800보만 더 걸으면 +6점 올라가요 💪",
            "tone": "encourage" | "celebrate" | "rest"
        } or None (데이터 없을 시)
    """
    if current_steps is None:
        return None

    # 8000보 만점 = base 10점 가정 (steps_score 로직 참조)
    # 6000~7999 = 8점, 4000~5999 = 6점, 2000~3999 = 3점
    def steps_to_base_score(s: int) -> int:
        if s >= 8000:
            return 10
        if s >= 6000:
            return 8
        if s >= 4000:
            return 6
        if s >= 2000:
            return 3
        return 0

    cur_base = steps_to_base_score(current_steps)
    max_possible = 10
    remaining_to_goal = max(0, goal_steps - current_steps)
    potential_gain = max_possible - cur_base

    # 목표 달성 시
    if current_steps >= goal_steps:
        return {
            "current": current_steps,
            "remaining": 0,
            "potential_score_gain": 0,
            "message": "🏆 오늘 목표 달성! 활동 점수 만점이에요",
            "tone": "celebrate",
        }

    # 시간대별 차등 멘트
    if current_hour >= 21:
        # 밤 — 무리한 운동 권유 X
        return {
            "current": current_steps,
            "remaining": remaining_to_goal,
            "potential_score_gain": potential_gain,
            "message": f"오늘 {current_steps:,}보 걸으셨어요. 내일 다시 도전해보세요 😊",
            "tone": "rest",
        }

    # 격차 큰 경우 — 격려 톤
    if cur_base == 0:
        message = (
            f"오늘 활동이 아직 적어요. "
            f"6,000보까지 약 {6000 - current_steps:,}보 남았어요. "
            f"식후 산책 어떠세요?"
        )
        tone = "encourage"
    elif remaining_to_goal <= 500:
        message = f"{remaining_to_goal:,}보만 더 걸으면 만점! 🎯"
        tone = "celebrate"
    else:
        message = (
            f"지금까지 {current_steps:,}보 · "
            f"{remaining_to_goal:,}보만 더 걸으면 +{potential_gain}점 올라가요 💪"
        )
        tone = "encourage"

    return {
        "current": current_steps,
        "remaining": remaining_to_goal,
        "potential_score_gain": potential_gain,
        "message": message,
        "tone": tone,
    }


# ============================================================
# 점수 변화 격려 멘트 (당봄 #11)
# ============================================================
def score_change_celebration(
    today_score: int | None,
    yesterday_score: int | None,
) -> dict | None:
    """전날 대비 점수 변화 시각 격려 메시지.

    return: {
        "delta": +7,
        "title": "🎉 전날보다 7점 좋아졌어요!",
        "tone": "celebrate" | "maintain" | "encourage",
    } or None
    """
    if today_score is None or yesterday_score is None:
        return None
    delta = today_score - yesterday_score

    if delta >= 3:
        return {
            "delta": delta,
            "title": f"🎉 전날보다 {delta:g}점 좋아졌어요!",
            "tone": "celebrate",
        }
    if delta <= -3:
        return {
            "delta": delta,
            "title": f"💪 오늘 다시 도전해볼까요?",
            "tone": "encourage",
        }
    return {
        "delta": delta,
        "title": "✓ 꾸준히 유지하고 있어요",
        "tone": "maintain",
    }
