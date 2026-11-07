@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.dangbomcare.app.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dangbomcare.app.data.ServiceLocator
import com.dangbomcare.app.data.repo.ApiResult
import com.dangbomcare.app.ui.components.BigButton
import com.dangbomcare.app.ui.components.SectionCard
import com.dangbomcare.app.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun WatchDebugScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val healthRepo = remember { ServiceLocator.healthConnectRepository }
    val watchRepo = remember { ServiceLocator.watchDataRepository }

    var status by remember { mutableStateOf("준비") }
    var permAll by remember { mutableStateOf(false) }
    var permAny by remember { mutableStateOf(false) }
    var permDenied by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("아직 동기화 안 됨") }
    var lastSyncLabel by remember { mutableStateOf(com.dangbomcare.app.data.health.WatchSyncTracker.formatLastSync(context)) }

    suspend fun refreshStatus() {
        if (healthRepo.isSdkAvailable()) {
            val ps = healthRepo.checkPermissions()
            permAll = ps.all
            permAny = ps.any
            status = when {
                ps.all -> "✅ 권한 OK (전체 ${ps.totalCount}개)"
                ps.any -> "🟡 일부 권한 OK (${ps.grantedCount}/${ps.totalCount})"
                else -> "⚠️ 권한 필요"
            }
        } else {
            status = "❌ Health Connect 사용 불가"
        }
    }

    LaunchedEffect(Unit) { refreshStatus() }

    val permLauncher = rememberLauncherForActivityResult(
        contract = com.dangbomcare.app.data.health.HealthConnectRepository.PERMS_CONTRACT
    ) { granted ->
        permAll = granted.containsAll(com.dangbomcare.app.data.health.HealthConnectRepository.PERMISSIONS)
        permAny = granted.isNotEmpty()
        permDenied = granted.isEmpty()
        status = if (permAll) "✅ 권한 OK (전체)"
        else if (permAny) "🟡 일부 권한 OK (${granted.size}/${com.dangbomcare.app.data.health.HealthConnectRepository.PERMISSIONS.size})"
        else "❌ 권한 거부됨 — 설정 앱 → 권한에서 직접 부여 필요"
        // 사용자 피드백 — "자동 동기화가 되어야 함"
        // 권한이 한 항목이라도 새로 부여됐다면 자동 동기화 워커 즉시 1회 트리거
        // [Codex 검토] periodic UPDATE는 즉시 실행 보장 X → OneTimeWorkRequest 사용
        if (permAny) {
            com.dangbomcare.app.data.health.WatchSyncWorker.enqueueImmediateSync(context)
        }
    }

    Scaffold(
        containerColor = BgSecondary,
        topBar = {
            TopAppBar(
                title = { Text("워치 데이터", fontWeight = FontWeight.Bold) },
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            SectionCard(title = "Health Connect 상태") {
                Text(status, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                if (permAll) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "걸음 / 수면 / 심박 / 산소포화도 권한이 모두 부여되어 있어요.",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "마지막 동기화: $lastSyncLabel",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }

            // 권한 OK면 버튼 비활성 + 안내 (사용자: "권한 요청 눌러도 화면 안 나옴" → 이미 권한 OK라 OS가 즉시 통과)
            BigButton(
                text = if (permAll) "✅ 권한이 이미 부여됨" else "1️⃣ 권한 요청",
                onClick = { permLauncher.launch(com.dangbomcare.app.data.health.HealthConnectRepository.PERMISSIONS) },
                enabled = !permAll
            )
            if (permDenied) {
                Text(
                    "권한이 거부됐어요. Health Connect 앱 → 권한 → 당봄케어에서 직접 켜주세요.",
                    fontSize = 12.sp,
                    color = ErrorRed
                )
            }

            // 사용자 피드백: "어제 데이터" → "오늘+어제 데이터" 모두 동기화 + 표시
            BigButton(
                text = "2️⃣ 데이터 동기화 → 서버 전송",
                onClick = {
                    scope.launch {
                        result = "동기화 중..."
                        try {
                            val today = java.time.LocalDate.now()
                            val yesterday = today.minusDays(1)
                            val todayData = healthRepo.fetchWatchData(today)
                            val yesterdayData = healthRepo.fetchWatchData(yesterday)
                            val resYesterday = watchRepo.post(yesterdayData)
                            val resToday = watchRepo.post(todayData)
                            val ok = resYesterday is ApiResult.Success && resToday is ApiResult.Success
                            if (ok) {
                                com.dangbomcare.app.data.health.WatchSyncTracker.mark(context)
                                lastSyncLabel = com.dangbomcare.app.data.health.WatchSyncTracker.formatLastSync(context)
                            }
                            // 오늘 + 어제 데이터 한 번에 표시
                            val tSteps = todayData.steps ?: 0
                            val tSleepMin = todayData.sleepTotalMinutes ?: 0
                            val tHr = todayData.avgHeartRate
                            val ySteps = yesterdayData.steps ?: 0
                            val ySleepMin = yesterdayData.sleepTotalMinutes ?: 0
                            val yHr = yesterdayData.avgHeartRate
                            val emoji = if (ok) "✅" else "⚠️"
                            result = buildString {
                                append("$emoji ${if (ok) "서버 전송 완료" else "일부 전송 실패"}\n\n")
                                append("📅 오늘 (${today.monthValue}/${today.dayOfMonth})\n")
                                append("  🚶 걸음 ${"%,d".format(tSteps)}보\n")
                                append("  😴 수면 ${tSleepMin/60}h ${tSleepMin%60}m\n")
                                append("  ❤️ 평균 심박 ${tHr ?: "-"} bpm\n\n")
                                append("📅 어제 (${yesterday.monthValue}/${yesterday.dayOfMonth})\n")
                                append("  🚶 걸음 ${"%,d".format(ySteps)}보\n")
                                append("  😴 수면 ${ySleepMin/60}h ${ySleepMin%60}m\n")
                                append("  ❤️ 평균 심박 ${yHr ?: "-"} bpm")
                            }
                        } catch (e: Exception) {
                            result = "❌ 오류: ${e.message}"
                        }
                    }
                },
                enabled = permAny,
                primary = false
            )

            BigButton(
                text = "🔧 Health Connect 앱 열기",
                onClick = {
                    try {
                        context.startActivity(Intent("androidx.health.ACTION_HEALTH_CONNECT_SETTINGS"))
                    } catch (e: Exception) {
                        try {
                            val intent = Intent(Intent.ACTION_MAIN)
                            intent.setPackage("com.google.android.apps.healthdata")
                            context.startActivity(intent)
                        } catch (_: Exception) { /* ignore */ }
                    }
                },
                primary = false
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = BgCardSoft)
            ) {
                Text(
                    text = result,
                    modifier = Modifier.padding(16.dp),
                    fontSize = 13.sp,
                    color = TextPrimary
                )
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = LimeLight.copy(alpha = 0.3f))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("💡 동기화 안내", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = PrimaryDark)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "• Samsung Health에서 Health Connect로 데이터 공유가 ON되어 있어야 해요.\n" +
                        "• 앱이 열린 상태/로그인 직후/권한 부여 직후에 자동으로 한 번 동기화돼요.\n" +
                        "• 백그라운드에서도 30분 주기로 자동 동기화를 시도합니다 (네트워크 연결 시).\n" +
                        "• 단, 절전 모드·기기 제조사 백그라운드 제한 시에는 지연될 수 있어요.\n" +
                        "  이런 경우 위 \"데이터 동기화\" 버튼으로 직접 동기화하실 수 있어요.",
                        fontSize = 12.sp,
                        color = TextPrimary,
                        lineHeight = 18.sp
                    )
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}
