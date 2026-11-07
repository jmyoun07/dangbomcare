@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.dangbomcare.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dangbomcare.app.ui.theme.*
import java.time.LocalDate
import java.time.YearMonth

private val SaturdayBlue = Color(0xFF2563EB)

/**
 * 앱 공용 깔끔한 캘린더 다이얼로그 (Material 기본 DatePicker 대체).
 *
 * @param markedDates 점으로 표시할 날짜(예: 이미 입력한 날) — showMarkers=true일 때만 그림.
 * @param allowFuture 미래 날짜 선택 허용 (목표 시점 등). false면 오늘까지만.
 * @param allowPast   과거 날짜 선택 허용. false면 오늘부터만.
 * @param onMonthChange 표시 달이 바뀔 때 호출 (그 달의 markedDates를 로드하는 용도).
 */
@Composable
fun AppCalendarDialog(
    initialDate: LocalDate,
    onDismiss: () -> Unit,
    onSelect: (LocalDate) -> Unit,
    allowFuture: Boolean = false,
    allowPast: Boolean = true,
    showMarkers: Boolean = false,
    markedDates: Set<LocalDate> = emptySet(),
    onMonthChange: (YearMonth) -> Unit = {},
) {
    val today = LocalDate.now()
    val thisMonth = YearMonth.from(today)
    var displayedMonth by remember { mutableStateOf(YearMonth.from(initialDate)) }
    var showYmPicker by remember { mutableStateOf(false) }

    LaunchedEffect(displayedMonth) { onMonthChange(displayedMonth) }

    // [년/월 다이얼] 헤더 탭 시 년·월 휠 선택
    if (showYmPicker) {
        YearMonthPickerDialog(
            initial = displayedMonth,
            allowFuture = allowFuture,
            allowPast = allowPast,
            onDismiss = { showYmPicker = false },
            onConfirm = { ym -> displayedMonth = ym; showYmPicker = false }
        )
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = BgCard,
            modifier = Modifier.fillMaxWidth(0.92f)
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp)) {
                // 헤더 — 이전/다음 달
                val canPrev = allowPast || displayedMonth.isAfter(thisMonth)
                val canNext = allowFuture || displayedMonth.isBefore(thisMonth)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    IconButton(onClick = { if (canPrev) displayedMonth = displayedMonth.minusMonths(1) }, enabled = canPrev) {
                        Icon(Icons.Filled.ChevronLeft, "이전 달", tint = if (canPrev) TextPrimary else TextDisabled)
                    }
                    Text(
                        "${displayedMonth.year}년 ${displayedMonth.monthValue}월  ▾",
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { showYmPicker = true }
                            .padding(vertical = 6.dp),
                        textAlign = TextAlign.Center,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    IconButton(onClick = { if (canNext) displayedMonth = displayedMonth.plusMonths(1) }, enabled = canNext) {
                        Icon(Icons.Filled.ChevronRight, "다음 달", tint = if (canNext) TextPrimary else TextDisabled)
                    }
                }

                Spacer(Modifier.height(6.dp))

                // 요일 헤더
                Row(Modifier.fillMaxWidth()) {
                    listOf("일", "월", "화", "수", "목", "금", "토").forEachIndexed { i, d ->
                        Text(
                            d,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = when (i) { 0 -> ErrorRed; 6 -> SaturdayBlue; else -> TextSecondary }
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))

                // 날짜 그리드
                val firstDay = displayedMonth.atDay(1)
                val leadBlanks = firstDay.dayOfWeek.value % 7  // 일요일=0 컬럼
                val daysInMonth = displayedMonth.lengthOfMonth()
                val rawCells: List<LocalDate?> =
                    List(leadBlanks) { null } + (1..daysInMonth).map { displayedMonth.atDay(it) }
                // [고정 높이] 달마다 주 수(4~6)가 달라 헤더 버튼이 움직이는 것 방지 → 항상 6주(42칸)로 패딩.
                val cells: List<LocalDate?> = rawCells + List(42 - rawCells.size) { null }
                cells.chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth()) {
                        for (col in 0 until 7) {
                            val date = week.getOrNull(col)
                            Box(
                                modifier = Modifier.weight(1f).aspectRatio(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                if (date != null) {
                                    val isFuture = date.isAfter(today)
                                    val isPast = date.isBefore(today)
                                    val enabled = (allowFuture || !isFuture) && (allowPast || !isPast)
                                    val isSelected = date == initialDate
                                    val isToday = date == today
                                    val marked = showMarkers && date in markedDates
                                    val dowCol = date.dayOfWeek.value % 7
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = if (enabled) Modifier.clickable {
                                            onSelect(date); onDismiss()
                                        } else Modifier
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(CircleShape)
                                                .background(if (isSelected) Primary else Color.Transparent)
                                                .then(
                                                    if (isToday && !isSelected)
                                                        Modifier.border(1.5.dp, Primary, CircleShape)
                                                    else Modifier
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                "${date.dayOfMonth}",
                                                fontSize = 14.sp,
                                                fontWeight = if (isSelected || isToday) FontWeight.Bold else FontWeight.Normal,
                                                color = when {
                                                    !enabled -> TextDisabled
                                                    isSelected -> Color.White
                                                    dowCol == 0 -> ErrorRed
                                                    dowCol == 6 -> SaturdayBlue
                                                    else -> TextPrimary
                                                }
                                            )
                                        }
                                        // 입력 표시 점
                                        Spacer(Modifier.height(2.dp))
                                        Box(
                                            Modifier
                                                .size(5.dp)
                                                .clip(CircleShape)
                                                .background(if (marked) Primary else Color.Transparent)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (showMarkers) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(Primary))
                        Spacer(Modifier.width(5.dp))
                        Text("입력한 날", fontSize = 11.sp, color = TextSecondary)
                    }
                }

                Spacer(Modifier.height(6.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("닫기", color = TextSecondary, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** [년/월 다이얼] 헤더 탭 시 — 년·월을 휠로 빠르게 선택. */
@Composable
private fun YearMonthPickerDialog(
    initial: YearMonth,
    allowFuture: Boolean,
    allowPast: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (YearMonth) -> Unit,
) {
    val today = LocalDate.now()
    val thisMonth = YearMonth.from(today)
    val minYear = if (allowPast) today.year - 5 else today.year
    val maxYear = if (allowFuture) today.year + 5 else today.year
    val years = (minYear..maxYear).toList()

    var yearIdx by remember { mutableStateOf((initial.year - minYear).coerceIn(0, years.size - 1)) }
    var monthIdx by remember { mutableStateOf(initial.monthValue - 1) }  // 0..11

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(24.dp), color = BgCard, modifier = Modifier.fillMaxWidth(0.82f)) {
            // 가로모드/분할화면(높이 부족)에서 버튼 밀림 방지
            Column(
                modifier = Modifier.padding(20.dp)
                    .verticalScroll(androidx.compose.foundation.rememberScrollState())
            ) {
                Text(
                    "년 · 월 선택", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
                Spacer(Modifier.height(12.dp))
                Box {
                    // WheelPicker 행 높이(fontScale 반영)와 동일하게 하이라이트도 스케일
                    val highlightHeight = 48.dp * androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceIn(1f, 1.5f)
                    Box(
                        modifier = Modifier.align(Alignment.Center).fillMaxWidth().height(highlightHeight)
                            .background(LimeLight.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                    )
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        WheelPicker(
                            items = years.map { "${it}년" },
                            initialIndex = yearIdx,
                            onSelected = { yearIdx = it },
                            modifier = Modifier.weight(1f)
                        )
                        WheelPicker(
                            items = (1..12).map { "${it}월" },
                            initialIndex = monthIdx,
                            onSelected = { monthIdx = it },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text("취소", color = TextSecondary)
                    }
                    androidx.compose.material3.Button(
                        onClick = {
                            var picked = YearMonth.of(years[yearIdx], monthIdx + 1)
                            if (!allowFuture && picked.isAfter(thisMonth)) picked = thisMonth
                            if (!allowPast && picked.isBefore(thisMonth)) picked = thisMonth
                            onConfirm(picked)
                        },
                        modifier = Modifier.weight(1f),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Primary)
                    ) { Text("확인", fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}
