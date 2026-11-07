@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.dangbomcare.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dangbomcare.app.ui.components.BigButton
import com.dangbomcare.app.ui.theme.*
import com.dangbomcare.app.ui.viewmodel.AuthViewModel
import kotlinx.coroutines.delay

/**
 * [v1.0] 이메일 인증 대기 화면 — 약심 앱 스타일.
 *
 * 흐름:
 *  1) 사용자가 메일함의 인증 링크 클릭 → Supabase가 이메일 인증 처리 → 앱 딥링크로 복귀
 *     (MainActivity가 OAuth 콜백으로 처리 → 세션 저장 → 자동으로 다음 화면)
 *  2) 또는 사용자가 다른 기기에서 클릭한 경우 → 앱 돌아와서 "인증 완료 확인" 버튼 클릭
 *     (signIn 재시도 → 인증 완료되었으면 성공)
 *  3) 메일이 안 오면 → "인증 이메일 재발송" 버튼 (60초 쿨다운)
 */
@Composable
fun EmailVerifyWaitScreen(
    email: String,
    onVerified: () -> Unit,
    onBack: () -> Unit,
    vm: AuthViewModel = viewModel()
) {
    // [코덱스 P0] password는 더 이상 route param X — PendingSignupCredentials singleton에서 메모리 조회
    val pendingPassword = remember(email) {
        com.dangbomcare.app.ui.viewmodel.PendingSignupCredentials.getPassword(email)
    }
    // [코덱스 P1] 화면 dispose 시 비밀번호 메모리에서 즉시 정리 (앱 백그라운드 잔존 방지)
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            com.dangbomcare.app.ui.viewmodel.PendingSignupCredentials.clear()
        }
    }
    val state by vm.state.collectAsState()

    // OAuth 콜백 (이메일 링크 클릭 → 딥링크) 관찰
    val mainActivity = com.dangbomcare.app.MainActivity.instance
    val oauthEvent by (mainActivity?.oauthEvent
        ?: remember { kotlinx.coroutines.flow.MutableStateFlow<com.dangbomcare.app.MainActivity.OAuthEvent?>(null) })
        .collectAsState()

    // 재발송 쿨다운 (60초)
    var resendCooldown by remember { mutableStateOf(0) }
    var resendMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(resendCooldown) {
        if (resendCooldown > 0) {
            delay(1000L)
            resendCooldown -= 1
        }
    }

    // 딥링크로 자동 인증 완료 처리
    LaunchedEffect(oauthEvent) {
        when (oauthEvent) {
            is com.dangbomcare.app.MainActivity.OAuthEvent.Success -> {
                mainActivity?.consumeOAuthEvent()
                onVerified()
            }
            is com.dangbomcare.app.MainActivity.OAuthEvent.Error -> {
                // 딥링크 에러는 잠시 표시만 (사용자가 수동 확인 가능)
                mainActivity?.consumeOAuthEvent()
            }
            is com.dangbomcare.app.MainActivity.OAuthEvent.PasswordResetReady -> {
                // 글로벌 NavGraph 핸들러가 처리 — 여기서는 no-op
            }
            null -> Unit
        }
    }

    // "인증 완료 확인" 버튼이 signIn 성공 → Success 상태
    LaunchedEffect(state) {
        if (state is AuthViewModel.UiState.Success) {
            onVerified()
        }
    }

    Scaffold(
        containerColor = BgPrimary,
        topBar = {
            TopAppBar(
                title = { Text("이메일 인증", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgPrimary)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(48.dp))

            // 이메일 아이콘 (큰 원형 배경)
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .background(Primary, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.Email,
                    contentDescription = null,
                    tint = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.size(64.dp)
                )
            }

            Spacer(Modifier.height(32.dp))

            Text(
                "이메일을 확인하세요",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(20.dp))

            Text(
                email,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Primary,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "위 주소로 인증 이메일을 보냈습니다.\n받은편지함에서 인증 링크를 클릭한 후\n아래 버튼을 눌러주세요.",
                fontSize = 14.sp,
                color = TextSecondary,
                lineHeight = 22.sp,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(16.dp))

            Text(
                "도착하지 않으면 스팸함을 확인하거나\n아래 \"인증 이메일 재발송\"을 눌러주세요.",
                fontSize = 13.sp,
                color = TextSecondary,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center
            )

            // 에러 메시지
            if (state is AuthViewModel.UiState.Error) {
                Spacer(Modifier.height(20.dp))
                Surface(
                    color = ErrorRed.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "⚠️ ${(state as AuthViewModel.UiState.Error).message}",
                        color = ErrorRed,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            // 재발송 안내 메시지
            resendMessage?.let {
                Spacer(Modifier.height(12.dp))
                Surface(
                    color = Primary.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "✓ $it",
                        color = Primary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            // "인증 완료 확인" 버튼 — signIn 재시도
            BigButton(
                text = if (state is AuthViewModel.UiState.Loading) "확인 중..." else "인증 완료 확인",
                onClick = {
                    if (pendingPassword != null) {
                        vm.checkEmailVerified(email, pendingPassword)
                    } else {
                        // 세션이 메모리에서 사라진 케이스 (앱 재실행 등) — 안내
                        vm.setExternalError("앱이 재시작되어 인증 확인이 불가능해요.\n로그인 화면에서 비밀번호로 로그인해주세요.")
                    }
                },
                enabled = state !is AuthViewModel.UiState.Loading
            )

            Spacer(Modifier.height(12.dp))

            // 재발송 버튼 (60초 쿨다운)
            TextButton(
                onClick = {
                    if (resendCooldown == 0) {
                        vm.resendConfirmation(email) { success, msg ->
                            resendMessage = msg
                            if (success) {
                                resendCooldown = 60
                            }
                        }
                    }
                },
                enabled = resendCooldown == 0 && state !is AuthViewModel.UiState.Loading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (resendCooldown > 0) "재발송 (${resendCooldown}초 후)"
                    else "인증 이메일 재발송",
                    fontSize = 14.sp,
                    color = if (resendCooldown > 0) TextSecondary else Primary,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
