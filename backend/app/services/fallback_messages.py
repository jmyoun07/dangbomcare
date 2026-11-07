"""GPT 호출 실패 시 정적 fallback 메시지"""
from __future__ import annotations

import random
import re


def _deadline_to_24h(label: str | None) -> str | None:
    """'오후 7시 30분' → '19:30'. 파싱 실패 시 None."""
    if not label:
        return None
    m = re.match(r"(오전|오후)\s*(\d{1,2})시(?:\s*(\d{1,2})분)?", label.strip())
    if not m:
        return None
    ampm, hour, minute = m.group(1), int(m.group(2)), int(m.group(3) or 0)
    if ampm == "오후" and hour != 12:
        hour += 12
    elif ampm == "오전" and hour == 12:
        hour = 0
    if not (0 <= hour <= 23 and 0 <= minute <= 59):
        return None
    return f"{hour:02d}:{minute:02d}"


# [260728 R2] 저혈당 주의 안내 문장 — 음주 분기와 혈당 분기가 같은 문장을 쓰므로 한 곳에서만 정의한다.
#   (두 곳에 문자열이 복사돼 있으면 중복 노출/문구 불일치가 다시 생긴다.)
_HYPO_CAUTION = "어지럽거나 식은땀이 나면 바로 당분을 보충해주세요."


def _as_glucose_number(value) -> float | None:
    """혈당/범위 값 정규화 — int/float 만 통과(bool 은 int 서브클래스라 명시 배제). 그 외는 None.

    [260729 P1-A] 아래 두 판정 함수는 원래 fallback(=GPT 실패) 경로에서만 쓰여서 호출부가
    타입을 보증했다. 이제 GPT **주 경로**(ai_service._strip_hypo_caution)도 같은 함수를 쓰므로
    payload 원본 값(문자열·None·bool)이 그대로 들어올 수 있다 → 비교 전에 여기서 한 번 거른다.
    정상 int 입력의 동작은 종전과 완전히 동일하다.
    """
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    return float(value)


def _is_glucose_actually_low(
    glucose: int | None,
    normal_low: int | None,
    normal_high: int | None,
) -> bool:
    """오늘 공복혈당이 '실제로 낮게' 나왔는지.

    ★ [260729 P1-A] 이 함수가 '오늘 혈당이 실제로 낮은가'의 **단일 소스**다.
      · fallback(GPT 실패) 경로 — 아래 get_fallback_daily
      · GPT 주 경로 — ai_service._strip_hypo_caution (저혈당 주의 안내 코드 가드)
      두 경로가 같은 규칙을 쓰도록 여기 한 곳만 고치면 되게 유지할 것.
      (앱 ScoreDetailScreen.describeAlcohol 은 별도 구현 — 규칙 변경 시 함께 봐야 한다)

    [260728 R2 당봄 피드백] 저혈당 주의 안내를 음주만으로 띄우지 않기 위한 게이트.
      · 개인 평소 범위가 있으면 → glucose < normal_low 일 때만 True
      · 개인 범위가 없으면      → glucose < 100 일 때만 True
      · glucose 가 없으면(None) → 항상 False (추측 금지)
    """
    g = _as_glucose_number(glucose)
    if g is None:
        return False
    lo = _as_glucose_number(normal_low)
    hi = _as_glucose_number(normal_high)
    if lo is not None and hi is not None and lo <= hi:
        return g < lo
    return g < 100


def _is_glucose_actually_high(
    glucose: int | None,
    normal_low: int | None,
    normal_high: int | None,
) -> bool:
    """오늘 공복혈당이 '실제로 높게' 나왔는지. (_is_glucose_actually_low 의 반대쪽)

    [260728 R3 당봄 피드백 ③] 음주 다음날 기본 경향은 '낮게'인데 실제로 높게 나온 날은
    안주·추가 음식 쪽을 짚어주는 게 맞다. 그 분기 판정에만 쓴다.
      · 개인 평소 범위가 있으면 → glucose > normal_high
      · 개인 범위가 없으면      → glucose > 130 (당뇨 목표 상한, 아래 절대 기준과 동일)
      · glucose 가 없으면(None) → 항상 False (추측 금지)
    """
    g = _as_glucose_number(glucose)
    if g is None:
        return False
    lo = _as_glucose_number(normal_low)
    hi = _as_glucose_number(normal_high)
    if lo is not None and hi is not None and lo <= hi:
        return g > hi
    return g > 130


def get_fallback_daily(
    score: int,
    glucose: int | None = None,
    alcohol: bool | None = None,
    dinner_before_7pm: bool | None = None,
    no_food_after_dinner: bool | None = None,
    sleep_hours: float | None = None,
    change_from_yesterday: int = 0,
    avg_sleep_hours: float | None = None,   # [260705] 최근 7일 개인 평균 수면 — '부족' 단정 방지 기준
    # [260726] 사용자가 설정한 저녁 마감 시각 라벨(예 "오후 7시 30분").
    #   당봄 버그 제보: 7시 30분으로 설정했는데 피드백이 '8시' 같은 임의 시각을 말함.
    #   GPT 경로는 프롬프트에서 dinner_deadline 을 강제하고, 이 fallback 경로도 동일 기준을 쓴다.
    #   None 이면 시각을 아예 말하지 않는다(임의 시각 생성 금지).
    dinner_deadline: str | None = None,
    # [260728 당봄 피드백 2] 이 환자가 직접 설정한 '평소 공복혈당 범위'(mg/dL).
    #   둘 다 주어졌을 때만 '미만/안/초과'를 구분해서 말한다.
    #   기존 호출부가 안 깨지도록 기본값 None — 없으면 종전대로 절대 기준(100~130)만 본다.
    glucose_normal_low: int | None = None,
    glucose_normal_high: int | None = None,
) -> dict:
    """GPT 호출 실패 시 사용. 점수/혈당/회고/수면 기반 정적 템플릿."""

    # 메시지 선택 — [260705 피드백] '보통 수준'·'회복 방향' 표현 금지 → 목표 근접 프레이밍
    if score >= 80:
        message = "오늘은 잘 관리하고 계세요! 이대로 유지해주세요."
    elif score >= 60:
        message = "목표에 가까워지고 있어요. 조금만 더 신경 쓰면 목표 범위에 들어갈 수 있어요."
    else:
        message = "오늘 데이터가 평소보다 낮아요. 전날 컨디션을 돌아봐주세요."

    # [260728 R2 당봄 피드백] 저혈당 주의 안내 게이트 — 음주만으로는 안 띄운다.
    #   어제(260727) 음주 다음날 '공복혈당 낮아지는 경향'을 넣을 때 주의 안내도 같이 붙였는데,
    #   음주=True 면 실제 혈당이 정상이어도 매번 떠서 과했다.
    #   → "실제로 낮게 나온 날"만 노출. 방향 설명("낮게 나오는 경향")은 수치와 무관하게 항상 유지.
    _glucose_is_low = _is_glucose_actually_low(glucose, glucose_normal_low, glucose_normal_high)
    _glucose_is_high = _is_glucose_actually_high(glucose, glucose_normal_low, glucose_normal_high)
    # [260729 통합검증 P1] 오늘 실측이 '있는지'. 있으면 방향 예측(높게/낮게 나올 수 있어요)을 하지 않는다.
    #   뒤쪽 혈당 분기가 실측 방향을 단언하므로, 예측과 단언이 어긋나면 한 메시지 안에서 앞뒤가 모순된다.
    #   기존 코드는 [미만] 갈래만 막아서 [안] 갈래에 구멍이 있었다(실측:
    #     "…높게 나올 수 있어요. … 134 mg/dL로 평소 범위(130~150) 안에서 잘 유지되고 있어요.").
    _glucose_measured = _as_glucose_number(glucose) is not None
    _hypo_caution_shown = False  # 음주 분기와 혈당 분기에서 같은 문장이 두 번 나오는 것 방지

    # ── 음주 방향 설명 — 우선순위 체인 '밖' ────────────────────────────────
    # ⚠️ [260729 통합검증 P1] 이전에는 이 블록이 `elif alcohol is True:` 로 체인 안에 있었다.
    #   체인은 머리말을 **하나만** 고르므로 sleep_hours < 7 인 날에는 음주 설명이 통째로 사라졌는데,
    #   influencing_factors 에는 "alcohol" 이 그대로 남아 앱이 음주 개선 칩을 띄웠다
    #   — 본문에 음주가 한 글자도 없는 채로(실측). ai_prompts.py L236 은 이 방향 설명이
    #   "세 갈래 모두에서 항상 먼저 나온다"로 계약을 고정했으므로 GPT 경로와도 어긋났다.
    #   같은 형태의 버그를 저혈당 주의 안내에서 이미 한 번 고쳤다(아래 L236-243 주석) —
    #   그때 방향 설명만 빼내지 않은 것이 이 구멍이다.
    _alcohol_block = ""
    if alcohol is True:
        # [260722 codex] 금지어 "야식" 제거 — 시니어 친화 표현("저녁 후 추가 음식")으로 교체
        # [260728 당봄 피드백 3] 방향 명확화. 의학적 기본 경향은 **낮아지는 쪽**이다
        #   (간이 알코올을 처리하느라 밤사이 혈당을 충분히 만들어내지 못함). 안주를 많이 든 경우가 예외.
        #   시니어 대상이라 '당신생·글리코겐' 같은 용어는 쓰지 않는다.
        _alcohol_lead = (
            "전날 🍷 음주가 있었어요. 술을 드신 다음 날은 간이 알코올을 처리하느라 밤사이 혈당을 "
            "충분히 만들어내지 못해서, 아침 공복혈당이 평소보다 낮게 나오는 경향이 있어요. "
        )
        if _glucose_is_low:
            # 경향대로 낮게 나온 날 — 예외 설명은 생략하고 주의 안내만
            _alcohol_block = _alcohol_lead + f"{_HYPO_CAUTION} "
            _hypo_caution_shown = True
        elif _glucose_is_high:
            # 경향과 반대로 높게 나온 날 — 함께 드신 안주·추가 음식 쪽을 짚어준다
            # ⚠️ 안주 섭취량은 payload 에 없다 → 원인을 단정하지 않고 관찰형("~수 있어요")으로만 짚는다.
            _alcohol_block = _alcohol_lead + (
                "오늘처럼 반대로 높게 나온 날은 함께 드신 안주나 식사 후 추가로 드신 음식의 "
                "탄수화물이 영향을 줬을 수 있어요. "
            )
        elif no_food_after_dinner is False:
            # [260728 R4] 범위 안이거나 기록이 없는 날 — '양 추정'(많이 드셨다면) 금지.
            #   실제로 값이 있는 근거(식후 추가 섭취 O)가 있을 때만 '존재' 기준으로 한 문장 덧붙인다.
            _alcohol_block = _alcohol_lead + (
                "함께 드신 안주나 식사 후 추가로 드신 음식의 탄수화물이 있으면 "
                "반대로 높게 나오는 경우도 있어요. "
            )
        else:
            _alcohol_block = _alcohol_lead

    # 우선순위: 수면 > 이른 저녁 > 식후 추가 음식  (음주는 위에서 체인 밖으로 분리)
    # [260705 피드백] 7시간에 살짝 못 미친다고 '부족' 단정 금지 — 개인 평균(최근 7일) 대비로 판단.
    if sleep_hours is not None and sleep_hours < 7.0:
        if avg_sleep_hours is not None and avg_sleep_hours > 0 and sleep_hours >= avg_sleep_hours + 0.5:
            message = f"😴 평소(평균 {avg_sleep_hours:.1f}시간)보다 푹 주무셨어요({sleep_hours:.1f}시간). " + message
        # [260728 R4 최종 교차검증] '오늘 혈당이 높게 나올 수 있다'는 예측은 오늘 실측이
        #   **이미 [미만](평소보다 낮음)** 인 날에는 쓰지 않는다.
        #   아래 혈당 분기가 뒤에 "평소 범위(120~140)보다 낮게 나왔어요"를 붙이므로,
        #   그대로 두면 한 메시지 안에서 "높게 나올 수 있어요 … 낮게 나왔어요"로 앞뒤가 어긋난다
        #   (시니어 대상 · 의료 문구이므로 방향이 상충하면 안 된다).
        #   → [260729 통합검증] 판정 기준을 `_glucose_is_low` 에서 `_glucose_measured` 로 넓힌다.
        #     [안] 갈래도 뒤에서 "범위(130~150) 안에서 잘 유지되고 있어요"라고 **단언**하므로
        #     앞에서 "높게 나올 수 있어요"라고 예측하면 똑같이 앞뒤가 어긋난다(실측).
        #     방향 예측은 오늘 실측이 아직 없을 때만 의미가 있다.
        elif sleep_hours >= 6.0:
            message = (
                # [260728] 권장 시간 표기 통일 — 다른 모든 경로(프롬프트·앱·리포트)는 7~9시간이다.
                #   여기만 '7~8시간'이라 같은 상황에서 화면마다 다른 권장 시간을 말하고 있었다.
                f"😴 수면이 권장(7~9시간)보다 조금 짧았어요({sleep_hours:.1f}시간). "
                + ("수면이 짧은 날은 다음날 혈당이 흔들릴 수 있어요. " if _glucose_measured
                   else "오늘 공복혈당이 평소보다 살짝 높게 나올 수 있어요. ")
                + message
            )
        elif _glucose_measured:
            message = (
                f"😴 수면이 부족했어요({sleep_hours:.1f}시간). "
                "수면이 짧은 날은 다음날 혈당이 흔들릴 수 있으니 오늘은 조금 더 채워보세요. " + message
            )
        else:
            message = f"😴 수면 부족({sleep_hours:.1f}시간)으로 오늘 공복혈당이 평소보다 높게 측정될 수 있어요. " + message
    elif dinner_before_7pm is False:
        # [260726] 하드코딩 '7시' 제거 — 사용자가 설정한 마감 시각만 사용, 없으면 시각 언급 자체를 생략.
        _late = (
            f"🍚 전날 저녁 식사가 {dinner_deadline} 이후였어요. 오늘은 좀 더 일찍 마쳐보세요. "
            if dinner_deadline
            else "🍚 전날 저녁 식사가 설정하신 마감 시각보다 늦었어요. 오늘은 좀 더 일찍 마쳐보세요. "
        )
        message = _late + message
    elif no_food_after_dinner is False:
        # [P0 v1.8 용어] "야식"은 시니어 환자에게 야간금식과 혼동 → "추가 음식 섭취"
        message = "🍜 전날 식사 후 추가로 드신 음식이 있었어요. " + message

    # 음주 방향 설명은 어떤 머리말이 선택됐든 **항상 맨 앞**에 온다(ai_prompts.py L236 계약).
    message = _alcohol_block + message

    # [260728 당봄 피드백 2] 공복혈당은 **개인 평소 범위** 기준으로 미만/안/초과를 구분해서 말한다.
    #   이전엔 개인 범위를 아예 받지 않아 절대 기준(100~130)만 봤다. 그래서
    #     · 평소 120~140인 분의 99 → 아무 말도 못 함(정작 '평소보다 낮다'가 핵심 정보)
    #     · 평소 130~150인 분의 135 → "목표 범위보다 높네요"만 반복
    #   범위가 안 넘어오면 종전 동작(절대 기준) 그대로 — 기존 호출부 호환.
    if glucose is not None:
        _lo, _hi = glucose_normal_low, glucose_normal_high
        if _lo is not None and _hi is not None and _lo <= _hi:
            if glucose < _lo:
                message += f" 오늘 공복혈당은 {glucose} mg/dL로 평소 범위({_lo}~{_hi})보다 낮게 나왔어요."
            elif glucose > _hi:
                message += f" 오늘 공복혈당은 {glucose} mg/dL로 평소 범위({_lo}~{_hi})보다 높게 나왔어요."
            else:
                message += f" 오늘 공복혈당은 {glucose} mg/dL로 평소 범위({_lo}~{_hi}) 안에서 잘 유지되고 있어요."
        elif glucose > 130:
            message += " 공복혈당이 당뇨 목표 범위(100~130)보다 높네요."

    # [260728 R2] 저혈당 주의 안내를 붙일지 최종 판정하는 곳은 여기 한 군데다.
    #   위 음주 분기에서 이미 붙었으면(_hypo_caution_shown=True) 다시 붙이지 않는다(중복 방지).
    #
    #   ⚠️ 분기 체인 '밖에서' 다시 판정해야 하는 이유 두 가지 —
    #   (1) 위 '수면 > 음주 > 식사' 우선순위 체인은 머리말을 **하나만** 고른다.
    #       그래서 sleep_hours < 7 이면 음주 분기가 아예 실행되지 않아,
    #       음주O + 실제로 낮은 혈당(예: 99 / 평소 120~140)인데도 주의 안내가 빠졌다.
    #       프롬프트·앱 층은 수면과 무관하게 안내하므로 같은 상황에서 화면마다 안내가 달라졌다.
    #   (2) glucose < 70 안전 안내가 '개인 범위가 설정된 경우'에만 붙어 있었다.
    #       범위 미설정 사용자는 65 mg/dL 에도 아무 안내가 없었다.
    #       저혈당 안전망은 개인 범위·음주 여부와 **무관하게** 항상 떠야 한다.
    #
    #   게이트: glucose < 70(안전망, 무조건) 또는 (음주O + 실제로 낮게 나옴).
    #   glucose 가 None 이면 어느 쪽도 성립하지 않는다 — 추측해서 안내하지 않는다.
    if glucose is not None and not _hypo_caution_shown:
        if glucose < 70 or (alcohol is True and _glucose_is_low):
            message += f" {_HYPO_CAUTION}"
            _hypo_caution_shown = True

    # 격려
    encouragement = None
    if change_from_yesterday >= 5:
        encouragement = f"전날보다 {change_from_yesterday:g}점 올랐어요. 훌륭해요!"

    # 미션
    missions = [
        {
            "type": "steps",
            "text": "오늘 점심 식후 10분 걷기",
            "target": {"post_meal_walking_minutes": 10, "after_meal": "lunch"},
            "tip": "💡 식후 걷기는 혈당 스파이크 억제에 효과적",
        },
        {
            "type": "dinner_timing",
            # [260726] 하드코딩 '7시' 제거 — 사용자 설정 마감 시각 사용, 없으면 시각 없이 표현.
            "text": (
                f"오늘 저녁은 {dinner_deadline} 전에 마치기"
                if dinner_deadline
                else "오늘 저녁은 설정하신 마감 시각 전에 마치기"
            ),
            "target": (
                {"deadline": _deadline_to_24h(dinner_deadline)}
                if _deadline_to_24h(dinner_deadline)
                else {}
            ),
            "tip": "💡 저녁식사 마감 = 취침 4시간 전",
        },
        {
            "type": "sleep",
            "text": "오늘 7시간 수면 목표",
            "target": {"target_hours": 7.0, "bedtime_by": "23:00"},
            "tip": "💡 23시 전 잠자리",
        },
    ]
    mission = random.choice(missions)

    factors: list[str] = []
    if sleep_hours is not None and sleep_hours < 7.0:
        factors.append("sleep")
    if alcohol is True:
        factors.append("alcohol")
    if dinner_before_7pm is False:
        factors.append("dinner_before_7pm")
    if no_food_after_dinner is False:
        factors.append("no_food_after_dinner")

    return {
        "message": message,
        "influencing_factors": factors,
        "encouragement": encouragement,
        "mission": mission,
        "_fallback": True,
    }


def get_mission_complete_message(mission_type: str) -> str:
    """미션 완료 시 격려 메시지"""
    msgs = {
        "steps": [
            "🎉 걸음 미션 달성! 오늘 정말 잘 걸으셨어요.",
            "🔥 식후 걷기 성공! 혈당 스파이크를 잘 막아내셨어요.",
        ],
        "sleep": [
            "😴 수면 미션 달성! 체력 회복 잠이 충분했어요.",
        ],
        "alcohol": [
            # [피드백 260714 정리 · AI1] 시니어 용어 통일 — "깊은 수면" → "체력 회복 잠"
            "🍵 음주 절제 성공! 혈당과 체력 회복 잠에 큰 도움이 됩니다.",
        ],
        "dinner_timing": [
            "🍚 이른 저녁 식사 마치기 성공!",  # [260726] 하드코딩 '7시' 제거
        ],
    }
    return random.choice(msgs.get(mission_type, ["🎉 미션 완료!"]))


def get_streak_message(weekly_days: int) -> str | None:
    if weekly_days >= 7:
        return "🏆 이번 주 완벽 달성! 정말 대단하세요."
    if weekly_days >= 5:
        return f"🔥 이번 주 {weekly_days}/7일 달성 : 잘하고 계세요!"
    if weekly_days >= 3:
        return f"⭐ 이번 주 {weekly_days}/7일 : 절반 넘었어요!"
    return None
