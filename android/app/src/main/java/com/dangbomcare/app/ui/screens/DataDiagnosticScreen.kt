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
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dangbomcare.app.data.ServiceLocator
import com.dangbomcare.app.data.health.HealthConnectRepository
import com.dangbomcare.app.ui.theme.*
import java.time.LocalDate

/**
 * [Codex] 워치 데이터 출처 모드 — 이 화면 전체가 이 값을 기준으로 분기한다.
 *   SamsungActive          : 삼성헬스 SDK 직접읽기가 실제 활성 (repo.isSdkDirectActive()=true).
 *                            Health Connect 권한이 없어도 SDK로 데이터를 읽는 중 → HC 권한/진단 UI 숨김.
 *   SamsungNeedsPermission : 이 기기가 SDK 후보(isPermissionFlowAvailable)인데 아직 SDK 권한 미부여.
 *                            → 삼성헬스 SDK 권한 요청 버튼을 노출 (HC가 아니라 SDK 권한을 받아야 함).
 *   HealthConnect          : 비-삼성/SDK 불가 기기 — 기존 Health Connect 경로 그대로 (무변화).
 */
private enum class WatchSourceMode { SamsungActive, SamsungNeedsPermission, HealthConnect }

/**
 * Compose LocalContext는 보통 ContextWrapper(예: ContextThemeWrapper) — Activity로 바로 캐스팅 실패 가능.
 *   base context를 따라 풀어가며 Activity를 찾는다. SDK 권한 요청(requestPermissions)에 Activity 필요.
 */
private fun android.content.Context.findActivity(): android.app.Activity? {
    var ctx: android.content.Context? = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is android.app.Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/**
 * Health Connect 데이터 진단 화면 — 디버그용.
 * 어떤 데이터가 들어오는지 / 어떤 소스에서 / 누락된 권한이 있는지 한눈에 확인.
 */
@Composable
fun DataDiagnosticScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var selectedDate by remember { mutableStateOf(LocalDate.now().minusDays(1)) }
    var diagnostics by remember { mutableStateOf<List<HealthConnectRepository.RawDataDiagnostic>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var permStatus by remember { mutableStateOf<HealthConnectRepository.PermStatus?>(null) }
    var refreshTrigger by remember { mutableStateOf(0) }

    // 삼성 헬스 SDK 직접 읽기 상태 — [v1.0] isReady=true일 때만 카드 노출 (일반 환자는 안 보임).
    //   v1.0 출시 시 Samsung Partner 인증 전이라 일반 환자는 SDK 차단 = isReady=false → 카드 숨김.
    //   본인 폰처럼 개발자 모드 ON이면 isReady=true → 카드 표시 + 디버그 가능.
    var sdkReady by remember { mutableStateOf(false) }
    var sdkStatus by remember { mutableStateOf("확인 중...") }
    var sdkPermCount by remember { mutableStateOf(0) }
    var sdkBusy by remember { mutableStateOf(false) }
    var sdkResult by remember { mutableStateOf<String?>(null) }
    // [Codex] 이 화면의 출처 모드 — 새로고침 effect에서 갱신. null = 아직 판정 전(로딩).
    var sourceMode by remember { mutableStateOf<WatchSourceMode?>(null) }
    // [대표님 전용 테스트] 이 기기의 'SDK 직접 읽기' opt-in 플래그 (수동 강제 ON — 자동 감지가 기본).
    var sdkDirectEnabled by remember { mutableStateOf(HealthConnectRepository.isSdkDirectReadEnabled(context)) }
    // [대표님 전용] SDK 테스트 카드 숨김 — 제목을 7번 탭하면 잠금 해제(일반 환자 비노출). 이미 켜져 있으면 항상 표시.
    var sdkTapCount by remember { mutableStateOf(0) }
    var sdkUnlocked by remember { mutableStateOf(false) }
    LaunchedEffect(refreshTrigger) {
        try {
            // [Codex] 출처 모드 판정 — repo의 실제 정책과 동일하게.
            //   candidate(=isPermissionFlowAvailable): 이 기기가 삼성 SDK 권한 흐름을 띄울 수 있는 후보.
            //   active(=isSdkDirectActive): candidate + SDK 준비완료(isReady) → 실제 SDK로 읽는 중.
            //   둘 다 아니면 HealthConnect (비-삼성/SDK 불가 기기 — 기존 경로 무변화).
            val src = ServiceLocator.samsungHealthSource
            // [v1.0] force=true — 캐시 무시 / 매번 SDK store 직접 조회 → 권한 회수 즉시 반영.
            sdkReady = src.isReady(force = true)
            sdkPermCount = src.grantedPermissionsCount()
            val sdkCandidate = src.isPermissionFlowAvailable()
            val sdkActive = HealthConnectRepository(context).isSdkDirectActive()
            sourceMode = when {
                sdkActive -> WatchSourceMode.SamsungActive
                sdkCandidate -> WatchSourceMode.SamsungNeedsPermission
                else -> WatchSourceMode.HealthConnect
            }
            sdkStatus = when {
                sdkReady -> "✅ 준비됨 — SDK 직접 읽기 활성 (권한 $sdkPermCount/4 부여)"
                sdkPermCount > 0 -> "⚠️ 권한 일부 ($sdkPermCount/4) — SDK 활성 안 됨"
                else -> "❌ 미준비 — 권한 없음 또는 삼성헬스 개발자모드 OFF"
            }
        } catch (e: Exception) {
            sdkReady = false
            sourceMode = WatchSourceMode.HealthConnect  // 판정 실패 시 안전하게 HC 경로
            sdkStatus = "오류: ${e.message}"
        }
    }

    // [v1.0 피드백] 우리 앱이 실제 사용하는 fetchWatchData 결과 — 백엔드로 전송되는 값 그대로.
    //   fetchAllRawData(HC raw 진단)와 별개로, 일반 환자가 보게 될 "앱이 받은 값"을 명시.
    //   useSamsung()=false면 HC 경로, true면 SDK 경로. 어느 경로 썼는지도 표시.
    var actualWatchData by remember { mutableStateOf<com.dangbomcare.app.data.api.WatchDataRequest?>(null) }
    var stepsExpanded by remember { mutableStateOf(false) }  // [워치데이터 점검] 걸음수 시간대별 펼침
    LaunchedEffect(selectedDate, refreshTrigger) {
        stepsExpanded = false  // [Codex P2] 날짜 바뀌면 시간대별 펼침 초기화 (다른 날인데 펼쳐진 채 표시 방지)
        try {
            actualWatchData = HealthConnectRepository(context).fetchWatchData(selectedDate)
        } catch (_: Exception) {
            actualWatchData = null
        }
    }

    // 권한 재요청 launcher (당봄케어가 추가한 새 권한 8종 한 번에 다이얼로그 표시)
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = HealthConnectRepository.PERMS_CONTRACT
    ) { granted ->
        // 권한 결과 받은 후 자동 새로고침
        refreshTrigger++
    }

    // [Codex] HC 전용 읽기(권한 체크 + raw 진단)는 HealthConnect 모드에서만 수행.
    //   삼성 모드(SamsungActive/NeedsPermission)에선 HC 권한이 꺼져 있어도 정상이므로
    //   이 호출들을 건너뛰어 "권한 없음/데이터 없음" 오해를 차단한다. sourceMode가 판정되면 재실행.
    LaunchedEffect(selectedDate, refreshTrigger, sourceMode) {
        if (sourceMode == WatchSourceMode.HealthConnect) {
            loading = true
            try {
                val repo = HealthConnectRepository(context)
                permStatus = repo.checkPermissions()
                diagnostics = repo.fetchAllRawData(selectedDate)
            } catch (e: Exception) { /* */ }
            loading = false
        } else {
            // 삼성 모드 — HC 진단 상태 초기화 (이전 HC 데이터가 남아 오해 주지 않게)
            loading = false
            permStatus = null
            diagnostics = emptyList()
        }
    }

    Scaffold(
        containerColor = BgSecondary,
        topBar = {
            TopAppBar(
                title = {
                    // [대표님 전용 히든] 제목 7번 탭 → SDK 직접 읽기 테스트 카드 잠금 해제 (일반 환자엔 안 보임).
                    Text(
                        "📊 워치 데이터 점검",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null
                        ) {
                            if (!sdkUnlocked) {
                                sdkTapCount++
                                if (sdkTapCount >= 7) {
                                    sdkUnlocked = true
                                    android.widget.Toast.makeText(
                                        context,
                                        "🩺 SDK 직접 읽기 테스트 옵션이 표시됩니다",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgPrimary)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // [Codex] 시니어 친화 — 화면 진입 시 한 줄 요약 + 자가 진단 가이드.
            //   출처 모드(SamsungActive/NeedsPermission/HealthConnect) 기준으로 상태/권한 UI를 분기한다.
            //   삼성 모드에선 HC 권한 누락/빈 진단을 아예 보여주지 않아 "권한 없음" 오해를 차단.
            val mode = sourceMode
            val totalRecords = diagnostics.sumOf { it.recordCount }
            val grantedAll = permStatus?.all == true
            // 데이터 타입별 유무 — 걸음(StepsRecord)이 있는데 수면/심박이 없으면 워치 미착용 케이스
            val hasSteps = diagnostics.any { it.type.contains("StepsRecord") && it.recordCount > 0 }
            val hasWatchOnly = diagnostics.any {
                (it.type.contains("SleepSessionRecord") || it.type.contains("HeartRate")) && it.recordCount > 0
            }
            // SDK가 실제로 읽어온 값(앱이 점수에 쓰는 값)에 데이터가 있는지
            val sdkHasData = actualWatchData?.let {
                it.steps != null || it.sleepTotalMinutes != null || it.avgHeartRate != null
            } == true
            val (statusEmoji, statusMsg, statusColor) = when {
                mode == null -> Triple("⏳", "확인 중...", TextSecondary)
                // ── 삼성 SDK 활성: HC 권한과 무관하게 SDK 기준으로 안내 ──
                mode == WatchSourceMode.SamsungActive && sdkHasData -> Triple(
                    "✅",
                    "삼성 헬스 SDK로 데이터를 직접 읽고 있어요 (Health Connect 우회). 위 '우리 앱이 받은 값'이 실제 사용되는 값이에요.",
                    Success
                )
                mode == WatchSourceMode.SamsungActive -> Triple(
                    "ℹ️",
                    "삼성 SDK로 읽는 중인데 이 날짜엔 데이터가 없어요. 날짜를 넘겨보거나 워치 착용을 확인해 주세요.",
                    Color(0xFF1976D2)
                )
                // ── 삼성 SDK 후보지만 권한 필요 ──
                mode == WatchSourceMode.SamsungNeedsPermission -> Triple(
                    "⌚",
                    "삼성헬스 SDK 직접읽기를 쓸 수 있어요. 아래 '삼성헬스 권한 허용' 버튼을 눌러 권한을 켜주세요. (Health Connect 권한은 필요 없어요)",
                    Warning
                )
                // ── Health Connect 경로 ──
                permStatus == null || loading -> Triple("⏳", "확인 중...", TextSecondary)
                !grantedAll -> Triple("⚠️", "권한이 일부 누락됐어요 — 아래 '권한 허용' 버튼을 눌러주세요", Warning)
                totalRecords == 0 -> Triple("❌", "데이터가 들어오지 않고 있어요 — 워치 착용 또는 삼성헬스 동기화를 확인해주세요", ErrorRed)
                hasSteps && !hasWatchOnly -> Triple(
                    "ℹ️",
                    "걸음수는 들어왔어요. 수면·심박이 비어있는 건 어제 워치를 안 차고 주무셨거나 동기화가 늦어진 경우일 수 있어요.",
                    Color(0xFF1976D2)  // 정보 톤 (파랑)
                )
                else -> Triple("✅", "워치 데이터가 정상적으로 들어오고 있어요", Success)
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = statusColor.copy(alpha = 0.12f)),
                border = BorderStroke(1.dp, statusColor.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(statusEmoji, fontSize = 22.sp)
                        Spacer(Modifier.width(8.dp))
                        Text("현재 상태", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Spacer(Modifier.weight(1f))
                        // 데이터 경로 배지 — 어느 소스로 읽는지 한눈에 (삼성=초록 / HC=회색 / 판정 전=중립).
                        //   [Codex Fix3] mode==null(초기 감지) 동안엔 HC 배지로 새지 않도록 '확인 중' 중립 배지.
                        val samsungMode = mode == WatchSourceMode.SamsungActive ||
                            mode == WatchSourceMode.SamsungNeedsPermission
                        val (badgeText, pathColor) = when {
                            mode == null -> "확인 중" to TextDisabled
                            samsungMode -> "🟢 삼성 SDK" to Success
                            else -> "Health Connect" to TextSecondary
                        }
                        Surface(
                            color = pathColor.copy(alpha = 0.18f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                badgeText,
                                fontSize = 11.sp,
                                color = pathColor,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                    Text(statusMsg, fontSize = 14.sp, color = TextPrimary, lineHeight = 20.sp)
                    // SamsungActive — SDK ready + 부여 권한 개수(읽기 전용 카운트) 보조 표시
                    if (mode == WatchSourceMode.SamsungActive) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "✅ 삼성헬스 SDK 직접읽기 활성 · Health Connect 권한 없어도 됨" +
                                "  (SDK 준비됨 · 권한 $sdkPermCount/4)",
                            fontSize = 12.sp, color = TextSecondary, lineHeight = 17.sp
                        )
                    }
                    if (mode == WatchSourceMode.HealthConnect && permStatus != null && grantedAll && totalRecords == 0) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "📌 점검해 보세요:\n" +
                            "1) 워치를 어제부터 차고 계셨나요?\n" +
                            "2) 삼성헬스 앱을 한 번 켜서 어제 데이터가 보이는지 확인\n" +
                            "3) 삼성헬스 → 설정 → Health Connect 동기화 토글 ON",
                            fontSize = 13.sp,
                            color = TextSecondary,
                            lineHeight = 19.sp
                        )
                    }
                }
            }

            // [Codex] SamsungNeedsPermission — 삼성헬스 SDK 권한 요청 버튼 (HC 권한 아님!).
            //   permissionLauncher(HC contract)가 아니라 SamsungHealthSource.requestPermissions(activity) 호출.
            if (mode == WatchSourceMode.SamsungNeedsPermission) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    sdkResult?.let { Text(it, fontSize = 12.sp, color = TextPrimary) }
                    Button(
                        onClick = {
                            // [Codex Fix4] Activity를 못 찾으면 조용히 끝나지 않게 안내 후 중단.
                            val act = context.findActivity() ?: run {
                                sdkResult = "권한 화면을 열 수 없어요 (앱을 다시 실행해 주세요)"
                                return@Button
                            }
                            sdkBusy = true
                            scope.launch {
                                try {
                                    val src = ServiceLocator.samsungHealthSource
                                    val granted = src.requestPermissions(act)
                                    sdkResult = if (granted) "✅ 삼성헬스 권한 허용됨 — 데이터를 불러옵니다"
                                    else "권한이 모두 허용되지 않았어요. 다시 시도해 주세요."
                                } catch (e: Exception) {
                                    sdkResult = "오류: ${e.message}"
                                }
                                sdkBusy = false
                                refreshTrigger++  // 모드 재판정 + 상태/데이터 갱신
                            }
                        },
                        enabled = !sdkBusy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = Color.White),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            if (sdkBusy) "처리 중..." else "⌚ 삼성헬스 권한 허용",
                            fontSize = 14.sp, fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        "삼성헬스 동의 화면이 뜨면 걸음·수면·심박·산소포화도를 모두 허용해 주세요.",
                        fontSize = 11.sp, color = TextSecondary, lineHeight = 16.sp
                    )
                }
            }

            // [v1.0 피드백] 우리 앱이 실제로 받은 값 — 백엔드 점수 계산에 그대로 사용됨.
            //   환자가 "내 데이터가 어떻게 들어갔지?"를 즉시 파악. fetchAllRawData(아래)는 raw 진단,
            //   이 박스는 우리 앱이 가공해서 백엔드로 보내는 최종 값.
            actualWatchData?.let { wd ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = BgCard),
                    border = BorderStroke(1.dp, Divider)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // [Codex] 헤더 — 제목 + 데이터 출처 라벨 (모드 기반 단순 표기).
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "📦 우리 앱이 받은 값 (점수 계산에 사용)",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            // [Codex Fix1] 출처 라벨 — 모드 기반. SamsungNeedsPermission일 때 데이터는
                            //   실제로 Health Connect 폴백(SDK 권한 아직 없음)이므로 'SDK'로 표기하지 않는다.
                            val sourceLabel = when (mode) {
                                WatchSourceMode.SamsungActive -> "출처: 삼성헬스 SDK"
                                WatchSourceMode.SamsungNeedsPermission -> "출처: Health Connect (SDK 권한 허용 전 임시)"
                                WatchSourceMode.HealthConnect -> "출처: Health Connect"
                                null -> "출처: 확인 중"
                            }
                            Text(
                                sourceLabel,
                                fontSize = 11.sp,
                                color = TextSecondary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        // [Codex Fix1] SamsungNeedsPermission — HC 임시 데이터임을 한 줄로 명확히.
                        if (mode == WatchSourceMode.SamsungNeedsPermission) {
                            Text(
                                "지금 값은 Health Connect에서 임시로 받은 거예요. SDK 권한을 허용하면 삼성헬스에서 직접 받아요.",
                                fontSize = 11.sp, color = TextSecondary, lineHeight = 16.sp
                            )
                        }
                        // [워치데이터 점검] 걸음수 — 시간대별 데이터 있으면 탭하여 펼침 (단순 숫자 X)
                        val hourly = wd.stepsHourly
                        val hasHourly = hourly != null && hourly.any { it > 0 }
                        val stepsValue = wd.steps?.let { "${"%,d".format(it)}보" } ?: "측정 안 됨"
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .then(if (hasHourly) Modifier.clickable { stepsExpanded = !stepsExpanded } else Modifier),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("🚶 걸음수", fontSize = 13.sp, color = TextSecondary)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    stepsValue,
                                    fontSize = 13.sp,
                                    color = if (stepsValue == "측정 안 됨") TextSecondary else TextPrimary,
                                    fontWeight = if (stepsValue == "측정 안 됨") FontWeight.Normal else FontWeight.SemiBold
                                )
                                if (hasHourly) {
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (stepsExpanded) "시간대별 ▴" else "시간대별 ▾", fontSize = 11.sp, color = Primary, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                        // 시간대별 걸음수 막대 (펼침)
                        if (stepsExpanded && hourly != null) {
                            val maxHour = (hourly.maxOrNull() ?: 1).coerceAtLeast(1)
                            Box(modifier = Modifier.fillMaxWidth().height(90.dp)) {
                                Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(1.dp), verticalAlignment = Alignment.Bottom) {
                                    hourly.forEach { count ->
                                        val ratio = count.toFloat() / maxHour
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxHeight(ratio.coerceAtLeast(0.01f))
                                                .background(if (count > 0) Primary else BgCardSoft, RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                                        )
                                    }
                                }
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("0시", fontSize = 9.sp, color = TextSecondary)
                                Text("6시", fontSize = 9.sp, color = TextSecondary)
                                Text("12시", fontSize = 9.sp, color = TextSecondary)
                                Text("18시", fontSize = 9.sp, color = TextSecondary)
                                Text("23시", fontSize = 9.sp, color = TextSecondary)
                            }
                        }
                        // 나머지 — 수면/심박/산소 (null은 "측정 안 됨")
                        listOf(
                            "😴 수면" to (wd.sleepTotalMinutes?.let { "${it / 60}시간 ${it % 60}분" } ?: "측정 안 됨"),
                            "❤️ 심박 (평균)" to (wd.avgHeartRate?.let { "$it BPM" } ?: "측정 안 됨"),
                            "🫁 산소포화도 (평균)" to (wd.spo2Avg?.let { "%.1f%%".format(it) } ?: "측정 안 됨"),
                        ).forEach { (label, value) ->
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(label, fontSize = 13.sp, color = TextSecondary)
                                Text(
                                    value,
                                    fontSize = 13.sp,
                                    color = if (value == "측정 안 됨") TextSecondary else TextPrimary,
                                    fontWeight = if (value == "측정 안 됨") FontWeight.Normal else FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }

            // 날짜 선택
            DateSelectorBar(
                date = selectedDate,
                onChange = { selectedDate = it }
            )

            // 🩺 삼성 헬스 SDK 직접 읽기 (Health Connect 우회) — [대표님 전용 테스트]
            //   이 기기에서만 ON. 기본 OFF → 일반 환자는 항상 기존 HC 경로(무변화).
            //   토글 ON + SDK 권한 부여(아래 버튼) + 삼성헬스 개발자 모드(패키지 등록) → 이 기기에서 삼성헬스 직접 읽기.
            //   SDK 미준비면 자동으로 HC로 되돌아가므로 데이터 공백/중복 없음.
            //   [대표님 전용] 제목 7번 탭으로 잠금 해제(sdkUnlocked)됐거나 이미 켜진(sdkDirectEnabled) 경우에만 노출 → 일반 환자 비노출.
            //   토글 기본 OFF + isReady 가드 + 히든 게이트 3중 안전장치로 환자엔 영향 0.
            if (sdkUnlocked || sdkDirectEnabled) Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2E7D32).copy(alpha = 0.10f)),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "🩺 삼성 헬스 SDK 직접 읽기 — 수동 강제 ON (대표님 전용 테스트)",
                        fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary
                    )
                    Text(
                        "이 토글은 '수동 강제 ON' 스위치예요 — 자동 감지가 기본입니다. " +
                            "삼성헬스가 설치된 기기는 토글이 꺼져 있어도 권한만 부여되면 자동으로 SDK 직접읽기를 씁니다. " +
                            "이 토글은 자동 감지와 무관하게 SDK 경로를 강제로 켭니다(끈다고 비-삼성 기기에서 SDK가 켜지진 않음).",
                        fontSize = 12.sp, color = TextSecondary, lineHeight = 17.sp
                    )
                    // 수동 강제 ON 토글 (이 기기 한정, SharedPreferences) — 자동 감지 외 레거시 오버라이드
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "SDK 직접 읽기 수동 강제 ON",
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            color = TextPrimary, modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = sdkDirectEnabled,
                            onCheckedChange = { on ->
                                sdkDirectEnabled = on
                                HealthConnectRepository.setSdkDirectReadEnabled(context, on)
                                refreshTrigger++  // 경로 표시 + '앱이 받은 값' 즉시 갱신
                            }
                        )
                    }
                    Text("상태: $sdkStatus", fontSize = 12.sp, color = TextSecondary)
                    // 토글 ON인데 아직 SDK 미준비 → 안내 (그동안은 HC로 안전 동작)
                    if (sdkDirectEnabled && !sdkReady) {
                        Text(
                            "⚠️ SDK가 아직 준비되지 않았어요. 아래 'SDK 권한 요청'을 누르고, 삼성헬스 개발자 모드에 앱이 등록돼 있는지 확인하세요. 준비 전까지는 기존 방식(Health Connect)으로 동작합니다.",
                            fontSize = 12.sp, color = Warning, lineHeight = 17.sp
                        )
                    }
                    sdkResult?.let { Text(it, fontSize = 12.sp, color = TextPrimary) }
                    Button(
                        onClick = {
                            // [Codex Fix4] Activity를 못 찾으면 조용히 끝나지 않게 안내 후 중단.
                            val act = context.findActivity() ?: run {
                                sdkResult = "권한 화면을 열 수 없어요 (앱을 다시 실행해 주세요)"
                                return@Button
                            }
                            sdkBusy = true
                            scope.launch {
                                try {
                                    val src = ServiceLocator.samsungHealthSource
                                    val granted = src.requestPermissions(act)
                                    sdkResult = if (granted) "권한 OK → ${src.debugSummary()}"
                                    else "권한 거부 또는 미부여"
                                } catch (e: Exception) {
                                    sdkResult = "오류: ${e.message}"
                                }
                                sdkBusy = false
                                refreshTrigger++
                            }
                        },
                        enabled = !sdkBusy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (sdkBusy) "처리 중..." else "SDK 권한 요청 + 데이터 테스트")
                    }
                }
            }

            // 권한 요약 — Health Connect 권한. [Codex Fix2] HealthConnect 모드에서만 표시.
            //   !sdkActive로 게이트하면 SamsungNeedsPermission(sdkActive=false)에서 모드 전환 중
            //   stale permStatus로 HC 권한 카드가 잠깐 뜰 수 있어, sourceMode 기준으로 고정.
            if (mode == WatchSourceMode.HealthConnect) permStatus?.let { ps ->
                val permCardColor = when {
                    ps.all -> Success
                    else -> Warning
                }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = permCardColor.copy(alpha = 0.13f)),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            if (ps.all) "✅ 모든 권한 허용됨" else "⚠️ 일부 권한 누락",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            "허용 ${ps.grantedCount}/${ps.totalCount} 개",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                }

                // 권한 누락 시 권한 화면 열기 버튼들
                if (!ps.all) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // 1차 — 권한 다이얼로그 시도
                        Button(
                            onClick = {
                                permissionLauncher.launch(HealthConnectRepository.PERMISSIONS)
                            },
                            // 고정 48dp → 최소 48dp. 긴 라벨이 시니어 1.4× 글씨에서 2줄이 돼도 안 잘림.
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Primary,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                "🔓 권한 다이얼로그 요청 (${ps.totalCount - ps.grantedCount}개 누락)",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // 2차 — Health Connect 앱 직접 열기 (다이얼로그 안 뜨는 경우 fallback)
                        OutlinedButton(
                            onClick = {
                                openHealthConnectPermissions(context)
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.5.dp, Primary)
                        ) {
                            Text(
                                "🏥 Health Connect 앱에서 직접 허용",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Primary
                            )
                        }
                    }
                    Text(
                        "1️⃣ 권한 다이얼로그가 안 뜨면 2️⃣ 번을 눌러 Health Connect 앱에서 직접 허용하세요.\n" +
                        "(혈당, 체중, 거리, 운동 세션 등 모두 ON)",
                        fontSize = 11.sp,
                        color = TextSecondary,
                        lineHeight = 16.sp
                    )
                }
            }

            // [Codex] ── 구분선 ── SamsungActive: 'SDK로 읽은 데이터' / NeedsPermission: HC 임시 데이터 / HC: 'Health Connect 진단'
            //   samsungSection: 삼성 후보(둘 다)는 HC raw 진단 카드 묶음을 노출하지 않고 단순 카드로 보여준다.
            //   [Codex Fix1] 단, SamsungNeedsPermission의 데이터는 실제 HC 폴백이므로 헤딩을 'SDK'로 달지 않는다.
            val samsungSection = mode == WatchSourceMode.SamsungActive ||
                mode == WatchSourceMode.SamsungNeedsPermission
            if (mode != null) {
                val (sectionLabel, sectionColor) = when (mode) {
                    WatchSourceMode.SamsungActive ->
                        "── 🟢 삼성 SDK로 읽은 데이터 (${selectedDate.monthValue}월 ${selectedDate.dayOfMonth}일) ──" to Success
                    WatchSourceMode.SamsungNeedsPermission ->
                        "── Health Connect 임시 데이터 (SDK 권한 허용 전 · ${selectedDate.monthValue}월 ${selectedDate.dayOfMonth}일) ──" to TextSecondary
                    else ->
                        "── Health Connect 직접 진단 ──" to TextSecondary
                }
                Surface(
                    color = sectionColor.copy(alpha = 0.10f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        sectionLabel,
                        fontSize = 12.sp,
                        color = sectionColor,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 17.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(10.dp)
                    )
                }
            }

            // 데이터 요약
            if (loading || mode == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Primary)
                Text("데이터 조회 중...", fontSize = 12.sp, color = TextSecondary)
            } else if (samsungSection) {
                // 삼성 모드 — 선택 날짜 데이터를 SDK 기준 카드로 표시 (HC 종합·CSV·raw 진단 카드는 숨김).
                //   NeedsPermission이면 actualWatchData가 비어 '측정 없음'으로 보이는 게 정상(권한 부여 후 채워짐).
                SdkDataDetailCards(actualWatchData)
                RefreshButton(onRefresh = { refreshTrigger++ })
            } else {
                val total = diagnostics.size
                val received = diagnostics.count { it.recordCount > 0 }
                val noPermission = diagnostics.count { !it.permissionGranted }
                val errored = diagnostics.count { it.error != null && it.permissionGranted }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = BgCard),
                    border = BorderStroke(1.dp, Divider)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "${selectedDate.monthValue}월 ${selectedDate.dayOfMonth}일 데이터 종합",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("📊 전체 데이터 타입: $total 개", fontSize = 13.sp, color = TextSecondary)
                        Text("✅ 데이터 수신: $received 개", fontSize = 13.sp, color = Success)
                        if (noPermission > 0) {
                            Text("⛔ 권한 없음: $noPermission 개", fontSize = 13.sp, color = ErrorRed)
                        }
                        if (errored > 0) {
                            Text("⚠️ 오류: $errored 개", fontSize = 13.sp, color = Warning)
                        }
                    }
                }

                // 데이터 새로고침 버튼
                RefreshButton(onRefresh = { refreshTrigger++ })

                // CSV Export 버튼 (Health Connect raw 데이터 추출)
                CsvExportCard(selectedDate = selectedDate)

                // 데이터 타입별 카드
                diagnostics.forEach { diag ->
                    DiagnosticCard(diag)
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

/**
 * [대표님 SDK] SDK 직접 읽기 활성 시 — 선택 날짜 데이터를 삼성 SDK 기준으로 타입별 표시 (HC raw 진단 대체).
 *   값은 actualWatchData(= fetchWatchData → SDK 경로)에서 가져옴. '측정 없음'은 그 날 해당 데이터가 없는 정상 케이스.
 */
@Composable
private fun SdkDataDetailCards(wd: com.dangbomcare.app.data.api.WatchDataRequest?) {
    if (wd == null) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = BgCard),
            border = BorderStroke(1.dp, Divider)
        ) {
            Text(
                "이 날짜엔 삼성 SDK로 읽은 데이터가 없어요. 날짜를 넘겨보세요.",
                fontSize = 13.sp, color = TextSecondary,
                modifier = Modifier.padding(16.dp)
            )
        }
        return
    }
    val sleepText = wd.sleepTotalMinutes?.let { t ->
        val base = "${t / 60}시간 ${t % 60}분"
        val stages = buildList {
            wd.sleepDeepMinutes?.let { add("깊은 잠 ${it}분") }
            wd.sleepLightMinutes?.let { add("얕은 잠 ${it}분") }
            wd.sleepRemMinutes?.let { add("렘 ${it}분") }
            wd.sleepAwakeMinutes?.let { add("깸 ${it}분") }
        }
        if (stages.isEmpty()) base else "$base\n(${stages.joinToString(" · ")})"
    }
    val hrText = wd.avgHeartRate?.let { avg ->
        val range = if (wd.minHeartRate != null && wd.maxHeartRate != null)
            " (최소 ${wd.minHeartRate} · 최대 ${wd.maxHeartRate})" else ""
        "평균 ${avg} bpm$range"
    }
    val spo2Text = wd.spo2Avg?.let { a ->
        "평균 %.0f%%".format(a) + (wd.spo2Min?.let { " (최소 %.0f%%)".format(it) } ?: "")
    }
    data class Metric(val icon: String, val label: String, val value: String, val has: Boolean)
    val metrics = listOf(
        Metric("🚶", "걸음수", wd.steps?.let { "${"%,d".format(it)}보" } ?: "측정 없음", wd.steps != null),
        Metric("😴", "수면", sleepText ?: "측정 없음", sleepText != null),
        Metric("❤️", "심박", hrText ?: "측정 없음", hrText != null),
        Metric("🫁", "산소포화도", spo2Text ?: "측정 없음", spo2Text != null),
    )
    metrics.forEach { m ->
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (m.has) Success.copy(alpha = 0.10f) else BgCard
            ),
            border = BorderStroke(1.dp, if (m.has) Success.copy(alpha = 0.4f) else Divider)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(m.icon, fontSize = 18.sp)
                Spacer(Modifier.width(10.dp))
                Text(
                    m.label,
                    fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary,
                    // 고정 92dp → 최소 92dp. "산소포화도" 같은 긴 라벨이 시니어 1.4× 글씨에서 잘리지 않게.
                    modifier = Modifier.widthIn(min = 92.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    m.value,
                    fontSize = 13.sp,
                    color = if (m.has) TextPrimary else TextSecondary,
                    fontWeight = if (m.has) FontWeight.SemiBold else FontWeight.Normal,
                    lineHeight = 18.sp,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun DateSelectorBar(date: LocalDate, onChange: (LocalDate) -> Unit) {
    val today = LocalDate.now()
    val canNext = date.isBefore(today)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        border = BorderStroke(1.dp, Divider)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { onChange(date.minusDays(1)) }) {
                Icon(Icons.Default.ChevronLeft, "이전", tint = Primary)
            }
            Text(
                "${date.year}년 ${date.monthValue}월 ${date.dayOfMonth}일${if (date == today) " (오늘)" else if (date == today.minusDays(1)) " (어제)" else ""}",
                modifier = Modifier.weight(1f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            IconButton(
                onClick = { if (canNext) onChange(date.plusDays(1)) },
                enabled = canNext
            ) {
                Icon(
                    Icons.Default.ChevronRight,
                    "다음",
                    tint = if (canNext) Primary else TextDisabled
                )
            }
        }
    }
}

/**
 * Health Connect 권한 관리 화면을 직접 열기.
 * PermissionController launcher가 다이얼로그를 못 띄울 때 fallback.
 */
private fun openHealthConnectPermissions(context: android.content.Context) {
    // 1차 — 우리 앱의 권한 관리 화면 직접 열기 (Health Connect 1.1+)
    try {
        val intent = android.content.Intent("androidx.health.ACTION_MANAGE_HEALTH_PERMISSIONS")
            .putExtra(android.content.Intent.EXTRA_PACKAGE_NAME, context.packageName)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return
    } catch (_: Exception) { /* try next */ }
    // 2차 — Health Connect 메인 설정 화면
    try {
        val intent = android.content.Intent("androidx.health.ACTION_HEALTH_CONNECT_SETTINGS")
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return
    } catch (_: Exception) { /* try next */ }
    // 3차 — Health Connect 앱 자체 (Play Store 패키지)
    try {
        val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(android.net.Uri.parse("package:com.google.android.apps.healthdata"))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (_: Exception) {
        // Health Connect 미설치 — 무시
    }
}

/**
 * CSV 추출 카드 — Health Connect raw 데이터를 CSV로 받아 공유.
 * 사용자가 직접 확인 / 의료진과 공유 / 자체 분석용.
 */
@Composable
private fun CsvExportCard(selectedDate: LocalDate) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    var resultMsg by remember { mutableStateOf<String?>(null) }
    var rangeDays by remember { mutableStateOf(7) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = LimeLight.copy(alpha = 0.3f)),
        border = BorderStroke(1.dp, Primary.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📥", fontSize = 22.sp)
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Health Connect 데이터 CSV 추출",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        "걸음·수면·심박·SpO2 raw 데이터를 CSV로 받기",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }
            }
            Spacer(Modifier.height(10.dp))

            // 기간 선택
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("기간:", fontSize = 12.sp, color = TextSecondary)
                Spacer(Modifier.width(8.dp))
                listOf(1, 7, 31).forEach { days ->
                    Surface(
                        modifier = Modifier.padding(end = 6.dp).clickable { rangeDays = days },
                        color = if (rangeDays == days) Primary else BgCard,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            "${days}일",
                            fontSize = 12.sp,
                            color = if (rangeDays == days) Color.White else TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))

            Button(
                onClick = {
                    exporting = true
                    resultMsg = null
                    scope.launch {
                        val endDate = selectedDate
                        val startDate = endDate.minusDays((rangeDays - 1).toLong())
                        val result = com.dangbomcare.app.data.health.HealthDataCsvExporter.exportToCsv(
                            context, startDate, endDate
                        )
                        if (result.success && result.fileNames.isNotEmpty()) {
                            // 공유 인텐트
                            shareCsvFiles(context, result.outputDir, result.fileNames)
                            resultMsg = "✅ ${result.fileNames.size}개 파일, 총 ${result.totalRecords}건 추출 완료"
                        } else {
                            resultMsg = "⚠️ 추출 실패: ${result.error ?: "알 수 없는 오류"}"
                        }
                        exporting = false
                    }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                enabled = !exporting,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Primary)
            ) {
                if (exporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("추출 중...", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                } else {
                    Text("📥 CSV 추출 + 공유", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }

            resultMsg?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, fontSize = 11.sp, color = TextPrimary, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private fun shareCsvFiles(context: android.content.Context, dir: java.io.File, fileNames: List<String>) {
    try {
        val uris = fileNames.map { fn ->
            val file = java.io.File(dir, fn)
            androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        }
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/csv"
            putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, ArrayList(uris))
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(android.content.Intent.createChooser(intent, "CSV 파일 공유"))
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, "공유 실패: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
    }
}

/**
 * 데이터 새로고침 버튼 — 캐시 없이 다시 fetch.
 */
@Composable
private fun RefreshButton(onRefresh: () -> Unit) {
    OutlinedButton(
        onClick = onRefresh,
        modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, Primary)
    ) {
        Text("🔄 데이터 새로고침", fontSize = 13.sp, color = Primary, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun DiagnosticCard(diag: HealthConnectRepository.RawDataDiagnostic) {
    val statusColor = when {
        !diag.permissionGranted -> ErrorRed
        diag.error != null -> Warning
        diag.recordCount > 0 -> Success
        else -> TextSecondary
    }
    val statusIcon = when {
        !diag.permissionGranted -> "⛔"
        diag.error != null -> "⚠️"
        diag.recordCount > 0 -> "✅"
        else -> "○"
    }
    val statusText = when {
        !diag.permissionGranted -> "권한 없음"
        diag.error != null -> "오류"
        diag.recordCount > 0 -> "${diag.recordCount}개 수신"
        else -> "데이터 없음"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(statusIcon, fontSize = 16.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    diag.type,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    color = statusColor.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        statusText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = statusColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            // 조회 시간 범위 명시 — 사용자가 어느 일자 데이터인지 명확히 확인
            Spacer(Modifier.height(4.dp))
            Surface(
                color = Primary.copy(alpha = 0.08f),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    "📅 ${diag.timeRange}",
                    fontSize = 10.sp,
                    color = Primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            if (diag.sources.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "데이터 소스: ${diag.sources.joinToString(", ")}",
                    fontSize = 10.sp,
                    color = TextSecondary,
                    fontFamily = FontFamily.Monospace
                )
            }
            if (diag.sample.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Surface(
                    color = BgCardSoft,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        diag.sample,
                        fontSize = 11.sp,
                        color = TextPrimary,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
            if (diag.error != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "💬 ${diag.error}",
                    fontSize = 11.sp,
                    color = Warning,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}
