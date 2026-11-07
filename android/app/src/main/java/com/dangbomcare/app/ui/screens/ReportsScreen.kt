@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.dangbomcare.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.dangbomcare.app.data.api.CommonReason
import com.dangbomcare.app.data.api.GlucoseCalendar
import com.dangbomcare.app.data.api.GlucoseDay
import com.dangbomcare.app.data.api.MonthlyReportResponse
import com.dangbomcare.app.data.api.RiskFactor
import com.dangbomcare.app.data.api.UsualRange
import com.dangbomcare.app.data.api.WeeklyReportResponse
import com.dangbomcare.app.ui.components.*
import com.dangbomcare.app.ui.theme.*
import com.dangbomcare.app.ui.viewmodel.ReportViewModel

/**
 * [260726] 리포트 화면의 '오늘' — 백엔드가 KST 기준으로 날짜를 저장/집계하므로 Asia/Seoul 고정.
 *   기기 로컬 타임존이 KST가 아니면(해외/수동 변경) '지난 주·지난 달' 판정과 빈 화면 안내 문구가 하루 어긋난다.
 *   (ReportViewModel / WatchSyncActions / MorningInputViewModel 과 동일 규칙)
 */
private fun todayKst(): java.time.LocalDate = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))

@Composable
private fun StressBox(
    relaxedDays: Int?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isEmpty: Boolean = false,
    emptyLabel: String = "기록 전",
    totalDays: Int = 7
): Unit = WeeklyMetricTile(
    icon = "😌",
    label = "스트레스 적정",
    value = relaxedDays?.toString() ?: "-",
    unit = "/${totalDays}일",
    modifier = modifier,
    onClick = onClick,
    isEmpty = isEmpty || relaxedDays == null,
    emptyLabel = emptyLabel
)

@Composable
fun ReportsScreen(
    onBack: () -> Unit,
    scrollToTopTrigger: Int = 0,
    // 사용자 피드백: 주간/월간 지표 클릭 → 해당 7일/31일 상세 화면 이동
    // [v1.0] 주간/월간에서 클릭 시 해당 기간의 endDate를 같이 전달 → 상세 화면이 그 주의 7일을 표시
    // [v1.0 피드백] 모든 지표 클릭 시 (period, date) 둘 다 전달 → detail 화면이 7일/31일 모드로 시작
    onGotoGlucose: (period: String, date: String) -> Unit = { _, _ -> },
    onGotoActivity: (period: String, date: String) -> Unit = { _, _ -> },
    onGotoSleep: (period: String, date: String) -> Unit = { _, _ -> },
    onGotoStress: (period: String, date: String) -> Unit = { _, _ -> },
    // 사용자 피드백 — 일별 점수 그래프의 점 클릭 → 점수 상세 화면
    onGotoScoreDetail: (String) -> Unit = {},
    vm: ReportViewModel = viewModel()
) {
    val state by vm.state.collectAsState()
    val scrollState = rememberScrollState()
    LaunchedEffect(scrollToTopTrigger) {
        if (scrollToTopTrigger > 0) scrollState.animateScrollTo(0)
    }

    // [260726] 리포트 탭에 올 때마다(ON_RESUME) 현재 기간 리포트 새로고침 — 홈/기록 탭과 동일한 패턴.
    //   ⚠️ 하단탭 이동은 saveState/restoreState 라 ViewModelStore 가 유지돼 init 의 loadWeekly() 가 앱 세션당 1회만 실행됐다.
    //      → 아침에 리포트를 본 뒤 홈에서 입력을 마치고 다시 들어오면 기록 일수·평균·추이가 입력 전 값 그대로였음.
    //   최초 진입 중복 로드와 자정 넘김(주/월 전환) 처리는 ViewModel.refresh() 안에 있다.
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) vm.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    Scaffold(
        containerColor = BgSecondary,
        topBar = {
            TopAppBar(
                title = { Text("리포트", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgSecondary)
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            PrimaryTabRow(
                selectedTabIndex = state.tab.ordinal,
                containerColor = BgSecondary,
                contentColor = Primary
            ) {
                Tab(
                    selected = state.tab == ReportViewModel.Tab.WEEKLY,
                    onClick = { vm.selectTab(ReportViewModel.Tab.WEEKLY) }
                ) {
                    Text("주간", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(16.dp))
                }
                Tab(
                    selected = state.tab == ReportViewModel.Tab.MONTHLY,
                    onClick = { vm.selectTab(ReportViewModel.Tab.MONTHLY) }
                ) {
                    Text("월간", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(16.dp))
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                val isWeeklyTab = state.tab == ReportViewModel.Tab.WEEKLY
                val currentReport: Any? = if (isWeeklyTab) state.weekly else state.monthly
                when {
                    state.loading -> LoadingIndicator(modifier = Modifier.height(200.dp))
                    // [QA fix] 조회 실패를 '신규 사용자'로 위장하지 않음 — 네트워크/파싱 오류가 "데이터 사라짐"처럼 보이던 문제.
                    //   에러 + 표시할 데이터 없음 → '불러오기 실패 + 다시 시도'. (갱신 에러로 기존 데이터가 있으면 그대로 유지)
                    state.error != null && currentReport == null -> ReportErrorCard(
                        onRetry = { if (isWeeklyTab) vm.loadWeekly() else vm.loadMonthly() }
                    )
                    // 빈 기간 안내는 각 View 내부에서(주/월 이동 화살표 아래) 처리 → 화살표가 항상 유지됨.
                    isWeeklyTab -> WeeklyView(
                        state, vm,
                        onGotoGlucose = onGotoGlucose,
                        onGotoActivity = onGotoActivity,
                        onGotoSleep = onGotoSleep,
                        onGotoStress = onGotoStress,
                        onGotoScoreDetail = onGotoScoreDetail
                    )
                    else -> MonthlyView(
                        state, vm,
                        onGotoGlucose = onGotoGlucose,
                        onGotoActivity = onGotoActivity,
                        onGotoSleep = onGotoSleep,
                        onGotoStress = onGotoStress,
                        onGotoScoreDetail = onGotoScoreDetail
                    )
                }
                Spacer(Modifier.height(20.dp))  // 하단 네비는 상위 Scaffold padding에서 이미 반영
            }
        }
    }
}

@Composable
private fun WeeklyView(
    state: ReportViewModel.UiState,
    vm: ReportViewModel,
    // [v1.0 피드백] 모든 지표 클릭 시 (period, date) 둘 다 전달 → detail 화면이 7일/31일 모드로 시작
    onGotoGlucose: (period: String, date: String) -> Unit = { _, _ -> },
    onGotoActivity: (period: String, date: String) -> Unit = { _, _ -> },
    onGotoSleep: (period: String, date: String) -> Unit = { _, _ -> },
    onGotoStress: (period: String, date: String) -> Unit = { _, _ -> },
    onGotoScoreDetail: (String) -> Unit = {}
) {
    // [사용자 피드백] 백엔드 watch_data가 동기화 안 됐어도 Health Connect에서 직접 평균 계산해서 표시
    val context = androidx.compose.ui.platform.LocalContext.current
    var clientAvgSteps by remember(state.weekStart) { mutableStateOf<Int?>(null) }
    var clientAvgSleepMin by remember(state.weekStart) { mutableStateOf<Int?>(null) }
    LaunchedEffect(state.weekStart) {
        try {
            val repo = com.dangbomcare.app.data.health.HealthConnectRepository(context)
            if (repo.isSdkAvailable()) {
                val from = state.weekStart
                val to = state.weekStart.plusDays(6)
                val stepsByDay = repo.fetchStepsByDay(from, to)
                val nonZeroSteps = stepsByDay.values.filter { it > 0 }
                clientAvgSteps = if (nonZeroSteps.isNotEmpty()) nonZeroSteps.average().toInt() else null

                val sleepByDay = repo.fetchSleepByDay(from, to)
                val nonZeroSleep = sleepByDay.values.map { it.totalMinutes }.filter { it > 0 }
                clientAvgSleepMin = if (nonZeroSleep.isNotEmpty()) nonZeroSleep.average().toInt() else null
            }
        } catch (e: Exception) { /* HC 권한/SDK 이슈 무시 */ }
    }

    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = vm::previousWeek) { Icon(Icons.Filled.ChevronLeft, contentDescription = "이전 주") }
        val w = state.weekly
        com.dangbomcare.app.ui.components.AutoSizeSingleLineText(
            "${state.weekStart} ~ ${state.weekStart.plusDays(6)}",
            maxFontSize = 14.sp, minFontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        IconButton(onClick = vm::nextWeek) { Icon(Icons.Filled.ChevronRight, contentDescription = "다음 주") }
    }

    // [QA fix] 이 주에 기록(공복혈당)이 0일이면 빈 화면 대신 안내 — 위 화살표는 항상 유지.
    if ((state.weekly?.recordedDays ?: 0) == 0) {
        NewUserEmptyReportCard(isWeekly = true, isPast = state.weekStart.plusDays(6).isBefore(todayKst()))  // [260726] KST 기준
    } else state.weekly?.let { w ->
        // 0. 마일스톤 배지 (당봄 #12 — 연속 달성·활동 증가·점수 신기록)
        w.milestones?.takeIf { it.isNotEmpty() }?.let { ms ->
            MilestoneBadgeCard(ms)
        }

        // 1. [260623] 공복혈당 달력 (주간) — 기존 AI 주간 피드백 카드 삭제, 그 자리에 달력
        w.glucoseCalendar?.let { cal ->
            WeeklyGlucoseCalendar(cal, state.weekStart)
        }

        // 2. 일별 점수 변화 + 평균 점수 (통합)
        // [사용자 피드백] 카드 전체 클릭(→항상 오늘) 제거 → 차트에서 날짜별 점 클릭 시 그 날 상세로.
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = BgCard)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "일별 점수 변화",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        // [260707] '기록 N/7일'을 제목 아래 크게+굵게. 접미 안내문구 제거.
                        val recordedDays = w.dailyScores?.count { it > 0 } ?: 0
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "기록 ${recordedDays}/7일",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            // [260726 접근성] 글자색은 대비 확보 변형(WarningText) — 원색 Warning은 흰 배경 대비 ~1.8:1
                            color = if (recordedDays == 0) WarningText else TextSecondary
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("주 평균", fontSize = 12.sp, color = TextSecondary)
                        Text(
                            "${w.avgScore?.let { "%.1f".format(it) } ?: "-"}점",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Black,
                            color = Primary
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                ScoreLineChart(
                    // 차트는 정수 높이면 충분 → 소수 총점을 Int로 변환 (모델은 소수 안전을 위해 Double)
                    scores = w.dailyScores?.map { it.toInt() } ?: emptyList(),
                    weekStart = state.weekStart,
                    onDayClick = { idx ->
                        // 그 날 점수가 있는 날만 그 날 점수상세로 이동 (수면·걸음처럼)
                        val sc = (w.dailyScores ?: emptyList()).getOrNull(idx) ?: 0.0
                        if (sc > 0) onGotoScoreDetail(state.weekStart.plusDays(idx.toLong()).toString())
                    }
                )
            }
        }

        // 3. [260623] 패턴 발견 = 공복혈당 달력 분석 (+ 통계 인사이트 보조)
        run {
            val calPattern = w.glucoseCalendar?.let { glucoseCalendarPattern(it, "이번 주") }
            val insights = w.correlationInsights ?: emptyList()
            if (calPattern != null || insights.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1))
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("🔍", fontSize = 20.sp)
                            Spacer(Modifier.width(8.dp))
                            Text("이번 주 패턴 발견", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        }
                        Spacer(Modifier.height(10.dp))
                        if (calPattern != null) {
                            Text(calPattern, fontSize = 13.sp, color = TextPrimary, lineHeight = 20.sp)
                        }
                        insights.forEachIndexed { i, msg ->
                            if (i > 0 || calPattern != null) Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.Top) {
                                Text("•  ", fontSize = 14.sp, color = TextSecondary)
                                Text(msg, fontSize = 13.sp, color = TextPrimary, lineHeight = 20.sp)
                            }
                        }
                    }
                }
            }
        }

        // 3-2. Top 위험 요인 (당봄 — 이번 주 개선 포인트)
        // [260707] 공복혈당은 '결과값'이라 개선 포인트(행동 목표)에서 제외 — 위 달력/패턴카드가 개인범위로 다룸.
        //   (점수 미달=100~130 목표범위인데도 '높은 날'로 오표기되던 문제 근본 해소)
        w.topRiskFactors?.filter { it.factor != "glucose" }?.takeIf { it.isNotEmpty() }?.let { risks ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🎯", fontSize = 20.sp)
                        Spacer(Modifier.width(8.dp))
                        Text("다음 주 개선 포인트", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("다음 주엔 이 점을 목표로 해보세요.", fontSize = 12.sp, color = TextSecondary)
                    Spacer(Modifier.height(10.dp))
                    // [260726] 조언 문구의 저녁 시각은 하드코딩이 아니라 사용자 설정값을 따른다.
                    val dinnerLabelAdvice = com.dangbomcare.app.notification.AlarmScheduler
                        .dinnerDeadlineLabel(androidx.compose.ui.platform.LocalContext.current)
                    risks.forEachIndexed { i, risk ->
                        if (i > 0) Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.Top) {
                            Text("•  ", fontSize = 14.sp, color = TextSecondary)
                            Text(
                                riskFactorAdvice(risk, dinnerLabelAdvice) ?: risk.label ?: "",
                                fontSize = 13.sp,
                                color = TextPrimary,
                                lineHeight = 20.sp
                            )
                        }
                    }
                }
            }
        }

        // 4. 지표 — 사용자 피드백: 6개 박스 3×2 grid (메인 3 + 회고 3)
        w.metrics?.let { m ->
            val recordedDays = w.dailyScores?.count { it > 0 } ?: 0
            val hasRetrospect = recordedDays > 0
            // [사용자 피드백] 과거 주이면 "기록 전" → "기록 없음" (더 이상 입력 불가)
            val today = todayKst()  // [260726] KST 기준
            val weekEnd = state.weekStart.plusDays(6)
            val isPastWeek = weekEnd.isBefore(today)
            val emptyLabelText = if (isPastWeek) "기록 없음" else "기록 전"

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = BgCard)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "주간 지표 (평균)",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "기록 ${recordedDays}/7일",
                            fontSize = 12.sp,
                            // [260726 접근성] 글자색은 대비 확보 변형(WarningText) 사용
                            color = if (hasRetrospect) TextSecondary else WarningText,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    // [v1.0 피드백] 평균 지표 — 클릭 시 그 주의 7일 데이터로 이동 (weekEnd 전달)
                    val weeklyAnchor = state.weekStart.plusDays(6).toString()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        WeeklyMetricTile(
                            icon = "🩸",
                            label = "공복혈당",
                            value = m.avgGlucose?.let { "%.0f".format(it) } ?: "-",
                            unit = "mg/dL",
                            modifier = Modifier.weight(1f),
                            onClick = { onGotoGlucose("7d", weeklyAnchor) },
                            isEmpty = (m.avgGlucose == null),
                            emptyLabel = emptyLabelText
                        )
                        WeeklyMetricTile(
                            icon = "🚶",
                            label = "걸음수",
                            value = (m.avgSteps ?: clientAvgSteps)?.let { "%,d".format(it) } ?: "-",
                            unit = "보",
                            modifier = Modifier.weight(1f),
                            onClick = { onGotoActivity("7d", weeklyAnchor) },
                            isEmpty = (m.avgSteps == null && clientAvgSteps == null),
                            emptyLabel = emptyLabelText
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        WeeklyMetricTile(
                            icon = "😴",
                            label = "수면",
                            value = (m.avgSleepHours ?: clientAvgSleepMin?.let { it / 60.0 })
                                ?.let { h ->
                                    val tm = (h * 60).toInt()
                                    "${tm / 60}시간 ${tm % 60}분"
                                } ?: "-",
                            unit = "",
                            modifier = Modifier.weight(1f),
                            onClick = { onGotoSleep("7d", weeklyAnchor) },
                            isEmpty = (m.avgSleepHours == null && clientAvgSleepMin == null),
                            emptyLabel = emptyLabelText
                        )
                        // [v1.0 피드백] 스트레스 지표 추가
                        StressBox(
                            relaxedDays = m.stressRelaxedDays,
                            onClick = { onGotoStress("7d", weeklyAnchor) },
                            modifier = Modifier.weight(1f),
                            isEmpty = m.stressRelaxedDays == null,
                            emptyLabel = emptyLabelText,
                            totalDays = 7
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    // 하단 — 회고 지표 (음주/저녁/야식). 회고 미입력 시 isEmpty
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        WeeklyMetricTile(
                            icon = "🍺",
                            label = "음주",
                            value = "${m.alcoholDays ?: 0}",
                            unit = "일",
                            modifier = Modifier.weight(1f),
                            onClick = null,
                            isEmpty = !hasRetrospect,
                            emptyLabel = emptyLabelText
                        )
                        WeeklyMetricTile(
                            icon = "🍚",
                            label = "${com.dangbomcare.app.notification.AlarmScheduler.dinnerHourLabel(androidx.compose.ui.platform.LocalContext.current)} 전 저녁",
                            value = "${m.dinnerBefore7pmDays ?: 0}",
                            unit = "일",
                            modifier = Modifier.weight(1f),
                            onClick = null,
                            isEmpty = !hasRetrospect,
                            emptyLabel = emptyLabelText
                        )
                        WeeklyMetricTile(
                            icon = "🌙",
                            label = "식후 금식 미완료",
                            value = "${m.lateEatingDays ?: 0}",
                            unit = "일",
                            modifier = Modifier.weight(1f),
                            onClick = null,
                            isEmpty = !hasRetrospect,
                            emptyLabel = emptyLabelText
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    // [피드백 260719 E] 신규 3항목 지표 추가 (저녁유형/자기 전 소화/식후 운동)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        WeeklyMetricTile(
                            icon = "🍽",
                            label = "건강한 저녁",
                            value = "${m.healthyDinnerDays ?: 0}",
                            unit = "일",
                            modifier = Modifier.weight(1f),
                            onClick = null,
                            isEmpty = !hasRetrospect,
                            emptyLabel = emptyLabelText
                        )
                        WeeklyMetricTile(
                            icon = "😴",
                            label = "속 편한 취침",
                            value = "${m.comfortableDigestionDays ?: 0}",
                            unit = "일",
                            modifier = Modifier.weight(1f),
                            onClick = null,
                            isEmpty = !hasRetrospect,
                            emptyLabel = emptyLabelText
                        )
                        WeeklyMetricTile(
                            icon = "🚶",
                            label = "식후 운동 10분+",
                            value = "${m.postMealExerciseDays ?: 0}",
                            unit = "일",
                            modifier = Modifier.weight(1f),
                            onClick = null,
                            isEmpty = !hasRetrospect,
                            emptyLabel = emptyLabelText
                        )
                    }
                }
            }
        }
    }
}

/** 주간 지표 한 박스 — isEmpty=true 시 흐림 + emptyLabel. 클릭 가능 시 우측 상단에 chevron 표시. */
@Composable
private fun WeeklyMetricTile(
    icon: String,
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    isEmpty: Boolean = false,
    emptyLabel: String = "기록 전"
) {
    val isClickable = onClick != null && !isEmpty
    Surface(
        modifier = modifier.let { if (isClickable) it.clickable { onClick!!() } else it },
        shape = RoundedCornerShape(14.dp),
        color = if (isEmpty) BgCardSoft.copy(alpha = 0.4f) else BgCardSoft
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // [v1.0 피드백] 클릭 가능한 박스는 우측 상단에 chevron 표시 → "들어갈 수 있다" 시각적 단서
            if (isClickable) {
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = "상세 보기",
                    tint = TextSecondary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 6.dp, end = 4.dp)
                        .size(16.dp)
                )
            }
            Column(
                modifier = Modifier.padding(vertical = 12.dp, horizontal = 6.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    icon,
                    fontSize = 20.sp,
                    modifier = if (isEmpty) Modifier.alpha(0.35f) else Modifier
                )
                Spacer(Modifier.height(4.dp))
                // [260706 R7] 타일 라벨 크기 통일 — 전 타일이 가장 긴 라벨 기준으로 같은 크기
                //   (기존엔 '식후 금식 미완료'만 줄어들어 '음주'와 크기가 달랐음)
                // [260721] 실제 최장 라벨은 '12시 30분 전 저녁'(저녁시각 설정 반영) — 이 문자열 기준으로
                //   축소해야 6개 타일 라벨 크기가 실제로 균일해짐
                com.dangbomcare.app.ui.components.AutoSizeSingleLineText(
                    label,
                    maxFontSize = 11.sp,
                    minFontSize = 8.5.sp,
                    color = if (isEmpty) TextDisabled else TextSecondary,
                    fontWeight = FontWeight.Medium,
                    fitText = "12시 30분 전 저녁",
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
                if (isEmpty) {
                    Text(
                        emptyLabel,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextDisabled,
                        maxLines = 1
                    )
                } else {
                    Row(verticalAlignment = Alignment.Bottom) {
                        // 좁은 타일(폴드 커버 2열)·큰 글씨에서 "7시간 30분" 등이 잘리지 않게 자동 축소
                        com.dangbomcare.app.ui.components.AutoSizeSingleLineText(
                            value,
                            modifier = Modifier.weight(1f, fill = false),
                            maxFontSize = 14.sp,
                            minFontSize = 9.sp,
                            fontWeight = FontWeight.Black,
                            color = TextPrimary
                        )
                        if (unit.isNotEmpty()) {
                            Spacer(Modifier.width(2.dp))
                            Text(
                                unit,
                                fontSize = 10.sp,
                                color = TextSecondary,
                                modifier = Modifier.padding(bottom = 1.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthlyView(
    state: ReportViewModel.UiState,
    vm: ReportViewModel,
    // [v1.0 피드백] 모든 지표 클릭 시 (period, date) 둘 다 전달 → detail 화면이 7일/31일 모드로 시작
    onGotoGlucose: (period: String, date: String) -> Unit = { _, _ -> },
    onGotoActivity: (period: String, date: String) -> Unit = { _, _ -> },
    onGotoSleep: (period: String, date: String) -> Unit = { _, _ -> },
    onGotoStress: (period: String, date: String) -> Unit = { _, _ -> },
    onGotoScoreDetail: (String) -> Unit = {}
) {
    // [사용자 피드백] 백엔드 동기화 안 됐어도 HC에서 직접 평균 계산 fallback
    val context = androidx.compose.ui.platform.LocalContext.current
    var clientAvgSteps by remember(state.year, state.month) { mutableStateOf<Int?>(null) }
    var clientAvgSleepMin by remember(state.year, state.month) { mutableStateOf<Int?>(null) }
    LaunchedEffect(state.year, state.month) {
        try {
            val repo = com.dangbomcare.app.data.health.HealthConnectRepository(context)
            if (repo.isSdkAvailable()) {
                val monthStart = java.time.LocalDate.of(state.year, state.month, 1)
                val monthEnd = monthStart.withDayOfMonth(monthStart.lengthOfMonth())
                val stepsByDay = repo.fetchStepsByDay(monthStart, monthEnd)
                val nonZeroSteps = stepsByDay.values.filter { it > 0 }
                clientAvgSteps = if (nonZeroSteps.isNotEmpty()) nonZeroSteps.average().toInt() else null

                val sleepByDay = repo.fetchSleepByDay(monthStart, monthEnd)
                val nonZeroSleep = sleepByDay.values.map { it.totalMinutes }.filter { it > 0 }
                clientAvgSleepMin = if (nonZeroSleep.isNotEmpty()) nonZeroSleep.average().toInt() else null
            }
        } catch (e: Exception) { /* HC 권한/SDK 이슈 무시 */ }
    }

    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = vm::previousMonth) { Icon(Icons.Filled.ChevronLeft, contentDescription = "이전 달") }
        com.dangbomcare.app.ui.components.AutoSizeSingleLineText(
            "${state.year}년 ${state.month}월",
            maxFontSize = 18.sp, minFontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        IconButton(onClick = vm::nextMonth) { Icon(Icons.Filled.ChevronRight, contentDescription = "다음 달") }
    }

    // [QA fix] 이 달에 기록(공복혈당)이 0일이면 빈 화면 대신 안내 — 위 화살표는 항상 유지.
    if ((state.monthly?.metrics?.daysWithData ?: 0) == 0) {
        // [260726] KST 기준
        NewUserEmptyReportCard(isWeekly = false, isPast = java.time.YearMonth.of(state.year, state.month).isBefore(java.time.YearMonth.from(todayKst())))
    } else state.monthly?.let { m ->
        // 1. [260623] 공복혈당 달력 (월간) — 기존 AI 월간 분석 카드 삭제, 그 자리에 달력
        m.glucoseCalendar?.let { cal ->
            MonthlyGlucoseCalendar(cal, state.year, state.month)
        }

        // 2. 월 평균 점수 + 전월 대비
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = BgCard)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("${m.month}월 평균 점수", fontSize = 14.sp, color = TextSecondary)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${m.avgScore?.let { "%.1f".format(it) } ?: "-"}점",
                        fontSize = 48.sp,
                        fontWeight = FontWeight.Black,
                        color = Primary
                    )
                    // 지난달 대비 화살표 (당봄 #16 — 상승=빨강, 하락=초록)
                    if (m.avgScore != null && m.prevMonthAvgScore != null) {
                        Spacer(Modifier.width(8.dp))
                        ScoreChangeArrow(
                            current = m.avgScore,
                            previous = m.prevMonthAvgScore,
                            label = "지난달"
                        )
                    }
                }
            }
        }

        // 3. 주차별 점수 막대 그래프
        m.weeklyScores?.takeIf { it.isNotEmpty() }?.let { ws ->
            SectionCard(title = "주차별 점수 변화") {
                WeeklyBarChart(ws.map { it.toInt() })
            }
        }

        // 3-1. [260623] 패턴 발견 = 공복혈당 달력 분석 (+ 통계 인사이트 보조)
        run {
            val calPattern = m.glucoseCalendar?.let { glucoseCalendarPattern(it, "이번 달") }
            val insights = m.correlationInsights ?: emptyList()
            if (calPattern != null || insights.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1))
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("🔍", fontSize = 20.sp)
                            Spacer(Modifier.width(8.dp))
                            Text("이번 달 패턴 발견", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        }
                        Spacer(Modifier.height(10.dp))
                        if (calPattern != null) {
                            Text(calPattern, fontSize = 13.sp, color = TextPrimary, lineHeight = 20.sp)
                        }
                        insights.forEachIndexed { i, msg ->
                            if (i > 0 || calPattern != null) Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.Top) {
                                Text("•  ", fontSize = 14.sp, color = TextSecondary)
                                Text(msg, fontSize = 13.sp, color = TextPrimary, lineHeight = 20.sp)
                            }
                        }
                    }
                }
            }
        }

        // 3-2. Top 위험 요인 (당봄 — 이번 달 개선 포인트)
        // [260707] 공복혈당은 '결과값'이라 개선 포인트에서 제외 (주간과 동일).
        m.topRiskFactors?.filter { it.factor != "glucose" }?.takeIf { it.isNotEmpty() }?.let { risks ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🎯", fontSize = 20.sp)
                        Spacer(Modifier.width(8.dp))
                        Text("다음 달 개선 포인트", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("다음 달엔 이 점을 목표로 해보세요.", fontSize = 12.sp, color = TextSecondary)
                    Spacer(Modifier.height(10.dp))
                    // [260726] 조언 문구의 저녁 시각은 하드코딩이 아니라 사용자 설정값을 따른다.
                    val dinnerLabelAdvice = com.dangbomcare.app.notification.AlarmScheduler
                        .dinnerDeadlineLabel(androidx.compose.ui.platform.LocalContext.current)
                    risks.forEachIndexed { i, risk ->
                        if (i > 0) Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.Top) {
                            Text("•  ", fontSize = 14.sp, color = TextSecondary)
                            Text(
                                riskFactorAdvice(risk, dinnerLabelAdvice) ?: risk.label ?: "",
                                fontSize = 13.sp,
                                color = TextPrimary,
                                lineHeight = 20.sp
                            )
                        }
                    }
                }
            }
        }

        // [사용자 피드백] 혈당 인사이트 / 핵심 목표 달성률 카드 제거
        //   - 혈당 인사이트: 단순 통계라 의미 없음 → 혈당 박스 클릭 시 베스트/워스트 분석으로 대체 (TODO)
        //   - 핵심 목표 달성률: 하단 지표 박스의 음주/저녁/야식 카운트와 중복

        // 4. 월간 지표 — 사용자 피드백: 6박스 3×2 grid (주간과 동일 패턴)
        m.metrics?.let { mt ->
            val hasRetrospect = (mt.daysWithData ?: 0) > 0
            val totalDays = mt.totalDays ?: 30
            // [사용자 피드백] 과거 월이면 "기록 전" → "기록 없음"
            val today = todayKst()  // [260726] KST 기준
            val isPastMonth = (state.year < today.year) ||
                (state.year == today.year && state.month < today.monthValue)
            val emptyLabelText = if (isPastMonth) "기록 없음" else "기록 전"
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = BgCard)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "월간 지표 (평균)",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "기록 ${mt.daysWithData ?: 0}/${totalDays}일",
                            fontSize = 12.sp,
                            // [260726 접근성] 글자색은 대비 확보 변형(WarningText) 사용
                            color = if (hasRetrospect) TextSecondary else WarningText,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    // [v1.0 피드백] 월간 지표 — 클릭 시 그 달의 31일 데이터로 이동 (monthEnd 전달)
                    val monthlyAnchor = java.time.LocalDate.of(state.year, state.month, 1)
                        .let { it.withDayOfMonth(it.lengthOfMonth()) }
                        .toString()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        WeeklyMetricTile(
                            icon = "🩸",
                            label = "공복혈당",
                            value = mt.avgGlucose?.let { "%.0f".format(it) } ?: "-",
                            unit = "mg/dL",
                            modifier = Modifier.weight(1f),
                            onClick = { onGotoGlucose("31d", monthlyAnchor) },
                            isEmpty = (mt.avgGlucose == null),
                            emptyLabel = emptyLabelText
                        )
                        WeeklyMetricTile(
                            icon = "🚶",
                            label = "걸음수",
                            value = (mt.avgSteps ?: clientAvgSteps)?.let { "%,d".format(it) } ?: "-",
                            unit = "보",
                            modifier = Modifier.weight(1f),
                            onClick = { onGotoActivity("31d", monthlyAnchor) },
                            isEmpty = (mt.avgSteps == null && clientAvgSteps == null),
                            emptyLabel = emptyLabelText
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        WeeklyMetricTile(
                            icon = "😴",
                            label = "수면",
                            value = (mt.avgSleepHours ?: clientAvgSleepMin?.let { it / 60.0 })
                                ?.let { h ->
                                    val tm = (h * 60).toInt()
                                    "${tm / 60}시간 ${tm % 60}분"
                                } ?: "-",
                            unit = "",
                            modifier = Modifier.weight(1f),
                            onClick = { onGotoSleep("31d", monthlyAnchor) },
                            isEmpty = (mt.avgSleepHours == null && clientAvgSleepMin == null),
                            emptyLabel = emptyLabelText
                        )
                        // [v1.0 피드백] 스트레스 지표 추가
                        StressBox(
                            relaxedDays = mt.stressRelaxedDays,
                            onClick = { onGotoStress("31d", monthlyAnchor) },
                            modifier = Modifier.weight(1f),
                            isEmpty = mt.stressRelaxedDays == null,
                            emptyLabel = emptyLabelText,
                            totalDays = totalDays
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    // 하단 — 회고 지표 (음주/저녁/야식)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        WeeklyMetricTile(
                            icon = "🍺",
                            label = "음주",
                            value = "${mt.alcoholDays ?: 0}",
                            unit = "일",
                            modifier = Modifier.weight(1f),
                            onClick = null,
                            isEmpty = !hasRetrospect,
                            emptyLabel = emptyLabelText
                        )
                        WeeklyMetricTile(
                            icon = "🍚",
                            label = "${com.dangbomcare.app.notification.AlarmScheduler.dinnerHourLabel(androidx.compose.ui.platform.LocalContext.current)} 전 저녁",
                            value = "${mt.dinnerBefore7pmDays ?: 0}",
                            unit = "일",
                            modifier = Modifier.weight(1f),
                            onClick = null,
                            isEmpty = !hasRetrospect,
                            emptyLabel = emptyLabelText
                        )
                        WeeklyMetricTile(
                            icon = "🌙",
                            label = "식후 금식 미완료",
                            value = "${mt.lateEatingDays ?: 0}",
                            unit = "일",
                            modifier = Modifier.weight(1f),
                            onClick = null,
                            isEmpty = !hasRetrospect,
                            emptyLabel = emptyLabelText
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    // [피드백 260719 E] 월간 신규 3항목 지표 (저녁유형/자기 전 소화/식후 운동)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        WeeklyMetricTile(
                            icon = "🍽",
                            label = "건강한 저녁",
                            value = "${mt.healthyDinnerDays ?: 0}",
                            unit = "일",
                            modifier = Modifier.weight(1f),
                            onClick = null,
                            isEmpty = !hasRetrospect,
                            emptyLabel = emptyLabelText
                        )
                        WeeklyMetricTile(
                            icon = "😴",
                            label = "속 편한 취침",
                            value = "${mt.comfortableDigestionDays ?: 0}",
                            unit = "일",
                            modifier = Modifier.weight(1f),
                            onClick = null,
                            isEmpty = !hasRetrospect,
                            emptyLabel = emptyLabelText
                        )
                        WeeklyMetricTile(
                            icon = "🚶",
                            label = "식후 운동 10분+",
                            value = "${mt.postMealExerciseDays ?: 0}",
                            unit = "일",
                            modifier = Modifier.weight(1f),
                            onClick = null,
                            isEmpty = !hasRetrospect,
                            emptyLabel = emptyLabelText
                        )
                    }
                }
            }
        }
    }
}

// ============================================================
// [260623] 공복혈당 달력 (리포트 주간/월간) — 셀=혈당, 색=평소범위 대비
// ============================================================
private val GcHigh = Color(0xFFEF9A9A)   // 평소보다 높음 — 빨강
private val GcLow = Color(0xFF90CAF9)     // 평소보다 낮음 — 파랑
private val GcIn = Color(0xFFE0E0E0)      // 범위 안(범위 있음) / 기록한 날(범위 없음) — 회색
private val GcNone = Color(0xFFF7F7F7)    // 기록 없음
private val GcSunday = Color(0xFFD32F2F)

/** [260728] 평소 범위 미설정이면 백엔드가 status="unknown" 을 준다(구버전은 "in_range").
 *  어느 쪽이든 '기록은 있는 날'이므로 '기록 없음' 색(GcNone)으로 칠하면 안 된다.
 *  미지 값이 와도 회색으로 떨어지게 when{} 로 두고, 색의 '뜻'은 카드의 범례가 정한다. */
private fun gcColor(day: GlucoseDay?): Color = when {
    day == null -> GcNone
    day.status == "high" -> GcHigh
    day.status == "low" -> GcLow
    else -> GcIn
}

/** [260728 확정①] '평소 범위'가 실제로 있는가 — 이 한 곳에서만 판정한다(층마다 다르게 판정하다 사고난 전례).
 *  두 겹으로 본다: ① usual_range 가 null (범위 자체가 없음)
 *                ② status="unknown" 인 날이 있음 (백엔드가 '판정 불가'로 내려준 신호)
 *  false 면 화면 어디서도 '범위 안'이라고 말하지 않는다. */
private fun GlucoseCalendar.hasUsualRange(): Boolean =
    usualRange != null && days.orEmpty().none { it.status == "unknown" }

/** 달력 기반 패턴 줄글 — '패턴 발견' 카드용 (당봄 260623). */
private fun glucoseCalendarPattern(cal: GlucoseCalendar, periodLabel: String): String? {
    val rec = cal.recordedDays
    // 평소 범위 미설정이면 높음/낮음 판정이 불가 → 잘못된 '범위 안' 단정 방지 (패턴은 통계 인사이트로 대체)
    if (rec == 0 || !cal.hasUsualRange()) return null
    val inRange = (rec - cal.outOfRangeCount).coerceAtLeast(0)
    // [260702] 기록이 3일 미만이면 '안정적' 같은 단정 금지 — 표본 부족을 분명히
    if (rec < 3) {
        return "$periodLabel 아직 기록이 ${rec}일뿐이라 경향을 말하긴 일러요. 며칠 더 기록하면 패턴을 알려드릴게요."
    }
    val sb = StringBuilder("$periodLabel 공복혈당을 기록한 ${rec}일 중 ${inRange}일은 평소 범위 안이었어요.")
    if (cal.outOfRangeCount == 0) {
        sb.append(" 기간 내내 안정적이었어요. 지금처럼만 해주세요!")
    } else {
        // [260705] 높인/낮춘 요인을 구분해 줄글로
        val topHigh = cal.highReasons.orEmpty().firstOrNull()
        val topLow = cal.lowReasons.orEmpty().firstOrNull()
        if (cal.highCount > 0) {
            sb.append(" 높았던 ${cal.highCount}일엔")
            sb.append(if (topHigh != null) " 주로 '${topHigh.label}' 요인이 있었어요." else " 특별한 공통 요인을 찾지 못했어요.")
        }
        if (cal.lowCount > 0) {
            sb.append(" 낮았던 ${cal.lowCount}일엔")
            sb.append(if (topLow != null) " '${topLow.label}' 요인이 있었어요." else " 기록된 요인이 없었어요.")
        }
    }
    return sb.toString()
}

/** [260705 R3] 상태별 일수 칩 — 달력 셀과 같은 색으로 직관적으로. */
@Composable
private fun GcCountChip(color: Color, label: String, count: Int, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(10.dp), color = color, modifier = modifier) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, fontSize = 12.sp, color = TextPrimary.copy(alpha = 0.75f), maxLines = 1, softWrap = false)
            Text("${count}일", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary, maxLines = 1, softWrap = false)
        }
    }
}

/** [260705 R4] 높인/낮춘 요인 섹션 — 공통 이유를 한 목록으로 정리 (회고+걸음·수면·스트레스 통합).
 *  별도 평균 비교 박스는 사용자 피드백으로 제거 — 워치 기반 요인도 같은 불릿(예: '활동량이 적었음 — 2일')으로. */
@Composable
private fun GcReasonSection(title: String, reasons: List<CommonReason>, visible: Boolean) {
    if (!visible) return
    Spacer(Modifier.height(12.dp))
    Text(title, fontSize = 13.sp, color = TextPrimary, fontWeight = FontWeight.SemiBold, lineHeight = 19.sp)
    Spacer(Modifier.height(4.dp))
    if (reasons.isEmpty()) {
        Text("기록이 부족해 공통 이유를 찾지 못했어요.", fontSize = 13.sp, color = TextSecondary, lineHeight = 19.sp)
    } else {
        reasons.forEach { r ->
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 2.dp)) {
                Text("•  ", fontSize = 13.sp, color = TextSecondary)
                // [260728 통합검증] 줄표(—) → 콜론(" : "). 260727 고객 피드백으로 같은 화면의
                //   describeXxx 10곳과 백엔드 정적 문구를 전부 콜론으로 바꿨는데 여기만 남아 있었다.
                //   "늦은 저녁 식사 — 2일 있었어요"는 시니어가 앞뒤를 한 문장으로 이어 읽는다.
                //   구분자만 바꿨고 의미·수치는 그대로다.
                Text("${r.label} : ${r.count}일 있었어요", fontSize = 13.sp, color = TextPrimary, lineHeight = 19.sp)
            }
        }
    }
}

@Composable
private fun GcLegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Spacer(Modifier.width(5.dp))
        Text(label, fontSize = 12.sp, color = TextSecondary)
    }
}

/** 달력 한 칸 — 상단 라벨(요일/일자) + 혈당 숫자, 배경=상태색. high/low만 탭 가능.
 *  [260705] 글자 깨짐 절대 방지: 셀 안 글자는 dp 고정 크기(시스템 글자확대 무관) + 한 줄 강제.
 *  좁은 기종·큰 글씨 설정에서도 "147"이 "14/7"로 줄바꿈되는 문제가 구조적으로 안 생김. */
@Composable
private fun GcCell(
    topLabel: String,
    day: GlucoseDay?,
    hasUsualRange: Boolean,     // [260728] 평소 범위가 없으면 TalkBack도 '범위 안'이라 말하지 않는다
    modifier: Modifier = Modifier,
    labelColor: Color = TextSecondary,
    subLabel: String? = null,   // 주간: 날짜(일). 월간은 topLabel이 이미 날짜라 null.
    onClick: (GlucoseDay) -> Unit
) {
    val tappable = day != null && (day.status == "high" || day.status == "low")
    val statusWord = when {
        day == null -> "기록 없음"
        day.status == "high" -> "평소보다 높음"
        day.status == "low" -> "평소보다 낮음"
        // 평소 범위가 있을 때만 '범위 안'이라고 단정. 없으면(unknown 포함) 판정 없이 '기록 있음'.
        day.status == "in_range" && hasUsualRange -> "평소 범위 안"
        else -> "기록 있음"
    }
    val cd = if (day == null) "$topLabel, 기록 없음"
        else "$topLabel, 공복혈당 ${day.glucose}, $statusWord" + (if (tappable) ", 눌러서 요인 보기" else "")
    val den = LocalDensity.current
    // [260705 R3] 요일·날짜 살짝 확대 (11/10 → 12.5/11.5)
    val fsLabel = with(den) { 12.5.dp.toSp() }
    val fsSub = with(den) { 11.5.dp.toSp() }
    val fsNum = with(den) { 14.dp.toSp() }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(gcColor(day))
            .then(if (tappable) Modifier.clickable { onClick(day!!) } else Modifier)
            .semantics { contentDescription = cd }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(topLabel, fontSize = fsLabel, color = labelColor, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
        if (subLabel != null) {
            Text(subLabel, fontSize = fsSub, color = TextSecondary, maxLines = 1, softWrap = false)
        }
        Spacer(Modifier.height(2.dp))
        Text(
            day?.glucose?.toString() ?: "·",
            fontSize = fsNum,
            fontWeight = FontWeight.Bold,
            color = if (day == null) TextSecondary.copy(alpha = 0.35f) else TextPrimary,
            maxLines = 1,
            softWrap = false
        )
    }
}

/** 주간/월간 공용 카드 — 헤더(평소 범위) + grid + 범례 + 하단(공통요인·N/총일) + 요인 팝업. */
@Composable
private fun GlucoseCalendarCard(
    cal: GlucoseCalendar,
    totalDays: Int,
    grid: @Composable (onDayClick: (GlucoseDay) -> Unit) -> Unit
) {
    // cal(주/월 전환·리포트 갱신)이 바뀌면 열려있던 팝업의 옛 날짜를 닫는다 (cal 키)
    var dialogDay by remember(cal) { mutableStateOf<GlucoseDay?>(null) }
    val hasUsualRange = cal.hasUsualRange()
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🩸 공복혈당 달력", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TextPrimary, modifier = Modifier.weight(1f))
                // 범위가 실제로 유효할 때만 '평소 N~N' 배지 (판정 불가면 배지도 숨겨 한 화면에서 말이 엇갈리지 않게)
                cal.usualRange?.takeIf { hasUsualRange }?.let { r ->
                    Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFFEFEFEF)) {
                        Text("평소 ${r.low}~${r.high}", fontSize = 12.sp, color = TextPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                    }
                }
            }
            if (!hasUsualRange) {
                // [260728 확정①] 왜 범위 표시가 없는지 시니어가 이해할 수 있게 — '아직 정하지 않았다' + '어떻게 정해지는지'
                Spacer(Modifier.height(6.dp))
                Text(
                    "아직 '평소 공복혈당 범위'가 정해지지 않아서 높음·낮음을 나눌 수 없어요. 지금은 기록한 날만 회색으로 보여드려요.",
                    fontSize = 12.sp, color = TextSecondary, lineHeight = 17.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "달이 바뀌면 지난 한 달 기록으로 범위를 계산해 여쭤봐요. 설정 > 평소 공복혈당 범위에서 직접 입력할 수도 있어요.",
                    fontSize = 12.sp, color = TextSecondary, lineHeight = 17.sp
                )
            }
            Spacer(Modifier.height(14.dp))
            grid { d -> if (d.status == "high" || d.status == "low") dialogDay = d }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (hasUsualRange) {
                    GcLegendDot(GcHigh, "높음")
                    GcLegendDot(GcIn, "범위 안")
                    GcLegendDot(GcLow, "낮음")
                } else {
                    // 범위 미설정 — 회색의 뜻을 '범위 안'이 아닌 '기록한 날'로 바꿔 말한다
                    // (GcNone 은 흰 카드 위에서 안 보이는 색이라 '기록 없음' 점은 넣지 않는다)
                    GcLegendDot(GcIn, "기록한 날")
                }
            }
            if (hasUsualRange && cal.usualRange != null) {
                // [260702] 달력 색은 '평소 범위' 기준 — 혈당 분포의 '당뇨 목표(100~130)'와 다른 기준임을 명시
                Spacer(Modifier.height(6.dp))
                Text(
                    "※ 색은 내 '평소 범위(${cal.usualRange.low}~${cal.usualRange.high})' 기준이에요. 당뇨 목표 구간과는 다를 수 있어요.",
                    fontSize = 11.sp, color = TextSecondary, lineHeight = 15.sp
                )
            }
            if (cal.recordedDays > 0) {
                Spacer(Modifier.height(14.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFEEEEEE)))
                Spacer(Modifier.height(12.dp))
                // [260705 R3] 상태별 일수 — 달력 색과 같은 색 칩으로 직관적으로
                if (hasUsualRange) {
                    Text("기록 ${cal.recordedDays}일", fontSize = 13.sp, color = TextSecondary, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GcCountChip(GcIn, "범위 안", cal.inRangeCount, Modifier.weight(1f))
                        GcCountChip(GcHigh, "높음", cal.highCount, Modifier.weight(1f))
                        GcCountChip(GcLow, "낮음", cal.lowCount, Modifier.weight(1f))
                    }
                } else {
                    // [260728 확정①] 범위가 없으면 '범위 안 N일'은 사실이 아니다 → 판정 없는 '기록 N일'만 보여준다.
                    //   (in_range_count 는 쓰지 않는다. 구버전 백엔드는 이 값에 전체 일수를 담아 보낸다)
                    Row(modifier = Modifier.fillMaxWidth()) {
                        GcCountChip(GcIn, "기록", cal.recordedDays, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "평소 범위가 정해지면 '범위 안 · 높음 · 낮음'으로 나눠서 보여드릴게요.",
                        fontSize = 12.sp, color = TextSecondary, lineHeight = 17.sp
                    )
                }
                // [260705 피드백] '혈당을 높인 요인'과 '낮춘 요인'을 명확히 분리 + 워치 비교 분석
                GcReasonSection(
                    title = "🔴 혈당이 높았던 날(${cal.highCount}일)의 공통점",
                    reasons = cal.highReasons.orEmpty(),
                    visible = cal.highCount > 0
                )
                GcReasonSection(
                    title = "🔵 혈당이 낮았던 날(${cal.lowCount}일)의 공통점",
                    reasons = cal.lowReasons.orEmpty(),
                    visible = cal.lowCount > 0
                )
                if (cal.outOfRangeCount > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text("색이 있는 날(빨강·파랑)을 누르면 그 날의 요인을 볼 수 있어요.", fontSize = 12.sp, color = TextSecondary, lineHeight = 17.sp)
                }
            }
        }
    }
    dialogDay?.let { d -> GlucoseFactorDialog(d) { dialogDay = null } }
}

@Composable
private fun WeeklyGlucoseCalendar(cal: GlucoseCalendar, weekStart: java.time.LocalDate) {
    val byDate = remember(cal) { cal.days.orEmpty().associateBy { it.date } }
    val labels = listOf("월", "화", "수", "목", "금", "토", "일")
    GlucoseCalendarCard(cal = cal, totalDays = 7) { onDayClick ->
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (i in 0..6) {
                val date = weekStart.plusDays(i.toLong())
                GcCell(
                    topLabel = labels[i],
                    day = byDate[date.toString()],
                    hasUsualRange = cal.hasUsualRange(),
                    modifier = Modifier.weight(1f),
                    labelColor = if (i == 6) GcSunday else TextSecondary,
                    subLabel = "${date.dayOfMonth}",   // [260705] 요일 아래 날짜 함께 표시
                    onClick = onDayClick
                )
            }
        }
    }
}

@Composable
private fun MonthlyGlucoseCalendar(cal: GlucoseCalendar, year: Int, month: Int) {
    val byDate = remember(cal) { cal.days.orEmpty().associateBy { it.date } }
    val firstDay = remember(year, month) { java.time.LocalDate.of(year, month, 1) }
    val daysInMonth = firstDay.lengthOfMonth()
    // 1일의 요일 → 일요일 시작 그리드 열 인덱스 (Mon=1..Sun=7 → Sun=0,...,Sat=6)
    val firstCol = firstDay.dayOfWeek.value % 7
    val headers = listOf("일", "월", "화", "수", "목", "금", "토")
    GlucoseCalendarCard(cal = cal, totalDays = daysInMonth) { onDayClick ->
        Column {
            Row(modifier = Modifier.fillMaxWidth()) {
                headers.forEachIndexed { idx, h ->
                    Text(h, fontSize = 11.sp, color = if (idx == 0) GcSunday else TextSecondary, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(6.dp))
            val rows = (firstCol + daysInMonth + 6) / 7
            var dayNum = 1
            for (r in 0 until rows) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    for (c in 0..6) {
                        val cellIdx = r * 7 + c
                        if (cellIdx < firstCol || dayNum > daysInMonth) {
                            Spacer(Modifier.weight(1f))
                        } else {
                            val date = java.time.LocalDate.of(year, month, dayNum)
                            GcCell(
                                topLabel = "$dayNum",
                                day = byDate[date.toString()],
                                hasUsualRange = cal.hasUsualRange(),
                                modifier = Modifier.weight(1f),
                                labelColor = if (c == 0) GcSunday else TextSecondary,
                                onClick = onDayClick
                            )
                            dayNum++
                        }
                    }
                }
                if (r < rows - 1) Spacer(Modifier.height(3.dp))
            }
        }
    }
}

@Composable
private fun GlucoseFactorDialog(day: GlucoseDay, onDismiss: () -> Unit) {
    val isHigh = day.status == "high"
    val headColor = if (isHigh) GcSunday else Color(0xFF1976D2)
    val dateLabel = remember(day.date) {
        try { val d = java.time.LocalDate.parse(day.date); "${d.monthValue}월 ${d.dayOfMonth}일" } catch (e: Exception) { day.date }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = Color.White,   // [260705] 연보라(M3 기본 tint) 제거 — 흰 배경 통일
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기", color = Primary, fontWeight = FontWeight.Bold) } },
        title = {
            Column {
                Text(dateLabel, fontSize = 13.sp, color = TextSecondary)
                Spacer(Modifier.height(4.dp))
                Text(
                    "공복혈당 ${day.glucose} · 평소보다 ${if (isHigh) "높았어요" else "낮았어요"}",
                    fontSize = 16.sp, fontWeight = FontWeight.Bold, color = headColor
                )
            }
        },
        text = {
            Column {
                // [260705 R3] '전 날'이 어제로 읽히는 혼동 → 실제 날짜(그 날의 전날)를 명시
                val prevDateLabel = remember(day.date) {
                    try {
                        val p = java.time.LocalDate.parse(day.date).minusDays(1)
                        "${p.monthValue}월 ${p.dayOfMonth}일"
                    } catch (e: Exception) { "전날" }
                }
                Text(
                    if (isHigh) "그 전날($prevDateLabel) 저녁~밤의 생활이 이 날 아침 공복혈당에 영향을 줬을 수 있어요."
                    else "그 전날($prevDateLabel) 저녁~밤에 이런 점이 있어 혈당이 낮았을 수 있어요.",
                    fontSize = 13.sp, color = TextSecondary, lineHeight = 19.sp
                )
                Spacer(Modifier.height(12.dp))
                val factors = day.factors.orEmpty()
                if (factors.isEmpty()) {
                    Text("그 전날($prevDateLabel)의 기록이 부족해 요인을 찾지 못했어요.", fontSize = 13.sp, color = TextPrimary)
                } else {
                    factors.forEach { f ->
                        Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 3.dp)) {
                            Text("•  ", fontSize = 14.sp, color = headColor)
                            Text(f, fontSize = 14.sp, color = TextPrimary, lineHeight = 20.sp)
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun WeeklyBarChart(scores: List<Int>) {
    val maxScore = (scores.maxOrNull() ?: 100).coerceAtLeast(50)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(140.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            scores.forEachIndexed { i, s ->
                val heightFraction = if (maxScore > 0) s.toFloat() / 100f else 0f
                val barColor = when {
                    s >= 70 -> ScoreGood
                    s >= 50 -> ScoreOk
                    s > 0 -> ScoreLow
                    else -> Divider
                }
                // [260726 접근성] ScoreOk(#E9C46A)는 흰 배경 대비 ~1.8:1 → 글자에 쓰면 시니어가 못 읽는다.
                //   막대(배경)는 원색 유지, 숫자 라벨만 대비 확보 변형(WarningText)으로 교체.
                val labelColor = if (barColor == ScoreOk) WarningText else barColor
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                ) {
                    if (s > 0) {
                        Text(
                            "${s}",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = labelColor
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .fillMaxWidth(0.6f)
                            .fillMaxHeight(heightFraction.coerceAtLeast(0.02f))
                            .background(
                                color = if (s > 0) barColor else Divider,
                                shape = RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)
                            )
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            scores.forEachIndexed { i, _ ->
                Text(
                    "${i + 1}주",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun GlucoseDistributionBar(
    dist: com.dangbomcare.app.data.api.GlucoseDistribution,
    total: Int
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(14.dp)
            .clip(RoundedCornerShape(7.dp))
    ) {
        if (dist.low > 0) androidx.compose.foundation.layout.Box(modifier = Modifier.weight(dist.low.toFloat()).fillMaxHeight().background(ScoreLow))
        if (dist.normal > 0) androidx.compose.foundation.layout.Box(modifier = Modifier.weight(dist.normal.toFloat()).fillMaxHeight().background(Success))
        if (dist.impaired > 0) androidx.compose.foundation.layout.Box(modifier = Modifier.weight(dist.impaired.toFloat()).fillMaxHeight().background(ScoreOk))
        if (dist.diabeticManaged > 0) androidx.compose.foundation.layout.Box(modifier = Modifier.weight(dist.diabeticManaged.toFloat()).fillMaxHeight().background(Warning))
        if (dist.diabeticHigh > 0) androidx.compose.foundation.layout.Box(modifier = Modifier.weight(dist.diabeticHigh.toFloat()).fillMaxHeight().background(ErrorRed))
    }
    Spacer(Modifier.height(6.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // [260623 피드백] 당뇨인 3구간 — 정상(<100)/당뇨목표(100~130)/높음(>130). '경계' 표현 제거.
        if (dist.low > 0) LegendDot(ScoreLow, "낮음(저혈당) ${dist.low}")  // [코덱스 P1/P2] 저혈당 범례
        LegendDot(Success, "정상 ${dist.normal}")
        LegendDot(ScoreOk, "당뇨목표 ${dist.impaired}")
        LegendDot(Warning, "높음 ${dist.diabeticManaged}")
        if (dist.diabeticHigh > 0) LegendDot(ErrorRed, "매우높음 ${dist.diabeticHigh}")
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, shape = androidx.compose.foundation.shape.CircleShape)
        )
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 11.sp, color = TextSecondary)
    }
}

@Composable
private fun HighlightDayRow(label: String, dateIso: String, score: Int, color: Color) {
    val display = try {
        val d = java.time.LocalDate.parse(dateIso)
        "${d.monthValue}월 ${d.dayOfMonth}일"
    } catch (e: Exception) { dateIso }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 13.sp, color = TextPrimary)
        Text(display, fontSize = 13.sp, color = TextSecondary)
        Spacer(Modifier.width(8.dp))
        Text("${score}점", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
private fun GoalProgressRow(label: String, goal: com.dangbomcare.app.data.api.GoalRate) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), fontSize = 14.sp, color = TextPrimary)
            Text(
                "${goal.achieved}/${goal.total}일",
                fontSize = 13.sp,
                color = TextSecondary
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "${goal.rate}%",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                // [260726 접근성] 글자엔 대비 확보 변형 사용 — ScoreOk(#E9C46A)는 흰 배경에서 거의 안 보임.
                //   아래 진행 막대(배경)는 원색 ScoreOk 유지.
                color = when {
                    goal.rate >= 70 -> Success
                    goal.rate >= 40 -> WarningText
                    else -> ScoreLow
                }
            )
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { (goal.rate / 100f).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            color = when {
                goal.rate >= 70 -> Success
                goal.rate >= 40 -> ScoreOk
                else -> ScoreLow
            },
            trackColor = Divider
        )
    }
}

@Composable
private fun DemoBanner() {
    // ⚠️ 환자가 데모를 본인 결과로 오해하지 않도록 강조 (Codex 검토)
    Spacer(Modifier.height(8.dp))
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Warning.copy(alpha = 0.18f)),
        border = androidx.compose.foundation.BorderStroke(2.dp, Warning)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("⚠️", fontSize = 22.sp)
                Spacer(Modifier.width(10.dp))
                Text(
                    "이건 예시 데이터예요 (본인 데이터 X)",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Warning
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "리포트 화면 디자인 미리보기용입니다. 매일 회고를 입력하시고 7일 이상 데이터가 쌓이면 본인 실제 데이터로 표시됩니다.",
                fontSize = 12.sp,
                color = TextPrimary,
                lineHeight = 17.sp
            )
        }
    }
}

@Composable
private fun DemoWeeklyView(state: ReportViewModel.UiState, vm: ReportViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = vm::previousWeek) { Icon(Icons.Filled.ChevronLeft, contentDescription = "이전 주") }
        com.dangbomcare.app.ui.components.AutoSizeSingleLineText(
            "${state.weekStart} ~ ${state.weekStart.plusDays(6)}",
            maxFontSize = 14.sp, minFontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        IconButton(onClick = vm::nextWeek) { Icon(Icons.Filled.ChevronRight, contentDescription = "다음 주") }
    }

    // 1. [260623] 공복혈당 달력 (예시) — AI 주간 피드백 카드 대체
    val demoCal = remember(state.weekStart) {
        val ws = state.weekStart
        GlucoseCalendar(
            usualRange = UsualRange(100, 130),
            days = listOf(
                GlucoseDay(ws.toString(), 112, "in_range"),
                // [260728 ③] 음주를 '높았던 날' 요인으로 예시하면 음주→혈당 상승으로 읽힌다.
                //   → 음주는 '낮았던 날' 요인으로 옮긴다(실제 경향과 일치).
                GlucoseDay(ws.plusDays(1).toString(), 145, "high", listOf("늦은 저녁 식사", "수면 부족(5.5시간)")),
                GlucoseDay(ws.plusDays(2).toString(), 108, "in_range"),
                GlucoseDay(ws.plusDays(3).toString(), 152, "high", listOf("외식·고기 위주 저녁", "배부른 상태로 취침")),
                GlucoseDay(ws.plusDays(4).toString(), 120, "in_range"),
                GlucoseDay(ws.plusDays(5).toString(), 92, "low", listOf("전날 음주", "충분한 식후 운동")),
                GlucoseDay(ws.plusDays(6).toString(), 115, "in_range")
            ),
            highReasons = listOf(
                CommonReason("늦은 저녁 식사", 1),
                CommonReason("외식·고기 위주 저녁", 1)
            ),
            lowReasons = listOf(CommonReason("전날 음주", 1), CommonReason("충분한 식후 운동", 1)),
            inRangeCount = 4, highCount = 2, lowCount = 1,
            outOfRangeCount = 3,
            recordedDays = 7
        )
    }
    WeeklyGlucoseCalendar(demoCal, state.weekStart)

    // 2. 일별 점수 변화 + 평균 점수 (통합) — 데모
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, Divider)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "일별 점수 변화 (예시)",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                Column(horizontalAlignment = Alignment.End) {
                    Text("주 평균", fontSize = 12.sp, color = TextSecondary)
                    Text("78점", fontSize = 24.sp, fontWeight = FontWeight.Black, color = Primary)
                }
            }
            Spacer(Modifier.height(12.dp))
            ScoreLineChart(scores = listOf(72, 80, 65, 85, 78, 82, 75), weekStart = state.weekStart)
        }
    }

    // 3. 데모 상관관계 인사이트
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1))
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🔍", fontSize = 20.sp)
                Spacer(Modifier.width(8.dp))
                Text("이번 주 패턴 발견 (예시)", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            }
            Spacer(Modifier.height(10.dp))
            listOf(
                // [260728 고객피드백 ③] 방향이 반대로 쓰여 있던 예시 — 술을 드신 다음날 아침 공복혈당은
                //   오히려 낮게 나오는 경향이다(간이 알코올을 분해하는 동안 밤사이 혈당을 못 올려줘서).
                "🍷 음주 다음날 공복혈당 평균 98 mg/dL, 안 마신 다음날 평균 118 mg/dL — 술을 드신 다음날 아침이 20 mg/dL 낮게 나왔어요",
                "🍚 저녁 7시 전 식사 마친 날 평균 82점, 늦게 드신 날 평균 71점 — 11점 차이로 도움됐어요",
                "😴 7시간 이상 주무신 날 평균 78점, 부족한 날 평균 70점 — 수면이 8점 차이를 만들었어요"
            ).forEachIndexed { i, msg ->
                if (i > 0) Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Text("•  ", fontSize = 14.sp, color = TextSecondary)
                    Text(msg, fontSize = 13.sp, color = TextPrimary, lineHeight = 20.sp)
                }
            }
        }
    }

    // 4. 데모 지표
    SectionCard(title = "주간 지표 (평균·예시)") {
        MetricItem("평균 공복혈당", "98", "mg/dL", "🩸")
        MetricItem("평균 걸음수", "7,234", "보", "🚶")
        MetricItem("평균 수면", "7.2", "시간", "😴")
        MetricItem("음주 일수", "1", "일", "🍺")
        MetricItem("${com.dangbomcare.app.notification.AlarmScheduler.dinnerHourLabel(androidx.compose.ui.platform.LocalContext.current)} 전 저녁", "5", "일", "🍚")
        MetricItem("식후 금식 미완료", "2", "일", "🌙")
    }

    // 4. 데모 Top 위험 요인
    SectionCard(title = "다음 주 개선 포인트 (예시)") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // [260728 ③] "공복혈당 +15 영향"은 음주가 혈당을 올린다는 뜻 → 방향 교정
            DemoRiskItem("🍷", "음주 1회", "다음날 아침 공복혈당이 낮게 나올 수 있음")
            DemoRiskItem("🌙", "식후 금식 미완료 2일", "수면 질 저하 + 다음날 혈당 ↑")
            DemoRiskItem("😴", "수면 6시간 미만 2일", "인슐린 저항성 ↑")
        }
    }
}

@Composable
private fun DemoMonthlyView(state: ReportViewModel.UiState, vm: ReportViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = vm::previousMonth) { Icon(Icons.Filled.ChevronLeft, contentDescription = "이전 달") }
        com.dangbomcare.app.ui.components.AutoSizeSingleLineText(
            "${state.year}년 ${state.month}월 (예시)",
            maxFontSize = 18.sp, minFontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        IconButton(onClick = vm::nextMonth) { Icon(Icons.Filled.ChevronRight, contentDescription = "다음 달") }
    }

    // 1. [260623] 공복혈당 달력 (예시) — AI 월간 분석 카드 대체
    val demoCalM = remember(state.year, state.month) {
        val ym = java.time.LocalDate.of(state.year, state.month, 1)
        fun d(day: Int) = ym.withDayOfMonth(day.coerceAtMost(ym.lengthOfMonth())).toString()
        GlucoseCalendar(
            usualRange = UsualRange(100, 130),
            days = listOf(
                GlucoseDay(d(2), 118, "in_range"),
                // [260728 ③] 음주는 '높았던 날' 요인이 아니라 '낮았던 날' 요인으로 예시한다.
                GlucoseDay(d(5), 142, "high", listOf("늦은 저녁 식사")),
                GlucoseDay(d(8), 110, "in_range"),
                GlucoseDay(d(12), 95, "low", listOf("전날 음주", "충분한 식후 운동")),
                GlucoseDay(d(15), 148, "high", listOf("외식·고기 위주 저녁", "식후 운동 없음")),
                GlucoseDay(d(19), 122, "in_range"),
                GlucoseDay(d(23), 138, "high", listOf("배부른 상태로 취침")),
                GlucoseDay(d(27), 116, "in_range")
            ),
            highReasons = listOf(
                CommonReason("늦은 저녁 식사", 1),
                CommonReason("외식·고기 위주 저녁", 1),
                CommonReason("배부른 상태로 취침", 1)
            ),
            lowReasons = listOf(CommonReason("전날 음주", 1), CommonReason("충분한 식후 운동", 1)),
            inRangeCount = 4, highCount = 3, lowCount = 1,
            outOfRangeCount = 4,
            recordedDays = 8
        )
    }
    MonthlyGlucoseCalendar(demoCalM, state.year, state.month)

    // 2. 월 평균 점수 + 전월 대비
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, Divider)
    ) {
        Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${state.month}월 평균 점수 (예시)", fontSize = 14.sp, color = TextSecondary)
            Spacer(Modifier.height(8.dp))
            Text("76점", fontSize = 48.sp, fontWeight = FontWeight.Black, color = Primary)
            Spacer(Modifier.height(4.dp))
            Text("▲ 지난달 대비 4.0점 ↑", fontSize = 13.sp, color = Success, fontWeight = FontWeight.SemiBold)
        }
    }

    // 3. 주차별 점수 막대 그래프
    SectionCard(title = "주차별 점수 변화 (예시)") {
        WeeklyBarChart(listOf(72, 78, 74, 80))
    }

    // 4. 혈당 인사이트
    SectionCard(title = "🩸 혈당 인사이트 (예시)") {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("월 평균 공복혈당", modifier = Modifier.weight(1f), fontSize = 14.sp, color = TextPrimary)
            Text("105 mg/dL", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = ScoreOk)
        }
        Spacer(Modifier.height(8.dp))
        Text("정상 범위(70~100) 내 일수: 18/28일 (64%)", fontSize = 13.sp, color = TextSecondary)
        Spacer(Modifier.height(8.dp))
        GlucoseDistributionBar(
            com.dangbomcare.app.data.api.GlucoseDistribution(low = 0, normal = 18, impaired = 8, diabeticManaged = 2, diabeticHigh = 0),
            28
        )
        Spacer(Modifier.height(12.dp))
        HighlightDayRow("🏆 가장 좋았던 날", "${state.year}-${"%02d".format(state.month)}-14", 88, Success)
        Spacer(Modifier.height(8.dp))
        HighlightDayRow("💪 어려웠던 날", "${state.year}-${"%02d".format(state.month)}-07", 52, Warning)
    }

    // 5. 목표 달성률
    SectionCard(title = "🎯 핵심 목표 달성률 (예시)") {
        GoalProgressRow(
            "저녁 ${com.dangbomcare.app.notification.AlarmScheduler.dinnerHourLabel(androidx.compose.ui.platform.LocalContext.current)} 전 식사",
            com.dangbomcare.app.data.api.GoalRate(achieved = 18, total = 28, rate = 64)
        )
        Spacer(Modifier.height(12.dp))
        GoalProgressRow(
            "음주 없는 날",
            com.dangbomcare.app.data.api.GoalRate(achieved = 20, total = 28, rate = 71)
        )
        Spacer(Modifier.height(12.dp))
        GoalProgressRow(
            "식후 금식 지킨 날",
            com.dangbomcare.app.data.api.GoalRate(achieved = 24, total = 28, rate = 86)
        )
    }

    // 6. 월간 지표
    SectionCard(title = "월간 지표 (평균·예시)") {
        MetricItem("평균 걸음수", "6,800", "보", "🚶")
        MetricItem("평균 수면", "7.1", "시간", "😴")
        MetricItem("기록한 날", "28", "/ 31일", "📅")
    }
}

@Composable
private fun DemoRiskItem(emoji: String, title: String, impact: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(emoji, fontSize = 20.sp)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Text(impact, fontSize = 11.sp, color = TextSecondary)
        }
    }
}

@Composable
private fun ScoreLineChart(scores: List<Int>, weekStart: java.time.LocalDate? = null, onDayClick: ((Int) -> Unit)? = null) {
    val currentOnDayClick by rememberUpdatedState(onDayClick)
    val max = 100f
    val days = listOf("월", "화", "수", "목", "금", "토", "일")
    val primaryArgb = Primary.toArgb()
    val secondaryArgb = TextSecondary.toArgb()
    val primaryDarkArgb = TextPrimary.toArgb()

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            .then(
                if (onDayClick != null) Modifier.pointerInput(Unit) {
                    detectTapGestures { offset: Offset ->
                        val sidePadPx = 12.dp.toPx()
                        val effN2 = if (scores.size < 2) 7 else scores.size
                        val stepXp = (size.width - sidePadPx * 2) / (effN2 - 1)
                        val idx = Math.round((offset.x - sidePadPx) / stepXp).coerceIn(0, effN2 - 1)
                        currentOnDayClick?.invoke(idx)
                    }
                } else Modifier
            )
    ) {
        // [반응형] 모든 px 상수를 dp.toPx()로 — 저/고밀도 기기에서 동일 비율 유지 (테스트 기기 density≈2.75 기준 N/2.75)
        // [피드백 260714] 점수 라벨을 데이터 점 '위'로 이동 — 곡선의 봉/골과 함께 이동해 그래프 모양이 그대로 읽힘.
        val topPad = 30.dp.toPx()       // 점 위 12dp에 얹는 점수 라벨 여유
        val bottomPad = 44.dp.toPx()    // 하단 2행(요일/날짜) — 점수행 폐지
        val sidePad = 12.dp.toPx()
        val w = size.width - sidePad * 2
        val h = size.height - topPad - bottomPad
        val n = scores.size

        // 격자 (0, 50, 100)
        listOf(0, 50, 100).forEach { line ->
            val y = topPad + h - (line / max) * h
            drawLine(
                color = Divider,
                start = Offset(sidePad, y),
                end = Offset(size.width - sidePad, y),
                strokeWidth = 1.dp.toPx()
            )
        }

        // Paint 정의 (n < 2 라도 요일/날짜는 그리기 위해 미리 선언)
        val scorePaint = android.graphics.Paint().apply {
            color = primaryArgb
            textSize = 44f / 2.75f * density   // [반응형] sp 대신 px → density로 스케일
            textAlign = android.graphics.Paint.Align.CENTER
            isFakeBoldText = true
            isAntiAlias = true
        }
        val dayPaint = android.graphics.Paint().apply {
            color = primaryDarkArgb
            textSize = 36f / 2.75f * density   // [반응형] sp 대신 px → density로 스케일
            textAlign = android.graphics.Paint.Align.CENTER
            isFakeBoldText = true
            isAntiAlias = true
        }
        val datePaint = android.graphics.Paint().apply {
            color = secondaryArgb
            textSize = 30f / 2.75f * density   // [반응형] sp 대신 px → density로 스케일
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }

        val effN = if (n < 2) 7 else n
        val stepX = w / (effN - 1)

        // 점이 적어도 요일/날짜는 7개 다 그리기
        if (n < 2) {
            for (i in 0 until 7) {
                val x = sidePad + i * stepX
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawText(
                        days.getOrElse(i) { "" },
                        x,
                        size.height - 22.dp.toPx(),   // 요일 (빈 주도 본 차트와 동일 간격)
                        dayPaint
                    )
                    weekStart?.plusDays(i.toLong())?.let { d ->
                        canvas.nativeCanvas.drawText(
                            "${d.monthValue}/${d.dayOfMonth}",
                            x,
                            size.height - 4.dp.toPx(),   // 날짜
                            datePaint
                        )
                    }
                }
            }
            return@Canvas
        }

        val points = scores.mapIndexed { i, s ->
            Offset(sidePad + i * stepX, topPad + h - (s.toFloat() / max) * h)
        }

        // 라인 — 연속한 non-zero 점들 사이만 연결 (0점이 끼면 라인 끊김)
        // [사용자 피드백] 점수 없는 날의 0이 그래프 하단에 점으로 찍히던 문제 해결.
        // [피드백 260714] 선 두께 얇게 (1.8 → 1.0dp)
        for (i in 0 until points.size - 1) {
            if (scores[i] > 0 && scores[i + 1] > 0) {
                drawLine(
                    color = Primary,
                    start = points[i],
                    end = points[i + 1],
                    strokeWidth = 1.0.dp.toPx()
                )
            }
        }

        points.forEachIndexed { i, p ->
            // 점수 있는 날만 점 + 점수 라벨 표시
            // [피드백 260714] 점 크기도 라인 두께에 맞춰 축소 (4.4 → 3.2dp)
            if (scores[i] > 0) {
                drawCircle(color = LimeAccent, radius = 3.2.dp.toPx(), center = p)
                drawCircle(color = Primary, radius = 3.2.dp.toPx(), center = p, style = Stroke(width = 1.0.dp.toPx()))
            }

            drawIntoCanvas { canvas ->
                // [피드백 260714] 점수 라벨을 점 '위'(12dp)에 얹어 곡선 모양과 함께 이동시킴.
                //   기존 하단 고정 행은 폐지 — 봉/골 구분 없이 같은 줄이라 그래프 모양이 안 보였음.
                if (scores[i] > 0) {
                    canvas.nativeCanvas.drawText(
                        scores[i].toString(),
                        p.x,
                        p.y - 12.dp.toPx(),
                        scorePaint
                    )
                }
                // 요일/날짜는 점수 유무와 무관하게 7일 다 표시
                canvas.nativeCanvas.drawText(
                    days.getOrElse(i) { "" },
                    p.x,
                    size.height - 22.dp.toPx(),   // 요일
                    dayPaint
                )
                weekStart?.plusDays(i.toLong())?.let { d ->
                    canvas.nativeCanvas.drawText(
                        "${d.monthValue}/${d.dayOfMonth}",
                        p.x,
                        size.height - 4.dp.toPx(),   // 날짜
                        datePaint
                    )
                }
            }
        }
    }
}

/**
 * 점수 변화 화살표 (당봄 #16).
 * 당봄 의도: 상승 = 🔺 빨강 / 하락 = 🔻 초록 / 변동 없음 = ━ 회색
 */
@Composable
private fun ScoreChangeArrow(
    current: Double,
    previous: Double,
    label: String = "지난",
) {
    val delta = current - previous
    val (arrow, color, text) = when {
        delta >= 3.0 -> Triple("▲", Color(0xFFE53935), "+${"%.1f".format(delta)}")
        delta <= -3.0 -> Triple("▼", Color(0xFF2E7D32), "${"%.1f".format(delta)}")
        else -> Triple("━", Color(0xFF9E9E9E), if (delta == 0.0) "변동 없음" else "${if (delta > 0) "+" else ""}${"%.1f".format(delta)}")
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.12f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(arrow, fontSize = 11.sp, color = color, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(2.dp))
            Text(text, fontSize = 11.sp, color = color, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * 마일스톤 배지 카드 (당봄 #12).
 * 주간 리포트 상단에 표시. 연속 달성·활동 증가·점수 신기록 등.
 */
@Composable
fun MilestoneBadgeCard(milestones: List<com.dangbomcare.app.data.api.MilestoneInfo>) {
    if (milestones.isEmpty()) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
        border = BorderStroke(1.dp, Color(0xFFFFB74D))
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🏅", fontSize = 22.sp)
                Spacer(Modifier.width(8.dp))
                Text("이번 주 달성 배지", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            }
            milestones.forEach { m ->
                Surface(
                    color = Color.White,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(m.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Spacer(Modifier.height(4.dp))
                        Text(m.message, fontSize = 12.sp, color = TextSecondary, lineHeight = 17.sp)
                    }
                }
            }
        }
    }
}

// [사용자 피드백] '개선 포인트'는 점수·미달 통계가 아니라 '문제점(+실제 미달 일수) → 개선법'으로.
//   백엔드 top_risk_factors 의 점수 reason 대신 factor + low_days(미달 일수)로 데이터 기반 문구.
//   줄바꿈: "문제\n→ 개선". (식후 산책은 이미 하는 사람에게 틀릴 수 있어 무조건 권유 X)
// [피드백 260714] 목표값을 실제 채점 기준과 정확히 맞춤(steps 8,000·sleep 7~9h·저녁 마감 시각 이전).
//   관찰일 대비 미달일(3/7일 형태)로 사용자가 얼마나 부족했는지 즉시 파악 가능하게.
// [260726] dinnerLabel — 하드코딩 '오후 7시' 제거. 사용자가 설정한 저녁 마감 시각을 그대로 노출한다
//   (당봄 제보: 7시 30분으로 설정했는데 안내 문구는 다른 시각을 말함).
private fun riskFactorAdvice(risk: RiskFactor, dinnerLabel: String): String? {
    val n = risk.lowDays ?: 0
    val obs = risk.observedDays ?: 0
    val days = when {
        n > 0 && obs > 0 -> "${n}일/${obs}일 "
        n > 0 -> "${n}일 "
        else -> ""
    }
    return when (risk.factor) {
        // [260707] glucose는 개선 포인트에서 제외(결과값). 렌더 단계에서 이미 필터되지만 방어적으로 null.
        "glucose" -> null
        "steps" -> "활동량이 부족한 날이 ${days}있었어요\n→ 하루 8,000보 이상 걷는 걸 목표로 해보세요."
        // [260728 고객피드백] 수면 배점이 차등(20~10점)으로 바뀌어 **과다수면일도** 위험요인으로 잡힌다.
        //   백엔드 top_risk_factors 는 방향(부족/과다)을 내려주지 않으므로(_build_top_risk_factors:
        //   factor/label/avg_score/low_days/observed_days 뿐) "부족한 날"로 단정하면
        //   12시간 잔 사용자에게 틀린 말이 된다 → 방향을 말하지 않는 표현으로.
        "sleep" -> "수면 시간이 적정(7~9시간)에서 벗어난 날이 ${days}있었어요\n→ 매일 7~9시간, 일정한 시각에 주무세요."
        "dinner_before_7pm" -> "저녁 식사가 늦은 날이 ${days}있었어요\n→ 저녁을 ${dinnerLabel} 전에 마쳐보세요."
        // [260728 고객 피드백 3] "음주를 줄이면 다음날 공복혈당이 안정돼요"는 음주가 혈당을
        //   올린다는 뜻으로 읽힌다. 실제로는 술을 드신 다음날 아침 공복혈당이 오히려 낮게
        //   나오는 경향이라 방향을 바로잡고 저혈당 주의로 안내한다(절주 권유는 그대로).
        "alcohol" -> "음주한 날이 ${days}있었어요\n→ 술을 드신 다음날 아침은 공복혈당이 평소보다 낮게 나올 수 있어요. 저혈당에 주의하시고 절주해보세요."
        // [260726] 금지어 '야식' 제거 — 시니어 환자는 '야간 금식'과 혼동 (백엔드 프롬프트와 동일 기준).
        "no_food_after_dinner" -> "저녁 식사 후 추가로 드신 날이 ${days}있었어요\n→ 저녁 식사 후 4시간 이상 아무것도 드시지 마세요."
        "stress" -> "스트레스가 높았던 날이 ${days}있었어요\n→ 가벼운 운동·휴식으로 풀어보세요."
        // [피드백 260714] 새 회고 3항목 조언 추가 — 3개 신 항목이 위험요인으로 잡힐 때 표시.
        "dinner_type" -> "저녁으로 외식·배달·육류가 많은 날이 ${days}있었어요\n→ 집밥 한식이나 간단식(샐러드·누룽지)으로 바꿔보세요."
        "digestion" -> "저녁 소화가 불편했던 날이 ${days}있었어요\n→ 식사량을 줄이거나 저녁 메뉴를 순한 것으로 바꿔보세요."
        "post_meal_exercise" -> "식후 걷기(10분 이상) 안 한 날이 ${days}있었어요\n→ 식후 10~15분 정도 가볍게 산책해보세요."
        else -> null
    }
}

/**
 * [QA fix] 리포트 조회 실패 시 — '신규 사용자' 카드로 위장하지 않고 명확한 에러 + 재시도.
 *   네트워크/세션 오류가 "데이터가 사라진 것"처럼 보이던 문제 방지.
 */
@Composable
private fun ReportErrorCard(onRetry: () -> Unit) {
    Spacer(Modifier.height(24.dp))
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Primary.copy(alpha = 0.08f))
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("⚠️", fontSize = 44.sp)
            Spacer(Modifier.height(12.dp))
            Text(
                "리포트를 불러오지 못했어요",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "인터넷 연결을 확인하고 다시 시도해주세요.\n그동안의 기록은 안전하게 저장되어 있어요.",
                fontSize = 13.sp,
                color = TextSecondary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                lineHeight = 19.sp
            )
            Spacer(Modifier.height(20.dp))
            Surface(
                onClick = onRetry,
                color = Primary,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    "다시 시도",
                    color = androidx.compose.ui.graphics.Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp)
                )
            }
        }
    }
}

/**
 * 신규 사용자 — 데이터 부족 시 리포트 빈 화면 대신 친근한 안내.
 * 가입 후 며칠인지에 따라 단계별 메시지.
 */
@Composable
private fun NewUserEmptyReportCard(isWeekly: Boolean, isPast: Boolean = false) {
    val reportType = if (isWeekly) "주간" else "월간"
    val periodWord = if (isWeekly) "주" else "달"

    // [QA fix] 가입경과일을 알 수 없어 "오늘 처음 설치" 등 단정 안 함 — 중립 안내.
    //   지난(과거) 기간이면 '기록하면 채워져요' 같은 미래형 권유 없이 사실만.
    val (emoji, title, message) = if (isPast) Triple(
        "📭",
        "이 ${periodWord}는 기록이 없어요",
        "이 ${periodWord}에는 입력된 기록이 없어 ${reportType} 리포트를 만들 수 없어요."
    ) else Triple(
        "📊",
        "이 ${periodWord}는 기록이 없어요",
        "입력된 기록이 없어서 ${reportType} 리포트를 만들 수 없어요. 매일 공복혈당과 전날 회고를 기록하면 리포트가 채워져요."
    )

    Spacer(Modifier.height(24.dp))

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Primary.copy(alpha = 0.08f))
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(emoji, fontSize = 48.sp)
            Spacer(Modifier.height(12.dp))
            Text(
                title,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Text(
                message,
                fontSize = 13.sp,
                color = TextSecondary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                lineHeight = 19.sp
            )

            // [QA fix] 지난(과거) 기간엔 '그동안 이렇게 해보세요' 권유 숨김 — 이미 지나서 의미 없음.
            if (!isPast) {
                Spacer(Modifier.height(20.dp))
                Surface(
                    color = BgCard,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            "💡 그동안 이렇게 해보세요",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Primary
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "• 매일 아침 공복혈당 입력하기\n" +
                            "• 워치를 잠잘 때도 착용하기\n" +
                            "• 전날 회고 (저녁식사 시각, 식후 금식, 음주) 입력하기\n" +
                            "• 식후 10분 산책으로 혈당 안정",
                            fontSize = 12.sp,
                            color = TextPrimary,
                            lineHeight = 19.sp
                        )
                    }
                }
            }
        }
    }
}
