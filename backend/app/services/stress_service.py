"""스트레스 자체 추정 서비스 (v1.9)
워치 raw HRV가 없으니 심박수 샘플의 변동성으로 추정.
Samsung Health Data SDK 신청 후 v1.x에서 정확한 값으로 전환.
"""
from __future__ import annotations

from datetime import datetime
from typing import Any


def estimate_stress_from_hr(
    hr_samples: list[dict[str, Any]] | None,
) -> dict[str, Any]:
    """심박수 샘플에서 스트레스 등급 추정.

    hr_samples: [{"time": ISO8601, "bpm": int}, ...]
    return: {
        "avg_variability": float,
        "level": "적정" | "주의" | "높음",
        "below_avg_ratio": float,
        "sample_count": int,
    }
    """
    if not hr_samples or len(hr_samples) < 10:
        return {
            "avg_variability": None,
            "level": None,
            "below_avg_ratio": None,
            "sample_count": len(hr_samples) if hr_samples else 0,
        }

    # 시각순 정렬
    sorted_samples = sorted(
        hr_samples,
        key=lambda s: s.get("time", "")
    )

    # 인접 샘플 간 BPM 차이 = HRV 근사
    diffs: list[float] = []
    for i in range(1, len(sorted_samples)):
        prev_bpm = sorted_samples[i - 1].get("bpm")
        cur_bpm = sorted_samples[i].get("bpm")
        if prev_bpm is None or cur_bpm is None:
            continue
        diff = abs(cur_bpm - prev_bpm)
        diffs.append(float(diff))

    if not diffs:
        return {
            "avg_variability": None,
            "level": None,
            "below_avg_ratio": None,
            "sample_count": 0,
        }

    avg_var = sum(diffs) / len(diffs)

    # 등급화 (의학적 정확도는 ~70% 수준 — Samsung SDK로 교체 예정)
    if avg_var >= 8.0:
        level = "적정"
        below_avg_ratio = 0.85
    elif avg_var >= 5.0:
        level = "주의"
        below_avg_ratio = 0.55
    else:
        level = "높음"
        below_avg_ratio = 0.25

    return {
        "avg_variability": round(avg_var, 2),
        "level": level,
        "below_avg_ratio": below_avg_ratio,
        "sample_count": len(diffs),
    }


def estimate_stress_by_time_of_day(
    hr_samples: list[dict[str, Any]] | None,
) -> dict[str, str | None]:
    """시간대별 스트레스 (오전/오후/저녁) 추정"""
    if not hr_samples:
        return {"morning_status": None, "afternoon_status": None, "evening_status": None}

    # 시간대별로 그룹화
    buckets: dict[str, list] = {"morning": [], "afternoon": [], "evening": []}

    for s in hr_samples:
        t = s.get("time")
        if not t:
            continue
        try:
            dt = datetime.fromisoformat(str(t).replace("Z", "+00:00"))
        except (ValueError, TypeError):
            continue
        h = dt.hour
        if 6 <= h < 12:
            buckets["morning"].append(s)
        elif 12 <= h < 18:
            buckets["afternoon"].append(s)
        elif 18 <= h < 23:
            buckets["evening"].append(s)

    result = {}
    for k, samples in buckets.items():
        if len(samples) < 5:
            result[f"{k}_status"] = None
            continue
        est = estimate_stress_from_hr(samples)
        result[f"{k}_status"] = est["level"]

    return result
