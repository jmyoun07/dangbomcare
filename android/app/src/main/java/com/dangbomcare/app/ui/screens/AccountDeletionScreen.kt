@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.dangbomcare.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dangbomcare.app.data.ServiceLocator
import com.dangbomcare.app.data.repo.ApiResult
import com.dangbomcare.app.ui.theme.*
import kotlinx.coroutines.launch

/**
 * 회원 탈퇴 — 30일 grace period 후 영구 삭제 안내 + 이중 확인.
 *
 * 1. 경고 화면 (영구 삭제 안내)
 * 2. 사유 선택 (선택)
 * 3. 확인 문구 입력 ("탈퇴합니다")
 * 4. 최종 다이얼로그
 */
@Composable
fun AccountDeletionScreen(
    onBack: () -> Unit,
    onDeleted: () -> Unit
) {
    var step by rememberSaveable { mutableStateOf(1) }  // 1: 안내, 2: 확인 입력
    var confirmText by rememberSaveable { mutableStateOf("") }
    var showFinalDialog by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val confirmMatched = confirmText.trim() == "탈퇴합니다"

    Scaffold(
        containerColor = BgSecondary,
        topBar = {
            TopAppBar(
                title = { Text("회원 탈퇴", fontWeight = FontWeight.Bold) },
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
                // [v1.0 피드백] 키보드가 "탈퇴합니다" 입력 칸을 가리지 않도록 imePadding 추가
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // 경고 카드
            Surface(
                color = ErrorRed.copy(alpha = 0.08f),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = ErrorRed)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            "정말 탈퇴하시겠어요?",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = ErrorRed
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "탈퇴 시 그동안 쌓아온 모든 건강 기록이 30일 후 영구 삭제됩니다.",
                            fontSize = 13.sp,
                            color = TextPrimary,
                            lineHeight = 19.sp
                        )
                    }
                }
            }

            // 안내 섹션
            Surface(
                color = BgCard,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("📋 탈퇴 시 처리 내용", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Spacer(Modifier.height(12.dp))
                    // 복구 정책 통일 (Codex 검토): 30일 내 재로그인으로 복구 가능, 30일 후 영구 삭제
                    BulletText("탈퇴 즉시 앱 로그아웃 상태가 되며, 데이터는 30일간 보관됩니다.")
                    BulletText("30일 이내에 같은 계정으로 다시 로그인하시면 모든 데이터가 복구됩니다.")
                    BulletText("30일이 지나면 모든 의료 데이터가 영구 삭제되며 복구가 불가능합니다.")
                    BulletText("의료법 시행규칙에 따른 의무 보존 기간이 있는 경우 해당 기간 동안 별도 보관됩니다.")
                }
            }

            // 법적 근거
            Surface(
                color = BgCardSoft,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("⚖️ 법적 근거", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextSecondary)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "개인정보보호법 제21조 (파기) + 의료법 시행규칙 (의료기록 보존)에 따라\n" +
                        "탈퇴 신청 후 30일 grace period 종료 시 모든 식별 가능 데이터를 영구 삭제합니다.",
                        fontSize = 11.sp,
                        color = TextSecondary,
                        lineHeight = 16.sp
                    )
                }
            }

            if (step >= 2) {
                Surface(
                    color = ErrorRed.copy(alpha = 0.06f),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "탈퇴 확인",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = ErrorRed
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "아래 칸에 \"탈퇴합니다\"를 정확히 입력해주세요.",
                            fontSize = 13.sp,
                            color = TextPrimary
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = confirmText,
                            onValueChange = { confirmText = it },
                            singleLine = true,
                            placeholder = { Text("탈퇴합니다", color = TextDisabled) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            error?.let {
                Surface(color = ErrorRed.copy(alpha = 0.1f), shape = RoundedCornerShape(8.dp)) {
                    Text(
                        it,
                        modifier = Modifier.padding(12.dp),
                        color = ErrorRed,
                        fontSize = 13.sp
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            Button(
                onClick = {
                    when {
                        step == 1 -> step = 2
                        confirmMatched -> showFinalDialog = true
                    }
                },
                enabled = step == 1 || confirmMatched,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ErrorRed,
                    contentColor = androidx.compose.ui.graphics.Color.White,
                    disabledContainerColor = ErrorRed.copy(alpha = 0.3f),
                    disabledContentColor = androidx.compose.ui.graphics.Color.White
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(
                    when {
                        deleting -> "처리 중..."
                        step == 1 -> "계속 진행"
                        else -> "탈퇴하기"
                    },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("취소", fontSize = 15.sp, color = TextPrimary)
            }

            Spacer(Modifier.height(40.dp))
        }
    }

    // ⭐ 2단계 확인 (당봄 #18-3) — 1단계: 30일 보관 안내 / 2단계: 복구 불가 명시
    var showStep2 by remember { mutableStateOf(false) }

    if (showFinalDialog) {
        // 1단계 — 30일 보관 + 영구 삭제 정책 안내
        AlertDialog(
            onDismissRequest = { showFinalDialog = false },
            icon = { Text("⏳", fontSize = 28.sp) },
            title = { Text("정말 탈퇴하시겠어요?", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "건강 데이터는 30일 동안 보관 후 영구 삭제됩니다.",
                        fontSize = 14.sp,
                        color = TextPrimary,
                        lineHeight = 20.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = Primary.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            "💡 30일 이내에 다시 로그인하시면 계정과 데이터가 복구됩니다.",
                            fontSize = 12.sp,
                            color = TextPrimary,
                            lineHeight = 17.sp,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showFinalDialog = false
                        showStep2 = true
                    }
                ) { Text("계속", color = ErrorRed, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showFinalDialog = false }) { Text("취소") }
            }
        )
    }

    if (showStep2) {
        // 2단계 — 영구 삭제 복구 불가 명시 (의료 데이터 강조)
        AlertDialog(
            onDismissRequest = { showStep2 = false },
            icon = { Text("⚠️", fontSize = 28.sp) },
            title = { Text("최종 확인", fontWeight = FontWeight.Bold, color = ErrorRed) },
            text = {
                Column {
                    Text(
                        "삭제된 의료 데이터는 복구가 불가능합니다.",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = ErrorRed,
                        lineHeight = 21.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "혈당 기록, 라이프스타일 점수, AI 분석 결과, 수면·활동 데이터까지 " +
                        "30일 후 영구 삭제되어 복구할 수 없습니다.\n\n정말 진행하시겠어요?",
                        fontSize = 13.sp,
                        color = TextPrimary,
                        lineHeight = 19.sp
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showStep2 = false
                        deleting = true
                        error = null
                        scope.launch {
                            val res = ServiceLocator.userRepository.deleteMe()
                            when (res) {
                                is ApiResult.Success -> {
                                    ServiceLocator.authRepository.signOut()
                                    onDeleted()
                                }
                                is ApiResult.Error -> {
                                    error = "탈퇴 실패: ${res.message}"
                                    deleting = false
                                }
                            }
                        }
                    }
                ) { Text("탈퇴 진행", color = ErrorRed, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showStep2 = false }) { Text("취소") }
            }
        )
    }
}

@Composable
private fun BulletText(text: String) {
    Row(modifier = Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Text("•", fontSize = 14.sp, color = TextSecondary, modifier = Modifier.padding(end = 8.dp))
        Text(text, fontSize = 13.sp, color = TextPrimary, lineHeight = 19.sp)
    }
}
