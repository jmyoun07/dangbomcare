@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.dangbomcare.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dangbomcare.app.data.api.MedicalGoalRequest
import com.dangbomcare.app.ui.components.AppCalendarDialog
import com.dangbomcare.app.ui.components.BigButton
import com.dangbomcare.app.ui.components.SectionCard
import com.dangbomcare.app.ui.theme.*
import com.dangbomcare.app.ui.viewmodel.MedicalGoalViewModel
import java.time.LocalDate

// [260730] 목표 입력은 의료진 웹으로 이관 → 앱 내 PIN 잠금·입력 폼 제거.
//   이 화면은 의료진이 웹에서 설정한 목표를 환자가 '확인'만 한다(현재/과거 이력 조회).

private fun trimNumber(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

/** 목표 시점(ISO 날짜 문자열)을 "YYYY년 M월 D일"로. 파싱 실패 시 원문. */
private fun formatGoalPeriod(s: String?): String? {
    if (s.isNullOrBlank()) return null
    val d = runCatching { LocalDate.parse(s) }.getOrNull() ?: return s
    return "${d.year}년 ${d.monthValue}월 ${d.dayOfMonth}일"
}

/** 목표 설정 시점(created_at ISO datetime)을 "YYYY.M.D 설정"으로. 파싱 실패 시 앞 10자. */
private fun formatGoalSetDate(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    return runCatching {
        val d = java.time.OffsetDateTime.parse(iso).toLocalDate()
        "${d.year}.${d.monthValue}.${d.dayOfMonth} 설정"
    }.getOrElse { iso.take(10) }
}

@Composable
fun MedicalGoalScreen(
    onBack: () -> Unit,
    vm: MedicalGoalViewModel = viewModel()
) {
    val state by vm.state.collectAsState()
    var historyIndex by remember { mutableStateOf(0) }   // 이력 ◀▶ 위치 (0=현재/최신)

    Scaffold(
        containerColor = BgSecondary,
        topBar = {
            TopAppBar(
                title = { Text("의료진 목표", fontWeight = FontWeight.Bold) },
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
                .imePadding()   // [우노 4] 키패드가 입력칸을 가리지 않게
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // 안내
            Surface(
                color = Primary.copy(alpha = 0.08f),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("🏥", fontSize = 22.sp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "의료진이 진료 때 설정한 목표를 확인하는 화면이에요. 최근 목표와 지난 목표를 볼 수 있어요.",
                        fontSize = 12.sp, color = TextSecondary, lineHeight = 17.sp
                    )
                }
            }

            // 목표 — 현재 + 과거 이력 ◀▶ 조회
            when (val s = state) {
                is MedicalGoalViewModel.UiState.Loading ->
                    SectionCard(title = "현재 목표") {
                        Text("불러오는 중…", fontSize = 13.sp, color = TextSecondary)
                    }
                is MedicalGoalViewModel.UiState.Error ->
                    SectionCard(title = "현재 목표") {
                        Text("불러오기 실패: ${s.message}", fontSize = 13.sp, color = ErrorRed)
                    }
                is MedicalGoalViewModel.UiState.Ready -> {
                    val history = s.history
                    if (history.isEmpty()) {
                        SectionCard(title = "현재 목표") {
                            Text("아직 설정된 목표가 없어요.", fontSize = 13.sp, color = TextSecondary)
                        }
                    } else {
                        val idx = historyIndex.coerceIn(0, history.size - 1)
                        val g = history[idx]
                        val isCurrent = idx == 0
                        SectionCard(title = if (isCurrent) "현재 목표" else "과거 목표") {
                            // ◀(과거) [현재/과거 · 설정일 · N/M] ▶(최신)
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = { if (idx < history.size - 1) historyIndex = idx + 1 },
                                    enabled = idx < history.size - 1
                                ) {
                                    Icon(
                                        Icons.Filled.ChevronLeft, contentDescription = "이전(과거) 목표",
                                        tint = if (idx < history.size - 1) Primary else TextDisabled
                                    )
                                }
                                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        if (isCurrent) "현재 목표" else "과거 목표",
                                        fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                        color = if (isCurrent) PrimaryDark else TextSecondary
                                    )
                                    val dateLabel = formatGoalSetDate(g.createdAt)
                                    Text(
                                        (if (dateLabel.isNotBlank()) "$dateLabel · " else "") + "${idx + 1}/${history.size}",
                                        fontSize = 11.sp, color = TextSecondary
                                    )
                                }
                                IconButton(
                                    onClick = { if (idx > 0) historyIndex = idx - 1 },
                                    enabled = idx > 0
                                ) {
                                    Icon(
                                        Icons.Filled.ChevronRight, contentDescription = "다음(최신) 목표",
                                        tint = if (idx > 0) Primary else TextDisabled
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            if (!g.hasGoal) {
                                Text("이 시점엔 설정된 목표가 없어요.", fontSize = 13.sp, color = TextSecondary)
                            } else {
                                GoalSummaryRow("당화혈색소 목표", g.targetHba1c?.let { "${trimNumber(it)} %" })
                                GoalSummaryRow("췌장수치 목표", g.targetPancreas?.let { trimNumber(it) })
                                GoalSummaryRow("공복혈당 목표", g.targetFastingGlucose?.let { "$it mg/dL" })
                                GoalSummaryRow("목표 시점", formatGoalPeriod(g.targetPeriod))
                                GoalSummaryRow("생활습관 목표", g.lifestyleGoals)
                                // [피드백 260714] 담당 의료진 이름 표시 — 폼 저장 시 함께 저장한 값
                                GoalSummaryRow("담당 의료진", g.setBy)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun GoalSummaryRow(label: String, value: String?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(label, fontSize = 13.sp, color = TextSecondary, modifier = Modifier.width(108.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            value?.takeIf { it.isNotBlank() } ?: "없음",
            fontSize = 13.sp,
            color = if (value.isNullOrBlank()) TextDisabled else TextPrimary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
    }
}
