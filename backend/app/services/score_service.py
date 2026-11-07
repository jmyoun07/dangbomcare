"""점수 산출 서비스

[배점 — 260623 재배분 확정. 정본은 아래 WEIGHTS_MAX]
  수면 20 / 공복혈당 15 / 걸음 10 / 식후 운동 10 / 저녁 마감 시각 10 /
  음주 절제 10 / 식후 금식 10 / 저녁식사 유형 5 / 자기 전 소화 5 / 스트레스 5  = 100

  ※ '저녁 마감 시각'의 키 이름은 dinner_before_7pm 이지만 7시 고정이 아니다.
    사용자가 user_settings.dinner_deadline_time 에 설정한 시각이 기준이다(이름은 과거 잔재).
  ※ 걸음의 '식후 걷기 가산점'은 260623 개편에서 제거됐다.
    식후 활동은 '식후 운동'(post_meal_exercise, 10점)으로 분리됐다.
    post_meal_walking_status() 는 점수가 아니라 안내 멘트 생성용으로만 남아 있다.

[수면 구간 배점 — 260728 사용자 확정. 최저 6점, **0점 구간 없음**]
  0~3h 6 / 3~4h 8 / 4~5h 11 / 5~6h 14 / 6~7h 17 /
  **7~9h 20 (적정)** / 9~10h 18 / 10~11h 16 / 11~12h 14 / 12~13h 12 / 13h↑ 10
  ※ 비대칭 — 부족 쪽 3점씩, 과다 쪽 2점씩. 수면 부족이 혈당에 더 강한 요인이라서다.
  적정은 양 끝 포함(7.0 <= h <= 9.0), 부족 쪽은 [하한,상한) / 과다 쪽은 (하한,상한].
  수면 데이터가 없으면(또는 0분) 채점 제외(None) — 자세한 근거는 sleep_score() docstring.

[미측정 항목 처리 — 260727 사용자 확정: **0점**]
  만점은 항상 100 고정이며, 측정되지 않은 항목은 그만큼 실점으로 반영된다.
  (워치 미착용일은 수면 20 + 걸음 10 + 스트레스 5 = 최대 35점 손실)
  calculate_score() 가 돌려주는 valid_max 는 '측정된 항목의 만점 합'이라는 참고값일 뿐이며,
  ⚠️ 이 값을 점수 표시의 분모로 쓰면 확정된 정책이 깨진다. 앱은 의도적으로 /100 을 쓴다.
"""
from __future__ import annotations

from typing import Any

# [260623 피드백] 점수 재배분 (합 100)
WEIGHTS_MAX = {
    "glucose": 15,
    "steps": 10,
    "sleep": 20,
    "dinner_before_7pm": 10,
    "alcohol": 10,
    "no_food_after_dinner": 10,
    "stress": 5,
    "dinner_type": 5,
    "digestion": 5,
    "post_meal_exercise": 10,
}


def glucose_score(
    fasting_glucose: int | None,
    normal_low: int | None = None,
    normal_high: int | None = None,
) -> int | None:
    """공복혈당 점수 (15점 만점) — [260623] 당뇨인 3구간 기반 재배분.

    - <100 (정상): 15 (고득점)
    - 100~130 (당뇨 목표): 10 (중간)
    - >130 (높음): 낮음 — 단, 본인 평소범위(normal_high) 이내면 '평상시'로 보고 중간(10) 유지.
        그 외엔 초과 정도만큼 감점 (130<g<=150:6 / 150<g<=180:3 / >180:0).
    - <70 (저혈당): 0 — glucose_safety_status가 별도 안전 안내.
    """
    if fasting_glucose is None:
        return None
    g = fasting_glucose
    if g < 70:
        return 0
    if g < 100:
        return 15
    if g <= 130:
        return 10
    # g > 130 — 평소 범위 이내면 중간 유지, 아니면 초과 정도만큼 감점
    if normal_high is not None and normal_high > 0 and g <= normal_high:
        return 10
    if g <= 150:
        return 6
    if g <= 180:
        return 3
    return 0


def glucose_safety_status(fasting_glucose: int | None) -> str | None:
    """공복혈당 안전성 상태 분류 (코덱스 검토 P0 — CDC 기준).

    의료 안전:
    - <55: 즉시 의료적 대응 필요 (심각한 저혈당)
    - 55~69: 저혈당 — 운동 미션 금지, 재측정 권유
    - 70~250: 정상 범위 (점수 계산)
    - 251~299: 매우 높음 — 미션 보수적
    - >=300: 즉시 의료진 상담 (고혈당 위기)

    Returns:
        "critical_low" | "low" | "normal" | "very_high" | "critical_high" | None
    """
    if fasting_glucose is None:
        return None
    g = fasting_glucose
    if g < 55:
        return "critical_low"
    if g < 70:
        return "low"
    if g <= 250:
        return "normal"
    if g < 300:
        return "very_high"
    return "critical_high"


def glucose_safety_message(status: str | None, glucose: int | None) -> str | None:
    """안전성 상태별 환자 안내 메시지 (점수 카드 위에 노출용)."""
    if status is None or glucose is None:
        return None
    if status == "critical_low":
        return f"⚠️ 공복혈당 {glucose} mg/dL : 즉시 단당류(주스/사탕) 섭취 후 재측정해주세요. 의식이 흐려지면 가족·119에 도움 요청하세요."
    if status == "low":
        return f"⚠️ 저혈당 의심 ({glucose} mg/dL). 가벼운 간식 후 15분 뒤 재측정하시고, 운동 미션은 잠시 미뤄주세요."
    if status == "very_high":
        # [P0 의료 안전] 위험 단어 / 약물 지시 제거 — 관찰형 + 의료진 상담 권장으로 완화
        return f"⚠️ 공복혈당 {glucose} mg/dL : 평소보다 매우 높게 측정됐어요. 무리한 활동을 피하시고 잠시 안정을 취해주세요."
    if status == "critical_high":
        return f"⚠️ 공복혈당 {glucose} mg/dL : 매우 높은 수치입니다. 무리한 활동을 피하시고 당봄한의원 또는 주치의에게 상담을 권장드립니다."
    return None


def should_suppress_mission(
    safety_status: str | None,
    fasting_glucose: int | None = None,
) -> bool:
    """저혈당/위기성 고혈당/매우 높은 고혈당이면 미션(특히 운동) 출제 금지.

    코덱스 검토 P1 — 251~299 mg/dL (very_high) 도 의료 안전상 미션 자제 권장.
    CDC DKA 안내: 당뇨 환자가 혈당 250 mg/dL 이상이면 케톤 확인 + 무리한 활동 자제.
    [260722 codex HIGH 1] 200 mg/dL 이상도 즉시 관리 필요 임계 (ADA/당뇨학회 기준) —
      "very_high" 라벨(251+) 은 유지하되, 미션 억제 트리거만 200으로 낮춤.
      예: 공복혈당 220 mg/dL 사용자에게 식후 걷기 미션이 나가지 않도록 방어.
    """
    if safety_status in {"critical_low", "low", "very_high", "critical_high"}:
        return True
    # [260722 codex HIGH 1] 라벨은 "normal"이지만 200 mg/dL 이상이면 미션 자제
    if fasting_glucose is not None and fasting_glucose >= 200:
        return True
    return False


def apply_safety_override(ai_out: dict, fasting_glucose: int | None) -> dict:
    """[260726][P0 의료 안전] 저장된 공복혈당 기준으로 '응답 시점에' 안전 오버라이드를 적용한다.

    저혈당/고혈당 위기에서는 GPT 문구·미션·격려가 위험할 수 있어 안전 안내로 대체한다.

    ⚠️ 이전 구현은 이 오버라이드를 ai_feedbacks 행(fb)에 직접 write 했다. 그런데 regen_capped /
       7일 초과 경로는 fb 저장 블록을 스킵하므로, 사용자가 오타(예: 40 mg/dL)를 정상값으로 정정해도
       fb.daily_message 가 복구되지 않아 "저혈당 경고"가 하루 종일 고착됐다.
       → fb 에는 원본 GPT 응답만 저장하고, 응답을 만들 때마다 저장된 공복혈당으로 다시 판정한다.

    ⚠️ 이 함수는 AI 피드백을 사용자에게 노출하는 **모든** 경로에서 호출해야 한다.
       (POST /api/daily-logs, GET /api/daily-logs/{log_date}, GET /api/ai-feedback/today)
       한 곳이라도 빠지면 저혈당 상황에서 원본 GPT 문구와 운동 미션이 그대로 노출된다.
    """
    status = glucose_safety_status(fasting_glucose)
    out = dict(ai_out)
    if should_suppress_mission(status, fasting_glucose):
        msg = glucose_safety_message(status, fasting_glucose)
        if msg is None and fasting_glucose is not None:
            # 200~250 구간은 status 가 "normal" 이라 위 함수가 None 을 준다.
            #   (라벨 체계상 "very_high" 는 251+) → 이 구간 전용 안내를 별도로 만든다.
            msg = (
                f"⚠️ 공복혈당 {fasting_glucose} mg/dL : 평소보다 높게 측정됐어요. "
                "무리한 활동은 피하시고 잠시 안정을 취해주세요."
            )
        out["message"] = msg or (
            "공복혈당이 안전 범위를 벗어났어요. 무리한 활동을 피하시고 의료진 상담을 권장드립니다."
        )
        out["mission"] = None
        out["encouragement"] = None
        out["influencing_factors"] = []
    elif status is not None and status != "normal":
        # 방어층: suppress 조건이 향후 바뀌어도 비정상 혈당에 "점수 올랐어요" 격려가 새어나가지 않도록.
        out["encouragement"] = None
    return out


def steps_score(
    steps: int | None,
    steps_hourly: list[int] | None = None,
    meal_times: dict | None = None,
) -> tuple[int | None, int]:
    """걸음 점수 (10점 만점, base only) — [260623] 식후 걷기 가산점은 '식후 운동' 항목으로 분리.
    steps_hourly/meal_times 는 식후 걷기 안내(post_meal_walking_status)용으로만 쓰이고 점수엔 미반영.
    return (score, 0)
    """
    if steps is None:
        return None, 0

    # Base 점수 (10점 만점)
    if steps >= 8000:
        base = 10
    elif steps >= 6000:
        base = 8
    elif steps >= 4000:
        base = 6
    elif steps >= 2000:
        base = 3
    else:
        base = 0

    # [260623 피드백] 식후 걷기 가산점은 점수에서 제거 — '식후 운동'이 별도 항목(10점)으로 분리됨.
    #   (식후 걷기 안내 멘트용 post_meal_walking_status 함수는 그대로 유지.)
    return base, 0


def post_meal_walking_status(
    steps_hourly: list[int] | None,
    meal_times: dict | None,
) -> dict[str, dict]:
    """각 식사 직후(30분~2시간) 걷기 상태 반환 — 안내 멘트 자동 생성용.

    return: {
        "breakfast": {"steps": 1200, "sufficient": True, "label": "충분"},
        "lunch":     {"steps":  300, "sufficient": False, "label": "부족"},
        "dinner":    {"steps":   80, "sufficient": False, "label": "거의 없음"},
    }

    당봄 #8: 식사 시간 이후 30분~1시간 내 걸음 100보 미만이면
    "🍚 식후 산책으로 혈당 스파이크를 줄여보세요" 멘트 노출.
    """
    result: dict[str, dict] = {}
    if not (steps_hourly and meal_times and len(steps_hourly) >= 24):
        return result

    for meal_name, meal_hour in meal_times.items():
        try:
            meal_hour_f = float(meal_hour)
        except (TypeError, ValueError):
            continue
        if meal_hour_f < 0 or meal_hour_f >= 24:
            continue
        # 식후 1~2시간 슬롯 (steps_score와 동일 윈도우)
        start_h = int(meal_hour_f) + 1
        end_h = start_h + 1
        if start_h > 23:
            continue
        end_h = min(end_h, 23)
        post_meal_steps = sum(steps_hourly[h] for h in range(start_h, end_h + 1))

        if post_meal_steps >= 1000:
            label = "충분"
            sufficient = True
        elif post_meal_steps >= 500:
            label = "보통"
            sufficient = True
        elif post_meal_steps >= 100:
            label = "부족"
            sufficient = False
        else:
            label = "거의 없음"
            sufficient = False

        result[meal_name] = {
            "steps": post_meal_steps,
            "sufficient": sufficient,
            "label": label,
        }
    return result


def detect_late_sleep_anomaly(
    yesterday_sleep_start_hour: float | None,
    avg_sleep_start_hour: float | None,
    late_sleep_reason: str | None = None,
    threshold_hours: float = 2.0,
) -> dict | None:
    """평소 자정 전 취침인데 어제 2시간 이상 늦었으면 감지 (당봄 #8).

    Args:
        yesterday_sleep_start_hour: 어제 취침 시각 (24시간 표기, 02:00 = 2.0)
        avg_sleep_start_hour: 평소 평균 취침 시각 (7일 평균)
        late_sleep_reason: 'job' (야간/교대근무) | 'habit' | None
        threshold_hours: 평소 대비 얼마나 늦으면 감지할지 (기본 2시간)

    Returns:
        {"detected": True, "delay_hours": 2.5, "yesterday_hour": 2.0, "avg_hour": 23.5} or None
    """
    if late_sleep_reason == "job":
        return None  # 야간/교대 근무자는 감지 스킵
    if yesterday_sleep_start_hour is None or avg_sleep_start_hour is None:
        return None

    # 자정 넘어가는 케이스 정규화 (0~12시 새벽 = +24, 18~24시 저녁 = 그대로)
    def normalize(h: float) -> float:
        return h + 24 if h < 12 else h

    y = normalize(yesterday_sleep_start_hour)
    a = normalize(avg_sleep_start_hour)
    delay = y - a

    # 어제가 자정 이후이고 평소보다 threshold_hours 이상 늦으면 감지
    if y >= 24 and delay >= threshold_hours:
        return {
            "detected": True,
            "delay_hours": round(delay, 1),
            "yesterday_hour": yesterday_sleep_start_hour,
            "avg_hour": avg_sleep_start_hour,
        }
    return None


def sleep_score(sleep_minutes: int | None) -> int | None:
    """수면 점수 (20점 만점) — [260728 사용자 확정] 적정에서 멀어질수록 감점, 최저 6점(부족)/10점(과다).

    ⚠️ [260728 통합검증] 이 첫 줄에 "2점씩 · 최저 10점"이라고 적혀 있었다. 아래 표(정본)와
      module docstring([수면 구간 배점])은 3점씩(부족)/2점씩(과다) · 최저 6점이라 서로 어긋났다.
      한 파일 안에서 배점이 두 가지로 읽히면 다음 사람이 표가 아니라 첫 줄을 믿고 되돌린다.
      → 표·module docstring·앱 sleepScoreForMinutes 와 일치하도록 첫 줄을 정정했다(동작 변경 없음).

    입력은 분(minutes). h = sleep_minutes / 60.

      부족 (3점씩)              과다 (2점씩)
      7.0 <= h <= 9.0  -> 20  (적정)
      6.0 <= h <  7.0  -> 17   9.0 <  h <= 10.0 -> 18
      5.0 <= h <  6.0  -> 14  10.0 <  h <= 11.0 -> 16
      4.0 <= h <  5.0  -> 11  11.0 <  h <= 12.0 -> 14
      3.0 <= h <  4.0  ->  8  12.0 <  h <= 13.0 -> 12
              h <  3.0 ->  6          h >  13.0 -> 10

    적정은 양 끝 포함(h=7.0→20, h=6.99→17, h=9.0→20, h=9.01→18).
    부족 쪽 구간은 [하한, 상한), 과다 쪽 구간은 (하한, 상한].

    ⚠️ **비대칭은 의도된 것이다. "부족/과다 감점 폭이 다르다"고 보고 대칭으로 맞추지 마라.**
      부족 쪽 3점씩 / 과다 쪽 2점씩. 근거:
        · 수면 부족은 인슐린 저항성을 즉각 올리는 확립된 요인이다.
          고객 문서 원문: "수면 부족은 인슐린 저항성을 즉각적으로 높입니다. 따라서 수면 시간이
          7시간 미만인 날에는 AI 코멘트에 (…) 경고 문구를 우선 노출해주세요."
        · 과다수면은 혈당과의 연관성이 약하고, 아프거나 피로할 때 오래 자는 경우도 있어
          부족과 같은 폭으로 깎는 것이 부당하다.

    ⚠️ **0점 구간은 의도적으로 없다. "0점이 빠졌다"고 보고 되돌리지 마라.**
      고객(당봄) 260728 지적:
        "점수가 너무 짜게 부여하고 있어. 어떻게 4시간 미만이 0점이니. 적어도 10점을 주고"
        "수면 부족도 0점이 아니라 차등점수 부여 아니었어? 0점인 경우는 수면 데이터 자체가 없는 경우만"
      이전 구현(7~9h=20 / 6~7·9~10h=12 / 5~6h=6 / 나머지 0)은 문서에 없던 임의값이었고,
      4시간 수면과 10시간 48분 수면이 똑같이 0점이 되어 점수 차이가 과하게 벌어졌다.
      (동일한 지적으로 '식후 금식'은 260623에 0/10 → 5단계 차등으로 이미 바뀌었다.)
      고객이 확정한 것은 "적정 7~9시간"과 "수면 20점"이고, 구간 차등은 위 표가 정본이다.
      최저 6점은 만점의 30% — 고객이 직접 정한 다른 항목 최저점(음주·식후운동·저녁마감·
      저녁유형·소화·걸음 = 0점, 식후금식 = 2점)과의 형평을 고려해 260728에 확정했다.

    미측정(None) 및 **0분 이하 → None (채점 제외)**.
      0분은 '진짜 0분 수면'이 아니라 기록 결함이다 — 앱 업로드 경로 근거:
        · Samsung SDK 경로(SamsungHealthSource.fetchWatchData)는
          `sleepTotal = t.takeIf { it > 0 }` 로 0을 명시적으로 null 로 바꿔 보낸다.
        · Health Connect 경로(HealthConnectRepository.fetchWatchData)는 세션이 있으면
          분 단위 절삭(`(end-start)/60`)이라 1분 미만/길이 0 세션이면 0이 새어들 수 있다.
        · 스키마는 `sleep_total_minutes: int|None = Field(ge=0, le=1440)` 로 0을 통과시키고
          upsert 는 `is not None` 이라 0이 그대로 저장된다.
        · 백엔드 읽기 경로들은 이미 0을 falsy(=미측정)로 취급한다
          (watch_data.get_watch_data 의 `if wd.sleep_total_minutes else None`, daily_summary 동일).
      → 0분에 10점을 주면 UI 는 "수면 기록 없음"인데 점수만 붙어 260727 미측정 정책과 어긋난다.
      (채점 제외 = 합산에서 빠지고 만점은 100 고정이므로, 표시상으로는 그만큼 실점이다.)
    """
    if sleep_minutes is None or sleep_minutes <= 0:
        return None
    h = sleep_minutes / 60
    if 7.0 <= h <= 9.0:
        return 20
    if h < 7.0:
        # 부족 쪽 — [하한, 상한). 과다 쪽보다 감점 폭이 크다(3점씩 vs 2점씩) — 아래 비대칭 근거 참조.
        if h >= 6.0:
            return 17
        if h >= 5.0:
            return 14
        if h >= 4.0:
            return 11
        if h >= 3.0:
            return 8
        return 6
    # 과다 쪽 — (하한, 상한]
    if h <= 10.0:
        return 18
    if h <= 11.0:
        return 16
    if h <= 12.0:
        return 14
    if h <= 13.0:
        return 12
    return 10


def dinner_before_7pm_score(value: bool | None) -> int | None:
    """7시 전 식사 완료 점수 (10점)"""
    if value is None:
        return None
    return 10 if value else 0


def alcohol_score(value: bool | None) -> int | None:
    """음주 절제 점수 (10점)"""
    if value is None:
        return None
    return 10 if not value else 0


def no_food_after_dinner_score(value: bool | None, hours: str | None = None) -> int | None:
    """식후 금식 점수 (10점) — [260623] 5단계 (식후 음식 섭취까지 시간).
    1h미만 2 / 1~2h 4 / 2~3h 6 / 3~4h 8 / 4h↑ 10. hours 없으면 기존 불리언 fallback.
    """
    tier = {"1h_under": 2, "1to2h": 4, "2to3h": 6, "3to4h": 8, "4h_over": 10}
    if hours in tier:
        return tier[hours]
    if value is None:
        return None
    return 10 if value else 0  # 구버전 불리언 fallback


def dinner_type_score(dinner_type: list | None) -> int | None:
    """저녁식사 유형 점수 (5점) — [260623 + 260720 개편].
    한식(home_korean)·간단식(light)만이면 5, 그 외 유형(eating_out/meat) 하나라도 포함 시 0.
    - eating_out: 외식/배달
    - meat: 고기 위주
    - other: 기타(자유입력) — 채점 대상에서 제외. other만 선택하면 점수 미산출(None).
    """
    if not dinner_type:
        return None
    scored = [t for t in dinner_type if t != "other"]
    if not scored:
        return None  # '기타'만 선택한 경우 점수 미산출
    healthy = {"home_korean", "light"}
    return 5 if all(t in healthy for t in scored) else 0


def digestion_score(digestion: str | None) -> float | None:
    """자기 전 소화 점수 (5점) — [260623]. 배고픔(hungry)5 / 소화 조금 됨(partial)2.5 / 배부름(full)0.
    (명세대로 중간 2.5점 — total/digestion 점수는 소수점 허용.)
    """
    return {"hungry": 5.0, "partial": 2.5, "full": 0.0}.get(digestion)


def post_meal_exercise_score(exercise: str | None) -> int | None:
    """식후 운동 점수 (10점) — [260623]. 안함(none)0 / 10~30분(10to30)3 / 30~60분(30to60)6 / 1시간↑(60plus)10."""
    return {"none": 0, "10to30": 3, "30to60": 6, "60plus": 10}.get(exercise)


def stress_score(below_avg_ratio: float | None) -> int | None:
    """스트레스 점수 (5점 만점) — 본인 평균 이하 비율 기반"""
    if below_avg_ratio is None:
        return None
    if below_avg_ratio >= 0.75:
        return 5
    if below_avg_ratio >= 0.5:
        return 3
    return 1


def calculate_score(data: dict[str, Any]) -> dict[str, Any]:
    """v1.9 점수 산출
    data: {
        fasting_glucose, steps, steps_hourly, sleep_minutes,
        alcohol, dinner_before_7pm, no_food_after_dinner,
        stress_below_avg_ratio, meal_times
    }
    return: {total, label, breakdown, post_meal_walking_bonus, has_missing_data}
    """
    g = glucose_score(
        data.get("fasting_glucose"),
        data.get("glucose_normal_low"),
        data.get("glucose_normal_high"),
    )
    s, post_meal_bonus = steps_score(
        data.get("steps"),
        data.get("steps_hourly"),
        data.get("meal_times"),
    )
    sl = sleep_score(data.get("sleep_minutes"))
    d = dinner_before_7pm_score(data.get("dinner_before_7pm"))
    a = alcohol_score(data.get("alcohol"))
    nf = no_food_after_dinner_score(data.get("no_food_after_dinner"), data.get("no_food_after_dinner_hours"))
    st = stress_score(data.get("stress_below_avg_ratio"))
    # [260623 피드백] 신규 3항목
    dt = dinner_type_score(data.get("dinner_type"))
    dg = digestion_score(data.get("digestion"))
    pme = post_meal_exercise_score(data.get("post_meal_exercise"))

    breakdown = {
        "glucose": g,
        "steps": s,
        "steps_post_meal_bonus": post_meal_bonus,
        "sleep": sl,
        "dinner_before_7pm": d,
        "alcohol": a,
        "no_food_after_dinner": nf,
        "stress": st,
        "dinner_type": dt,
        "digestion": dg,
        "post_meal_exercise": pme,
    }

    # [사용자 피드백] 정규화(비율 환산) 제거 — 측정된 항목 점수만 단순 합산.
    # [260727 사용자 확정] 미측정 항목은 **0점**이다. 만점은 항상 100 고정.
    #   → 아래 valid_max 는 참고값(측정된 항목의 만점 합)일 뿐 분모가 아니다.
    #     이걸 UI 분모로 쓰면 "미측정은 실점 없음"이 되어 확정 정책과 반대가 된다.
    #     앱은 의도적으로 /100 을 쓰고 valid_max 를 매핑하지 않는다 — 버그가 아니다.
    all_scores = {
        "glucose": g, "steps": s, "sleep": sl,
        "dinner_before_7pm": d, "alcohol": a, "no_food_after_dinner": nf,
        "stress": st, "dinner_type": dt, "digestion": dg, "post_meal_exercise": pme,
    }
    valid = {k: v for k, v in all_scores.items() if v is not None}

    valid_max = sum(WEIGHTS_MAX[k] for k in valid)
    total = sum(valid.values())  # 단순 합산 (정규화 X)

    # ⚠️ 의료 안전성 (Codex 검토): 핵심 데이터인 공복혈당이 없으면 "좋음/만점" 표시 금지
    glucose_missing = g is None

    # 데이터 커버리지 계산 — [260623] 10개 항목 기준
    missing_domains = [k for k, v in all_scores.items() if v is None]
    n_domains = len(all_scores)
    data_coverage = len(valid) / float(n_domains)
    has_missing_data = len(valid) < n_domains

    # 핵심 데이터 누락 (혈당 + 워치 영역 중 2개 이상) → "좋음" 라벨 금지
    core_missing_count = sum([
        g is None,           # 혈당
        s is None,           # 걸음
        sl is None,          # 수면
    ])
    label = when_label(total, glucose_missing, core_missing_count)

    # 혈당 안전성 평가 (코덱스 P0)
    glucose_value = data.get("fasting_glucose")
    safety_status = glucose_safety_status(glucose_value)
    safety_message = glucose_safety_message(safety_status, glucose_value)
    # [260722 codex HIGH 1] 200 mg/dL 이상도 미션 자제하도록 원본 값 전달
    suppress_mission = should_suppress_mission(safety_status, glucose_value)

    return {
        "total": total,
        "label": label,
        "breakdown": breakdown,
        "valid_max": valid_max,  # [260706 Codex] 측정된 항목 기준 실제 만점 (부분 측정일 "N/valid_max" 표시용)
        "post_meal_walking_bonus": post_meal_bonus,
        "has_missing_data": has_missing_data,
        "glucose_missing": glucose_missing,  # UI에서 "혈당 측정 권장" 안내용
        "missing_domains": missing_domains,  # ["sleep", "stress"] 형태
        "data_coverage": round(data_coverage, 2),  # 0.0~1.0
        "core_missing_count": core_missing_count,  # 0~3
        # 코덱스 P0 — 저혈당/고혈당 안전 분리
        "glucose_safety_status": safety_status,   # critical_low/low/normal/very_high/critical_high
        "glucose_safety_message": safety_message, # 환자 안내 문구 (UI 상단 강조)
        "suppress_mission": suppress_mission,     # True면 미션 출제 안 함 (저혈당/위기 시)
    }


def when_label(total: int, glucose_missing: bool, core_missing_count: int = 0) -> str:
    """라벨 결정 — 혈당 없거나 핵심 데이터 다수 누락 시 "측정 권장" 명시.

    Args:
        total: 정규화된 점수 (0~100)
        glucose_missing: 공복혈당 미입력 여부
        core_missing_count: 혈당/걸음/수면 중 누락된 개수 (0~3)
    """
    if glucose_missing:
        return "측정 권장 (혈당 미입력)"
    # 코덱스 검토: 핵심 데이터 2개 이상 빠지면 "좋음" 라벨 금지 → 환자 오해 방지
    if core_missing_count >= 2:
        return "데이터 부족"
    if total >= 80:
        return "좋음"
    if total >= 60:
        return "보통"
    return "개선 가능"
