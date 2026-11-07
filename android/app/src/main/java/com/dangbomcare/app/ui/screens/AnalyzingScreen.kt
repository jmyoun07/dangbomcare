@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.dangbomcare.app.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dangbomcare.app.ui.theme.*
import kotlinx.coroutines.delay

/**
 * 분석 로딩 화면 — "어제의 생활을 분석 중입니다" 3단계 진행.
 * v1.9 신규 화면.
 */
@Composable
fun AnalyzingScreen(
    isDone: Boolean,
    onDoneAnimationComplete: () -> Unit
) {
    var currentStep by remember { mutableIntStateOf(0) }
    val steps = listOf(
        "📥 전날 워치 데이터 가져오는 중...",
        "🧮 라이프스타일 점수 계산 중...",
        "🤖 AI가 맞춤 메시지 작성 중..."
    )

    // 자동 진행 (백엔드 호출 끝나면 isDone=true)
    LaunchedEffect(Unit) {
        while (!isDone && currentStep < steps.size - 1) {
            delay(900)
            currentStep++
        }
        // 모든 단계 표시 후 추가 대기
        if (!isDone) {
            while (!isDone) {
                delay(200)
            }
        }
        // 완료 표시 후 다음 화면으로
        currentStep = steps.size
        delay(700)
        onDoneAnimationComplete()
    }

    val infinite = rememberInfiniteTransition(label = "loading")
    val rotation by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(1400, easing = LinearEasing)),
        label = "rotation"
    )

    Scaffold(containerColor = BgSecondary) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 메인 로딩 아이콘
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(LimeLight),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "🤖",
                    fontSize = 64.sp,
                    modifier = if (currentStep < steps.size) Modifier.rotate(rotation * 0.1f) else Modifier
                )
            }

            Spacer(Modifier.height(32.dp))

            Text(
                if (currentStep >= steps.size) "✅ 분석 완료!" else "전날의 생활을 분석 중이에요",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(8.dp))
            Text(
                if (currentStep >= steps.size) "잠시만 기다려주세요" else "최대 10초 소요됩니다",
                fontSize = 13.sp,
                color = TextSecondary
            )

            Spacer(Modifier.height(40.dp))

            // 진행 단계
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = BgCard)
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    steps.forEachIndexed { i, label ->
                        StepLine(
                            label = label,
                            state = when {
                                i < currentStep -> StepState.DONE
                                i == currentStep && currentStep < steps.size -> StepState.IN_PROGRESS
                                currentStep >= steps.size -> StepState.DONE
                                else -> StepState.PENDING
                            }
                        )
                    }
                }
            }
        }
    }
}

private enum class StepState { PENDING, IN_PROGRESS, DONE }

@Composable
private fun StepLine(label: String, state: StepState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.size(28.dp).clip(CircleShape).background(
                when (state) {
                    StepState.DONE -> Success
                    StepState.IN_PROGRESS -> LimeAccent
                    StepState.PENDING -> BgCardSoft
                }
            ),
            contentAlignment = Alignment.Center
        ) {
            when (state) {
                StepState.DONE -> Text("✓", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                StepState.IN_PROGRESS -> Text("•", color = PrimaryDark, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                StepState.PENDING -> Text("◦", color = TextDisabled, fontSize = 16.sp)
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            fontSize = 14.sp,
            color = when (state) {
                StepState.DONE -> TextPrimary
                StepState.IN_PROGRESS -> TextPrimary
                StepState.PENDING -> TextDisabled
            },
            fontWeight = if (state == StepState.IN_PROGRESS) FontWeight.Bold else FontWeight.Normal
        )
    }
}
