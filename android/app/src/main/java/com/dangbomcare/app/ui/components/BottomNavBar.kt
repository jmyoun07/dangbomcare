package com.dangbomcare.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dangbomcare.app.ui.navigation.MainTab
import com.dangbomcare.app.ui.theme.*

/**
 * 하단 탭 + 중앙 ⊕ FAB.
 *
 * v1.9 수정사항:
 * - 시스템 네비게이션 바 인셋 처리 (navigationBarsPadding)
 * - FAB 위쪽 잘림 방지 (Box height 확장)
 */
@Composable
fun BottomNavBar(
    currentTab: MainTab?,
    onTabSelected: (MainTab) -> Unit,
    onFabClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 컨테이너 — 시스템 네비 인셋 자동 적용
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = BgPrimary,
        shadowElevation = 8.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()  // ⭐ 시스템 네비 영역 피하기
                .height(72.dp)              // 다른 탭과 같은 높이 (위쪽 padding 제거)
        ) {
            // 4 tabs (홈, 기록, [공간], 리포트, 설정)
            // 5등분 weight 슬롯 — 좁은 화면(폴드 커버 ~320dp)·큰 글씨에서도 오버플로 없음.
            //   가운데 슬롯이 FAB 자리(Center 정렬과 일치).
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TabItem(MainTab.HOME, currentTab == MainTab.HOME, Icons.Filled.Home, onTabSelected, Modifier.weight(1f))
                TabItem(MainTab.RECORDS, currentTab == MainTab.RECORDS, Icons.Filled.EditNote, onTabSelected, Modifier.weight(1f))
                Spacer(Modifier.weight(1f))  // FAB 공간
                TabItem(MainTab.REPORTS, currentTab == MainTab.REPORTS, Icons.Filled.BarChart, onTabSelected, Modifier.weight(1f))
                TabItem(MainTab.SETTINGS, currentTab == MainTab.SETTINGS, Icons.Filled.Settings, onTabSelected, Modifier.weight(1f))
            }

            // 중앙 ⊕ FAB — 다른 탭과 동일한 중앙 정렬 (튀어나오지 않음)
            Box(
                modifier = Modifier
                    .align(Alignment.Center)  // ⭐ TopCenter → Center (튀어나옴 제거)
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Primary)
                    .clickable { onFabClick() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = "입력",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}

@Composable
private fun TabItem(
    tab: MainTab,
    isActive: Boolean,
    icon: ImageVector,
    onClick: (MainTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor = if (isActive) LimeLight else Color.Transparent
    val contentColor = if (isActive) Primary else TextSecondary

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(bgColor)
                .clickable { onClick(tab) }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = tab.label, tint = contentColor, modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(2.dp))
            // 큰 글씨 설정에서 슬롯 폭을 넘으면 자동 축소 (잘림 방지)
            AutoSizeSingleLineText(
                tab.label,
                maxFontSize = 11.sp,
                minFontSize = 9.5.sp,  // 시니어 가독성 하한
                color = contentColor,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium
            )
        }
    }
}
