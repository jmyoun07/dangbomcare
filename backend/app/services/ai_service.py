"""OpenAI GPT 호출 서비스 (v1.9)"""
from __future__ import annotations

import json
import logging
import re
from typing import Any

from openai import AsyncOpenAI

from app.config import settings
from app.services import ai_prompts, fallback_messages

logger = logging.getLogger(__name__)


_client: AsyncOpenAI | None = None


def get_client() -> AsyncOpenAI:
    global _client
    if _client is None:
        _client = AsyncOpenAI(api_key=settings.openai_api_key)
    return _client


# 모델별 비용 (USD/1M tokens, 2026-05 기준)
PRICING = {
    "gpt-4o-mini": {"input": 0.150, "output": 0.600},
    "gpt-4o": {"input": 2.500, "output": 10.000},
}
USD_TO_KRW = 1380


# [260726] influencing_factors 화이트리스트.
#   근거 = 아래 3곳의 교집합(직접 확인):
#     1) 프롬프트 ai_prompts.py L326-327 "influencing_factors 가능 값 (우선순위 순)"
#     2) supabase_ddl_v1.9.sql — ai_feedbacks.influencing_factors 는 JSONB이고 CHECK 제약이 없음
#        (mission_type 만 CHECK 있음) → DB는 아무 값이나 받아버리므로 여기가 유일한 방어선
#     3) 앱 AiFeedbackScreen.kt friendlyFactorText() 의 정확 키 매칭 분기(key == "...")
#   → 세 곳 모두에 존재하는 값은 아래 9개. 앱에만 있는 contains 매칭 키
#     (sleep_quality / sleep_long / fasting_glucose_high / missing_data 등)는
#     프롬프트가 허용하지 않는 값이라 교집합에서 제외했다.
#   fallback_messages.get_fallback_daily()가 만드는 factors(sleep/alcohol/
#   dinner_before_7pm/no_food_after_dinner)도 전부 이 집합의 부분집합이라 안전.
#   ⚠️ 앱의 else 분기는 원문(raw)을 그대로 노출한다 → 미지 값이 오면 시니어 화면에
#     영어 원문("carb_load" 등)이 그대로 찍힌다. 그래서 서버에서 잘라낸다.
VALID_INFLUENCING_FACTORS = {
    "alcohol",
    "sleep",
    "dinner_before_7pm",
    "no_food_after_dinner",
    "digestion",
    "dinner_type",
    "post_meal_exercise",
    "stress",
    "steps",
}


# ----------------------------------------------------------------------------
# [260728][당봄 피드백 2] '평소 범위 안' 오표현 방어층
# ----------------------------------------------------------------------------
#   실제 버그: 평소 범위가 120~140 인 환자의 오늘 공복혈당 99 에 대해 GPT가
#     "오늘 아침 공복혈당은 99 mg/dL로 평소 범위(120~140) 안에서 잘 유지되고 계세요."
#   라고 썼다. 99는 120 미만이므로 '범위 안'이 아니라 '범위 미만'이다 — 명백한 사실 오류.
#   원인은 ai_prompts.py 에 '범위 미만(낮은 날)' 예시가 없어서(규칙만 존재) 모델이 가장 가까운
#   '범위 안' 예시를 모방하는 것. 프롬프트를 고쳐도 LLM 출력은 100% 보장이 안 되므로 여기서 한 번 더 잡는다.
#
#   ⚠️ 과잉 치환 사고 이력: 수면 가드 정규식이 정상 문장("체력 회복 잠이 42분(총 6시간 중)")을
#     파괴해 원래 버그보다 나쁜 의료 오정보를 만든 적이 있다. 그래서 아래 조건이 **전부** 맞을 때만 손댄다.
#     (1) today_glucose 와 개인 범위(low/high)가 둘 다 실제 값으로 있다
#     (2) today_glucose 가 그 범위 '밖'이다 — 범위 안이면 문장이 맞으므로 아예 동작하지 않는다
#     (3) 그 문장(직전 문장부호 이후 ~ 매치 지점)에 "혈당" 이 들어있다 — 혈압·체중 등 타 지표 문장 보호
#     (4) 문장에 범위가 "(A~B)"로 명시돼 있으면 A,B 가 이 환자 범위와 **정확히 일치**해야 한다.
#         → "당뇨 목표 범위(100~130) 안에" 같은 의료진 목표치 문장은 건드리지 않는다.
#         숫자 명시가 없으면 "평소 범위" 처럼 '평소' 수식어가 붙어 있어야 한다(무슨 범위인지 확신 가능할 때만).
#     (5) "안에 / 안에서 / 내에 / 안입니다 …" 처럼 **현재 상태를 단언**하는 형태만 매칭한다.
#         "범위 안으로 들어오도록 해봐요" 같은 목표 문장은 조사('안으로')가 달라 매칭되지 않는다.
#     (6) [260728 통합검증 추가] (5)의 조사 검사만으로는 부족하다. 조사가 '안에'인 **목표 문장**
#         ("오늘은 공복혈당이 평소 범위(130~150) 안에 들어오도록 저녁을 일찍 드셔보세요")도 매칭돼서
#         "…평소 범위(130~150)보다 높게 나왔어요."로 통째로 갈아엎히는 게 확인됐다.
#         → 제안·희망을 나타내는 어미(도록/보세요/봐요/해주세요/하세요/합시다/노력)가 매치 구간에
#           들어 있으면 그건 '현재 상태 단언'이 아니라 '오늘의 목표'이므로 건드리지 않는다.
#           (못 잡고 지나가는 건 안전, 정상 문장을 파괴하는 건 위험 — 수면 가드에서 배운 것과 같은 원칙)
#   교정은 단어 치환이 아니라 **매치 지점부터 문장 끝까지를 올바른 절로 교체**한다.
#   단어만 바꾸면 "범위(120~140)보다 낮게 잘 유지되고 계세요" 같은 비문이 나온다.
#
#   [260729 P1-B] 실측으로 확인된 누락 패턴 보강 —
#     · 전각 물결 "(120～140)"(U+FF5E)·웨이브대시(U+301C)·전각 하이픈 등이 dash 클래스에 없어
#       **정규식이 매칭조차 안 됐다**(괄호가 열리는 순간 뒤의 '안/내' 요구를 못 맞춰 통째로 빠져나감).
#       → 아래 _RANGE_DASH 로 물결·하이픈 변형을 모두 수용한다.
#     · "(120~140 mg/dL)" 처럼 괄호 안에 단위가 붙는 표기도 같은 이유로 통째로 빠져나갔다 → 선택 허용.
_RANGE_DASH = r"~∼〜～˜\-‐‑‒–—―−－"
_RANGE_CLAIM_RE = re.compile(
    r"(평소\s*)?범위\s*"                                       # g1: '평소' 수식어 유무
    r"(?:\(\s*(\d{2,3})\s*[" + _RANGE_DASH + r"]\s*(\d{2,3})\s*"
    r"(?:mg\s*/\s*d[lL]\s*)?\)\s*)?"                            # g2,g3: 명시된 범위 (선택)
    r"(?:안|내)\s*(?:에서|에|입니다|이에요|예요|이네요|이고|이며|이라)"   # 현재 상태 단언형만
    r"[^.!?\n]*[.!?]?"                                          # 문장 끝까지
)

# 제안·목표 문장 표지 — 이게 보이면 상태 단언이 아니므로 교정 대상에서 제외한다.
_RANGE_GOAL_HINT_RE = re.compile(
    r"도록|보세요|봐요|해주세요|하세요|합시다|노력|목표로|들어오|들어가|맞춰"
)

# 이 문장이 '혈당' 문장이라는 표지. (3) 조건에 쓴다.
#   [260728 R3] 이전엔 "혈당" 리터럴만 봤다. 그래서 격려 문구처럼 지표명을 생략한
#   "99 mg/dL로 평소 범위(120~140) 안에서 잘 유지되고 계세요!" 가 무검열 통과했다(실측 확인).
#   → mg/dL 은 혈당 전용 단위다(혈압 mmHg·체중 kg과 겹치지 않음) → 표지로 안전하게 추가 가능.
_GLUCOSE_MARK_RE = re.compile(r"혈당|mg\s*/\s*d[lL]", re.IGNORECASE)

# [260729 통합검증] 최종 message 에 오늘 수치가 남아 있는지 확인할 때만 쓰는 단위 표지.
#   (숫자까지 요구하지 않는다 — "138 mg/dL" / "138mg/dl" / "138 mg / dL" 모두 통과시킨다)
_MGDL_RE = re.compile(r"mg\s*/\s*d[lL]", re.IGNORECASE)

# [260729 P1-B] (3) 혈당 표지 면제 경로에서만 쓰는 '타 지표' 거부 표지.
#   면제 근거는 "괄호 숫자가 이 환자 범위와 정확히 일치한다"는 것뿐인데, 우연히 같은 두 수를
#   쓰는 다른 지표 문장(예: 혈당 범위가 120~140 인 환자에게 "혈압이 평소 범위(120~140) 안이에요")이
#   있으면 그 문장에 mg/dL 절이 꽂힌다. 면제 경로에 한해 타 지표 단어가 보이면 손대지 않는다.
#   ※ 혈당 표지가 있는 문장에는 적용하지 않는다 — 프롬프트 정답 예시가
#     "총 수면시간이 5시간 20분으로 …, 오늘 아침 공복혈당은 138 mg/dL로 평소 범위(130~150) 안에서 …"
#     처럼 수면과 혈당을 한 문장에 담기 때문이다(정상 교정 대상을 잃으면 안 된다).
_OTHER_METRIC_RE = re.compile(
    r"혈압|맥박|심박|체중|몸무게|체온|콜레스테롤|체지방|수면|잠|걸음|걸으|스트레스"
)


def _fix_range_claim(
    text: str,
    today_glucose: int | float | None,
    low: int | None,
    high: int | None,
) -> str:
    """공복혈당이 실제로는 개인 범위 밖인데 '범위 안'이라 말한 문장을 교정한다.

    조건이 하나라도 안 맞으면 원문을 그대로 돌려준다(못 잡고 지나가는 건 안전, 잘못 잡는 건 위험).
    """
    if not text:
        return text
    # (1) 입력 유효성 — bool 은 int 의 서브클래스라 명시적으로 배제
    if isinstance(today_glucose, bool) or not isinstance(today_glucose, (int, float)):
        return text
    if isinstance(low, bool) or isinstance(high, bool):
        return text
    if not isinstance(low, int) or not isinstance(high, int) or low > high:
        return text
    # (2) 범위 안이면 '범위 안' 서술이 맞다 → 손대지 않는다
    if low <= today_glucose <= high:
        return text

    if today_glucose < low:
        replacement = f"평소 범위({low}~{high})보다 낮게 유지되고 있어요."
    else:
        replacement = f"평소 범위({low}~{high})보다 높게 나왔어요."

    out: list[str] = []
    pos = 0
    for m in _RANGE_CLAIM_RE.finditer(text):
        # 이 매치가 속한 문장의 시작 = 직전 문장부호/줄바꿈 다음
        sent_start = max(
            text.rfind(".", 0, m.start()),
            text.rfind("!", 0, m.start()),
            text.rfind("?", 0, m.start()),
            text.rfind("\n", 0, m.start()),
        ) + 1
        head = text[sent_start:m.start()]
        # (6) 목표·제안 문장은 상태 단언이 아니다 → 손대지 않는다
        #     ⚠️ 이 보호는 절대 약화시키지 않는다. "평소 범위(130~150) 안에 들어오도록 해보세요" 는 불변.
        if _RANGE_GOAL_HINT_RE.search(m.group(0)):
            continue
        # (4) 범위 동일성 확인
        a, b = m.group(2), m.group(3)
        _range_matches = False
        if a is not None and b is not None:
            if int(a) != low or int(b) != high:
                continue          # 다른 범위(의료진 목표치 등) → 건드리지 않는다
            _range_matches = True
        elif not m.group(1):
            continue              # 숫자도 없고 '평소'도 없으면 무슨 범위인지 확신 불가
        # (3) 혈당 문장만 — 혈압·체중 등 타 지표 문장 보호
        #   [260729 P1-B] 단, **괄호 안 숫자가 이 환자 범위와 정확히 일치**하면 그 자체로
        #   '이 문장은 이 환자의 공복혈당 범위를 말하고 있다'가 증명되므로 표지 검사를 면제한다.
        #   놓치던 실제 패턴: 프롬프트의 [중복 문장 금지] 규칙이 모델을 "앞 문장에서 수치 인용 →
        #   뒷문장은 지표명 생략"으로 몰아서, 교정이 필요한 문장에 '혈당'·'mg/dL'이 아예 없다.
        #     예) "오늘 아침 공복혈당은 99 mg/dL이었어요. … 평소 범위(120~140) 안에서 잘 유지되고 계세요."
        #   면제 경로에서는 대신 타 지표 표지를 거부 조건으로 둔다(_OTHER_METRIC_RE 주석 참조).
        if not _GLUCOSE_MARK_RE.search(head) and not _GLUCOSE_MARK_RE.search(m.group(0)):
            if not _range_matches:
                continue
            if _OTHER_METRIC_RE.search(head) or _OTHER_METRIC_RE.search(m.group(0)):
                continue
        out.append(text[pos:m.start()])
        out.append(replacement)
        pos = m.end()

    if not out:
        return text
    out.append(text[pos:])
    fixed = re.sub(r"[ \t]{2,}", " ", "".join(out)).strip()
    logger.warning(f"Glucose range claim corrected: {text!r} -> {fixed!r}")
    return fixed


# ----------------------------------------------------------------------------
# [260729 P1-A] 저혈당 주의 안내(행동 지시) 코드 방어층 — GPT **주 경로**
# ----------------------------------------------------------------------------
#   260728 신설 3규칙 중 나머지 둘(수면 오라벨·범위 오표현)은 "프롬프트를 고쳐도 LLM 출력은
#   100% 보장이 안 되므로 여기서 한 번 더 잡는다"는 근거로 코드 가드를 붙였는데, 저혈당 게이트만
#   프롬프트 단독이었다. 코드 게이트는 fallback(=GPT 실패) 경로와 앱 점수상세에만 있어서
#   **사용자가 실제로 보는 주 경로가 무방비**였다. 셋 중 유일하게 실패 결과가 라벨 오류가 아니라
#   **행동 지시(당분 섭취)** 라 위험도가 가장 높다.
#
#   재현: today_glucose=162, 평소 범위 120~140, alcohol=true → [초과] → 프롬프트가 금지한 구간인데
#         "…어지럽거나 식은땀이 나면 바로 드실 것을 챙겨 주세요." 가 무변경 통과.
#
#   게이트 판정은 fallback_messages._is_glucose_actually_low 를 **재사용**한다(단일 소스).
#     · 게이트 통과(실제로 낮은 날) → 정당한 안내이므로 손대지 않는다.
#     · today_glucose < 70          → score_service.apply_safety_override 가 메시지 전체를
#                                      안전 안내로 대체한다 → 여기서 건드리지 않는다.
#     · today_glucose 가 값이 없음  → 판단 근거가 없다 → 손대지 않는다(못 잡고 지나가는 건 안전).
#
#   ⚠️ 과잉 치환 사고 이력(수면 가드가 정상 문장을 파괴) 때문에 **문장 단위 제거**만 하고,
#     제거 조건은 한 문장 안에 아래가 **둘 다** 있을 때로 좁힌다.
#       ⓐ 저혈당 증상 표지(어지럼·식은땀·손떨림·'저혈당')
#       ⓑ 당분 섭취 **지시** 표지(당분/사탕/주스/드실 것 … + 보충·챙기·드세요)
#     한쪽만으로는 자르지 않는다. 예: "당분이 많은 음식은 줄여 드세요"(ⓑ만) 는 정상 문장이고,
#     "어지러우시면 무리하지 마세요"(ⓐ만) 도 당분 섭취 지시가 아니다.
#   ⓒ 만 예외적으로 단독 조건 — "혈당이 너무 떨어지지 않도록 주의하세요" 류는 증상·당분 없이도
#     저혈당 주의 안내 그 자체다. '혈당' + '떨어지지/낮아지지 않' + '주의/조심' 셋을 한 문장에서
#     모두 요구해 오검출을 막는다.
_HYPO_HINT_RE = re.compile(r"어지|식은땀|저혈당|당분|사탕|주스|떨어지지|낮아지지|손\s*떨")

_HYPO_SYMPTOM_RE = re.compile(
    r"어지럽|어지러|식은땀|손\s*떨림|손이\s*떨|기운이\s*(?:없|빠)|힘이\s*빠|저혈당"
)
# 앞쪽 명사(당분/사탕/…)와 뒤쪽 '섭취 지시' 동사가 같은 문장에서 가까이 붙어 있을 때만.
#   ⚠️ [260729 통합검증 P2] '드시-/먹으-' 뒤에 '-면'이 오면 **가정절**이지 지시가 아니다.
#     실측 오검출: "당분이 많은 음식을 드시면 오후에 어지러울 수 있어요."
#       → 당분 회피를 권하는 정상 문장인데 ⓐ(어지러)+ⓑ(당분…드시) 동시 매칭으로 삭제됐다.
#     → 조건형 어미를 배제한다. '드시고/드시는 게' 처럼 지시로 읽히는 활용은 그대로 잡힌다.
_HYPO_INTAKE_RE = re.compile(
    r"(?:당분|사탕|주스|초콜릿|꿀|단\s*것|드실\s*것|먹을\s*것)"
    r"[^.!?\n]{0,20}?"
    r"(?:보충|챙기|챙겨|섭취하|섭취해|드세|드시(?!면)|드셔|드실|먹어|먹으(?!면)|준비)"
)
_HYPO_DROP_WARN_RE = re.compile(
    r"혈당[^.!?\n]{0,25}(?:떨어지지|낮아지지)\s*않[^.!?\n]{0,20}(?:주의|조심)"
)

# 문장 스캔 — 종결부호 앞까지 + 종결부호. 줄바꿈은 매치에서 빠져 원문 그대로 보존된다.
_SENTENCE_SCAN_RE = re.compile(r"[^.!?\n]+[.!?]*")


def _strip_hypo_caution(
    text: str,
    today_glucose: int | float | None,
    low: int | None,
    high: int | None,
) -> str:
    """게이트를 통과하지 못한 날의 저혈당 주의 안내 문장만 제거한다.

    조건이 하나라도 안 맞으면 원문을 그대로 돌려준다.
    """
    if not isinstance(text, str) or not text:
        return text
    if not _HYPO_HINT_RE.search(text):
        return text
    _g = fallback_messages._as_glucose_number(today_glucose)
    if _g is None or _g < 70:
        # 값이 없으면 판단 불가 / 70 미만은 apply_safety_override 관할 → 건드리지 않는다
        return text
    if fallback_messages._is_glucose_actually_low(today_glucose, low, high):
        return text                      # 게이트 통과 — 정당한 안내다

    def _repl(_m):
        _s = _m.group(0)
        if _HYPO_SYMPTOM_RE.search(_s) and _HYPO_INTAKE_RE.search(_s):
            return ""
        if _HYPO_DROP_WARN_RE.search(_s):
            return ""
        return _s

    _new = _SENTENCE_SCAN_RE.sub(_repl, text)
    if _new == text:
        return text
    fixed = re.sub(r"[ \t]{2,}", " ", _new).strip()
    logger.warning(
        f"Hypoglycemia caution removed (glucose={today_glucose}, range={low}~{high} "
        f"— gate not passed): {text!r} -> {fixed!r}"
    )
    return fixed


def calc_cost_krw(model: str, input_tokens: int, output_tokens: int) -> float:
    p = PRICING.get(model, PRICING["gpt-4o-mini"])
    usd = (input_tokens / 1_000_000) * p["input"] + (output_tokens / 1_000_000) * p["output"]
    return round(usd * USD_TO_KRW, 2)


async def call_daily_feedback(user_data: dict[str, Any]) -> dict[str, Any]:
    """Daily Feedback — gpt-4o-mini + JSON response

    user_data (정본은 routers/daily_logs.py 의 ai_input 조립부 — 여기 적힌 것과 어긋나면 그쪽이 맞다):
        user                  : {age, gender}
        today_glucose         : int | None
        glucose_normal_range  : {low, high} | None      # 이 환자가 설정한 평소 공복혈당 범위
        dinner_deadline       : str | None              # 예 "오후 7시 30분" (임의 시각 생성 금지)
        goal / medical_safety : 의료 목표·안전 컨텍스트
        yesterday: {
            alcohol, dinner_before_7pm, no_food_after_dinner,
            dinner_type(한국어 라벨 리스트) / dinner_type_other,
            digestion, post_meal_exercise,
            steps, stress_level,
            sleep_hours, sleep_start_hour, avg_sleep_hours_7d,
            sleep_deep_minutes, sleep_rem_minutes, sleep_light_minutes, sleep_awake_minutes,
        }
        score                 : {total, change_from_yesterday, ...}

    ⚠️ [260729 통합검증 P2] 이전 docstring 은 payload 에 **없는** 필드(diabetes_years /
      weekly_streak_days / score.weekly_avg)를 적고, 실제로 보내는 절반가량을 빠뜨리고 있었다.
      이 프로젝트의 반복 버그 유형("payload 에 있는데 프롬프트가 모르는 필드")의 시작점이 여기다.
    """
    score_val = user_data.get("score", {}).get("total", 0)
    yesterday = user_data.get("yesterday", {})
    # [260728 R3] yesterday 가 명시적으로 null 로 오면(키는 있고 값이 None) 아래 .get 이 전부
    #   AttributeError 를 내고, except 절의 fallback 도 같은 dict 를 쓰므로 예외가 밖으로 새서 500 이 된다.
    if not isinstance(yesterday, dict):
        yesterday = {}
    change = user_data.get("score", {}).get("change_from_yesterday", 0)

    try:
        client = get_client()
        messages = [
            {"role": "system", "content": ai_prompts.SYSTEM_PROMPT_DAILY},
            {"role": "user", "content": json.dumps(user_data, ensure_ascii=False)},
        ]
        # [피드백 260714 정리] 이전 [우노 5] 목표치 강제 삽입 로직 제거.
        #   프롬프트(ai_prompts.SYSTEM_PROMPT_DAILY [의료진 목표 반영])가 이미 규정:
        #   "매 응답에 목표 수치를 넣지 마세요. 가끔·마지막 한 줄에 가볍게" — 이 지시와 충돌해서 규칙이 무력화됐음.
        #   사용자 원문 피드백: "목표치보다는 평소 공복혈당 범위에 대해 주되게 피드백" — 강제 삽입 X.
        #   → 프롬프트에 판단 위임. goal 정보는 user_data["goal"]로 이미 전달되고 있어 AI가 자율 판단.
        # [저녁 마감시간 동적화] 사용자가 설정한 저녁 식사 마감 시각을 AI에 알려 프롬프트의 '7시'를 덮어씀.
        #   caller(routers/daily_logs.py)가 user_data["dinner_deadline"]에 "오후 8시" 같은 문자열을 넣어줌.
        _dinner_deadline = user_data.get("dinner_deadline")
        if _dinner_deadline:
            messages.append({
                "role": "user",
                "content": (
                    f"중요 지시: 이 사용자의 저녁 식사 마감 목표 시각은 '{_dinner_deadline}'입니다. "
                    f"저녁 식사 시각 관련 코칭은 프롬프트 예시의 '7시'가 아니라 반드시 이 시각('{_dinner_deadline}')을 기준으로 말하세요. "
                    f"(예: \"저녁을 {_dinner_deadline} 전에 마치면 야간 혈당 안정에 도움돼요.\")"
                ),
            })
        response = await client.chat.completions.create(
            model=settings.openai_daily_model,
            messages=messages,
            response_format={"type": "json_object"},
            temperature=0.7,
            # 사용자 피드백: AI 멘트가 너무 짧음 → 토큰 늘려서 3~5문장 + 상관관계 분석 가능하게
            max_tokens=700,
        )
        content = response.choices[0].message.content
        result = json.loads(content) if content else {}

        # 메타데이터 추가
        usage = response.usage
        result["_meta"] = {
            "model": settings.openai_daily_model,
            "input_tokens": usage.prompt_tokens if usage else 0,
            "output_tokens": usage.completion_tokens if usage else 0,
            "cost_krw": calc_cost_krw(
                settings.openai_daily_model,
                usage.prompt_tokens if usage else 0,
                usage.completion_tokens if usage else 0,
            ),
        }

        # mission.type 검증 — [260623 피드백] '물 마시기/수분(hydration)' 미션 폐지 → valid_types에서 제외
        #   (AI가 실수로 hydration을 줘도 아래 default general 미션으로 대체됨)
        valid_types = {
            "steps", "sleep", "alcohol", "dinner_timing",
            "stretching", "stress_relief", "general",
        }
        mission_obj = result.get("mission")
        mission_type = (
            mission_obj.get("type")
            if isinstance(mission_obj, dict) else None
        )

        # [260722 의료진 피드백 · 260722 fix] 물/수분 언급 후처리 필터.
        #   ⚠️ 이전엔 else 블록 안에서 정의해 mission_type invalid 케이스에서 UnboundLocalError → fallback 강등.
        #   if/else 밖으로 이동해서 어느 분기든 안전하게 사용.
        # [정규식 강화] "건물을·인물을·동물을" 같은 false positive 방지 — 한글 lookbehind 추가.
        import re as _re
        _water_re = _re.compile(
            r"물\s*[일이삼사오육칠팔구십\d]+\s*잔"       # "물 8잔", "물 여덟잔"
            r"|물\s*마시"                                    # "물 마시기"
            r"|(?<![가-힣])물을\s*(?:마시|드시|섭취)"        # "물을 마시" (한글 앞이면 제외 → 건물을/인물을 안전)
            r"|수분\s*(?:섭취|보충)"                         # "수분 섭취/보충"
            r"|water_glasses"
            r"|(?<![a-z])water(?![a-z])"                    # 단어 경계 (waterfall 등 방지)
            r"|(?<![a-z])hydration(?![a-z])",
            _re.IGNORECASE,
        )
        _water_sub_re = _re.compile(
            r"[^.!?]*(?:물\s*[일이삼사오육칠팔구십\d]+\s*잔"
            r"|물\s*마시"
            r"|(?<![가-힣])물을\s*(?:마시|드시|섭취)"
            r"|수분\s*(?:섭취|보충)"
            r"|water_glasses"
            r"|(?<![a-z])water(?![a-z])"
            r"|(?<![a-z])hydration(?![a-z]))[^.!?]*[.!?]?",
            _re.IGNORECASE,
        )

        if mission_type not in valid_types:
            logger.warning(
                f"Invalid/missing mission type: {mission_type}, "
                f"using default general mission"
            )
            result["mission"] = {
                "type": "general",
                # [260623 피드백] 물 미션 폐지 — 식후 걷기 + 식후 금식으로 대체
                "text": "오늘 식후 30분 내 10분 걷기\n저녁 식사 후 잠들기 전까지 추가 음식 X",
                "target": {"post_meal_walking_minutes": 10, "no_food_after_dinner": True},
                "tip": "💡 작은 행동이 혈당 관리에 큰 차이를 만들어요",
            }
        else:
            # GPT가 프롬프트 지시 어기고 물 관련 문구를 mission.text에 담는 케이스 방어
            _mt = mission_obj.get("text") or ""
            _tt = mission_obj.get("tip") or ""
            if _water_re.search(_mt) or _water_re.search(_tt):
                logger.warning(f"Mission contained water/hydration keyword — replacing with default general: text={_mt!r}")
                result["mission"] = {
                    "type": "general",
                    "text": "오늘 식후 30분 내 10분 걷기\n저녁 식사 후 잠들기 전까지 추가 음식 X",
                    "target": {"post_meal_walking_minutes": 10, "no_food_after_dinner": True},
                    "tip": "💡 작은 행동이 혈당 관리에 큰 차이를 만들어요",
                }

        # [260726 P2-C] influencing_factors 화이트리스트 검증.
        #   mission.type 은 valid_types 로 검증되는데 factors 는 어느 계층에도 enum 검증이
        #   없었다(스키마 list[str] / DB JSONB 무제약 / 앱은 미지 키를 raw 그대로 출력).
        #   → 모델이 "carb_intake" 같은 값을 주면 시니어 화면에 영어 원문이 노출된다.
        #   허용값 근거는 VALID_INFLUENCING_FACTORS 주석 참고.
        _raw_factors = result.get("influencing_factors")
        if isinstance(_raw_factors, list):
            _kept: list[str] = []
            _dropped: list[str] = []
            for _f in _raw_factors:
                if not isinstance(_f, str):
                    _dropped.append(repr(_f))
                    continue
                # 앱 friendlyFactorText()와 동일한 정규화(trim/lower/공백·하이픈→언더스코어)
                _norm = _f.strip().lower().replace(" ", "_").replace("-", "_")
                if _norm in VALID_INFLUENCING_FACTORS:
                    if _norm not in _kept:  # 중복 키는 앱에서 같은 문장 반복 노출됨
                        _kept.append(_norm)
                else:
                    _dropped.append(_f)
            if _dropped:
                logger.warning(
                    f"Invalid influencing_factors dropped: {_dropped} "
                    f"(kept={_kept})"
                )
            result["influencing_factors"] = _kept
        else:
            if _raw_factors is not None:
                logger.warning(
                    f"influencing_factors is not a list — discarding: {_raw_factors!r}"
                )
            result["influencing_factors"] = []

        # [260722 codex HIGH 3] 물/수분 언급 후처리 sanitize — 실제 응답 필드에 정확히 매칭.
        #   이전엔 존재하지 않는 필드(score_message/mission_completion_message/insight)만 검사했음(no-op).
        #   실제 스키마(AIFeedbackOut / AIFeedbackTodayResponse): top-level message/encouragement + mission.text/tip.
        #   ※ mission.text/tip에 water 문구가 있으면 위 else 블록이 이미 mission 전체를 default general로 치환하므로
        #     여기선 top-level 자유서술 필드(message/encouragement)만 문장 단위 sanitize.
        #   influencing_factors는 enum 리스트라서 자연어 필터 대상 아님.
        for _msg_key in ("message", "encouragement"):
            _val = result.get(_msg_key)
            if isinstance(_val, str) and _water_re.search(_val):
                logger.warning(f"{_msg_key} contained water keyword — sanitizing: {_val!r}")
                _cleaned = _water_sub_re.sub("", _val).strip()
                if _msg_key == "message":
                    # message는 필수(str) — 완전히 비면 스키마 검증 실패.
                    result[_msg_key] = _cleaned or "오늘도 꾸준히 관리하고 계세요."
                else:
                    # encouragement는 Optional — 완전히 비면 null 유지.
                    result[_msg_key] = _cleaned or None

        # ------------------------------------------------------------------
        # [260726 / 260728 R3][당봄 반복 지적 ①] AI 수면 '단계 이름' 오라벨 방어층
        # ------------------------------------------------------------------
        #   고객 실제 사고: 총 수면 8시간 18분인 사용자에게
        #     "😴 체력 회복 잠도 8시간 18분으로 충분하셨네요"
        #   → **총 수면시간(sleep_hours)을 수면 단계 이름으로 부른 것**이 지적의 본체다.
        #   프롬프트(ai_prompts.py)의 필드 정의·예시를 모두 고쳤지만 LLM 출력은 100% 보장이
        #   안 되므로 사용자 노출 직전에 한 번 더 잡는다.
        #
        #   [260728 R3 확장] 이전 버전은 '체력 회복 잠'(Deep) 한 단계만 봤다. 같은 프롬프트·같은
        #   payload 구조라서 나머지 단계도 똑같이 총 수면시간으로 오라벨된다 — 확장 전 실측으로
        #   "뇌 휴식하는 잠도 8시간 18분", "얕은 잠이 8시간 18분", "자다 깬 시간이 8시간 18분"이
        #   전부 무검열 통과하는 것을 확인했다. → 4단계 전부로 확장.
        #   4단계 모두 payload(yesterday.sleep_deep/rem/light/awake_minutes)에 실제로 들어온다
        #   (routers/daily_logs.py). payload에 없는 필드를 근거로 삼지 않는다.
        #
        #   판정 3단계 — '숫자는 건드리지 않고 이름만 고친다'는 원칙 유지.
        #     ⓥ 거부권(최우선): 인용된 값이 그 단계의 payload 값과 사실상 같으면 **절대 손대지 않는다**.
        #        → "체력 회복 잠이 42분(총 6시간 중)" 같은 정상 문장을 파괴한 과거 사고의 근본 차단막.
        #     ⓐ 총 수면시간 오라벨 = 인용값이 총 수면시간의 85% 이상.
        #        어떤 단계도 하룻밤의 85%를 차지할 수 없다(가장 긴 얕은 잠도 50~60% 수준).
        #        → 이때만 라벨을 '총 수면시간'으로 교체한다. 숫자가 실제로 총 수면시간이라 사실이 맞다.
        #          (라벨 문자열은 ai_prompts.py 의 노출 명칭과 일치해야 한다 — _relabel 주석 참조)
        #     ⓑ 불가능한 값이지만 총 수면시간도 아님 = 체력 회복 잠/뇌 휴식하는 잠이 180분 이상.
        #        ⚠️ 이전 코드는 이 경우도 라벨만 바꿨다. 총 8시간 18분인 사람의 "체력 회복 잠이 3시간"을
        #        "총 수면시간이 3시간"으로 바꿔 **없는 사실(3시간만 잤다)**을 만들어냈다(실측 확인).
        #        → 자유서술은 문장 제거. 미션은 '오늘의 목표'라 어제 사실과 충돌하지 않으므로 라벨 교정.
        #        ※ 얕은 잠·자다 깬 시간에는 절대 상한을 두지 않는다 — 얕은 잠 4시간은 정상이다.
        #     ⓒ 환각 제거: 그 단계의 payload 값 자체가 없으면 언급 전체가 지어낸 것 → 문장 제거.
        #        ⚠️ [260726 fix P1-B] ⓒ·ⓑ 판정은 반드시 교정 전 '원문' 기준으로 ⓐ보다 먼저 한다.
        #        (ⓐ가 먼저 돌면 라벨이 사라져 ⓒ가 검사할 대상을 잃고, 환각이 '총 수면시간'이라는
        #         더 그럴듯한 허위 사실로 승격된다.)
        _total_sleep = yesterday.get("sleep_hours")

        def _numv(_v):
            """int/float 만 통과(bool 은 int 서브클래스라 명시 배제). 아니면 None."""
            if isinstance(_v, bool) or not isinstance(_v, (int, float)):
                return None
            return float(_v)

        _total_min = None
        if _numv(_total_sleep) is not None and _numv(_total_sleep) > 0:
            _total_min = _numv(_total_sleep) * 60.0

        #   ⚠️ [260726 fix P1-A] 라벨과 숫자 사이(그룹2)는 '조사/공백' 수준으로만 허용한다.
        #   이전 패턴 [^.!?\n]{0,10}? 은 너무 관대해서 "체력 회복 잠이 42분(총 6시간 중)" 에서
        #   그룹2가 "이 42분(총 "을 삼키고 뒤쪽 '6시간'을 잡아 정상 문장을 파괴했다.
        #   → 숫자·구두점·'분'·'총'·'시'가 하나라도 끼면 매칭 불가, 길이도 4자로 조인다.
        #   [260728 R3] 단 "체력 회복 잠(8시간 18분)" 괄호 표기를 놓쳤으므로 여는 괄호 1개만 추가 허용.
        #     괄호를 허용해도 문자클래스가 숫자를 막아 위 사고 지점("42분(총 6시간")은 재발하지 않는다.
        _GAP = r"[^0-9.,()\[\]/~%!?\n분총시]{0,4}?[(\[]?"

        # (라벨 정규식, payload 값(분), ⓑ 절대 상한(분) — None 이면 상한 없음)
        #   프롬프트가 강제하는 시니어 용어가 정식 표기이고, 금지어(깊은 잠/렘 수면 등)도 함께 잡는다.
        _SLEEP_STAGES = (
            (r"체력\s*회복\s*잠|깊은\s*(?:잠|수면)",
             yesterday.get("sleep_deep_minutes"), 180.0),
            (r"뇌\s*휴식(?:하는)?\s*잠|렘\s*수면|REM\s*수면",
             yesterday.get("sleep_rem_minutes"), 180.0),
            (r"얕은\s*(?:잠|수면)",
             yesterday.get("sleep_light_minutes"), None),
            (r"자다\s*깬\s*시간",
             yesterday.get("sleep_awake_minutes"), None),
        )
        # 인덱스: 0=라벨등장 1=문장제거 2=시간표기 3=분표기 4=payload값 5=절대상한
        #   ⚠️ 시간 표기는 뒤따르는 '분'까지 함께 읽어야 한다("6시간 56분" → 416분).
        #     시(時)만 읽으면 총 수면 6.93시간(=415.8분)을 360분으로 오인해 '총 수면시간 아님'으로
        #     잘못 판정하고 정상 교정 대상을 문장째로 지워버린다(실측 확인).
        _SLEEP_GUARDS = [
            (
                _re.compile(_src),
                _re.compile(r"[^.!?]*(?:" + _src + r")[^.!?]*[.!?]?"),
                _re.compile(r"(" + _src + r")(" + _GAP + r")(\d+(?:\.\d+)?)\s*시간"
                            r"(?:\s*(\d{1,2})\s*분)?"),
                _re.compile(r"(" + _src + r")(" + _GAP + r")(\d+)\s*분"),
                _numv(_stage_val),
                _abs_min,
            )
            for _src, _stage_val, _abs_min in _SLEEP_STAGES
        ]
        _SLEEP_HINT_RE = _re.compile("|".join(_s for _s, _, _ in _SLEEP_STAGES))

        def _sleep_kind(_stage_val, _abs_min, _quoted_min):
            """'total'(총 수면시간 오라벨) / 'impossible'(불가능한 값) / None(정상)"""
            # ⓥ 거부권 — 모델이 그 단계의 실제 값을 인용한 것이면 무조건 정상 문장이다
            if _stage_val is not None and abs(_quoted_min - _stage_val) <= max(5.0, _stage_val * 0.05):
                return None
            if _total_min is not None:
                # ⓐ '이 숫자는 사실 총 수면시간이다'라고 말해도 되는 건 실제로 그 값일 때만이다.
                #   (허용 오차는 모델의 반올림·분 단위 절삭 폭)
                if abs(_quoted_min - _total_min) <= max(20.0, _total_min * 0.05):
                    return "total"
                # 총 수면시간의 85% 이상 = 한 단계가 하룻밤을 거의 다 차지 → 불가능한 값.
                #   그런데 총 수면시간 값 자체도 아니므로 라벨만 바꾸면 '없는 총 수면시간'이 생긴다.
                if _quoted_min >= _total_min * 0.85:
                    return "impossible"
            if _abs_min is not None and _quoted_min >= _abs_min:
                return "impossible"
            return None

        def _quoted_hours(_m) -> float:
            """"6시간 56분" → 416.0 (뒤따르는 분이 있으면 합산)"""
            return float(_m.group(3)) * 60.0 + float(_m.group(4) or 0)

        def _stage_kinds(_g, _text) -> set:
            _kinds = set()
            for _m in _g[2].finditer(_text):            # "N시간 (M분)"
                _k = _sleep_kind(_g[4], _g[5], _quoted_hours(_m))
                if _k:
                    _kinds.add(_k)
            for _m in _g[3].finditer(_text):            # "N분"
                _k = _sleep_kind(_g[4], _g[5], float(_m.group(3)))
                if _k:
                    _kinds.add(_k)
            return _kinds

        def _quoted_mins(_g, _text) -> list:
            """그 단계 라벨에 붙어 인용된 값들(분)."""
            _vals = [_quoted_hours(_m) for _m in _g[2].finditer(_text)]
            _vals += [float(_m.group(3)) for _m in _g[3].finditer(_text)]
            return _vals

        def _has_offbase_quote(_g, _text) -> bool:
            """payload 값(_g[4]) 과 사실상 다른 숫자를 인용했는가 — ⓥ 거부권과 같은 허용 오차."""
            _sv = _g[4] or 0.0
            _tol = max(5.0, abs(_sv) * 0.05)
            return any(abs(_q - _sv) > _tol for _q in _quoted_mins(_g, _text))

        def _relabel(_g, _text, _allowed):
            """단계 이름만 '총 수면시간'으로 바꾼다.

            매치 중 **라벨 길이만큼만** 갈아치우고 나머지(조사·괄호·숫자·단위)는 원문 그대로
            이어 붙인다 → 숫자를 재조립하다 표기를 잃는 사고("6시간 56분"의 56분 유실)를 원천 차단.

            ⚠️ [260729 통합검증] 교정 라벨은 반드시 ai_prompts.py 가 사용자 노출 명칭으로 확정한
              문자열과 **같아야** 한다. 프롬프트 L112-114 는 sleep_hours 의 노출 명칭을
              "총 수면시간"으로 통일하고 **"실제 수면시간"은 쓰지 마라**고 금지했는데(자다 깬 시간을
              포함하는 값이라 '실제로 잔 시간'이라 부르면 정의가 어긋난다), 이 가드만 옛 라벨인
              "실제 수면시간"을 그대로 찍고 있었다 → GPT가 규칙을 지킨 날은 "총 수면시간",
              가드가 교정한 날은 "실제 수면시간"이 나가 같은 값이 두 이름으로 보였다.
              라벨을 바꿀 일이 생기면 프롬프트와 이 상수를 **함께** 고칠 것.
            """
            _LABEL = "총 수면시간"

            def _sub_hours(_m):
                if _sleep_kind(_g[4], _g[5], _quoted_hours(_m)) in _allowed:
                    return _LABEL + _m.group(0)[len(_m.group(1)):]
                return _m.group(0)

            def _sub_mins(_m):
                if _sleep_kind(_g[4], _g[5], float(_m.group(3))) in _allowed:
                    return _LABEL + _m.group(0)[len(_m.group(1)):]
                return _m.group(0)

            return _g[3].sub(_sub_mins, _g[2].sub(_sub_hours, _text))

        def _strip_stage(_g, _text: str) -> str:
            # 문장 제거 후 남는 이중 공백 정리(줄바꿈은 보존)
            return _re.sub(r"[ \t]{2,}", " ", _g[1].sub("", _text)).strip()

        def _sleep_guard(_text: str, *, allow_strip: bool) -> str:
            _out = _text
            for _g in _SLEEP_GUARDS:
                if not _g[0].search(_out):
                    continue
                _kinds = _stage_kinds(_g, _out)     # ⚠️ 교정 전 원문 기준
                if not allow_strip:
                    # 미션은 제거하면 빈 미션이 되므로 라벨 교정만.
                    #
                    # ⚠️ [260729 통합검증 P0] 이전 코드는 `{"total", "impossible"}` 을 허용해서
                    #   ⓑ(불가능한 값)까지 라벨을 갈아끼웠다. 근거는 "미션은 오늘의 목표라
                    #   어제 사실과 충돌하지 않는다"였는데, 그 논리는 인용값이 **정상 총 수면
                    #   범위(7~9h)일 때만** 성립한다. ⓑ가 발화하는 구간은 체력회복잠/뇌휴식잠
                    #   180분 이상 ~ 총 수면시간 미만이라 항상 7시간 미만이다. 실측:
                    #     "체력 회복 잠 3시간 확보하기" → "총 수면시간 3시간 확보하기"
                    #   = 시니어 당뇨 환자에게 **총 수면 3시간(배점 8/20 '부족' 구간)을
                    #     오늘의 행동 목표로 지시**하는 유해 문구가 만들어졌다.
                    #   자유서술에서 같은 변환을 폐기한 근거(L497-500)와 동일하다.
                    #   → ⓐ(총 수면시간 오라벨)만 교정하고, ⓑ 는 그 줄을 제거한다.
                    #     미션이 통째로 비면 아래 빈-미션 방어(L724-736 계열)가 기본 미션으로 대체한다.
                    if "impossible" in _kinds and "total" not in _kinds:
                        _new = _strip_stage(_g, _out)
                        logger.warning(
                            f"Mission sleep goal is an impossible stage value — removing "
                            f"instead of relabeling (would create a harmful sleep target): "
                            f"{_out!r} -> {_new!r}"
                        )
                        _out = _new
                        continue
                    _new = _relabel(_g, _out, {"total"})
                    if _new != _out:
                        logger.warning(f"Sleep mislabel corrected (mission): {_out!r} -> {_new!r}")
                        _out = _new
                    continue
                if _g[4] is None:
                    # ⓒ payload 값 자체가 없다 → 언급 전체가 환각
                    _new = _strip_stage(_g, _out)
                    logger.warning(
                        f"Sleep stage mentioned but its payload minutes are missing — "
                        f"removing hallucinated sentence: {_out!r} -> {_new!r}"
                    )
                    _out = _new
                elif _g[4] <= 0 and _has_offbase_quote(_g, _out):
                    # ⓒ' payload 는 0분인데 모델이 전혀 다른 숫자를 인용 → 환각
                    #   ⚠️ [260729 통합검증 P1] 이전 조건은 `_g[4] <= 0` 만 보고 **0분 자체를
                    #     '측정 결함'으로 단정**했다. 그런데 자다 깬 시간 0분·렘 0분은 결함이 아니라
                    #     정상 측정값이고(스키마 ge=0), 그 결과
                    #       "자다 깬 시간이 0분으로 푹 주무셨어요. 오늘 아침 공복혈당은 138 mg/dL이에요."
                    #     라는 **참인 문장**이 통째로 삭제됐다(실측). sleep_score 가 총 수면 0분을
                    #     미측정으로 보는 근거는 '총합'에만 적용되는 논거지 단계에는 해당하지 않는다.
                    #   → 0분일 때는 모델이 0(±5분)을 인용했으면 정상, 다른 값을 인용했을 때만 환각.
                    _new = _strip_stage(_g, _out)
                    logger.warning(
                        f"Sleep stage payload is 0 min but a different value was quoted — "
                        f"removing hallucinated sentence: {_out!r} -> {_new!r}"
                    )
                    _out = _new
                elif "impossible" in _kinds:
                    # ⓑ 불가능한 값이고 총 수면시간도 아님 → 라벨을 바꾸면 없는 사실이 된다
                    _new = _strip_stage(_g, _out)
                    logger.warning(
                        f"Sleep stage value is impossible and is not the total sleep time — "
                        f"removing instead of relabeling: {_out!r} -> {_new!r}"
                    )
                    _out = _new
                elif "total" in _kinds:
                    # ⓐ 총 수면시간을 단계 이름으로 부름 → 이름만 교체
                    _new = _relabel(_g, _out, {"total"})
                    if _new != _out:
                        logger.warning(
                            f"Total sleep time was called by a sleep-stage name — "
                            f"corrected: {_out!r} -> {_new!r}"
                        )
                        _out = _new
            return _out

        for _msg_key in ("message", "encouragement"):
            _val = result.get(_msg_key)
            if isinstance(_val, str) and _SLEEP_HINT_RE.search(_val):
                _new = _sleep_guard(_val, allow_strip=True)
                if _msg_key == "message":
                    result[_msg_key] = _new or "오늘도 꾸준히 관리하고 계세요."
                else:
                    result[_msg_key] = _new or None

        # 미션은 라벨 교정만. (B)까지 적용하면 "체력 회복 잠을 늘려보세요" 같은
        # 수치 없는 정상 미션까지 통째로 지워져 빈 미션이 될 수 있다.
        #   미션은 '어제의 사실'이 아니라 '오늘의 목표'라서, 총 수면시간 유무와 무관하게
        #   "체력 회복 잠 7시간" 같은 불가능한 목표는 라벨만 바로잡는 게 맞다.
        _mission = result.get("mission")
        if isinstance(_mission, dict):
            for _mk in ("text", "tip"):
                _v = _mission.get(_mk)
                if isinstance(_v, str) and _SLEEP_HINT_RE.search(_v):
                    _mission[_mk] = _sleep_guard(_v, allow_strip=False)

        # [260728][당봄 피드백 2] 공복혈당 '평소 범위 안' 오표현 가드 (_fix_range_claim 주석 참조).
        #   자유서술 필드만 대상. mission 은 '오늘의 목표'라 "범위 안으로 들어오게" 같은 정상 문장이
        #   나올 수 있고, 그건 상태 단언이 아니므로 애초에 매칭되지 않지만 안전하게 제외한다.
        _gr = user_data.get("glucose_normal_range")
        _g_low = _gr.get("low") if isinstance(_gr, dict) else None
        _g_high = _gr.get("high") if isinstance(_gr, dict) else None
        for _msg_key in ("message", "encouragement"):
            _val = result.get(_msg_key)
            if isinstance(_val, str) and "범위" in _val:
                _new = _fix_range_claim(
                    _val, user_data.get("today_glucose"), _g_low, _g_high
                )
                if _msg_key == "message":
                    result[_msg_key] = _new or "오늘도 꾸준히 관리하고 계세요."
                else:
                    result[_msg_key] = _new or None

        # [260729 P1-A] 저혈당 주의 안내 게이트 — 코드 방어층 (_strip_hypo_caution 주석 참조).
        #   범위 가드와 달리 mission.text / mission.tip 까지 본다. 이 문구는 라벨 오류가 아니라
        #   **행동 지시**라서, 미션·팁에 실려도 사용자는 똑같이 '당분을 드세요'로 읽는다.
        for _msg_key in ("message", "encouragement"):
            _val = result.get(_msg_key)
            if isinstance(_val, str):
                _new = _strip_hypo_caution(
                    _val, user_data.get("today_glucose"), _g_low, _g_high
                )
                if _msg_key == "message":
                    result[_msg_key] = _new or "오늘도 꾸준히 관리하고 계세요."
                else:
                    result[_msg_key] = _new or None

        _mission2 = result.get("mission")
        if isinstance(_mission2, dict):
            for _mk in ("text", "tip"):
                _v = _mission2.get(_mk)
                if isinstance(_v, str):
                    _mission2[_mk] = _strip_hypo_caution(
                        _v, user_data.get("today_glucose"), _g_low, _g_high
                    )
            # 문장을 걷어내다 미션이 통째로 비면 빈 카드가 노출된다.
            #   → 물/수분 가드와 같은 방식으로 기본 general 미션으로 대체(빈 화면 방지).
            if not (_mission2.get("text") or "").strip():
                logger.warning(
                    "Mission text became empty after guard strips (hypo-caution / "
                    "impossible sleep goal) — replacing with default general mission"
                )
                result["mission"] = {
                    "type": "general",
                    "text": "오늘 식후 30분 내 10분 걷기\n저녁 식사 후 잠들기 전까지 추가 음식 X",
                    "target": {"post_meal_walking_minutes": 10, "no_food_after_dinner": True},
                    "tip": "💡 작은 행동이 혈당 관리에 큰 차이를 만들어요",
                }
            elif not (_mission2.get("tip") or "").strip():
                _mission2["tip"] = "💡 작은 행동이 혈당 관리에 큰 차이를 만들어요"

        # ------------------------------------------------------------------
        # [260729 통합검증 P1] 가드가 문장을 걷어내다 **오늘 공복혈당까지 함께 지운** 경우 복구
        # ------------------------------------------------------------------
        #   위 가드들은 전부 '문장 단위'로 지운다. 그런데 프롬프트의 정답 예시(ai_prompts.py
        #   L179·L186) 자체가 수면과 혈당을 한 문장에 담으라고 가르친다:
        #     "오늘 아침 공복혈당은 138 mg/dL이고 체력 회복 잠은 짧은 편이었어요."
        #   → 수면 절이 환각이면 문장 전체가 사라져 message 가 "오늘도 꾸준히 관리하고 계세요."
        #     로 축약된다. 이건 (1) 프롬프트 L205 가 명시적으로 금지한 '추상 단독 멘트'이고
        #     (2) today_glucose 1회 언급 필수(L216) 계약을 깨며 (3) 그대로 DB 에 저장돼
        #     하루 종일 노출되고 CSV 로 의료진에게도 나간다(실측).
        #   → 최종 message 에 오늘 수치가 사라졌으면 사실만 담은 한 문장을 되돌려 붙인다.
        #     ('범위 안/밖' 같은 판단은 붙이지 않는다 — 판단 문장은 다른 가드의 관할이다.)
        _tg = fallback_messages._as_glucose_number(user_data.get("today_glucose"))
        _final_msg = result.get("message")
        if _tg is not None and isinstance(_final_msg, str):
            _tg_txt = f"{_tg:g}"
            if not _MGDL_RE.search(_final_msg) and _tg_txt not in _final_msg:
                result["message"] = (
                    _final_msg.rstrip() + f" 오늘 아침 공복혈당은 {_tg_txt} mg/dL이에요."
                ).strip()
                logger.warning(
                    "Today's glucose disappeared from the message after guard strips — "
                    f"restored: {_final_msg!r} -> {result['message']!r}"
                )

        return result

    except Exception as e:
        logger.error(f"GPT Daily Feedback failed: {e}", exc_info=True)
        # Fallback
        _fb_gr = user_data.get("glucose_normal_range")
        _fb_range = _fb_gr if isinstance(_fb_gr, dict) else {}
        fb = fallback_messages.get_fallback_daily(
            score=score_val,
            glucose=user_data.get("today_glucose"),
            alcohol=yesterday.get("alcohol"),
            dinner_before_7pm=yesterday.get("dinner_before_7pm"),
            no_food_after_dinner=yesterday.get("no_food_after_dinner"),
            sleep_hours=yesterday.get("sleep_hours"),
            change_from_yesterday=change,
            avg_sleep_hours=yesterday.get("avg_sleep_hours_7d"),
            # [260726] GPT 실패 시에도 사용자가 설정한 저녁 마감 시각을 그대로 쓴다(임의 시각 생성 금지).
            dinner_deadline=user_data.get("dinner_deadline"),
            # [260728 당봄 피드백 2] GPT 실패 경로도 개인 평소 범위 기준으로 '미만/안/초과'를 말한다.
            #   daily_logs.py 가 ai_input["glucose_normal_range"] = {"low","high"} 로 넣어준다(없으면 None).
            glucose_normal_low=_fb_range.get("low"),
            glucose_normal_high=_fb_range.get("high"),
        )
        fb["_meta"] = {"model": "fallback", "input_tokens": 0, "output_tokens": 0, "cost_krw": 0}
        return fb


async def call_weekly_report(weekly_data: dict[str, Any]) -> dict[str, Any]:
    """주간 리포트 — gpt-4o"""
    try:
        client = get_client()
        response = await client.chat.completions.create(
            model=settings.openai_weekly_model,
            messages=[
                {"role": "system", "content": ai_prompts.SYSTEM_PROMPT_WEEKLY},
                {"role": "user", "content": json.dumps(weekly_data, ensure_ascii=False)},
            ],
            response_format={"type": "json_object"},
            temperature=0.7,
            max_tokens=800,
        )
        content = response.choices[0].message.content
        result = json.loads(content) if content else {}

        usage = response.usage
        result["_meta"] = {
            "model": settings.openai_weekly_model,
            "input_tokens": usage.prompt_tokens if usage else 0,
            "output_tokens": usage.completion_tokens if usage else 0,
            "cost_krw": calc_cost_krw(
                settings.openai_weekly_model,
                usage.prompt_tokens if usage else 0,
                usage.completion_tokens if usage else 0,
            ),
        }
        return result

    except Exception as e:
        logger.error(f"GPT Weekly Report failed: {e}", exc_info=True)
        return {
            "summary": "이번 주 데이터를 분석할 수 없었습니다. 잠시 후 다시 시도해주세요.",
            "key_pattern": None,
            "achievement": None,
            "next_week_goals": [],
            "correlation_findings": [],
            "_fallback": True,
            "_meta": {"model": "fallback", "cost_krw": 0},
        }


async def call_monthly_report(monthly_data: dict[str, Any]) -> dict[str, Any]:
    """월간 리포트 — gpt-4o"""
    try:
        client = get_client()
        response = await client.chat.completions.create(
            model=settings.openai_monthly_model,
            messages=[
                {"role": "system", "content": ai_prompts.SYSTEM_PROMPT_MONTHLY},
                {"role": "user", "content": json.dumps(monthly_data, ensure_ascii=False)},
            ],
            response_format={"type": "json_object"},
            temperature=0.7,
            max_tokens=1000,
        )
        content = response.choices[0].message.content
        result = json.loads(content) if content else {}

        usage = response.usage
        result["_meta"] = {
            "model": settings.openai_monthly_model,
            "input_tokens": usage.prompt_tokens if usage else 0,
            "output_tokens": usage.completion_tokens if usage else 0,
            "cost_krw": calc_cost_krw(
                settings.openai_monthly_model,
                usage.prompt_tokens if usage else 0,
                usage.completion_tokens if usage else 0,
            ),
        }
        return result

    except Exception as e:
        logger.error(f"GPT Monthly Report failed: {e}", exc_info=True)
        return {
            "summary": "이번 달 데이터를 분석할 수 없었습니다.",
            "monthly_trend": "유지",
            "next_month_goals": [],
            "_fallback": True,
            "_meta": {"model": "fallback", "cost_krw": 0},
        }
