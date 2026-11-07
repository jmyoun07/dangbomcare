package com.dangbomcare.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * 당봄케어 디자인 시스템 — 다크모드 / 다이내믹 컬러 비활성화 (시니어 친화 단일 라이트 테마).
 */
private val DangbomCareColorScheme = lightColorScheme(
    primary = Primary,
    onPrimary = BgPrimary,
    primaryContainer = LimeLight,
    onPrimaryContainer = PrimaryDark,
    secondary = LimeAccent,
    onSecondary = PrimaryDark,
    secondaryContainer = LimeLight,
    onSecondaryContainer = PrimaryDark,
    tertiary = Slate,
    onTertiary = BgPrimary,
    background = BgSecondary,
    onBackground = TextPrimary,
    surface = BgCard,
    onSurface = TextPrimary,
    surfaceVariant = BgCardSoft,
    onSurfaceVariant = TextSecondary,
    outline = Divider,
    error = ErrorRed,
    onError = BgPrimary,
)

// [시니어 + 기종 호환] 앱 전역 글씨 배율 한계.
//   MIN: 시스템 글씨가 작아도(또는 기본값이어도) 최소 이만큼 확대 → 시니어 가독성 (인라인 sp 포함 전체 적용).
//   MAX: 시스템 글씨를 아주 크게 설정해도 이 이상 안 커짐 → 고정 박스/그래프 깨짐 방지 (기종별 화면 일관성).
//   ※ dp는 영향 없음 → 박스 크기는 그대로이므로, 커진 글씨에 맞춰 레이아웃 유동화는 별도로 진행.
private const val SENIOR_FONT_SCALE_MIN = 1.15f
private const val SENIOR_FONT_SCALE_MAX = 1.40f

@Composable
fun DangbomCareTheme(content: @Composable () -> Unit) {
    val base = LocalDensity.current
    val managed = Density(
        density = base.density,
        fontScale = base.fontScale.coerceIn(SENIOR_FONT_SCALE_MIN, SENIOR_FONT_SCALE_MAX)
    )
    CompositionLocalProvider(LocalDensity provides managed) {
        MaterialTheme(
            colorScheme = DangbomCareColorScheme,
            typography = Typography,
            content = content
        )
    }
}

// 기존 호환용 별칭
@Composable
fun HealthConnectTestTheme(
    darkTheme: Boolean = false,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) = DangbomCareTheme(content = content)
