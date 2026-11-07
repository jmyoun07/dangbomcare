@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.dangbomcare.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.LockReset
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dangbomcare.app.ui.components.BigButton
import com.dangbomcare.app.ui.theme.*
import com.dangbomcare.app.ui.viewmodel.AuthViewModel

/**
 * [v1.0] 비밀번호 재설정 1단계 — 이메일 입력 → 재설정 메일 발송.
 */
@Composable
fun PasswordResetRequestScreen(
    onSent: () -> Unit,
    onBack: () -> Unit,
    vm: AuthViewModel = viewModel()
) {
    val state by vm.state.collectAsState()
    var email by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    val emailRegex = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
    val canSubmit = email.isNotBlank() && emailRegex.matches(email)

    // [코덱스 P1] submit 이후의 Success만 처리 — 이전 화면 잔여 Success로 즉시 onSent() 호출되는 race 방지
    var submitted by remember { mutableStateOf(false) }
    LaunchedEffect(state, submitted) {
        if (submitted && state is AuthViewModel.UiState.Success) {
            submitted = false
            onSent()
        }
    }

    // 화면 진입 시 state 초기화 (이전 화면 에러가 보이지 않게)
    LaunchedEffect(Unit) { vm.resetState() }

    Scaffold(
        containerColor = BgPrimary,
        topBar = {
            TopAppBar(
                title = { Text("비밀번호 찾기", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgPrimary)
            )
        }
    ) { padding ->
        // [v1.0 피드백] 키보드가 이메일 박스를 가리던 문제 해결 — verticalScroll + imePadding 추가
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(24.dp))

            // 자물쇠 아이콘 (크기 축소 96→80 — 키보드 올라와도 화면 안에 다 보이게)
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .background(LimeLight.copy(alpha = 0.4f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.LockReset,
                    contentDescription = null,
                    tint = Primary,
                    modifier = Modifier.size(48.dp)
                )
            }

            Spacer(Modifier.height(16.dp))

            Text(
                "비밀번호를 잊으셨나요?",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "가입하신 이메일을 입력해주세요.\n비밀번호 재설정 안내 메일을 보내드릴게요.",
                fontSize = 14.sp,
                color = TextSecondary,
                lineHeight = 22.sp,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(24.dp))

            // 이메일 입력
            com.dangbomcare.app.ui.components.LabeledField(label = "이메일", required = true) {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it; vm.resetError() },
                    placeholder = { Text("example@email.com") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = {
                        focusManager.clearFocus()
                        if (canSubmit) submitted = true; vm.requestPasswordReset(email)
                    }),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
            }

            if (state is AuthViewModel.UiState.Error) {
                Spacer(Modifier.height(12.dp))
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

            // [v1.0 피드백] verticalScroll에서 weight(1f) 동작 안 함 → 일정 간격으로 변경
            Spacer(Modifier.height(32.dp))

            BigButton(
                text = if (state is AuthViewModel.UiState.Loading) "발송 중..." else "재설정 메일 보내기",
                onClick = { submitted = true; vm.requestPasswordReset(email) },
                enabled = state !is AuthViewModel.UiState.Loading && canSubmit
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * [v1.0] 비밀번호 재설정 2단계 — 메일 발송 완료 안내.
 */
@Composable
fun PasswordResetSentScreen(
    onBackToLogin: () -> Unit
) {
    Scaffold(
        containerColor = BgPrimary,
        topBar = {
            TopAppBar(
                title = { Text("비밀번호 찾기", fontWeight = FontWeight.Bold) },
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

            Box(
                modifier = Modifier
                    .size(120.dp)
                    .background(Primary, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.MarkEmailRead,
                    contentDescription = null,
                    tint = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.size(64.dp)
                )
            }

            Spacer(Modifier.height(32.dp))

            Text(
                "이메일을 보냈습니다",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            Spacer(Modifier.height(20.dp))

            Text(
                "받은편지함에서 안내 메일을 확인하시고,\n메일 안의 \"비밀번호 재설정\" 링크를 눌러주세요.\n\n링크를 누르면 앱으로 돌아와\n새 비밀번호를 입력하실 수 있어요.",
                fontSize = 14.sp,
                color = TextSecondary,
                lineHeight = 22.sp,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(16.dp))

            Text(
                "메일이 보이지 않으면 스팸함도 확인해주세요.",
                fontSize = 13.sp,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.weight(1f))

            BigButton(
                text = "로그인 화면으로",
                onClick = onBackToLogin
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * [v1.0] 비밀번호 재설정 3단계 — 새 비밀번호 입력 (메일 링크 클릭 → 딥링크로 진입).
 * MainActivity가 type=recovery 딥링크를 감지 → 세션 저장 → 이 화면으로 라우팅.
 */
@Composable
fun PasswordResetNewPasswordScreen(
    onCompleted: () -> Unit,
    onBack: () -> Unit,
    vm: AuthViewModel = viewModel()
) {
    val state by vm.state.collectAsState()
    var newPassword by remember { mutableStateOf("") }
    var newPasswordConfirm by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var passwordConfirmVisible by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val confirmFocus = remember { FocusRequester() }

    val passwordMatch = newPasswordConfirm.isNotEmpty() && newPassword == newPasswordConfirm
    val canSubmit = newPassword.length >= 6 && passwordMatch

    // [코덱스 P1] submit 후 Success만 처리
    var submitted by remember { mutableStateOf(false) }
    LaunchedEffect(state, submitted) {
        if (submitted && state is AuthViewModel.UiState.Success) {
            submitted = false
            onCompleted()
        }
    }
    LaunchedEffect(Unit) { vm.resetState() }

    Scaffold(
        containerColor = BgPrimary,
        topBar = {
            TopAppBar(
                title = { Text("새 비밀번호 설정", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgPrimary)
            )
        }
    ) { padding ->
        // [v1.0 피드백] 키보드 영역 padding + scroll 추가 (회원가입 화면과 동일 패턴)
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
        ) {
            Spacer(Modifier.height(24.dp))

            Text("새 비밀번호를 입력해주세요", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Spacer(Modifier.height(6.dp))
            Text("6자 이상 입력해주세요.", fontSize = 13.sp, color = TextSecondary)

            Spacer(Modifier.height(24.dp))

            // 새 비밀번호
            val (pwState, pwMsg) = when {
                newPassword.isBlank() -> com.dangbomcare.app.ui.components.ValidationState.Hint to "6자 이상 입력해주세요."
                newPassword.length < 6 -> com.dangbomcare.app.ui.components.ValidationState.Invalid to "6자 이상 입력해주세요."
                else -> com.dangbomcare.app.ui.components.ValidationState.Valid to "사용 가능한 비밀번호입니다."
            }
            com.dangbomcare.app.ui.components.LabeledField(
                label = "새 비밀번호",
                required = true,
                validationState = pwState,
                validationMessage = pwMsg
            ) {
                OutlinedTextField(
                    value = newPassword,
                    onValueChange = { newPassword = it; vm.resetError() },
                    placeholder = { Text("6자 이상") },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { confirmFocus.requestFocus() }),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = null,
                                tint = TextSecondary
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
            }

            // 비밀번호 확인
            val (pw2State, pw2Msg) = when {
                newPasswordConfirm.isBlank() -> com.dangbomcare.app.ui.components.ValidationState.Hint to "비밀번호를 다시 입력해주세요."
                newPassword != newPasswordConfirm -> com.dangbomcare.app.ui.components.ValidationState.Invalid to "비밀번호가 일치하지 않습니다."
                else -> com.dangbomcare.app.ui.components.ValidationState.Valid to "비밀번호가 일치합니다."
            }
            com.dangbomcare.app.ui.components.LabeledField(
                label = "비밀번호 확인",
                required = true,
                validationState = pw2State,
                validationMessage = pw2Msg
            ) {
                OutlinedTextField(
                    value = newPasswordConfirm,
                    onValueChange = { newPasswordConfirm = it; vm.resetError() },
                    placeholder = { Text("비밀번호 다시 입력") },
                    singleLine = true,
                    visualTransformation = if (passwordConfirmVisible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    trailingIcon = {
                        IconButton(onClick = { passwordConfirmVisible = !passwordConfirmVisible }) {
                            Icon(
                                if (passwordConfirmVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = null,
                                tint = TextSecondary
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().focusRequester(confirmFocus),
                    shape = RoundedCornerShape(12.dp)
                )
            }

            if (state is AuthViewModel.UiState.Error) {
                Spacer(Modifier.height(12.dp))
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

            Spacer(Modifier.height(32.dp))

            BigButton(
                text = if (state is AuthViewModel.UiState.Loading) "저장 중..." else "비밀번호 저장",
                onClick = { if (canSubmit) submitted = true; vm.updatePassword(newPassword) },
                enabled = state !is AuthViewModel.UiState.Loading && canSubmit
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}
