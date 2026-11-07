package com.dangbomcare.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dangbomcare.app.ui.theme.*

/**
 * [260705 R3] 한 줄 고정 + 넘치면 글자를 자동으로 줄이는 텍스트.
 * 날짜/기간 헤더처럼 '반드시 한 줄이어야 하는' 자리 전용 — 기기 폭·글자확대 설정과 무관하게
 * "2026년 6월 30일 (화)"가 두 줄로 꺾이는 문제를 원천 차단한다.
 */
@Composable
fun AutoSizeSingleLineText(
    text: String,
    modifier: Modifier = Modifier,
    maxFontSize: TextUnit = 18.sp,
    minFontSize: TextUnit = 12.sp,
    fontWeight: FontWeight? = null,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
    // [260706 R7] 그룹 글씨 크기 통일용 — 같은 목록의 모든 행에 '그룹에서 가장 긴 라벨'을 넘기면
    //   전 행이 그 라벨 기준으로 크기를 정해, 짧은 행만 크게 나오는 문제("얕은 수면만 큼")가 사라짐.
    fitText: String? = null,
) {
    val sizeBasis = fitText ?: text
    var fontSize by remember(sizeBasis, maxFontSize) { mutableStateOf(maxFontSize) }
    fun shrinkOn(result: androidx.compose.ui.text.TextLayoutResult) {
        if (result.hasVisualOverflow && fontSize.value > minFontSize.value) {
            fontSize = (fontSize.value * 0.9f).coerceAtLeast(minFontSize.value).sp
        }
    }
    Box(
        modifier = modifier,
        contentAlignment = when (textAlign) {
            TextAlign.Center -> Alignment.Center
            TextAlign.End -> Alignment.CenterEnd
            else -> Alignment.CenterStart
        }
    ) {
        if (sizeBasis != text) {
            // 측정 전용(투명) — 기준 라벨이 넘치면 fontSize를 줄여 그룹 전체가 같은 크기가 되게
            // [Codex] 스크린리더가 기준 문자열을 읽지 않도록 semantics 제거
            Text(
                text = sizeBasis,
                fontSize = fontSize,
                fontWeight = fontWeight,
                color = Color.Transparent,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                onTextLayout = ::shrinkOn,
                modifier = Modifier.clearAndSetSemantics { }
            )
        }
        Text(
            text = text,
            fontSize = fontSize,
            fontWeight = fontWeight,
            color = color,
            textAlign = textAlign,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            onTextLayout = ::shrinkOn
        )
    }
}

/**
 * 시니어 친화 — 56dp 이상 높이, 큰 폰트, 명확한 색상 대비.
 */
@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp),
        shape = RoundedCornerShape(16.dp),
        colors = if (primary) ButtonDefaults.buttonColors(
            containerColor = Primary,
            contentColor = Color.White,
            disabledContainerColor = TextDisabled
        ) else ButtonDefaults.buttonColors(
            containerColor = BgCardSoft,
            contentColor = Primary,
            disabledContainerColor = TextDisabled
        )
    ) {
        Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Divider)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            if (title != null) {
                Text(
                    title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(Modifier.height(12.dp))
            }
            content()
        }
    }
}

/**
 * 100점 만점 원형 점수 게이지.
 */
@Composable
fun ScoreGauge(
    score: Double,   // [260623] 소화 2.5점 → 총점 소수 가능
    label: String,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 200.dp
) {
    val color = when {
        score >= 70.0 -> ScoreGood
        score >= 50.0 -> ScoreOk
        else -> ScoreLow
    }
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.size(size)) {
            val stroke = 14.dp.toPx()
            val pad = stroke / 2
            // 배경 원 (회색 트랙)
            drawArc(
                color = androidx.compose.ui.graphics.Color(0xFFE4E2E1),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(pad, pad),
                size = androidx.compose.ui.geometry.Size(this.size.width - stroke, this.size.height - stroke),
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = stroke,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            )
            // 점수만큼만 채우는 원호
            val sweep = (score.coerceIn(0.0, 100.0).toFloat() / 100f) * 360f
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(pad, pad),
                size = androidx.compose.ui.geometry.Size(this.size.width - stroke, this.size.height - stroke),
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = stroke,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (score % 1.0 == 0.0) "${score.toInt()}" else "$score",
                fontSize = 64.sp,
                fontWeight = FontWeight.Black,
                color = color
            )
            Text("/ 100", fontSize = 14.sp, color = TextSecondary)
            Spacer(Modifier.height(4.dp))
            Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = color)
        }
    }
}

@Composable
fun LoadingIndicator(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Primary)
    }
}

@Composable
fun ErrorMessage(message: String, onRetry: (() -> Unit)? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            message,
            color = ErrorRed,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Medium
        )
        if (onRetry != null) {
            Spacer(Modifier.height(16.dp))
            BigButton(text = "다시 시도", onClick = onRetry, primary = false)
        }
    }
}

@Composable
fun MetricItem(
    label: String,
    value: String,
    unit: String = "",
    icon: String = "",
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null   // 사용자 피드백: 지표 클릭 → 상세 화면 이동
) {
    val baseModifier = modifier
        .fillMaxWidth()
        .let { if (onClick != null) it.clickable { onClick() } else it }
        .padding(vertical = 8.dp)
    Row(
        modifier = baseModifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon.isNotEmpty()) {
            Text(icon, fontSize = 24.sp)
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp, color = TextSecondary)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                if (unit.isNotEmpty()) {
                    Spacer(Modifier.width(4.dp))
                    Text(unit, fontSize = 14.sp, color = TextSecondary)
                }
            }
        }
        // 클릭 가능하면 > 표시
        if (onClick != null) {
            androidx.compose.material3.Icon(
                imageVector = androidx.compose.material.icons.Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun YesNoChoice(
    question: String,
    selected: Boolean?,
    onSelect: (Boolean) -> Unit,
    yesLabel: String = "예",
    noLabel: String = "아니오",
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(question, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
        Spacer(Modifier.height(12.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.height(IntrinsicSize.Min)   // 두 버튼 높이를 더 큰 쪽(2줄 라벨) 기준으로 통일
        ) {
            ChoicePill(
                text = yesLabel,
                isSelected = selected == true,
                onClick = { onSelect(true) },
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
            ChoicePill(
                text = noLabel,
                isSelected = selected == false,
                onClick = { onSelect(false) },
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
        }
    }
}

/**
 * 입력 검증 즉시 피드백 텍스트 (geto 스타일).
 *
 * @param state Empty(기본) / Valid(파랑 ✓) / Invalid(빨강 ⚠️)
 * @param message 표시할 메시지 (Empty면 안 보임)
 *
 * 예:
 * - "사용 가능한 이름입니다."  → Valid
 * - "2~16자까지 입력 가능합니다." → Empty (안내)
 * - "비밀번호가 일치하지 않습니다" → Invalid
 */
enum class ValidationState { Empty, Hint, Valid, Invalid }

@Composable
fun ValidationText(
    state: ValidationState,
    message: String,
    modifier: Modifier = Modifier
) {
    if (message.isBlank()) return
    val (color, icon) = when (state) {
        ValidationState.Empty -> TextDisabled to ""
        ValidationState.Hint -> TextSecondary to ""
        ValidationState.Valid -> Info to "✓ "
        ValidationState.Invalid -> ErrorRed to "⚠️ "
    }
    Text(
        text = "$icon$message",
        fontSize = 12.sp,
        color = color,
        fontWeight = if (state == ValidationState.Valid || state == ValidationState.Invalid) FontWeight.Medium else FontWeight.Normal,
        modifier = modifier.padding(top = 4.dp, start = 4.dp)
    )
}

/**
 * 라벨 + 입력 + 즉시 검증 메시지 (geto 12, 13번 화면 패턴).
 * 한 화면에 여러 필드가 깔끔하게 정렬됨.
 */
@Composable
fun LabeledField(
    label: String,
    required: Boolean = false,
    validationState: ValidationState = ValidationState.Empty,
    validationMessage: String = "",
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            if (required) {
                Text(" *", fontSize = 14.sp, color = ErrorRed, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(6.dp))
        content()
        if (validationMessage.isNotBlank()) {
            ValidationText(state = validationState, message = validationMessage)
        }
    }
}

/**
 * 단계 진행 표시 (geto 회원가입 흐름).
 * STEP 1 · STEP 2 · STEP 3 같은 단계별 진행도.
 */
@Composable
fun StepIndicator(
    current: Int,
    total: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for (i in 1..total) {
            val active = i <= current
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (active) Primary else BgCardSoft)
            )
        }
    }
}

@Composable
fun ChoicePill(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.heightIn(min = 56.dp),   // 고정 56dp → 최소 56dp. 큰 글씨로 2줄이 돼도 안 잘림.
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) Primary else BgCardSoft,
        border = if (!isSelected) androidx.compose.foundation.BorderStroke(1.dp, Divider) else null,
        onClick = onClick
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Text(
                text,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = if (isSelected) Color.White else TextPrimary,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp
            )
        }
    }
}

/**
 * [v1.1.4-rate polish] AI 재생성 카운터 안내 배지.
 *
 * 이전: 회색 12sp 캡션 → 눈에 안 띔.
 * 지금: 소프트 초록 배지 + 아이콘 + 남은 횟수 강조 (Primary·Bold),
 *       소진 시 앰버 배지 + Lock 아이콘 + WarningText 컬러로 자연스레 톤 다운.
 *
 * 카피:
 *  - 정상: "남은 수정 횟수: N회"
 *  - 소진: "AI 재생성 한도 소진"
 *
 * [260726] 기존 "수정은 최대 2회 가능합니다.(남은 횟수: N회)"는 총 한도를 2로 하드코딩해
 *   서버 정책(오늘 3회 / 과거 2회)과 어긋났고, GPT fallback으로 카운트가 오르지 않으면
 *   "최대 2회(남은 3회)" 같은 자기모순이 나왔다. → 총 한도는 빼고 남은 횟수만 표시한다.
 */
@Composable
fun AiRegenNotice(
    remaining: Int,
    locked: Boolean,
    modifier: Modifier = Modifier
) {
    val isLocked = locked || remaining <= 0
    val bg = if (isLocked) Warning.copy(alpha = 0.15f) else Primary.copy(alpha = 0.08f)
    val borderColor = if (isLocked) Warning.copy(alpha = 0.45f) else Primary.copy(alpha = 0.28f)
    val iconTint = if (isLocked) WarningText else Primary
    val icon = if (isLocked) Icons.Outlined.Lock else Icons.Outlined.Info

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(6.dp))
        if (isLocked) {
            Text(
                text = "AI 재생성 한도 소진",   // [260728 당봄 피드백] "이 날 " 제거
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = WarningText
            )
        } else {
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = TextSecondary)) {
                        append("남은 수정 횟수: ")
                    }
                    withStyle(SpanStyle(color = Primary, fontWeight = FontWeight.Bold)) {
                        append("${remaining}회")
                    }
                },
                fontSize = 13.sp
            )
        }
    }
}
