package com.dangbomcare.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dangbomcare.app.data.ServiceLocator
import com.dangbomcare.app.data.repo.ApiResult
import com.dangbomcare.app.ui.theme.*

/**
 * Splash 분기:
 * 1. 로그인 안 됨 → Welcome
 * 2. 로그인 됨 + 토큰 유효 + 온보딩 완료 → 홈
 * 3. 로그인 됨 + 토큰 만료 → refresh 시도 → 성공 시 위 분기
 * 4. 로그인 됨 + 온보딩 미완료 → 온보딩 이어서 (단계별 정확 라우팅)
 */
@Composable
fun SplashScreen(
    onAuthenticated: () -> Unit,
    onNotAuthenticated: () -> Unit,
    onNeedsConsents: () -> Unit,
    onNeedsAccountInfo: () -> Unit,
    onNeedsOnboarding: () -> Unit,  // [v1.0 버그픽스] 약관+계정정보 OK + 온보딩 미완료 → ONBOARD_BASIC (키/체중)
    onForceUpdate: () -> Unit = {}  // [260904] 서버 min_version_code 미만 → ForceUpdateScreen
) {
    LaunchedEffect(Unit) {
        val authRepo = ServiceLocator.authRepository
        val userRepo = ServiceLocator.userRepository

        // [260904] 강제 업데이트 게이트 — 로그인 판정보다 먼저. 서버 app_config.min_version_code 가
        //   이 빌드의 versionCode 보다 크면 업데이트 화면에서 멈춘다.
        //   fail-open: 응답 실패·4초 타임아웃·구버전 서버(필드 없음 → 0)면 그냥 정상 진입.
        //   (업데이트 게이트 때문에 오프라인/서버 장애 시 앱이 먹통이 되면 안 된다)
        val policy = kotlinx.coroutines.withTimeoutOrNull(4_000L) { userRepo.versionPolicy() }
        if (policy is ApiResult.Success && policy.data.minVersionCode > com.dangbomcare.app.BuildConfig.VERSION_CODE) {
            AppUpdateGate.pending = policy.data
            onForceUpdate()
            return@LaunchedEffect
        }

        val loggedIn = authRepo.isLoggedIn()
        if (!loggedIn) {
            onNotAuthenticated()
            return@LaunchedEffect
        }

        // 1차 시도: 현재 토큰으로 사용자 정보 조회
        var userRes = userRepo.getMe()

        // [260726 버그픽스] 404(NOT_FOUND)는 인증 실패가 아니라 "서버에 아직 사용자 행이 없다"는 뜻.
        //   신규 가입 직후(행 생성과 경합) 또는 동의 단계에서 앱을 끈 경우에 발생한다.
        //   기존에는 이걸 세션 오류로 보고 tryRefresh() → 실패 시 signOut() 으로 토큰까지 지워
        //   아무 안내 없이 로그인 화면으로 튕겼다. → 동의 화면부터 이어가도록 처리한다.
        //   (동의 저장 시 백엔드가 사용자 행을 생성하므로 이후 흐름은 정상 복구된다.)
        val notFoundErr = (userRes as? ApiResult.Error)
            ?.takeIf { it.code == "NOT_FOUND" || it.code == "HTTP_404" }
        if (notFoundErr != null) {
            onNeedsConsents()
            return@LaunchedEffect
        }

        // 401 등 실패 시 → refresh_token으로 갱신 시도
        if (userRes is ApiResult.Error) {
            // [QA fix] 네트워크 오류(오프라인/타임아웃)는 인증 실패가 아님 → 토큰 삭제 금지.
            //   기존엔 getMe NETWORK 실패 → tryRefresh()도 네트워크라 false → signOut()이 토큰을 지워
            //   신호 약한 곳에서 앱 열 때마다 강제 로그아웃되던 문제. 네트워크 오류면 토큰 유지하고
            //   라우팅만(다음 실행에 네트워크 정상이면 자동 진입). 진짜 만료(HTTP_401 등)는 아래 refresh→signOut 유지.
            val errCode = userRes.code
            if (errCode == "NETWORK") {
                onNotAuthenticated()
                return@LaunchedEffect
            }
            val refreshed = authRepo.tryRefresh()
            if (refreshed) {
                userRes = userRepo.getMe()  // 새 토큰으로 재시도
            } else {
                // refresh도 실패 → 재로그인 필요
                authRepo.signOut()
                onNotAuthenticated()
                return@LaunchedEffect
            }
        }
        // [코덱스 P1] refresh 후에도 5xx/NETWORK 실패면 세션 무효화 X — 단순 라우팅만 (재시도 유도)
        // 401은 RetrofitFactory가 이미 notifySessionExpired() → NavGraph가 WELCOME으로 이동 처리
        if (userRes is ApiResult.Error) {
            // 일시 오류 — signOut X, 단순 WELCOME으로 (사용자가 다시 로그인 시도 가능)
            onNotAuthenticated()
            return@LaunchedEffect
        }

        // [v1.0 피드백 + 버그픽스] 온보딩 단계별 라우팅
        //   • 약관 미동의 → CONSENTS
        //   • 약관 OK, 이름/생년월일 없음 → ACCOUNT_INFO
        //   • 약관+계정정보 OK, 온보딩 미완료 → ONBOARD_BASIC (키/체중부터)
        //   • 전부 완료 → HOME
        //   [v1.0] onboarding_completed 플래그를 누락해서 ACCOUNT_INFO만 끝낸 사용자가
        //   재로그인 시 홈으로 직행하던 버그 수정.
        val user = (userRes as? ApiResult.Success)?.data
        if (user == null) {
            // getMe 두 번 다 실패 → 안전하게 재로그인 유도
            onNotAuthenticated()
            return@LaunchedEffect
        }
        // [260726] 필수 동의 4종 모두 확인 (NavGraph 의 postLoginRouting 과 동일 기준).
        //   thirdParty(개인정보 및 민감정보 제3자 제공)가 빠져 있어 기존 가입자가 4번째 동의 없이 통과하던 문제 수정.
        val hasConsents = user.consents?.terms == true && user.consents.privacy &&
            user.consents.sensitiveData && user.consents.thirdParty
        val hasAccountInfo = !user.name.isNullOrBlank() && !user.birthDate.isNullOrBlank()
        when {
            !hasConsents -> onNeedsConsents()
            !hasAccountInfo -> onNeedsAccountInfo()
            !user.onboardingCompleted -> onNeedsOnboarding()
            else -> onAuthenticated()
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(BgPrimary),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("당봄케어", fontSize = 48.sp, fontWeight = FontWeight.Black, color = Primary)
            Spacer(Modifier.height(8.dp))
            Text("당봄한의원 환자 전용", fontSize = 14.sp, color = TextSecondary, textAlign = TextAlign.Center)
        }
    }
}
