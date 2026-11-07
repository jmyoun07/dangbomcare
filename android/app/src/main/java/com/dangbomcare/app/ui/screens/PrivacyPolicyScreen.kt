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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dangbomcare.app.ui.theme.*

/**
 * 개인정보처리방침 전문 화면.
 *
 * 두 개의 법정 문서를 하나의 스크롤 뷰로 통합해 표시:
 *   섹션 1) 일반 개인정보 수집 및 이용 (PRIVACY_TEXT, 개인정보보호법 제15조)
 *   섹션 2) 민감정보(건강정보) 처리    (SENSITIVE_TEXT, 개인정보보호법 제23조)
 *
 * 시니어 사용자 가독성 표준 준수:
 *   - 본문 14sp / lineHeight 22sp
 *   - `# ` 헤더 22sp Black, `## ` 서브헤더 17sp Bold
 *   - 최상단 섹션 구분 헤더 20sp Black
 *
 * ConsentDetailScreen 과 동일한 마크다운 파서 로직을 사용해
 * 두 문서의 렌더 스타일을 일관되게 유지한다.
 */
@Composable
fun PrivacyPolicyScreen(onBack: () -> Unit) {
    Scaffold(
        containerColor = BgPrimary,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "개인정보처리방침",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "뒤로"
                        )
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
            // [260723 최종본] 앱·웹 게시용 통합 개인정보처리방침(제2부) 단일 문서 표시.
            //   기존에는 가입용 동의서 3종(PRIVACY/SENSITIVE/THIRD_PARTY)을 이어붙여 보여줬으나,
            //   260723 최종본 지침에 따라 '개인정보처리방침'에는 제2부 통합 문서를 표시한다.
            //   (가입 시 받는 개별 동의 전문은 설정 → 동의 내역에서 각각 확인)
            MarkdownBody(PRIVACY_POLICY_TEXT)

            Spacer(Modifier.height(40.dp))
        }
    }
}

/**
 * 두 법정 문서를 구분하는 최상위 섹션 헤더.
 * 문서 내부의 `# ` 헤더(22sp)와 시각적으로 구분되도록 20sp 로 유지.
 */
@Composable
private fun SectionDivider(text: String) {
    Spacer(Modifier.height(4.dp))
    Text(
        text,
        fontSize = 20.sp,
        fontWeight = FontWeight.Black,
        color = Primary
    )
    Spacer(Modifier.height(12.dp))
}

/**
 * ConsentDetailScreen 과 동일한 경량 마크다운 렌더러.
 * 지원 문법: `# `, `## `, `- `, 빈 줄, 본문.
 */
@Composable
private fun MarkdownBody(body: String) {
    body.split("\n").forEach { line ->
        when {
            line.startsWith("# ") -> {
                Spacer(Modifier.height(4.dp))
                Text(
                    line.removePrefix("# "),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black,
                    color = Primary
                )
                Spacer(Modifier.height(12.dp))
            }
            line.startsWith("## ") -> {
                Spacer(Modifier.height(16.dp))
                Text(
                    line.removePrefix("## "),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = PrimaryDark
                )
                Spacer(Modifier.height(8.dp))
            }
            line.startsWith("- ") -> {
                Row(modifier = Modifier.padding(vertical = 2.dp)) {
                    Text("• ", fontSize = 14.sp, color = TextPrimary)
                    Text(
                        line.removePrefix("- "),
                        fontSize = 14.sp,
                        color = TextPrimary,
                        lineHeight = 22.sp
                    )
                }
            }
            line.isBlank() -> Spacer(Modifier.height(10.dp))
            else -> Text(
                line,
                fontSize = 14.sp,
                color = TextPrimary,
                lineHeight = 22.sp
            )
        }
    }
}
