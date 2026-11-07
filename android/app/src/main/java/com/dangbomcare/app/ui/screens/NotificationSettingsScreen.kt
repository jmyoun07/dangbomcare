@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.dangbomcare.app.ui.screens

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.dangbomcare.app.ui.components.SectionCard
import com.dangbomcare.app.ui.theme.*

/**
 * 알림 세부 설정 화면 (사용자 피드백 — 설정 화면 박스 분리).
 * SettingsScreen 의 "알림" 박스 클릭 시 진입.
 */
@Composable
fun NotificationSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // [v1.0 버그픽스] SCHEDULE_EXACT_ALARM 권한 상태 — false면 알람이 inexact로 fallback해서
    //   Doze 모드에서 한꺼번에 묶여 늦게 발사됨. 화면 진입/복귀 시마다 재확인.
    var canScheduleExact by remember { mutableStateOf(checkCanScheduleExactAlarms(context)) }
    // [260721 버그A픽스] LifePatternRow에서 시각을 편집하고 이 화면으로 돌아왔을 때 아래 timeLabel이
    //   stale 값을 유지하지 않도록 ON_RESUME 마다 tick을 증가시켜 getHourMinute 재계산 트리거.
    var refreshTick by remember { mutableStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canScheduleExact = checkCanScheduleExactAlarms(context)
                refreshTick++
                // 권한 받았으면 모든 알람 재예약 → exact로 다시 등록됨
                if (canScheduleExact) {
                    com.dangbomcare.app.notification.AlarmScheduler.rescheduleAllEnabled(context)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // [260721 r27 심각버그픽스] 같은 화면 안에서 LifePatternRow 다이얼로그로 시각을 저장하면
    //   lifecycle 이벤트가 발생하지 않아 ON_RESUME 옵저버가 안 뜬다 → 위 refreshTick 미증가 →
    //   아래 알림 카드의 timeLabel 이 stale 로 남는 심각한 UX 버그.
    //   해결: SharedPreferences 변경을 직접 구독해서 저장 즉시 refreshTick 을 올려 재계산.
    //   - life_pattern: 아침/점심/저녁 식사 시각 (LifePatternRow → 저녁 저장 시 내부에서 DINNER 알람도 재예약)
    //   - dangdangcare_alarms: AlarmScheduler prefs — MORNING/BEDTIME 등 알람 시각 저장 대상
    //   콜백이 백그라운드 스레드에서 올 가능성에 대비해 main looper 로 post → 상태 갱신은 항상 UI 스레드.
    androidx.compose.runtime.DisposableEffect(Unit) {
        val lifePrefs = context.getSharedPreferences("life_pattern", android.content.Context.MODE_PRIVATE)
        val alarmPrefs = context.getSharedPreferences("dangdangcare_alarms", android.content.Context.MODE_PRIVATE)
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            mainHandler.post { refreshTick++ }
        }
        lifePrefs.registerOnSharedPreferenceChangeListener(listener)
        alarmPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            lifePrefs.unregisterOnSharedPreferenceChangeListener(listener)
            alarmPrefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    Scaffold(
        containerColor = BgSecondary,
        topBar = {
            TopAppBar(
                title = { Text("생활패턴·알림 설정", fontWeight = FontWeight.Bold) },   // [260705] 생활패턴 통합
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

            // [v1.0 버그픽스] 정확한 알람 권한 안내 배너 — 권한 없으면 알람이 늦게 옴
            if (!canScheduleExact) {
                ExactAlarmPermissionBanner(onGrant = { openExactAlarmSettings(context) })
            }

            // [260721] 안내 문구를 생활패턴 박스 위로 이동 + 카드형 디자인 강화.
            //   기존 위치(생활패턴↔알림 사이)의 밋밋한 회색 텍스트를 제거하고, 이곳에
            //   Primary tint 배경 + 💡 이모지가 있는 Row 카드로 배치. 좌우 padding 없이
            //   SectionCard와 수평 정렬(부모 Column의 horizontal=20.dp 만 상속).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = Primary.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("💡", fontSize = 18.sp)
                Spacer(Modifier.width(10.dp))
                Text(
                    "생활패턴 시각을 바꾸면 관련 알림 시간이 자동으로 재조정됩니다.",
                    fontSize = 14.sp,
                    color = TextPrimary,
                    lineHeight = 22.sp
                )
            }

            // [260719 재구성 v3] 생활패턴은 시각 편집만 (알림 토글 제거).
            //   시각 변경 시 관련 알람은 자동 재예약 (source of truth).
            LifePatternSection()

            // [260719 재구성 v3] '알림 설정' 섹션 부활 — 시각은 생활패턴에서 편집, 여기서는 on/off만.
            //   [260721] 각 항목 오른쪽에 실제 알림 발화 시각 표시.
            //     - MORNING: prefs 그대로.
            //     - DINNER: LifePatternRow가 저녁식사 시각 저장 시 이미 supper+30으로 setTime 하므로
            //         prefs 값 그대로가 실제 발화 시각(추가 +30 하면 이중 오프셋 버그).
            //     - BEDTIME: AlarmScheduler.schedule() 이 -30분으로 발사 → 저장 시각과 다르므로 -30분 오프셋 표시.
            //   [260721 버그A픽스] refreshTick 를 remember 키로 넣어 화면 재진입 시 재계산 → LifePatternRow
            //     편집 결과가 즉시 반영. 없으면 초기 컴포지션 값이 stale 로 남음.
            val (morningH, morningM) = remember(refreshTick) {
                com.dangbomcare.app.notification.AlarmScheduler.getHourMinute(
                    context, com.dangbomcare.app.notification.AlarmType.MORNING
                )
            }
            val (dinnerAlarmH, dinnerAlarmM) = remember(refreshTick) {
                com.dangbomcare.app.notification.AlarmScheduler.getHourMinute(
                    context, com.dangbomcare.app.notification.AlarmType.DINNER
                )
            }
            val (bedHRaw, bedMRaw) = remember(refreshTick) {
                com.dangbomcare.app.notification.AlarmScheduler.getHourMinute(
                    context, com.dangbomcare.app.notification.AlarmType.BEDTIME
                )
            }
            // [260721 버그B픽스] 취침 알림 발화 시각 = 저장 시각 -30분. 음수/자정 넘김 케이스 안전하게 정규화.
            val bedAlarmTotal = ((bedHRaw * 60 + bedMRaw - 30) % (24 * 60) + 24 * 60) % (24 * 60)
            val bedAlarmH = bedAlarmTotal / 60
            val bedAlarmM = bedAlarmTotal % 60

            SectionCard(title = "알림 설정") {
                AlarmOnOffRow(
                    label = "공복혈당 측정 알림",
                    timeLabel = "%02d:%02d".format(morningH, morningM),
                    type = com.dangbomcare.app.notification.AlarmType.MORNING
                )
                AlarmOnOffRow(
                    label = "저녁 식사 마감 알림",
                    timeLabel = "%02d:%02d".format(dinnerAlarmH, dinnerAlarmM),
                    type = com.dangbomcare.app.notification.AlarmType.DINNER
                )
                AlarmOnOffRow(
                    label = "취침 알림",
                    timeLabel = "%02d:%02d".format(bedAlarmH, bedAlarmM),
                    type = com.dangbomcare.app.notification.AlarmType.BEDTIME
                )
                AlarmOnOffRow(
                    label = "주간 리포트 알림\n(월요일 8시)",
                    timeLabel = null,
                    type = com.dangbomcare.app.notification.AlarmType.WEEKLY_REPORT
                )
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

// ============================================================
// [260719 재구성 v3 · 260721] 알림 on/off 전용 행 — 왼쪽 아이콘/서브텍스트 제거,
//   제목 오른쪽에 실제 알림 발화 시각을 표시(WEEKLY_REPORT는 시각 생략).
// ============================================================
@Composable
private fun AlarmOnOffRow(
    label: String,
    timeLabel: String?,
    type: com.dangbomcare.app.notification.AlarmType,
) {
    val context = LocalContext.current
    var enabled by remember {
        mutableStateOf(com.dangbomcare.app.notification.AlarmScheduler.isEnabled(context, type))
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            fontSize = 15.sp,
            color = TextPrimary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        if (timeLabel != null) {
            Text(
                timeLabel,
                fontSize = 15.sp,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.width(12.dp))
        }
        Switch(
            checked = enabled,
            onCheckedChange = { checked ->
                enabled = checked
                com.dangbomcare.app.notification.AlarmScheduler.setEnabled(context, type, checked)
            },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Primary,
                checkedTrackColor = LimeAccent
            )
        )
    }
}


// ============================================================
// [v1.0 버그픽스] SCHEDULE_EXACT_ALARM 권한 안내 배너 + 헬퍼
// ============================================================

/** Android 12+ 에서 정확한 알람 권한 보유 여부. 12 미만은 항상 true. */
private fun checkCanScheduleExactAlarms(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
    return am.canScheduleExactAlarms()
}

/** "알람 및 리마인더" 설정 화면 오픈. */
private fun openExactAlarmSettings(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = Uri.parse("package:${context.packageName}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        runCatching { context.startActivity(intent) }
            .onFailure {
                // 일부 OEM은 위 인텐트 미지원 → 앱 정보 화면으로 fallback
                val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                runCatching { context.startActivity(fallback) }
            }
    }
}

@Composable
private fun ExactAlarmPermissionBanner(onGrant: () -> Unit) {
    Surface(
        color = Color(0xFFFFF4E5),  // 연한 주황 — 주의 환기
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = Color(0xFFC77700),
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    "정확한 알람 권한이 꺼져 있어요",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = Color(0xFF5C3A00)
                )
            }
            Text(
                "권한이 없으면 알람이 늦게 오거나 여러 개가 한꺼번에 와요.\n" +
                    "아래 버튼을 누른 뒤 '당봄케어' 항목을 켜주세요.",
                fontSize = 12.sp,
                color = Color(0xFF5C3A00)
            )
            Button(
                onClick = onGrant,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFC77700),
                    contentColor = Color.White
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("정확한 알람 권한 켜기", fontWeight = FontWeight.Bold)
            }
        }
    }
}
