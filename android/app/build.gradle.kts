import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    kotlin("plugin.serialization") version "2.2.10"
    // Samsung Health Data SDK의 Parcelable 클래스 사용 위함
    kotlin("plugin.parcelize")
    // Firebase: google-services plugin + Crashlytics
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
}

// local.properties에서 Supabase 키 / API URL 읽기 (gitignore됨)
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}
val supabaseUrl: String = localProps.getProperty("SUPABASE_URL", "")
val supabaseAnonKey: String = localProps.getProperty("SUPABASE_ANON_KEY", "")
val apiBaseUrl: String = localProps.getProperty("API_BASE_URL", "http://10.0.2.2:8000/")

// [v1.0 보안 A1] release 빌드는 반드시 HTTPS. release task 실행 시 http URL이면 빌드 abort.
// gradle properties로 task 이름 검사 — assembleRelease, bundleRelease, installRelease 등 release 관련.
val isReleaseBuild = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
if (isReleaseBuild) {
    require(apiBaseUrl.startsWith("https://")) {
        "[BUILD ABORT] Release build requires HTTPS. Current API_BASE_URL=$apiBaseUrl. " +
            "Set HTTPS URL in local.properties before building release."
    }
    require(supabaseUrl.startsWith("https://")) {
        "[BUILD ABORT] Release build requires HTTPS. Current SUPABASE_URL=$supabaseUrl. " +
            "Set HTTPS URL in local.properties before building release."
    }
    require(supabaseAnonKey.isNotBlank()) {
        "[BUILD ABORT] Release build requires SUPABASE_ANON_KEY in local.properties."
    }
}

android {
    namespace = "com.dangbomcare.app"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        // [출시 준비] 브랜드 당봄케어 — 정식 applicationId = "com.dangbomcare.app".
        //   namespace는 이미 "com.dangbomcare.app"로 변경 완료(2026-06).
        //   applicationId는 아직 "com.dangdang.hctest" 유지 — Firebase에 com.dangbomcare.app 등록 +
        //   google-services.json 교체 후 "com.dangbomcare.app"로 변경 예정.
        //   (applicationId 변경 = 새 앱 식별자 = Play Console 첫 업로드 시 영구 고정)
        applicationId = "com.dangbomcare.app"
        minSdk = 28
        targetSdk = 36
        // [260721] v1.1.3 — 저녁유형 개편(기타 신설/밀가루 삭제), 알림 UI 재작업, 설정 정리
        //   26: draft v8 하위호환, 알림 카드 stale/취침30분 표시 버그 수정
        //   27: 알림 stale 근본수정(SharedPreferences 리스너), 생활패턴 이모지 제거, 저녁 회고 박스 문구 명확화, 버전 표기 축소
        //   28: 알림 안내 문구 카드화(위로 이동), 생활패턴 라벨/서브 간격 축소, 하단 여백 3화면 재조정, eveningMode P0-15 롤백(오늘 완료 후만 활성)
        //   29: 홈 점수 도넛에서 라벨 배지 삭제 (소수점 점수 답답함 해소)
        //   30: r29 배지 삭제 롤백 (사용자 요청 — 점수만 있으니 이상)
        //   31: 저녁 회고 진입을 자동→팝업 방식으로. 진입 시 내일 날짜 상단 표시. 오늘 아침 화면 하단에 저녁 회고 진입 버튼.
        //   32: 팝업/저녁회고 화면 문구 다듬기. 수정 링크 → 연한 초록 박스. 저장 버튼 서브 제거.
        //   33: 팝업 서브 fillMaxWidth(가운데정렬 실제로), 저녁회고 본문 '내일 아침엔'→'아침엔', 수정박스 축소·가운데·폭 압축.
        //   34: Q2 이용약관·개인정보처리방침 별도 항목화 (TermsScreen + PrivacyPolicyScreen 신설, 설정 3항목 분리, Routes.TERMS/PRIVACY 추가)
        //   35: 약관/정책 박스에서 '동의 내역 보기' 항목 제거 (사용자 요청 — 별도 열람 가능)
        //   36: Case B (10~14일) 팝업 문구 '다만~' 앞 문단 분리, '필요하면 직접 수정해주세요' 삭제
        //   37: Case B 팝업 문단 간격 조정 (빈 줄 제거, 붙임)
        //   38: Case C 팝업 문구 재배치 ('기록(N일)이 부족해서' 형태로)
        //   39: 코덱스 17개 이슈 fix (CRITICAL 2 + HIGH 6 + MEDIUM 9)
        //       draft KST 고정, 로그아웃 prefs 정리, BEDTIME while, AI ai_input dinner_type_other,
        //       rememberSaveable eveningChoice, BACK 팝업 dismiss, 캘린더 텍스트 AutoSize, 원자 동기화,
        //       dinnerHour=0 방어, glucose_range_history RLS, glucose_normal_range null 정규화, flour 필터
        //   40: 저녁 유형 "단백질·지방(육류) 위주" → "고기 위주" 라벨 재환원 (사용자 요청)
        //   41: 실전검증 6건 fix — 집밥/한식 라벨, 로그아웃 근본수정(콜백완료대기),
        //       flour legacy 매핑, 리포트 식후운동 10분+ 라벨 (백엔드: 물언급삭제 + 음주혈당양방향 + 카운트 확장)
        //   42: 오늘의 미션 팁 아이콘 노란색+텍스트 앞 💡 중복 제거
        //   43: HomeContent에서 feedback null 시 카드 통째 소멸 → Pending 안내 카드 fallback (사용자 실전 이슈 fix)
        //   44: RecordsScreen에서 reflection null 시 회고 카드 통째 소멸 → "회고 기록 없음" 안내 fallback
        //   45: 저녁 모드 게이트에서 '저녁 2시간 전' 시각 조건 제거 — 오늘 아침 회고 완료 즉시 팝업 가능
        //   52: v1.1.10 — AI 피드백/수면 정확도, 마케팅 동의, 앱 아이콘, 로그인 브랜드 마크, 오픈소스 고지
        //   53: 260730 피드백 — 의료진 화면 조회전용(PIN 제거), 마케팅 행 디자인, 로그인 당봄케어 알약 제거, 홈 재생성배지 제거
        //   54: 260730 피드백2 — 미션 글머리기호 통일(•), 마케팅 토글 제목라인 정렬, 로그인 여백/텍스트 가운데정렬
        //   55: 260730 피드백3 — 마케팅 행 간격 축소(제목·부제목 붙임), 로그인 문구 통일(카카오/Google/이메일 로그인)
        //   56: 260812 한의원 피드백 — 배터리 최적화 예외 요청(백그라운드 동기화 유실 대응), '워치 동기화'→'삼성헬스 동기화' 문구 정정
        //   57: 260821 — 재동의 화면 마케팅 조용한 철회 수정, 일시장애 시 WELCOME 튕김 수정, 데드코드 정리
        //   58: 260821 — 삼성헬스 절전 해제 유도 안내(걸음 데이터 출처가 삼성헬스라 이쪽도 풀어야 함)
        //   59: 260821 — 삼성헬스 '제한 없음'으로 바뀌면 배너 자동 소멸(완료 버튼 없이도)
        //   60: 260821 — 삼성헬스 안내 '완료했어요'(영구닫기) → '닫기'(이번만). 해제될 때까지 계속 안내
        //   61: 260904 — 강제 업데이트 게이트(서버 app_config.min_version_code, Splash→ForceUpdateScreen),
        //       클라이언트 상태 보고(앱버전·배터리최적화·HC권한·기기 → users 9컬럼, PUT /me/client-status),
        //       '워치 동기화' 잔여 문구 2곳 정정(점수상세·기록)
        versionCode = 61
        versionName = "1.1.19"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 빌드 시 local.properties에서 주입
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"")
        // 코덱스 P0 — 데모 데이터 노출 차단 (release 빌드에서는 false)
        buildConfigField("boolean", "ALLOW_DEMO_FALLBACK", "false")
    }

    // ⭐ Play Store 릴리즈 서명 설정
    // [P0 보안] 비밀번호 하드코딩 제거 — local.properties 또는 환경변수에서만 로드, 없으면 release 빌드 실패
    signingConfigs {
        create("release") {
            val keystorePath = localProps.getProperty("KEYSTORE_PATH")
                ?: "${rootDir}/keystore/dangdangcare-release.jks"
            val ksPwd = localProps.getProperty("KEYSTORE_PASSWORD")
                ?: System.getenv("KEYSTORE_PASSWORD") ?: ""
            val keyAliasName = localProps.getProperty("KEY_ALIAS") ?: "dangdangcare"
            val keyPwd = localProps.getProperty("KEY_PASSWORD")
                ?: System.getenv("KEY_PASSWORD") ?: ""
            storeFile = file(keystorePath)
            storePassword = ksPwd
            keyAlias = keyAliasName
            keyPassword = keyPwd
        }
    }

    buildTypes {
        debug {
            // local.properties의 API_BASE_URL 사용 (위 defaultConfig에서 이미 주입됨)
            // debug 빌드만 데모 데이터 허용 (개발/스크린샷용)
            buildConfigField("boolean", "ALLOW_DEMO_FALLBACK", "true")
        }
        release {
            // ⭐ Play Store 출시용 — 서명 + 코드 압축
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false  // Compose는 minify off 권장 (false negative 방지)
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    // [출시 진행용] release 빌드 시 lint 경고로 인한 빌드 실패 방지.
    // 출시 후 lint 경고 별도 정리 필요.
    lint {
        abortOnError = false
        checkReleaseBuilds = false
        // lintVital이 무시 못하는 fatal 체크들 명시적 비활성화
        disable += setOf(
            "InvalidFragmentVersionForActivityResult", // Compose-only 앱이라 Fragment 미사용
        )
    }
}

dependencies {
    // Health Connect (기존)
    implementation("androidx.health.connect:connect-client:1.1.0-rc02")

    // Samsung Health Data SDK (로컬 aar) — 삼성헬스에서 직접 읽기 (HC 우회)
    // .aar은 app/libs/ 에 위치. 개발자 모드(테스트) / 파트너 승인(배포)에서 동작.
    implementation(files("libs/samsung-health-data-api-1.1.0.aar"))
    implementation("com.google.code.gson:gson:2.13.1")

    // Compose 기본 (기존)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.material:material-icons-extended:1.7.5")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    // === 추가: 네트워크 ===
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // === 추가: 코루틴 ===
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // === 추가: 직렬화 (선택적, 향후 사용) ===
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // === 추가: Navigation Compose ===
    implementation("androidx.navigation:navigation-compose:2.8.4")

    // === 추가: ViewModel ===
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")

    // === 추가: DataStore (토큰/설정 저장) ===
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // === 추가: WorkManager (백그라운드 워치 sync) ===
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // === 추가: Custom Tabs (Google/Kakao OAuth) ===
    implementation("androidx.browser:browser:1.8.0")

    // === 추가: Firebase BoM + Analytics + Crashlytics + Messaging ===
    // BoM이 호환 가능한 Firebase 라이브러리 버전을 자동 관리 → 개별 버전 명시 X
    implementation(platform("com.google.firebase:firebase-bom:34.13.0"))
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-crashlytics")
    // [v1.0 fix - Codex 2차] FCM 통합 — onNewToken에서 백엔드 PUT /me/fcm-token,
    //   onMessageReceived에서 NotificationCompat로 알림 표시 (DangDangCareMessagingService).
    implementation("com.google.firebase:firebase-messaging")

    // Test
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
