@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.dangbomcare.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dangbomcare.app.data.api.DailySummaryResponse
import com.dangbomcare.app.ui.components.*
import com.dangbomcare.app.ui.theme.*
import com.dangbomcare.app.ui.viewmodel.DailySummaryViewModel
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun DailySummaryScreen(
    onBack: () -> Unit,
    vm: DailySummaryViewModel = viewModel()
) {
    val state by vm.state.collectAsState()

    Scaffold(
        containerColor = BgSecondary,
        topBar = {
            TopAppBar(
                title = { Text("일일 종합", fontWeight = FontWeight.Bold) },
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
            // 날짜 선택 헤더
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = vm::goPreviousDay) {
                    Icon(Icons.Filled.ChevronLeft, contentDescription = "이전 날")
                }
                com.dangbomcare.app.ui.components.AutoSizeSingleLineText(
                    state.date.format(DateTimeFormatter.ofPattern("yyyy년 M월 d일 (E)", Locale.KOREAN)),
                    maxFontSize = 18.sp, minFontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                IconButton(onClick = vm::goNextDay) {
                    Icon(Icons.Filled.ChevronRight, contentDescription = "다음 날")
                }
            }

            when {
                state.loading -> LoadingIndicator(modifier = Modifier.height(200.dp))
                state.error != null -> ErrorMessage(message = state.error!!, onRetry = { vm.load(state.date) })
                state.data != null -> SummaryContent(state.data!!)
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun SummaryContent(data: DailySummaryResponse) {
    // 점수
    data.score?.let { score ->
        SectionCard(title = "점수") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${score.total ?: 0}",
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Black,
                    color = Primary
                )
                Text("점", fontSize = 18.sp, color = TextSecondary)
                Spacer(Modifier.width(16.dp))
                Text(score.label ?: "-", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Primary)
            }
        }
    }

    // 공복혈당
    data.glucose?.let { g ->
        SectionCard(title = "공복혈당") {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "${g.value ?: "-"}",
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    // [피드백 260714 정리 G4] '공복혈당 장애' 표현 완전 제거 (구버전 호환용도 삭제).
                    //   당봄케어는 당뇨약 복용 환자 대상이라 100~130은 '당뇨인 목표 범위 내(양호)'.
                    color = when (g.status) {
                        "정상", "당뇨 목표 범위", "당뇨인 목표 범위" -> Success
                        "낮음" -> GlucoseLow
                        "높음" -> ErrorRed
                        else -> TextPrimary
                    }
                )
                Text(" mg/dL", fontSize = 16.sp, color = TextSecondary)
            }
            Spacer(Modifier.height(4.dp))
            Text("상태: ${g.status ?: "-"}", fontSize = 14.sp, color = TextSecondary)
            g.targetRange?.let { r ->
                Text("당뇨 목표 범위: ${r.getOrNull(0)}~${r.getOrNull(1)} mg/dL", fontSize = 12.sp, color = TextDisabled)
            }
        }
    }

    // 활동 (워치)
    data.activity?.let { a ->
        SectionCard(title = "활동 / 워치 데이터") {
            MetricItem(label = "걸음수", value = "${a.steps ?: 0}", unit = "보", icon = "🚶")
            a.sleepHours?.let { h ->
                val tm = (h * 60).toInt()
                MetricItem(label = "수면 시간", value = "${tm / 60}시간 ${tm % 60}분", unit = "", icon = "😴")
                a.sleepStages?.let { st ->
                    Spacer(Modifier.height(8.dp))
                    val deep = st.deepMinutes ?: 0
                    val light = st.lightMinutes ?: 0
                    val rem = st.remMinutes ?: 0
                    val awake = st.awakeMinutes ?: 0
                    Text("• 체력 회복 잠: ${deep}분", fontSize = 14.sp, color = TextSecondary)
                    Text("• 얕은 잠: ${light}분", fontSize = 14.sp, color = TextSecondary)
                    Text("• 뇌 휴식하는 잠: ${rem}분", fontSize = 14.sp, color = TextSecondary)
                    if (awake > 0)
                        Text("• 자다 깬 시간: ${awake}분", fontSize = 14.sp, color = TextSecondary)
                }
            }
            a.stress?.let { stress ->
                Spacer(Modifier.height(8.dp))
                MetricItem(label = "오전 컨디션", value = stress, icon = "⚡")
            }
        }
    }

    // 회고 — [저녁 마감시간 동적화] 설정한 저녁식사 시각 반영
    data.reflection?.let { r ->
        val dinnerLabel = com.dangbomcare.app.notification.AlarmScheduler.dinnerDeadlineLabel(
            androidx.compose.ui.platform.LocalContext.current
        )
        SectionCard(title = "전날 회고") {
            ReflectionLine("술", r.alcohol, yesIsGood = false)
            ReflectionLine("$dinnerLabel 전 저녁식사", r.dinnerBefore7pm, yesIsGood = true)
            ReflectionLine("식후 금식", r.noFoodAfterDinner, yesIsGood = true)
            // [피드백 260714] 260623 도입 신규 3항목도 요약 표시 — 기존엔 점수 상세에만 노출.
            //   text-based 값이라 색은 정답/오답 대신 값 유무만 확인. 미입력이면 '-' 회색.
            ReflectionTextLine("저녁식사 유형", describeDinnerTypeShort(r.dinnerType, r.dinnerTypeOther))
            ReflectionTextLine("자기 전 소화", describeDigestionShort(r.digestion))
            ReflectionTextLine("식후 운동", describePostMealExerciseShort(r.postMealExercise))
        }
    }
}

// [피드백 260714] 회고 요약 라인 — 텍스트값 표시 (Boolean용 ReflectionLine과 별개).
@Composable
private fun ReflectionTextLine(label: String, value: String?) {
    val text = value ?: "-"
    val color = if (value == null) TextSecondary else TextPrimary
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, fontSize = 15.sp, modifier = Modifier.weight(1f), color = TextPrimary)
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = color)
    }
}

// [v1.1.3] "flour" 삭제 + "other"(기타) 신설. otherText 있으면 "기타(파스타)" 형식으로 렌더.
private fun describeDinnerTypeShort(types: List<String>?, otherText: String? = null): String? {
    if (types.isNullOrEmpty()) return null
    return types.joinToString(", ") {
        when (it) {
            "home_korean" -> "집밥/한식"
            "eating_out" -> "외식/배달"
            "meat" -> "육류"
            "light" -> "간단식"
            "other" -> otherText?.takeIf { t -> t.isNotBlank() }?.let { t -> "기타($t)" } ?: "기타"
            "flour" -> "밀가루(폐지)" // legacy — 신규 선택 불가, 기존 데이터 렌더링용
            else -> it
        }
    }
}

private fun describeDigestionShort(v: String?): String? = when (v) {
    "hungry" -> "배고픔"
    "partial" -> "조금 됨"
    "full" -> "여전히 배부름"
    null -> null
    else -> v
}

private fun describePostMealExerciseShort(v: String?): String? = when (v) {
    "60plus" -> "1시간 이상"
    "30to60" -> "30~60분"
    "10to30" -> "10~30분"
    "none" -> "안 함"
    null -> null
    else -> v
}

@Composable
private fun ReflectionLine(label: String, value: Boolean?, yesIsGood: Boolean) {
    val text = when (value) {
        true -> "예"
        false -> "아니오"
        null -> "-"
    }
    val good = (value == yesIsGood)
    val color = when {
        value == null -> TextSecondary
        good -> Success
        else -> ErrorRed
    }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, fontSize = 15.sp, modifier = Modifier.weight(1f), color = TextPrimary)
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = color)
    }
}
