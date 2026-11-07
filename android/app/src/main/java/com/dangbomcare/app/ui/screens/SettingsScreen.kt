@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.dangbomcare.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dangbomcare.app.ui.components.SectionCard
import com.dangbomcare.app.ui.theme.*
import com.dangbomcare.app.ui.viewmodel.AuthViewModel
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onLogout: () -> Unit,
    onGotoWatchDebug: () -> Unit,
    onGotoAccountDeletion: () -> Unit = {},
    onGotoDataDiagnostic: () -> Unit = {},
    onGotoExportData: () -> Unit = {},
    onGotoNotificationSettings: () -> Unit = {},  // 사용자 피드백 — 알림 박스 분리
    onGotoConsentsView: () -> Unit = {},          // 사용자 피드백 — 약관 팝업 X, 풀스크린
    onGotoTerms: () -> Unit = {},                 // [260722] 이용약관 풀스크린
    onGotoPrivacy: () -> Unit = {},               // [260722] 개인정보처리방침 풀스크린
    onGotoMarketingConsent: () -> Unit = {},      // [260728] 마케팅 동의서 전문 (선택 동의)
    onGotoOpenSourceLicense: () -> Unit = {},     // [260729] 오픈소스 라이선스 고지
    onGotoMedicalGoals: () -> Unit = {},          // [우노 4] 의료진 목표 입력 (PIN 잠금)
    scrollToTopTrigger: Int = 0,
    vm: AuthViewModel = viewModel()
) {
    var showLogoutDialog by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    LaunchedEffect(scrollToTopTrigger) {
        if (scrollToTopTrigger > 0) scrollState.animateScrollTo(0)
    }

    Scaffold(
        containerColor = BgSecondary,
        topBar = {
            TopAppBar(
                title = { Text("설정", fontWeight = FontWeight.Bold) },
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
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp),
            // [260721 v2] 섹션 간격 16→12dp: 하단 여백 인상 개선.
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // 사용자 피드백: 알림 토글들 펼쳐서 보여주지 말고 박스 하나 → 클릭 시 알림 화면 이동
            SectionCard(title = "알림") {
                // [260705 피드백] 생활패턴 박스를 알림설정 화면 안으로 통합 → 메뉴명도 변경
                ClickableSettingsRow(
                    label = "생활패턴·알림 설정",
                    icon = Icons.Filled.Notifications,
                    onClick = onGotoNotificationSettings
                )
            }

            // [PDF 2 — 개인 공복혈당 범위] 평소 공복혈당 범위 보기/수정 (점수 채점 기준선)
            GlucoseRangeSettingCard()

            // 사용자 피드백 — 기기/데이터 박스는 워치 동기화 행만 유지.
            //   "워치 데이터 관리"·"내 데이터 내보내기"·"워치 데이터 점검" 항목은 사용자 피드백으로 제거.
            SectionCard(title = "기기 / 데이터") {
                // [사용자 피드백] 큰 워치 카드 대신 다른 행들과 동일한 '삼성헬스 동기화' 행 — 탭하면 오늘/어제 안전 동기화.
                WatchSyncRow()
            }

            // [260730] 목표 입력은 의료진 웹으로 이관 → 앱은 의료진이 설정한 목표를 '확인'만 한다.
            SectionCard(title = "의료진") {
                ClickableSettingsRow(
                    label = "의료진 목표 확인",
                    icon = Icons.Filled.MedicalInformation,
                    onClick = onGotoMedicalGoals
                )
            }

            // 약관 — [260722 r35] 이용약관 / 개인정보처리방침 두 항목만.
            //   동의 내역 보기는 이용약관·개인정보 각각 열람 가능하므로 GDPR/PIPA 요건 충족되어 제거.
            SectionCard(title = "약관 / 정책") {
                ClickableSettingsRow(
                    label = "이용약관",
                    icon = Icons.Filled.Description,
                    onClick = onGotoTerms
                )
                ClickableSettingsRow(
                    label = "개인정보처리방침",
                    icon = Icons.Filled.PrivacyTip,
                    onClick = onGotoPrivacy
                )
                // [260729] 오픈소스 라이선스 고지 — 이용약관·개인정보처리방침과 완전히 동일한 행 스타일.
                ClickableSettingsRow(
                    label = "오픈소스 라이선스",
                    icon = Icons.Filled.Code,
                    onClick = onGotoOpenSourceLicense
                )
                // [260728 우노 마케팅 동의] 유일한 '선택' 동의 → 여기서만 켜고 끌 수 있다(철회 경로).
                //   필수 4종(이용약관·개인정보·민감정보·제3자 제공)에는 토글을 붙이지 않는다.
                MarketingConsentRow(onShowFullText = onGotoMarketingConsent)
            }

            Card(
                modifier = Modifier.fillMaxWidth().clickable { showLogoutDialog = true },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = BgCard),
                border = androidx.compose.foundation.BorderStroke(1.dp, ErrorRed.copy(alpha = 0.3f))
            ) {
                Row(
                    modifier = Modifier.padding(20.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null, tint = ErrorRed)
                    Spacer(Modifier.width(12.dp))
                    Text("로그아웃", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = ErrorRed)
                }
            }

            // 버전 표기 (앱 하단 회색 소문자 — 정보 섹션 삭제 대체)
            Spacer(Modifier.height(12.dp))
            Text(
                "버전 ${com.dangbomcare.app.BuildConfig.VERSION_NAME}",
                fontSize = 11.sp,
                color = androidx.compose.ui.graphics.Color(0xFF999999),
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            // 회원 탈퇴 (덜 강조)
            TextButton(
                onClick = onGotoAccountDeletion,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "회원 탈퇴",
                    fontSize = 13.sp,
                    color = TextSecondary,
                    style = androidx.compose.ui.text.TextStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)
                )
            }

            // [260721 v2] 하단 잔여 여백 지적 → 마지막 Spacer 제거 (TextButton 자체 padding으로 충분).
        }
    }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("로그아웃 하시겠어요?") },
            text = { Text("다시 로그인하려면 이메일/비밀번호가 필요해요.") },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutDialog = false
                    // [260722 P0 프라이버시 CRITICAL] signOut() 완료(SharedPreferences clear 등) 후에만
                    // onLogout()을 호출해야 함. 이전 코드는 launch 시작 직후 곧바로 onLogout() → 네비게이션이
                    // popUpTo(0){inclusive=true}로 백스택을 파괴 → ViewModel 파괴 → 코루틴 취소 → 사용자
                    // 데이터/토큰 잔존이라는 CRITICAL 버그를 발생시킴.
                    vm.signOut(onDone = { onLogout() })
                }) {
                    Text("로그아웃", color = ErrorRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text("취소")
                }
            }
        )
    }
}

@Composable
private fun SettingsRow(label: String, icon: ImageVector, enabled: Boolean) {
    var checked by remember { mutableStateOf(enabled) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = Primary)
        Spacer(Modifier.width(12.dp))
        Text(label, fontSize = 15.sp, modifier = Modifier.weight(1f), color = TextPrimary)
        Switch(
            checked = checked,
            onCheckedChange = { checked = it },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Primary,
                checkedTrackColor = LimeAccent
            )
        )
    }
}

/**
 * 실제 AlarmScheduler에 연결된 알람 토글.
 * 시각은 prefs에 저장된 사용자 설정 (또는 기본값) — 시간 라벨 클릭 시 WheelTimePicker 다이얼로그로 변경 가능.
 * (사용자 피드백 — "알림 시간 변경도 처음 알림 설정할 때처럼 드래그로")
 */
@Composable
internal fun AlarmToggleRow(
    label: String,
    icon: ImageVector,
    type: com.dangbomcare.app.notification.AlarmType
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var checked by remember {
        mutableStateOf(com.dangbomcare.app.notification.AlarmScheduler.isEnabled(context, type))
    }
    // 시간 변경 후 즉시 라벨이 갱신되도록 state로 관리
    val initial = com.dangbomcare.app.notification.AlarmScheduler.getHourMinute(context, type)
    var hour by remember { mutableStateOf(initial.first) }
    var minute by remember { mutableStateOf(initial.second) }
    val timeLabel = "%02d:%02d".format(hour, minute)
    var showTimePicker by remember { mutableStateOf(false) }

    if (showTimePicker) {
        WheelTimePickerDialog(
            initialTime = timeLabel,
            title = "$label 시각",
            onConfirm = { newTime ->
                val (h, m) = newTime.split(":").map { it.toInt() }
                hour = h
                minute = m
                com.dangbomcare.app.notification.AlarmScheduler.setTime(context, type, h, m)
                showTimePicker = false
            },
            onDismiss = { showTimePicker = false }
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = Primary)
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable { showTimePicker = true }
                .padding(vertical = 4.dp)
        ) {
            Text(label, fontSize = 15.sp, color = TextPrimary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(timeLabel, fontSize = 13.sp, color = Primary, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(4.dp))
                Text("· 시각 변경", fontSize = 11.sp, color = TextSecondary)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = {
                checked = it
                com.dangbomcare.app.notification.AlarmScheduler.setEnabled(context, type, it)
            },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Primary,
                checkedTrackColor = LimeAccent
            )
        )
    }
}

/**
 * [260705 → 260714 → 260719 재구성 v3]
 *   생활패턴 = 시각 편집 전용 (알림 토글 제거).
 *   알림 on/off는 아래 별도 '알림 설정' 섹션(NotificationSettingsScreen)에서 담당.
 *   → 시각 변경 시 관련 알림은 자동 재예약(source of truth), 켜고 끄기는 알림 섹션에서.
 */
@Composable
internal fun LifePatternSection() {
    SectionCard(title = "생활패턴 (식사·수면 시각)") {
        // 기상 시각 — MORNING (공복혈당 측정 알림 트리거)
        LifePatternRow(
            label = "기상 시각",
            sub = "기상 후 공복혈당 측정 알림 시각",
            timeSource = TimeSource.Alarm(com.dangbomcare.app.notification.AlarmType.MORNING),
            icon = Icons.Filled.WbSunny
        )
        LifePatternRow(
            label = "아침식사 시각",
            sub = "",
            timeSource = TimeSource.Prefs("breakfast", "07:30"),
            icon = Icons.Filled.RestaurantMenu
        )
        LifePatternRow(
            label = "점심식사 시각",
            sub = "",
            timeSource = TimeSource.Prefs("lunch", "12:30"),
            icon = Icons.Filled.RestaurantMenu
        )
        // [260721] 저녁 시각 저장 시 LifePatternRow 내부에서 DINNER 알림(=저녁+30분)을 자동 재설정.
        //   → 별도 '저녁 식사 마감' 행은 삭제(중복 편집 방지).
        LifePatternRow(
            label = "저녁식사 시각",
            sub = "30분 후 식사 마감 알림",
            timeSource = TimeSource.Prefs("supper", "18:00"),
            icon = Icons.Filled.RestaurantMenu
        )
        LifePatternRow(
            label = "취침 시각",
            sub = "30분 전 취침 알림",
            timeSource = TimeSource.Alarm(com.dangbomcare.app.notification.AlarmType.BEDTIME),
            icon = Icons.Filled.Bedtime
        )
    }
}

/** 시각 저장 소스: 로컬 life_pattern prefs (식사) 또는 AlarmScheduler prefs (알람 시각). */
private sealed class TimeSource {
    data class Prefs(val key: String, val default: String) : TimeSource()
    data class Alarm(val type: com.dangbomcare.app.notification.AlarmType) : TimeSource()
}

/**
 * [260714 → 260719 재구성 v3] 생활패턴 행 — 시각 편집 전용.
 *   알림 on/off 토글은 별도 알림 설정 섹션으로 분리됨.
 *   시각을 바꾸면 관련 알람은 자동 재예약(AlarmScheduler.setTime).
 */
@Composable
private fun LifePatternRow(
    label: String,
    sub: String,
    timeSource: TimeSource,
    icon: ImageVector,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    val initialTime = when (timeSource) {
        is TimeSource.Prefs -> {
            val prefs = context.getSharedPreferences("life_pattern", android.content.Context.MODE_PRIVATE)
            prefs.getString(timeSource.key, timeSource.default) ?: timeSource.default
        }
        is TimeSource.Alarm -> {
            val (h, m) = com.dangbomcare.app.notification.AlarmScheduler.getHourMinute(context, timeSource.type)
            "%02d:%02d".format(h, m)
        }
    }

    var time by remember { mutableStateOf(initialTime) }
    var showPicker by remember { mutableStateOf(false) }

    if (showPicker) {
        WheelTimePickerDialog(
            initialTime = time,
            // [260721] 라벨이 이미 "N 시각"으로 끝나므로 " 시각" 접미 삭제 (조사 중복 방지)
            title = label,
            onConfirm = { newTime ->
                time = newTime
                // [260722] 원자 동기화 가드:
                //   각 단계(로컬 prefs → 관련 알람 재예약 → 백엔드 sync)를 개별 try/catch로 감싸
                //   중간 실패로 인한 편차(예: prefs만 저장되고 알람은 실패)를 명시적으로 흡수.
                //   최소한 로컬 커밋은 백엔드 실패와 무관하게 유지되도록 한다.
                scope.launch {
                    try {
                        when (timeSource) {
                            is TimeSource.Prefs -> {
                                // 1. 로컬 prefs 저장
                                try {
                                    context.getSharedPreferences("life_pattern", android.content.Context.MODE_PRIVATE)
                                        .edit().putString(timeSource.key, newTime).apply()
                                } catch (e: Exception) {
                                    android.util.Log.w("SettingsScreen", "life_pattern prefs 저장 실패(${timeSource.key})", e)
                                }
                                // 2. supper인 경우 DINNER 알람을 저녁+30분으로 자동 재예약
                                if (timeSource.key == "supper") {
                                    try {
                                        val (supperH, supperM) = newTime.split(":").map { it.toInt() }
                                        val addMin = supperM + 30
                                        val addH = supperH + addMin / 60
                                        val newM = addMin % 60
                                        val newH = addH % 24
                                        com.dangbomcare.app.notification.AlarmScheduler.setTime(
                                            context,
                                            com.dangbomcare.app.notification.AlarmType.DINNER,
                                            newH,
                                            newM
                                        )
                                    } catch (e: Exception) {
                                        android.util.Log.w("SettingsScreen", "DINNER 알람 재예약 실패", e)
                                    }
                                }
                            }
                            is TimeSource.Alarm -> {
                                // 1. AlarmScheduler prefs 저장 + 재예약
                                try {
                                    val (h, m) = newTime.split(":").map { it.toInt() }
                                    com.dangbomcare.app.notification.AlarmScheduler.setTime(context, timeSource.type, h, m)
                                } catch (e: Exception) {
                                    android.util.Log.w("SettingsScreen", "알람 저장/재예약 실패(${timeSource.type})", e)
                                }
                            }
                        }
                        // 3. 백엔드 sync — supper(Prefs)/Alarm 공통. 실패해도 로컬은 유지.
                        val needsSync = timeSource is TimeSource.Alarm ||
                            (timeSource is TimeSource.Prefs && timeSource.key == "supper")
                        if (needsSync) {
                            try {
                                syncLifePatternToBackend(context)
                            } catch (e: Exception) {
                                android.util.Log.w("SettingsScreen", "syncLifePatternToBackend 실패", e)
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("SettingsScreen", "LifePatternRow onConfirm 예상외 실패", e)
                    } finally {
                        showPicker = false
                    }
                }
            },
            onDismiss = { showPicker = false }
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth().clickable { showPicker = true }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = Primary)
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            // [260721 v2] 라벨-서브텍스트 간격이 커 보인다는 피드백 → 명시적 2dp 간격.
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                label,
                fontSize = 15.sp,
                lineHeight = 17.sp,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold
            )
            // [260721] 서브 문구가 비어있으면 라인 자체를 렌더하지 않음 (아침·점심식사 시각).
            if (sub.isNotEmpty()) {
                // [260721 v2] lineHeight 명시로 폰트 메트릭 여유 축소 → 라벨과 딱 붙게.
                Text(sub, fontSize = 11.sp, lineHeight = 13.sp, color = TextSecondary)
            }
        }
        Text(time, fontSize = 16.sp, color = Primary, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(4.dp))
        Text("· 변경", fontSize = 11.sp, color = TextSecondary)
    }
}

/** 로컬 알람에 저장된 아침/저녁/취침 시각을 백엔드 설정과 동기화 (점수·라벨 일관성). 실패해도 무시. */
private suspend fun syncLifePatternToBackend(context: android.content.Context) {
    fun fmt(t: com.dangbomcare.app.notification.AlarmType): String {
        val (h, m) = com.dangbomcare.app.notification.AlarmScheduler.getHourMinute(context, t)
        return "%02d:%02d".format(h, m)
    }
    runCatching {
        com.dangbomcare.app.data.ServiceLocator.userRepository.updateSettings(
            com.dangbomcare.app.data.api.UserSettingsRequest(
                glucoseCheckTime = fmt(com.dangbomcare.app.notification.AlarmType.MORNING),
                dinnerDeadlineTime = fmt(com.dangbomcare.app.notification.AlarmType.DINNER),
                bedtime = fmt(com.dangbomcare.app.notification.AlarmType.BEDTIME)
            )
        )
    }
}

/**
 * 휠 드래그 시각 피커 다이얼로그 — 시니어 친화 (처음 알림 설정과 동일한 UX).
 */
@Composable
private fun WheelTimePickerDialog(
    initialTime: String,
    title: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var currentTime by remember { mutableStateOf(initialTime) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = BgCard,
            modifier = Modifier.fillMaxWidth(0.92f)
        ) {
            // 가로모드/분할화면(높이 부족)에서 버튼 밀림 방지
            Column(modifier = Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
                Text(
                    title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "위아래로 드래그해서 시각을 맞춰주세요",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
                Spacer(Modifier.height(16.dp))
                com.dangbomcare.app.ui.components.WheelTimePicker(
                    initialTime = initialTime,
                    onTimeChange = { currentTime = it }
                )
                Spacer(Modifier.height(20.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("취소", fontSize = 14.sp, color = TextSecondary)
                    }
                    Button(
                        onClick = { onConfirm(currentTime) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary)
                    ) {
                        Text("확인", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ClickableSettingsRow(label: String, icon: ImageVector, valueText: String? = null, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = Primary)
        Spacer(Modifier.width(12.dp))
        Text(label, fontSize = 15.sp, modifier = Modifier.weight(1f), color = TextPrimary)
        if (valueText != null) {
            Text(valueText, fontSize = 14.sp, color = TextSecondary, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(6.dp))
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = TextSecondary)
    }
}

/**
 * [260728 우노 마케팅 동의] 약관/정책 섹션의 '이벤트 및 마케팅 정보 수신' 토글 (유일한 선택 동의).
 *
 *  • 현재 상태는 GET /api/users/me 의 `consents.marketing` 에서 읽는다.
 *    (settings 가 아니다 — 백엔드 users.py get_me 가 UserConsentsOut 안에 동의 값을 담아 내려준다.)
 *  • 켜고 끄면 PATCH api/users/me/consents/marketing 으로 즉시 반영.
 *    낙관적 업데이트 → 실패하면 이전 값으로 원복하고 행 아래에 실패 문구를 남긴다.
 *    (조용히 어긋나면 사용자는 '거부했다'고 믿는데 서버는 '수신 중'인 상태가 된다.)
 *  • 제목을 누르면 마케팅 동의서 전문으로 이동.
 */
@Composable
private fun MarketingConsentRow(onShowFullText: () -> Unit) {
    val scope = rememberCoroutineScope()
    var checked by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var loadFailed by remember { mutableStateOf(false) }
    var reloadTrigger by remember { mutableStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reloadTrigger) {
        loadFailed = false
        val res = com.dangbomcare.app.data.ServiceLocator.userRepository.getMe()
        if (res is com.dangbomcare.app.data.repo.ApiResult.Success) {
            checked = res.data.consents?.marketing == true
            loaded = true
        } else {
            loadFailed = true
        }
    }

    val statusText = when {
        loadFailed && !loaded -> "수신 여부를 불러오지 못했어요"
        !loaded -> "불러오는 중..."
        checked -> "수신 중"
        else -> "수신 거부됨"
    }

    // [260730 v2] 아이콘·제목·토글을 한 Row(상단 정렬)에 두고, 상태·전문보기는 제목 바로 아래(같은 Column)에
    //   붙인다. v54에서 토글(48dp)이 상단 라인 높이를 키워 위 간격이 벌어지고 부제목이 멀어졌던 것을 해소.
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            Icons.Filled.Campaign, contentDescription = null, tint = Primary,
            modifier = Modifier.padding(top = 1.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text("이벤트 및 마케팅 정보 수신", fontSize = 15.sp, color = TextPrimary)
            Row(
                modifier = Modifier.clickable { onShowFullText() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    statusText,
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                    color = if (loadFailed && !loaded) ErrorRed else TextSecondary
                )
                Spacer(Modifier.width(6.dp))
                Text("· 전문 보기", fontSize = 11.sp, color = Primary, fontWeight = FontWeight.SemiBold)
            }
            errorMsg?.let {
                Text("⚠️ $it", fontSize = 11.sp, lineHeight = 15.sp, color = ErrorRed)
            }
        }
        when {
            // 최초 상태 조회 실패 → 토글을 임의로 false 로 보여주면 안 된다(오해 유발). 다시 시도 버튼.
            loadFailed && !loaded -> {
                IconButton(onClick = { reloadTrigger += 1 }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Refresh, contentDescription = "다시 시도", tint = Primary)
                }
            }
            !loaded -> {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Primary)
            }
            else -> {
                Switch(
                    checked = checked,
                    enabled = !saving,
                    onCheckedChange = { want ->
                        val prev = checked
                        checked = want            // 1) 낙관적 반영 — 시니어에게 즉시 피드백
                        saving = true
                        errorMsg = null
                        scope.launch {
                            val res = com.dangbomcare.app.data.ServiceLocator.userRepository
                                .updateMarketingConsent(want)
                            saving = false
                            if (res is com.dangbomcare.app.data.repo.ApiResult.Error) {
                                checked = prev    // 2) 실패 → 원복 (서버와 어긋난 상태로 남기지 않는다)
                                errorMsg = if (want) {
                                    "수신 동의를 저장하지 못했어요. 인터넷을 확인하고 다시 시도해주세요."
                                } else {
                                    "수신 거부를 저장하지 못했어요. 인터넷을 확인하고 다시 시도해주세요."
                                }
                            }
                        }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Primary,
                        checkedTrackColor = LimeAccent
                    )
                )
            }
        }
    }
}

/**
 * [PDF 2 — 개인 공복혈당 범위] 설정에서 평소 공복혈당 범위를 보고/수정.
 *   /me 로 현재 범위를 읽어 표시 → 다이얼로그에서 수정 → updateMe 저장.
 *   (온보딩에서 건너뛴 사용자도 여기서 설정 가능. 미설정 시 기존 절대 기준 채점.)
 */
@Composable
private fun GlucoseRangeSettingCard() {
    val scope = rememberCoroutineScope()
    var low by remember { mutableStateOf<Int?>(null) }
    var high by remember { mutableStateOf<Int?>(null) }
    var showDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val res = com.dangbomcare.app.data.ServiceLocator.userRepository.getMe()
        if (res is com.dangbomcare.app.data.repo.ApiResult.Success) {
            low = res.data.glucoseNormalLow
            high = res.data.glucoseNormalHigh
        }
    }

    SectionCard(title = "건강 정보") {
        ClickableSettingsRow(
            label = "평소 공복혈당 범위",
            icon = Icons.Filled.Bloodtype,
            valueText = if (low != null && high != null) "${low}~${high}" else "미설정",
            onClick = { showDialog = true }
        )
    }

    if (showDialog) {
        var lowStr by remember { mutableStateOf(low?.toString() ?: "") }
        var highStr by remember { mutableStateOf(high?.toString() ?: "") }
        var saving by remember { mutableStateOf(false) }
        var saveError by remember { mutableStateOf<String?>(null) }
        val gl = lowStr.toIntOrNull()
        val gh = highStr.toIntOrNull()
        val valid = gl != null && gh != null && gl in 40..400 && gh in 40..400 && gl <= gh
        // [디자인 통일] 앱의 '알림 팝업'과 동일한 커스텀 팝업 스타일(둥근 카드 + 원형 아이콘 헤더 + 닫기 + 풀폭 버튼)
        androidx.compose.ui.window.Dialog(onDismissRequest = { if (!saving) showDialog = false }) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = BgCard,
                modifier = Modifier.fillMaxWidth(0.94f).heightIn(max = 560.dp)
            ) {
                // [QA] 시니어 폰트 확대 + 키보드 시 저장 버튼이 가려지지 않게 스크롤 + IME 패딩
                Column(modifier = Modifier.padding(20.dp).imePadding().verticalScroll(rememberScrollState())) {
                    // 헤더: 원형 아이콘 + 제목 + 닫기
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = androidx.compose.foundation.shape.CircleShape,
                            color = LimeLight.copy(alpha = 0.4f),
                            border = BorderStroke(2.dp, Primary.copy(alpha = 0.3f)),
                            modifier = Modifier.size(48.dp)
                        ) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Bloodtype, contentDescription = null, tint = Primary, modifier = Modifier.size(24.dp))
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("평소 공복혈당 범위", fontSize = 18.sp, fontWeight = FontWeight.Black, color = TextPrimary)
                            Text("점수 채점 기준", fontSize = 11.sp, color = Primary, fontWeight = FontWeight.SemiBold)
                        }
                        IconButton(onClick = { if (!saving) showDialog = false }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "닫기", tint = TextSecondary, modifier = Modifier.size(24.dp))
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "평소 아침 공복혈당이 보통 어느 정도인지 범위로 입력하세요. 이 범위를 기준으로 점수를 매겨드려요.",
                        fontSize = 13.sp, color = TextSecondary, lineHeight = 19.sp
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = lowStr,
                            onValueChange = { if (it.length <= 3 && it.all { c -> c.isDigit() }) lowStr = it },
                            placeholder = { Text("130") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        )
                        Text("~", fontSize = 20.sp, color = TextSecondary)
                        OutlinedTextField(
                            value = highStr,
                            onValueChange = { if (it.length <= 3 && it.all { c -> c.isDigit() }) highStr = it },
                            placeholder = { Text("150") },
                            suffix = { Text("mg/dL") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                            modifier = Modifier.weight(1.3f),
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                    if ((lowStr.isNotBlank() || highStr.isNotBlank()) && !valid) {
                        Spacer(Modifier.height(8.dp))
                        Text("⚠️ 40~400 mg/dL, 하한 ≤ 상한으로 입력해주세요", fontSize = 12.sp, color = ErrorRed)
                    }
                    saveError?.let {
                        Spacer(Modifier.height(8.dp))
                        Text("⚠️ $it", fontSize = 12.sp, color = ErrorRed)
                    }
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = { if (!saving) showDialog = false },
                            modifier = Modifier.weight(1f).height(50.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) { Text("취소", fontSize = 15.sp, color = TextSecondary) }
                        Button(
                            onClick = {
                                saving = true
                                saveError = null
                                scope.launch {
                                    val res = com.dangbomcare.app.data.ServiceLocator.userRepository.updateMe(
                                        com.dangbomcare.app.data.api.UserUpdateRequest(
                                            glucoseNormalLow = gl, glucoseNormalHigh = gh
                                        )
                                    )
                                    saving = false
                                    if (res is com.dangbomcare.app.data.repo.ApiResult.Success) {
                                        low = res.data.glucoseNormalLow
                                        high = res.data.glucoseNormalHigh
                                        showDialog = false
                                    } else {
                                        // [Codex P2] 저장 실패 시 무반응 → 에러 표시 + 재시도 가능
                                        saveError = "저장에 실패했어요. 인터넷을 확인하고 다시 시도해주세요."
                                    }
                                }
                            },
                            enabled = valid && !saving,
                            modifier = Modifier.weight(1.4f).height(50.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Primary)
                        ) { Text(if (saving) "저장 중..." else "저장", fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }
}

// [QA] LocalContext가 ContextWrapper(테마 래퍼 등)일 수 있어 as? Activity가 실패할 수 있음 → 언래핑.
//   [SDK 자동화] WatchSyncRow에서 삼성헬스 SDK 권한 다이얼로그를 띄우려면 Activity가 필요.
private fun android.content.Context.findActivityOrNull(): android.app.Activity? {
    var c: android.content.Context? = this
    while (c is android.content.ContextWrapper) {
        if (c is android.app.Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * 워치 재연결 큰 카드 (당봄 #18).
 * 설정 화면 최상단 — 연결 상태에 따라 색상이 바뀌고
 * 한 번의 터치로 Health Connect 권한 재요청 + 강제 동기화.
 *
 * 사용자 피드백 — 버튼 클릭 시 별도 화면으로 이동하지 말고 인라인으로
 *  • 권한 없으면 HC 권한 요청 launcher
 *  • 권한 OK면 오늘/어제 데이터 fetch+POST → "동기화 완료" 메시지만 표시
 */
/**
 * ⌚ 워치 동기화 행 (기기/데이터 섹션 — 다른 행들과 동일 스타일).
 *
 * 사용자 피드백: 큰 카드 대신 '내 데이터 내보내기'·'워치 데이터 점검'과 같은 행으로.
 *  • 탭하면 오늘+어제 워치 데이터를 안전 동기화(WatchSyncActions: all-null 가드 + WatchSyncTracker.mark).
 *  • 권한이 없으면(HC 가용·SDK 비활성) 먼저 권한 요청 → 허용되면 곧바로 동기화 (재요청 동작 유지).
 *  • 진행 상태를 행 안에 간단히 표시: "동기화 중..." → "동기화 완료" / "최근 동기화: N분 전" / 오류 문구.
 */
@Composable
internal fun WatchSyncControl(
    onSynced: () -> Unit = {},
    content: @Composable (busy: Boolean, statusMsg: String, onTap: () -> Unit) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val healthRepo = remember { com.dangbomcare.app.data.ServiceLocator.healthConnectRepository }
    val scope = rememberCoroutineScope()

    // [QA Fix #3] 탭 전체 경로를 막는 단일 가드. onTap 진입 즉시(가용성 체크 전) set →
    //   빠른 더블탭이 코루틴/권한요청을 중복 생성하지 못하게. 권한 콜백 OR 동기화 종료 시 모든 종료 경로에서 해제.
    var busy by remember { mutableStateOf(false) }
    // 행 보조 문구 — 평상시엔 마지막 동기화 시각, 동기화 중/직후엔 진행·결과 메시지.
    var statusMsg by remember { mutableStateOf(com.dangbomcare.app.data.health.WatchSyncTracker.formatLastSync(context)) }

    // 공통 안전 백필 실행 (최근 90일, all-null 가드, mark). IO에서 돌리고 결과를 행에 반영.
    //  • 가벼운 오늘+어제(홈 자동 동기화)와 달리 설정 행은 "지난 기록이 안 보이면 최근 90일 다시 가져오기" 용도 →
    //    수 초 걸리므로 그 동안 '동기화 중...' 스피너를 끝까지 유지하고, 끝나면 'N일 반영' 요약.
    //  [QA Fix #3] busy 가드는 호출자(onTap/launcher)가 이미 set·관리. 여기선 동기화 시작 시 유지(idempotent)하고
    //    finally에서 반드시 해제 → 실패/예외 포함 모든 종료 경로에서 행이 다시 활성화.
    val runSync: () -> Unit = {
        busy = true
        statusMsg = "동기화 중..."
        scope.launch {
            try {
                val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.dangbomcare.app.data.health.WatchSyncActions.syncRange(context, healthRepo, days = 90)
                }
                statusMsg = when {
                    !result.readable ->
                        "⚠️ 워치 기록 권한이 필요해요. 다시 눌러 '모두 허용'을 눌러주세요."
                    result.anyPosted -> {
                        // 백그라운드 주기 동기화도 깨워 최신 상태 유지 (수동 카드와 동일 동작).
                        com.dangbomcare.app.data.health.WatchSyncWorker.enqueueImmediateSync(context)
                        "동기화 완료 · ${result.posted}일 반영 · " +
                            com.dangbomcare.app.data.health.WatchSyncTracker.formatLastSync(context)
                    }
                    result.failed > 0 ->
                        "⚠️ 동기화 실패. 인터넷을 확인하고 다시 시도해주세요."
                    else ->
                        // 보낼 새 데이터가 없음(워치 기록 없음) — 마지막 동기화 시각은 유지해서 안내.
                        "최근 워치에 새 기록이 없어요 · " + com.dangbomcare.app.data.health.WatchSyncTracker.formatLastSync(context)
                }
                // 권한이 OK 상태로 동기화가 돌았으면(=읽기 가능) 소비자에 알림 → 홈 배너가 사라지고 새로고침.
                if (result.readable) onSynced()
            } catch (_: Exception) {
                statusMsg = "⚠️ 동기화 실패. 인터넷을 확인하고 다시 시도해주세요."
            } finally {
                busy = false
            }
        }
    }

    // HC 권한 요청 launcher — 허용되면 곧바로 동기화 (시니어: 추가 탭 없이).
    //   권한이 한 개라도 부여되면 동기화 시도 → 내부 canReadWatchData 게이트가 최종 판정(result.readable로 안내).
    //   [QA Fix #3] 결과 도착 = 권한 단계 종료. 동기화로 이어지면 runSync가 가드를 계속 관리,
    //     아니면(거부) 여기서 가드 해제 → 행 재활성화.
    val permLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = com.dangbomcare.app.data.health.HealthConnectRepository.PERMS_CONTRACT
    ) { granted ->
        if (granted.isNotEmpty()) {
            runSync()  // busy 유지 → 동기화 끝나면 runSync.finally에서 해제
        } else {
            busy = false
            statusMsg = "❌ 권한이 허용되지 않았어요. 다시 눌러주세요."
        }
    }

    // 탭 동작: [SDK 마이그레이션] 삼성헬스 SDK 후보인데 아직 SDK 권한이 없으면(= HC로만 쓰던 기존 사용자/최초 사용자)
    //   동기화 전에 삼성헬스 SDK 권한을 먼저 요청해 SDK로 '승격'. 허용 시 SDK로 동기화.
    //   거부/타임아웃/실패 시 기존 HC 경로로 폴백(HC 권한 있으면 HC로 동기화, 없으면 HC 권한 요청).
    //   이미 SDK 활성이면 분기를 건너뛰고 바로 동기화. 비-삼성폰이면 sdkCandidate=false라 건너뜀.
    //   ※ 핵심: 기존 HC 사용자는 canReadWatchData()=true라 예전엔 SDK 권한이 안 떴음 → SDK 후보+미활성이면
    //     읽기 가능 여부와 무관하게 SDK 권한을 먼저 요청해 마이그레이션을 트리거. SDK 권한 요청은 여기 한 곳만 → 이중 팝업 방지.
    //   [QA Fix #3] busy를 onTap 맨 처음(가용성 체크 전) 동기 set → 더블탭/recomposition 이중 실행 차단.
    //     권한 런처로 넘어갈 땐 busy를 풀지 않음(런처 콜백이 동기화로 이어가거나 거부 시 해제).
    val onTap: () -> Unit = onTap@{
        if (busy) return@onTap
        busy = true
        scope.launch {
            // 현재 워치 데이터 소스 상태 파악
            val sdkCandidate = try {
                com.dangbomcare.app.data.ServiceLocator.samsungHealthSource.isPermissionFlowAvailable()
            } catch (_: Exception) { false }
            val sdkActive = try {
                healthRepo.isSdkDirectActive()
            } catch (ce: kotlinx.coroutines.CancellationException) {
                busy = false; throw ce
            } catch (_: Exception) { false }
            val canRead = try {
                healthRepo.canReadWatchData()
            } catch (ce: kotlinx.coroutines.CancellationException) {
                busy = false; throw ce
            } catch (_: Exception) { false }

            // [SDK 마이그레이션] SDK 후보 + 아직 SDK 권한 없음 → SDK 권한을 먼저 요청(읽기 가능 여부 무관).
            if (sdkCandidate && !sdkActive) {
                val act = context.findActivityOrNull()
                if (act != null) {
                    statusMsg = "워치 권한 요청 중..."
                    // [QA Fix #5] 60초 타임아웃 — null/false/throw 모두 HC로 폴백. CancellationException은 재던짐.
                    val granted = try {
                        kotlinx.coroutines.withTimeoutOrNull(60_000) {
                            com.dangbomcare.app.data.ServiceLocator.samsungHealthSource.requestPermissions(act)
                        }
                    } catch (ce: kotlinx.coroutines.CancellationException) {
                        busy = false
                        throw ce
                    } catch (_: Throwable) {
                        false
                    }
                    if (granted == true) {
                        runSync()  // SDK 권한 OK → SDK로 정상 동기화(busy 유지)
                        return@launch
                    }
                    // SDK 권한 거부/타임아웃/실패 → HC로 폴백
                    if (canRead) {
                        runSync()  // 기존 HC 권한으로라도 동기화
                    } else if (healthRepo.isSdkAvailable()) {
                        try {
                            permLauncher.launch(com.dangbomcare.app.data.health.HealthConnectRepository.PERMISSIONS)
                        } catch (e: Exception) {
                            busy = false
                            statusMsg = "❌ 동기화 권한 요청 실패"
                        }
                    } else {
                        busy = false
                        statusMsg = "❌ 워치 권한이 허용되지 않았어요. 다시 눌러주세요."
                    }
                    return@launch
                }
                // act == null → 아래 일반 경로로 폴백
            }

            // 일반 경로: 이미 SDK 활성이거나 비-후보 — 읽기 가능하면 동기화, 아니면 HC 권한 요청.
            if (canRead) {
                runSync()  // busy 유지(runSync가 관리)
                return@launch
            }
            if (healthRepo.isSdkAvailable()) {
                // busy 유지(런처 콜백에서 해제). launch가 throw하면 콜백이 안 와 busy가 안 풀리므로 여기서 해제.
                try {
                    permLauncher.launch(com.dangbomcare.app.data.health.HealthConnectRepository.PERMISSIONS)
                } catch (e: Exception) {
                    busy = false
                    statusMsg = "❌ 동기화 권한 요청 실패"
                }
            } else {
                busy = false
                statusMsg = "⚠️ Health Connect가 설치되어 있지 않아요."
            }
        }
    }

    content(busy, statusMsg, onTap)
}

/**
 * 설정 화면 '삼성헬스 동기화' 행 — WatchSyncControl(권한+동기화 로직) + 행 UI.
 */
@Composable
private fun WatchSyncRow() {
    WatchSyncControl { busy, statusMsg, onTap ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !busy) { onTap() }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Watch, contentDescription = null, tint = Primary)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("삼성헬스 동기화", fontSize = 15.sp, color = TextPrimary)
                Spacer(Modifier.height(2.dp))
                Text(
                    statusMsg,
                    fontSize = 12.sp,
                    color = if (statusMsg.startsWith("⚠️") || statusMsg.startsWith("❌")) ErrorRed else TextSecondary,
                    lineHeight = 16.sp
                )
            }
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Primary)
            } else {
                Icon(Icons.Filled.Refresh, contentDescription = null, tint = TextSecondary)
            }
        }
    }
}

/**
 * 내 데이터 내보내기 화면 진입 카드.
 * 의료진과 공유하거나 개인 백업으로 받을 수 있는 CSV 다운로드 화면으로 이동.
 */
@Composable
private fun ExportDataEntryCard(onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        border = BorderStroke(1.dp, Divider)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("📥", fontSize = 24.sp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "내 데이터 내보내기",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    "의료진과 공유할 수 있는 CSV 파일로 받기",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }
            Text("›", fontSize = 24.sp, color = TextSecondary)
        }
    }
}

/**
 * Health Connect 데이터 진단 화면 진입 카드 — 디버그용.
 * 어떤 데이터가 들어오는지 / 어떤 게 누락됐는지 한눈에 확인.
 */
@Composable
private fun DataDiagnosticEntryCard(onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        border = BorderStroke(1.dp, Divider)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("📊", fontSize = 24.sp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Health Connect 데이터 진단",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    "워치에서 어떤 데이터가 들어오는지 확인",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }
            Text("›", fontSize = 24.sp, color = TextSecondary)
        }
    }
}
