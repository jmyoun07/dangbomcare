@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.dangbomcare.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dangbomcare.app.ui.theme.*

/**
 * 호환 워치 안내 화면.
 * Health Connect 호환 워치 목록 — 깔끔하게 이름만.
 */
@Composable
fun CompatibleWatchesScreen(onBack: () -> Unit) {
    Scaffold(
        containerColor = BgSecondary,
        topBar = {
            TopAppBar(
                title = { Text("호환 워치", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgSecondary)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // 안내
            Surface(
                color = LimeLight.copy(alpha = 0.4f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("📱", fontSize = 22.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "삼성 헬스와 연동되는 워치는 모두 사용 가능",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = PrimaryDark
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "삼성 헬스 → 당봄케어로 걸음·수면·심박·SpO2 데이터를 자동으로 동기화합니다",
                        fontSize = 12.sp,
                        color = TextPrimary,
                        lineHeight = 18.sp
                    )
                }
            }

            // ── Samsung ──
            WatchSection(
                brand = "Samsung",
                watches = listOf(
                    "Galaxy Watch 8",
                    "Galaxy Watch 7",
                    "Galaxy Watch Ultra",
                    "Galaxy Watch FE",
                    "Galaxy Watch 6",
                    "Galaxy Watch 6 Classic",
                    "Galaxy Watch 5",
                    "Galaxy Watch 5 Pro",
                    "Galaxy Watch 4",
                    "Galaxy Watch 4 Classic",
                    "Galaxy Fit 3",
                    "Galaxy Fit 2"
                )
            )

            // ── Google ──
            WatchSection(
                brand = "Google",
                watches = listOf(
                    "Pixel Watch 3",
                    "Pixel Watch 2",
                    "Pixel Watch"
                )
            )

            // ── Fitbit ──
            WatchSection(
                brand = "Fitbit",
                watches = listOf(
                    "Fitbit Charge 6",
                    "Fitbit Charge 5",
                    "Fitbit Versa 4",
                    "Fitbit Versa 3",
                    "Fitbit Sense 2",
                    "Fitbit Sense",
                    "Fitbit Inspire 3",
                    "Fitbit Luxe"
                )
            )

            // ── Garmin ──
            WatchSection(
                brand = "Garmin",
                watches = listOf(
                    "Garmin Venu 시리즈",
                    "Garmin Vivoactive 시리즈",
                    "Garmin Forerunner 시리즈",
                    "Garmin Fenix 시리즈"
                )
            )

            // ── 기타 안드로이드 워치 ──
            WatchSection(
                brand = "기타",
                watches = listOf(
                    "Xiaomi Mi Band 8 / 9",
                    "Xiaomi Watch 시리즈",
                    "Huawei Watch (Health 앱)",
                    "Amazfit (Zepp 앱)",
                    "Wear OS 5 / 6 워치 전반"
                )
            )

            // 도움말
            Surface(
                color = BgCardSoft,
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "💡 워치가 목록에 없나요?",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "워치의 공식 앱(Samsung Health, Fitbit, Garmin Connect 등)에서 삼성 헬스로 데이터 연동을 켜시면 대부분 작동합니다.",
                        fontSize = 12.sp,
                        color = TextSecondary,
                        lineHeight = 18.sp
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun WatchSection(brand: String, watches: List<String>) {
    Column {
        Text(
            brand,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = BgCard),
            border = BorderStroke(1.dp, Divider)
        ) {
            Column {
                watches.forEachIndexed { idx, name ->
                    if (idx > 0) HorizontalDivider(color = Divider)
                    WatchRow(name = name)
                }
            }
        }
    }
}

@Composable
private fun WatchRow(name: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 왼쪽: 워치 아이콘 (이미지 자리 — 추후 실제 사진 추가 가능)
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(BgCardSoft),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Watch,
                contentDescription = null,
                tint = Primary,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(Modifier.width(14.dp))
        // 오른쪽: 워치 이름
        Text(
            name,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
            modifier = Modifier.weight(1f)
        )
    }
}
