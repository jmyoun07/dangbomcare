@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.dangbomcare.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dangbomcare.app.data.ServiceLocator
import com.dangbomcare.app.data.repo.ApiResult
import com.dangbomcare.app.ui.components.LoadingIndicator
import com.dangbomcare.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * 공복혈당 상세 (당봄 #16).
 * 7일 / 31일 탭 + 그래프 (사용자 피드백: 12개월 탭 삭제).
 *
 * 정상 범위 70~130 점선 + 색상 강조 (130 초과 빨강, 70 미만 파랑).
 */
private enum class GlucosePeriod(val days: Int, val label: String) {
    DAYS_7(7, "7일"),
    DAYS_31(31, "31일")
}

// [목표 공복혈당 범위] 의료진 목표(단일 수치)가 있으면 1순위로 [T-20, T] 밴드.
//   없으면 개인 평소범위 하한(personalLow)에서 [pl-20, pl] 도출 (예 130~150 입력 → 목표 110~130).
//   둘 다 없으면 기존 기본 70~130 (대한당뇨병학회 < 130). 하한은 70 미만으로 안 내려감(저혈당 보호).
internal fun computeGlucoseTargetRange(medicalTarget: Int?, personalLow: Int?): Pair<Int, Int> {
    val raw = medicalTarget ?: personalLow ?: return 70 to 130
    // [QA] 목표 상한을 90~400으로 제한 → 하한(상한-20, 최소 70)이 절대 상한을 넘지 않게(역전 방지) + 비현실적 저혈당 목표 차단
    val high = raw.coerceIn(90, 400)
    val low = (high - 20).coerceAtLeast(70)
    return low to high
}

@Composable
fun DetailGlucoseScreen(onBack: () -> Unit, initialDate: String? = null, initialPeriod: String? = null) {
    // [v1.0 피드백] initialDate가 있으면 그 날짜를 anchor로 7일/31일 윈도우 표시
    //   리포트의 주간 클릭 → 그 주의 endDate를 받아서 그 주를 표시
    val initialAnchor = remember(initialDate) {
        initialDate?.let { try { LocalDate.parse(it) } catch (_: Exception) { LocalDate.now() } } ?: LocalDate.now()
    }
    // [v1.0 피드백] 좌우 화살표로 기간 이동 가능하도록 endDate 상태 추가
    var glucoseEndDate by remember { mutableStateOf(initialAnchor) }
    val today = remember { LocalDate.now() }
    var period by remember {
        mutableStateOf(
            when (initialPeriod) {
                "31d" -> GlucosePeriod.DAYS_31
                else -> GlucosePeriod.DAYS_7
            }
        )
    }
    var glucoseByDate by remember { mutableStateOf<Map<LocalDate, Int>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }

    // [260623 피드백] 추이 그래프는 당뇨인 임상 표준 3구간(정상 70~100 / 당뇨 목표 100~130 / 높음 >130)을 고정 표시.
    //   개인 평소범위·의료진 목표는 '점수 계산'에만 사용하고, 이 그래프의 기준선/통계는 통일된 임상 3구간으로 둔다.
    //   (이전엔 동적 개인범위였는데 설명란의 고정 3구간과 충돌 — 코덱스 P0 교차검증 반영.)
    val targetLow = 70
    val targetHigh = 130

    LaunchedEffect(period, glucoseEndDate) {
        loading = true
        try {
            // ⚠️ 백엔드 list API가 fasting_glucose 수치를 반환하지 않는 버그 우회 (당봄 #16)
            // → 각 날짜를 개별 get으로 순차 조회 (안정성 우선, 31일치 약 3~5초)
            val dates = periodDatesOf(glucoseEndDate, periodIsMonth(period.days))
            val map = mutableMapOf<LocalDate, Int>()
            for (date in dates) {
                try {
                    val res = ServiceLocator.dailyLogRepository.get(date.toString())
                    if (res is ApiResult.Success) {
                        res.data.log?.fastingGlucose?.let { map[date] = it }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e  // [코덱스 P1] 취소는 전파 — 기간 전환 시 stale 로드가 최신 데이터를 덮어쓰지 않게
                } catch (e: Exception) { /* 이 날만 스킵 */ }
            }
            glucoseByDate = map.toMap()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) { /* keep empty */ }
        loading = false
    }

    Scaffold(
        containerColor = BgSecondary,
        topBar = {
            TopAppBar(
                title = { Text("🩸 공복혈당", fontWeight = FontWeight.Bold) },
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
            modifier = Modifier.padding(padding).fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            PeriodTabsGlucose(period) {
                if (it != period) {
                    period = it
                    // 사용자가 직접 기간 탭 클릭 시 endDate를 오늘로 리셋 (걸음/수면과 동일 UX)
                    glucoseEndDate = today
                }
            }

            if (loading) {
                LoadingIndicator(modifier = Modifier.height(200.dp))
            } else {
                GlucoseStatsCard(glucoseByDate, period, glucoseEndDate, targetLow, targetHigh)
                GlucoseLineChartCard(
                    glucoseByDate = glucoseByDate,
                    period = period,
                    endDate = glucoseEndDate,
                    targetLow = targetLow,
                    targetHigh = targetHigh,
                    onEndDateChange = { glucoseEndDate = it }
                )
                GlucoseRangeGuideCard()
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun PeriodTabsGlucose(selected: GlucosePeriod, onSelect: (GlucosePeriod) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BgCardSoft)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        GlucosePeriod.values().forEach { p ->
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
private fun GlucoseStatsCard(glucoseByDate: Map<LocalDate, Int>, period: GlucosePeriod, endDate: LocalDate = LocalDate.now(), targetLow: Int = 70, targetHigh: Int = 130) {
    val im = periodIsMonth(period.days)
    val from = periodStartOf(endDate, im)
    val winEnd = periodEndOf(endDate, im)
    val winLen = (winEnd.toEpochDay() - from.toEpochDay()).toInt() + 1
    val values = glucoseByDate.values
    val nonEmpty = values.isNotEmpty()
    val avg = if (nonEmpty) values.average().toInt() else 0
    val maxV = values.maxOrNull() ?: 0
    val minV = values.minOrNull() ?: 0
    val normalDays = values.count { it in targetLow..targetHigh }
    val highDays = values.count { it > targetHigh }
    val lowDays = values.count { it < targetLow }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        border = BorderStroke(1.dp, Divider)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "${from.monthValue}월 ${from.dayOfMonth}일 ~ ${winEnd.monthValue}월 ${winEnd.dayOfMonth}일",
                fontSize = 12.sp,
                color = TextSecondary
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    if (nonEmpty) "$avg" else "-",
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Black,
                    color = if (avg in targetLow..targetHigh) Primary else if (avg > targetHigh) ErrorRed else TextSecondary
                )
                Spacer(Modifier.width(6.dp))
                Text("mg/dL 평균", fontSize = 14.sp, color = TextSecondary, modifier = Modifier.padding(bottom = 6.dp))
            }
            Spacer(Modifier.height(12.dp))
            if (nonEmpty) {
                // [반응형] 작은 화면(360dp)+시니어 폰트(1.4x)에서 3개 통계+구분선이 넘치지 않게
                //   각 통계에 weight(1f) 부여 → 폭을 균등 분배하고 텍스트는 가운데 정렬로 줄바꿈/축소 흡수.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlucoseMiniStat("$minV", "최저", modifier = Modifier.weight(1f))
                    Box(modifier = Modifier.width(1.dp).height(36.dp).background(Divider))
                    GlucoseMiniStat("$maxV", "최고", modifier = Modifier.weight(1f))
                    Box(modifier = Modifier.width(1.dp).height(36.dp).background(Divider))
                    GlucoseMiniStat("${values.size}/${winLen}일", "기록", modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = Divider)
                Spacer(Modifier.height(10.dp))
                Row {
                    Text("정상·당뇨목표 (${targetLow}~${targetHigh})", fontSize = 13.sp, color = TextSecondary, modifier = Modifier.weight(1f))
                    Text("${normalDays}일", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Success)
                }
                Spacer(Modifier.height(6.dp))
                Row {
                    Text("높음 (>${targetHigh})", fontSize = 13.sp, color = TextSecondary, modifier = Modifier.weight(1f))
                    Text("${highDays}일", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = ErrorRed)
                }
                if (lowDays > 0) {
                    Spacer(Modifier.height(6.dp))
                    Row {
                        Text("낮음 (<${targetLow})", fontSize = 13.sp, color = TextSecondary, modifier = Modifier.weight(1f))
                        Text("${lowDays}일", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1976D2))
                    }
                }
            } else {
                Text("기간 내 입력 데이터가 없어요", fontSize = 13.sp, color = TextSecondary)
            }
        }
    }
}

@Composable
private fun GlucoseMiniStat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            maxLines = 1,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Text(label, fontSize = 11.sp, color = TextSecondary, maxLines = 1)
    }
}

@Composable
private fun GlucoseLineChartCard(
    glucoseByDate: Map<LocalDate, Int>,
    period: GlucosePeriod,
    endDate: LocalDate = LocalDate.now(),
    targetLow: Int = 70,
    targetHigh: Int = 130,
    onEndDateChange: (LocalDate) -> Unit = {}
) {
    val today = LocalDate.now()
    val days = periodDatesOf(endDate, periodIsMonth(period.days))
    val values = days.map { glucoseByDate[it] }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        border = BorderStroke(1.dp, Divider)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            // [v1.0 피드백] 좌우 화살표 — 이전/다음 기간으로 이동 (걸음/수면과 동일 UX)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { onEndDateChange(periodPrevOf(endDate, periodIsMonth(period.days))) }) {
                    Icon(Icons.Filled.ChevronLeft, contentDescription = "이전 기간", tint = Primary)
                }
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    val rangeLabel = "${days.first().monthValue}월 ${days.first().dayOfMonth}일 - ${days.last().monthValue}월 ${days.last().dayOfMonth}일"
                    Text("일별 공복혈당 추이", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Spacer(Modifier.height(2.dp))
                    Text(rangeLabel, fontSize = 12.sp, color = TextSecondary)
                }
                IconButton(
                    onClick = {
                        if (periodEndOf(endDate, periodIsMonth(period.days)) < periodEndOf(today, periodIsMonth(period.days))) {
                            onEndDateChange(periodNextOf(endDate, periodIsMonth(period.days)))
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
            Spacer(Modifier.height(6.dp))
            // [260705 R3] '점선:' 접두 제거 + 왼쪽 라벨 / 오른쪽 숫자 정렬 통일 (3줄)
            GlucoseRangeGuideRow("정상", "70~100")
            GlucoseRangeGuideRow("당뇨 목표", "100~130")
            GlucoseRangeGuideRow("높음", "130 초과")
            Spacer(Modifier.height(12.dp))
            GlucoseTrendLineChart(days = days, values = values, period = period, targetLow = targetLow, targetHigh = targetHigh)
        }
    }
}

/** [260705 R3] 3구간 안내 한 줄 — 왼쪽 라벨 / 오른쪽 숫자 정렬. */
@Composable
private fun GlucoseRangeGuideRow(label: String, range: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(label, fontSize = 12.sp, color = TextSecondary, modifier = Modifier.weight(1f))
        Text(range, fontSize = 12.sp, color = TextSecondary, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun GlucoseTrendLineChart(
    days: List<LocalDate>,
    values: List<Int?>,
    period: GlucosePeriod,
    targetLow: Int = 70,
    targetHigh: Int = 130,
) {
    val minRange = 50f
    // ⚠️ 의료 정확성: 200 초과 값 + 목표 상한도 잘리지 않게 동적 조정 (Codex 검토 수정)
    val dataMax = maxOf(values.filterNotNull().maxOrNull() ?: 200, targetHigh)
    val maxRange = maxOf(200, ((dataMax + 30) / 50) * 50 + (if ((dataMax + 30) % 50 > 0) 50 else 0)).toFloat()
    val rangeSpan = maxRange - minRange
    val textPrimaryArgb = TextPrimary.toArgb()
    val secondaryArgb = TextSecondary.toArgb()

    Canvas(modifier = Modifier.fillMaxWidth().height(240.dp)) {
        // [반응형] 모든 px 상수를 dp.toPx()로 — 저/고밀도 기기에서 동일 비율 유지 (테스트 기기 density≈2.75 기준 N/2.75)
        val topPad = 10.dp.toPx()
        val bottomPad = 20.dp.toPx()   // 사용자 피드백: X축 날짜 라벨이 50 라인과 겹쳐서 여백 확보
        val sidePad = 13.dp.toPx()
        // [260705 피드백] 우측 130/100/70 라벨 전용 여백 — 마지막 점 값 라벨과 겹치던 문제 해결
        //   (코덱스 2차: 값 라벨이 점 중앙정렬이라 gutter로 ~10dp 침범 → 36dp + 기준라벨 우측정렬로 확실히 분리)
        val rightPad = 36.dp.toPx()
        val w = size.width - sidePad - rightPad
        val h = size.height - topPad - bottomPad

        fun yFor(v: Int): Float {
            val ratio = ((v - minRange) / rangeSpan).coerceIn(0f, 1f)
            return topPad + h - h * ratio
        }

        // 목표 범위 영역 음영 (y70=목표 하한, y130=목표 상한 — 변수명은 기존 유지)
        val y70 = yFor(targetLow)
        val y130 = yFor(targetHigh)
        drawRect(
            color = Color(0xFF006E1C).copy(alpha = 0.06f),
            topLeft = Offset(sidePad, y130),
            size = androidx.compose.ui.geometry.Size(w, y70 - y130)
        )

        // 가로 격자 + Y라벨 (50, 150, 200 — 100은 아래 3구간 점선이 대신함)
        val gridPaint = android.graphics.Paint().apply {
            color = secondaryArgb
            textSize = 26f / 2.75f * density   // [반응형] sp 대신 px → density로 스케일
            textAlign = android.graphics.Paint.Align.RIGHT
            isAntiAlias = true
        }
        listOf(50, 150, 200).forEach { v ->
            val y = yFor(v)
            drawLine(Divider, Offset(sidePad, y), Offset(size.width - rightPad, y), 1.dp.toPx())
            drawIntoCanvas { c -> c.nativeCanvas.drawText("$v", sidePad - 1.5.dp.toPx(), y + 3.dp.toPx(), gridPaint) }
        }

        // [260623 피드백] 당뇨인 3구간 경계 점선 — 70(정상 하한) / 100(정상·목표 경계) / 130(목표·높음 경계)
        val y100 = yFor(100)
        val targetPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.rgb(0, 110, 28)
            strokeWidth = 1.dp.toPx()
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(4.4.dp.toPx(), 3.dp.toPx()), 0f)
            isAntiAlias = true
        }
        drawIntoCanvas { c ->
            c.nativeCanvas.drawLine(sidePad, y70, size.width - rightPad, y70, targetPaint)
            c.nativeCanvas.drawLine(sidePad, y100, size.width - rightPad, y100, targetPaint)
            c.nativeCanvas.drawLine(sidePad, y130, size.width - rightPad, y130, targetPaint)
        }
        val labelPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.rgb(0, 110, 28)
            textSize = 22f / 2.75f * density   // [반응형] sp 대신 px → density로 스케일
            textAlign = android.graphics.Paint.Align.RIGHT   // 화면 오른쪽 끝 기준 정렬 → 값 라벨과 안 겹침
            isFakeBoldText = true
            isAntiAlias = true
        }
        drawIntoCanvas { c ->
            c.nativeCanvas.drawText("$targetHigh", size.width - 1.5.dp.toPx(), y130 + 2.dp.toPx(), labelPaint)
            c.nativeCanvas.drawText("100", size.width - 1.5.dp.toPx(), y100 + 2.dp.toPx(), labelPaint)
            c.nativeCanvas.drawText("$targetLow", size.width - 1.5.dp.toPx(), y70 + 2.dp.toPx(), labelPaint)
        }

        // 데이터 포인트
        val n = days.size
        val stepX = if (n > 1) w / (n - 1) else 0f
        val points = values.mapIndexed { i, v ->
            v?.let { Offset(sidePad + i * stepX, yFor(it)) }
        }

        // 라인 연결
        for (i in 0 until points.size - 1) {
            val p1 = points[i]; val p2 = points[i + 1]
            if (p1 != null && p2 != null) {
                drawLine(Primary, p1, p2, 1.dp.toPx())
            }
        }

        // 점 + 값 라벨 (7일 한정)
        val showLabels = period == GlucosePeriod.DAYS_7
        val valuePaint = android.graphics.Paint().apply {
            color = textPrimaryArgb
            textSize = 30f / 2.75f * density   // [반응형] sp 대신 px → density로 스케일
            textAlign = android.graphics.Paint.Align.CENTER
            isFakeBoldText = true
            isAntiAlias = true
        }
        val dayPaint = android.graphics.Paint().apply {
            color = secondaryArgb
            textSize = 24f / 2.75f * density   // [반응형] sp 대신 px → density로 스케일
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }
        points.forEachIndexed { i, p ->
            val v = values[i]
            val xCenter = sidePad + i * stepX
            val labelInterval = when (period) {
                GlucosePeriod.DAYS_7 -> 1
                GlucosePeriod.DAYS_31 -> 5
            }
            if (i % labelInterval == 0 || i == days.lastIndex) {
                val d = days[i]
                drawIntoCanvas { c ->
                    // 50 라인(yFor(50)) 아래 bottomPad 영역 가운데 정도에 위치
                    c.nativeCanvas.drawText(
                        "${d.monthValue}/${d.dayOfMonth}",
                        xCenter, size.height - 6.dp.toPx(), dayPaint
                    )
                }
            }
            if (p != null && v != null) {
                // 색상: 목표 하한 미만 파랑 / 목표 범위 내 녹색 / 목표 상한 초과 빨강
                val pointColor = when {
                    v < targetLow -> Color(0xFF1976D2)
                    v in targetLow..targetHigh -> Success
                    else -> ErrorRed
                }
                drawCircle(pointColor, if (period == GlucosePeriod.DAYS_7) 4.dp.toPx() else 2.2.dp.toPx(), p)
                drawCircle(BgCard, if (period == GlucosePeriod.DAYS_7) 1.8.dp.toPx() else 0.7.dp.toPx(), p)
                if (showLabels) {
                    drawIntoCanvas { c ->
                        c.nativeCanvas.drawText("$v", p.x, p.y - 5.dp.toPx(), valuePaint)
                    }
                }
            }
        }
    }
}

@Composable
private fun GlucoseRangeGuideCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = LimeLight.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // [260623 피드백] 당뇨인 3구간 안내 — 기존 '목표 범위' 설명이 모호하다는 피드백 반영
            Text("💡 공복혈당 3구간 안내", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = PrimaryDark)
            Spacer(Modifier.height(6.dp))
            Text(
                "• 정상: 70~100 mg/dL\n" +
                "• 당뇨인 목표 범위: 100~130 mg/dL (당뇨약 복용 중이면 이 안에 있어도 잘 관리되는 거예요)\n" +
                "• 높음: 130 초과 — 식이·운동 관리가 필요해요\n" +
                "70 미만은 저혈당이라 주의가 필요해요. 그래프의 점선이 70·100·130 경계예요.",
                fontSize = 12.sp,
                color = TextPrimary,
                lineHeight = 18.sp
            )
        }
    }
}
