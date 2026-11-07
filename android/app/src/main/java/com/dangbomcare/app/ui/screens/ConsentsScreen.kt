@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.dangbomcare.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dangbomcare.app.data.ServiceLocator
import com.dangbomcare.app.data.api.UserConsentsRequest
import com.dangbomcare.app.ui.components.AutoSizeSingleLineText
import com.dangbomcare.app.ui.components.BigButton
import com.dangbomcare.app.ui.theme.*
import kotlinx.coroutines.launch

// [260726] 동의 4개 라벨의 글자 크기를 서로 같게 맞추기 위한 기준 문자열(= 가장 긴 라벨).
//   각 행이 이 문자열을 기준으로 축소율을 계산하므로 4개가 항상 동일한 크기로 렌더된다.
private const val CONSENT_LABEL_FIT = "개인정보 및 민감정보 제3자 제공 동의"

/**
 * 회원가입 직후 동의서 — 필수 4개 + 선택 1개(마케팅, 260728 추가).
 * 선택 항목은 "다음" 버튼 게이트(canProceed)에 포함되지 않는다.
 * 각 항목 탭 → 전체 약관 화면(ConsentDetailScreen)으로 이동.
 * "보기/접기" 패턴 대신 별도 화면 진입 (슈가지니/카카오 표준).
 */
@Composable
fun ConsentsScreen(
    onCompleted: () -> Unit,
    onBack: () -> Unit,
    onShowDetail: (String) -> Unit
) {
    var terms by rememberSaveable { mutableStateOf(false) }
    var privacy by rememberSaveable { mutableStateOf(false) }
    var sensitive by rememberSaveable { mutableStateOf(false) }
    // [260726] 개인정보 및 민감정보 제3자 제공 동의(당봄한의원) — 필수.
    //   백엔드 third_party_accepted 컬럼이 추가되어 저장(POST)·통과조건(게이트) 모두 연결 완료.
    var thirdParty by rememberSaveable { mutableStateOf(false) }
    // [260728] 마케팅 정보 수신 동의 — 선택. 체크하지 않아도 온보딩을 통과해야 한다.
    var marketing by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // [260728] 두 개념을 분리한다. 섞으면 마케팅 미체크 시 온보딩이 막힌다.
    //   canProceed = 필수 4개만       → "다음" 버튼 활성화 조건
    //   allChecked = 필수 4개 + 선택  → "전체 동의" 체크박스 표시/동작
    val canProceed = terms && privacy && sensitive && thirdParty
    val allChecked = canProceed && marketing

    fun toggleAll(v: Boolean) {
        terms = v; privacy = v; sensitive = v; thirdParty = v; marketing = v
    }

    // [260821] 서버에 이미 저장된 마케팅 동의를 프리필한다.
    //   이 화면은 신규 가입 외에도 '필수 동의가 추가됐을 때 기존 사용자를 다시 태우는' 경로로 재진입한다.
    //   그때 체크박스가 항상 false 로 시작하는데 저장 시 그 값을 무조건 전송하므로,
    //   설정에서 마케팅 수신을 켜둔 사용자가 재동의 화면을 지나가는 것만으로 조용히 철회되고
    //   marketing_revoked_at 에 거짓 철회 시각이 남는다(users.py 의 True→False 전이 = 철회 기록).
    //   → 진입 시 한 번 읽어 초기값으로 넣는다. 실패해도 화면은 그대로 동작한다(기본 false 유지).
    LaunchedEffect(Unit) {
        runCatching {
            val res = ServiceLocator.userRepository.getMe()
            if (res is com.dangbomcare.app.data.repo.ApiResult.Success) {
                if (res.data.consents?.marketing == true) marketing = true
            }
        }
    }

    Scaffold(
        containerColor = BgSecondary,
        topBar = {
            TopAppBar(
                title = { Text("이용 동의", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgSecondary)
            )
        },
        bottomBar = {
            Surface(color = BgSecondary, shadowElevation = 8.dp) {
                Column(
                    modifier = Modifier
                        .navigationBarsPadding()  // 시스템 네비바와 겹침 방지
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    error?.let {
                        Text(it, color = ErrorRed, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
                    }
                    BigButton(
                        text = if (saving) "저장 중..." else "다음",
                        // [260728] 필수 4개만 보면 된다. 선택(마케팅)은 게이트에 넣지 않는다.
                        enabled = canProceed && !saving,
                        onClick = {
                            saving = true
                            error = null
                            scope.launch {
                                val res = ServiceLocator.userRepository.postConsents(
                                    UserConsentsRequest(
                                        termsAccepted = terms,
                                        privacyAccepted = privacy,
                                        sensitiveDataAccepted = sensitive,
                                        // [260726] 4번째 동의도 실제로 서버에 저장 (이전에는 화면에서만 받고 버려짐)
                                        thirdPartyAccepted = thirdParty,
                                        // [260728] 선택 동의(마케팅)도 체크 여부를 그대로 서버에 저장
                                        marketingAccepted = marketing,
                                        researchUseAccepted = false  // 연구 활용 동의는 사용 안 함
                                    )
                                )
                                saving = false
                                when (res) {
                                    is com.dangbomcare.app.data.repo.ApiResult.Success -> onCompleted()
                                    is com.dangbomcare.app.data.repo.ApiResult.Error -> {
                                        error = "저장 실패: ${res.message}"
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)  // 12 → 8 축소
        ) {
            Spacer(Modifier.height(4.dp))  // 8 → 4

            // 진행 단계 표시 (2/2 — 가입 완료, 동의 단계)
            com.dangbomcare.app.ui.components.StepIndicator(current = 2, total = 2)
            Spacer(Modifier.height(8.dp))  // 20 → 8 (대폭 축소)

            Text(
                "이용 약관에 동의해 주세요",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                lineHeight = 28.sp
            )
            Text(
                // [260728] 선택 항목이 생겼으므로 "필수 동의 절차" 단정 표현을 수정.
                "필수 항목에 동의해 주세요. 선택 항목은 원하실 때만 체크하시면 됩니다.",
                fontSize = 13.sp,
                color = TextSecondary
            )

            // 사용자 피드백 — "AES-256 ~~" 상세 보안 박스는 제거 (시니어에게 복잡함).
            // 필수 보장 문구만 한 줄로 압축.
            Surface(
                color = LimeLight.copy(alpha = 0.5f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("🔒", fontSize = 18.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "혈당 등 민감정보는 암호화 보관되며, 동의 없이 제3자에게 제공되지 않습니다.",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = PrimaryDark,
                        lineHeight = 17.sp
                    )
                }
            }

            // 전체 동의
            Surface(
                color = BgCard,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { toggleAll(!allChecked) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),  // 18 → 14
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (allChecked) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                        contentDescription = null,
                        tint = if (allChecked) Primary else TextDisabled,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("전체 약관에 동의합니다", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        // [260728] 이제 선택 항목까지 함께 체크되므로 문구를 맞춘다.
                        Text("필수 항목과 선택 항목을 모두 포함합니다", fontSize = 12.sp, color = TextSecondary)
                    }
                }
            }

            HorizontalDivider(color = Divider)

            // 필수 4개 (terms / privacy / sensitive / thirdParty) — 4개 모두 서버 저장 + 게이트 반영됨.
            // [260726] 글자 크기는 CONSENT_LABEL_FIT(가장 긴 라벨) 기준으로 4개가 동일하게 계산된다.
            Text(
                "필수 항목",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = TextSecondary
            )
            ConsentItemRow(
                label = "이용약관 동의",
                required = true,
                checked = terms,
                onToggle = { terms = !terms },
                onShowDetail = { onShowDetail("terms") }
            )
            ConsentItemRow(
                label = "개인정보 수집·이용 동의",
                required = true,
                checked = privacy,
                onToggle = { privacy = !privacy },
                onShowDetail = { onShowDetail("privacy") }
            )
            ConsentItemRow(
                label = "민감정보 수집·이용 동의",
                required = true,
                checked = sensitive,
                onToggle = { sensitive = !sensitive },
                onShowDetail = { onShowDetail("sensitive") }
            )
            ConsentItemRow(
                label = "개인정보 및 민감정보 제3자 제공 동의",
                required = true,
                checked = thirdParty,
                onToggle = { thirdParty = !thirdParty },
                onShowDetail = { onShowDetail("thirdparty") }
            )

            // [260728] 선택 항목 — 시니어가 "여기까지 전부 체크해야 하나?" 오해하지 않도록
            //   구분선 + 소제목으로 필수 그룹과 확실히 떼어놓는다.
            Spacer(Modifier.height(2.dp))
            HorizontalDivider(color = Divider)
            Text(
                "선택 항목 · 동의하지 않아도 이용할 수 있어요",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = TextSecondary,
                lineHeight = 16.sp
            )
            ConsentItemRow(
                label = "이벤트 및 마케팅 정보 수신",
                required = false,      // → "선택" 배지 + 회색 표기 (기존 렌더러 재사용)
                checked = marketing,
                onToggle = { marketing = !marketing },
                onShowDetail = { onShowDetail("marketing") }
            )

            // bottomBar는 Scaffold가 자동 padding 처리. 하단 여분만 최소로.
            Spacer(Modifier.height(8.dp))  // 140 → 8 (대폭 축소)
        }
    }
}

@Composable
private fun ConsentItemRow(
    label: String,
    required: Boolean,
    checked: Boolean,
    onToggle: () -> Unit,
    onShowDetail: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth(),  // vertical=4dp 제거 — 외부 spacedBy로 처리
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onToggle, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = if (checked) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                contentDescription = null,
                tint = if (checked) Primary else TextDisabled,
                modifier = Modifier.size(24.dp)
            )
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable { onShowDetail() }
                .padding(vertical = 6.dp),  // 12 → 6 축소
            verticalAlignment = Alignment.CenterVertically
        ) {
            // [260726] 동의 4개 스타일 통일 — 굵기·색(검정)·크기를 모두 동일하게.
            //   13sp + maxLines=1 만으로는 Theme 강제 fontScale(최소 1.15) 환경의 360dp급 기기에서
            //   가장 긴 "개인정보 및 민감정보 제3자 제공 동의"가 잘렸다.
            //   → AutoSizeSingleLineText + fitText(가장 긴 라벨)로 한 줄을 보장하면서
            //     4개 항목이 항상 같은 글자 크기로 계산되게 한다.
            //   [260728] 오른쪽 "필수/선택" 배지가 이미 구분을 표시하므로 "(선택) " 접두어는 제거.
            //     (필수 행과 라벨 시작 위치·글자 크기를 동일하게 유지)
            AutoSizeSingleLineText(
                text = label,
                fitText = CONSENT_LABEL_FIT,
                maxFontSize = 13.sp,
                minFontSize = 10.sp,
                fontWeight = FontWeight.Normal,
                color = TextPrimary,
                modifier = Modifier.weight(1f)
            )
            Text(
                if (required) "필수" else "선택",
                fontSize = 12.sp,
                color = if (required) Primary else TextSecondary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(end = 8.dp)
            )
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = "자세히 보기",
                tint = TextSecondary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
