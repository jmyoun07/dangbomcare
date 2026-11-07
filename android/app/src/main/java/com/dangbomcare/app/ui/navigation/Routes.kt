package com.dangbomcare.app.ui.navigation

/**
 * 화면 경로 (Navigation Compose 단순 String 라우트).
 * v1.9 14화면 + 보조 화면.
 */
object Routes {
    // 진입 / 인증
    const val SPLASH = "splash"
    const val FORCE_UPDATE = "force_update"  // [260904] 서버 min_version_code 미만 → 업데이트 전 진입 차단
    const val WELCOME = "welcome"            // 1. 가입/환영
    const val SIGNUP_EMAIL = "signup_email"  // 이메일 가입 (보조)
    const val LOGIN_EMAIL = "login_email"    // 이메일 로그인 (보조)
    const val EMAIL_VERIFY_WAIT = "email_verify_wait"      // 이메일 인증 대기 화면 (가입 후 Magic Link 대기)
    const val PASSWORD_RESET_REQUEST = "password_reset_request"  // 비밀번호 재설정 — 이메일 입력
    const val PASSWORD_RESET_SENT = "password_reset_sent"        // 비밀번호 재설정 — 메일 발송 안내
    const val PASSWORD_RESET_NEW = "password_reset_new"          // 비밀번호 재설정 — 새 비번 입력 (딥링크)

    // 가입 단계 — OAuth/이메일 인증 후 첫 정보 입력 (사용자 피드백 — 일반 패턴)
    const val ACCOUNT_INFO = "account_info"            // 이름/성별/생년월일

    // 온보딩 3단계
    const val ONBOARD_BASIC = "onboard_basic"          // 1. 신체 정보 (키/체중/당뇨 진단)
    const val ONBOARD_WATCH = "onboard_watch"          // 2. 워치 연결
    const val ONBOARD_LIFESTYLE = "onboard_lifestyle"  // 3. 생활 패턴

    // 메인 탭 (하단 네비)
    const val TAB_HOME = "tab_home"           // 8. 홈
    const val TAB_RECORDS = "tab_records"     // 11. 기록
    const val TAB_REPORTS = "tab_reports"     // 12-13. 리포트
    const val TAB_SETTINGS = "tab_settings"   // 14. 설정

    // 입력 흐름
    const val MORNING_INPUT = "morning_input"      // 6. 통합 입력 모달
    const val ANALYZING = "analyzing"              // 7. 분석 로딩

    // 상세 화면
    const val DETAIL_ACTIVITY = "detail_activity"  // 9. 활동량 상세
    const val DETAIL_SLEEP = "detail_sleep"        // 10. 수면 상세
    const val DETAIL_STRESS = "detail_stress"      // 11. 스트레스 상세
    const val DETAIL_GLUCOSE = "detail_glucose"    // 공복혈당 상세 (주간/월간 그래프)

    // 보조 화면
    const val SCORE_DETAIL = "score_detail"
    const val AI_FEEDBACK = "ai_feedback"
    const val WATCH_DEBUG = "watch_debug"
    const val NOTIFICATION_SETTINGS = "notification_settings"  // 알림 세부 설정 (사용자 피드백 — 박스 분리)
    const val COMPATIBLE_WATCHES = "compatible_watches"
    const val DATA_DIAGNOSTIC = "data_diagnostic"  // Health Connect 데이터 진단 (디버그)
    const val EXPORT_DATA = "export_data"          // 내 데이터 CSV 내보내기 (의료진 공유)
    const val MEDICAL_GOAL = "medical_goal"        // [우노 4] 의료진 목표 입력 (PIN 잠금)

    // 법적 / 정책
    const val CONSENTS = "consents"                  // 4단계 동의서 (회원가입 직후)
    const val CONSENT_DETAIL = "consent_detail"      // 약관 전문 보기 ({type} arg)
    const val CONSENTS_VIEW = "consents_view"        // 설정 화면 → 동의 내역 풀스크린 보기 (사용자 피드백 — 팝업 X)
    const val TERMS = "terms"                        // 이용약관 (설정 → 단독 진입)
    const val PRIVACY = "privacy"                    // 개인정보처리방침 (설정 → 단독 진입)
    const val ACCOUNT_DELETION = "account_deletion"  // 회원 탈퇴
}

/** 하단 탭 정의 */
enum class MainTab(
    val route: String,
    val label: String,
    val icon: String   // emoji 또는 Compose icon 이름
) {
    HOME(Routes.TAB_HOME, "홈", "home"),
    RECORDS(Routes.TAB_RECORDS, "기록", "edit_note"),
    REPORTS(Routes.TAB_REPORTS, "리포트", "bar_chart"),
    SETTINGS(Routes.TAB_SETTINGS, "설정", "settings");

    companion object {
        fun fromRoute(route: String?): MainTab? = values().firstOrNull { it.route == route }
    }
}
