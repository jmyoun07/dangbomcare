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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dangbomcare.app.ui.theme.*

/**
 * 이용약관 전문 화면 (설정 → 이용약관).
 * ConsentDetailScreen 의 마크다운 렌더 로직을 참조해 동일한 시니어 UX 표준(폰트/여백)을 유지한다.
 * 본문 소스는 ConsentDetailScreen.kt 의 TERMS_TEXT (internal const val).
 */
@Composable
fun TermsScreen(onBack: () -> Unit) {
    Scaffold(
        containerColor = BgPrimary,
        topBar = {
            TopAppBar(
                title = { Text("이용약관", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Primary,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
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
            // 약관 본문 (마크다운 스타일로 줄바꿈 처리) — ConsentDetailScreen 과 동일 규칙
            TERMS_TEXT.split("\n").forEach { line ->
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
                    else -> Text(line, fontSize = 14.sp, color = TextPrimary, lineHeight = 22.sp)
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}
