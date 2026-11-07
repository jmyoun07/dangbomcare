package com.dangbomcare.app.ui.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.dangbomcare.app.data.ServiceLocator
import com.dangbomcare.app.data.repo.ApiResult
import com.dangbomcare.app.ui.components.BottomNavBar
import com.dangbomcare.app.ui.screens.*

/**
 * 라우팅 정책:
 * - SPLASH: 앱 시작 시 분기 (로그인+온보딩 완료 → HOME / 그 외 → WELCOME)
 * - 로그인 후: 온보딩 완료 여부 체크 → HOME 또는 ONBOARDING
 * - 온보딩 중 뒤로가기: WELCOME까지 자연스럽게 pop
 */
@Composable
fun DangbomCareNavGraph() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val currentTab = MainTab.fromRoute(currentRoute)
    val showBottomNav = currentTab != null

    // 같은 탭을 다시 누르면 최상단으로 스크롤 — 탭별 trigger
    var homeScrollToTop by remember { mutableStateOf(0) }
    var recordsScrollToTop by remember { mutableStateOf(0) }
    var reportsScrollToTop by remember { mutableStateOf(0) }
    var settingsScrollToTop by remember { mutableStateOf(0) }

    Scaffold(
        bottomBar = {
            if (showBottomNav) {
                BottomNavBar(
                    currentTab = currentTab,
                    onTabSelected = { tab ->
                        if (currentTab == tab) {
                            // 동일 탭 재클릭 → 해당 화면 최상단 스크롤 트리거
                            when (tab) {
                                MainTab.HOME -> homeScrollToTop += 1
                                MainTab.RECORDS -> recordsScrollToTop += 1
                                MainTab.REPORTS -> reportsScrollToTop += 1
                                MainTab.SETTINGS -> settingsScrollToTop += 1
                            }
                        } else {
                            navController.navigate(tab.route) {
                                popUpTo(Routes.TAB_HOME) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    },
                    onFabClick = { navController.navigate(Routes.MORNING_INPUT) }
                )
            }
        }
    ) { padding ->
        NavHostInternal(
            navController = navController,
            padding = padding,
            showBottomNav = showBottomNav,
            homeScrollToTop = homeScrollToTop,
            recordsScrollToTop = recordsScrollToTop,
            reportsScrollToTop = reportsScrollToTop,
            settingsScrollToTop = settingsScrollToTop
        )
    }
}

@Composable
private fun NavHostInternal(
    navController: NavHostController,
    padding: PaddingValues,
    showBottomNav: Boolean,
    homeScrollToTop: Int = 0,
    recordsScrollToTop: Int = 0,
    reportsScrollToTop: Int = 0,
    settingsScrollToTop: Int = 0
) {
    // 로그인 후 onboarding 분기 처리용
    var postLoginRouting by remember { mutableStateOf(false) }
    // [P1 260727] 설정 하이드레이션용 — Activity 누수 방지로 applicationContext 사용
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext

    // [P1] 401 refresh 실패 → 전역 세션 만료 이벤트 collect → WELCOME으로 강제 이동
    LaunchedEffect(Unit) {
        ServiceLocator.sessionExpired.collect {
            // [260726] 이 경로는 토큰만 지우고 로컬 데이터를 남겼다.
            //   dangbom_input_draft(공복혈당 포함) 등은 기기 전역이라 user 스코프가 없어서,
            //   A 세션 만료 → B 로그인 시 B의 입력 폼에 A의 공복혈당이 프리필됐다.
            //   → 화면 이동 전에 signOut()으로 사용자별 로컬 데이터까지 정리한다.
            runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    ServiceLocator.authRepository.signOut()
                }
            }
            navController.navigate(Routes.WELCOME) {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    // [v1.0] 비밀번호 재설정 메일 클릭 → 딥링크로 진입 → PASSWORD_RESET_NEW 화면으로 이동
    val mainActivity = com.dangbomcare.app.MainActivity.instance
    LaunchedEffect(mainActivity) {
        mainActivity?.oauthEvent?.collect { event ->
            if (event is com.dangbomcare.app.MainActivity.OAuthEvent.PasswordResetReady) {
                mainActivity.consumeOAuthEvent()
                navController.navigate(Routes.PASSWORD_RESET_NEW) {
                    // [코덱스 P1] popUpTo(0)는 graph에 destination id 0 없으면 crash → start destination 사용
                    popUpTo(navController.graph.startDestinationId) { inclusive = true }
                    launchSingleTop = true
                }
            }
        }
    }

    LaunchedEffect(postLoginRouting) {
        if (postLoginRouting) {
            val res = fetchMeWithRetry()
            // [v1.0 피드백 + 버그픽스] 온보딩 단계별 분기 — 약관/계정정보/온보딩 완료 여부에 따라 정확히 이어서
            val target = when (res) {
                is ApiResult.Success -> {
                    val user = res.data
                    // [FCM 갭 fix] 로그인 직후 FCM 토큰 등록 — 신규 설치(패키지명 변경)로 첫 로그인한 사용자
                    //   푸시 누락 방지. getMe 성공 후라 백엔드에 유저 row 존재 확정(404 회피).
                    com.dangbomcare.app.notification.registerFcmTokenIfLoggedIn()
                    // [P1 260727] 공복혈당 측정·저녁 마감·취침 시각의 정본은 서버 user_settings 다.
                    //   로그아웃 시 로컬 알람/생활패턴 prefs 가 clear 되는데 재로그인 시 온보딩은
                    //   스킵되므로(onboardingCompleted=true), 여기서 서버 값으로 로컬을 복원하지 않으면
                    //   로컬을 보는 화면(알림 발화·회고 라벨·리포트 조언)과 서버가 만든 AI 미션이
                    //   한 화면에서 서로 다른 시각을 표시한다.
                    //   settings 가 null(=user_settings 행이 아직 없는 신규 사용자)이면 로컬 기본값 유지.
                    //   화면 이동 전에 수행해 첫 렌더부터 올바른 시각이 보이게 한다.
                    user.settings?.let { s ->
                        runCatching {
                            com.dangbomcare.app.notification.AlarmScheduler.hydrateFromServer(
                                context = appContext,
                                glucoseCheckTime = s.glucoseCheckTime,
                                dinnerDeadlineTime = s.dinnerDeadlineTime,
                                bedtime = s.bedtime
                            )
                        }.onFailure {
                            android.util.Log.w("NavGraph", "설정 하이드레이션 실패", it)
                        }
                    }
                    // [260726] 필수 동의 4종 모두 확인. thirdParty(개인정보 및 민감정보 제3자 제공)가
                    //   빠져 있어, 3종만 동의한 기존 가입자가 4번째 동의를 받지 않고 통과하던 문제 수정.
                    //   → third_party=false 인 사용자는 동의 화면으로 다시 유도되어 4번째 동의를 받는다.
                    val hasConsents = user.consents?.terms == true &&
                        user.consents.privacy && user.consents.sensitiveData &&
                        user.consents.thirdParty
                    val hasAccountInfo = !user.name.isNullOrBlank() && !user.birthDate.isNullOrBlank()
                    when {
                        !hasConsents -> Routes.CONSENTS
                        !hasAccountInfo -> Routes.ACCOUNT_INFO
                        // [v1.0 버그픽스] onboardingCompleted 누락 → 키/체중부터 이어서
                        !user.onboardingCompleted -> Routes.ONBOARD_BASIC
                        else -> Routes.TAB_HOME
                    }
                }
                is ApiResult.Error -> {
                    // [260726 버그픽스] 404(NOT_FOUND)는 세션 문제가 아니라 "아직 서버에 사용자 행이 없다"는 뜻이다.
                    //   신규 가입(특히 소셜 로그인) 직후 public.users 생성과 getMe 호출이 경합하면 첫 호출이 404가 난다.
                    //   이걸 WELCOME 으로 보내면 아무 안내 없이 로그인 화면으로 튕겨 가입이 끊긴다(실제 발생).
                    //   → 위 fetchMeWithRetry 로 재시도한 뒤에도 404면 '신규 사용자'로 보고 동의 화면부터 시작한다.
                    // 401은 RetrofitFactory에서 이미 notifySessionExpired() → WELCOME으로 이동 중
                    // [260821] 재시도까지 실패한 일시 장애(NETWORK/5xx)는 WELCOME 으로 보내지 않는다.
                    //   토큰이 살아 있는데 로그인 화면으로 튕기면 사용자는 '가입이 끊겼다'고 받아들인다.
                    //   → 홈으로 낙관 진행. 홈의 각 카드가 자체적으로 로딩·오류를 표시하고,
                    //     30분 워커/다음 진입에서 다시 조회되므로 상태는 자연히 복구된다.
                    //     세션이 진짜 만료됐다면 401 경로가 별도로 WELCOME 으로 보낸다.
                    when {
                        isUserNotFound(res) -> Routes.CONSENTS
                        isTransient(res) -> Routes.TAB_HOME
                        else -> Routes.WELCOME
                    }
                }
            }
            navController.navigate(target) {
                popUpTo(0) { inclusive = true }
            }
            postLoginRouting = false
        }
    }

    NavHost(
        navController = navController,
        startDestination = Routes.SPLASH,
        modifier = if (showBottomNav) Modifier.padding(padding) else Modifier
    ) {
        // ============ 진입 ============
        composable(Routes.SPLASH) {
            SplashScreen(
                onAuthenticated = {
                    navController.navigate(Routes.TAB_HOME) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onNotAuthenticated = {
                    navController.navigate(Routes.WELCOME) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onNeedsConsents = {
                    navController.navigate(Routes.CONSENTS) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onNeedsAccountInfo = {
                    navController.navigate(Routes.ACCOUNT_INFO) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                // [v1.0 버그픽스] 약관+계정정보는 끝났는데 온보딩 미완료 → 키/체중부터
                onNeedsOnboarding = {
                    navController.navigate(Routes.ONBOARD_BASIC) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                // [260904] 서버 min_version_code 미만 → 업데이트 화면 (로그인 여부와 무관, 가장 먼저 판정)
                onForceUpdate = {
                    navController.navigate(Routes.FORCE_UPDATE) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        // ============ 강제 업데이트 (뒤로가기 차단, 업데이트만 가능) ============
        composable(Routes.FORCE_UPDATE) { ForceUpdateScreen() }

        // ============ 환영 (로그인/가입 진입점) ============
        composable(Routes.WELCOME) {
            WelcomeScreen(
                onEmailLogin = { navController.navigate(Routes.LOGIN_EMAIL) },
                onEmailSignup = { navController.navigate(Routes.SIGNUP_EMAIL) },
                onLoginSuccess = {
                    // OAuth 성공 → onboarding 체크 후 홈 또는 onboarding으로 분기
                    // (postLoginRouting LaunchedEffect 사용)
                    postLoginRouting = true
                }
            )
        }

        // ============ 로그인 ============
        composable(
            route = "${Routes.LOGIN_EMAIL}?email={email}",
            arguments = listOf(androidx.navigation.navArgument("email") {
                type = androidx.navigation.NavType.StringType
                defaultValue = ""
            })
        ) { backStackEntry ->
            val initialEmail = java.net.URLDecoder.decode(
                backStackEntry.arguments?.getString("email") ?: "", "UTF-8"
            )
            LoginScreen(
                onLoginSuccess = {
                    // [P1] OAuth와 일관성 — hasConsents 체크 후 분기
                    //   기존 user면 홈, 약관 미동의면 CONSENTS (이전 가입 도중 끊긴 케이스)
                    postLoginRouting = true
                },
                onGotoSignup = { navController.navigate(Routes.SIGNUP_EMAIL) },
                onForgotPassword = { navController.navigate(Routes.PASSWORD_RESET_REQUEST) },
                initialEmail = initialEmail,
                onBack = {
                    navController.popBackStack(Routes.WELCOME, inclusive = false)
                }
            )
        }

        // ============ 회원가입 ============
        composable(Routes.SIGNUP_EMAIL) {
            SignupScreen(
                onSignupSuccess = {
                    // 회원가입 후 4단계 동의서 → 온보딩
                    navController.navigate(Routes.CONSENTS) {
                        popUpTo(Routes.SIGNUP_EMAIL) { inclusive = true }
                    }
                },
                // [v1.0] 이메일 인증 대기 화면으로 이동 (Magic Link 방식)
                // [코덱스 P0] password는 route param X — PendingSignupCredentials singleton에 보관
                onEmailConfirmationRequired = { email ->
                    val encEmail = java.net.URLEncoder.encode(email, "UTF-8")
                    navController.navigate("${Routes.EMAIL_VERIFY_WAIT}?email=$encEmail")
                },
                // [v1.0] 이미 가입된 이메일 → 로그인 화면으로 (이메일 자동 입력)
                onGotoLoginWithEmail = { existingEmail ->
                    val encEmail = java.net.URLEncoder.encode(existingEmail, "UTF-8")
                    navController.navigate("${Routes.LOGIN_EMAIL}?email=$encEmail") {
                        popUpTo(Routes.SIGNUP_EMAIL) { inclusive = true }
                    }
                },
                onBackToLogin = {
                    navController.popBackStack(Routes.WELCOME, inclusive = false)
                }
            )
        }

        // ============ 이메일 인증 대기 화면 (Magic Link) ============
        composable(
            route = "${Routes.EMAIL_VERIFY_WAIT}?email={email}",
            arguments = listOf(
                androidx.navigation.navArgument("email") {
                    type = androidx.navigation.NavType.StringType
                    defaultValue = ""
                }
            )
        ) { backStackEntry ->
            val email = java.net.URLDecoder.decode(backStackEntry.arguments?.getString("email") ?: "", "UTF-8")
            EmailVerifyWaitScreen(
                email = email,
                onVerified = {
                    // 인증 완료 → 임시 자격정보 즉시 폐기 후 약관 동의로
                    com.dangbomcare.app.ui.viewmodel.PendingSignupCredentials.clear()
                    navController.navigate(Routes.CONSENTS) {
                        popUpTo(Routes.SIGNUP_EMAIL) { inclusive = true }
                    }
                },
                onBack = {
                    com.dangbomcare.app.ui.viewmodel.PendingSignupCredentials.clear()
                    navController.popBackStack(Routes.SIGNUP_EMAIL, inclusive = false)
                }
            )
        }

        // ============ 비밀번호 재설정 — 1단계: 이메일 입력 ============
        composable(Routes.PASSWORD_RESET_REQUEST) {
            PasswordResetRequestScreen(
                onSent = {
                    navController.navigate(Routes.PASSWORD_RESET_SENT) {
                        popUpTo(Routes.PASSWORD_RESET_REQUEST) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        // ============ 비밀번호 재설정 — 2단계: 안내 화면 ============
        composable(Routes.PASSWORD_RESET_SENT) {
            PasswordResetSentScreen(
                onBackToLogin = {
                    navController.navigate(Routes.LOGIN_EMAIL) {
                        popUpTo(Routes.WELCOME) { inclusive = false }
                    }
                }
            )
        }

        // ============ 비밀번호 재설정 — 3단계: 새 비번 입력 (딥링크 진입) ============
        composable(Routes.PASSWORD_RESET_NEW) {
            val activity = com.dangbomcare.app.MainActivity.instance
            PasswordResetNewPasswordScreen(
                onCompleted = {
                    // 새 비번 저장 성공 → 홈으로 (이미 로그인 상태)
                    // [v1.0 fix - Codex] recovery 흐름은 MainActivity에서 enqueueImmediateSync 호출 X →
                    //   비번 재설정 직후 워치 데이터 sync가 onResume 5분 throttle에 막힘. 여기서 강제 1회.
                    activity?.applicationContext?.let { ctx ->
                        com.dangbomcare.app.data.health.WatchSyncWorker.enqueueImmediateSync(ctx)
                    }
                    postLoginRouting = true
                },
                onBack = {
                    navController.navigate(Routes.WELCOME) { popUpTo(0) { inclusive = true } }
                }
            )
        }

        // ============ 약관 동의 (가입/OAuth 후 첫 단계) ============
        composable(Routes.CONSENTS) {
            ConsentsScreen(
                onCompleted = {
                    // [260726 회귀픽스] 이전에는 무조건 ACCOUNT_INFO → ONBOARD_BASIC 으로 보냈다.
                    //   4번째 동의(제3자 제공)가 필수로 추가되면서 '이미 온보딩을 끝낸 기존 사용자'도
                    //   동의 화면을 다시 거치게 됐는데, 그 뒤 온보딩 전체가 재실행되어
                    //   키·체중을 다시 입력받고 기상/취침/식사 시각과 알람이 기본값으로 덮어써졌다.
                    //   → 서버 상태를 다시 조회해 라우팅 사다리를 재평가한다.
                    //     (동의만 부족했던 사용자는 곧바로 홈으로, 진짜 신규 가입자는 ACCOUNT_INFO 로)
                    postLoginRouting = true
                },
                onBack = {
                    navController.navigate(Routes.WELCOME) { popUpTo(0) { inclusive = true } }
                },
                onShowDetail = { type ->
                    navController.navigate("${Routes.CONSENT_DETAIL}/$type")
                }
            )
        }

        // ============ 계정 정보 (이름/성별/생년월일) ============
        composable(Routes.ACCOUNT_INFO) {
            AccountInfoScreen(
                onNext = { navController.navigate(Routes.ONBOARD_BASIC) },
                onBack = {
                    if (!navController.popBackStack()) {
                        navController.navigate(Routes.WELCOME) { popUpTo(0) { inclusive = true } }
                    }
                }
            )
        }

        // ============ 약관 전문 보기 ============
        composable(
            route = "${Routes.CONSENT_DETAIL}/{type}",
            arguments = listOf(androidx.navigation.navArgument("type") { type = androidx.navigation.NavType.StringType })
        ) { backStackEntry ->
            val type = backStackEntry.arguments?.getString("type") ?: "terms"
            ConsentDetailScreen(
                type = type,
                onBack = { navController.popBackStack() }
            )
        }

        // ============ 회원 탈퇴 ============
        composable(Routes.ACCOUNT_DELETION) {
            AccountDeletionScreen(
                onBack = { navController.popBackStack() },
                onDeleted = {
                    navController.navigate(Routes.WELCOME) { popUpTo(0) { inclusive = true } }
                }
            )
        }

        // ============ 온보딩 3단계 ============
        composable(Routes.ONBOARD_BASIC) {
            OnboardingBasicScreen(
                onNext = { navController.navigate(Routes.ONBOARD_WATCH) },
                onBack = {
                    // 백스택이 비어있으면 (직접 진입) → Welcome으로
                    if (!navController.popBackStack()) {
                        navController.navigate(Routes.WELCOME) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                }
            )
        }
        composable(Routes.ONBOARD_WATCH) {
            OnboardingWatchScreen(
                onNext = { navController.navigate(Routes.ONBOARD_LIFESTYLE) },
                onBack = { navController.popBackStack() },
                onShowCompatibleWatches = { navController.navigate(Routes.COMPATIBLE_WATCHES) }
            )
        }
        composable(Routes.COMPATIBLE_WATCHES) {
            CompatibleWatchesScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.ONBOARD_LIFESTYLE) {
            OnboardingLifestyleScreen(
                onComplete = {
                    navController.navigate(Routes.TAB_HOME) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        // ============ 메인 탭 ============
        composable(Routes.TAB_HOME) {
            HomeScreen(
                onGotoMorningInput = { navController.navigate(Routes.MORNING_INPUT) },
                onGotoDetailActivity = { navController.navigate("${Routes.DETAIL_ACTIVITY}?date=&period=") },
                // [v1.0 피드백] "어젯밤 잠"은 어제 날짜 수면 데이터로 진입 (홈 카드에 표시된 값 = lastNightSleepHours = yesterday)
                onGotoDetailSleep = {
                    val yest = java.time.LocalDate.now().minusDays(1).toString()
                    navController.navigate("${Routes.DETAIL_SLEEP}?date=$yest")
                },
                onGotoDetailStress = { navController.navigate("${Routes.DETAIL_STRESS}?date=") },
                onGotoAiFeedback = { navController.navigate(Routes.AI_FEEDBACK) },
                onGotoScoreDetail = { navController.navigate("${Routes.SCORE_DETAIL}?date=") },
                scrollToTopTrigger = homeScrollToTop
            )
        }
        composable(Routes.TAB_RECORDS) {
            RecordsScreen(
                onGotoScoreDetail = { date -> navController.navigate("${Routes.SCORE_DETAIL}?date=$date") },
                onGotoActivity = { date -> navController.navigate("${Routes.DETAIL_ACTIVITY}?date=$date&period=") },
                onGotoSleep = { date -> navController.navigate("${Routes.DETAIL_SLEEP}?date=$date") },
                onGotoStress = { date -> navController.navigate("${Routes.DETAIL_STRESS}?date=$date") },
                onGotoGlucose = { date -> navController.navigate("${Routes.DETAIL_GLUCOSE}?date=$date&period=7d") },
                // [우노 3] 캘린더 날짜 → 과거 내용 입력하기
                onGotoPastInput = { date -> navController.navigate("${Routes.MORNING_INPUT}?date=$date") },
                scrollToTopTrigger = recordsScrollToTop
            )
        }
        composable(Routes.TAB_REPORTS) {
            ReportsScreen(
                onBack = { },
                scrollToTopTrigger = reportsScrollToTop,
                // [v1.0 피드백] 주간/월간 지표 클릭 시 (period + endDate) 둘 다 전달
                onGotoGlucose = { period, date -> navController.navigate("${Routes.DETAIL_GLUCOSE}?date=$date&period=$period") },
                onGotoActivity = { period, date -> navController.navigate("${Routes.DETAIL_ACTIVITY}?date=$date&period=$period") },
                onGotoSleep = { period, date -> navController.navigate("${Routes.DETAIL_SLEEP}?date=$date&period=$period") },
                onGotoStress = { period, date -> navController.navigate("${Routes.DETAIL_STRESS}?date=$date&period=$period") },
                // 일별 점수 그래프의 점(날짜) 클릭 → 그 날 점수 상세
                onGotoScoreDetail = { date -> navController.navigate("${Routes.SCORE_DETAIL}?date=$date") }
            )
        }
        composable(Routes.TAB_SETTINGS) {
            SettingsScreen(
                onBack = { },
                onLogout = {
                    navController.navigate(Routes.WELCOME) { popUpTo(0) { inclusive = true } }
                },
                onGotoWatchDebug = { navController.navigate(Routes.WATCH_DEBUG) },
                onGotoAccountDeletion = { navController.navigate(Routes.ACCOUNT_DELETION) },
                onGotoDataDiagnostic = { navController.navigate(Routes.DATA_DIAGNOSTIC) },
                onGotoExportData = { navController.navigate(Routes.EXPORT_DATA) },
                onGotoNotificationSettings = { navController.navigate(Routes.NOTIFICATION_SETTINGS) },
                onGotoConsentsView = { navController.navigate(Routes.CONSENTS_VIEW) },
                onGotoTerms = { navController.navigate(Routes.TERMS) },
                onGotoPrivacy = { navController.navigate(Routes.PRIVACY) },
                // [260728 우노 마케팅 동의] 전문은 기존 약관 전문 화면을 재사용 — ConsentDetailScreen 의
                //   type 분기에 "marketing"(→ MARKETING_TEXT)이 추가된다. 새 라우트는 필요 없다.
                onGotoMarketingConsent = { navController.navigate("${Routes.CONSENT_DETAIL}/marketing") },
                // [260729] 오픈소스 라이선스 고지. 라우트 상수는 OpenSourceLicenseScreen.kt 에 있다
                //   (Routes.kt 는 이 작업의 소유 파일이 아니라 수정하지 않았다 — 이관 필요).
                onGotoOpenSourceLicense = { navController.navigate(ROUTE_OPEN_SOURCE_LICENSE) },
                onGotoMedicalGoals = { navController.navigate(Routes.MEDICAL_GOAL) },
                scrollToTopTrigger = settingsScrollToTop
            )
        }

        // ============ 동의 내역 풀스크린 보기 (사용자 피드백 — 팝업 X) ============
        composable(Routes.CONSENTS_VIEW) {
            ConsentsViewScreen(
                onBack = { navController.popBackStack() },
                onShowDetail = { type -> navController.navigate("${Routes.CONSENT_DETAIL}/$type") }
            )
        }

        // ============ 이용약관 / 개인정보처리방침 단독 진입 (설정 화면 별도 항목) ============
        composable(Routes.TERMS) {
            TermsScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.PRIVACY) {
            PrivacyPolicyScreen(onBack = { navController.popBackStack() })
        }

        // ============ 오픈소스 라이선스 고지 (설정 → 약관 / 정책) ============
        composable(ROUTE_OPEN_SOURCE_LICENSE) {
            OpenSourceLicenseScreen(onBack = { navController.popBackStack() })
        }

        // ============ 입력 흐름 (Analyzing은 MorningInputScreen 내부에서 풀스크린 처리) ============
        // [우노 3] ?date= 로 과거 날짜 입력 지원 (없으면 오늘). +버튼/홈은 인자 없이 호출 → 오늘.
        composable(
            route = "${Routes.MORNING_INPUT}?date={date}",
            arguments = listOf(androidx.navigation.navArgument("date") {
                type = androidx.navigation.NavType.StringType
                defaultValue = ""
            })
        ) { backStackEntry ->
            val dateArg = backStackEntry.arguments?.getString("date").orEmpty()
            val isPast = dateArg.isNotBlank() && dateArg != java.time.LocalDate.now().toString()
            MorningInputScreen(
                initialDateIso = dateArg.ifBlank { null },
                onBack = { navController.popBackStack() },
                onSuccess = {
                    if (isPast) {
                        // 과거 날짜 입력 후엔 직전(기록) 화면으로 복귀
                        navController.popBackStack()
                    } else {
                        navController.navigate(Routes.TAB_HOME) {
                            popUpTo(Routes.TAB_HOME) { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                }
            )
        }

        // ============ 상세 화면 (선택 날짜 전달 가능) ============
        composable("${Routes.DETAIL_ACTIVITY}?date={date}&period={period}") { backStackEntry ->
            val date = backStackEntry.arguments?.getString("date")
            val period = backStackEntry.arguments?.getString("period")
            DetailActivityScreen(
                onBack = { navController.popBackStack() },
                initialDate = date,
                initialPeriod = period
            )
        }
        composable("${Routes.DETAIL_SLEEP}?date={date}&period={period}") { backStackEntry ->
            val date = backStackEntry.arguments?.getString("date")
            val period = backStackEntry.arguments?.getString("period")
            DetailSleepScreen(
                onBack = { navController.popBackStack() },
                initialDate = date,
                initialPeriod = period
            )
        }
        // 호환성 — period 없는 호출 (RecordsScreen에서)
        composable("${Routes.DETAIL_SLEEP}?date={date}") { backStackEntry ->
            val date = backStackEntry.arguments?.getString("date")
            DetailSleepScreen(onBack = { navController.popBackStack() }, initialDate = date)
        }
        composable("${Routes.DETAIL_STRESS}?date={date}&period={period}") { backStackEntry ->
            val date = backStackEntry.arguments?.getString("date")
            val period = backStackEntry.arguments?.getString("period")
            DetailStressScreen(
                onBack = { navController.popBackStack() },
                initialDate = date,
                initialPeriod = period
            )
        }
        composable("${Routes.DETAIL_STRESS}?date={date}") { backStackEntry ->
            val date = backStackEntry.arguments?.getString("date")
            DetailStressScreen(onBack = { navController.popBackStack() }, initialDate = date)
        }
        composable("${Routes.DETAIL_GLUCOSE}?date={date}&period={period}") { backStackEntry ->
            val date = backStackEntry.arguments?.getString("date")
            val period = backStackEntry.arguments?.getString("period")
            DetailGlucoseScreen(
                onBack = { navController.popBackStack() },
                initialDate = date,
                initialPeriod = period
            )
        }
        composable(Routes.DETAIL_GLUCOSE) {
            DetailGlucoseScreen(onBack = { navController.popBackStack() }, initialDate = null, initialPeriod = null)
        }

        // ============ 보조 ============
        // 점수 상세 / AI 피드백은 HomeViewModel을 공유하여 즉시 표시 (다시 fetch 안 함)
        composable("${Routes.SCORE_DETAIL}?date={date}") { backStackEntry ->
            val date = backStackEntry.arguments?.getString("date")
            val parentEntry = remember(backStackEntry) {
                runCatching { navController.getBackStackEntry(Routes.TAB_HOME) }.getOrNull()
            }
            val homeVm: com.dangbomcare.app.ui.viewmodel.HomeViewModel = if (parentEntry != null) {
                androidx.lifecycle.viewmodel.compose.viewModel(parentEntry)
            } else {
                androidx.lifecycle.viewmodel.compose.viewModel()
            }
            ScoreDetailScreen(
                onBack = { navController.popBackStack() },
                vm = homeVm,
                initialDate = date
            )
        }
        composable(Routes.AI_FEEDBACK) { backStackEntry ->
            val parentEntry = remember(backStackEntry) {
                runCatching { navController.getBackStackEntry(Routes.TAB_HOME) }.getOrNull()
            }
            val homeVm: com.dangbomcare.app.ui.viewmodel.HomeViewModel = if (parentEntry != null) {
                androidx.lifecycle.viewmodel.compose.viewModel(parentEntry)
            } else {
                androidx.lifecycle.viewmodel.compose.viewModel()
            }
            AiFeedbackScreen(onBack = { navController.popBackStack() }, vm = homeVm)
        }
        composable(Routes.WATCH_DEBUG) { WatchDebugScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.NOTIFICATION_SETTINGS) {
            NotificationSettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.DATA_DIAGNOSTIC) { DataDiagnosticScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.EXPORT_DATA) { ExportDataScreen(onBack = { navController.popBackStack() }) }
        // [우노 4] 의료진 목표 입력 (PIN 잠금)
        composable(Routes.MEDICAL_GOAL) { MedicalGoalScreen(onBack = { navController.popBackStack() }) }
    }
}

/**
 * [260726 버그픽스] 백엔드 get_me 404 = "public.users 행이 아직 없다" (세션 만료가 아님).
 *   신규 가입 직후 서버의 사용자 행 생성과 getMe 호출이 경합하면 첫 호출이 404로 떨어진다.
 */
internal fun isUserNotFound(e: ApiResult.Error): Boolean =
    e.code == "NOT_FOUND" || e.code == "HTTP_404"

/**
 * [260821] 일시 장애인가 — 네트워크 끊김 또는 서버 5xx.
 *   세션 문제(401)나 '사용자 없음'(404)과 달리 **잠시 뒤 다시 하면 되는** 오류다.
 *   이걸 WELCOME(재로그인 유도)으로 보내면, 동의를 막 저장한 신규 가입자가
 *   아무 설명 없이 로그인 화면으로 튕겨 가입이 끊긴다.
 */
internal fun isTransient(e: ApiResult.Error): Boolean =
    e.code == "NETWORK" || e.code.startsWith("HTTP_5")

/**
 * [260726 버그픽스] getMe 를 호출하되, 위 경합으로 인한 404 면 짧게 재시도한다.
 *   재시도 후에도 404 면 호출부가 '신규 사용자'로 판단해 동의 화면부터 진행한다.
 */
internal suspend fun fetchMeWithRetry(
    attempts: Int = 3,
    delayMs: Long = 700
): ApiResult<com.dangbomcare.app.data.api.UserMeResponse> {
    var res = ServiceLocator.userRepository.getMe()
    var i = 0
    while (i < attempts) {
        val err = res as? ApiResult.Error ?: break
        // [260821] 404(생성 경합) 뿐 아니라 일시 장애(NETWORK/5xx)도 재시도한다.
        //   이전엔 404만 재시도해서, 동의 저장 직후 순간적인 네트워크 끊김이 곧바로
        //   WELCOME 튕김이 됐다. 토큰은 살아 있으므로 세션 파기 경로와 구분해야 한다.
        if (!isUserNotFound(err) && !isTransient(err)) break
        kotlinx.coroutines.delay(delayMs)
        res = ServiceLocator.userRepository.getMe()
        i++
    }
    return res
}
