@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.dangbomcare.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dangbomcare.app.R
import com.dangbomcare.app.ui.theme.*

/**
 * Welcome 화면 — 진짜 당봄한의원 로고 + 당봄케어 브랜드 + 강한 멘트.
 * 카카오/Google는 여기서 바로 Custom Tabs OAuth 시작 (로그인 화면 안 거침).
 */
@Composable
fun WelcomeScreen(
    onEmailLogin: () -> Unit,
    onEmailSignup: () -> Unit,
    onLoginSuccess: () -> Unit = {},
    onKakaoLogin: () -> Unit = {}  // 호환성 (사용 X)
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    // OAuth 콜백 결과 관찰 — MainActivity가 발행
    val mainActivity = com.dangbomcare.app.MainActivity.instance
    val oauthEvent by (mainActivity?.oauthEvent
        ?: remember { kotlinx.coroutines.flow.MutableStateFlow<com.dangbomcare.app.MainActivity.OAuthEvent?>(null) })
        .collectAsState()
    var oauthError by remember { mutableStateOf<String?>(null) }
    // OAuth 성공 → onLoginSuccess 호출 후 다음 화면 결정까지 로딩 표시
    var loginProcessing by remember { mutableStateOf(false) }

    LaunchedEffect(oauthEvent) {
        when (val e = oauthEvent) {
            is com.dangbomcare.app.MainActivity.OAuthEvent.Success -> {
                mainActivity?.consumeOAuthEvent()
                loginProcessing = true  // 로딩 표시 시작
                onLoginSuccess()
            }
            is com.dangbomcare.app.MainActivity.OAuthEvent.Error -> {
                oauthError = e.message
                mainActivity?.consumeOAuthEvent()
            }
            is com.dangbomcare.app.MainActivity.OAuthEvent.PasswordResetReady -> {
                // 글로벌 NavGraph 핸들러가 처리 — 여기서는 no-op
            }
            null -> Unit
        }
    }

    // OAuth 처리 중 풀스크린 로딩 오버레이 (Welcome 화면 2초 깜빡임 방지)
    if (loginProcessing) {
        androidx.compose.material3.Scaffold(containerColor = BgPrimary) { padding ->
            androidx.compose.foundation.layout.Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
            ) {
                androidx.compose.material3.CircularProgressIndicator(color = Primary, strokeWidth = 3.dp)
                androidx.compose.foundation.layout.Spacer(Modifier.height(20.dp))
                Text("로그인 처리 중...", fontSize = 14.sp, color = TextSecondary)
            }
        }
        return
    }

    Scaffold(containerColor = BgPrimary) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 상단 여백
            Spacer(Modifier.weight(0.8f))

            // 진짜 당봄한의원 로고 (PNG)
            Image(
                painter = painterResource(id = R.drawable.dangbom_logo),
                contentDescription = "당봄한의원",
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .height(80.dp),
                contentScale = ContentScale.Fit
            )

            // [260730] 로고와 메인 메시지 사이를 유연 여백으로 — 메시지가 로고에 붙지 않고
            //   로고와 로그인 버튼의 '중간쯤'에 오도록 위/아래 weight 를 같게 둔다.
            Spacer(Modifier.weight(1f))

            // 임팩트 강한 메인 메시지
            Text(
                "당뇨, 졸업하세요",
                fontSize = 32.sp,
                fontWeight = FontWeight.Black,
                color = TextPrimary,
                textAlign = TextAlign.Center,
                letterSpacing = (-1).sp
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "당봄한의원이 매일 함께해요",
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                color = Primary,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.weight(1f))

            // OAuth 에러 메시지
            oauthError?.let { msg ->
                Surface(
                    color = ErrorRed.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    Text(
                        "⚠️ $msg",
                        color = ErrorRed,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            // [브랜딩 260730] 카카오/Google 버튼은 공용 컴포넌트로 통일했다.
            //   이전엔 이 화면이 카카오 옐로 위에 말풍선 이모지(💬), Google 은 브랜드 블루로
            //   글자 'G' 를 직접 그린 상태였다 — 둘 다 로고 모방이라 각 사 가이드 위반이다.
            //   규정(문구/색/마크/radius)은 ui/components/SocialLoginButtons.kt 한 곳에만 있고
            //   화면은 크기만 넘긴다. LoginScreen 과 갈라져서 한쪽만 고쳐지는 일을 막기 위함.
            //   시니어 대상이라 이 화면은 56dp / 16sp 로 크게 쓴다(마크 크기는 비율로 자동 계산).
            com.dangbomcare.app.ui.components.KakaoLoginButton(
                onClick = {
                    oauthError = null
                    com.dangbomcare.app.data.auth.SocialLoginHelper.open(
                        context,
                        com.dangbomcare.app.data.auth.SocialLoginHelper.Provider.KAKAO
                    )
                },
                height = 56.dp,
                fontSize = 16.sp,
            )
            Spacer(Modifier.height(8.dp))

            com.dangbomcare.app.ui.components.GoogleLoginButton(
                onClick = {
                    oauthError = null
                    com.dangbomcare.app.data.auth.SocialLoginHelper.open(
                        context,
                        com.dangbomcare.app.data.auth.SocialLoginHelper.Provider.GOOGLE
                    )
                },
                height = 56.dp,
                fontSize = 16.sp,
            )
            Spacer(Modifier.height(10.dp))

            // 구분선
            Row(verticalAlignment = Alignment.CenterVertically) {
                HorizontalDivider(modifier = Modifier.weight(1f), color = Divider)
                Text(" 또는 ", fontSize = 11.sp, color = TextSecondary, modifier = Modifier.padding(horizontal = 8.dp))
                HorizontalDivider(modifier = Modifier.weight(1f), color = Divider)
            }
            Spacer(Modifier.height(10.dp))

            // 이메일 로그인 (보조)
            OutlinedButton(
                onClick = onEmailLogin,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Divider)
            ) {
                Text("이메일 로그인", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextSecondary)
            }
            Spacer(Modifier.height(4.dp))

            // 회원가입 링크
            TextButton(onClick = onEmailSignup, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "처음이신가요? 회원가입",
                    fontSize = 14.sp,
                    color = Primary,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}
