@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.dangbomcare.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dangbomcare.app.data.ServiceLocator
import com.dangbomcare.app.data.api.UserConsentsOut
import com.dangbomcare.app.data.repo.ApiResult
import com.dangbomcare.app.ui.theme.*

/**
 * 설정 → 이용 약관 동의 내역 (사용자 피드백 — 팝업창 X, 풀스크린 + 항목별 상세 진입).
 *
 * [260726] 회원가입 필수 동의 4종을 모두 표시한다.
 *   (이용약관 / 개인정보 수집·이용 / 민감정보 수집·이용 / 개인정보 및 민감정보 제3자 제공)
 *   - 라벨은 가입 화면(ConsentsScreen)과 동일 문구로 맞춘다.
 *   - 동의 여부는 하드코딩하지 않고 서버(GET /users/me 의 consents) 값을 반영한다.
 * 각 항목 클릭 → ConsentDetailScreen 으로 약관 전문 진입.
 * (연구 활용 동의는 v1.9에서 제거됨 — 사용자 피드백)
 */
@Composable
fun ConsentsViewScreen(
    onBack: () -> Unit,
    onShowDetail: (String) -> Unit
) {
    // [260726] 서버에 저장된 실제 동의 값을 표시 — 이전에는 값과 무관하게 "동의 완료"를 하드코딩했다.
    //   조회 전이거나 조회 실패면 null 로 두어 동의 여부를 단정하지 않는다(의료·법적 문구이므로 추정 금지).
    var consents by remember { mutableStateOf<UserConsentsOut?>(null) }
    LaunchedEffect(Unit) {
        val res = ServiceLocator.userRepository.getMe()
        if (res is ApiResult.Success) consents = res.data.consents
    }

    Scaffold(
        containerColor = BgSecondary,
        topBar = {
            TopAppBar(
                title = { Text("이용 약관 동의 내역", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // 안내 박스 — 회원가입 시 동의한 약관임을 명시
            Surface(
                color = LimeLight.copy(alpha = 0.4f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                    Text("📋", fontSize = 20.sp)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            "회원가입 시 동의하신 약관 내역입니다.",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "각 항목을 누르시면 전체 약관을 보실 수 있어요.",
                            fontSize = 12.sp,
                            color = TextSecondary,
                            lineHeight = 17.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            // [260726] 필수 4종 — 라벨은 가입 화면(ConsentsScreen)과 동일 문구.
            ConsentViewItem(
                label = "이용약관 동의",
                required = true,
                emphasized = false,
                accepted = consents?.terms,
                onClick = { onShowDetail("terms") }
            )
            ConsentViewItem(
                label = "개인정보 수집·이용 동의",
                required = true,
                emphasized = false,
                accepted = consents?.privacy,
                onClick = { onShowDetail("privacy") }
            )
            ConsentViewItem(
                label = "민감정보 수집·이용 동의",
                required = true,
                emphasized = true,
                accepted = consents?.sensitiveData,
                onClick = { onShowDetail("sensitive") }
            )
            ConsentViewItem(
                label = "개인정보 및 민감정보 제3자 제공 동의",
                required = true,
                emphasized = true,
                accepted = consents?.thirdParty,
                onClick = { onShowDetail("thirdparty") }
            )

            Spacer(Modifier.height(12.dp))

            // 하단 안내 — 민감정보 보호 강조
            Surface(
                color = BgCard,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Divider),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        "🔒 민감정보 보호",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = PrimaryDark
                    )
                    Spacer(Modifier.height(6.dp))
                    // [260726] 기존 문구는 "본인 동의 없이 제3자에게 제공되지 않습니다"로 끝나
                    //   제3자 제공에 필수 동의한 사용자에게 사실과 어긋나 보였다. 제공 범위를 명시하도록 수정.
                    Text(
                        "혈당·건강 데이터는 「개인정보보호법 제23조」 및 「의료법 제21조」에 따라 암호화되어 보관됩니다. 제3자 제공은 회원가입 시 동의하신 범위(당봄한의원 의료진의 진료·생활지도 목적)에서만 이루어지며, 그 밖의 제3자에게는 제공되지 않습니다.",
                        fontSize = 11.sp,
                        color = TextSecondary,
                        lineHeight = 17.sp
                    )
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun ConsentViewItem(
    label: String,
    required: Boolean,
    emphasized: Boolean,
    // [260726] 서버 동의 값. null = 아직 확인 전(또는 조회 실패) → 동의 여부를 표시하지 않는다.
    accepted: Boolean?,
    onClick: () -> Unit
) {
    Surface(
        color = BgCard,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Divider),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (accepted == true) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                contentDescription = when (accepted) {
                    true -> "동의 완료"
                    false -> "동의하지 않음"
                    null -> "동의 여부 확인 중"
                },
                tint = if (accepted == true) Primary else TextDisabled,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    label,
                    fontSize = 15.sp,
                    fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (emphasized) PrimaryDark else TextPrimary
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    when (accepted) {
                        true -> "동의 완료 · 자세히 보기"
                        false -> "동의하지 않음 · 자세히 보기"
                        null -> "자세히 보기"
                    },
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }
            Surface(
                color = if (required) ErrorRed.copy(alpha = 0.1f) else BgCardSoft,
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    if (required) "필수" else "선택",
                    fontSize = 10.sp,
                    color = if (required) ErrorRed else TextSecondary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
