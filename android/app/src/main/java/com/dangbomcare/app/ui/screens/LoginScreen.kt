@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.dangbomcare.app.ui.screens

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dangbomcare.app.data.ServiceLocator
import com.dangbomcare.app.data.api.UserConsentsRequest
import com.dangbomcare.app.ui.components.BigButton
import com.dangbomcare.app.ui.theme.*
import com.dangbomcare.app.ui.viewmodel.AuthViewModel
import kotlinx.coroutines.launch

/**
 * 로그인 화면 — 한 화면 fit, 드래그 없음.
 */
@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    onGotoSignup: () -> Unit,
    onForgotPassword: () -> Unit,
    initialEmail: String = "",
    onBack: () -> Unit = onGotoSignup,  // 호환성을 위한 기본값
    vm: AuthViewModel = viewModel()
) {
    val state by vm.state.collectAsState()
    // [260729 통합검증 P2] 이메일만 rememberSaveable — 회전·프로세스 사망 후 재입력 방지.
    //   시니어 사용자에게 이메일 재입력은 실질적인 이탈 요인이다.
    //   ⚠️ 비밀번호는 의도적으로 `remember` 그대로 둔다. rememberSaveable 은 값을 저장된
    //     인스턴스 상태(Bundle)에 직렬화하므로 평문 비밀번호가 프로세스 밖으로 나간다.
    var email by rememberSaveable { mutableStateOf(initialEmail) }
    var password by remember { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // OAuth 콜백 결과 관찰 — MainActivity가 발행
    val mainActivity = com.dangbomcare.app.MainActivity.instance
    val oauthEvent by (mainActivity?.oauthEvent
        ?: remember { kotlinx.coroutines.flow.MutableStateFlow<com.dangbomcare.app.MainActivity.OAuthEvent?>(null) })
        .collectAsState()

    LaunchedEffect(oauthEvent) {
        when (val e = oauthEvent) {
            is com.dangbomcare.app.MainActivity.OAuthEvent.Success -> {
                mainActivity?.consumeOAuthEvent()
                onLoginSuccess()
            }
            is com.dangbomcare.app.MainActivity.OAuthEvent.Error -> {
                vm.setExternalError(e.message)
                mainActivity?.consumeOAuthEvent()
            }
            is com.dangbomcare.app.MainActivity.OAuthEvent.PasswordResetReady -> {
                // 글로벌 NavGraph 핸들러가 처리 — 여기서는 no-op
            }
            null -> Unit
        }
    }

    // 삼성패스 등 autofill 감지 — 둘 다 한 번에 채워지는 패턴 (한 글자씩 입력은 제외)
    // 사용자가 직접 입력 중일 때는 키패드 유지
    var prevPassword by remember { mutableStateOf("") }
    LaunchedEffect(email, password) {
        val isAutofill = email.isNotBlank() &&
                password.length >= 6 &&
                (password.length - prevPassword.length >= 4)  // 한 번에 4글자 이상 들어오면 autofill로 판단
        prevPassword = password
        if (isAutofill) {
            keyboardController?.hide()
            focusManager.clearFocus()
        }
    }

    LaunchedEffect(state) {
        if (state is AuthViewModel.UiState.Success) onLoginSuccess()
    }

    Scaffold(
        containerColor = BgPrimary,
        topBar = {
            TopAppBar(
                title = { Text("로그인", fontWeight = FontWeight.Bold) },
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
            Spacer(Modifier.weight(0.8f))

            Text("당봄케어", fontSize = 32.sp, fontWeight = FontWeight.Black, color = Primary)
            Spacer(Modifier.height(4.dp))
            Text("환자 전용 당뇨 관리", fontSize = 13.sp, color = TextSecondary)

            Spacer(Modifier.height(24.dp))

            // 이메일 — 형식 검증 (입력값 있을 때만 표시 — 로그인 화면은 가입과 달리 안내 최소화)
            val emailRegex = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
            val (emailState, emailMsg) = when {
                email.isBlank() -> com.dangbomcare.app.ui.components.ValidationState.Empty to ""
                !emailRegex.matches(email) -> com.dangbomcare.app.ui.components.ValidationState.Invalid to "이메일 형식이 아닙니다."
                else -> com.dangbomcare.app.ui.components.ValidationState.Empty to ""
            }
            com.dangbomcare.app.ui.components.LabeledField(
                label = "이메일",
                validationState = emailState,
                validationMessage = emailMsg
            ) {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it; vm.resetError() },
                    placeholder = { Text("example@email.com") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
            }

            com.dangbomcare.app.ui.components.LabeledField(label = "비밀번호") {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; vm.resetError() },
                    placeholder = { Text("비밀번호 입력") },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        focusManager.clearFocus()
                        keyboardController?.hide()
                    }),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (passwordVisible) "비밀번호 숨기기" else "비밀번호 보기",
                                tint = TextSecondary
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
            }

            // 에러 메시지 (서버 에러 — 한 번에 강조)
            if (state is AuthViewModel.UiState.Error) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = ErrorRed.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "⚠️ ${(state as AuthViewModel.UiState.Error).message}",
                        color = ErrorRed,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            BigButton(
                text = if (state is AuthViewModel.UiState.Loading) "로그인 중..." else "로그인",
                onClick = { vm.signIn(email, password) },
                enabled = state !is AuthViewModel.UiState.Loading && email.isNotBlank() && password.isNotBlank()
            )
            Spacer(Modifier.height(8.dp))
            // FlowRow — 큰 글씨(fontScale 2.0)에서 한 줄에 안 들어가면 '비밀번호 찾기'가
            //   글자 단위로 쪼개지는 대신 통째로 다음 줄로 내려감 (평소엔 기존과 동일 한 줄)
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalArrangement = Arrangement.Center,
            ) {
                TextButton(onClick = onGotoSignup) {
                    Text("처음이신가요? 회원가입", fontSize = 13.sp, color = Primary, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
                Text("|", color = Divider, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterVertically))
                TextButton(onClick = onForgotPassword) {
                    Text("비밀번호 찾기", fontSize = 13.sp, color = Primary, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }

            Spacer(Modifier.height(20.dp))

            // ─── 소셜 로그인 (Google + Kakao) ───
            // [v1.0] 시니어 환자 친화 UX를 위해 release에도 노출.
            // 보안: B2B2C 의료 앱 + 의사 처방 진입이라 토큰 탈취 위험 낮음.
            // [v1.1 예정] HTTPS App Link + PKCE로 전환 후 보안 강화.
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.weight(1f).height(1.dp).background(Divider))
                Text(" 또는 ", fontSize = 11.sp, color = TextSecondary)
                Box(modifier = Modifier.weight(1f).height(1.dp).background(Divider))
            }
            Spacer(Modifier.height(12.dp))

            val context = androidx.compose.ui.platform.LocalContext.current
            // [브랜딩] Google 공식 "G" 마크.
            //   에셋 출처: com.google.android.gms:play-services-base (firebase-messaging/analytics가
            //   transitive 로 끌어옴) → 별도 다운로드/추가 불필요, 이미 APK 에 포함돼 있다.
            //   AGP 9 는 non-transitive R 이므로 앱 R 이 아니라 **라이브러리 자기 네임스페이스의 R**
            //   (com.google.android.gms.base.R) 로 참조해야 컴파일된다. 앱 R 로 쓰면 unresolved reference.
            // [브랜딩 260730] 규정(문구/색/마크/radius)은 ui/components/SocialLoginButtons.kt 한 곳에.
            //   이 화면은 보조 진입점이라 52dp / 14sp 로 쓴다(진입점 WelcomeScreen 은 56dp / 16sp).
            com.dangbomcare.app.ui.components.GoogleLoginButton(
                onClick = {
                    com.dangbomcare.app.data.auth.SocialLoginHelper.open(
                        context,
                        com.dangbomcare.app.data.auth.SocialLoginHelper.Provider.GOOGLE
                    )
                }
            )
            Spacer(Modifier.height(8.dp))
            com.dangbomcare.app.ui.components.KakaoLoginButton(
                onClick = {
                    com.dangbomcare.app.data.auth.SocialLoginHelper.open(
                        context,
                        com.dangbomcare.app.data.auth.SocialLoginHelper.Provider.KAKAO
                    )
                }
            )

            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * 회원가입 화면 — 이메일 + 비밀번호만. 동의서는 다음 화면(ConsentsScreen)에서 받음.
 */
@Composable
fun SignupScreen(
    onSignupSuccess: () -> Unit,
    onEmailConfirmationRequired: (email: String) -> Unit,
    onGotoLoginWithEmail: (email: String) -> Unit,
    onBackToLogin: () -> Unit,
    vm: AuthViewModel = viewModel()
) {
    val state by vm.state.collectAsState()
    // [260729 통합검증 P2] 로그인 화면과 같은 기준 — 이메일만 saveable, 비밀번호 2개는 remember 유지.
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordConfirm by remember { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var passwordConfirmVisible by rememberSaveable { mutableStateOf(false) }

    // [v1.0 피드백] 키보드가 비밀번호 박스를 가리는 문제 해결
    // FocusRequester로 비밀번호 / 비밀번호 확인 필드 명시적 포커스 제어 + scroll
    val focusManager = LocalFocusManager.current
    val passwordFocus = remember { FocusRequester() }
    val passwordConfirmFocus = remember { FocusRequester() }

    val passwordMatch = passwordConfirm.isNotEmpty() && password == passwordConfirm

    // [v1.0] 이메일 실시간 중복 체크
    //   - 사용자가 이메일 입력 → 800ms debounce → 백엔드 /api/auth/check-email 호출
    //   - exists=true → "이미 가입된 이메일입니다" (빨강) + 로그인 화면 이동 버튼
    //   - exists=false → "사용 가능한 이메일입니다" (초록)
    var emailDuplicateState by remember { mutableStateOf<Boolean?>(null) }  // null=미확인, true=중복, false=사용가능
    var emailChecking by remember { mutableStateOf(false) }
    val emailRegex = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")

    LaunchedEffect(email) {
        emailDuplicateState = null
        if (email.isNotBlank() && emailRegex.matches(email)) {
            emailChecking = true
            kotlinx.coroutines.delay(700)  // debounce
            val capturedEmail = email
            try {
                val res = com.dangbomcare.app.data.ServiceLocator.apiService.checkEmail(capturedEmail.trim())
                if (capturedEmail == email && res.isSuccessful) {
                    emailDuplicateState = res.body()?.exists == true
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // [코덱스 P1] 취소는 상위로 다시 throw — 다른 job 상태 덮어쓰지 X
                throw e
            } catch (_: Exception) {
                if (capturedEmail == email) emailDuplicateState = null
            } finally {
                // [코덱스 P1] 늦은 응답이 새 입력의 checking 상태 덮어쓰지 X
                if (capturedEmail == email) emailChecking = false
            }
        } else {
            emailChecking = false
        }
    }

    val canSubmit = email.isNotBlank() && password.length >= 6 && passwordMatch &&
        emailDuplicateState != true  // 중복인 경우 가입 불가

    // [v1.0] 회원가입 흐름:
    // - Success → onSignupSuccess() (이메일 인증 OFF 또는 debug auto-confirm)
    // - EmailConfirmationRequired → EmailVerifyWaitScreen으로 이동 (password 전달)
    // - Error → 화면 내 에러 표시 (특히 EMAIL_ALREADY_EXISTS는 빨간 에러 박스)
    LaunchedEffect(state) {
        when (val s = state) {
            is AuthViewModel.UiState.Success -> onSignupSuccess()
            is AuthViewModel.UiState.EmailConfirmationRequired -> {
                vm.resetError()
                onEmailConfirmationRequired(s.email)
            }
            else -> Unit
        }
    }

    Scaffold(
        containerColor = BgPrimary,
        topBar = {
            TopAppBar(
                title = { Text("회원가입", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBackToLogin) {
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
                // [v1.0 피드백] 키보드 영역만큼 padding + scroll 가능 → 가려지지 않음
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            // 진행 단계 표시 (1/2 — 가입 → 동의)
            com.dangbomcare.app.ui.components.StepIndicator(current = 1, total = 2)
            Spacer(Modifier.height(20.dp))

            Text("회원 정보를 입력해 주세요", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Spacer(Modifier.height(6.dp))
            Text(
                "가입 후 인증 이메일을 보내드립니다.\n받은편지함에서 인증 링크를 클릭하면 완료됩니다.",
                fontSize = 13.sp,
                color = TextSecondary,
                lineHeight = 20.sp
            )
            Spacer(Modifier.height(20.dp))

            // [v1.0] 이메일 — 형식 검증 + 실시간 중복 체크
            val (emailState, emailMsg) = when {
                email.isBlank() -> com.dangbomcare.app.ui.components.ValidationState.Hint to "예: example@email.com"
                !emailRegex.matches(email) -> com.dangbomcare.app.ui.components.ValidationState.Invalid to "올바른 이메일 형식이 아닙니다."
                emailChecking -> com.dangbomcare.app.ui.components.ValidationState.Hint to "확인 중..."
                emailDuplicateState == true -> com.dangbomcare.app.ui.components.ValidationState.Invalid to "이미 가입된 이메일입니다."
                emailDuplicateState == false -> com.dangbomcare.app.ui.components.ValidationState.Valid to "사용 가능한 이메일입니다."
                else -> com.dangbomcare.app.ui.components.ValidationState.Hint to "예: example@email.com"
            }
            com.dangbomcare.app.ui.components.LabeledField(
                label = "이메일",
                required = true,
                validationState = emailState,
                validationMessage = emailMsg
            ) {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it; vm.resetError() },
                    placeholder = { Text("example@email.com") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    // [v1.0 피드백] 다음 누르면 비밀번호로 자동 포커스 + 스크롤
                    keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
            }

            // [v1.0] 이미 가입된 이메일 → "로그인 화면으로 가기" 버튼 (인라인 검증과 별개로 즉시 액션 가능하도록)
            if (emailDuplicateState == true) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        vm.resetState()
                        onGotoLoginWithEmail(email.trim())
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Primary)
                ) {
                    Text("로그인 화면으로 가기", color = Primary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }

            // 비밀번호 — 6자 이상 검증
            val (pwState, pwMsg) = when {
                password.isBlank() -> com.dangbomcare.app.ui.components.ValidationState.Hint to "6자 이상 입력해주세요."
                password.length < 6 -> com.dangbomcare.app.ui.components.ValidationState.Invalid to "6자 이상 입력해주세요."
                else -> com.dangbomcare.app.ui.components.ValidationState.Valid to "사용 가능한 비밀번호입니다."
            }
            com.dangbomcare.app.ui.components.LabeledField(
                label = "비밀번호",
                required = true,
                validationState = pwState,
                validationMessage = pwMsg
            ) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; vm.resetError() },
                    placeholder = { Text("6자 이상") },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                    // [v1.0 피드백] 다음 누르면 비밀번호 확인으로 자동 포커스 + 스크롤
                    keyboardActions = KeyboardActions(onNext = { passwordConfirmFocus.requestFocus() }),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (passwordVisible) "비밀번호 숨기기" else "비밀번호 보기",
                                tint = TextSecondary
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().focusRequester(passwordFocus),
                    shape = RoundedCornerShape(12.dp)
                )
            }

            // 비밀번호 확인 — 일치 검증
            val (pw2State, pw2Msg) = when {
                passwordConfirm.isBlank() -> com.dangbomcare.app.ui.components.ValidationState.Hint to "비밀번호를 다시 입력해주세요."
                password != passwordConfirm -> com.dangbomcare.app.ui.components.ValidationState.Invalid to "비밀번호가 일치하지 않습니다."
                else -> com.dangbomcare.app.ui.components.ValidationState.Valid to "비밀번호가 일치합니다."
            }
            com.dangbomcare.app.ui.components.LabeledField(
                label = "비밀번호 확인",
                required = true,
                validationState = pw2State,
                validationMessage = pw2Msg
            ) {
                OutlinedTextField(
                    value = passwordConfirm,
                    onValueChange = { passwordConfirm = it; vm.resetError() },
                    placeholder = { Text("비밀번호 다시 입력") },
                    singleLine = true,
                    visualTransformation = if (passwordConfirmVisible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    trailingIcon = {
                        IconButton(onClick = { passwordConfirmVisible = !passwordConfirmVisible }) {
                            Icon(
                                if (passwordConfirmVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (passwordConfirmVisible) "비밀번호 숨기기" else "비밀번호 보기",
                                tint = TextSecondary
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().focusRequester(passwordConfirmFocus),
                    shape = RoundedCornerShape(12.dp)
                )
            }

            // [v1.0] 일반 에러 박스 (EMAIL_ALREADY_EXISTS는 인라인으로 처리되므로 제외)
            if (state is AuthViewModel.UiState.Error) {
                val errState = state as AuthViewModel.UiState.Error
                if (errState.code != "EMAIL_ALREADY_EXISTS") {
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        color = ErrorRed.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "⚠️ ${errState.message}",
                            color = ErrorRed,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }

            // [v1.0 피드백] verticalScroll에서 weight(1f) 동작 안 함 → 일정 간격으로 변경
            Spacer(Modifier.height(32.dp))

            BigButton(
                text = if (state is AuthViewModel.UiState.Loading) "가입 중..." else "회원가입 및 인증 이메일 받기",
                onClick = {
                    if (canSubmit) vm.signUp(email, password)
                },
                enabled = state !is AuthViewModel.UiState.Loading && canSubmit
            )
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onBackToLogin, modifier = Modifier.fillMaxWidth()) {
                Text("이미 계정이 있으신가요? 로그인", fontSize = 13.sp, color = Primary, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
