@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.dangbomcare.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dangbomcare.app.ui.theme.*
import kotlin.math.absoluteValue

/**
 * 삼성 시계 스타일 휠 피커.
 *
 * @param loop true일 때 끝에서 처음으로 순환 (23시 다음 0시).
 *             내부적으로 큰 가상 리스트 (items × 1000)를 사용하고 modulo로 실제 인덱스 계산.
 */
@Composable
fun WheelPicker(
    items: List<String>,
    initialIndex: Int = 0,
    visibleCount: Int = 5,
    itemHeight: Dp = 48.dp,
    loop: Boolean = false,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    // 시니어 큰 글씨(fontScale 1.3~2.0)에서 28sp 텍스트가 48dp 행을 넘지 않도록 행 높이를 배율에 맞춰 확대
    val rowHeight = itemHeight * LocalDensity.current.fontScale.coerceIn(1f, 1.5f)
    val itemCount = items.size
    // 순환 모드: 가상 리스트 = 실제 × 1000, 중간에서 시작 (양방향 스크롤 가능)
    val repeatFactor = if (loop) 1000 else 1
    val totalCount = itemCount * repeatFactor
    val centerOffset = if (loop) (totalCount / 2 / itemCount) * itemCount else 0
    val startIndex = (centerOffset + initialIndex.coerceIn(0, itemCount - 1)).coerceIn(0, totalCount - 1)

    val state = rememberLazyListState(initialFirstVisibleItemIndex = startIndex)
    val centerVirtualIndex by remember {
        derivedStateOf {
            val info = state.layoutInfo
            if (info.visibleItemsInfo.isEmpty()) startIndex
            else {
                val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2
                info.visibleItemsInfo
                    .minByOrNull { ((it.offset + it.size / 2) - viewportCenter).absoluteValue }
                    ?.index ?: startIndex
            }
        }
    }

    // 실제 선택된 인덱스 (loop이면 modulo, 아니면 그대로)
    val centerActualIndex = if (loop) centerVirtualIndex % itemCount else centerVirtualIndex

    LaunchedEffect(centerActualIndex) { onSelected(centerActualIndex) }

    val halfCount = visibleCount / 2

    LazyColumn(
        state = state,
        flingBehavior = rememberSnapFlingBehavior(lazyListState = state),
        contentPadding = PaddingValues(vertical = rowHeight * halfCount),
        modifier = modifier.height(rowHeight * visibleCount)
    ) {
        items(count = totalCount) { virtualIdx ->
            val actualIdx = if (loop) virtualIdx % itemCount else virtualIdx
            val distance = (virtualIdx - centerVirtualIndex).absoluteValue
            val isCenter = virtualIdx == centerVirtualIndex
            Box(
                modifier = Modifier.fillMaxWidth().height(rowHeight),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    items[actualIdx],
                    fontSize = if (isCenter) 28.sp else 20.sp,
                    fontWeight = if (isCenter) FontWeight.Bold else FontWeight.Normal,
                    color = when (distance) {
                        0 -> Primary
                        1 -> TextPrimary.copy(alpha = 0.5f)
                        2 -> TextPrimary.copy(alpha = 0.25f)
                        else -> TextPrimary.copy(alpha = 0.1f)
                    }
                )
            }
        }
    }
}

/**
 * 24시간제 시각 휠 피커 (시 + 분).
 * @return "HH:mm" 24시간 형식
 */
@Composable
fun WheelTimePicker(
    initialTime: String,  // "HH:mm" 24h
    onTimeChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val parts = initialTime.split(":")
    val initialH = parts.getOrNull(0)?.toIntOrNull() ?: 7
    val initialM = parts.getOrNull(1)?.toIntOrNull() ?: 0

    var hourIdx by remember { mutableStateOf(initialH) }       // 0~23
    var minuteIdx by remember { mutableStateOf(initialM) }     // 0~59

    LaunchedEffect(hourIdx, minuteIdx) {
        onTimeChange("%02d:%02d".format(hourIdx, minuteIdx))
    }

    Box(modifier = modifier.fillMaxWidth()) {
        // 가운데 강조 박스 (배경) — WheelPicker 행 높이와 동일하게 fontScale 반영
        val highlightHeight = 48.dp * LocalDensity.current.fontScale.coerceIn(1f, 1.5f)
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(highlightHeight)
                .background(LimeLight.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 시 (0~23, 24시간제) — 일반 모드 (UX 검토 후 순환 제거)
            WheelPicker(
                items = (0..23).map { "%02d".format(it) },
                initialIndex = hourIdx,
                onSelected = { hourIdx = it },
                modifier = Modifier.weight(1f)
            )
            Text(":", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Primary,
                modifier = Modifier.padding(horizontal = 8.dp))
            // 분 (00~59) — 일반 모드
            WheelPicker(
                items = (0..59).map { "%02d".format(it) },
                initialIndex = minuteIdx,
                onSelected = { minuteIdx = it },
                modifier = Modifier.weight(1f)
            )
        }
    }
}
