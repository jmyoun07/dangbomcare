@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.dangbomcare.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dangbomcare.app.data.api.AIFeedbackToday
import com.dangbomcare.app.ui.components.*
import com.dangbomcare.app.ui.theme.*
import com.dangbomcare.app.ui.viewmodel.HomeViewModel

@Composable
fun AiFeedbackScreen(
    onBack: () -> Unit,
    vm: HomeViewModel = viewModel()
) {
    val state by vm.state.collectAsState()

    // 코덱스 E: 어제 breakdown 선조회 (격려 카드의 항목별 비교 미니 UI 용)
    var previousBreakdown by remember { mutableStateOf<com.dangbomcare.app.data.api.ScoreBreakdown?>(null) }
    LaunchedEffect(state.score?.scoreDate) {
        previousBreakdown = null
        val scoreDate = state.score?.scoreDate ?: return@LaunchedEffect
        val prevDate = runCatching {
            java.time.LocalDate.parse(scoreDate).minusDays(1)
        }.getOrElse { java.time.LocalDate.now().minusDays(1) }

        when (val res = com.dangbomcare.app.data.ServiceLocator.dailyLogRepository.get(prevDate.toString())) {
            is com.dangbomcare.app.data.repo.ApiResult.Success ->
                previousBreakdown = res.data.score?.breakdown
            else -> Unit
        }
    }

    Scaffold(
        containerColor = BgSecondary,
        topBar = {
            TopAppBar(
                title = { Text("오늘의 AI 피드백", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgSecondary)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            when {
                state.loading -> LoadingIndicator(modifier = Modifier.height(200.dp))
                state.feedback == null -> ErrorMessage(message = "오늘의 AI 피드백이 없어요. 아침 입력을 완료해주세요.")
                else -> FeedbackContent(
                    fb = state.feedback!!,
                    todayBreakdown = state.score?.breakdown,
                    previousBreakdown = previousBreakdown
                )
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun FeedbackContent(
    fb: AIFeedbackToday,
    todayBreakdown: com.dangbomcare.app.data.api.ScoreBreakdown? = null,
    previousBreakdown: com.dangbomcare.app.data.api.ScoreBreakdown? = null
) {
    // 안전 추출 — 빈 문자열·빈 배열·null 미션은 카드 자체 안 그림
    val dailyMessage = fb.dailyMessage?.trim().orEmpty()
    val factors = fb.influencingFactors.orEmpty().filter { it.isNotBlank() }
    val encouragement = fb.encouragement?.trim().orEmpty()
    val mission = fb.mission?.takeIf { !it.text.isNullOrBlank() }
    val streak = fb.streak
    val hasAny = dailyMessage.isNotBlank() || factors.isNotEmpty() ||
                 encouragement.isNotBlank() || mission != null || streak != null
    // 전체 영역이 비어 있을 때만 안내 (5개 박스 모두 데이터 없을 때 사용자가 빈 화면 보는 것 방지)
    if (!hasAny) {
        ErrorMessage(message = "오늘의 AI 피드백이 비어 있어요. 아침 입력을 완료하면 분석이 시작돼요.")
        return
    }

    // 오늘의 메시지
    dailyMessage.takeIf { it.isNotBlank() }?.let { msg ->
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = LimeLight)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("🤖 오늘의 AI 코치", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = PrimaryDark)
                Spacer(Modifier.height(12.dp))
                Text(msg, fontSize = 17.sp, color = TextPrimary, lineHeight = 26.sp)
            }
        }
    }

    // 영향 요인 (영어 키 → 한국어 친절 멘트 변환)
    factors.takeIf { it.isNotEmpty() }?.let { factors ->
        SectionCard(title = "오늘 점수에 영향을 준 요인") {
            factors.forEach { f ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text("• ", fontSize = 16.sp, color = Primary, fontWeight = FontWeight.Bold)
                    Text(
                        friendlyFactorText(f),
                        fontSize = 15.sp,
                        color = TextPrimary,
                        lineHeight = 22.sp
                    )
                }
            }
        }
    }

    // 격려 (단독 카드)
    encouragement.takeIf { it.isNotBlank() }?.let { e ->
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = BgCard),
            border = androidx.compose.foundation.BorderStroke(2.dp, LimeAccent)
        ) {
            Text(
                "💚 $e",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = PrimaryDark,
                modifier = Modifier.padding(20.dp)
            )
        }
    }

    // 항목별 비교 (별도 카드 — 격려 유무와 무관하게 표시) — 코덱스 E + 비차단 개선
    if (todayBreakdown != null && previousBreakdown != null) {
        val diffs = computeBreakdownDiffs(todayBreakdown, previousBreakdown)
        if (diffs.isNotEmpty()) {
            SectionCard(title = "전날 대비 주요 변화") {
                diffs.take(4).forEach { (label, diff) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (diff >= 0) "▲" else "▼",
                            fontSize = 14.sp,
                            color = if (diff >= 0) Success else ErrorRed,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(label, fontSize = 14.sp, color = TextPrimary, modifier = Modifier.weight(1f))
                        // 소화는 소수점 점수(2.5 단위) — 정수면 소수점 없이 표시
                        val diffText = if (diff == diff.toLong().toDouble()) diff.toLong().toString() else diff.toString()
                        Text(
                            "${if (diff >= 0) "+" else ""}$diffText" + "점",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (diff >= 0) Success else ErrorRed
                        )
                    }
                }
            }
        }
    }

    // 미션
    mission?.let { m ->
        SectionCard(title = "🎯 오늘의 미션") {
            Text(m.text ?: "-", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            if (m.progress > 0) {
                // 백엔드 mission_progress는 0.0~1.0 (fraction) — 바는 그대로, 라벨은 ×100
                val frac = m.progress.toFloat().coerceIn(0f, 1f)
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { frac },
                    modifier = Modifier.fillMaxWidth().height(10.dp),
                    color = if (m.completed) Success else LimeAccent,
                    trackColor = BgCardSoft
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (m.completed) "✅ 달성!" else "${(frac * 100).toInt()}% 진행 중",
                    fontSize = 13.sp,
                    color = if (m.completed) Success else TextSecondary
                )
            }
            m.tip?.let { tip ->
                Spacer(Modifier.height(12.dp))
                Text("💡 $tip", fontSize = 14.sp, color = TextSecondary, lineHeight = 20.sp)
            }
        }
    }

    // 스트릭 (Streak)
    streak?.let { s ->
        SectionCard(title = "이번 주 / 이번 달") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🔥", fontSize = 28.sp)
                    Text("이번 주", fontSize = 13.sp, color = TextSecondary)
                    Text(
                        "${s.weeklyCompletedDays} / ${s.weeklyTotalDays}일",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Primary
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("📅", fontSize = 28.sp)
                    Text("이번 달", fontSize = 13.sp, color = TextSecondary)
                    Text(
                        "${s.monthlyCompletedDays}일",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Primary
                    )
                }
            }
        }
    }
}

/**
 * 영향 요인(influencing_factors)의 영어 키 or AI가 보낸 짧은 문장을
 * 시니어 친화적인 한국어 친절 멘트로 변환.
 *
 * - 키 매칭(소문자/언더스코어)이 우선 적용되고,
 * - 매칭 안 되면 원문(이미 한국어 문장일 가능성) 그대로 노출.
 */
private fun friendlyFactorText(raw: String): String {
    val key = raw.trim().lowercase().replace(" ", "_").replace("-", "_")
    return when {
        // 식사/공복 관련
        // [260729 계약 확정] influencing_factors 는 **'개선이 필요한(부정) 요인'만 담는 칸**이다 — 9개 키 전부.
        //   (ai_prompts.py [influencing_factors 가능 값] ★ 항목 / 서버 화이트리스트
        //    ai_service.VALID_INFLUENCING_FACTORS 9개와 같은 계약. 셋이 어긋나면 안 된다.)
        //   ⚠️ 260728 주석은 "키가 방향을 담지 않아 잘한 날에도 온다"고 적혀 있었으나 그건 옛 계약이다.
        //     지금은 프롬프트가 "잘한 습관·긍정 기여는 factors 에 절대 넣지 않는다"로 못박았고,
        //     칭찬은 message 문장으로만 한다.
        //   그래도 아래 문구는 **방향 중립 서술 + 개선 안내** 형태를 유지한다. 부정으로 단정("끝내지
        //   않으셨어요")하면 모델이 계약을 어긴 날 사실과 반대인 문장이 시니어 화면에 그대로 나가기 때문이다.
        //   ReportsScreen.riskFactorAdvice(L2009 sleep) 와 같은 톤으로 '사실 → 개선'만 말한다.
        key == "dinner_before_7pm" || key.contains("dinner_late") || key.contains("late_dinner") ->
            "저녁 식사 시각이 다음날 공복혈당에 영향을 줘요. 저녁을 설정하신 마감 시각 전에 마쳐보세요."
        key == "no_food_after_dinner" || key.contains("snack_after_dinner") || key.contains("night_eating") ->
            "저녁 식사 후 추가 섭취가 다음날 공복혈당에 영향을 줘요. 저녁 식사 후 4시간 이상 아무것도 드시지 마세요."
        key == "fasting_glucose_high" || key.contains("high_fasting") ->
            "공복혈당이 평소보다 높게 측정됐어요. 전날 저녁 식사 시각/식후 금식/수면을 함께 살펴보세요."
        // [260728 고객피드백 ②] "낮은 편"은 무엇 대비 낮은지가 빠져 있다 → 기준(평소 범위)을 밝힌다.
        key == "fasting_glucose_low" || key.contains("low_fasting") ->
            "공복혈당이 평소 범위보다 낮게 측정됐어요. 어지럽거나 식은땀이 나면 바로 단 것을 드세요."
        // 부정 키("no_post_meal_walk")가 아래 긍정 contains("post_meal_walk")에 걸리지 않도록 먼저 검사
        key.contains("no_post_meal_walk") || key.contains("sedentary_after_meal") ->
            "식후에 바로 앉아 계셨어요. 식사 후 10~15분 걷기는 혈당 안정에 도움돼요."
        key.contains("post_meal_walk") || key.contains("after_meal_walk") ->
            "식후 가벼운 산책이 있었어요. 식후 혈당 상승을 완만하게 해줘서 좋아요."

        // [260707] 신규 회고 3항목 — 백엔드가 부정 방향일 때만 이 키를 보냄
        key == "dinner_type" || key.contains("heavy_dinner") || key.contains("greasy_dinner") ->
            "전날 저녁식사 유형(외식·배달·육류 등)이 다음날 공복혈당에 영향을 줬을 수 있어요."
        key == "digestion" || key.contains("full_stomach") || key.contains("indigestion") ->
            "속이 더부룩한 상태로 잠드셨어요. 취침 전 소화가 덜 되면 다음날 공복혈당이 높게 측정될 수 있어요."
        key == "post_meal_exercise" || key.contains("no_post_meal_exercise") ->
            "식후 운동이 없었어요. 저녁 식사 후 10~15분 걷기는 혈당 안정에 큰 도움이 돼요."

        // 음주
        // [260728 고객피드백 ③] "평소와 다르게"는 방향이 없어 사용자가 상승으로 읽는다.
        //   실제 경향은 **낮게** 나오는 쪽이다(간이 알코올을 분해하는 동안 밤사이 혈당을 못 올려줘서).
        //   높게 나오는 건 안주·추가 음식이 함께 있었을 때의 예외라서 뒤에 짧게 덧붙인다.
        //   ScoreDetailScreen.describeAlcohol / ReportsScreen.riskFactorAdvice 와 같은 방향으로 통일.
        key == "alcohol" || key.contains("alcohol_yes") || key.contains("drinking") ->
            // [260728 R4 최종 교차검증] "많았다면"의 양 추정 제거 — 안주를 얼마나 드셨는지는
            //   어느 계층에도 데이터가 없다(ai_prompts.py 도 양 가정을 명시적으로 금지).
            //   양이 아니라 '함께 드신 음식이 있는지'를 기준으로 백엔드와 문구를 통일.
            "전날 음주가 있으셨어요. 술을 드신 다음날 아침은 공복혈당이 오히려 낮게 나오는 경우가 많아요 " +
                "(함께 드신 안주나 추가 음식의 탄수화물이 있으면 반대로 높게 나올 수 있어요)."

        // 수면
        // [260728 수면 배점 개편] 과다수면도 감점(9~10h 18 / 13h↑ 10)이라 "짧았어요"로 고정 번역하면
        //   12시간 주무신 분께 거짓 문장이 나간다 — 위쪽 AI 코치 메시지는 "길었어요"라고 말하는데
        //   같은 화면 아래 요인 카드가 "짧았어요"라고 정반대를 말하던 문제.
        //   백엔드 top_risk_factors 도 방향을 안 내려주므로(reports.py L57-59 동일 결론)
        //   ReportsScreen.kt L2009 와 같은 톤의 방향 중립 표현으로 통일한다.
        key == "sleep" || key.contains("sleep_short") || key.contains("short_sleep") ->
            "수면 시간이 다음날 혈당에 영향을 줘요. 짧아도 길어도 좋지 않으니 매일 7~9시간, 일정한 시각에 주무세요."
        key.contains("sleep_long") || key.contains("oversleep") ->
            "수면 시간이 평소보다 길었어요. 너무 긴 수면도 컨디션에 영향을 줄 수 있어요."
        key.contains("sleep_quality") || key.contains("poor_sleep") || key.contains("low_sleep_quality") ->
            "수면의 질이 떨어진 날이에요. 체력 회복 잠 비율이 낮으면 혈당 조절에도 영향이 있어요."
        key.contains("sleep_missing") || key.contains("no_sleep_data") ->
            "수면 데이터가 측정되지 않은 날이에요. 시계를 착용하고 주무시면 더 정확한 분석을 받을 수 있어요."

        // 스트레스
        key == "stress" || key.contains("stress_high") || key.contains("high_stress") ->
            "스트레스 지수가 높았어요. 스트레스 호르몬(코르티솔)은 혈당을 올릴 수 있어요."
        key.contains("stress_low") ->
            "스트레스가 낮고 안정적인 하루였어요. 좋은 흐름을 이어가세요."

        // 활동/걸음
        // [260729 계약 확정] "steps" 는 '걸음이 적어 개선 대상으로 짚은 날'에만 온다(위 ★ 참조).
        //   아래 high_steps/active_day/steps_good 분기는 서버 화이트리스트가 잘라내 도달하지 않는다
        //   (남겨두는 이유: 계약이 다시 바뀌어도 raw 영어 키가 시니어 화면에 노출되지 않게).
        //   목표 수치는 ReportsScreen.kt L2004 / score_service.steps_score 와 동일한 8,000보.
        key == "steps" || key.contains("low_steps") || key.contains("steps_low") || key.contains("sedentary") ->
            "활동량이 혈당 관리에 영향을 줘요. 하루 8,000보 이상 걷는 걸 목표로 해보세요."
        key.contains("high_steps") || key.contains("active_day") || key.contains("steps_good") ->
            "활동량이 충분한 하루였어요. 꾸준한 걷기가 혈당 안정에 큰 도움이 돼요."

        // 데이터 누락
        key.contains("missing_data") || key.contains("no_data") || key.contains("data_missing") ->
            "측정되지 않은 항목이 있어요. 시계 착용 시간을 늘리시면 더 정확한 피드백을 받을 수 있어요."

        // 기본값: 이미 한국어 문장이면 그대로, 아니면 원문 노출
        else -> raw
    }
}

/**
 * 오늘 vs 어제 breakdown 차이 계산.
 * 절대값 기준 상위 변화 순으로 정렬 (큰 변화부터 사용자가 주목하기 쉽게).
 * 차이가 0인 항목은 제외.
 */
private fun computeBreakdownDiffs(
    today: com.dangbomcare.app.data.api.ScoreBreakdown,
    prev: com.dangbomcare.app.data.api.ScoreBreakdown
): List<Pair<String, Double>> {
    fun diff(t: Int?, p: Int?): Double? =
        if (t != null && p != null) (t - p).toDouble() else null
    fun diffD(t: Double?, p: Double?): Double? =
        if (t != null && p != null) t - p else null

    return listOfNotNull(
        diff(today.glucose, prev.glucose)?.let { "공복혈당" to it },
        diff(today.steps, prev.steps)?.let { "걸음수" to it },
        diff(today.sleep, prev.sleep)?.let { "수면" to it },
        diff(today.dinnerBefore7pm, prev.dinnerBefore7pm)?.let { "저녁 마감 전 식사" to it },
        diff(today.alcohol, prev.alcohol)?.let { "음주" to it },
        diff(today.noFoodAfterDinner, prev.noFoodAfterDinner)?.let { "식후 음식 섭취 여부" to it },
        diff(today.dinnerType, prev.dinnerType)?.let { "저녁식사 유형" to it },
        diffD(today.digestion, prev.digestion)?.let { "자기 전 소화" to it },
        diff(today.postMealExercise, prev.postMealExercise)?.let { "식후 운동" to it },
        diff(today.stress, prev.stress)?.let { "스트레스" to it },
    )
        .filter { kotlin.math.abs(it.second) > 0.05 }
        .sortedByDescending { kotlin.math.abs(it.second) }
}
