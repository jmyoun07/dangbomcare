package com.dangbomcare.app.ui.theme

import androidx.compose.ui.graphics.Color

// 당봄케어 브랜드 컬러 (당봄한의원 / arirangdm.com 스타일)
val Primary = Color(0xFF2D5F3F)        // Deep Forest
val PrimaryDark = Color(0xFF1F4A2E)
val PrimaryLight = Color(0xFF4A8060)
val LimeAccent = Color(0xFFA8D55C)     // 강조 라임
val LimeLight = Color(0xFFD4EBA8)
val Slate = Color(0xFF4A6670)
val ErrorRed = Color(0xFFE63946)
val Warning = Color(0xFFE9C46A)
val Info = Color(0xFF6B9BD8)
val Success = Color(0xFF38A169)

// [260707 접근성] 밝은 배경 '텍스트 전용' 진한 변형 — 노랑/하늘색 원색은 시니어 대비 부족(<3:1).
//   배경 틴트/아이콘엔 원색(Warning/GlucoseLow)을 유지하고, 글자에만 이 색을 사용.
val WarningText = Color(0xFF8A5A00)     // 진한 앰버 (흰 배경 ~5:1)
val GlucoseLowText = Color(0xFF2C5F8A)  // 진한 파랑 (흰 배경 ~5:1)

// 텍스트
val TextPrimary = Color(0xFF1A2530)
val TextSecondary = Color(0xFF5C6B7A)
val TextDisabled = Color(0xFFAAB5C0)

// 배경
val BgPrimary = Color(0xFFFFFFFF)
val BgSecondary = Color(0xFFF7F8F9)
val BgCard = Color(0xFFFFFFFF)
val BgCardSoft = Color(0xFFF1F4F2)     // 살짝 녹색감
val Divider = Color(0xFFE5E8EC)

// 점수별 색상
val ScoreGood = Color(0xFF2D5F3F)       // 70+ 좋음
val ScoreOk = Color(0xFFE9C46A)         // 50-69 보통
val ScoreLow = Color(0xFFE63946)        // <50 개선 필요

// [피드백 260714] 기록 캘린더 셀 배경 — 파스텔 톤 (Slate 회색 X, 하늘색 진함 X, 회색 톤 안 남기).
//   좋음: 하늘색 파스텔 / 보통: 연한 회색 / 나쁨: 연한 빨강
val CalendarGood = Color(0xFFC7E8FA)    // 70+ 하늘색 파스텔
val CalendarMid = Color(0xFFEAEAEA)     // 50-69 연한 회색
val CalendarBad = Color(0xFFFADADA)     // <50 연한 빨강

// 혈당 상태
val GlucoseNormal = Color(0xFF2D5F3F)
val GlucoseHigh = Color(0xFFE63946)
val GlucoseLow = Color(0xFF6B9BD8)
