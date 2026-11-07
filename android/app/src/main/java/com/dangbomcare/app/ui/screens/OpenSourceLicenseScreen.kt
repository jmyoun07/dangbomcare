@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.dangbomcare.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dangbomcare.app.ui.theme.*

/**
 * 오픈소스 라이선스 고지 화면 (설정 → 약관 / 정책 → 오픈소스 라이선스).
 *
 * [작성 원칙 — 260729]
 *  • 목록은 자동 생성 플러그인이 아니라 app/build.gradle.kts + gradle/libs.versions.toml 의
 *    **실제 선언 의존성**을 읽어 손으로 작성했다. 임의로 추가/삭제하지 말고, 의존성을 바꾸면 여기도 바꾼다.
 *  • 라이선스명은 확실한 것만 적는다. 틀린 라이선스명을 적는 것이 빠뜨리는 것보다 나쁘다.
 *  • Samsung Health Data SDK 는 오픈소스가 아니다
 *    (app/libs 의 samsung-health-data-api-1.1.0.aar 로 반입된 파트너 계약 기반 SDK).
 *    → 라이선스명을 붙이지 않고 사실만 서술하며, 오픈소스 목록과 시각적으로 분리해 배치한다.
 *  • 시니어 가독성: 본문 15sp / lineHeight 24sp, 카테고리 소제목 굵게(17sp Bold).
 *    ConsentDetailScreen 의 관례(상하 20dp/16dp 패딩, ## 소제목 17sp Bold PrimaryDark,
 *    Primary 톱바 + 흰 글자, 하단 40dp 여백)를 그대로 따랐고 본문만 14sp → 15sp 로 키웠다.
 */

/** Routes.kt 는 이 작업의 소유 파일이 아니므로 라우트 상수를 여기에 둔다. */
const val ROUTE_OPEN_SOURCE_LICENSE = "open_source_license"

/** 한 줄 = 라이브러리명 – 라이선스명 */
private data class OssEntry(val name: String, val license: String)

private data class OssCategory(val title: String, val entries: List<OssEntry>)

private const val APACHE2 = "Apache License 2.0"

/**
 * 실제 선언 의존성 기준 목록.
 *  - AndroidX / Jetpack Compose / Health Connect : Apache License 2.0
 *  - Retrofit / OkHttp / Gson                    : Apache License 2.0
 *  - Kotlin 표준 라이브러리 / 코루틴 / 직렬화     : Apache License 2.0
 *  - Firebase Android SDK                        : Apache License 2.0
 *  - Google Play 서비스 클라이언트 라이브러리     : Apache License 2.0
 * [260730] Google Play 서비스 계열은 Firebase 가 transitive 로 끌어와 APK 에 실제로 탑재되므로
 *   목록에 포함한다(이전엔 '라이선스 단정 불가'로 제외했는데, 클라이언트 라이브러리는 Apache 2.0 이다).
 *   Play 서비스가 내부적으로 동봉하는 제3자 구성요소의 상세 고지는 Google 오픈소스 사이트에서 제공되며,
 *   과거의 GoogleApiAvailability.getOpenSourceSoftwareLicenseInfo() 런타임 API 는 최신
 *   play-services 에서 제거돼 사용할 수 없다(그래서 하단 안내로 위치만 알린다).
 */
private val OSS_CATEGORIES: List<OssCategory> = listOf(
    OssCategory(
        "안드로이드 기본",
        listOf(
            OssEntry("AndroidX Core KTX", APACHE2),
            OssEntry("AndroidX Activity Compose", APACHE2),
            OssEntry("AndroidX Lifecycle (Runtime · ViewModel)", APACHE2),
            OssEntry("AndroidX Navigation Compose", APACHE2),
            OssEntry("AndroidX DataStore Preferences", APACHE2),
            OssEntry("AndroidX WorkManager", APACHE2),
            OssEntry("AndroidX Browser (Custom Tabs)", APACHE2),
        )
    ),
    OssCategory(
        "화면 / UI",
        listOf(
            OssEntry("Jetpack Compose BOM", APACHE2),
            OssEntry("Jetpack Compose UI", APACHE2),
            OssEntry("Jetpack Compose UI Graphics", APACHE2),
            OssEntry("Jetpack Compose UI Tooling Preview", APACHE2),
            OssEntry("Jetpack Compose Material 3", APACHE2),
            OssEntry("Material Icons Extended", APACHE2),
        )
    ),
    OssCategory(
        "네트워크",
        listOf(
            OssEntry("Retrofit", APACHE2),
            OssEntry("Retrofit Gson Converter", APACHE2),
            OssEntry("OkHttp · Logging Interceptor", APACHE2),
            OssEntry("Gson", APACHE2),
        )
    ),
    OssCategory(
        "코틀린",
        listOf(
            OssEntry("Kotlin 표준 라이브러리", APACHE2),
            OssEntry("Kotlin Coroutines (Android)", APACHE2),
            OssEntry("Kotlin Serialization (JSON)", APACHE2),
        )
    ),
    OssCategory(
        "Firebase",
        listOf(
            OssEntry("Firebase Android BoM", APACHE2),
            OssEntry("Firebase Analytics", APACHE2),
            OssEntry("Firebase Crashlytics", APACHE2),
            OssEntry("Firebase Cloud Messaging", APACHE2),
        )
    ),
    OssCategory(
        "건강 데이터",
        listOf(
            OssEntry("Health Connect Client", APACHE2),
        )
    ),
    OssCategory(
        "Google Play 서비스",
        listOf(
            OssEntry("Google Play Services (Base · Basement · Tasks)", APACHE2),
            OssEntry("Google Play Services Ads Identifier", APACHE2),
            OssEntry("Google Play Services Measurement", APACHE2),
        )
    ),
)

@Composable
fun OpenSourceLicenseScreen(onBack: () -> Unit) {
    Scaffold(
        containerColor = BgPrimary,
        topBar = {
            TopAppBar(
                title = { Text("오픈소스 라이선스", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Primary,
                    titleContentColor = androidx.compose.ui.graphics.Color.White,
                    navigationIconContentColor = androidx.compose.ui.graphics.Color.White
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            // 상단 안내 한 문단
            Text(
                "당봄케어 앱은 아래와 같은 오픈소스 소프트웨어의 도움을 받아 만들어졌습니다. " +
                    "각 라이브러리는 해당 프로젝트가 정한 라이선스를 따르며, 요약 정보는 다음과 같습니다.",
                fontSize = 15.sp,
                lineHeight = 24.sp,
                color = TextPrimary
            )

            // 카테고리별 그룹 (구분선 + 소제목)
            OSS_CATEGORIES.forEach { category ->
                Spacer(Modifier.height(24.dp))
                HorizontalDivider(thickness = 1.dp, color = Divider)
                Spacer(Modifier.height(16.dp))
                Text(
                    category.title,
                    fontSize = 17.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = PrimaryDark
                )
                Spacer(Modifier.height(10.dp))
                category.entries.forEach { entry ->
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = TextPrimary, fontWeight = FontWeight.SemiBold)) {
                                append(entry.name)
                            }
                            withStyle(SpanStyle(color = TextSecondary)) {
                                append("  –  ${entry.license}")
                            }
                        },
                        fontSize = 15.sp,
                        lineHeight = 24.sp,
                        modifier = Modifier.padding(vertical = 5.dp)
                    )
                }
            }

            // 오픈소스가 아닌 구성요소 — 시각적으로 분리(구분선 + 별도 카드)
            Spacer(Modifier.height(28.dp))
            HorizontalDivider(thickness = 1.dp, color = Divider)
            Spacer(Modifier.height(16.dp))
            Text(
                "오픈소스가 아닌 구성요소",
                fontSize = 17.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.Bold,
                color = PrimaryDark
            )
            Spacer(Modifier.height(10.dp))
            Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                color = BgCardSoft,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Samsung Health Data SDK",
                        fontSize = 15.sp,
                        lineHeight = 24.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "삼성전자 제공, 파트너 이용 약관 적용",
                        fontSize = 15.sp,
                        lineHeight = 24.sp,
                        color = TextSecondary
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "삼성헬스의 건강 데이터를 읽어오기 위해 사용하며, 오픈소스 소프트웨어가 아닙니다.",
                        fontSize = 15.sp,
                        lineHeight = 24.sp,
                        color = TextSecondary
                    )
                }
            }

            // 하단 안내
            Spacer(Modifier.height(24.dp))
            Text(
                "각 라이브러리의 상세한 라이선스 전문은 해당 프로젝트의 공식 저장소에서 확인하실 수 있습니다. " +
                    "Google Play 서비스가 포함하는 오픈소스 구성요소의 상세 고지는 Google 오픈소스 라이선스 " +
                    "안내(google.com/policies 및 각 라이브러리 저장소)에서 확인하실 수 있습니다.",
                fontSize = 15.sp,
                lineHeight = 24.sp,
                color = TextSecondary
            )

            Spacer(Modifier.height(40.dp))
        }
    }
}
