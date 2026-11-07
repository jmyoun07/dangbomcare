@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.dangbomcare.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dangbomcare.app.data.api.DailySummaryResponse
import com.dangbomcare.app.data.api.SummaryActivity
import com.dangbomcare.app.ui.components.LoadingIndicator
import com.dangbomcare.app.ui.theme.*
import com.dangbomcare.app.ui.viewmodel.DailySummaryViewModel

// ============================================================
// [기간 정렬] 7일 = 월~일(주간 리포트와 동일 기준) / 31일 = 그 달 1일~말일.
//   상세 화면들이 anchor(endDate) 기준 "오늘−N일" 롤링을 쓰던 걸 캘린더 주/월에 정렬한다.
//   isMonth = period.days > 7  (DAYS_31 → 달, DAYS_7 → 주). DAY_1은 차트 미사용.
// ============================================================
internal fun periodIsMonth(periodDays: Int): Boolean = periodDays > 7

internal fun periodStartOf(anchor: java.time.LocalDate, isMonth: Boolean): java.time.LocalDate =
    if (isMonth) anchor.withDayOfMonth(1)
    else anchor.with(java.time.DayOfWeek.MONDAY)

internal fun periodEndOf(anchor: java.time.LocalDate, isMonth: Boolean): java.time.LocalDate =
    if (isMonth) anchor.withDayOfMonth(anchor.lengthOfMonth())
    else anchor.with(java.time.DayOfWeek.MONDAY).plusDays(6)

internal fun periodDatesOf(anchor: java.time.LocalDate, isMonth: Boolean): List<java.time.LocalDate> {
    val start = periodStartOf(anchor, isMonth)
    val n = java.time.temporal.ChronoUnit.DAYS.between(start, periodEndOf(anchor, isMonth)).toInt() + 1
    return (0 until n).map { start.plusDays(it.toLong()) }
}

internal fun periodPrevOf(anchor: java.time.LocalDate, isMonth: Boolean): java.time.LocalDate =
    if (isMonth) anchor.minusMonths(1) else anchor.minusWeeks(1)

internal fun periodNextOf(anchor: java.time.LocalDate, isMonth: Boolean): java.time.LocalDate =
    if (isMonth) anchor.plusMonths(1) else anchor.plusWeeks(1)

// ==============================================
// 활동량 상세 (9 / 14) — 삼성헬스 스타일 (7일/31일/12개월 탭 + 막대그래프)
// ==============================================
private enum class ActivityPeriod(val days: Int, val label: String) {
    DAY_1(1, "일일"),
    DAYS_7(7, "7일"),
    DAYS_31(31, "31일")
}

@Composable
fun DetailActivityScreen(
    onBack: () -> Unit,
    initialDate: String? = null,  // ⭐ 외부에서 날짜 전달 받음
    initialPeriod: String? = null,  // ⭐ "7d" / "31d" → 진입 시 모드 (사용자 피드백: 리포트→7일 그래프)
    vm: DailySummaryViewModel = viewModel()
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // 일일 모드 기본. initialPeriod 가 있으면 그 모드로 시작 (리포트→7일/31일 그래프 직행).
    var selectedPeriod by remember {
        mutableStateOf(
            when (initialPeriod) {
                "7d" -> ActivityPeriod.DAYS_7
                "31d" -> ActivityPeriod.DAYS_31
                else -> ActivityPeriod.DAY_1
            }
        )
    }
    // 7/31일 → 일일 점프 시 이전 period 기억 → 뒤로가기 시 복귀 (코덱스 P0 fix)
    var returnPeriod by remember { mutableStateOf<ActivityPeriod?>(null) }
    // 선택된 날짜 (일일 모드용) — 외부 전달값 우선, 없으면 오늘
    var selectedDate by remember {
        mutableStateOf(
            initialDate?.let {
                try { java.time.LocalDate.parse(it) } catch (e: Exception) { java.time.LocalDate.now() }
            } ?: java.time.LocalDate.now()
        )
    }
    var stepsByDay by remember { mutableStateOf<Map<java.time.LocalDate, Int>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var sdkAvailable by remember { mutableStateOf(true) }
    // 코덱스 B-완성: endDate 부모 hoist — 좌우 화살표 시 실제 과거 fetch
    val today = remember { java.time.LocalDate.now() }
    // [v1.0 피드백] initialDate가 있으면 그 날짜를 endDate로 사용 (그 주/달의 데이터 표시)
    var activityEndDate by remember {
        mutableStateOf(
            initialDate?.let { try { java.time.LocalDate.parse(it) } catch (_: Exception) { today } } ?: today
        )
    }

    // [260726] 일일 모드 워치 데이터 — 요약 카드와 시간대 카드가 각각 fetchWatchData(같은 날짜)를 부르고
    //   Repository 인스턴스도 따로 만들던 중복 제거. 상위에서 1회만 조회해 두 카드에 값으로 내려준다.
    //   (fetchWatchData 1회 = HC 왕복 여러 번 → 화면 진입/날짜 이동마다 절반이 낭비였음)
    var dayWatch by remember { mutableStateOf<com.dangbomcare.app.data.api.WatchDataRequest?>(null) }
    var dayLoading by remember { mutableStateOf(true) }

    LaunchedEffect(selectedPeriod, activityEndDate) {
        if (selectedPeriod == ActivityPeriod.DAY_1) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        try {
            val repo = com.dangbomcare.app.data.health.HealthConnectRepository(context)
            if (!repo.isSdkAvailable()) {
                sdkAvailable = false
            } else {
                val im = periodIsMonth(selectedPeriod.days)
                stepsByDay = repo.fetchStepsByDay(periodStartOf(activityEndDate, im), periodEndOf(activityEndDate, im))
            }
        } catch (e: Exception) {
            // 코덱스 H 보강: fetch 실패 시 이전 range 데이터가 남지 않도록 비움
            stepsByDay = emptyMap()
        }
        loading = false
    }

    // [260726] 일일 모드 단일 조회.
    //   ① 두 하위 카드의 fetchWatchData 중복 호출 제거 (같은 날짜 2회 → 1회)
    //   ② 이전엔 위 effect가 DAY_1에서 즉시 return 해서 isSdkAvailable() 검사를 아예 안 탔고,
    //      그래서 HC 미설치여도 상위 !sdkAvailable 안내 분기가 절대 표시되지 않았다 → 여기서 검사한다.
    LaunchedEffect(selectedPeriod, selectedDate) {
        if (selectedPeriod != ActivityPeriod.DAY_1) return@LaunchedEffect
        dayLoading = true
        dayWatch = null
        try {
            val repo = com.dangbomcare.app.data.health.HealthConnectRepository(context)
            if (!repo.isSdkAvailable()) {
                sdkAvailable = false
            } else {
                dayWatch = repo.fetchWatchData(selectedDate)
            }
        } catch (e: Exception) {
            // 권한 없음·조회 실패 → null 유지. 하위 카드가 "동기화 필요"로 구분 표기한다(0보로 안 보임).
            dayWatch = null
        }
        dayLoading = false
    }

    // 일일 모드에서 뒤로가기 누르면 이전 period(7일/31일) 로 복귀
    fun handleBack() {
        val prev = returnPeriod
        if (selectedPeriod == ActivityPeriod.DAY_1 && prev != null) {
            selectedPeriod = prev
            returnPeriod = null
        } else onBack()
    }
    BackHandler(enabled = selectedPeriod == ActivityPeriod.DAY_1 && returnPeriod != null) {
        handleBack()
    }

    Scaffold(
        containerColor = BgSecondary,
        topBar = { DetailTopBar(title = "🚶 걸음 활동", onBack = { handleBack() }) }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // 기간 선택 탭 (7일 / 31일 / 12개월)
            PeriodTabs(selected = selectedPeriod, onSelect = { p ->
                // 다른 기간 직접 선택 시 endDate를 오늘로 reset (코덱스 B-완성)
                // BackHandler 복귀(returnPeriod 경로)에서는 reset 안 함 — 보던 과거 range 유지
                if (p != selectedPeriod && p != ActivityPeriod.DAY_1) {
                    activityEndDate = today
                }
                selectedPeriod = p
                if (p != ActivityPeriod.DAY_1) returnPeriod = null  // 7/31일 직접 선택 시 returnPeriod 초기화
            })

            if (loading) {
                LoadingIndicator(modifier = Modifier.height(200.dp))
            } else if (!sdkAvailable) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = LimeLight.copy(alpha = 0.4f))
                ) {
                    Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("⌚", fontSize = 36.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("Health Connect가 설치돼 있지 않아요", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = PrimaryDark)
                    }
                }
            } else {
                when (selectedPeriod) {
                    ActivityPeriod.DAY_1 -> {
                        // 일일 모드 — 날짜 선택 화살표 + 선택 날짜 데이터
                        DateNavigatorBar(
                            date = selectedDate,
                            onChange = { selectedDate = it }
                        )
                        // [260726] 상위에서 1회 조회한 값을 그대로 전달 (카드별 중복 조회 제거)
                        ActivityDaySummaryCard(steps = dayWatch?.steps, loading = dayLoading)
                        ActivityDayHourlyCard(
                            date = selectedDate,
                            hourlyData = dayWatch?.stepsHourly,
                            loading = dayLoading
                        )
                    }
                    else -> {
                        // 사용자 피드백: HC 진단 박스 + 식후 보너스 박스 삭제 (보기 안 좋음)
                        ActivityStatsCard(
                            stepsByDay = stepsByDay,
                            period = selectedPeriod,
                            endDate = activityEndDate
                        )
                        ActivityBarChartCard(
                            stepsByDay = stepsByDay,
                            period = selectedPeriod,
                            endDate = activityEndDate,
                            onEndDateChange = { activityEndDate = it },
                            onDayClick = { date ->
                                // 막대 터치 후 날짜 칩 클릭 → 일일 모드로 점프 (뒤로가기로 복귀)
                                returnPeriod = selectedPeriod
                                selectedDate = date
                                selectedPeriod = ActivityPeriod.DAY_1
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun PeriodTabs(selected: ActivityPeriod, onSelect: (ActivityPeriod) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BgCardSoft)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        ActivityPeriod.values().forEach { p ->
            val isSel = p == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSel) Primary else Color.Transparent)
                    .clickable { onSelect(p) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    p.label,
                    fontSize = 14.sp,
                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.SemiBold,
                    color = if (isSel) Color.White else TextSecondary
                )
            }
        }
    }
}

@Composable
private fun ActivityStatsCard(
    stepsByDay: Map<java.time.LocalDate, Int>,
    period: ActivityPeriod,
    endDate: java.time.LocalDate = java.time.LocalDate.now()
) {
    // [기간 정렬] 7일=월~일 / 31일=그 달 1일~말일
    val im = periodIsMonth(period.days)
    val from = periodStartOf(endDate, im)
    val to = periodEndOf(endDate, im)
    val winLen = (to.toEpochDay() - from.toEpochDay()).toInt() + 1
    val values = stepsByDay.values
    val total = values.sum()
    val daysWithData = values.count { it > 0 }
    val avg = if (daysWithData > 0) total / daysWithData else 0
    val goalReachedDays = values.count { it >= 8000 }
    val goal = 8000

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        border = BorderStroke(1.dp, Divider)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            // 기간 표시
            Text(
                "${from.monthValue}월 ${from.dayOfMonth}일 ~ ${to.monthValue}월 ${to.dayOfMonth}일",
                fontSize = 12.sp,
                color = TextSecondary
            )
            Spacer(Modifier.height(8.dp))
            // 평균 걸음수 (큰 글씨)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "%,d".format(avg),
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Black,
                    color = TextPrimary
                )
                Spacer(Modifier.width(6.dp))
                Text("걸음/일", fontSize = 16.sp, color = TextSecondary, modifier = Modifier.padding(bottom = 6.dp))
            }
            Spacer(Modifier.height(12.dp))
            // 거리/칼로리/층 — 간이 추정 (4839보 ≈ 3.82km ≈ 159kcal 기준)
            val estKm = avg * 0.00079
            val estKcal = (avg * 0.033).toInt()
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatItem("%.2f km".format(estKm), "거리")
                Box(modifier = Modifier.width(1.dp).height(36.dp).background(Divider))
                StatItem("$estKcal kcal", "칼로리")
                Box(modifier = Modifier.width(1.dp).height(36.dp).background(Divider))
                StatItem("$daysWithData/${winLen}일", "기록")
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = Divider)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("총 걸음", fontSize = 13.sp, color = TextSecondary, modifier = Modifier.weight(1f))
                Text("%,d 보".format(total), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("목표 ${goal}보 달성", fontSize = 13.sp, color = TextSecondary, modifier = Modifier.weight(1f))
                Text("$goalReachedDays/${winLen}일", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Primary)
            }
        }
    }
}

@Composable
private fun StatItem(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        Text(label, fontSize = 11.sp, color = TextSecondary)
    }
}

/**
 * 삼성헬스 스타일 걸음 막대 그래프 (7일 / 31일)
 * - 주간/월간 좌우 화살표 네비게이션
 * - Y축 우측 점선: 11k / 8k(녹색 목표) / 평균 / 4k
 * - 막대 터치 → 캡슐 툴팁 (날짜 + 걸음수 + ">" 아이콘)
 * - 툴팁 클릭 → 일일 모드로 점프 (onDayClick)
 */
@Composable
private fun ActivityBarChartCard(
    stepsByDay: Map<java.time.LocalDate, Int>,
    period: ActivityPeriod,
    endDate: java.time.LocalDate,
    onEndDateChange: (java.time.LocalDate) -> Unit,
    onDayClick: (java.time.LocalDate) -> Unit = {}
) {
    val today = java.time.LocalDate.now()
    val days = periodDatesOf(endDate, periodIsMonth(period.days))
    val values = days.map { stepsByDay[it] ?: 0 }
    val nonZeroValues = values.filter { it > 0 }
    val avg = if (nonZeroValues.isNotEmpty()) nonZeroValues.average().toInt() else 0
    val totalSteps = values.sum()
    val goal = 8000
    // [260707] Y축 동적 스케일 — 실제 최댓값을 4,000 단위로 올림(항상 헤드룸 확보), 최소 8,000(목표선 표시).
    //   16,000보 같은 날도 막대가 천장을 안 뚫도록. 눈금선도 이 값 기준 4,000 단위로 생성.
    val maxVal = maxOf(8000, ((values.maxOrNull() ?: 0) / 4000 + 1) * 4000)
    val maxBarHeight = 180.dp

    // 선택된 막대 인덱스 (터치 시 툴팁)
    var selectedIdx by remember(period, endDate) { mutableStateOf<Int?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        border = BorderStroke(1.dp, Divider)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            // ── 헤더: 주간/월간 좌우 화살표 + 기간 라벨 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    onEndDateChange(periodPrevOf(endDate, periodIsMonth(period.days)))
                    selectedIdx = null
                }) {
                    Icon(Icons.Filled.ChevronLeft, contentDescription = "이전 기간", tint = Primary)
                }
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    val rangeLabel = "${days.first().monthValue}월 ${days.first().dayOfMonth}일 - ${days.last().monthValue}월 ${days.last().dayOfMonth}일"
                    com.dangbomcare.app.ui.components.AutoSizeSingleLineText(rangeLabel, maxFontSize = 13.sp, minFontSize = 10.sp, color = TextSecondary)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${"%,d".format(avg)} 걸음/일",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }
                IconButton(
                    onClick = {
                        if (periodEndOf(endDate, periodIsMonth(period.days)) < periodEndOf(today, periodIsMonth(period.days))) {
                            onEndDateChange(periodNextOf(endDate, periodIsMonth(period.days)))
                            selectedIdx = null
                        }
                    },
                    enabled = periodEndOf(endDate, periodIsMonth(period.days)) < periodEndOf(today, periodIsMonth(period.days))
                ) {
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = "다음 기간",
                        tint = if (periodEndOf(endDate, periodIsMonth(period.days)) < periodEndOf(today, periodIsMonth(period.days))) Primary else TextDisabled
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            // 통계 한 줄 — 평균은 위 헤더 "X 걸음/일"이 이미 표시하므로 여기선 총/목표만 (중복 제거)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Text("총 ${"%,d".format(totalSteps)}보", fontSize = 12.sp, color = TextSecondary)
                Text("목표 ${values.count { it >= goal }}/${days.size}일", fontSize = 12.sp, color = TextSecondary)
            }
            Spacer(Modifier.height(12.dp))

            // 코덱스 H: 접근성 요약 (스크린 리더용)
            val maxIdx = values.indices.maxByOrNull { values[it] }
            val chartA11ySummary = buildString {
                append("걸음 차트. ")
                append("${days.first().a11yDate()}부터 ${days.last().a11yDate()}까지. ")
                append("일평균 ${stepsA11y(avg)}, 총 ${stepsA11y(totalSteps)}. ")
                append("목표 8천 걸음 달성 ${values.count { it >= goal }}일. ")
                if (maxIdx != null && values[maxIdx] > 0) {
                    append("가장 많이 걸은 날은 ${days[maxIdx].a11yDate()}, ${stepsA11y(values[maxIdx])}.")
                }
            }

            // ── 차트 본체 (Y축 점선 + 막대) ──
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(maxBarHeight + 40.dp)
                    .semantics { contentDescription = chartA11ySummary }
            ) {
                val chartW = maxWidth - 48.dp
                val chartH = maxBarHeight
                // [260707] Y축 눈금 — maxVal까지 4,000 단위로 동적 생성. 목표선(8,000)만 강조, 0은 밑변.
                val gridLines = (maxVal / 4000 downTo 0).map { mult ->
                    val v = mult * 4000
                    val color = when (v) {
                        8000 -> Primary
                        0 -> Divider
                        else -> TextSecondary
                    }
                    Triple(v, "%,d".format(v), color)
                }
                // [v1.0 피드백] 점선 + 우측 숫자를 같은 Row로 묶어 수직 정렬 보장.
                //   이전엔 점선(좌 Box)/숫자(우 Box) 분리 → offset 계산 차이로 어긋남.
                //   평균선도 제거 — 4000/8000과 겹쳐서 헤더 "평균 X보" 텍스트로 대체.
                val labelW = 48.dp
                Box(modifier = Modifier.fillMaxWidth().height(chartH)) {
                    // 기준선 점선 + 우측 숫자 (한 Row, CenterVertically → 항상 정렬)
                    gridLines.forEach { (yVal, label, color) ->
                        val yFromTop = chartH.value * (1f - yVal.toFloat() / maxVal)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .offset(y = (yFromTop - 7f).coerceIn(0f, chartH.value - 14f).dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(modifier = Modifier.weight(1f)) {
                                HorizontalDashedLine(
                                    color = color.copy(alpha = 0.35f),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            Text(
                                label,
                                fontSize = 10.sp,
                                color = color,
                                fontWeight = if (yVal == 8000) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.width(labelW).padding(start = 6.dp)
                            )
                        }
                    }
                    // 막대들 — 오른쪽 숫자 영역(labelW) 제외. 컨테이너에서 tap+좌우 드래그로 선택 (일일/스트레스 차트와 통일).
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(end = labelW)
                            .pointerInput(values.size) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val w = size.width.toFloat().coerceAtLeast(1f)
                                    fun idxFromX(x: Float): Int =
                                        (x.coerceIn(0f, w) / w * values.size).toInt().coerceIn(0, values.size - 1)
                                    selectedIdx = idxFromX(down.position.x)
                                    drag(down.id) { change ->
                                        selectedIdx = idxFromX(change.position.x)
                                        change.consume()
                                    }
                                }
                            }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = if (period.days <= 7) Arrangement.spacedBy(6.dp) else Arrangement.spacedBy(1.dp),
                            verticalAlignment = Alignment.Bottom
                        ) {
                            values.forEachIndexed { i, v ->
                                val ratio = if (maxVal > 0) v.toFloat() / maxVal else 0f
                                val reached = v >= goal
                                val isSelected = selectedIdx == i
                                val barA11y =
                                    "${days[i].a11yDate()}, ${stepsA11y(v)}" +
                                    if (v >= goal) ", 목표 달성" else ", 목표 미달"
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        // [v1.0 피드백] 선택 하이라이트 = 셀 자체 배경(한 겹, 막대와 정확히 정렬).
                                        //   이전엔 별도 Canvas 띠가 spacedBy 간격 때문에 막대와 어긋나 "2겹"으로 보였음.
                                        .then(
                                            if (isSelected) Modifier.background(
                                                Slate.copy(alpha = 0.15f),
                                                RoundedCornerShape(4.dp)
                                            ) else Modifier
                                        )
                                        .semantics {
                                            contentDescription = barA11y
                                            stateDescription = if (isSelected) "선택됨" else "선택 안 됨"
                                        },
                                    contentAlignment = Alignment.BottomCenter
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(if (period.days <= 7) 0.7f else 0.9f)
                                            .height((chartH.value * ratio).coerceAtLeast(2f).dp)
                                            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 6.dp, bottomEnd = 6.dp))
                                            .background(if (reached) Primary else Primary.copy(alpha = 0.28f))
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            // X축 라벨 — 균등 간격 틱(겹침/잘림 방지). 31일은 6개 균등, 월 바뀔 때만 'M/d'. 7일은 전부.
            run {
                val tickIdx = if (period == ActivityPeriod.DAYS_31)
                    (0..5).map { it * days.lastIndex / 5 }.distinct()
                else days.indices.toList()
                Row(modifier = Modifier.fillMaxWidth().padding(end = 48.dp)) {
                    var prevMonth = -1
                    tickIdx.forEach { idx ->
                        val d = days[idx]
                        val isSel = selectedIdx == idx
                        val label = if (d.monthValue != prevMonth) "${d.monthValue}/${d.dayOfMonth}" else "${d.dayOfMonth}"
                        prevMonth = d.monthValue
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            Text(
                                label,
                                fontSize = 10.sp,
                                maxLines = 1,
                                softWrap = false,
                                color = if (isSel) Primary else TextSecondary,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }

            // ── 선택된 막대 툴팁 + 일일 점프 ──
            val sel = selectedIdx
            if (sel != null) {
                val selDate = days[sel]
                val selValue = values[sel]
                Spacer(Modifier.height(12.dp))
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onDayClick(selDate) }
                        .semantics {
                            // 코덱스 H: TalkBack 가 선택 변경을 자동 발화하도록
                            liveRegion = LiveRegionMode.Polite
                            contentDescription =
                                "${selDate.a11yDate()}, ${stepsA11y(selValue)}. 두 번 누르면 일일 보기로 이동합니다."
                        },
                    shape = RoundedCornerShape(12.dp),
                    color = Primary.copy(alpha = 0.12f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "${selDate.monthValue}월 ${selDate.dayOfMonth}일 (${selDate.dayOfWeek.koreanLabel()})",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "${"%,d".format(selValue)}보",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (selValue >= goal) Primary else TextPrimary
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("일일 보기", fontSize = 11.sp, color = Primary, fontWeight = FontWeight.SemiBold)
                            Icon(
                                Icons.Filled.ChevronRight,
                                contentDescription = null,
                                tint = Primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 가로 점선 (Y축 그리드용). 짧은 dash + 작은 gap. */
@Composable
private fun HorizontalDashedLine(
    color: Color,
    modifier: Modifier = Modifier
) {
    androidx.compose.foundation.Canvas(modifier = modifier.height(1.dp)) {
        val dashWidth = 6f
        val gapWidth = 4f
        var x = 0f
        while (x < size.width) {
            drawLine(
                color = color,
                start = androidx.compose.ui.geometry.Offset(x, 0f),
                end = androidx.compose.ui.geometry.Offset((x + dashWidth).coerceAtMost(size.width), 0f),
                strokeWidth = size.height
            )
            x += dashWidth + gapWidth
        }
    }
}

@Composable
private fun ActivityHourlyCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var hourlySteps by remember { mutableStateOf<List<Int>>(List(24) { 0 }) }
    var loading by remember { mutableStateOf(true) }
    var targetDate by remember { mutableStateOf(java.time.LocalDate.now().minusDays(1)) }

    LaunchedEffect(targetDate) {
        loading = true
        try {
            val repo = com.dangbomcare.app.data.health.HealthConnectRepository(context)
            val data = repo.fetchWatchData(targetDate)
            hourlySteps = data.stepsHourly ?: List(24) { 0 }
        } catch (e: Exception) { /* keep zero */ }
        loading = false
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        border = BorderStroke(1.dp, Divider)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("전날 시간별 걸음", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary, modifier = Modifier.weight(1f))
                Text(
                    "${targetDate.monthValue}/${targetDate.dayOfMonth}",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }
            Spacer(Modifier.height(12.dp))
            val maxHour = (hourlySteps.maxOrNull() ?: 1).coerceAtLeast(1)
            val total = hourlySteps.sum()
            Box(modifier = Modifier.fillMaxWidth().height(120.dp)) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(1.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    hourlySteps.forEachIndexed { hour, count ->
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
            Spacer(Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("0시", fontSize = 9.sp, color = TextSecondary)
                Text("6시", fontSize = 9.sp, color = TextSecondary)
                Text("12시", fontSize = 9.sp, color = TextSecondary)
                Text("18시", fontSize = 9.sp, color = TextSecondary)
                Text("23시", fontSize = 9.sp, color = TextSecondary)
            }
            Spacer(Modifier.height(8.dp))
            Text("총 ${"%,d".format(total)}보", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextSecondary)
        }
    }
}

@Composable
private fun ActivityMainCard(activity: SummaryActivity?) {
    val steps = activity?.steps ?: 0
    val goal = 8000
    val ratio = (steps.toFloat() / goal).coerceIn(0f, 1f)

    // 점수 유도 문구 — 다음 구간까지 얼마 남았는지 (15점 만점 base)
    // 0 → 2000 (5점) → 4000 (9점) → 6000 (12점) → 8000 (15점)
    val (nextThreshold, scoreUp) = when {
        steps < 2000 -> (2000 - steps) to 5
        steps < 4000 -> (4000 - steps) to 4
        steps < 6000 -> (6000 - steps) to 3
        steps < 8000 -> (8000 - steps) to 3
        else -> null to 0
    }
    val nudgeText = nextThreshold?.let { remaining ->
        "조금만 더 ${"%,d".format(remaining)}보 걸으면 점수 +${scoreUp}점 ↑"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        border = BorderStroke(1.dp, Divider)
    ) {
        Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("전날 걸음 수", fontSize = 14.sp, color = TextSecondary)
            Spacer(Modifier.height(12.dp))
            Box(contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.size(180.dp)) {
                    val stroke = 14.dp.toPx()
                    val pad = stroke / 2
                    drawArc(
                        color = Color(0xFFE4E2E1), startAngle = -90f, sweepAngle = 360f, useCenter = false,
                        topLeft = androidx.compose.ui.geometry.Offset(pad, pad),
                        size = androidx.compose.ui.geometry.Size(this.size.width - stroke, this.size.height - stroke),
                        style = Stroke(width = stroke, cap = StrokeCap.Round)
                    )
                    drawArc(
                        color = Primary, startAngle = -90f, sweepAngle = ratio * 360f, useCenter = false,
                        topLeft = androidx.compose.ui.geometry.Offset(pad, pad),
                        size = androidx.compose.ui.geometry.Size(this.size.width - stroke, this.size.height - stroke),
                        style = Stroke(width = stroke, cap = StrokeCap.Round)
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🚶", fontSize = 28.sp)
                    Text("$steps", fontSize = 36.sp, fontWeight = FontWeight.Black, color = Primary)
                    Text("/ ${goal}보", fontSize = 12.sp, color = TextSecondary)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("${(ratio * 100).toInt()}% 달성", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Primary)
            // 점수 유도 문구 (당봄 피드백 #7)
            nudgeText?.let {
                Spacer(Modifier.height(10.dp))
                Surface(
                    color = LimeLight.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        "💡 $it",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = PrimaryDark,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun Activity7DayCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        border = BorderStroke(1.dp, Divider)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("7일 추이", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth().height(120.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                val days = listOf("월", "화", "수", "목", "금", "토", "오늘")
                val heights = listOf(0.55f, 0.7f, 0.45f, 0.85f, 0.65f, 0.5f, 0.9f)
                days.forEachIndexed { i, d ->
                    Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier.fillMaxWidth().height((100 * heights[i]).dp)
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                .background(if (i == 6) Primary else PrimaryLight.copy(alpha = 0.5f))
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(d, fontSize = 11.sp, color = if (i == 6) Primary else TextSecondary, fontWeight = if (i == 6) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }
    }
}

@Composable
private fun PostMealBonusCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = LimeLight.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🍽️", fontSize = 22.sp)
                Spacer(Modifier.width(8.dp))
                Text("식후 걷기 보너스", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = PrimaryDark)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "식사 후 30분~2시간 사이 200보 이상 걸으면 +1.67점 가산 (최대 +5점)",
                fontSize = 13.sp,
                color = TextPrimary,
                lineHeight = 20.sp
            )
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MealBonusItem("아침", "+1.67", true)
                MealBonusItem("점심", "+1.67", true)
                MealBonusItem("저녁", "0", false)
            }
        }
    }
}

@Composable
private fun MealBonusItem(meal: String, points: String, achieved: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(meal, fontSize = 12.sp, color = TextSecondary)
        Spacer(Modifier.height(4.dp))
        Surface(
            color = if (achieved) Primary else BgCardSoft,
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                points,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = if (achieved) Color.White else TextSecondary,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun SyncStatusCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lastSyncMs = remember { com.dangbomcare.app.data.health.WatchSyncTracker.lastSyncMs(context) }
    val now = System.currentTimeMillis()
    val diffMin = if (lastSyncMs != null) (now - lastSyncMs) / 60_000 else null
    val isStale = diffMin != null && diffMin >= 30
    val statusColor = when {
        diffMin == null -> TextSecondary
        diffMin < 30 -> Success
        diffMin < 120 -> Warning
        else -> ErrorRed
    }
    var showDiag by remember { mutableStateOf(false) }

    if (showDiag) {
        WatchSyncDiagnosticDialog(onDismiss = { showDiag = false })
    }

    Card(
        modifier = Modifier.fillMaxWidth().clickable { showDiag = true },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isStale) Warning.copy(alpha = 0.08f) else BgCard
        ),
        border = BorderStroke(1.dp, if (isStale) Warning.copy(alpha = 0.4f) else Divider)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(statusColor))
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (isStale) "삼성헬스 동기화: 업데이트 필요" else "삼성헬스 동기화: 정상",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Text(
                    com.dangbomcare.app.data.health.WatchSyncTracker.formatLastSync(context),
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }
            if (isStale) {
                Text("진단 보기 →", fontSize = 11.sp, color = Warning, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * 워치 동기화 자동 진단 다이얼로그 (당봄 #12).
 * 30분 이상 지연 시 또는 사용자가 직접 클릭 시 노출.
 *
 * 점검 항목:
 * - Health Connect 권한
 * - 마지막 워치 데이터 도착 시간
 * - 백엔드 통신 상태
 *
 * 진단 후 사용자가 따라할 수 있는 단계별 해결 방법 제시.
 */
@Composable
private fun WatchSyncDiagnosticDialog(onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var hcStatus by remember { mutableStateOf<Boolean?>(null) }
    var permStatus by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(Unit) {
        try {
            val repo = com.dangbomcare.app.data.health.HealthConnectRepository(context)
            hcStatus = repo.isSdkAvailable()
            if (hcStatus == true) {
                permStatus = repo.checkPermissions().all
            }
        } catch (e: Exception) {
            hcStatus = false
        }
    }

    val lastMs = com.dangbomcare.app.data.health.WatchSyncTracker.lastSyncMs(context)
    val staleMin = if (lastMs != null) (System.currentTimeMillis() - lastMs) / 60_000 else null
    val syncStatus: Boolean? = when {
        lastMs == null -> null
        staleMin != null && staleMin < 30 -> true
        else -> false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Text("🔍", fontSize = 36.sp) },
        title = { Text("삼성헬스 동기화 진단", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                DiagRowItem("Health Connect 설치", hcStatus)
                DiagRowItem("Health Connect 권한 허용", permStatus)
                DiagRowItem("최근 30분 내 동기화", syncStatus)
                Spacer(Modifier.height(12.dp))
                Surface(
                    color = BgCardSoft,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("💡 해결 방법", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "1. 삼성헬스 앱을 한 번 열어주세요\n" +
                            "2. 워치와 폰이 10m 이내에 있는지 확인\n" +
                            "3. 워치 배터리가 충분한지 확인\n" +
                            "4. 위에 ❌ 항목이 있으면 [설정 → 삼성헬스 동기화]에서 다시 허용",
                            fontSize = 11.sp,
                            color = TextSecondary,
                            lineHeight = 17.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("확인", color = Primary, fontWeight = FontWeight.Bold)
            }
        },
        shape = RoundedCornerShape(20.dp)
    )
}

@Composable
private fun DiagRowItem(label: String, status: Boolean?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            when (status) {
                true -> "✅"
                false -> "❌"
                null -> "⏳"
            },
            fontSize = 16.sp
        )
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 13.sp, color = TextPrimary, modifier = Modifier.weight(1f))
        Text(
            when (status) {
                true -> "정상"
                false -> "확인 필요"
                null -> "확인 중..."
            },
            fontSize = 11.sp,
            color = when (status) {
                true -> Success
                false -> ErrorRed
                null -> TextSecondary
            },
            fontWeight = FontWeight.SemiBold
        )
    }
}

// ==============================================
// 수면 상세 (10 / 14) — 시니어 용어 + 삼성헬스 스타일 (7/31/12개월 + stacked 막대)
// ==============================================
private enum class SleepPeriod(val days: Int, val label: String) {
    DAY_1(1, "일일"),
    DAYS_7(7, "7일"),
    DAYS_31(31, "31일")
}

// 수면 단계 색상 — [260706 R8] 사용자 제공 삼성헬스 레퍼런스 스샷에서 실측한 팔레트 (1:1 재현 목표)
val SleepDeepColor = Color(0xFF232F9E)       // 깊은 수면 — 진남
val SleepRemColor = Color(0xFF5BB7F7)        // 렘 수면 — 하늘
val SleepLightColor = Color(0xFF6B7CE8)      // 얕은 수면 — 청보라 (가장 넓은 밴드)
val SleepAwakeColor = Color(0xFFFF7A3D)      // 수면 중 깸 — 주황

// [260729] 단계 미기록 수면 (Health Connect STAGE_TYPE_SLEEPING=2).
//   "잠은 잤다"만 알고 어느 단계인지 기기가 안 알려준 시간. 총 수면시간에는 포함되지만
//   deep/light/rem/awake 어디에도 안 들어간다 → 4단계 합 < totalMinutes 가 되는 유일한 정상 경로.
//   ⚠️ 색은 4단계(진남/청보라/하늘/주황) 어느 것도 재사용하면 안 된다. '단계를 모른다'가 한눈에
//      읽히도록 채도 없는 중립 회색. 진한 편으로 잡은 이유는 이 색이 막대뿐 아니라 분포 행의
//      "%" 글자색으로도 쓰여서 — 옅은 회색이면 시니어에게 안 보인다(대비 4.3:1).
//   ※ 삼성 SDK 경로엔 이 상수가 없어 항상 0분 → 대부분 사용자 화면엔 아예 나타나지 않는다.
val SleepUnstagedColor = Color(0xFF6E7A8C)   // 단계 미기록 — 중립 회색

/**
 * 단계 미기록 수면의 노출 라벨. 시니어 기준으로 "미분류/unstaged" 같은 용어는 안 쓴다.
 * ⚠️ 상수로 묶은 이유: 미니행·분포행·도움말(sleepStageInfo)이 **같은 문자열**이어야 한다.
 *    sleepStageInfo 는 문자열 contains 로 단계를 고르는데, 여기가 어긋나면 회색 행의 '?' 가
 *    else 분기(= 자다 깬 시간) 설명을 띄운다.
 * ⚠️ 길이: 분포 행은 fitText="수면 중 깸 (자다 깬 시간)" 로 글자 크기를 맞추므로 그보다 짧게 유지.
 */
private const val SLEEP_UNSTAGED_LABEL = "기타 수면 (단계 없음)"

@Composable
fun DetailSleepScreen(
    onBack: () -> Unit,
    initialDate: String? = null,
    initialPeriod: String? = null,  // "7d" / "31d" → 진입 시 모드
    vm: DailySummaryViewModel = viewModel()
) {
    val state by vm.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    // 일일 기본
    // [v1.0 피드백] initialPeriod 받으면 그 모드로 시작 (리포트→7일/31일 그래프 직행)
    var sleepPeriod by remember {
        mutableStateOf(
            when (initialPeriod) {
                "7d" -> SleepPeriod.DAYS_7
                "31d" -> SleepPeriod.DAYS_31
                else -> SleepPeriod.DAY_1
            }
        )
    }
    // 7/31일 → 일일 점프 시 이전 period 기억 → 뒤로가기 시 복귀 (코덱스 P0 fix)
    var returnSleepPeriod by remember { mutableStateOf<SleepPeriod?>(null) }
    var sleepSelectedDate by remember {
        mutableStateOf(
            initialDate?.let {
                try { java.time.LocalDate.parse(it) } catch (e: Exception) { java.time.LocalDate.now() }
            } ?: java.time.LocalDate.now()
        )
    }
    var sleepByDay by remember {
        mutableStateOf<Map<java.time.LocalDate, com.dangbomcare.app.data.health.HealthConnectRepository.SleepDay>>(emptyMap())
    }
    var periodLoading by remember { mutableStateOf(true) }
    // 코덱스 B-완성: endDate 부모 hoist
    val sleepToday = remember { java.time.LocalDate.now() }
    // [v1.0 피드백] initialDate가 있으면 그 날짜를 endDate로 사용
    var sleepEndDate by remember {
        mutableStateOf(
            initialDate?.let { try { java.time.LocalDate.parse(it) } catch (_: Exception) { sleepToday } } ?: sleepToday
        )
    }

    LaunchedEffect(sleepPeriod, sleepEndDate) {
        if (sleepPeriod == SleepPeriod.DAY_1) {
            periodLoading = false
            return@LaunchedEffect
        }
        periodLoading = true
        try {
            val repo = com.dangbomcare.app.data.health.HealthConnectRepository(context)
            if (repo.isSdkAvailable()) {
                val im = periodIsMonth(sleepPeriod.days)
                sleepByDay = repo.fetchSleepByDay(periodStartOf(sleepEndDate, im), periodEndOf(sleepEndDate, im))
            }
        } catch (e: Exception) {
            // 코덱스 H 보강: fetch 실패 시 이전 range 데이터가 남지 않도록 비움
            sleepByDay = emptyMap()
        }
        periodLoading = false
    }

    // ⭐ Codex P0-4: 일자 변경 시 VM 재조회 (이전엔 sleepSelectedDate만 바뀌고 vm.state.data는 그대로 → 다른 날 데이터 표시)
    LaunchedEffect(sleepSelectedDate) {
        if (sleepPeriod == SleepPeriod.DAY_1 && state.date != sleepSelectedDate) {
            vm.load(sleepSelectedDate)
        }
    }

    // 일일 모드에서 뒤로가기 누르면 이전 period(7일/31일)로 복귀
    fun handleSleepBack() {
        val prev = returnSleepPeriod
        if (sleepPeriod == SleepPeriod.DAY_1 && prev != null) {
            sleepPeriod = prev
            returnSleepPeriod = null
        } else onBack()
    }
    BackHandler(enabled = sleepPeriod == SleepPeriod.DAY_1 && returnSleepPeriod != null) {
        handleSleepBack()
    }

    Scaffold(
        containerColor = BgSecondary,
        topBar = { DetailTopBar(title = "😴 수면", onBack = { handleSleepBack() }) }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // ─── 기간 탭 + 통계 + 막대그래프 ───
            SleepPeriodTabs(selected = sleepPeriod, onSelect = { p ->
                // 다른 기간 직접 선택 시 endDate 오늘로 reset (BackHandler 복귀에서는 reset 안 함)
                if (p != sleepPeriod && p != SleepPeriod.DAY_1) {
                    sleepEndDate = sleepToday
                }
                sleepPeriod = p
                if (p != SleepPeriod.DAY_1) returnSleepPeriod = null
            })
            if (periodLoading) {
                LoadingIndicator(modifier = Modifier.height(120.dp))
            } else {
                when (sleepPeriod) {
                    SleepPeriod.DAY_1 -> {
                        // 일일 모드 — 날짜 선택 + 그날 수면 시간선
                        DateNavigatorBar(
                            date = sleepSelectedDate,
                            onChange = { sleepSelectedDate = it }
                        )
                        SleepDayTimelineCard(date = sleepSelectedDate)
                    }
                    else -> {
                        SleepPeriodSummaryCard(
                            sleepByDay = sleepByDay,
                            period = sleepPeriod,
                            endDate = sleepEndDate
                        )
                        SleepStackedBarCard(
                            sleepByDay = sleepByDay,
                            period = sleepPeriod,
                            endDate = sleepEndDate,
                            onEndDateChange = { sleepEndDate = it },
                            onDayClick = { date ->
                                returnSleepPeriod = sleepPeriod
                                sleepSelectedDate = date
                                sleepPeriod = SleepPeriod.DAY_1
                            }
                        )
                        // [사용자 피드백] 평균 단계별 시간 박스 추가
                        SleepStageAverageBoxes(sleepByDay = sleepByDay, period = sleepPeriod, endDate = sleepEndDate)
                    }
                }
            }

            // ─── 기존 어제 상세 카드 ─── (당봄 #20-2: 7일/31일 모드에서는 노출 X)
            if (sleepPeriod == SleepPeriod.DAY_1 && state.loading) LoadingIndicator(modifier = Modifier.height(200.dp))
            else if (sleepPeriod == SleepPeriod.DAY_1) {
                val activity = state.data?.activity
                val sleepHours = activity?.sleepHours ?: 0.0
                // (stages 변수는 잔존 카드 제거 후 unused → 삭제)

                // [사용자 피드백] "어제 수면 X시간 Y분 / 부족" 큰 박스 제거 —
                //   SleepDayTimelineCard에 이미 동일 정보 (총 시간 + 잠든/일어난 시각 + 단계 분포)가 다 표시됨.
                //   중복 박스라 일일 화면이 길어지고 불필요.

                // [260705 R5] '수면 부족 알림'·'수면과 혈당' 카드 제거 (사용자 지시) —
                //   부족 알림은 '오늘' 백엔드 수면 기준이라 과거 날짜(8시간 잔 날)에도 떠서 오해 유발.
                //   한줄평(SleepSummaryCard)이 그 날 기준 평가를 이미 제공.
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun SleepStageBar(label: String, minutes: Int, color: Color, desc: String) {
    val maxMinutes = 480f  // 8시간 기준
    val ratio = (minutes / maxMinutes).coerceIn(0f, 1f)
    var showHelp by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    Spacer(Modifier.width(6.dp))
                    // ? 아이콘 — 시니어 친화 도움말 (당봄 #13)
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(TextSecondary.copy(alpha = 0.15f))
                            .clickable { showHelp = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("?", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextSecondary)
                    }
                }
                Text(desc, fontSize = 11.sp, color = TextSecondary)
            }
            Text("${minutes}분", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = color)
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { ratio },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            color = color,
            trackColor = BgCardSoft
        )
    }

    // 도움말 팝업 (시니어 친화) — [v1.0 피드백] 통합된 신규 디자인 사용 (사진/단계 카드 스타일)
    if (showHelp) {
        SleepStageHelpDialog(stageName = label, onDismiss = { showHelp = false })
    }
}

// [v1.0 피드백 — 통합] 구 SleepStageHelpDialog + StageInfoSection 삭제됨.
//   라인 1235 호출은 신규 SleepStageHelpDialog (이 파일 내 line ~3060)로 변경.


/**
 * 사용자 피드백 — 수면 7일/31일 그래프 하단에 단계별 평균 시간 박스.
 * 체력회복 / 얕은잠 / REM / 깸 4개 카드.
 */
@Composable
private fun SleepStageAverageBoxes(
    sleepByDay: Map<java.time.LocalDate, com.dangbomcare.app.data.health.HealthConnectRepository.SleepDay>,
    period: SleepPeriod,
    endDate: java.time.LocalDate
) {
    val days = periodDatesOf(endDate, periodIsMonth(period.days))
    val records = days.mapNotNull { sleepByDay[it] }.filter { it.totalMinutes > 0 }
    if (records.isEmpty()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = BgCard),
            border = BorderStroke(1.dp, Divider)
        ) {
            Column(
                modifier = Modifier.padding(20.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("💤", fontSize = 32.sp)
                Spacer(Modifier.height(6.dp))
                Text("단계별 평균 계산할 수면 데이터 없음", fontSize = 12.sp, color = TextSecondary)
            }
        }
        return
    }

    val avgDeep = records.map { it.deepMinutes }.average().toInt()
    val avgLight = records.map { it.lightMinutes }.average().toInt()
    val avgRem = records.map { it.remMinutes }.average().toInt()
    val avgAwake = records.map { it.awakeMinutes }.average().toInt()
    // [260729] 단계 미기록(SLEEPING) 평균. 분모(avgTotal)에도 넣어야 % 합이 100에 맞는다.
    //   0분이면 더해도 avgTotal 이 그대로라 나머지 4행 %·글자 크기는 변경 전과 동일하다.
    val avgUnstaged = records.map { it.unstagedMinutes }.average().toInt()
    val avgTotal = (avgDeep + avgLight + avgRem + avgAwake + avgUnstaged).coerceAtLeast(1)

    // [사용자 피드백] 일일 모드와 동일한 "수면 단계 분포" 디자인 (SleepStagePercentRow 4줄)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        border = BorderStroke(1.dp, Divider)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "${period.label} 평균 수면 단계 분포",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "측정된 ${records.size}일 평균이에요",
                fontSize = 11.sp,
                color = TextSecondary
            )
            Spacer(Modifier.height(12.dp))
            // [260623] 순서 깸→렘→얕은→깊은 / [260705 R5] 한 줄 자동축소 라벨 (라인 정렬 통일)
            SleepStagePercentRow(SleepAwakeColor, "수면 중 깸 (자다 깬 시간)", avgAwake, avgTotal)
            // [260729] 단계 미기록 행 — SleepStagePercentRow 는 0분에도 "0% / 0시간 0분" 을 그리므로
            //   (다른 4행과 달리 자체 0-가드가 없다) 호출부에서 막는다. 대부분 기기는 0분이라
            //   이 가드가 없으면 전 사용자 화면에 쓸모없는 줄이 하나 늘어난다.
            if (avgUnstaged > 0) {
                SleepStagePercentRow(SleepUnstagedColor, SLEEP_UNSTAGED_LABEL, avgUnstaged, avgTotal)
            }
            SleepStagePercentRow(SleepRemColor, "렘 수면 (뇌 휴식하는 잠)", avgRem, avgTotal)
            SleepStagePercentRow(SleepLightColor, "얕은 수면", avgLight, avgTotal)
            SleepStagePercentRow(SleepDeepColor, "깊은 수면 (체력 회복 잠)", avgDeep, avgTotal)
        }
    }
}

// ==============================================
// 스트레스 상세 (11 / 14) — 삼성헬스 스타일 (7/31/12개월 + 라인그래프)
// ==============================================
private enum class StressPeriod(val days: Int, val label: String) {
    DAY_1(1, "일일"),
    DAYS_7(7, "7일"),
    DAYS_31(31, "31일")
}

// 스트레스 레벨 색상 (삼성헬스 톤)
private val StressCalmColor = Color(0xFF2196F3)     // 편안함 — 파랑
private val StressLowColor = Color(0xFF4CAF50)     // 낮음 — 초록
private val StressMidColor = Color(0xFFFFC107)     // 보통 — 노랑
private val StressHighColor = Color(0xFFFF6D00)    // 높음 — 주황

// 평균 심박을 4단계로 매핑 (휴식 시 60-100 정상, 100+ 스트레스 ↑)
private fun stressLevelFromHr(avgBpm: Int): Pair<String, Color> = when {
    avgBpm <= 0 -> "—" to TextDisabled
    avgBpm < 65 -> "편안함" to StressCalmColor
    avgBpm < 75 -> "낮음" to StressLowColor
    avgBpm < 85 -> "보통" to StressMidColor
    else -> "높음" to StressHighColor
}

// 0~1 정규화 (편안함=0, 낮음=0.25, 보통=0.5, 높음=1) — 그라데이션 바 마커 위치
private fun stressNormalizedFromHr(avgBpm: Int): Float = when {
    avgBpm <= 0 -> 0.5f
    avgBpm < 65 -> ((avgBpm - 50f) / 15f * 0.25f).coerceIn(0f, 0.25f)
    avgBpm < 75 -> 0.25f + ((avgBpm - 65f) / 10f * 0.25f)
    avgBpm < 85 -> 0.5f + ((avgBpm - 75f) / 10f * 0.25f)
    else -> (0.75f + ((avgBpm - 85f) / 25f * 0.25f)).coerceIn(0f, 1f)
}

/**
 * 삼성헬스 스타일 그라데이션 바 — 파랑(편안함) → 초록(낮음) → 노랑(보통) → 주황(높음).
 * 현재 위치 마커(흰 원 + 번개 아이콘)를 표시.
 */
@Composable
private fun StressGradientBar(avgBpm: Int, dailyAvgBpm: Int? = null) {
    val pos = stressNormalizedFromHr(avgBpm)
    val dailyPos = dailyAvgBpm?.let { stressNormalizedFromHr(it) }
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    androidx.compose.ui.graphics.Brush.horizontalGradient(
                        0.0f to StressCalmColor,
                        0.30f to StressLowColor,
                        0.60f to StressMidColor,
                        1.0f to StressHighColor
                    )
                )
        )
        // 마커 (현재 위치)
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxWidth().height(28.dp)) {
            // 일일평균 점선 마커 (먼저 그려서 현재 마커가 위). [밀도] dash 4f,4f→1.5dp · inset 4f→1.5dp · stroke 2f→0.7dp.
            if (dailyPos != null) {
                val dx = dailyPos * size.width
                val dashEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(1.5.dp.toPx(), 1.5.dp.toPx()), 0f)
                drawLine(
                    Color.White.copy(alpha = 0.85f),
                    androidx.compose.ui.geometry.Offset(dx, 1.5.dp.toPx()),
                    androidx.compose.ui.geometry.Offset(dx, size.height - 1.5.dp.toPx()),
                    strokeWidth = 0.7.dp.toPx(),
                    pathEffect = dashEffect
                )
            }
            // 현재 위치 (흰 원). [밀도] 링 반경 인셋 2f→0.7dp · 6f→2dp (흰 테두리 두께가 밀도에 비례).
            val cx = pos * size.width
            drawCircle(
                Color.White,
                radius = size.height / 2 - 0.7.dp.toPx(),
                center = androidx.compose.ui.geometry.Offset(cx, size.height / 2)
            )
            drawCircle(
                stressLevelFromHr(avgBpm).second,
                radius = size.height / 2 - 2.dp.toPx(),
                center = androidx.compose.ui.geometry.Offset(cx, size.height / 2)
            )
        }
    }
    Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text("편안함", fontSize = 10.sp, color = StressCalmColor, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (dailyAvgBpm != null) {
            Text("일일 평균", fontSize = 10.sp, color = TextSecondary, fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.weight(1f))
        } else Box(modifier = Modifier.weight(1f))
        Text("높음", fontSize = 10.sp, color = StressHighColor, fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.weight(1f))
    }
}

/**
 * 삼성헬스 스타일 비율 막대 — 높음 X% / 보통 X% / 낮음 X% / 편안함 X%.
 * 시간별 샘플(non-null)을 4단계로 묶고 각 비율을 가로 막대로 표시.
 */
@Composable
private fun StressProportionBar(hourly: List<Int?>) {
    val nonNull = hourly.filterNotNull().filter { it > 0 }
    if (nonNull.isEmpty()) return

    val total = nonNull.size.toFloat()
    val high = nonNull.count { it >= 85 } / total
    val mid = nonNull.count { it in 75..84 } / total
    val low = nonNull.count { it in 65..74 } / total
    val calm = nonNull.count { it < 65 } / total

    Column(modifier = Modifier.fillMaxWidth()) {
        StressProportionRow(StressHighColor, "높음", high)
        StressProportionRow(StressMidColor, "보통", mid)
        StressProportionRow(StressLowColor, "낮음", low)
        StressProportionRow(StressCalmColor, "편안함", calm)
    }
}

@Composable
private fun StressProportionRow(color: Color, label: String, ratio: Float) {
    val pct = (ratio * 100).toInt()
    if (pct == 0) return
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                // 좁은 화면(폴드 커버 등)에서 라벨 밀어내지 않게 남은 폭까지만 (비례 길이는 유지)
                .weight(1f, fill = false)
                .height(10.dp)
                .width((ratio * 200).dp.coerceAtLeast(8.dp))
                .clip(RoundedCornerShape(5.dp))
                .background(color)
        )
        Spacer(Modifier.width(8.dp))
        Text("$label $pct%", fontSize = 11.sp, color = TextSecondary, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun DetailStressScreen(
    onBack: () -> Unit,
    initialDate: String? = null,
    initialPeriod: String? = null,
    vm: DailySummaryViewModel = viewModel()
) {
    val state by vm.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    // 일일 기본
    // [v1.0 피드백] initialPeriod 받으면 그 모드로 시작
    var stressPeriod by remember {
        mutableStateOf(
            when (initialPeriod) {
                "7d" -> StressPeriod.DAYS_7
                "31d" -> StressPeriod.DAYS_31
                else -> StressPeriod.DAY_1
            }
        )
    }
    // 7/31일 → 일일 점프 시 이전 period 기억 → 뒤로가기 복귀 (걸음·수면과 일관성)
    var returnStressPeriod by remember { mutableStateOf<StressPeriod?>(null) }
    var stressSelectedDate by remember {
        mutableStateOf(
            initialDate?.let {
                try { java.time.LocalDate.parse(it) } catch (e: Exception) { java.time.LocalDate.now() }
            } ?: java.time.LocalDate.now()
        )
    }
    var hrByDay by remember {
        mutableStateOf<Map<java.time.LocalDate, com.dangbomcare.app.data.health.HealthConnectRepository.HeartRateDay>>(emptyMap())
    }
    var stressLoading by remember { mutableStateOf(true) }
    // 코덱스 일관성: endDate 부모 hoist (걸음/수면과 동일)
    val stressToday = remember { java.time.LocalDate.now() }
    // [v1.0 피드백] initialDate가 있으면 그 날짜를 endDate로 사용
    var stressEndDate by remember {
        mutableStateOf(
            initialDate?.let { try { java.time.LocalDate.parse(it) } catch (_: Exception) { stressToday } } ?: stressToday
        )
    }

    LaunchedEffect(stressPeriod, stressEndDate) {
        if (stressPeriod == StressPeriod.DAY_1) {
            stressLoading = false
            return@LaunchedEffect
        }
        stressLoading = true
        try {
            val repo = com.dangbomcare.app.data.health.HealthConnectRepository(context)
            if (repo.isSdkAvailable()) {
                val im = periodIsMonth(stressPeriod.days)
                hrByDay = repo.fetchHeartRateByDay(periodStartOf(stressEndDate, im), periodEndOf(stressEndDate, im))
            }
        } catch (e: Exception) {
            hrByDay = emptyMap()
        }
        stressLoading = false
    }

    // ⭐ Codex P0-4: 일자 변경 시 VM 재조회 (sleep 동일 패턴)
    LaunchedEffect(stressSelectedDate) {
        if (stressPeriod == StressPeriod.DAY_1 && state.date != stressSelectedDate) {
            vm.load(stressSelectedDate)
        }
    }

    // 일일 모드에서 뒤로가기 누르면 이전 period(7일/31일)로 복귀
    fun handleStressBack() {
        val prev = returnStressPeriod
        if (stressPeriod == StressPeriod.DAY_1 && prev != null) {
            stressPeriod = prev
            returnStressPeriod = null
        } else onBack()
    }
    BackHandler(enabled = stressPeriod == StressPeriod.DAY_1 && returnStressPeriod != null) {
        handleStressBack()
    }

    Scaffold(
        containerColor = BgSecondary,
        topBar = { DetailTopBar(title = "😌 스트레스", onBack = { handleStressBack() }) }
    ) { padding ->
        Column(
            // [Fix 1] 스트레스 카드 전체를 감싸는 바깥 Column 의 가로 패딩 20→12dp 로 축소 →
            //   시간별/일별 차트 카드가 화면 가로를 눈에 띄게 더 넓게 사용. 세로 패딩은 변경 없음.
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // ─── 기간 탭 + 라인 그래프 ───
            StressPeriodTabs(selected = stressPeriod, onSelect = { p ->
                // 다른 기간 직접 선택 시 endDate 오늘로 reset (BackHandler 복귀에서는 reset 안 함)
                if (p != stressPeriod && p != StressPeriod.DAY_1) {
                    stressEndDate = stressToday
                }
                stressPeriod = p
                if (p != StressPeriod.DAY_1) returnStressPeriod = null
            })
            if (stressLoading) {
                LoadingIndicator(modifier = Modifier.height(120.dp))
            } else {
                when (stressPeriod) {
                    StressPeriod.DAY_1 -> {
                        DateNavigatorBar(
                            date = stressSelectedDate,
                            onChange = { stressSelectedDate = it }
                        )
                        StressDayHourlyCard(date = stressSelectedDate)
                    }
                    else -> {
                        StressPeriodSummaryCard(
                            hrByDay = hrByDay,
                            period = stressPeriod,
                            endDate = stressEndDate
                        )
                        StressLineChartCard(
                            hrByDay = hrByDay,
                            period = stressPeriod,
                            endDate = stressEndDate,
                            onEndDateChange = { stressEndDate = it },
                            onDayClick = { date ->
                                returnStressPeriod = stressPeriod
                                stressSelectedDate = date
                                stressPeriod = StressPeriod.DAY_1
                            }
                        )
                    }
                }
            }

            // ─── 기존 어제 상세 카드 ───
            // 사용자 피드백: "오늘 오전 컨디션" 박스와 "시간대별 변동성" 박스 삭제
            // (의미가 약하고, 그래프 점 터치 툴팁으로 충분히 대체됨)
            if (state.loading) LoadingIndicator(modifier = Modifier.height(200.dp))
            else {
                val stress = state.data?.activity?.stress

                // 의학적 경고 — 스트레스 높음 시 (당봄 피드백 #9)
                if (stress == "높음" || stress == "주의") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Warning.copy(alpha = 0.15f)),
                        border = BorderStroke(1.dp, Warning.copy(alpha = 0.3f))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("⚠️", fontSize = 18.sp)
                                Spacer(Modifier.width(8.dp))
                                Text("스트레스 알림", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Warning)
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "스트레스는 인슐린 작용을 방해합니다.\n오늘 혈당이 조금 높다면 마음을 먼저 돌봐주세요.",
                                fontSize = 13.sp,
                                color = TextPrimary,
                                lineHeight = 19.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "스트레스 호르몬(코르티솔)이 증가하면 간이 포도당을\n더 많이 만들어내 혈당이 올라갑니다.",
                                fontSize = 11.sp,
                                color = TextSecondary,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }

                // 일반 안내 — 코르티솔
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = LimeLight.copy(alpha = 0.4f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("💡 스트레스와 혈당의 관계", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = PrimaryDark)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "스트레스 호르몬(코르티솔)이 증가하면 간이 포도당을 더 많이 만들어내 혈당이 올라갑니다. 깊은 호흡과 가벼운 산책이 도움이 됩니다.",
                            fontSize = 12.sp,
                            color = TextPrimary,
                            lineHeight = 18.sp
                        )
                    }
                }

                // 안내
                Text(
                    "※ 스트레스는 심박 변동성 기반 자체 추정값이며, 의료적 진단이 아닙니다.",
                    fontSize = 11.sp,
                    color = TextDisabled,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun TimeOfDayItem(label: String, time: String, status: String?) {
    val (color, bg) = when (status) {
        "적정" -> Primary to LimeLight
        "주의" -> ScoreOk to ScoreOk.copy(alpha = 0.15f)
        "높음" -> ErrorRed to ErrorRed.copy(alpha = 0.1f)
        else -> TextDisabled to BgCardSoft
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Text(time, fontSize = 11.sp, color = TextSecondary)
        }
        Surface(color = bg, shape = RoundedCornerShape(10.dp)) {
            Text(
                status ?: "—",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = color,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun DetailTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.Bold) },
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = BgSecondary)
    )
}

// ==============================================
// 수면 상세 — 삼성헬스 스타일 컴포넌트
// ==============================================
@Composable
private fun SleepPeriodTabs(selected: SleepPeriod, onSelect: (SleepPeriod) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BgCardSoft)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        SleepPeriod.values().forEach { p ->
            val isSel = p == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSel) Primary else Color.Transparent)
                    .clickable { onSelect(p) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    p.label,
                    fontSize = 14.sp,
                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.SemiBold,
                    color = if (isSel) Color.White else TextSecondary
                )
            }
        }
    }
}

@Composable
private fun SleepPeriodSummaryCard(
    sleepByDay: Map<java.time.LocalDate, com.dangbomcare.app.data.health.HealthConnectRepository.SleepDay>,
    period: SleepPeriod,
    endDate: java.time.LocalDate = java.time.LocalDate.now()
) {
    // [기간 정렬] 7일=월~일 / 31일=그 달 1일~말일
    val im = periodIsMonth(period.days)
    val from = periodStartOf(endDate, im)
    val winEnd = periodEndOf(endDate, im)
    val daysList = sleepByDay.values.toList()
    val totalMin = daysList.sumOf { it.totalMinutes }
    val daysCount = daysList.size
    val avgMin = if (daysCount > 0) totalMin / daysCount else 0
    val avgHours = avgMin / 60
    val avgRem = avgMin % 60

    // 평균 취침/기상 시각 (시 단위, 자정 넘어가는 케이스 처리)
    val zone = java.time.ZoneId.systemDefault()
    val sleepStartsMin = daysList.mapNotNull { it.sleepStart?.atZone(zone) }.map { z ->
        // 18시~24시 = 음수(전날 취침), 0시~12시 = 그대로
        val h = z.hour; val m = z.minute
        if (h >= 18) (h - 24) * 60 + m else h * 60 + m
    }
    val sleepEndsMin = daysList.mapNotNull { it.sleepEnd?.atZone(zone) }.map { it.hour * 60 + it.minute }
    val avgStart = if (sleepStartsMin.isNotEmpty()) sleepStartsMin.average().toInt() else 0
    val avgEnd = if (sleepEndsMin.isNotEmpty()) sleepEndsMin.average().toInt() else 0

    fun formatStart(min: Int): String {
        var totalMin = min
        if (totalMin < 0) totalMin += 24 * 60
        val h = totalMin / 60; val m = totalMin % 60
        val amPm = if (h < 12 || h >= 24) "오전" else "오후"
        val h12 = ((h + 11) % 12) + 1
        return "$amPm $h12:${"%02d".format(m)}"
    }
    fun formatEnd(min: Int): String {
        val h = min / 60; val m = min % 60
        val amPm = if (h < 12) "오전" else "오후"
        val h12 = ((h + 11) % 12) + 1
        return "$amPm $h12:${"%02d".format(m)}"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        border = BorderStroke(1.dp, Divider)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "${from.monthValue}월 ${from.dayOfMonth}일 ~ ${winEnd.monthValue}월 ${winEnd.dayOfMonth}일",
                fontSize = 12.sp, color = TextSecondary
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text("$avgHours", fontSize = 36.sp, fontWeight = FontWeight.Black, color = TextPrimary)
                Text("시간 ", fontSize = 18.sp, color = TextPrimary, modifier = Modifier.padding(bottom = 4.dp))
                Text("$avgRem", fontSize = 32.sp, fontWeight = FontWeight.Black, color = TextPrimary)
                Text("분/일", fontSize = 16.sp, color = TextSecondary, modifier = Modifier.padding(bottom = 6.dp))
            }
            if (daysCount > 0) {
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("평균 취침 시각", fontSize = 12.sp, color = TextSecondary)
                        Text(formatStart(avgStart), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    }
                    Box(modifier = Modifier.width(1.dp).height(36.dp).background(Divider))
                    Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
                        Text("평균 기상 시각", fontSize = 12.sp, color = TextSecondary)
                        Text(formatEnd(avgEnd), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    }
                }
            } else {
                Spacer(Modifier.height(8.dp))
                Text("기간 내 수면 데이터가 없어요", fontSize = 12.sp, color = TextSecondary)
            }
        }
    }
}

/**
 * 삼성헬스 스타일 수면 스택드 막대 그래프 (7일 / 31일).
 * - 좌우 화살표로 주간/월간 이동
 * - 8시간(목표) + 4h/12h Y축 점선
 * - 막대 터치 → 단계별 시간 툴팁 (자다 깬 / 뇌 휴식 / 얕은 / 체력 회복)
 * - 툴팁 클릭 → 일일 모드 점프 (onDayClick)
 * - X축 가로 라벨 (31일은 5단위, 1일은 M/1)
 */
@Composable
private fun SleepStackedBarCard(
    sleepByDay: Map<java.time.LocalDate, com.dangbomcare.app.data.health.HealthConnectRepository.SleepDay>,
    period: SleepPeriod,
    endDate: java.time.LocalDate,
    onEndDateChange: (java.time.LocalDate) -> Unit,
    onDayClick: (java.time.LocalDate) -> Unit = {}
) {
    val today = java.time.LocalDate.now()
    val days = periodDatesOf(endDate, periodIsMonth(period.days))
    val values = days.map { sleepByDay[it]?.totalMinutes ?: 0 }
    val nonZero = values.filter { it > 0 }
    val avgMin = if (nonZero.isNotEmpty()) nonZero.average().toInt() else 0
    val maxTotal = (values.maxOrNull() ?: 0).coerceAtLeast(720)   // 최대 12시간으로 고정
    val maxBarHeight = 200.dp

    var selectedIdx by remember(period, endDate) { mutableStateOf<Int?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        border = BorderStroke(1.dp, Divider)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            // ── 헤더: 좌우 화살표 + 기간 라벨 + 평균 ──
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    onEndDateChange(periodPrevOf(endDate, periodIsMonth(period.days)))
                    selectedIdx = null
                }) {
                    Icon(Icons.Filled.ChevronLeft, contentDescription = "이전 기간", tint = Primary)
                }
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    val rangeLabel = "${days.first().monthValue}월 ${days.first().dayOfMonth}일 - ${days.last().monthValue}월 ${days.last().dayOfMonth}일"
                    com.dangbomcare.app.ui.components.AutoSizeSingleLineText(rangeLabel, maxFontSize = 13.sp, minFontSize = 10.sp, color = TextSecondary)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${avgMin / 60}시간 ${avgMin % 60}분/일",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }
                IconButton(
                    onClick = {
                        if (periodEndOf(endDate, periodIsMonth(period.days)) < periodEndOf(today, periodIsMonth(period.days))) {
                            onEndDateChange(periodNextOf(endDate, periodIsMonth(period.days)))
                            selectedIdx = null
                        }
                    },
                    enabled = periodEndOf(endDate, periodIsMonth(period.days)) < periodEndOf(today, periodIsMonth(period.days))
                ) {
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = "다음 기간",
                        tint = if (periodEndOf(endDate, periodIsMonth(period.days)) < periodEndOf(today, periodIsMonth(period.days))) Primary else TextDisabled
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            // [260705 R4] 범례 제거 — 아래 '평균 수면 단계 분포'가 색·이름을 설명 (중복 제거)
            Spacer(Modifier.height(4.dp))

            // 코덱스 H: 접근성 요약
            val maxIdx = values.indices.maxByOrNull { values[it] }
            val sleepA11ySummary = buildString {
                append("수면 차트. ")
                append("${days.first().a11yDate()}부터 ${days.last().a11yDate()}까지. ")
                append("일평균 ${minutesToA11y(avgMin)}. ")
                if (maxIdx != null && values[maxIdx] > 0) {
                    append("가장 오래 잔 날은 ${days[maxIdx].a11yDate()}, ${minutesToA11y(values[maxIdx])}.")
                }
            }

            // ── 차트 본체 ── [재구성] 스트레스 차트(StressTrendCard)와 동일한 Canvas 레이아웃:
            //   · 플롯 영역을 옅은 박스(BgCardSoft)로 한정 → 막대/축라벨 시각 분리
            //   · 가로 격자(0·4h·8h·12h)를 막대 '뒤'에 솔리드 Divider 선으로 → 확실히 보임
            //   · Y 라벨(12h/8h/4h)은 박스 오른쪽 전용 여백(rightPad), X 날짜 라벨은 박스 아래 여백(bottomPad)
            //   · 막대를 좌우로 인셋(plotPad)해 슬롯 중앙에 → 첫/마지막 막대가 격자/라벨을 안 침범
            //   · 모든 캔버스 텍스트는 sp 기반(.sp.toPx()) → 전역 시니어 폰트 스케일 반영
            val secondaryArgb = TextSecondary.toArgb()
            val primaryArgb = Primary.toArgb()
            // 고정 Y범위 0~maxTotal(최소 12시간). 막대 위쪽이 천장에 안 붙도록 약간 헤드룸.
            val yTopMin = (maxTotal * 1.02f).coerceAtLeast(720f)
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(maxBarHeight + 40.dp)
                    .semantics { contentDescription = sleepA11ySummary }
                    .pointerInput(days.size) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            // draw 와 동일한 plotLeft/rightPad/plotPad 공식 → 터치 x-매핑을 막대 중심과 일치.
                            // [밀도 견고화] draw 블록과 동일하게 raw px → dp 변환 (터치/draw 동기화 유지).
                            val axisLabelPx = 10.sp.toPx()
                            val labelGap = 3.dp.toPx()                    // 8f → 3.dp(≈8.25px @2.75)
                            val leftEdge = 3.5.dp.toPx()                  // 10f → 3.5.dp(≈9.6px @2.75)
                            val rightPad = (axisLabelPx * 2.2f + labelGap + 1.5.dp.toPx()).coerceAtLeast(16.dp.toPx())  // 4f→1.5.dp, 44f→16.dp
                            val plotPad = if (days.size <= 7) 3.5.dp.toPx() else 1.5.dp.toPx()  // 10f/4f → 3.5dp/1.5dp
                            val plotLeft = leftEdge
                            val plotRight = size.width - rightPad
                            val innerW = (plotRight - plotLeft) - plotPad * 2
                            val n = days.size
                            val slotW = if (n > 0) innerW / n else innerW
                            fun idxFromX(x: Float): Int {
                                if (n <= 1) return 0
                                val rel = (x - plotLeft - plotPad).coerceIn(0f, innerW - 0.001f)
                                return (rel / slotW).toInt().coerceIn(0, n - 1)
                            }
                            selectedIdx = idxFromX(down.position.x)
                            drag(down.id) { change ->
                                selectedIdx = idxFromX(change.position.x)
                                change.consume()
                            }
                        }
                    }
            ) {
                // [밀도 견고화] 여백/인셋 raw px → dp (density 2.75에서 같은 px 재현, 다른 밀도서 비례 스케일).
                //   sp 기반 항(axisLabelPx*N)은 그대로 — 이미 폰트/밀도 반영. coerceAtLeast floor 도 dp 화해
                //   고밀도서 sp 라벨이 커질 때 거터/여백 floor 도 함께 커지게.
                val axisLabelPx = 10.sp.toPx()
                val labelGap = 3.dp.toPx()                    // 8f → 3.dp(≈8.25px @2.75)
                val leftEdge = 3.5.dp.toPx()                  // 10f → 3.5.dp(≈9.6px @2.75)
                // rightPad: 가장 긴 Y라벨 "12h" 폭 + 간격 + 여유. bottomPad: 날짜 라벨 높이 + 간격.
                val rightPad = (axisLabelPx * 2.2f + labelGap + 1.5.dp.toPx()).coerceAtLeast(16.dp.toPx())  // 4f→1.5.dp, 44f→16.dp
                val bottomPad = (axisLabelPx + labelGap * 2f).coerceAtLeast(13.dp.toPx())   // 36f → 13.dp(≈35.75px @2.75)
                val topPad = (axisLabelPx * 0.6f).coerceAtLeast(3.dp.toPx())   // 8f → 3.dp · 천장 헤드룸
                val plotPad = if (days.size <= 7) 3.5.dp.toPx() else 1.5.dp.toPx()  // 10f/4f → 3.5dp/1.5dp · 막대 행 좌우 인셋
                val plotLeft = leftEdge
                val plotRight = size.width - rightPad
                val plotTop = topPad
                val plotBottom = size.height - bottomPad
                val plotH = plotBottom - plotTop

                // 플롯 영역 박스 — 막대와 축 라벨을 시각적으로 분리(스트레스 차트와 동일).
                val boxCornerPx = 6.dp.toPx()   // [밀도] 16f → 6.dp(≈16.5px @2.75) · 박스 모서리
                drawRoundRect(
                    color = BgCardSoft,
                    topLeft = androidx.compose.ui.geometry.Offset(plotLeft, plotTop),
                    size = androidx.compose.ui.geometry.Size(plotRight - plotLeft, plotH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(boxCornerPx, boxCornerPx)
                )

                fun yForMin(min: Int): Float = plotBottom - plotH * (min.toFloat() / yTopMin).coerceIn(0f, 1f)

                // ── 가로 격자 + Y축 라벨 (0 / 4h / 8h / 12h) — 막대 '뒤'에 솔리드 Divider 선. ──
                val yLabelPaint = android.graphics.Paint().apply {
                    color = secondaryArgb
                    textSize = axisLabelPx
                    textAlign = android.graphics.Paint.Align.LEFT
                    isAntiAlias = true
                }
                val yLabelVCenter = -(yLabelPaint.fontMetrics.ascent + yLabelPaint.fontMetrics.descent) / 2f
                // (분, 라벨, 강조여부) — 8h(480) 가 목표선이라 Primary 색 + 굵게.
                val gridLines = listOf(
                    Triple(0, "0", false),
                    Triple(240, "4h", false),
                    Triple(480, "8h", true),
                    Triple(720, "12h", false)
                )
                gridLines.forEach { (min, label, emph) ->
                    val y = yForMin(min)
                    drawLine(
                        color = if (emph) Primary.copy(alpha = 0.55f) else Divider,
                        start = androidx.compose.ui.geometry.Offset(plotLeft, y),
                        end = androidx.compose.ui.geometry.Offset(plotRight, y),
                        strokeWidth = if (emph) 0.5.dp.toPx() else 0.4.dp.toPx()   // [밀도] 1.5f/1f → 0.5dp/0.4dp
                    )
                    yLabelPaint.color = if (emph) primaryArgb else secondaryArgb
                    yLabelPaint.isFakeBoldText = emph
                    drawIntoCanvas { c ->
                        c.nativeCanvas.drawText(label, plotRight + labelGap, y + yLabelVCenter, yLabelPaint)
                    }
                }

                // ── 막대 슬롯 — 좌우 인셋(plotPad), 각 날짜를 균등 슬롯 중앙에 배치. ──
                val n = days.size
                val innerW = (plotRight - plotLeft) - plotPad * 2
                val slotW = if (n > 0) innerW / n else innerW
                val barW = (slotW * (if (n <= 7) 0.62f else 0.78f)).coerceAtLeast(0.7.dp.toPx())  // [밀도] floor 2f → 0.7.dp

                days.forEachIndexed { i, d ->
                    val sleep = sleepByDay[d]
                    val slotCenter = plotLeft + plotPad + slotW * (i + 0.5f)
                    val left = slotCenter - barW / 2f

                    // 선택된 날짜 컬럼 세로 띠 (막대 뒤, 격자 위) — 일일/스트레스 차트와 통일.
                    if (selectedIdx == i) {
                        val bandW = slotW.coerceAtMost(20.dp.toPx())
                        var bl = slotCenter - bandW / 2f
                        if (bl < plotLeft) bl = plotLeft
                        if (bl + bandW > plotRight) bl = plotRight - bandW
                        drawRect(
                            color = Slate.copy(alpha = 0.15f),
                            topLeft = androidx.compose.ui.geometry.Offset(bl, plotTop),
                            size = androidx.compose.ui.geometry.Size(bandW, plotH)
                        )
                    }

                    if (sleep == null || sleep.totalMinutes == 0) {
                        // 데이터 없음 — 베이스라인에 옅은 점.
                        drawLine(
                            color = Color(0xFFC8CDD4),
                            start = androidx.compose.ui.geometry.Offset(left, plotBottom - 0.4.dp.toPx()),
                            end = androidx.compose.ui.geometry.Offset(left + barW, plotBottom - 0.4.dp.toPx()),
                            strokeWidth = 0.7.dp.toPx()   // [밀도] 2f → 0.7.dp(≈1.9px @2.75)
                        )
                        return@forEachIndexed
                    }

                    val total = sleep.totalMinutes
                    val barTopY = yForMin(total)
                    // 단계별 스택 (아래→위: 체력회복=Deep, 얕은=Light, 뇌휴식=Rem, 단계미기록, 깸=Awake) — 막대 색 보존.
                    // [260729] 단계 미기록(SLEEPING)을 '잠'이므로 4단계 위·'깸' 아래에 끼워 넣는다.
                    //   → 깸이 최상단이라는 기존 배치(히프노그램 Y축·아래 미니행 순서와 동일)는 그대로.
                    //   ⚠️ 0분이면 아래 `if (segMin > 0)` 과 indexOfLast{ it.first > 0 } 이 이 항목을 통째로
                    //      건너뛴다 → 리스트에 있어도 그려지지 않고 lastDrawnIdx 도 안 바뀌므로,
                    //      unstagedMinutes==0 인 막대는 이 변경 전과 1px도 다르지 않다.
                    val segs = listOf(
                        sleep.deepMinutes to SleepDeepColor,
                        sleep.lightMinutes to SleepLightColor,
                        sleep.remMinutes to SleepRemColor,
                        sleep.unstagedMinutes to SleepUnstagedColor,
                        sleep.awakeMinutes to SleepAwakeColor
                    )
                    // 막대 전체(스택)를 둥근 사각형으로 클립 → 윗변 두 모서리만 살짝 둥글게,
                    //   아랫변(0 베이스라인)은 직각으로 평평하게. 내부 단계 경계는 직각 유지.
                    // ⚠️ 모든 막대 동일한 윗변 둥글기: 반경은 '고정' 3dp. (높이로 스케일 ❌)
                    //   - barFullH 로 나누는 식은 3~8h 실데이터에선 막대마다 r 이 갈려 보일 위험 → 제거.
                    //   - 유일한 가드: 극단적으로 낮은 막대(높이<2r)와 폭(barW/2). barW 는 기간 내 균일하므로
                    //     막대 간 불일치를 만들지 않음. 실수면 데이터(3~8h)에선 두 가드 모두 발동하지 않음.
                    val cornerPx = 3.dp.toPx()   // 살짝만 — 전 막대 동일
                    val barFullH = (plotBottom - barTopY).coerceAtLeast(0f)
                    val r = cornerPx.coerceAtMost(barFullH / 2f).coerceAtMost(barW / 2f)
                    val clip = androidx.compose.ui.graphics.Path().apply {
                        addRoundRect(
                            androidx.compose.ui.geometry.RoundRect(
                                left = left,
                                top = barTopY,
                                right = left + barW,
                                bottom = plotBottom,
                                topLeftCornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
                                topRightCornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
                                bottomLeftCornerRadius = androidx.compose.ui.geometry.CornerRadius.Zero,
                                bottomRightCornerRadius = androidx.compose.ui.geometry.CornerRadius.Zero
                            )
                        )
                    }
                    clipPath(clip) {
                        // 🔑 핵심 수정: totalMinutes 는 세션 전체 길이, 단계 합(Σseg)은 그보다 작을 수 있다
                        //   (미분류/스테이지 공백). 예전엔 클립 윗변은 total(=barTopY)에, 채움은 Σseg 까지만 →
                        //   둥근 모서리 영역이 빈 공간이라 그 막대는 '직각'으로 보였다(= 막대마다 둥글기 불일치 원인).
                        //   해결: 맨 위 단계가 항상 barTopY 까지 닿게 그려 둥근 모서리를 '항상' 채운다.
                        //   (스택 순서·색은 그대로. total>Σseg 의 잔여분은 최상단 단계 색에 흡수.)
                        //   [260729] SLEEPING 분량은 이제 segs 에 회색으로 명시돼 있어 흡수 대상이 아니다.
                        //     여기 흡수로 남는 건 '단계 합이 0이라 세션 구간으로 폴백한 날'의 잔여뿐.
                        val lastDrawnIdx = segs.indexOfLast { it.first > 0 }
                        if (lastDrawnIdx < 0) {
                            // 세션은 있으나 단계 분류가 전혀 없는 날 → 막대가 사라지지 않도록 전체를 옅은색으로 채움.
                            drawRect(
                                color = SleepLightColor,
                                topLeft = androidx.compose.ui.geometry.Offset(left, barTopY),
                                size = androidx.compose.ui.geometry.Size(barW, (plotBottom - barTopY).coerceAtLeast(0f))
                            )
                        } else {
                            var segBottom = plotBottom
                            segs.forEachIndexed { idx, (segMin, segColor) ->
                                if (segMin > 0) {
                                    val segH = plotH * (segMin.toFloat() / yTopMin)
                                    // 최상단(마지막) 단계는 barTopY 까지 끌어올려 둥근 윗변을 항상 채운다.
                                    val segTop = if (idx == lastDrawnIdx) barTopY
                                                 else (segBottom - segH).coerceAtLeast(barTopY)
                                    drawRect(
                                        color = segColor,
                                        topLeft = androidx.compose.ui.geometry.Offset(left, segTop),
                                        size = androidx.compose.ui.geometry.Size(barW, (segBottom - segTop).coerceAtLeast(0f))
                                    )
                                    segBottom = segTop
                                }
                            }
                        }
                    }
                }

                // ── X축 날짜 라벨 — 박스 아래 전용 여백(bottomPad). 월 바뀔 때만 'M/d', 그 외 'd'. ──
                val dayPaint = android.graphics.Paint().apply {
                    color = secondaryArgb
                    textSize = axisLabelPx
                    textAlign = android.graphics.Paint.Align.CENTER
                    isAntiAlias = true
                }
                val selPaint = android.graphics.Paint().apply {
                    color = primaryArgb
                    textSize = axisLabelPx
                    textAlign = android.graphics.Paint.Align.CENTER
                    isAntiAlias = true
                    isFakeBoldText = true
                }
                val dayBaseline = plotBottom + labelGap + (-dayPaint.fontMetrics.ascent)
                val labelInterval = if (period == SleepPeriod.DAYS_31) 5 else 1
                var prevMonth = -1
                days.forEachIndexed { i, d ->
                    if (i % labelInterval == 0 || i == days.lastIndex) {
                        val label = if (d.monthValue != prevMonth) "${d.monthValue}/${d.dayOfMonth}" else "${d.dayOfMonth}"
                        prevMonth = d.monthValue
                        val cx = plotLeft + plotPad + slotW * (i + 0.5f)
                        drawIntoCanvas { c ->
                            c.nativeCanvas.drawText(label, cx, dayBaseline, if (selectedIdx == i) selPaint else dayPaint)
                        }
                    }
                }
            }

            // ── 선택 막대 툴팁 ──
            val sel = selectedIdx
            if (sel != null) {
                val selDate = days[sel]
                val sleep = sleepByDay[selDate]
                Spacer(Modifier.height(12.dp))
                val sleepTooltipA11y = if (sleep == null || sleep.totalMinutes == 0) {
                    "${selDate.a11yDate()}, 수면 데이터 없음"
                } else {
                    "${selDate.a11yDate()}, 총 ${minutesToA11y(sleep.totalMinutes)}, " +
                        "체력 회복 잠 ${minutesToA11y(sleep.deepMinutes)}, " +
                        "얕은 잠 ${minutesToA11y(sleep.lightMinutes)}, " +
                        "뇌 휴식하는 잠 ${minutesToA11y(sleep.remMinutes)}, " +
                        // [260729] 단계 미기록은 있을 때만 읽어준다. 0분이면 빈 문자열이 이어붙어
                        //   결과 문자열이 기존과 완전히 동일 → 토크백 낭독도 변경 전 그대로.
                        (if (sleep.unstagedMinutes > 0)
                            "단계가 기록되지 않은 잠 ${minutesToA11y(sleep.unstagedMinutes)}, " else "") +
                        "자다 깬 시간 ${minutesToA11y(sleep.awakeMinutes)}. " +
                        "두 번 누르면 일일 보기로 이동합니다."
                }
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onDayClick(selDate) }
                        .semantics {
                            liveRegion = LiveRegionMode.Polite
                            contentDescription = sleepTooltipA11y
                        },
                    shape = RoundedCornerShape(12.dp),
                    color = Primary.copy(alpha = 0.12f)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "${selDate.monthValue}월 ${selDate.dayOfMonth}일 (${selDate.dayOfWeek.koreanLabel()})",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary
                                )
                                Spacer(Modifier.height(2.dp))
                                val totalH = (sleep?.totalMinutes ?: 0) / 60
                                val totalM = (sleep?.totalMinutes ?: 0) % 60
                                Text(
                                    if (sleep == null || sleep.totalMinutes == 0) "수면 데이터 없음"
                                    else "${totalH}시간 ${totalM}분",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                                // [사용자 피드백] 잠든 / 일어난 시각 표시
                                if (sleep != null && sleep.totalMinutes > 0 && sleep.sleepStart != null && sleep.sleepEnd != null) {
                                    val zone = java.time.ZoneId.systemDefault()
                                    val st = sleep.sleepStart.atZone(zone).toLocalTime()
                                    val et = sleep.sleepEnd.atZone(zone).toLocalTime()
                                    fun fmtTime(t: java.time.LocalTime): String {
                                        val h = t.hour; val m = t.minute
                                        val ampm = if (h < 12) "오전" else "오후"
                                        val h12 = ((h + 11) % 12) + 1
                                        return "$ampm $h12:${"%02d".format(m)}"
                                    }
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        "🌙 ${fmtTime(st)}  ~  ☀️ ${fmtTime(et)}",
                                        fontSize = 12.sp,
                                        color = TextSecondary,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("일일 보기", fontSize = 11.sp, color = Primary, fontWeight = FontWeight.SemiBold)
                                Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = Primary, modifier = Modifier.size(16.dp))
                            }
                        }
                        if (sleep != null && sleep.totalMinutes > 0) {
                            Spacer(Modifier.height(6.dp))
                            SleepStageMiniRow(SleepAwakeColor, "수면 중 깸 (자다 깬 시간)", sleep.awakeMinutes)
                            // [260729] 막대 스택 순서(위→아래)와 동일 위치. SleepStageMiniRow 가
                            //   minutes <= 0 에서 아무 노드도 emit 하지 않으므로 0분이면 줄이 안 생긴다.
                            SleepStageMiniRow(SleepUnstagedColor, SLEEP_UNSTAGED_LABEL, sleep.unstagedMinutes)
                            SleepStageMiniRow(SleepRemColor, "렘 수면 (뇌 휴식하는 잠)", sleep.remMinutes)
                            SleepStageMiniRow(SleepLightColor, "얕은 수면", sleep.lightMinutes)
                            SleepStageMiniRow(SleepDeepColor, "깊은 수면 (체력 회복 잠)", sleep.deepMinutes)
                        }
                    }
                }
            }
        }
    }
}

/** 스택드 막대 툴팁의 단계별 미니 행 ("● 체력 회복 잠 1시간 44분"). */
@Composable
private fun SleepStageMiniRow(color: Color, label: String, minutes: Int) {
    if (minutes <= 0) return
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 11.sp, color = TextSecondary, modifier = Modifier.weight(1f))
        Text(
            if (minutes >= 60) "${minutes / 60}시간 ${minutes % 60}분" else "${minutes}분",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary
        )
    }
}

@Composable
private fun SleepLegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(4.dp))
        // [260705 R3] FlowRow에서 항목 단위로 줄바꿈되도록 글자 자체는 한 줄 고정 + 확대(10→11.5)
        Text(label, fontSize = 11.5.sp, color = TextSecondary, maxLines = 1, softWrap = false)
    }
}

// (SleepStageAverageCard 제거 — 호출부 없음. 단계별 정보는 SleepStackedBarCard 의 막대 터치 툴팁에서 제공.)

// ==============================================
// 스트레스 상세 — 삼성헬스 스타일 컴포넌트 (라인그래프)
// ==============================================
@Composable
private fun StressPeriodTabs(selected: StressPeriod, onSelect: (StressPeriod) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BgCardSoft)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        StressPeriod.values().forEach { p ->
            val isSel = p == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSel) Primary else Color.Transparent)
                    .clickable { onSelect(p) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    p.label,
                    fontSize = 14.sp,
                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.SemiBold,
                    color = if (isSel) Color.White else TextSecondary
                )
            }
        }
    }
}

@Composable
private fun StressPeriodSummaryCard(
    hrByDay: Map<java.time.LocalDate, com.dangbomcare.app.data.health.HealthConnectRepository.HeartRateDay>,
    period: StressPeriod,
    endDate: java.time.LocalDate = java.time.LocalDate.now()
) {
    // [기간 정렬] 7일=월~일 / 31일=그 달 1일~말일
    val im = periodIsMonth(period.days)
    val from = periodStartOf(endDate, im)
    val winEnd = periodEndOf(endDate, im)
    val entries = hrByDay.values.toList()
    val highestDay = hrByDay.entries.maxByOrNull { it.value.avgBpm }?.key
    val lowestDay = hrByDay.entries.minByOrNull { it.value.avgBpm }?.key
    val avgAll = if (entries.isNotEmpty()) entries.map { it.avgBpm }.average().toInt() else 0
    val (avgLabel, avgColor) = stressLevelFromHr(avgAll)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        border = BorderStroke(1.dp, Divider)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "${from.monthValue}월 ${from.dayOfMonth}일 ~ ${winEnd.monthValue}월 ${winEnd.dayOfMonth}일",
                fontSize = 12.sp, color = TextSecondary
            )
            Spacer(Modifier.height(8.dp))
            if (entries.isEmpty()) {
                Text("기간 내 워치 데이터가 없어요", fontSize = 13.sp, color = TextSecondary)
            } else {
                // 평균 스트레스 레벨 (큰 글씨)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("평균 스트레스 ", fontSize = 14.sp, color = TextSecondary, modifier = Modifier.padding(bottom = 6.dp))
                }
                Text(avgLabel, fontSize = 32.sp, fontWeight = FontWeight.Black, color = avgColor)
                Spacer(Modifier.height(12.dp))
                if (highestDay != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("평균 스트레스가 가장 높았던 때", fontSize = 12.sp, color = TextSecondary, modifier = Modifier.weight(1f))
                        Text(
                            "${highestDay.monthValue}월 ${highestDay.dayOfMonth}일",
                            fontSize = 14.sp, fontWeight = FontWeight.Bold, color = StressHighColor
                        )
                    }
                }
                if (lowestDay != null) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("평균 스트레스가 가장 낮았던 때", fontSize = 12.sp, color = TextSecondary, modifier = Modifier.weight(1f))
                        Text(
                            "${lowestDay.monthValue}월 ${lowestDay.dayOfMonth}일",
                            fontSize = 14.sp, fontWeight = FontWeight.Bold, color = StressCalmColor
                        )
                    }
                }
            }
        }
    }
}

// ==============================================
// Health Connect 데이터 진단 카드
// — "왜 일부 날짜만 표시되나" 사용자 의문 해소
// ==============================================
@Composable
private fun HCDataDiagnosticCard(
    stepsByDay: Map<java.time.LocalDate, Int>,
    period: ActivityPeriod
) {
    val today = java.time.LocalDate.now()
    val from = today.minusDays((period.days - 1).toLong())
    val daysWithData = stepsByDay.count { it.value > 0 }
    val oldestDate = stepsByDay.filter { it.value > 0 }.keys.minOrNull()
    val newestDate = stepsByDay.filter { it.value > 0 }.keys.maxOrNull()

    val (statusColor, statusIcon, statusText) = when {
        daysWithData == period.days -> Triple(Success, "✅", "모든 데이터 수신됨")
        daysWithData >= period.days / 2 -> Triple(ScoreOk, "⚠️", "일부 날짜 데이터 누락")
        else -> Triple(ErrorRed, "❌", "데이터 대부분 없음")
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = statusColor.copy(alpha = 0.1f)),
        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(statusIcon, fontSize = 18.sp)
                Spacer(Modifier.width(8.dp))
                Text("Health Connect 진단", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            }
            Spacer(Modifier.height(8.dp))
            Text(statusText, fontSize = 13.sp, color = statusColor, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            // 받은 데이터 통계
            DiagRow("• 요청 기간", "${from.monthValue}/${from.dayOfMonth} ~ ${today.monthValue}/${today.dayOfMonth} (${period.days}일)")
            DiagRow("• 데이터 받은 일수", "$daysWithData / ${period.days}일")
            if (oldestDate != null && newestDate != null) {
                DiagRow("• 가장 오래된 기록", "${oldestDate.monthValue}/${oldestDate.dayOfMonth}")
                DiagRow("• 가장 최근 기록", "${newestDate.monthValue}/${newestDate.dayOfMonth}")
            }
            if (daysWithData < period.days) {
                Spacer(Modifier.height(8.dp))
                Surface(color = BgCard, shape = RoundedCornerShape(8.dp)) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text("💡 참고 안내", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text(
                            "1. Samsung Health → Health Connect 동기화가 일부 기간 안 됨\n" +
                            "2. \"과거 데이터 접근\" 권한 미허용\n" +
                            "3. 그 날 워치 미착용",
                            fontSize = 11.sp,
                            color = TextSecondary,
                            lineHeight = 16.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "→ 설정 → 삼성헬스 동기화 → 권한 화면에서\n   \"과거 데이터 접근\" 도 허용해주세요",
                            fontSize = 11.sp,
                            color = Primary,
                            fontWeight = FontWeight.SemiBold,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, fontSize = 12.sp, color = TextSecondary, modifier = Modifier.weight(1f))
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
    }
}

// ==============================================
// 일일 모드 컴포넌트들 (오늘 시간대별 데이터)
// ==============================================

/**
 * 날짜 네비게이션 바 — 양쪽 화살표로 ±1일 (오늘 넘어가지 못함).
 * 일일 모드의 모든 디테일 화면(걸음/수면/스트레스)에서 공통 사용.
 */
@Composable
fun DateNavigatorBar(
    date: java.time.LocalDate,
    onChange: (java.time.LocalDate) -> Unit
) {
    val today = remember { java.time.LocalDate.now() }
    val canNext = date.isBefore(today)
    val label = when (date) {
        today -> "오늘 · ${date.monthValue}월 ${date.dayOfMonth}일 (${date.dayOfWeek.koreanLabel()})"
        today.minusDays(1) -> "전날 · ${date.monthValue}월 ${date.dayOfMonth}일 (${date.dayOfWeek.koreanLabel()})"
        else -> "${date.monthValue}월 ${date.dayOfMonth}일 (${date.dayOfWeek.koreanLabel()})"
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        border = BorderStroke(1.dp, Divider)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { onChange(date.minusDays(1)) }) {
                Icon(androidx.compose.material.icons.Icons.Filled.ChevronLeft, contentDescription = "이전 날", tint = TextPrimary)
            }
            com.dangbomcare.app.ui.components.AutoSizeSingleLineText(
                label,
                maxFontSize = 15.sp, minFontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            IconButton(
                onClick = { if (canNext) onChange(date.plusDays(1)) },
                enabled = canNext
            ) {
                Icon(
                    androidx.compose.material.icons.Icons.Filled.ChevronRight,
                    contentDescription = "다음 날",
                    tint = if (canNext) TextPrimary else TextDisabled
                )
            }
        }
    }
}

private fun java.time.DayOfWeek.koreanLabel(): String = when (this) {
    java.time.DayOfWeek.MONDAY -> "월"
    java.time.DayOfWeek.TUESDAY -> "화"
    java.time.DayOfWeek.WEDNESDAY -> "수"
    java.time.DayOfWeek.THURSDAY -> "목"
    java.time.DayOfWeek.FRIDAY -> "금"
    java.time.DayOfWeek.SATURDAY -> "토"
    java.time.DayOfWeek.SUNDAY -> "일"
}

// 코덱스 H: 접근성용 한국어 표현 헬퍼 (TalkBack/스크린 리더 — 시니어 사용자 친화)
private fun java.time.LocalDate.a11yDate(): String =
    "${monthValue}월 ${dayOfMonth}일 ${dayOfWeek.koreanLabel()}요일"

private fun minutesToA11y(min: Int): String = "${min / 60}시간 ${min % 60}분"

private fun stepsA11y(v: Int): String = "%,d걸음".format(v)

/**
 * 활동 — 선택한 날짜의 총 걸음 요약 카드.
 * [260726] 데이터는 상위(DetailActivityScreen)에서 1회 조회해 내려받는다 (카드별 중복 HC 조회 제거).
 * @param steps null = 미측정/권한 없음/조회 실패 (0보와 반드시 구분해서 표기)
 */
@Composable
private fun ActivityDaySummaryCard(steps: Int?, loading: Boolean) {
    // [260726] null(데이터 없음)과 0(정말 안 걸음)을 구분.
    //   기존엔 `totalSteps ?: 0` 이라 HC 미가용·권한 없음·조회 실패가 "0 / 8000보 · 0.00 km · 0 kcal"로 보였고,
    //   실제로 8,000보 걸은 환자가 "전혀 안 걸었다"로 오독했다. (HomeScreen TodayActivityCard 는 "동기화 필요"로 구분)
    //   ※ Repository 는 걸음 집계에 takeIf { it > 0 } 을 걸어 0도 null 로 내리므로 "0보"는 표시 대상이 아니다.
    val hasData = steps != null
    val stepCount = steps ?: 0
    val goal = 8000
    val ratio = if (hasData) (stepCount.toFloat() / goal).coerceIn(0f, 1f) else 0f

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        border = BorderStroke(1.dp, Divider)
    ) {
        Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(40.dp), color = Primary)
            } else {
                Box(contentAlignment = Alignment.Center) {
                    Canvas(modifier = Modifier.size(180.dp)) {
                        val stroke = 14.dp.toPx()
                        val pad = stroke / 2
                        drawArc(
                            color = Color(0xFFE4E2E1), startAngle = -90f, sweepAngle = 360f, useCenter = false,
                            topLeft = androidx.compose.ui.geometry.Offset(pad, pad),
                            size = androidx.compose.ui.geometry.Size(this.size.width - stroke, this.size.height - stroke),
                            style = Stroke(width = stroke, cap = StrokeCap.Round)
                        )
                        drawArc(
                            color = Primary, startAngle = -90f, sweepAngle = ratio * 360f, useCenter = false,
                            topLeft = androidx.compose.ui.geometry.Offset(pad, pad),
                            size = androidx.compose.ui.geometry.Size(this.size.width - stroke, this.size.height - stroke),
                            style = Stroke(width = stroke, cap = StrokeCap.Round)
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🚶", fontSize = 28.sp)
                        Text(
                            if (hasData) "%,d".format(stepCount) else "—",
                            fontSize = 36.sp,
                            fontWeight = FontWeight.Black,
                            color = if (hasData) Primary else TextSecondary
                        )
                        Text(
                            if (hasData) "/ ${goal}보" else "데이터 없음",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (hasData) {
                    Text("${(ratio * 100).toInt()}% 달성", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Primary)
                } else {
                    Text(
                        "이 날 걸음 데이터를 가져오지 못했어요 — 삼성헬스 동기화 후 다시 확인해주세요",
                        fontSize = 12.sp, color = TextSecondary, lineHeight = 17.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    StatItem(if (hasData) "%.2f km".format(stepCount * 0.00079) else "—", "거리")
                    Box(modifier = Modifier.width(1.dp).height(36.dp).background(Divider))
                    StatItem(if (hasData) "${(stepCount * 0.033).toInt()} kcal" else "—", "칼로리")
                }
            }
        }
    }
}

/**
 * 활동 — 선택한 날짜의 시간대별 걸음 막대그래프.
 * [260726] 데이터는 상위에서 1회 조회해 내려받는다. hourly == null = 시간대 데이터 없음/조회 실패.
 */
@Composable
private fun ActivityDayHourlyCard(
    date: java.time.LocalDate,
    hourlyData: List<Int>?,
    loading: Boolean,
) {
    var selectedHour by remember(date) { mutableStateOf<Int?>(null) }
    // [260726] null(조회 실패/시간대 데이터 없음)과 실제 0을 구분 — null 이면 빈 막대그래프 대신 안내를 띄운다.
    val hasHourly = hourlyData != null
    val hourly = hourlyData ?: List(24) { 0 }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        border = BorderStroke(1.dp, Divider)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("시간대별 걸음 수", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Spacer(Modifier.height(8.dp))
            // 막대 터치 시 툴팁 표시 — 큰 글자 모드 호환 위해 heightIn (코덱스 H)
            // [260726] 시간대 데이터가 없으면 터치해도 보여줄 값이 없으므로 안내 줄 자체를 감춘다.
            if (hasHourly) Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 36.dp)
                    .semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = selectedHour?.let { h ->
                            val cnt = hourly.getOrNull(h) ?: 0
                            "${formatHourLabel(h)}부터 ${formatHourLabel((h + 1) % 24)}까지 ${stepsA11y(cnt)}"
                        } ?: "막대를 터치하거나 좌우로 드래그하면 시간대별 걸음 수가 보입니다"
                    },
                contentAlignment = Alignment.CenterStart
            ) {
                val sel = selectedHour
                if (sel != null) {
                    val cnt = hourly.getOrNull(sel) ?: 0
                    val startLabel = formatHourLabel(sel)
                    val endLabel = formatHourLabel((sel + 1) % 24)
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Primary.copy(alpha = 0.15f)
                    ) {
                        Text(
                            "$startLabel - $endLabel · ${"%,d".format(cnt)}걸음",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = PrimaryDark,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                } else {
                    Text(
                        "막대를 터치하면 시간대별 걸음 수가 보여요",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            if (loading) {
                LoadingIndicator(modifier = Modifier.height(120.dp))
            } else if (!hasHourly) {
                // [260726] 조회 실패/미측정을 '0으로 가득 찬 빈 그래프'로 그리면 "하루 종일 안 걸었다"로 오독된다.
                Text(
                    "이 날 시간대별 걸음 데이터가 없어요 — 삼성헬스 동기화 후 다시 확인해주세요",
                    fontSize = 12.sp, color = TextSecondary, lineHeight = 17.sp
                )
            } else {
                // [260707] Y축 동적 스케일 — 실제 최댓값을 500 단위로 올림(항상 헤드룸 확보), 최소 1000.
                //   가장 활발한 시간대 막대도 최상단 눈금선 아래에 들어와 '눈금선과 막대가 안 맞는' 문제 해소.
                val maxHour = maxOf(1000, ((hourly.maxOrNull() ?: 0) / 500 + 1) * 500)
                val today = java.time.LocalDate.now()
                val now = if (date == today) java.time.LocalTime.now().hour else 24
                // 코덱스 H2: 접근성 — 차트 컨테이너에 정적 contentDescription + 동적 stateDescription
                val totalSteps = hourly.sum()
                val maxHourIdx = hourly.indices.maxByOrNull { hourly[it] } ?: 0
                val chartA11y = "시간대별 걸음 차트. 총 ${stepsA11y(totalSteps)}. " +
                    if (totalSteps > 0) "가장 많이 걸은 시간 ${formatHourLabel(maxHourIdx)}." else "측정된 걸음이 없습니다."
                val stateA11y = selectedHour?.let { h ->
                    "${formatHourLabel(h)}부터 ${formatHourLabel((h + 1) % 24)}까지 ${stepsA11y(hourly.getOrNull(h) ?: 0)}"
                } ?: "선택된 시간이 없습니다."
                // [v1.0 피드백] 삼성헬스 스타일 — 막대 영역(왼쪽 weight) | 숫자 영역(오른쪽 고정폭) 분리.
                //   숫자가 막대/점선과 절대 안 겹침. pointerInput은 막대 영역에만 → 드래그 좌표 정확.
                // [260707 재작업] 막대·눈금선·숫자를 하나의 Canvas에서 동일 좌표식 yOf(v)로 렌더 → 정렬 수학적 보장.
                //   (기존: 점선=padding / 막대=fillMaxHeight / 숫자=offset 3원화 → 미세 어긋남 + 천장 라벨 7dp 밀림)
                val gridLevels = (maxHour / 500 downTo 1).map { it * 500 }
                val emphasisLevel = gridLevels.first()   // 최상단(천장) = 스케일 기준, 강조
                val labelMargin = 44.dp
                val primaryArgb = Primary.toArgb()
                val secondaryArgb = TextSecondary.toArgb()
                androidx.compose.foundation.Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .semantics {
                            contentDescription = chartA11y
                            stateDescription = stateA11y
                        }
                        .pointerInput(hourly.size) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val bw = (size.width - labelMargin.toPx()).coerceAtLeast(1f)
                                selectedHour = hourIndexFromX(down.position.x, bw, hourly.size)
                                drag(down.id) { change ->
                                    selectedHour = hourIndexFromX(change.position.x, bw, hourly.size)
                                    change.consume()
                                }
                            }
                        }
                ) {
                    val h = size.height
                    val plotTop = 11.dp.toPx()                        // 천장 라벨 잘림 방지 위 여백
                    val plotH = (h - plotTop).coerceAtLeast(1f)
                    val barsW = (size.width - labelMargin.toPx()).coerceAtLeast(1f)
                    val txtPx = 10.sp.toPx()
                    // 값 v → y좌표 (눈금선·막대 공통). v=maxHour → plotTop, v=0 → h.
                    fun yOf(v: Float): Float = plotTop + plotH * (1f - v / maxHour)

                    // 눈금선(점선) + 우측 숫자 — 같은 y 사용 → 항상 정렬
                    gridLevels.forEach { level ->
                        val y = yOf(level.toFloat())
                        val emph = level == emphasisLevel
                        val lineColor = if (emph) Primary.copy(alpha = 0.5f) else TextSecondary.copy(alpha = 0.35f)
                        var sx = 0f
                        val dash = 6f; val gap = 5f
                        while (sx < barsW) {
                            drawLine(
                                color = lineColor,
                                start = androidx.compose.ui.geometry.Offset(sx, y),
                                end = androidx.compose.ui.geometry.Offset((sx + dash).coerceAtMost(barsW), y),
                                strokeWidth = 1.dp.toPx()
                            )
                            sx += dash + gap
                        }
                        drawIntoCanvas { c ->
                            c.nativeCanvas.drawText(
                                "%,d".format(level),
                                size.width,
                                y + txtPx / 3f,   // 선 중앙에 숫자 수직 정렬
                                android.graphics.Paint().apply {
                                    isAntiAlias = true
                                    textAlign = android.graphics.Paint.Align.RIGHT
                                    color = if (emph) primaryArgb else secondaryArgb
                                    textSize = txtPx
                                    isFakeBoldText = emph
                                }
                            )
                        }
                    }

                    // 선택 시간대 세로 띠 (막대 영역 전체 높이)
                    val colW = barsW / hourly.size
                    selectedHour?.let { sel ->
                        drawRect(
                            color = Slate.copy(alpha = 0.15f),
                            topLeft = androidx.compose.ui.geometry.Offset(sel * colW, 0f),
                            size = androidx.compose.ui.geometry.Size(colW, h)
                        )
                    }

                    // 막대 — 상단 = yOf(count) → 눈금선과 동일 스케일이라 값이 선에 정확히 대응
                    val barW = colW * 0.62f
                    hourly.forEachIndexed { hour, count ->
                        if (count > 0) {
                            val top = yOf(count.toFloat())
                            val left = hour * colW + (colW - barW) / 2f
                            val color = if (hour == now) Primary else Primary.copy(alpha = 0.7f)
                            drawRoundRect(
                                color = color,
                                topLeft = androidx.compose.ui.geometry.Offset(left, top),
                                size = androidx.compose.ui.geometry.Size(barW, (h - top).coerceAtLeast(2f)),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx(), 2.dp.toPx())
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                // [v1.0 피드백] X축 라벨도 막대 영역(우측 숫자여백 labelMargin 제외)과 정렬 → 23시가 마지막 막대 위에 오게
                Row(modifier = Modifier.fillMaxWidth().padding(end = labelMargin), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("0시", fontSize = 9.sp, color = TextSecondary)
                    Text("6시", fontSize = 9.sp, color = TextSecondary)
                    Text("12시", fontSize = 9.sp, color = TextSecondary)
                    Text("18시", fontSize = 9.sp, color = TextSecondary)
                    Text("23시", fontSize = 9.sp, color = TextSecondary)
                }
                Spacer(Modifier.height(8.dp))
                // [Codex 검토 위험] axisMaxHour(=maxHour, 1200 보정됨)와 실제 peak값을 분리.
                // 실제 hourly의 최댓값이 1200 미만이면 hourly.indexOf(maxHour)가 -1이 되어 "가장 활발: -1시"가 됨.
                val rawPeak = hourly.max().takeIf { it > 0 } ?: 0
                val peakHour = if (rawPeak > 0) hourly.indexOf(rawPeak) else -1
                Text(
                    "총 ${"%,d".format(hourly.sum())}보" + if (peakHour >= 0) " · 가장 활발: ${peakHour}시" else "",
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextSecondary
                )
            }
        }
    }
}

/** 막대 그래프 영역의 x 좌표를 시간 인덱스로 변환 (드래그 슬라이드용). */
private fun hourIndexFromX(x: Float, width: Float, count: Int): Int {
    if (width <= 0f || count <= 0) return 0
    return ((x.coerceIn(0f, width) / width) * count).toInt().coerceIn(0, count - 1)
}

/** "오전 7:00", "오후 3:00" 같은 한국어 시간 라벨. */
private fun formatHourLabel(hour: Int): String {
    val h = hour.coerceIn(0, 23)
    return when {
        h == 0 -> "오전 12:00"
        h < 12 -> "오전 ${h}:00"
        h == 12 -> "오후 12:00"
        else -> "오후 ${h - 12}:00"
    }
}

/**
 * 활동 — 오늘 총 걸음 요약 카드.
 */
@Composable
private fun ActivityTodaySummaryCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var todaySteps by remember { mutableStateOf<Int?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        try {
            val repo = com.dangbomcare.app.data.health.HealthConnectRepository(context)
            if (repo.isSdkAvailable()) {
                val data = repo.fetchWatchData(java.time.LocalDate.now())
                todaySteps = data.steps
            }
        } catch (e: Exception) { /* keep null */ }
        loading = false
    }

    val steps = todaySteps ?: 0
    val goal = 8000
    val ratio = (steps.toFloat() / goal).coerceIn(0f, 1f)
    val today = java.time.LocalDate.now()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        border = BorderStroke(1.dp, Divider)
    ) {
        Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${today.monthValue}월 ${today.dayOfMonth}일 (오늘)", fontSize = 12.sp, color = TextSecondary)
            Spacer(Modifier.height(12.dp))
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(40.dp), color = Primary)
            } else {
                Box(contentAlignment = Alignment.Center) {
                    Canvas(modifier = Modifier.size(180.dp)) {
                        val stroke = 14.dp.toPx()
                        val pad = stroke / 2
                        drawArc(
                            color = Color(0xFFE4E2E1), startAngle = -90f, sweepAngle = 360f, useCenter = false,
                            topLeft = androidx.compose.ui.geometry.Offset(pad, pad),
                            size = androidx.compose.ui.geometry.Size(this.size.width - stroke, this.size.height - stroke),
                            style = Stroke(width = stroke, cap = StrokeCap.Round)
                        )
                        drawArc(
                            color = Primary, startAngle = -90f, sweepAngle = ratio * 360f, useCenter = false,
                            topLeft = androidx.compose.ui.geometry.Offset(pad, pad),
                            size = androidx.compose.ui.geometry.Size(this.size.width - stroke, this.size.height - stroke),
                            style = Stroke(width = stroke, cap = StrokeCap.Round)
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🚶", fontSize = 28.sp)
                        Text("%,d".format(steps), fontSize = 36.sp, fontWeight = FontWeight.Black, color = Primary)
                        Text("/ ${goal}보", fontSize = 12.sp, color = TextSecondary)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("${(ratio * 100).toInt()}% 달성", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Primary)
                Spacer(Modifier.height(12.dp))
                // 거리/칼로리 추정
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    StatItem("%.2f km".format(steps * 0.00079), "거리")
                    Box(modifier = Modifier.width(1.dp).height(36.dp).background(Divider))
                    StatItem("${(steps * 0.033).toInt()} kcal", "칼로리")
                }
            }
        }
    }
}

// (ActivityTodayHourlyCard 제거 — 미호출 dead code. ActivityDayHourlyCard 로 대체됨.)

/**
 * 수면 — 선택한 날짜의 수면 시간선 카드.
 */
@Composable
private fun SleepDayTimelineCard(date: java.time.LocalDate) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var timeline by remember(date) { mutableStateOf<com.dangbomcare.app.data.health.HealthConnectRepository.SleepTimeline?>(null) }
    var loading by remember(date) { mutableStateOf(true) }
    var deepMin by remember(date) { mutableStateOf(0) }
    var lightMin by remember(date) { mutableStateOf(0) }
    var remMin by remember(date) { mutableStateOf(0) }
    var awakeMin by remember(date) { mutableStateOf(0) }
    var totalMin by remember(date) { mutableStateOf(0) }

    LaunchedEffect(date) {
        loading = true
        try {
            val repo = com.dangbomcare.app.data.health.HealthConnectRepository(context)
            if (repo.isSdkAvailable()) {
                timeline = repo.fetchSleepTimeline(date)
                val data = repo.fetchWatchData(date)
                totalMin = data.sleepTotalMinutes ?: 0
                deepMin = data.sleepDeepMinutes ?: 0
                lightMin = data.sleepLightMinutes ?: 0
                remMin = data.sleepRemMinutes ?: 0
                awakeMin = data.sleepAwakeMinutes ?: 0
            }
        } catch (e: Exception) { /* */ }
        loading = false
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        border = BorderStroke(1.dp, Divider)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "${date.monthValue}/${date.dayOfMonth} 밤 수면",
                fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary
            )
            Spacer(Modifier.height(8.dp))
            if (loading) {
                LoadingIndicator(modifier = Modifier.height(120.dp))
            } else if (timeline == null || totalMin == 0) {
                WatchAbsentNotice("이 날", subject = "수면")
            } else {
                val tl = timeline!!
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${totalMin / 60}", fontSize = 36.sp, fontWeight = FontWeight.Black, color = TextPrimary)
                    Text("시간 ", fontSize = 18.sp, color = TextPrimary, modifier = Modifier.padding(bottom = 4.dp))
                    Text("${totalMin % 60}", fontSize = 32.sp, fontWeight = FontWeight.Black, color = TextPrimary)
                    Text("분", fontSize = 16.sp, color = TextSecondary, modifier = Modifier.padding(bottom = 6.dp))
                }
                Spacer(Modifier.height(10.dp))
                fun mintoStr(min: Int): String {
                    var m = min; while (m < 0) m += 24 * 60; m %= (24 * 60)
                    val h = m / 60; val mm = m % 60
                    val amPm = if (h < 12) "오전" else "오후"
                    val h12 = ((h + 11) % 12) + 1
                    return "$amPm $h12:${"%02d".format(mm)}"
                }
                // [사용자 피드백] "언제부터 언제까지 잤는지" 명확히 — 박스로 강조 + 잠든/일어난 시각 라벨
                Surface(
                    color = BgCardSoft,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("🌙", fontSize = 13.sp)
                                Spacer(Modifier.width(4.dp))
                                Text("잠든 시각", fontSize = 11.sp, color = TextSecondary)
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                mintoStr(tl.sleepStartMin),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        }
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(34.dp)
                                .background(Divider)
                        )
                        Column(modifier = Modifier.weight(1f).padding(start = 14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("☀️", fontSize = 13.sp)
                                Spacer(Modifier.width(4.dp))
                                Text("일어난 시각", fontSize = 11.sp, color = TextSecondary)
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                mintoStr(tl.sleepEndMin),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                // 사용자 피드백 — "수면 흐름을 표시해야 됨. 10시14분에 자다 깬 시간이었다가 10시50분에 얕은 잠 상태고. 이런 흐름"
                // → 표준 hypnogram (4-row track 차트): 시간축에 따라 단계가 어떻게 변했는지 시각화.
                Text("수면 흐름", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Spacer(Modifier.height(2.dp))
                Text("시간이 지나면서 어떤 단계였는지 보여드려요", fontSize = 11.sp, color = TextSecondary)
                Spacer(Modifier.height(10.dp))

                val timelineStart = tl.sleepStartMin
                val timelineEnd = tl.sleepEndMin

                val stagedTotal = deepMin + remMin + lightMin + awakeMin
                // ── [260729 통합검증 P1] 단계 미분류(SLEEPING) 분량 ────────────────────
                //   260729에 SLEEPING 을 총 수면시간에 포함시키면서 평균 분포(L1346·L1375),
                //   막대 툴팁(L2219), hypnogram, 도움말(L3384)에는 전부 반영했는데
                //   **일일 상세(이 카드)만 빠져 있었다.**
                //   그 결과 SLEEPING 위주로 기록하는 기기(실측: SLEEPING 425분 + AWAKE 25분)에서
                //     · 상단 총합은 "7시간 30분"인데
                //     · "수면 단계 분포" 4행의 합이 5% → 나머지 95%를 설명하는 줄이 없음
                //     · SleepSummaryCard 는 deepRatio=0 → "체력 회복 잠이 부족해 오늘 피로할 수 있어요"
                //       라는 **거짓 단정**(단계 정보가 없을 뿐인데 부족하다고 말함)
                //
                //   ⚠️ 값은 파생으로 구한다 — WatchData 모델에 필드를 추가하지 않는다.
                //     HealthConnectRepository L553: total = 4단계 합 + unstaged 이므로
                //     unstaged == total - 4단계 합 으로 **정확히** 복원된다.
                //     삼성 SDK 경로는 total = 4단계 합(SLEEPING enum 없음)이라 항상 0 → 기존 화면 무변화.
                //     단계 분해가 아예 없어 세션 구간으로 폴백한 날은 전량이 여기로 잡히는데,
                //     그게 사실("잠은 잤고 단계는 모른다")이라 오히려 정확해진다.
                val unstagedMin = (totalMin - stagedTotal).coerceAtLeast(0)

                // 접근성 요약 (스크린 리더용) — 주간 막대 툴팁(L2219)과 같은 방식으로 조건부 삽입.
                //   unstagedMin == 0 이면 문자열이 기존과 문자 단위로 동일하다.
                val timelineA11y = buildString {
                    append("수면 흐름 차트. ")
                    append("${mintoStr(timelineStart)}부터 ${mintoStr(timelineEnd)}까지, ")
                    append("총 ${minutesToA11y(totalMin)}. ")
                    append("체력 회복 잠 ${minutesToA11y(deepMin)}, ")
                    append("뇌 휴식하는 잠 ${minutesToA11y(remMin)}, ")
                    append("얕은 잠 ${minutesToA11y(lightMin)}, ")
                    append("자다 깬 시간 ${minutesToA11y(awakeMin)}")
                    if (unstagedMin > 0) append(", 단계가 기록되지 않은 잠 ${minutesToA11y(unstagedMin)}")
                    append(".")
                }

                if (stagedTotal == 0 && unstagedMin == 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(BgCardSoft)
                            .semantics { contentDescription = "수면 단계 세부 데이터가 없습니다" },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("단계 세부 데이터 없음", fontSize = 12.sp, color = TextSecondary)
                    }
                } else if (stagedTotal == 0) {
                    // 총 수면시간은 있는데 단계가 하나도 안 잡힌 날 — 흐름 차트는 그릴 게 없다.
                    //   "데이터 없음"이라고만 하면 위의 큰 총합과 어긋나 보이므로 이유를 말해준다.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(BgCardSoft)
                            .semantics {
                                contentDescription =
                                    "잠든 시간은 기록됐지만 수면 단계는 기록되지 않았습니다"
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "잠든 시간은 기록됐지만\n단계별 기록은 없어요",
                            fontSize = 12.sp,
                            color = TextSecondary,
                            textAlign = TextAlign.Center,
                            lineHeight = 17.sp
                        )
                    }
                } else {
                    SleepHypnogramChart(
                        segments = tl.segments,
                        timelineStart = timelineStart,
                        timelineEnd = timelineEnd,
                        a11yDesc = timelineA11y
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text("수면 단계 분포", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextSecondary)
                Spacer(Modifier.height(8.dp))
                // [260623] 순서 깸→렘→얕은→깊은 / [260705 R5] 한 줄 자동축소 라벨 (라인 정렬 통일)
                SleepStagePercentRow(SleepAwakeColor, "수면 중 깸 (자다 깬 시간)", awakeMin, totalMin)
                SleepStagePercentRow(SleepRemColor, "렘 수면 (뇌 휴식하는 잠)", remMin, totalMin)
                SleepStagePercentRow(SleepLightColor, "얕은 수면", lightMin, totalMin)
                SleepStagePercentRow(SleepDeepColor, "깊은 수면 (체력 회복 잠)", deepMin, totalMin)
                // 0-가드는 주간 평균 박스(L1375)와 동일 — 대부분 기기는 0분이라 없으면
                //   전 사용자에게 "0% / 0시간 0분" 한 줄이 늘 붙는다.
                if (unstagedMin > 0) {
                    SleepStagePercentRow(
                        SleepUnstagedColor, SLEEP_UNSTAGED_LABEL, unstagedMin, totalMin
                    )
                }
                // [260705 R4] 한줄평 박스는 분포 아래로 이동
                Spacer(Modifier.height(12.dp))
                SleepSummaryCard(
                    deepMin = deepMin, remMin = remMin, awakeMin = awakeMin,
                    totalMin = totalMin, unstagedMin = unstagedMin
                )
            }
        }
    }
}

// (SleepTodayTimelineCard 제거 — 미호출 dead code. SleepDayTimelineCard 로 대체됨.
//  단일 연속 타임라인 패턴은 SleepDayTimelineCard 안으로 이식 완료.)

/**
 * 수면 한 줄 요약 — 깊은잠/REM/깸 비율로 룰 기반 평가.
 * 당봄 피드백 #9: "어제는 체력 회복 잠이 평소보다 많아 혈당 관리에 좋은 밤이었습니다"
 */
@Composable
fun SleepSummaryCard(
    deepMin: Int,
    remMin: Int,
    awakeMin: Int,
    totalMin: Int,
    // [260729 통합검증 P1] 단계 미분류(SLEEPING) 분량. 기본 0 = 기존 호출부 동작 무변화.
    unstagedMin: Int = 0,
) {
    if (totalMin <= 0) return
    // ⚠️ 질(質) 판정의 분모는 **단계가 실제로 판정된 시간**이어야 한다.
    //   총 수면시간에는 단계 미분류(SLEEPING)가 섞여 있어서, 그대로 분모로 쓰면
    //   SLEEPING 위주로 기록하는 기기(실측: 450분 중 425분이 SLEEPING)에서 deepRatio 가
    //   구조적으로 0에 가까워져 "체력 회복 잠이 부족해 오늘 피로할 수 있어요"라는
    //   거짓 단정이 매일 뜬다 — 부족한 게 아니라 **측정이 안 된 것**이다.
    val judgeBase = (totalMin - unstagedMin).coerceAtLeast(0)
    // 단계가 판정된 구간이 총 수면의 절반도 안 되면 질을 논할 근거가 없다 → 시간만 말한다.
    val stageDataThin = judgeBase * 2 < totalMin
    val deepRatio = if (judgeBase > 0) deepMin.toFloat() / judgeBase else 0f
    val awakeRatio = if (judgeBase > 0) awakeMin.toFloat() / judgeBase else 0f
    val totalHours = totalMin / 60f
    if (stageDataThin) {
        val (icon2, message2, color2) = when {
            totalHours < 6f ->
                Triple("⚠️", "수면 시간이 부족했어요. 오늘은 23시 전 취침을 권해드려요.", SleepAwakeColor)
            totalHours > 10f ->
                Triple("💤", "길게 주무셨어요. 너무 긴 수면도 컨디션에 영향을 줄 수 있어요.", SleepDeepColor)
            else ->
                Triple("😴", "수면 시간은 충분했어요. 이 날은 단계별 기록이 없어 수면의 질은 알 수 없어요.", SleepLightColor)
        }
        SleepSummaryBox(icon2, message2, color2)
        return
    }
    val qualityGood = deepRatio >= 0.13f && awakeRatio < 0.15f   // 적정 체력 회복 잠 + 적은 깸 = 질 좋음
    // [수면 단계 용어 통일] "깊은 잠" 표현 X → "체력 회복 잠"으로 통일
    val (icon, message, color) = when {
        // 시간 부족(<6h) + 질 좋음 → 분리 표현
        totalHours < 6f && qualityGood ->
            Triple("😴", "수면 시간이 부족해요. 하지만 수면의 질은 좋았어요. 오늘은 일찍 자보세요.", SleepLightColor)
        // [260728 ①] 시간 부족 + 질 나쁨 → 원인이 '깸'인지 '체력 회복 잠 부족'인지 갈라서 말한다.
        //   기존엔 !qualityGood 만 보고 무조건 "체력 회복 잠도 적었어요"를 붙였는데,
        //   qualityGood 은 (deepRatio >= 0.13 && awakeRatio < 0.15) 이라 **깸이 많아서** 탈락한 날에도
        //   체력 회복 잠이 충분한데 적었다고 단정하는 거짓 문구가 나왔다.
        //   → deep 비율이 실제로 낮을 때만 체력 회복 잠을 언급한다.
        totalHours < 6f && deepRatio >= 0.13f ->
            Triple("⚠️", "수면 시간이 부족하고 자다 깬 시간이 많았어요. 오늘은 23시 전 취침을 권해드려요.", SleepAwakeColor)
        totalHours < 6f ->
            Triple("⚠️", "수면 시간이 부족하고 체력 회복 잠도 적었어요. 오늘은 23시 전 취침을 권해드려요.", SleepAwakeColor)
        // 시간 과다(>10h) + 질 좋음
        totalHours > 10f && qualityGood ->
            Triple("💤", "푹 주무셨네요. 다만 너무 긴 수면도 컨디션에 영향을 줄 수 있어요.", SleepDeepColor)
        // 시간 OK + 자주 깸 → 깸 강조
        awakeRatio >= 0.2f ->
            Triple("⚠️", "자주 깨셨네요. 저녁 카페인이나 스트레스를 점검해보세요.", SleepAwakeColor)
        // 충분 + 매우 좋음
        deepRatio >= 0.18f && awakeRatio < 0.1f ->
            Triple("✨", "체력 회복 잠이 충분해 혈당 관리에 좋은 밤이었어요.", SleepDeepColor)
        // 양호
        deepRatio >= 0.15f ->
            Triple("👍", "수면의 질이 양호해요. 이대로 유지해주세요.", SleepDeepColor)
        // 체력 회복 잠 부족
        deepRatio < 0.1f ->
            Triple("💡", "체력 회복 잠이 부족해 오늘 피로할 수 있어요.", SleepRemColor)
        else ->
            Triple("😴", "보통 수면 패턴이에요.", SleepLightColor)
    }
    SleepSummaryBox(icon, message, color)
}

/** 수면 한줄평 박스의 렌더 — 수치·여백은 260705 확정본 그대로. */
@Composable
private fun SleepSummaryBox(icon: String, message: String, color: Color) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f)),
        border = BorderStroke(1.dp, color.copy(alpha = 0.3f))
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(icon, fontSize = 22.sp)
            Spacer(Modifier.width(10.dp))
            Text(message, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary, lineHeight = 18.sp)
        }
    }
}

/** 수면 단계 도움말 다이얼로그 (피드백 #9 — ? 버튼) */
@Composable
fun SleepStageHelpDialog(stageName: String, onDismiss: () -> Unit) {
    // [사용자 피드백] 삼성헬스 "수면 점수에 영향을 주는 요인" 페이지 카드 디자인 그대로 적용.
    //   • 큰 헤더 (단계 이름 + 영문)
    //   • 권장 시간 박스 (강조 색)
    //   • "어떤 단계인가요?" - 긴 설명 단락
    //   • "수면 점수에 어떤 영향을 주나요?" - 긴 설명 단락
    //   • "도움이 되는 습관" - bullet list (4~5개)
    val info = sleepStageInfo(stageName)
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = BgCard,
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .heightIn(max = 640.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
                // ── 헤더: 색 원 + 이모지 + 단계 이름 + 영문 ──
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(info.color.copy(alpha = 0.18f))
                            .border(2.dp, info.color.copy(alpha = 0.4f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(info.emoji, fontSize = 26.sp)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            info.label,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Black,
                            color = TextPrimary
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            info.englishName,
                            fontSize = 12.sp,
                            color = info.color,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))

                // ── 권장 시간 박스 (사진의 강조 카드 느낌) ──
                Surface(
                    color = info.color.copy(alpha = 0.10f),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, info.color.copy(alpha = 0.25f))
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("⏱", fontSize = 14.sp)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "권장 시간",
                                fontSize = 12.sp,
                                color = TextSecondary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            info.recommendedRange,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black,
                            color = info.color
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            info.recommendedDesc,
                            fontSize = 12.sp,
                            color = TextSecondary,
                            lineHeight = 17.sp
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))

                // ── 어떤 단계인가요? ──
                StageDialogSection(
                    title = "어떤 단계인가요?",
                    emoji = "📖",
                    body = info.whatIs
                )

                Spacer(Modifier.height(16.dp))

                // ── 수면 점수에 어떤 영향을 주나요? ──
                StageDialogSection(
                    title = "혈당·건강에 어떤 영향을 주나요?",
                    emoji = "💡",
                    body = info.impact
                )

                Spacer(Modifier.height(16.dp))

                // ── 도움이 되는 습관 (bullet list) ──
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("✅", fontSize = 14.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "도움이 되는 습관",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = info.color.copy(alpha = 0.06f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp).fillMaxWidth()) {
                        info.tips.forEachIndexed { idx, tip ->
                            Row(modifier = Modifier.padding(vertical = 5.dp)) {
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .background(info.color),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "${idx + 1}",
                                        fontSize = 11.sp,
                                        color = androidx.compose.ui.graphics.Color.White,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    tip,
                                    fontSize = 13.sp,
                                    color = TextPrimary,
                                    lineHeight = 19.sp,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) {
                    Text("확인했어요", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun StageDialogSection(title: String, emoji: String, body: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(emoji, fontSize = 14.sp)
        Spacer(Modifier.width(6.dp))
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
    }
    Spacer(Modifier.height(6.dp))
    Text(
        body,
        fontSize = 13.sp,
        color = TextPrimary,
        lineHeight = 20.sp
    )
}

private data class SleepStageInfo(
    val label: String,
    val englishName: String,
    val color: Color,
    val emoji: String,
    val recommendedRange: String,
    val recommendedDesc: String,
    val whatIs: String,
    val impact: String,
    val tips: List<String>
)

private fun sleepStageInfo(name: String): SleepStageInfo = when {
    // [260729] 단계 미기록(SLEEPING) — ⚠️ 반드시 첫 분기.
    //   이 함수의 else 는 '수면 중 깸' 이라, 여기서 안 잡으면 회색 행의 '?' 가
    //   "자다 깨는 횟수가 많을수록…" 이라는 **거짓 설명**을 띄운다.
    //   매칭 키워드는 SLEEP_UNSTAGED_LABEL("기타 수면 (단계 없음)") 기준. 라벨을 바꾸면 여기도 같이.
    name.contains("기타") || name.contains("단계 없음") -> SleepStageInfo(
        label = "기타 수면(단계 없음)",
        englishName = "Unspecified Sleep",
        color = SleepUnstagedColor,
        emoji = "❔",
        recommendedRange = "권장 기준 없음",
        recommendedDesc = "많고 적음을 따지는 항목이 아니에요. 시계가 단계를 남기지 못한 만큼만 표시돼요.",
        whatIs = "주무신 건 시계가 분명히 기록했지만, 그 시간이 어떤 단계였는지(깊은 잠인지 얕은 잠인지)까지는 기록하지 못한 시간이에요. 시계 종류나 착용 상태에 따라 생길 수 있어요. 잠을 못 주무신 게 아니라, 단계 정보만 없는 거예요.",
        impact = "총 수면시간에는 이미 포함돼 있어서 '몇 시간 잤는지'는 정확해요. 다만 이 시간이 길면 깊은 잠·얕은 잠 비율이 실제보다 적게 보일 수 있으니, 단계 비율은 참고만 해주세요.",
        tips = listOf(
            "잘 때 시계를 손목에 조금 더 밀착해서 착용해주세요",
            "주무시기 전 시계 배터리를 20% 이상 남겨주세요",
            "아침에 시계와 휴대폰을 한 번 동기화해주세요",
            "이 시간이 계속 길게 나오면 시계의 수면 측정 설정을 확인해주세요"
        )
    )
    name.contains("체력") || name.contains("회복") -> SleepStageInfo(
        label = "깊은 수면(체력 회복 잠)",   // [260705 R4] 도움말 제목을 분포 라벨과 일치
        englishName = "Deep Sleep",
        color = SleepDeepColor,
        emoji = "💪",
        recommendedRange = "전체 수면의 13~23%",
        recommendedDesc = "성인의 체력 회복 잠은 보통 10~20%를 차지해요. 부족하면 다음 날 피로감이 커져요.",
        whatIs = "신체가 가장 깊이 회복되는 단계예요. 호흡과 심박이 느려지고 뇌파가 안정되면서 몸 전체가 휴식 모드로 들어가요. 면역 강화, 근육·뼈 회복, 성장 호르몬 분비가 이 시간에 가장 활발해요.",
        impact = "체력 회복 잠이 충분하면 췌장의 인슐린 분비 기능이 회복되어 다음 날 혈당 조절이 안정적이에요. 부족하면 인슐린 저항성이 높아져 공복혈당이 평소보다 높게 측정될 수 있어요.",
        tips = listOf(
            "잠들기 4시간 전부터 음주·카페인은 피해주세요",
            "낮에 30분 이상 햇빛을 받으면 밤에 체력 회복 잠이 늘어요",
            "취침 1~2시간 전 따뜻한 물로 샤워하면 도움돼요",
            "침실은 어둡고 시원하게(18~20°C) 유지해주세요"
        )
    )
    name.contains("뇌") -> SleepStageInfo(
        label = "렘 수면(뇌 휴식하는 잠)",   // [260705 R4]
        englishName = "REM Sleep",
        color = SleepRemColor,
        emoji = "🧠",
        recommendedRange = "전체 수면의 20~25%",
        recommendedDesc = "꿈을 꾸는 단계로, 보통 90분 간격으로 반복되며 새벽으로 갈수록 길어져요.",
        whatIs = "꿈을 꾸는 단계예요. 눈동자가 빠르게 움직이고 뇌는 깨어있을 때처럼 활발히 작동해요. 낮 동안 받은 정보와 감정을 정리하고 장기 기억으로 저장하는 시간이에요.",
        impact = "뇌 휴식하는 잠이 부족하면 기억력·집중력이 떨어지고 감정 기복이 커져요. 또한 식욕 호르몬(렙틴·그렐린) 균형이 무너져 다음 날 단 음식을 더 찾게 되고 혈당 관리가 어려워져요.",
        tips = listOf(
            "매일 같은 시간에 자고 일어나기 — 가장 중요해요",
            "수면 시간이 짧으면 뇌 휴식하는 잠이 먼저 줄어요 — 최소 7시간 자기",
            "잠들기 전 명상·심호흡으로 스트레스 풀기",
            "취침 1시간 전 스마트폰·TV는 멀리해주세요"
        )
    )
    name.contains("얕은") -> SleepStageInfo(
        label = "얕은 수면",   // [260705 R4]
        englishName = "Light Sleep",
        color = SleepLightColor,
        emoji = "🌙",
        recommendedRange = "전체 수면의 45~55%",
        recommendedDesc = "수면의 절반 이상을 차지하는 자연스러운 단계예요. 50% 전후가 가장 좋아요.",
        whatIs = "잠과 깸 사이의 단계로, 호흡과 심박이 안정되고 근육이 이완돼요. 체력 회복 잠과 뇌 휴식하는 잠으로 가기 전 반드시 거치는 '연결 통로' 같은 단계예요. 작은 소리나 움직임에도 쉽게 반응해요.",
        impact = "얕은 잠 자체가 나쁜 게 아니에요. 정상 수면의 절반을 차지하는 게 맞고, 너무 많으면 체력 회복 잠과 뇌 휴식하는 잠이 부족하다는 신호이고, 너무 적으면 수면 사이클이 불안정하다는 뜻이에요.",
        tips = listOf(
            "취침 환경(소음·온도·빛)을 매일 같게 유지하기",
            "낮잠은 20~30분 이내로 짧게 — 길면 밤잠 방해",
            "취침 시간을 매일 같게 — 사이클이 안정돼요",
            "취침 전 가벼운 스트레칭으로 근육 이완"
        )
    )
    else -> SleepStageInfo(  // 자다 깬 시간
        label = "수면 중 깸(자다 깬 시간)",   // [260705 R4]
        englishName = "Awake",
        color = SleepAwakeColor,
        emoji = "👀",
        recommendedRange = "전체 수면의 5% 이내",
        recommendedDesc = "잠시 깨는 건 자연스럽지만 반복되면 수면의 질이 떨어져요.",
        whatIs = "수면 중에 잠시 깨어 있는 시간이에요. 하룻밤에 1~2번, 몇 분 정도 깨는 건 누구나 있는 자연스러운 현상이에요. 다만 자주 깨거나 한 번에 오래 깨어있다면 수면의 질을 점검해볼 필요가 있어요.",
        impact = "자다 깨는 횟수가 많을수록 다음 날 피로감이 커지고, 혈당 조절도 어려워져요. 특히 새벽 3~5시에 자주 깬다면 스트레스 호르몬(코르티솔)이 일찍 분비되어 공복혈당이 높아질 수 있어요.",
        tips = listOf(
            "잠들기 2시간 전부터 수분 섭취 줄이기 — 야간 화장실 방지",
            "침실을 완전히 어둡게, 외부 소음 차단",
            "코골이가 심하다면 의사 상담 — 수면 무호흡 가능성",
            "취침 전 카페인·니코틴은 절대 금물"
        )
    )
}

/** 분(자정 기준) → "오전 7:23" 같은 한국어 시각 라벨. SleepHypnogramChart 내부용. */
private fun sleepMinToTimeStr(min: Int): String {
    var m = min; while (m < 0) m += 24 * 60; m %= (24 * 60)
    val h = m / 60; val mm = m % 60
    val amPm = if (h < 12) "오전" else "오후"
    val h12 = ((h + 11) % 12) + 1
    return "$amPm $h12:${"%02d".format(mm)}"
}

/**
 * 사용자 피드백 — "너무 짧게짧게 흐름이 진행되어 빽빽함".
 *
 * Samsung Health raw 데이터는 1~3분 단위 단계 추정이라 transient noise 가 많음.
 * 의학적으로는 5분 미만 깸/REM flicker는 무시하는 게 표준 (sleep cycle은 90분 단위).
 *
 * 2단계 smoothing:
 *  1) threshold(기본 5분) 미만 짧은 segment를 인접 longer segment의 stage로 흡수
 *  2) 인접 동일 stage segment merge
 *  (안정화 될 때까지 반복)
 */
private fun smoothSleepSegments(
    segments: List<com.dangbomcare.app.data.health.HealthConnectRepository.SleepSegment>,
    minSegmentMinutes: Int = 5
): List<com.dangbomcare.app.data.health.HealthConnectRepository.SleepSegment> {
    if (segments.isEmpty()) return segments
    val sorted = segments.sortedBy { it.startMin }.toMutableList()

    var iteration = 0
    val maxIterations = 10  // 무한 루프 방지
    while (iteration < maxIterations) {
        iteration++
        var changed = false

        // 1) 짧은 segment 흡수 — 인접 longer segment의 stage로 변환
        //    [260705 R4] 단, '수면 중 깸'(1·7)은 절대 흡수하지 않음 — 삼성헬스의 1분짜리 깸 스파이크가
        //    잡음 제거에 먹혀서 차트에서 사라지던 원인. 깸은 원본 그대로 전부 표시한다.
        var i = 0
        while (i < sorted.size) {
            val cur = sorted[i]
            val duration = cur.endMin - cur.startMin
            if (duration < minSegmentMinutes && sorted.size > 1 && cur.stage != 1 && cur.stage != 7) {
                val prev = if (i > 0) sorted[i - 1] else null
                val next = if (i < sorted.size - 1) sorted[i + 1] else null
                val targetStage = when {
                    prev != null && next != null -> {
                        val pd = prev.endMin - prev.startMin
                        val nd = next.endMin - next.startMin
                        if (pd >= nd) prev.stage else next.stage
                    }
                    prev != null -> prev.stage
                    next != null -> next.stage
                    else -> cur.stage
                }
                if (targetStage != cur.stage) {
                    sorted[i] = com.dangbomcare.app.data.health.HealthConnectRepository.SleepSegment(
                        cur.startMin, cur.endMin, targetStage
                    )
                    changed = true
                }
            }
            i++
        }

        // 2) 인접 동일 stage merge
        i = 0
        while (i < sorted.size - 1) {
            val cur = sorted[i]
            val nx = sorted[i + 1]
            // 같은 stage + 시간상 거의 인접 (gap ≤ 2분)
            if (cur.stage == nx.stage && nx.startMin <= cur.endMin + 2) {
                sorted[i] = com.dangbomcare.app.data.health.HealthConnectRepository.SleepSegment(
                    cur.startMin, maxOf(cur.endMin, nx.endMin), cur.stage
                )
                sorted.removeAt(i + 1)
                changed = true
            } else {
                i++
            }
        }

        if (!changed) break
    }

    return sorted
}

/** [260706 R8] 같은 레인에서 인접(≤1분 간격) 세그먼트를 병합한 연속 구간 — 삼성헬스식 연속 슬래브용. */
private data class SleepRun(val startMin: Int, val endMin: Int, val row: Int, val stage: Int)

/**
 * 사용자 피드백 — "각각 단계가 너무 끊겨 보임. 부드럽게 이어서 선그래프 형식으로".
 *
 * 표준 hypnogram step line 차트:
 *  • Y축: 4단계 (위→아래: 깸 / 렘 / 얕은잠 / 체력회복) — 아래 rowIndex()·'수면 단계 분포' 순서와 동일.
 *    (구 주석은 '깸/얕은잠/REM/체력회복'이라 구현과 어긋나 있었다. 라벨은 분포 카드가 담당하므로
 *     차트 자체에는 단계 이름을 그리지 않는다 → 색↔이름 대응은 분포 4줄이 유일한 근거.)
 *  • X축: 시간 흐름 (취침 → 기상)
 *  • 각 세그먼트의 horizontal line + 단계 변경 시 vertical line으로 **자연스럽게 연결**
 *  • 단계별로 line 색상이 변해서 어떤 단계인지 한눈에 파악
 */
@Composable
private fun SleepHypnogramChart(
    segments: List<com.dangbomcare.app.data.health.HealthConnectRepository.SleepSegment>,
    timelineStart: Int,
    timelineEnd: Int,
    a11yDesc: String
) {
    val span = (timelineEnd - timelineStart).coerceAtLeast(1).toFloat()
    // [260706 R8-2] 원본 문서(image24, 6/22 밤) 실측: 플롯 389px@2.625 ≈ 148dp, 레인 37dp
    val chartHeight = 148.dp

    fun stageColor(stage: Int) = when (stage) {
        1, 7 -> SleepAwakeColor
        4 -> SleepLightColor
        6 -> SleepRemColor
        5 -> SleepDeepColor
        else -> androidx.compose.ui.graphics.Color.Transparent
    }
    // [260705 R3] 레인 순서 = 분포와 동일하게 깸 → 렘 → 얕은 → 깊은 (삼성헬스와 같은 배치)
    fun rowIndex(stage: Int): Int = when (stage) {
        1, 7 -> 0    // 수면 중 깸
        6 -> 1       // 렘수면
        4 -> 2       // 얕은 수면
        5 -> 3       // 깊은 수면
        else -> -1
    }

    Column(modifier = Modifier.fillMaxWidth().semantics { contentDescription = a11yDesc }) {
        // [260705 R4] 범례 제거 — 아래 '수면 단계 분포'가 색·이름을 이미 설명하므로 중복
        // ── 차트 본체 ──
        // [260706 R8-3] 삼성헬스 구버전(요청문서 21p image24, 6/22 밤) 실측 1:1 재현:
        //   · 플롯 148dp, 막대 = 레인의 53%, 모서리 = 막대의 26%
        //   · 전환 목 = min(막대의 38%, 좁은 쪽 조각 폭) + 공기 구간만 밝아지는 5-스톱 그라데이션
        //   · 흰 바탕 + 레인 경계 점(dot) 선, 좌측 축선 없음
        //   · 깸: 4분 이상만 리본에 연결, 4분 미만은 부유 스파이크 (밴드는 그 아래로 연속)
        //   · 폭은 길이 비례(최소 2dp) — 깸 두께가 다 같아 보이는 왜곡 없음

        // [Codex Low] 런 파이프라인은 분(minute) 공간의 순수 연산 — remember로 캐시해 매 draw 재계산 방지
        val chartRuns = remember(segments) {
            val cleaned = segments.filter { rowIndex(it.stage) >= 0 && it.endMin > it.startMin }
                .sortedBy { it.startMin }
            // [R8-5] 겹침 정규화 — 삼성 SDK가 겹치는 세그먼트를 주는 밤(예: 5/20)에 얕은+깊은이
            //   같은 시간대에 2층으로 그려지던 버그. 한 시점 = 한 단계 (먼저 시작한 세그먼트 우선).
            val nonOverlap = mutableListOf<com.dangbomcare.app.data.health.HealthConnectRepository.SleepSegment>()
            var cursor = Int.MIN_VALUE
            for (s in cleaned) {
                val st = maxOf(s.startMin, cursor)
                if (s.endMin > st) {
                    nonOverlap.add(
                        if (st == s.startMin) s
                        else com.dangbomcare.app.data.health.HealthConnectRepository.SleepSegment(st, s.endMin, s.stage)
                    )
                    cursor = maxOf(cursor, s.endMin)
                }
            }
            // 스무딩 4분 — 레퍼런스의 최소 조각 입도(≈4분)와 동일 (깸 1·7은 흡수 예외)
            val smoothed = smoothSleepSegments(nonOverlap, minSegmentMinutes = 4)
            // 같은 레인 인접(≤3분 갭) 세그먼트 병합 — 마이크로 갭으로 인한 소시지 조각화 방지
            val runs = mutableListOf<SleepRun>()
            for (s in smoothed) {
                val ri = rowIndex(s.stage)
                val last = runs.lastOrNull()
                if (last != null && last.row == ri && s.startMin <= last.endMin + 3) {
                    runs[runs.size - 1] = last.copy(endMin = maxOf(last.endMin, s.endMin))
                } else {
                    runs.add(SleepRun(s.startMin, s.endMin, ri, s.stage))
                }
            }
            // 리본 = 렘·얕은·깊은 전부 + 4분 이상의 깸 / 부유 = 4분 미만의 깸
            val joined = runs.filter { it.row != 0 || (it.endMin - it.startMin) >= 4 }
            val floats = runs.filter { it.row == 0 && (it.endMin - it.startMin) < 4 }
            // 같은 레인이 8분 이하 간격으로(사이에 다른 조각 없이) 이어지면 통합
            val merged = mutableListOf<SleepRun>()
            for (r in joined) {
                val last = merged.lastOrNull()
                if (last != null && last.row == r.row && r.startMin - last.endMin <= 8) {
                    merged[merged.size - 1] = last.copy(endMin = maxOf(last.endMin, r.endMin))
                } else {
                    merged.add(r)
                }
            }
            // [R13] R12의 척추 브리지 롤백 — 밴드가 에피소드 위를 지나가면 '얕은+깊은 동시'라는
            //   물리적으로 틀린 그림이 됨. 삼성은 그 순간 리본이 이동해 있음(한 시점 = 한 단계).
            // [R14/R15] 갭 스냅 1~8분 — 타임라인 구멍(흡수된 잡음·부유 깸)이 있으면 벽이 에피소드
            //   가장자리에만 붙고 반대쪽 밴드 끝이 허공에 남았음(실제 '미연결'의 주요 경로).
            //   다음 조각의 시작을 앞 조각의 끝에 붙여 모든 전환이 실제로 맞닿게 함.
            val snapped = mutableListOf<SleepRun>()
            for (r in merged) {
                val last = snapped.lastOrNull()
                if (last != null && (r.startMin - last.endMin) in 1..8) {
                    snapped.add(r.copy(startMin = last.endMin))
                } else {
                    snapped.add(r)
                }
            }
            Pair(snapped.toList(), floats)
        }
        val ribbon = chartRuns.first
        val floats = chartRuns.second
        // [DEBUG] 접합 진단 덤프 트리거 — 밤(데이터)당 1회만 (debug 빌드 전용)
        val dumpOnce = remember(segments) { java.util.concurrent.atomic.AtomicBoolean(false) }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                // [R9-4] 플롯 아래 6dp = 시간 눈금 틱 영역 (삼성헬스처럼 X축 '아래로' 틱)
                .height(chartHeight + 6.dp)
        ) {
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height - 6.dp.toPx()   // h = 플롯 높이 (아래 6dp는 틱 전용)
                // [Codex High] 0폭/0높이 측정 패스에서 coerceIn(min>max) 크래시 방지
                if (w <= 1f || h <= 1f) return@Canvas
                val rowH = h / 4f
                // [R9] 전체 슬림화 — 연결선을 헤어라인으로 바꾸면서 막대도 36%로 한 단계 하향
                val barH = rowH * 0.36f
                fun laneTop(ri: Int) = ri * rowH + (rowH - barH) / 2f
                fun laneCenter(ri: Int) = ri * rowH + rowH / 2f

                // 차트 골격 — 레퍼런스는 '점(dot) 선'만 있고 좌측 축선은 없음
                val dotEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                    floatArrayOf(1.2.dp.toPx(), 2.6.dp.toPx()), 0f
                )
                val gridColor = androidx.compose.ui.graphics.Color(0xFFE9ECF3)
                for (i in 0..4) {
                    val y = (i * rowH).coerceIn(0.5f, h - 0.5f)
                    // [R9-4] 맨 위·맨 아래는 실선, 중간 레인 경계만 점선 (삼성헬스와 동일)
                    val edge = i == 0 || i == 4
                    drawLine(
                        color = gridColor,
                        start = androidx.compose.ui.geometry.Offset(0f, y),
                        end = androidx.compose.ui.geometry.Offset(w, y),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = if (edge) null else dotEffect
                    )
                }
                // [R9-4] 시간 라벨 위치 눈금 — 삼성헬스처럼 바닥 축선 '아래로' 짧은 세로 틱 4개
                val tickColor = androidx.compose.ui.graphics.Color(0xFFCBD2DE)
                for (f in floatArrayOf(0f, 1f / 3f, 2f / 3f, 1f)) {
                    val tx = (w * f).coerceIn(0.6.dp.toPx(), w - 0.6.dp.toPx())
                    drawLine(
                        color = tickColor,
                        start = androidx.compose.ui.geometry.Offset(tx, h),
                        end = androidx.compose.ui.geometry.Offset(tx, h + 5.dp.toPx()),
                        strokeWidth = 1.2.dp.toPx()
                    )
                }

                if (ribbon.isEmpty() && floats.isEmpty()) return@Canvas

                fun xAt(min: Float) = ((min - timelineStart) / span * w).coerceIn(0f, w)

                // [R16/Codex] 접합 재작성 — 퍼지 탐색(hostAt) 폐기. 스냅 후 '시간상 실제로 맞닿은
                //   연속 페어'만 접합으로 확정하고, 벽과 모서리가 같은 접합 정보를 공유한다.
                //   (|레인차| 우선 탐색이 엉뚱한 조각을 잡아 벽 누락·모서리 반전을 만들던 버그,
                //    슬래브 최소폭 보정과 벽 좌표가 어긋나던 버그를 함께 해결)
                // [R17] 사용자 지시: 선은 '훨씬 더' 얇게(1dp), 데이터 조각 최소폭은 2.5dp로 —
                //   7시 부근 짧은 깊은수면이 선 굵기와 같아서 선 한 줄로 보이던 문제.
                //   막대(≥2.5dp)와 선(1dp)이 항상 구분되도록 비율 고정.
                val minW = 2.5.dp.toPx()
                val neckWMax = 1.0.dp.toPx()
                // 1) 시각 좌표(최소폭 보정 포함)를 먼저 확정 — 벽·모서리·슬래브가 전부 이 값을 씀
                val vLeft = FloatArray(ribbon.size)
                val vRight = FloatArray(ribbon.size)
                for (i in ribbon.indices) {
                    val r = ribbon[i]
                    val x1 = xAt(r.startMin.toFloat())
                    val bw = (xAt(r.endMin.toFloat()) - x1).coerceAtLeast(minW)
                    val l = x1.coerceAtMost(w - bw)
                    vLeft[i] = l
                    vRight[i] = l + bw
                }
                // 2) 접합 확정 + 모서리 플래그 (상승: cur 오른쪽 위·nx 왼쪽 아래 뾰족 / 하강: 반대)
                val sharpTL = BooleanArray(ribbon.size)
                val sharpBL = BooleanArray(ribbon.size)
                val sharpTR = BooleanArray(ribbon.size)
                val sharpBR = BooleanArray(ribbon.size)
                val joins = mutableListOf<IntArray>()
                for (i in 0 until ribbon.size - 1) {
                    val cur = ribbon[i]
                    val nx = ribbon[i + 1]
                    if (nx.startMin > cur.endMin) continue   // 스냅 후에도 남은 >8분 구멍 = 진짜 끊김
                    if (cur.row == nx.row) continue
                    joins.add(intArrayOf(i, i + 1))
                    if (nx.row < cur.row) { sharpTR[i] = true; sharpBL[i + 1] = true }
                    else { sharpBR[i] = true; sharpTL[i + 1] = true }
                }
                // 2.5) [R29] '겹침 용접' 단일 규칙 — 수직선이 두 막대 모두의 몸 안에 들어가도록,
                //   접합점에서 두 슬래브를 선폭(1dp)만큼 가로로 겹치게 붙임:
                //   cur 오른끝 = bx, nx 왼끝 = bx - 1dp (서로 다른 레인이라 시각 충돌 없음).
                //   수직선 [bx-1dp, bx]가 cur에도 nx에도 실제로 박혀 —
                //   기울기 없음 + 양끝 부착 + 돌출 없음을 동시에 만족.
                //   ※ 니들(1~7분 조각) 특별 처리 전부 삭제 — 이 규칙만으로 '가장자리 다리 2개 +
                //   캡 + 열린 가운데'(삼성 U)가 자동으로 나온다. 한 시점 = 한 단계(밴드 융합 금지).
                val keep = 1.dp.toPx()
                for (j in joins) {
                    val i1 = j[0]
                    val i2 = j[1]
                    val lo = vLeft[i1] + keep
                    val hi = vRight[i2] - keep
                    // [R21-2/Codex F] 용접은 '무조건' 성립 — keep 여유가 안 나오는 극단 케이스에도
                    //   중간점으로 강제 통일해 선(bx)과 슬래브 가장자리가 절대 어긋나지 않게 함.
                    val bx = if (lo < hi) ((vRight[i1] + vLeft[i2]) / 2f).coerceIn(lo, hi)
                        else (vRight[i1] + vLeft[i2]) / 2f
                    vRight[i1] = bx
                    vLeft[i2] = (bx - neckWMax).coerceAtLeast(0f)
                }

                // [DEBUG] 접합 진단 덤프 — 원인 분석용 (debug 빌드 전용, 밤당 1회)
                if (com.dangbomcare.app.BuildConfig.DEBUG && dumpOnce.compareAndSet(false, true)) {
                    val sb = StringBuilder("night ${timelineStart}..${timelineEnd} w=%.0f\n".format(w))
                    for (i in ribbon.indices) {
                        val r = ribbon[i]
                        sb.append(
                            "RIB[%d] row=%d st=%d t=%d-%d x=%.1f..%.1f w=%.1f %s%s%s%s\n".format(
                                i, r.row, r.stage, r.startMin, r.endMin,
                                vLeft[i], vRight[i], vRight[i] - vLeft[i],
                                if (sharpTL[i]) "TL" else "", if (sharpBL[i]) "BL" else "",
                                if (sharpTR[i]) "TR" else "", if (sharpBR[i]) "BR" else ""
                            )
                        )
                    }
                    for (j in joins) sb.append("JOIN ${j[0]}->${j[1]}\n")
                    for (f in floats) sb.append("FLOAT t=${f.startMin}-${f.endMin}\n")
                    android.util.Log.d("SleepChartDump", sb.toString())
                }

                // 3) 슬래브 — 시각 좌표 + 접합 플래그 공유 (붙는 쪽 모서리만 뾰족)
                for (i in ribbon.indices) {
                    val r = ribbon[i]
                    val left = vLeft[i]
                    val bw = vRight[i] - vLeft[i]
                    val top = laneTop(r.row)
                    val rad = minOf(barH * 0.33f, bw / 2f)
                    val tl = if (sharpTL[i]) 0f else rad
                    val bl = if (sharpBL[i]) 0f else rad
                    val tr = if (sharpTR[i]) 0f else rad
                    val br = if (sharpBR[i]) 0f else rad
                    val path = androidx.compose.ui.graphics.Path().apply {
                        addRoundRect(
                            androidx.compose.ui.geometry.RoundRect(
                                left = left, top = top, right = left + bw, bottom = top + barH,
                                topLeftCornerRadius = androidx.compose.ui.geometry.CornerRadius(tl, tl),
                                topRightCornerRadius = androidx.compose.ui.geometry.CornerRadius(tr, tr),
                                bottomRightCornerRadius = androidx.compose.ui.geometry.CornerRadius(br, br),
                                bottomLeftCornerRadius = androidx.compose.ui.geometry.CornerRadius(bl, bl)
                            )
                        )
                    }
                    drawPath(path, color = stageColor(r.stage))
                }

                // 4) 접합 리본 — [R22] 막대보다 '나중에' 그리는 채움 도형 (사용자: 라운드가 막대 밖으로 샘).
                //   시작 폭은 cur 막대의 접합면 위, 끝 폭은 nx 막대의 접합면 위에 정확히 놓이는 S자 리본.
                //   발자국이 [접합점±neckW] × 두 면 사이로 한정되어 막대 실루엣 밖으로 새는 픽셀이
                //   기하학적으로 불가능.
                for (j in joins) {
                    val i1 = j[0]
                    val i2 = j[1]
                    val cur = ribbon[i1]
                    val nx = ribbon[i2]
                    val narrowI = if (vRight[i1] - vLeft[i1] <= vRight[i2] - vLeft[i2]) i1 else i2
                    val neckW = minOf(neckWMax, vRight[narrowI] - vLeft[narrowI])
                    // [R24] 수직선 결정판 — 겹침 용접으로 [bx-1dp, bx]가 두 막대 모두의 몸 안:
                    //   · 완전 수직 (기울기 없음)
                    //   · 세로 = 두 접합면 사이 공기 구간 ±1dp 몸속 매립 (양끝이 몸 안에 박혀 실금 없음)
                    //   [R26/Codex] 조각 폭 전체 스틱은 'R25 니들'만 — 용접으로 좁아진 일반 조각까지
                    //   폭 판정으로 스틱 처리하면 가로 슬래브가 그라데이션에 덮여 사라졌음.
                    // [R29] 단일 규칙: 선은 항상 접합점의 겹침 구간 [bx-1dp, bx] —
                    //   접합점이 곧 각 조각의 가장자리이므로 "y선은 x선의 양쪽 가장자리에" 자동 성립.
                    val xR = vRight[i1]          // = 용접된 접합점 bx
                    val xL = xR - neckW          // = vLeft[i2] (겹침 용접으로 nx 몸 안)
                    val down = nx.row > cur.row
                    val curFace = if (down) laneTop(cur.row) + barH else laneTop(cur.row)
                    val nxFace = if (down) laneTop(nx.row) else laneTop(nx.row) + barH
                    val ov = 1.dp.toPx()
                    val gTop = minOf(curFace, nxFace)
                    val gBot = maxOf(curFace, nxFace)
                    val cTopC = stageColor(if (down) cur.stage else nx.stage)
                    val cBotC = stageColor(if (down) nx.stage else cur.stage)
                    // [R26/Codex] 2레인 이상 점프(깸↔얕은 등)는 중간 레인을 지나는 긴 띠 —
                    //   가운데를 밝게 빠지게 해(삼성의 워시) 중간색 벨트로 안 보이게 함
                    val brush = if (kotlin.math.abs(nx.row - cur.row) >= 2) {
                        val mid = androidx.compose.ui.graphics.lerp(
                            androidx.compose.ui.graphics.lerp(cTopC, cBotC, 0.5f),
                            androidx.compose.ui.graphics.Color.White, 0.45f
                        )
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            0f to cTopC, 0.5f to mid, 1f to cBotC,
                            startY = gTop, endY = gBot
                        )
                    } else {
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            0f to cTopC, 1f to cBotC,
                            startY = gTop, endY = gBot
                        )
                    }
                    drawRect(
                        brush = brush,
                        topLeft = androidx.compose.ui.geometry.Offset(xL, gTop - ov),
                        size = androidx.compose.ui.geometry.Size((xR - xL).coerceAtLeast(1f), (gBot - gTop) + 2f * ov)
                    )
                }
                // 부유 깸 — 연결이 없으므로 전면 라운드 (얇은 스틱은 어차피 bw/2가 상한)
                for (r in floats) {
                    val x1 = xAt(r.startMin.toFloat())
                    val x2 = xAt(r.endMin.toFloat())
                    // [R26/Codex] 스냅·병합된 깸 리본과 x가 겹치면 생략 — 리본 모서리 위에
                    //   둥근 캡이 혹처럼 겹쳐 보이던 경로 제거
                    val overlapsWakeRibbon = ribbon.indices.any {
                        ribbon[it].row == 0 && vLeft[it] < x2 && x1 < vRight[it]
                    }
                    if (overlapsWakeRibbon) continue
                    val bw = (x2 - x1).coerceAtLeast(minW)
                    val rad = minOf(barH * 0.33f, bw / 2f)
                    drawRoundRect(
                        color = stageColor(r.stage),
                        topLeft = androidx.compose.ui.geometry.Offset(x1.coerceAtMost(w - bw), laneTop(r.row)),
                        size = androidx.compose.ui.geometry.Size(bw, barH),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(rad, rad)
                    )
                }
            }
        }

        // ── 하단 시간 라벨 (틱 4개 위치와 대응) ──
        Spacer(Modifier.height(3.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // weight(fill=false): 평소엔 기존과 동일(SpaceBetween), 좁은 화면·큰 글씨에서만
            //   각 라벨이 1/4 폭을 넘으면 자동 축소돼 겹침/잘림 방지
            val third = (timelineEnd - timelineStart) / 3
            listOf(
                timelineStart, timelineStart + third, timelineStart + 2 * third, timelineEnd
            ).forEach { m ->
                com.dangbomcare.app.ui.components.AutoSizeSingleLineText(
                    sleepMinToTimeStr(m),
                    modifier = Modifier.weight(1f, fill = false),
                    maxFontSize = 12.sp,
                    minFontSize = 8.sp,
                    color = TextSecondary,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun HypnogramLegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color)
        )
        Spacer(Modifier.width(5.dp))
        Text(
            label,
            fontSize = 11.sp,
            color = TextPrimary,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            softWrap = false
        )
    }
}

// (구버전 SleepStageVerticalBar — hypnogram으로 대체됨. 호출자 없어 제거)
@Suppress("unused")
@Composable
private fun RowScope.SleepStageVerticalBar(
    label: String,
    color: Color,
    minutes: Int,
    maxMin: Int
) {
    val ratio = (minutes.toFloat() / maxMin).coerceIn(0f, 1f)
    Column(
        modifier = Modifier.weight(1f).fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom
    ) {
        // 막대 위 시간 라벨
        val timeLabel = when {
            minutes <= 0 -> "0분"
            minutes < 60 -> "${minutes}분"
            else -> "${minutes / 60}시간 ${minutes % 60}분"
        }
        Text(
            timeLabel,
            fontSize = 10.sp,
            color = TextPrimary,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false
        )
        Spacer(Modifier.height(4.dp))
        // 세로 막대 — 비어있어도 최소 2dp는 보이게
        Box(
            modifier = Modifier
                .fillMaxWidth(0.55f)
                .weight(1f, fill = false)
                .height((180 * ratio).coerceAtLeast(2f).dp)
                .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                .background(if (minutes > 0) color else color.copy(alpha = 0.25f))
        )
        Spacer(Modifier.height(6.dp))
        // 단계 이름
        Text(
            label,
            fontSize = 11.sp,
            color = TextSecondary,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun SleepStagePercentRow(color: Color, label: String, minutes: Int, totalMin: Int) {
    val pct = if (totalMin > 0) minutes * 100 / totalMin else 0
    var showHelp by remember { mutableStateOf(false) }
    if (showHelp) {
        SleepStageHelpDialog(stageName = label, onDismiss = { showHelp = false })
    }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(8.dp))
            // [260706 R7] % 박스 고정 폭 38dp(두 자리 기준) — 한 자리든 두 자리든 같은 크기
            Surface(color = color.copy(alpha = 0.15f), shape = RoundedCornerShape(6.dp)) {
                Box(
                    modifier = Modifier.width(38.dp).padding(vertical = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("$pct%", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = color, maxLines = 1, softWrap = false)
                }
            }
            Spacer(Modifier.width(8.dp))
            // [260706 R7] 라벨 크기 통일 — 4행 전부 '가장 긴 라벨' 기준으로 같은 크기.
            //   기존엔 행마다 따로 줄어서 짧은 '얕은 수면'만 크게 보였음.
            com.dangbomcare.app.ui.components.AutoSizeSingleLineText(
                label, maxFontSize = 15.sp, minFontSize = 11.sp,
                color = TextPrimary, modifier = Modifier.weight(1f),
                fitText = "수면 중 깸 (자다 깬 시간)"
            )
            // ⭐ 도움말 ? 버튼 (당봄 피드백 #9)
            IconButton(
                onClick = { showHelp = true },
                modifier = Modifier.size(20.dp)
            ) {
                Icon(
                    androidx.compose.material.icons.Icons.AutoMirrored.Filled.HelpOutline,
                    contentDescription = "$label 설명",
                    tint = TextSecondary,
                    modifier = Modifier.size(14.dp)
                )
            }
            Spacer(Modifier.width(6.dp))
            // [260706 R7] 시간 폭 고정+우측정렬 — 행마다 시간 글자수가 달라("1시간 8분" vs "0시간 23분")
            //   ? 버튼 열이 어긋나던 문제. 이제 ?가 모든 행에서 같은 x에 정렬됨.
            Text(
                "${minutes / 60}시간 ${minutes % 60}분",
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextSecondary,
                maxLines = 1, softWrap = false,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                modifier = Modifier.widthIn(min = 70.dp)
            )
        }
    }
}

/**
 * 스트레스 — 선택한 날짜의 시간대별 심박/스트레스 라인.
 */
@Composable
private fun StressDayHourlyCard(date: java.time.LocalDate) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var hourly by remember(date) { mutableStateOf<List<Int?>>(List(24) { null }) }
    var loading by remember(date) { mutableStateOf(true) }
    var selectedHour by remember(date) { mutableStateOf<Int?>(null) }

    LaunchedEffect(date) {
        loading = true
        try {
            val repo = com.dangbomcare.app.data.health.HealthConnectRepository(context)
            if (repo.isSdkAvailable()) {
                hourly = repo.fetchHeartRateHourly(date)
            }
        } catch (e: Exception) { /* */ }
        loading = false
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        border = BorderStroke(1.dp, Divider)
    ) {
        // [Fix 1] 가로 패딩을 14→10dp 로 더 줄여 시간별 차트가 화면 가로를 한층 더 넓게 사용. 세로 패딩은 20dp 유지.
        //   (Canvas 의 터치/draw 는 size.width 기준 상대 계산이라 자동 반영 — 클리핑 재발 없음.)
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 20.dp)) {
            Text(
                "${date.monthValue}/${date.dayOfMonth} 스트레스",
                fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary
            )
            Spacer(Modifier.height(12.dp))
            if (loading) {
                LoadingIndicator(modifier = Modifier.height(160.dp))
            } else {
                val nonNull = hourly.filterNotNull().filter { it > 0 }
                if (nonNull.isEmpty()) {
                    WatchAbsentNotice("이 날")
                } else {
                    val avg = nonNull.average().toInt()
                    val (avgLabel, avgColor) = stressLevelFromHr(avg)

                    // ─── 상단: 평균 레이블 + 그라데이션 위치 바 ───
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text(avgLabel, fontSize = 32.sp, fontWeight = FontWeight.Black, color = avgColor)
                        Text("최근 평균 ${avg} bpm", fontSize = 11.sp, color = TextSecondary)
                    }
                    Spacer(Modifier.height(12.dp))
                    StressGradientBar(avgBpm = avg)
                    Spacer(Modifier.height(20.dp))

                    HorizontalDivider(color = Divider)
                    Spacer(Modifier.height(16.dp))

                    // ─── 시간별 스트레스 라인 차트 ───
                    Text("시간별 스트레스 정도", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Spacer(Modifier.height(8.dp))
                    // 선택된 점 툴팁 — 큰 글자 호환 + 접근성 (코덱스 H)
                    val sel = selectedHour
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 36.dp)
                            .semantics {
                                liveRegion = LiveRegionMode.Polite
                                contentDescription = sel?.let { h ->
                                    val bpm = hourly.getOrNull(h)
                                    if (bpm != null && bpm > 0) {
                                        val (lbl, _) = stressLevelFromHr(bpm)
                                        "${formatHourLabel(h)}, $bpm bpm, $lbl"
                                    } else {
                                        "${formatHourLabel(h)}, 측정 데이터 없음"
                                    }
                                } ?: "점을 터치하거나 좌우로 드래그하면 시간대별 수치가 보입니다"
                            },
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (sel != null) {
                            val bpm = hourly.getOrNull(sel)
                            if (bpm != null && bpm > 0) {
                                val (lbl, col) = stressLevelFromHr(bpm)
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = col.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        "${formatHourLabel(sel)} · $bpm bpm ($lbl)",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = col,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            } else {
                                Text(
                                    "${formatHourLabel(sel)} · 측정 데이터 없음",
                                    fontSize = 11.sp,
                                    color = TextSecondary
                                )
                            }
                        } else {
                            Text(
                                "점을 터치하거나 좌우로 드래그하면 시간대별 bpm이 보여요",
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))

                    val secondaryArgb = TextSecondary.toArgb()
                    val maxBpm = 110f; val minBpm = 50f; val span = maxBpm - minBpm
                    // 코덱스 H2: 차트 컨테이너 정적 contentDescription + 동적 stateDescription
                    val chartA11y = "시간대별 스트레스 차트. 평균 ${avg} bpm, 상태 ${avgLabel}. 점을 터치하거나 드래그하면 시간대별 수치를 확인할 수 있습니다."
                    val stateA11y = selectedHour?.let { h ->
                        val bpm = hourly.getOrNull(h)
                        if (bpm != null && bpm > 0) {
                            val (lbl, _) = stressLevelFromHr(bpm)
                            "${formatHourLabel(h)}, $bpm bpm, $lbl"
                        } else {
                            "${formatHourLabel(h)}, 측정 데이터 없음"
                        }
                    } ?: "선택된 시간이 없습니다."
                    // [재구성] 걸음수(ActivityDayHourlyCard) 차트의 깔끔한 레이아웃을 그대로 차용:
                    //   · 플롯 영역을 '경계가 분명한 박스'로 한정 (옅은 배경)
                    //   · Y 레벨 라벨은 박스 오른쪽 전용 여백(rightPad)에, X 시간 라벨은 박스 아래 전용 여백(bottomPad)에 → 라벨이 박스 밖
                    //   · 데이터(점)는 좌우로 인셋(plotPad) → 0시/23시 점이 박스 가장자리에 닿지 않음
                    //   · 가장 낮은 편안함 점도 박스 바닥에서 살짝 띄워(yScaleFloor) 시간 라벨과 확실한 세로 간격 확보
                    androidx.compose.foundation.Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(196.dp)
                            .semantics {
                                contentDescription = chartA11y
                                stateDescription = stateA11y
                            }
                            .pointerInput(hourly.size) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    // draw 의 leftEdge/plotW(인셋 포함)와 동일하게 맞춰 터치-점 위치 일치.
                                    // [밀도 견고화] draw 블록과 동일하게 raw px → dp 변환 (터치/draw 동기화 유지).
                                    //   leftEdge=6dp, rightPad=axisLabelPx*3+labelGap(floor 17.5dp), plotPad=6.5dp.
                                    val leftEdge = 6.dp.toPx()                    // 16f → 6.dp(≈16.5px @2.75)
                                    val rightPad = (11.sp.toPx() * 3f + 3.dp.toPx()).coerceAtLeast(17.5.dp.toPx())  // 8f→3dp, 48f→17.5dp
                                    val plotPad = 6.5.dp.toPx()                   // 18f → 6.5.dp(≈17.875px @2.75)
                                    val plotW = (size.width - leftEdge - rightPad - plotPad * 2).coerceAtLeast(1f)
                                    fun idxFromX(x: Float): Int =
                                        (((x - leftEdge - plotPad).coerceIn(0f, plotW) / plotW) * 23f)
                                            .let { kotlin.math.round(it).toInt() }
                                            .coerceIn(0, 23)
                                    selectedHour = idxFromX(down.position.x)
                                    drag(down.id) { change ->
                                        selectedHour = idxFromX(change.position.x)
                                        change.consume()
                                    }
                                }
                            }
                    ) {
                        val axisLabelPx = 11.sp.toPx()  // [사용자 피드백] 축 글자 sp 기반 → 전역 폰트스케일 반영
                        // [재구성] 라벨이 박스 '밖으로 절대 안 넘치게' 여백을 sp(=폰트스케일 반영) 기준으로 산출.
                        //   rightPad: 가장 긴 레벨 라벨 "편안함"(3자) 폭 + 좌측 8f 간격 + 여유. bottomPad: 라벨 높이 + 점/라벨 간격.
                        // [밀도 견고화] 여백/인셋 raw px → dp (density 2.75서 같은 px, 다른 밀도서 비례 스케일).
                        //   sp 기반 항(axisLabelPx*N)·coerceAtLeast floor 도 dp 화 → 고밀도서 sp 라벨이 커지면 거터도 같이 커짐.
                        val labelGap = 3.dp.toPx()                        // 8f → 3.dp(≈8.25px @2.75)
                        val topPad = 6.dp.toPx()                          // 16f → 6.dp · 박스 상단 여백 (평균 라벨 자리)
                        // [Fix 2] 좌측 여백을 우측 라벨 거터에 맞춰 약간 키워 점 행을 가로 중앙으로 균형.
                        val leftEdge = 6.dp.toPx()                        // 16f → 6.dp · 박스 좌측 시작
                        // [Fix 2] rightPad 는 "편안함"(3자) 폭만큼만 — 최대 폰트스케일에서도 안 잘리되 군더더기 슬랙 제거(56→48 floor, +4 제거).
                        val rightPad = (axisLabelPx * 3f + labelGap).coerceAtLeast(17.5.dp.toPx())   // 48f → 17.5.dp · 우측 레벨 라벨 전용 여백
                        val bottomPad = (axisLabelPx + labelGap * 2f).coerceAtLeast(14.5.dp.toPx())  // 40f → 14.5.dp · 아래 시간 라벨 전용 여백
                        // [Fix 3] 데이터 좌우 인셋을 8→18 로 키워 0시/23시 점이 박스 좌/우 가장자리에 닿지 않고 작은 여백을 둠.
                        val plotPad = 6.5.dp.toPx()                       // 18f → 6.5.dp · 데이터 좌우 인셋 (점이 박스 가장자리에 안 닿게)
                        val plotLeft = leftEdge
                        val plotRight = size.width - rightPad
                        val plotTop = topPad
                        val plotBottom = size.height - bottomPad
                        val h = plotBottom - plotTop
                        // [사용자 피드백] 그래프 '플롯 영역만' 옅은 배경 박스로 한정 → 라벨은 박스 밖 여백에 배치(걸음·수면 차트처럼).
                        val boxCornerPx = 6.dp.toPx()   // [밀도] 16f → 6.dp(≈16.5px @2.75)
                        drawRoundRect(color = BgCardSoft,
                            topLeft = androidx.compose.ui.geometry.Offset(plotLeft, plotTop),
                            size = androidx.compose.ui.geometry.Size(plotRight - plotLeft, h),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(boxCornerPx, boxCornerPx))
                        // [사용자 피드백] y 스케일을 '데이터 + 고정 가로선' 양끝 모두에서 헤드룸을 둬 산출 →
                        //   가장 낮은 편안함 점은 바닥에서 확실히 띄우고, 가장 높은 높음 점도 천장에 안 붙게.
                        //   고정 가로선(높음100·편안함60) 기준으로도 패딩 → 데이터가 다 보통대여도 선이 박스 끝에 안 붙음.
                        val dataMin = nonNull.min(); val dataMax = nonNull.max()
                        val yScaleLo = (minOf(dataMin, 60) - 8).toFloat()
                        val yScaleHi = (maxOf(dataMax, 100) + 8).toFloat()
                        val yScaleSpan = yScaleHi - yScaleLo
                        fun yFor(v: Int): Float {
                            val r = ((v - yScaleLo) / yScaleSpan).coerceIn(0f, 1f)
                            return plotTop + h - h * r
                        }
                        val labelPaint = android.graphics.Paint().apply {
                            color = secondaryArgb; textSize = axisLabelPx
                            textAlign = android.graphics.Paint.Align.LEFT; isAntiAlias = true
                        }
                        // [사용자 피드백] 라벨을 가로선 위 '세로 중앙' 정렬 → 점·선·글자 같은 높이
                        val labelVCenter = -(labelPaint.fontMetrics.ascent + labelPaint.fontMetrics.descent) / 2f
                        val levelLabelGap = 3.dp.toPx()   // [밀도] plotRight 우측 라벨 x-오프셋 8f → 3.dp
                        listOf("높음" to 100, "보통" to 80, "낮음" to 70, "편안함" to 60).forEach { (lab, bpm) ->
                            val y = yFor(bpm)
                            drawLine(Divider, androidx.compose.ui.geometry.Offset(plotLeft, y),
                                androidx.compose.ui.geometry.Offset(plotRight, y), 0.4.dp.toPx())   // [밀도] 1f → 0.4.dp
                            drawIntoCanvas { c ->
                                c.nativeCanvas.drawText(lab, plotRight + levelLabelGap, y + labelVCenter, labelPaint)
                            }
                        }
                        // 일일 평균 점선 (삼성헬스 스타일)
                        val avgY = yFor(avg)
                        // [밀도] dash 간격 8f,6f → 3dp,2dp · 선 두께 1.5f → 0.5.dp.
                        val dashEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx()), 0f)
                        drawLine(
                            avgColor.copy(alpha = 0.6f),
                            androidx.compose.ui.geometry.Offset(plotLeft, avgY),
                            androidx.compose.ui.geometry.Offset(plotRight, avgY),
                            strokeWidth = 0.5.dp.toPx(),
                            pathEffect = dashEffect
                        )
                        // "일일 평균" 라벨 — 왼쪽 + 평균선 위쪽(데이터 점과 안 겹침).
                        val dashLabelPaint = android.graphics.Paint().apply {
                            color = avgColor.toArgb(); textSize = 9.sp.toPx()
                            textAlign = android.graphics.Paint.Align.LEFT; isAntiAlias = true
                        }
                        drawIntoCanvas { c ->
                            // [밀도] x-오프셋 4f → 1.5.dp · y-오프셋 6f → 2.dp.
                            c.nativeCanvas.drawText("일일 평균", plotLeft + 1.5.dp.toPx(), avgY - 2.dp.toPx(), dashLabelPaint)
                        }

                        // 데이터 점 — 좌우 인셋(plotPad) 적용. 0시는 plotLeft+plotPad, 23시는 plotRight-plotPad.
                        val ptX0 = plotLeft + plotPad
                        val plotW = plotRight - plotLeft - plotPad * 2
                        val stepX = plotW / 23f
                        data class Pt(val x: Float, val y: Float, val c: Color)
                        val pts = hourly.mapIndexed { i, v ->
                            if (v != null && v > 0) {
                                val (_, c) = stressLevelFromHr(v)
                                Pt(ptX0 + i * stepX, yFor(v), c)
                            } else null
                        }
                        // [v1.0 피드백 — 사용자 추가] 선택된 시간대 "컬럼 전체" 세로 띠 (선 하나 X). 점/선 뒤 z-order.
                        selectedHour?.let { sel ->
                            val cx = ptX0 + sel * stepX
                            val bandW = stepX.coerceAtMost(20.dp.toPx())
                            var left = cx - bandW / 2f
                            if (left < plotLeft) left = plotLeft
                            if (left + bandW > plotRight) left = plotRight - bandW
                            drawRect(
                                color = Slate.copy(alpha = 0.15f),
                                topLeft = androidx.compose.ui.geometry.Offset(left, plotTop),
                                size = androidx.compose.ui.geometry.Size(bandW, h)
                            )
                        }
                        for (i in 0 until pts.size - 1) {
                            val p1 = pts[i]; val p2 = pts[i + 1]
                            if (p1 != null && p2 != null) {
                                drawLine(Color.Gray.copy(alpha = 0.4f),
                                    androidx.compose.ui.geometry.Offset(p1.x, p1.y),
                                    androidx.compose.ui.geometry.Offset(p2.x, p2.y), 0.7.dp.toPx())   // [밀도] 2f → 0.7.dp
                            }
                        }
                        pts.forEachIndexed { i, p ->
                            if (p != null) {
                                if (selectedHour == i) {
                                    // 선택된 점은 큰 흰 원 + 색상 원으로 강조. [밀도] 12f/9f → 4.4dp/3.3dp.
                                    drawCircle(Color.White, 4.4.dp.toPx(), androidx.compose.ui.geometry.Offset(p.x, p.y))
                                    drawCircle(p.c, 3.3.dp.toPx(), androidx.compose.ui.geometry.Offset(p.x, p.y))
                                } else {
                                    drawCircle(p.c, 2.5.dp.toPx(), androidx.compose.ui.geometry.Offset(p.x, p.y))   // [밀도] 7f → 2.5.dp
                                }
                            }
                        }
                        // X축 시간 라벨 — 박스 '아래' 전용 여백(bottomPad)에 배치 → 점과 확실한 세로 간격(걸음 차트처럼).
                        val hourPaint = android.graphics.Paint().apply {
                            color = secondaryArgb; textSize = axisLabelPx
                            textAlign = android.graphics.Paint.Align.CENTER; isAntiAlias = true
                        }
                        // 라벨을 아래 여백(bottomPad)의 세로 중앙에 — 폰트 스케일이 커도 박스 밖으로 안 넘침.
                        val hourFm = hourPaint.fontMetrics
                        val hourLabelBaseline = plotBottom + labelGap + (-hourFm.ascent)
                        listOf(0, 6, 12, 18, 23).forEach { hour ->
                            val x = ptX0 + hour * stepX
                            drawIntoCanvas { c ->
                                c.nativeCanvas.drawText("${hour}시", x, hourLabelBaseline, hourPaint)
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))

                    // ─── 비율 막대 (높음 X% / 보통 X% / 낮음 X% / 편안함 X%) ───
                    StressProportionBar(hourly = hourly)
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = Divider)
                    Spacer(Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        StatItem("${nonNull.max()} bpm", "최고")
                        Box(modifier = Modifier.width(1.dp).height(36.dp).background(Divider))
                        StatItem("${avg} bpm", "평균")
                        Box(modifier = Modifier.width(1.dp).height(36.dp).background(Divider))
                        StatItem("${nonNull.min()} bpm", "최저")
                    }
                }
            }
        }
    }
}

// (StressTodayHourlyCard 제거 — 미호출 dead code. StressDayHourlyCard 로 대체됨)

@Composable
private fun StressLineChartCard(
    hrByDay: Map<java.time.LocalDate, com.dangbomcare.app.data.health.HealthConnectRepository.HeartRateDay>,
    period: StressPeriod,
    endDate: java.time.LocalDate,
    onEndDateChange: (java.time.LocalDate) -> Unit,
    onDayClick: (java.time.LocalDate) -> Unit = {}
) {
    val today = java.time.LocalDate.now()
    val days = periodDatesOf(endDate, periodIsMonth(period.days))
    // [사용자 피드백] y 범위는 데이터+고정 가로선 양끝에서 헤드룸을 둬 동적으로 산출(아래에서 yMin/yMax).
    //   고정 레벨 가로선: 높음=100, 보통=80, 낮음=70, 편안함=60 (시간별 차트와 동일).
    // 유효 데이터 점 + 기간 평균 — 제목 아래 평균 텍스트와 y 스케일 양쪽에서 사용하므로 먼저 계산.
    val validPts = days.mapNotNull { d -> hrByDay[d]?.avgBpm?.takeIf { it > 0 }?.let { d to it } }
    val avgBpm = validPts.map { it.second }.average().takeIf { !it.isNaN() }?.toInt()
    // y 스케일: 데이터 최저/최고와 고정선(편안함60·높음100) 양끝 모두에서 패딩 → 점이 박스 천장/바닥에 안 붙음.
    val dataMinBpm = validPts.minOfOrNull { it.second } ?: 60
    val dataMaxBpm = validPts.maxOfOrNull { it.second } ?: 100
    val minBpm = (minOf(dataMinBpm, 60) - 8).toFloat()  // 하한 (헤드룸 포함)
    val maxBpm = (maxOf(dataMaxBpm, 100) + 8).toFloat() // 상한 (헤드룸 포함)
    val span = maxBpm - minBpm

    // 선택된 점 (터치+드래그) — endDate 변경 시 reset
    var selectedIdx by remember(period, endDate) { mutableStateOf<Int?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        border = BorderStroke(1.dp, Divider)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            // ── 헤더: 좌우 화살표 + 기간 라벨 (걸음/수면과 일관성) ──
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    onEndDateChange(periodPrevOf(endDate, periodIsMonth(period.days)))
                    selectedIdx = null
                }) {
                    Icon(Icons.Filled.ChevronLeft, contentDescription = "이전 기간", tint = Primary)
                }
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    val rangeLabel = "${days.first().monthValue}월 ${days.first().dayOfMonth}일 - ${days.last().monthValue}월 ${days.last().dayOfMonth}일"
                    com.dangbomcare.app.ui.components.AutoSizeSingleLineText(rangeLabel, maxFontSize = 13.sp, minFontSize = 10.sp, color = TextSecondary)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "일별 스트레스 흐름",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }
                IconButton(
                    onClick = {
                        if (periodEndOf(endDate, periodIsMonth(period.days)) < periodEndOf(today, periodIsMonth(period.days))) {
                            onEndDateChange(periodNextOf(endDate, periodIsMonth(period.days)))
                            selectedIdx = null
                        }
                    },
                    enabled = periodEndOf(endDate, periodIsMonth(period.days)) < periodEndOf(today, periodIsMonth(period.days))
                ) {
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = "다음 기간",
                        tint = if (periodEndOf(endDate, periodIsMonth(period.days)) < periodEndOf(today, periodIsMonth(period.days))) Primary else TextDisabled
                    )
                }
            }
            // [사용자 피드백] 기간 평균을 캔버스 밖, 제목 아래 작은 컬러 텍스트로 (걸음 7일/31일 차트와 동일 배치).
            //   캔버스 안 "평균 NN" 숫자는 제거하고 여기로 이동. 점선 평균선 자체는 캔버스에 유지.
            if (avgBpm != null && avgBpm > 0) {
                val (_, avgCol) = stressLevelFromHr(avgBpm)
                Spacer(Modifier.height(4.dp))
                Text(
                    "평균 ${avgBpm} bpm",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = avgCol,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SleepLegendDot(StressCalmColor, "편안함")
                SleepLegendDot(StressLowColor, "낮음")
                SleepLegendDot(StressMidColor, "보통")
                SleepLegendDot(StressHighColor, "높음")
            }
            Spacer(Modifier.height(12.dp))

            val textPrimaryArgb = TextPrimary.toArgb()
            val secondaryArgb = TextSecondary.toArgb()

            // 코덱스 H: 접근성 요약 (스크린 리더용) — validPts/avgBpm 은 상단에서 이미 계산.
            val maxPt = validPts.maxByOrNull { it.second }
            val stressA11ySummary = buildString {
                append("스트레스 흐름 차트. ")
                append("${days.first().a11yDate()}부터 ${days.last().a11yDate()}까지. ")
                if (avgBpm != null) append("평균 ${avgBpm} bpm. ")
                if (maxPt != null) append("가장 높은 날은 ${maxPt.first.a11yDate()}, ${maxPt.second} bpm.")
            }
            // 코덱스 H2 일관성: 선택 점 stateDescription (개별 점 semantics가 없는 Canvas 기반이라 컨테이너에 묶음)
            val stressSelectedState = selectedIdx?.let { idx ->
                val d = days[idx]
                val bpm = hrByDay[d]?.avgBpm
                if (bpm != null && bpm > 0) {
                    val (label, _) = stressLevelFromHr(bpm)
                    "${d.a11yDate()}, $bpm bpm, $label"
                } else {
                    "${d.a11yDate()}, 측정 데이터가 없습니다"
                }
            } ?: "선택된 날짜가 없습니다"

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .semantics {
                        contentDescription = stressA11ySummary
                        stateDescription = stressSelectedState
                    }
                    .pointerInput(days.size) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            // draw의 plotLeft/ptX0/plotW(인셋 포함)와 동일하게 맞춰 터치-점 위치 일치.
                            // [밀도 견고화] draw 블록과 동일하게 raw px → dp 변환 (터치/draw 동기화 유지).
                            //   rightPad 는 draw 와 동일 공식 — sp 기반이라 전역 폰트 스케일에 따라 우측 라벨 폭만큼 확보.
                            val leftEdge = 3.5.dp.toPx()                  // 10f → 3.5.dp(≈9.625px @2.75)
                            val rightPad = (10.sp.toPx() * 3f + 4.5.dp.toPx()).coerceAtLeast(20.dp.toPx())  // 12f→4.5dp, 56f→20dp
                            // plotPad 는 draw 와 동일 — 점 반지름 + 넉넉한 여유(8.dp). 터치 x-매핑 일치 유지.
                            val plotPad = (if (period == StressPeriod.DAYS_7) 5.dp.toPx() else 3.dp.toPx()) + 8.dp.toPx()  // 14f/8f→5dp/3dp, 22f→8dp
                            val plotW = (size.width - leftEdge - rightPad - plotPad * 2).coerceAtLeast(1f)
                            val n = days.size
                            fun idxFromX(x: Float): Int {
                                if (n <= 1) return 0
                                val ratio = ((x - leftEdge - plotPad).coerceIn(0f, plotW) / plotW)
                                return kotlin.math.round(ratio * (n - 1)).toInt().coerceIn(0, n - 1)
                            }
                            selectedIdx = idxFromX(down.position.x)
                            drag(down.id) { change ->
                                selectedIdx = idxFromX(change.position.x)
                                change.consume()
                            }
                        }
                    }
            ) {
                // [재구성] 걸음 막대그래프(ActivityBarChartCard)의 깔끔한 레이아웃을 그대로 차용:
                //   · 플롯 영역을 경계가 분명한 박스(옅은 배경)로 한정 → 그래프/축라벨 시각 분리
                //   · Y 레벨 라벨은 박스 오른쪽 전용 여백(rightPad), X 날짜 라벨은 박스 아래 전용 여백(bottomPad)
                //   · 점을 좌우로 인셋(plotPad)해 슬롯 '중앙'에 → 첫/마지막 점이 격자 밖으로 안 튀고 우측 라벨도 안 침범
                // [사용자 피드백] 축 라벨을 sp 기반 px로 → 전역 글씨 스케일 반영(걸음·수면 차트와 크기 통일).
                val axisLabelPx = 10.sp.toPx()
                val avgLabelPx = 9.sp.toPx()
                // [재구성] 라벨이 박스 '밖으로 절대 안 넘치게' 여백을 sp(=폰트스케일 반영) 기준으로 산출.
                //   rightPad: 가장 긴 레벨 라벨 "편안함"(3자) 폭 + 좌측 8f 간격 + 여유. bottomPad: 날짜 라벨 높이 + 점/라벨 간격.
                // [밀도 견고화] 여백/인셋·점 반지름 raw px → dp (density 2.75서 같은 px, 다른 밀도서 비례 스케일).
                //   sp 기반 항(axisLabelPx*N, avgLabelPx)·coerceAtLeast floor 도 dp 화 → 고밀도서 라벨 커지면 거터도 같이 커짐.
                val labelGap = 3.dp.toPx()                        // 8f → 3.dp(≈8.25px @2.75)
                // [사용자 피드백] 데이터 좌우 인셋 — 점 반지름보다 넉넉히 크게 잡아 첫/끝 점이 격자선·우측 라벨을 침범하지 않게.
                val dotRadius = if (period == StressPeriod.DAYS_7) 5.dp.toPx() else 3.dp.toPx()  // 14f/8f → 5dp/3dp
                // [사용자 피드백] "평균" 라벨이 어떤 점에도 안 가려지게 박스 위 전용 띠를 확보:
                //   가장 높은 '높음' 점은 박스 상단(plotTop)에 중심이 오므로 위로 dotRadius 만큼 솟음 → 그보다 더 위에 라벨.
                // 박스 상단 여백 — 가장 높은 점이 천장에 안 붙도록 약간의 헤드룸(평균 숫자는 이제 캔버스 밖).
                val topPad = (dotRadius + avgLabelPx + labelGap).coerceAtLeast(7.dp.toPx())   // 20f → 7.dp
                val leftEdge = 3.5.dp.toPx()        // 10f → 3.5.dp · 박스 좌측 시작
                val rightPad = (axisLabelPx * 3f + labelGap + 1.5.dp.toPx()).coerceAtLeast(20.dp.toPx())  // 4f→1.5dp, 56f→20dp · 우측 레벨 라벨 전용 여백
                val bottomPad = (axisLabelPx + labelGap * 2f).coerceAtLeast(14.5.dp.toPx())   // 40f → 14.5.dp · 아래 날짜 라벨 전용 여백
                // [사용자 피드백] 좌우 인셋을 더 키워 점 행을 가로 중앙으로 — 첫/끝 점이 좌우 대칭 여백을 갖게.
                //   점 반지름 + 넉넉한 여유(8.dp). 첫 점=plotLeft+plotPad, 끝 점=plotRight-plotPad 로 대칭.
                val plotPad = dotRadius + 8.dp.toPx()             // 22f → 8.dp
                val plotLeft = leftEdge
                val plotRight = size.width - rightPad
                val plotTop = topPad
                val plotBottom = size.height - bottomPad
                val h = plotBottom - plotTop

                // [재구성] 플롯 영역 박스 — 걸음 차트처럼 그래프와 축 라벨을 '확실히' 구분.
                val boxCornerPx = 6.dp.toPx()   // [밀도] 16f → 6.dp(≈16.5px @2.75)
                drawRoundRect(color = BgCardSoft,
                    topLeft = androidx.compose.ui.geometry.Offset(plotLeft, plotTop),
                    size = androidx.compose.ui.geometry.Size(plotRight - plotLeft, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(boxCornerPx, boxCornerPx))

                fun yFor(v: Int): Float {
                    val ratio = ((v - minBpm) / span).coerceIn(0f, 1f)
                    return plotTop + h - h * ratio
                }

                // 가로 격자 + Y축 라벨 (4단계) — 라인은 박스 폭, 라벨은 박스 오른쪽 여백.
                // [사용자 피드백] 가로선 bpm 은 '고정'(높음100·보통80·낮음70·편안함60) — 시간별 차트와 동일.
                //   (minBpm/maxBpm 은 헤드룸 포함 y범위라 가로선 값으로 쓰면 안 됨.)
                val levelLabels = listOf("높음" to 100, "보통" to 80, "낮음" to 70, "편안함" to 60)
                val labelPaint = android.graphics.Paint().apply {
                    color = secondaryArgb
                    textSize = axisLabelPx
                    textAlign = android.graphics.Paint.Align.LEFT
                    isAntiAlias = true
                }
                // [사용자 피드백] 라벨을 가로선 위에 '세로 중앙' 정렬 → 점·선·글자가 같은 높이에 깔끔히 맞음.
                val labelVCenter = -(labelPaint.fontMetrics.ascent + labelPaint.fontMetrics.descent) / 2f
                val levelLabelGap = 3.dp.toPx()   // [밀도] 우측 라벨 x-오프셋 8f → 3.dp
                levelLabels.forEach { (label, bpm) ->
                    val y = yFor(bpm)
                    drawLine(color = Divider, start = androidx.compose.ui.geometry.Offset(plotLeft, y), end = androidx.compose.ui.geometry.Offset(plotRight, y), strokeWidth = 0.4.dp.toPx())   // [밀도] 1f → 0.4.dp
                    drawIntoCanvas { c ->
                        c.nativeCanvas.drawText(label, plotRight + levelLabelGap, y + labelVCenter, labelPaint)
                    }
                }

                // [v1.0 피드백] 기간 평균선 (점선) — 7일/31일에도 표시. 점선 '선'은 캔버스에 유지.
                //   [사용자 피드백] "평균 NN" 숫자 라벨은 캔버스 밖(제목 아래 텍스트)으로 이동 → 여기선 선만.
                if (avgBpm != null && avgBpm > 0) {
                    val avgY = yFor(avgBpm)
                    val (_, avgCol) = stressLevelFromHr(avgBpm)
                    drawLine(
                        color = avgCol.copy(alpha = 0.6f),
                        start = androidx.compose.ui.geometry.Offset(plotLeft, avgY),
                        end = androidx.compose.ui.geometry.Offset(plotRight, avgY),
                        strokeWidth = 0.5.dp.toPx(),   // [밀도] 1.5f → 0.5.dp
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx()), 0f)  // [밀도] 8f,6f → 3dp,2dp
                    )
                }

                // 데이터 포인트 좌표 + 색상 — 좌우 인셋(plotPad). 첫 점 plotLeft+plotPad, 끝 점 plotRight-plotPad.
                val n = days.size
                val ptX0 = plotLeft + plotPad
                val innerW = (plotRight - plotLeft) - plotPad * 2
                val stepX = if (n > 1) innerW / (n - 1) else 0f
                data class Pt(val x: Float, val y: Float, val color: Color)
                // [사용자 피드백] 점을 '실제 평균 bpm' 높이에 찍어 일별 변동을 그대로 보이게(스냅 제거).
                //   예전엔 레벨 가로선에 스냅해 같은 레벨 날이 전부 같은 높이(직선)였음 → 76 vs 84가 구분 안 됨.
                //   이제 yFor(실제 bpm)으로 높이를 결정하고, 점 '색'만 레벨(stressLevelFromHr)로 매핑.
                val pts = days.mapIndexed { i, d ->
                    val hr = hrByDay[d]?.avgBpm
                    if (hr != null && hr > 0) {
                        val (_, c) = stressLevelFromHr(hr)
                        Pt(ptX0 + i * stepX, yFor(hr), c)
                    } else null
                }

                // [v1.0 피드백 — 사용자 추가] 선택된 날짜 "컬럼 전체" 세로 띠 (일일 차트와 통일). 라인/점 뒤 z-order.
                selectedIdx?.let { sel ->
                    val cx = ptX0 + sel * stepX
                    val bandW = (if (n > 1) stepX else innerW).coerceAtMost(20.dp.toPx())
                    var left = cx - bandW / 2f
                    if (left < plotLeft) left = plotLeft
                    if (left + bandW > plotRight) left = plotRight - bandW
                    drawRect(
                        color = Slate.copy(alpha = 0.15f),
                        topLeft = androidx.compose.ui.geometry.Offset(left, plotTop),
                        size = androidx.compose.ui.geometry.Size(bandW, h)
                    )
                }

                // 라인 연결 (연속된 non-null) — 삼성헬스처럼 회색 라인 + 색 점
                for (i in 0 until pts.size - 1) {
                    val p1 = pts[i]; val p2 = pts[i + 1]
                    if (p1 != null && p2 != null) {
                        drawLine(color = Color.Gray.copy(alpha = 0.6f), start = androidx.compose.ui.geometry.Offset(p1.x, p1.y), end = androidx.compose.ui.geometry.Offset(p2.x, p2.y), strokeWidth = 0.9.dp.toPx())   // [밀도] 2.5f → 0.9.dp
                    }
                }
                // 점 — 솔리드 컬러 (삼성헬스 스타일) + 선택된 점은 흰 외곽
                pts.forEachIndexed { i, p ->
                    if (p != null) {
                        if (selectedIdx == i) {
                            // 선택 강조: 흰색 외곽 + 색상 점. [밀도] 외곽 +4f → +1.5.dp.
                            drawCircle(Color.White, dotRadius + 1.5.dp.toPx(), androidx.compose.ui.geometry.Offset(p.x, p.y))
                            drawCircle(p.color, dotRadius, androidx.compose.ui.geometry.Offset(p.x, p.y))
                        } else {
                            drawCircle(p.color, dotRadius, androidx.compose.ui.geometry.Offset(p.x, p.y))
                        }
                    }
                }

                // X축 날짜 라벨 — 박스 '아래' 전용 여백(bottomPad)에 배치 → 점과 확실한 세로 간격(걸음 차트처럼).
                val dayPaint = android.graphics.Paint().apply {
                    color = secondaryArgb
                    textSize = axisLabelPx
                    textAlign = android.graphics.Paint.Align.CENTER
                    isAntiAlias = true
                }
                // 라벨을 아래 여백(bottomPad)의 세로 중앙에 — 폰트 스케일이 커도 박스 밖으로 안 넘침.
                val dayLabelBaseline = plotBottom + labelGap + (-dayPaint.fontMetrics.ascent)
                val labelInterval = when (period) {
                    StressPeriod.DAY_1 -> 1
                    StressPeriod.DAYS_7 -> 1
                    StressPeriod.DAYS_31 -> 5
                }
                // [사용자 피드백] 날짜 형식을 걸음·수면 차트와 통일 — 월이 바뀔 때만 'M/d', 그 외 'd' (예: 6/1 2 3 4 5).
                // [정렬 수정] 모든 날짜 라벨을 CENTER 정렬 → 라벨 시각 중심이 점 x(ptX0 + i*stepX)와 정확히 일치.
                //   첫/끝 점은 plotPad(=dotRadius+22f) 만큼 안쪽으로 인셋돼 있어 CENTER 라벨도 박스 밖으로 안 넘침.
                dayPaint.textAlign = android.graphics.Paint.Align.CENTER
                var prevLabelMonth = -1
                days.forEachIndexed { i, d ->
                    if (i % labelInterval == 0 || i == days.lastIndex) {
                        val labelText = if (d.monthValue != prevLabelMonth) "${d.monthValue}/${d.dayOfMonth}" else "${d.dayOfMonth}"
                        prevLabelMonth = d.monthValue
                        drawIntoCanvas { c ->
                            c.nativeCanvas.drawText(labelText, ptX0 + i * stepX, dayLabelBaseline, dayPaint)
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))

            // 선택된 점 툴팁 + 일일 점프
            val sel = selectedIdx
            if (sel != null) {
                val selDate = days[sel]
                val selHr = hrByDay[selDate]?.avgBpm
                Spacer(Modifier.height(8.dp))
                val stressTipA11y = if (selHr != null && selHr > 0) {
                    val (lbl, _) = stressLevelFromHr(selHr)
                    "${selDate.a11yDate()}, $selHr bpm, $lbl. 두 번 누르면 일일 보기로 이동합니다."
                } else {
                    "${selDate.a11yDate()}, 측정 데이터 없음"
                }
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onDayClick(selDate) }
                        .semantics {
                            liveRegion = LiveRegionMode.Polite
                            contentDescription = stressTipA11y
                        },
                    shape = RoundedCornerShape(12.dp),
                    color = Primary.copy(alpha = 0.12f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "${selDate.monthValue}월 ${selDate.dayOfMonth}일 (${selDate.dayOfWeek.koreanLabel()})",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                            Spacer(Modifier.height(2.dp))
                            if (selHr != null && selHr > 0) {
                                val (lbl, col) = stressLevelFromHr(selHr)
                                Text(
                                    "$selHr bpm · $lbl",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = col
                                )
                            } else {
                                Text(
                                    "측정 데이터 없음",
                                    fontSize = 14.sp,
                                    color = TextSecondary
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("일일 보기", fontSize = 11.sp, color = Primary, fontWeight = FontWeight.SemiBold)
                            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = Primary, modifier = Modifier.size(16.dp))
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            // [Fix 2] 일별 차트에도 최고/평균/최저 bpm 통계 행 추가 (7일·31일 공통).
            //   소스 = 기간 내 '일별 평균 bpm'(non-null avgBpm) 리스트 = validPts.map{it.second}.
            //   평균은 위에서 계산한 avgBpm(반올림 Int, 평균 텍스트와 동일 반올림) 재사용.
            //   시간별 카드의 stat 행과 동일한 Row + 세로 Divider + StatItem 스타일로 일관성 유지.
            val dailyBpms = validPts.map { it.second }
            if (dailyBpms.isNotEmpty() && avgBpm != null) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = Divider)
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    StatItem("${dailyBpms.max()} bpm", "최고")
                    Box(modifier = Modifier.width(1.dp).height(36.dp).background(Divider))
                    StatItem("${avgBpm} bpm", "평균")
                    Box(modifier = Modifier.width(1.dp).height(36.dp).background(Divider))
                    StatItem("${dailyBpms.min()} bpm", "최저")
                }
            }
            Spacer(Modifier.height(12.dp))

            Text(
                "※ 평균 심박을 기반으로 한 스트레스 추정치예요 (의료 진단 X)",
                fontSize = 10.sp,
                color = TextDisabled
            )
        }
    }
}

// ==============================================
// 공통: 워치 미착용 안내 카드 (당봄 #14)
// "워치를 착용하시면 건강 상태를 분석해 드립니다"
// ==============================================
@Composable
private fun WatchAbsentNotice(
    timeLabel: String,           // "오늘" / "이 날" / "어젯밤"
    subject: String = "건강 상태", // "건강 상태" / "수면" / "스트레스"
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = LimeLight.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("⌚", fontSize = 28.sp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "$timeLabel ${subject} 데이터가 없어요",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = PrimaryDark
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "워치를 착용하시면 ${subject}를 분석해 드립니다",
                    fontSize = 12.sp,
                    color = TextPrimary,
                    lineHeight = 17.sp
                )
            }
        }
    }
}
