@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.dangbomcare.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dangbomcare.app.ui.components.AiRegenNotice
import com.dangbomcare.app.ui.components.BigButton
import com.dangbomcare.app.ui.components.ErrorMessage
import com.dangbomcare.app.ui.components.LoadingIndicator
import com.dangbomcare.app.ui.components.SectionCard
import com.dangbomcare.app.ui.components.YesNoChoice
import com.dangbomcare.app.ui.theme.*
import com.dangbomcare.app.ui.viewmodel.MorningInputViewModel

@Composable
fun MorningInputScreen(
    onBack: () -> Unit,
    onSuccess: () -> Unit,
    initialDateIso: String? = null,   // [우노 3] +버튼=null(오늘) / 캘린더=해당 날짜(yyyy-MM-dd)
    vm: MorningInputViewModel = viewModel()
) {
    val state by vm.state.collectAsState()
    val initialValues by vm.initialValues.collectAsState()
    val selectedDate by vm.selectedDate.collectAsState()
    val loggedDates by vm.loggedDates.collectAsState()
    // [v1.1.4-rate] AI 재생성 카운터 — 기존 로그 수정 시 남은 횟수/소진 여부 표시 및 버튼 비활성화 근거.
    val aiRegen by vm.aiRegen.collectAsState()
    // [260726] 폼 입력값 전부 rememberSaveable — 제출 실패 복귀·화면 회전·프로세스 사망에도 사용자가
    //   입력/수정한 값이 남아 있어야 아래 formTouched 가드가 의미를 갖는다. (기존 remember는 회전 시 전부 소실)
    var glucoseText by rememberSaveable { mutableStateOf("") }
    var alcohol by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var dinnerBefore7pm by rememberSaveable { mutableStateOf<Boolean?>(null) }
    // [260623] 회고 신규 4항목
    var noFoodHours by rememberSaveable { mutableStateOf<String?>(null) }
    var dinnerType by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    // [v1.1.3] 저녁 유형 "기타" 자유 입력 텍스트 — dinnerType에 "other" 포함 시 입력창 노출
    var dinnerTypeOther by rememberSaveable { mutableStateOf("") }
    var digestion by rememberSaveable { mutableStateOf<String?>(null) }
    var postMealExercise by rememberSaveable { mutableStateOf<String?>(null) }
    // [260726] 사용자가 폼을 한 번이라도 건드렸는지 — true면 서버/임시저장 값으로 덮어쓰지 않는다.
    //   (제출 실패 후 복귀 시 고친 값이 서버값으로 되돌아가 "변경 없음"으로 판정되던 입력 유실 방지)
    var formTouched by rememberSaveable { mutableStateOf(false) }
    // [260726] 폼 초기화 세대 키 "날짜|저녁모드" — 이 키가 실제로 바뀔 때만 폼을 비우고 formTouched를 해제한다.
    //   컴포지션 재진입(제출 실패)·회전으로 effect가 다시 실행돼도 키가 같으면 초기화하지 않는다.
    var formKey by rememberSaveable { mutableStateOf("") }
    var draftMsg by remember { mutableStateOf<String?>(null) }  // [260623] 임시저장 안내 다이얼로그 메시지
    var showSkipConfirm by remember { mutableStateOf(false) }  // [P2.7-b] 회고 미응답 시 제출 전 확인
    var showDatePicker by remember { mutableStateOf(false) }
    // [260722 r45] appCtx 삭제 — 저녁 2시간 전 게이트 제거로 SharedPreferences 접근 불필요.
    // [260726] rememberSaveable — 회전 시 false로 돌아가면 저녁 모드가 되살아나 폼(formKey)이 초기화된다.
    var forceEditToday by rememberSaveable { mutableStateOf(false) }   // 저녁 모드에서 '아침 기록 수정'으로 전환

    // [우노 3] 진입 시 대상 날짜 설정 (+버튼=오늘 / 캘린더=전달된 날짜)
    LaunchedEffect(Unit) {
        val d = initialDateIso?.takeIf { it.isNotBlank() }
            ?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
            ?: java.time.LocalDate.now()
        vm.setDate(d)
    }

    // [260726] "날짜 바뀌면 무조건 폼 초기화" 하던 LaunchedEffect(selectedDate) 제거.
    //   조건 없이 실행돼 회전/컴포지션 재진입 때도 입력을 지웠다. 초기화는 아래 자동 채움 effect에서
    //   formKey(날짜|모드)가 실제로 바뀐 경우에만 수행한다.
    // (자동 채움 로직은 eveningMode 계산 아래로 이동 — [260705 R3] 저녁 모드에선 빈 폼이어야 해서)

    LaunchedEffect(state) {
        if (state is MorningInputViewModel.UiState.Success) onSuccess()
    }

    // ⭐ 변경 사항 없음 다이얼로그
    if (state is MorningInputViewModel.UiState.NoChange) {
        AlertDialog(
            onDismissRequest = {
                vm.consumeNoChange()
                onBack()  // 화면 빠져나가기
            },
            icon = { Text("✅", fontSize = 36.sp) },
            title = {
                Text(
                    "이미 저장돼 있어요",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Text(
                    "수정한 내용이 없어요.\n오늘 입력하신 내용이 그대로 저장돼 있어요.",
                    fontSize = 14.sp,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.consumeNoChange()
                        onBack()
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) {
                    Text("확인", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            },
            shape = RoundedCornerShape(20.dp),
            containerColor = androidx.compose.ui.graphics.Color.White   // [260705] 연보라(M3 기본 tint) 제거 — 흰 배경 통일
        )
    }

    // ⭐ 제출 중일 때 풀스크린 분석 화면
    if (state is MorningInputViewModel.UiState.Submitting) {
        AnalyzingScreen(
            isDone = false,
            onDoneAnimationComplete = {}
        )
        return
    }

    // [우노 3] 날짜 표시/계산
    val today = java.time.LocalDate.now()
    val isToday = selectedDate == today
    val daysAgo = java.time.temporal.ChronoUnit.DAYS.between(selectedDate, today)
    // [260721] 저녁 모드에서 '내일 M/D' 안내에 사용 — 하드코딩 금지, 렌더 시점 동적 계산.
    val tomorrow = today.plusDays(1)
    val tomorrowLabel = "${tomorrow.monthValue}/${tomorrow.dayOfMonth}"
    val dowKo = when (selectedDate.dayOfWeek) {
        java.time.DayOfWeek.MONDAY -> "월"
        java.time.DayOfWeek.TUESDAY -> "화"
        java.time.DayOfWeek.WEDNESDAY -> "수"
        java.time.DayOfWeek.THURSDAY -> "목"
        java.time.DayOfWeek.FRIDAY -> "금"
        java.time.DayOfWeek.SATURDAY -> "토"
        else -> "일"
    }
    val dateLabel = "${selectedDate.year}년 ${selectedDate.monthValue}월 ${selectedDate.dayOfMonth}일 ($dowKo)"
    val titleText = if (isToday) "오늘 기록 입력" else "${selectedDate.monthValue}월 ${selectedDate.dayOfMonth}일 입력"
    // [260722 r31] 저녁 회고(내일 아침용) 모드에선 상단 표시 날짜를 내일로 (실제 selectedDate는 오늘 유지 — draft 로직).
    val tomorrowDowKo = when (tomorrow.dayOfWeek) {
        java.time.DayOfWeek.MONDAY -> "월"
        java.time.DayOfWeek.TUESDAY -> "화"
        java.time.DayOfWeek.WEDNESDAY -> "수"
        java.time.DayOfWeek.THURSDAY -> "목"
        java.time.DayOfWeek.FRIDAY -> "금"
        java.time.DayOfWeek.SATURDAY -> "토"
        else -> "일"
    }
    val tomorrowDateLabel = "${tomorrow.year}년 ${tomorrow.monthValue}월 ${tomorrow.dayOfMonth}일 ($tomorrowDowKo)"

    // [260722 r45] 저녁 모드 게이트: 저녁 2시간 전 조건 완전 제거.
    //   회고 팝업 UX가 잘 정리돼 있어 오늘 아침 회고 완료 즉시 다음날 저녁 회고 미리 입력 가능하게 함.
    //   기존: isToday && nowMin >= supper-120min && !forceEditToday && hasCompletedTodayLog
    //   현재: isToday && !forceEditToday && hasCompletedTodayLog
    //   → supperTimeStr, eveningGateMin, nowMin, 1분 주기 LaunchedEffect 모두 삭제 (dead code).
    //   hasCompletedTodayLog = 백엔드에 오늘 로그 있음 && draft 복원 상태 아님.
    val hasCompletedTodayLog = initialValues.isExisting && !initialValues.isDraft
    val eveningMode = isToday && !forceEditToday && hasCompletedTodayLog

    // [260722 r31] 저녁 회고 진입 팝업 — eveningMode 조건 만족 시 자동 전환하지 않고 사용자에게 선택 제시.
    //   eveningChoice: null=미결정(팝업 노출), true=예(저녁 회고 진입), false=아니오(오늘 아침 화면 유지)
    // [260722] rememberSaveable — 홈 탭 이동/구성 변경 후 재진입 시 사용자 선택 유지(Boolean? 기본 Saver 지원).
    var eveningChoice by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var showEveningPrompt by remember { mutableStateOf(false) }
    // eveningMode 조건이 새로 만족되고 아직 사용자가 선택 안 했으면 팝업 트리거
    LaunchedEffect(eveningMode) {
        if (eveningMode && eveningChoice == null) {
            showEveningPrompt = true
        }
    }
    // 실제 UI 분기 기준 — 팝업에서 '예' 선택했을 때만 저녁 회고 모드로 렌더
    val effectiveEveningMode = eveningMode && eveningChoice == true

    // 해당 날짜에 이미 입력한 값(백엔드/임시저장)이 있으면 자동 채움 — 단 저녁 모드는 '오늘 저녁 새 기록'이라 빈 폼
    // [260726] 폼 초기화 + 자동 채움 통합, 가드 2개 추가:
    //   (1) 초기화는 formKey("날짜|저녁모드")가 실제로 바뀐 경우에만 → 제출 실패로 컴포지션이 제거됐다
    //       Error로 복귀할 때(위 Submitting return) effect가 첫 실행처럼 재진입해도 폼이 지워지지 않는다.
    //   (2) 자동 채움은 formTouched == false 일 때만 → setDate→loadForDate 응답이 늦게 도착해도
    //       사용자가 입력·수정한 값을 서버값으로 되돌리지 않는다. (되돌아가면 ViewModel이 "변경 없음"으로
    //       판정해 수정 내용이 영구 유실됐음)
    //   (3) [260727 P2] 첫 로드 응답 전에는 초기화 자체를 보류 → 프로세스 사망 후 복귀 시 폼이 지워지지 않는다.
    LaunchedEffect(selectedDate, effectiveEveningMode, initialValues) {
        val key = "$selectedDate|$effectiveEveningMode"
        if (formKey != key) {
            // [260727 P2] 초기값 첫 응답(initialValues.loadSettled) 전에는 초기화 보류.
            //   프로세스 사망 후 복귀하면 ViewModel이 새로 만들어져 initialValues가 기본값이고,
            //   그동안 hasCompletedTodayLog=false → eveningMode=false 로 계산돼 저녁 회고 모드에서
            //   복원된 formKey("날짜|true")와 순간적으로 불일치한다. selectedDate 도 setDate 반영 전엔
            //   기본값(오늘)이라 과거 날짜 입력 중 사망한 경우 날짜 부분이 어긋난다.
            //   → 그 한 프레임 때문에 rememberSaveable 로 살려둔 입력이 전부 지워졌다.
            //   날짜·모드가 확정되는 첫 응답 이후에만 초기화를 판단한다. (게이트는 로드가 생략되는
            //   early-return 경로에서도 ViewModel 이 열어주므로 영구 보류로 남지 않는다.)
            if (!initialValues.loadSettled) return@LaunchedEffect
            // [코덱스 R3] 날짜 이동 후 오늘 복귀 시 저녁 모드가 다시 살아나게 — 날짜가 실제로 바뀐 경우만.
            if (formKey.substringBefore('|') != selectedDate.toString()) forceEditToday = false
            formKey = key
            formTouched = false
            glucoseText = ""
            alcohol = null; dinnerBefore7pm = null; noFoodHours = null
            dinnerType = emptyList(); dinnerTypeOther = ""; digestion = null; postMealExercise = null
        }
        // 저녁 모드는 '오늘 저녁 새 기록'이라 계속 빈 폼 / 사용자가 손댄 폼은 덮어쓰지 않음
        if (effectiveEveningMode || formTouched) return@LaunchedEffect
        if (initialValues.isExisting || initialValues.isDraft) {
            glucoseText = initialValues.fastingGlucose?.toString() ?: ""
            alcohol = initialValues.alcohol
            dinnerBefore7pm = initialValues.dinnerBefore7pm
            noFoodHours = initialValues.noFoodAfterDinnerHours
            dinnerType = initialValues.dinnerType ?: emptyList()
            dinnerTypeOther = initialValues.dinnerTypeOther ?: ""
            digestion = initialValues.digestion
            postMealExercise = initialValues.postMealExercise
        }
    }

    // [우노 3] 날짜 선택 — 깔끔한 커스텀 캘린더 + 입력한 날 점 표시 (미래 차단)
    if (showDatePicker) {
        com.dangbomcare.app.ui.components.AppCalendarDialog(
            initialDate = selectedDate,
            onDismiss = { showDatePicker = false },
            onSelect = { d -> vm.setDate(d) },
            allowFuture = false,
            showMarkers = true,
            markedDates = loggedDates,
            onMonthChange = { ym -> vm.loadMonth(ym) }
        )
    }

    Scaffold(
        containerColor = BgSecondary,
        topBar = {
            TopAppBar(
                title = { Text(titleText, fontWeight = FontWeight.Bold) },
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

            // [우노 3] 입력 날짜 선택 (오늘 기본 / 과거 선택 가능)
            SectionCard(title = "입력 날짜") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        // [260705 R3] 날짜가 두 줄로 꺾이지 않게 — 안 들어가면 자동으로 글자 축소(한 줄 유지)
                        // [260722 r31] 저녁 회고 모드일 때 상단 표시 날짜는 내일(23일 등)로 명시.
                        com.dangbomcare.app.ui.components.AutoSizeSingleLineText(
                            if (effectiveEveningMode) tomorrowDateLabel else dateLabel,
                            maxFontSize = 18.sp, minFontSize = 13.sp, fontWeight = FontWeight.Bold
                        )
                        if (!isToday) {
                            Spacer(Modifier.height(2.dp))
                            Text("지난 날짜를 입력하고 있어요", fontSize = 12.sp, color = TextSecondary)
                        }
                    }
                    // 저녁 회고 모드에선 날짜 편집 불가 (오늘 저녁 → 내일 아침 자동 반영이므로)
                    if (!effectiveEveningMode) {
                        OutlinedButton(onClick = { showDatePicker = true }) {
                            Text("날짜 변경")
                        }
                    }
                }
                if (daysAgo > 7) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "※ 일주일이 지난 날짜는 저장만 되고, AI 해석은 제공되지 않아요.",
                        fontSize = 12.sp,
                        color = Warning,
                        lineHeight = 16.sp
                    )
                }
            }

            // [260705 R3 / 260714 개선 / 260722 r31] 저녁 회고 모드: 팝업에서 '예' 선택했을 때만 진입.
            if (effectiveEveningMode) {
                // [260721] '오늘 저녁 기록' → '내일 아침용 저녁 회고' 로 명확화. 사용자가 '이게 오늘 회고인지
                //   내일 아침 회고인지' 헷갈리는 문제 해소. 저장 대상은 '내일(M/D) 아침 입력'임을 문구·버튼에 반영.
                SectionCard(title = "🌙 내일 아침용 저녁 회고") {
                    Text(
                        "내일 아침 회고를 미리 입력해보세요.\n아침엔 공복혈당만 입력하세요.",
                        fontSize = 14.sp, color = TextSecondary, lineHeight = 20.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    // [260722 r32/r33] 수정 진입 박스 — 축소 + 가운데 맞춤 + 위아래 여백 압축.
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { forceEditToday = true },
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = Primary.copy(alpha = 0.08f))
                    ) {
                        Text(
                            if (initialValues.isExisting) "오늘 아침에 입력한 기록을 수정하려면\n여기를 누르세요"
                            else "오늘 저녁 대신 아침 회고를 지금 입력하려면\n여기를 누르세요",
                            fontSize = 12.sp, color = Primary, fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center, lineHeight = 15.sp,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            } else
            // 1. 공복혈당
            SectionCard(title = if (isToday) "오늘 아침 공복혈당" else "${selectedDate.monthValue}월 ${selectedDate.dayOfMonth}일 공복혈당") {
                Text(
                    "측정한 수치를 입력해주세요",
                    fontSize = 14.sp,
                    color = TextSecondary
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = glucoseText,
                    onValueChange = { new ->
                        if (new.length <= 3 && new.all { it.isDigit() }) {
                            glucoseText = new
                            formTouched = true   // [260726] 사용자 입력 표시 — 서버값 자동 채움이 덮어쓰지 않도록
                            vm.resetError()
                        }
                    },
                    placeholder = {
                        Text(
                            "예: 102",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            fontSize = 24.sp
                        )
                    },
                    suffix = { Text("mg/dL", fontSize = 16.sp, color = TextSecondary) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    ),
                    // 고정 88dp → 최소 88dp. 36sp 입력이 시니어 1.4× 글씨에서도 세로로 안 잘림.
                    modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp),
                    shape = RoundedCornerShape(16.dp)
                )
                // 즉시 검증 메시지 + 의학 안내
                val glucoseInt = glucoseText.toIntOrNull()
                val (vState, vMsg) = when {
                    glucoseText.isBlank() -> com.dangbomcare.app.ui.components.ValidationState.Hint to "10~600 mg/dL 범위로 입력해주세요."
                    glucoseInt == null -> com.dangbomcare.app.ui.components.ValidationState.Invalid to "숫자만 입력해주세요."
                    glucoseInt !in 10..600 -> com.dangbomcare.app.ui.components.ValidationState.Invalid to "10~600 mg/dL 범위로 입력해주세요."
                    else -> com.dangbomcare.app.ui.components.ValidationState.Valid to "정상 입력 되었습니다."
                }
                com.dangbomcare.app.ui.components.ValidationText(state = vState, message = vMsg)

                glucoseInt?.takeIf { it in 10..600 }?.let { v ->
                    // [260623 / 260714 정리 G1] 당뇨인 3구간 문구 — 원문 그대로:
                    //   정상 (70~100), 당뇨인 목표 범위 (100~130), 높음 (>130).
                    //   ('공복혈당 장애' 표현은 완전 제거 — 당뇨약 복용 중인 환자에겐 부적합)
                    // [260726 의료안전] 백엔드 score_service.py 와 지시가 엇갈리던 문제 수정.
                    //   · glucose_safety_status: <55=critical_low, 55~69=low → 기존엔 <70 한 구간뿐이라
                    //     critical_low 안내(즉시 단당류 섭취·재측정, 의식 저하 시 119)가 입력 화면에 없었음 → 신설.
                    //   · should_suppress_mission: 200 이상이면 운동 미션 금지 + "무리한 활동을 피하고 안정" 안내.
                    //     기존 else 구간(>180)이 350 에도 "식후 산책 꼭!" 을 띄워 백엔드와 정반대 지시였음
                    //     → 200 이상을 별도 구간으로 분리하고 운동 권유 제거, 안정·상담 권고로 교체.
                    //   · 55~199 는 백엔드도 운동 미션을 허용하는 범위이므로 기존 문구 그대로 유지.
                    val (statusText, statusColor, advice) = when {
                        v < 55 -> Triple(
                            "심각한 저혈당 (55 미만)", ErrorRed,
                            "즉시 주스·사탕 등 단맛 나는 음식을 드시고 15분 뒤 다시 측정해주세요.\n의식이 흐려지면 가족이나 119에 도움을 요청하세요."
                        )
                        v < 70 -> Triple("저혈당 — 식사 권장", GlucoseLow, "빠른 식사가 필요해요")
                        v < 100 -> Triple("정상 (70~100)", Success, "이대로 유지해주세요!")
                        v <= 130 -> Triple("당뇨인 목표 범위 (100~130)", Success, "당뇨인 목표 범위 안이에요. 잘 유지하고 계세요!")
                        v <= 180 -> Triple("높음 (>130)", Warning, "운동 + 식단 관리가 필요해요")
                        v < 200 -> Triple("매우 높음 (>180)", ErrorRed, "오늘은 식후 산책 꼭!")
                        else -> Triple(
                            "매우 높음 (200 이상)", ErrorRed,
                            "무리한 활동은 피하시고 잠시 안정을 취해주세요.\n수치가 계속 높으면 당봄한의원 또는 주치의에게 상담받아보세요."
                        )
                    }
                    // [260707 접근성] 배경 틴트는 원색, 글자는 대비 확보된 진한 변형 사용
                    val statusTextColor = when (statusColor) {
                        Warning -> WarningText
                        GlucoseLow -> GlucoseLowText
                        else -> statusColor
                    }
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        color = statusColor.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                "현재 수치: $statusText",
                                fontSize = 15.sp,
                                color = statusTextColor,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(2.dp))
                            // [260726] 저혈당/고혈당 안내가 두 줄이 되면서 줄 간격 지정 (시니어 가독성)
                            Text(advice, fontSize = 12.sp, color = TextSecondary, lineHeight = 17.sp)
                        }
                    }
                }
            }

            // 2. 회고 — [저녁 마감시간 동적화] 설정한 저녁식사 시각을 질문에 반영 (하드코딩 '7시' 제거)
            val ctxDinner = androidx.compose.ui.platform.LocalContext.current
            val dinnerLabel = remember { com.dangbomcare.app.notification.AlarmScheduler.dinnerDeadlineLabel(ctxDinner) }
            // [260705 R3] 회고 시점 워딩 — 기준 명확화:
            //   아침(오늘 기록 제출 전) = '전날'(어젯밤 회고) / 저녁 모드 = '오늘'(오늘 저녁 회고, 내일 기록용)
            //   어제 날짜 입력 = '전날' / 그보다 과거 = 시점 단어 제거
            val isYesterdayInput = selectedDate == today.minusDays(1)
            val retroPrefix = when {
                eveningMode -> "오늘 "
                isToday || isYesterdayInput -> "전날 "
                else -> ""
            }
            val retroTitle = when {
                eveningMode -> "오늘 하루는 어떠셨어요?"
                isToday || isYesterdayInput -> "전날 하루는 어떠셨어요?"
                else -> "그 날 하루는 어떠셨어요?"
            }
            val retroSub = when {
                eveningMode -> "오늘 저녁 ~ 밤 시간을 떠올려주세요"
                isToday || isYesterdayInput -> "전날 저녁 ~ 밤 시간을 떠올려주세요"
                else -> "그 날 저녁 ~ 밤 시간을 떠올려주세요"
            }
            SectionCard(title = retroTitle) {
                Text(
                    retroSub,
                    fontSize = 14.sp,
                    color = TextSecondary
                )
                Spacer(Modifier.height(20.dp))

                // ⭐ 순서: 저녁 마감 전 식사 (1) → 식후 금식 (2) → 술 (3)
                YesNoChoice(
                    question = "1️⃣ ${retroPrefix}저녁식사를 $dinnerLabel 전에 끝내셨나요?",
                    selected = dinnerBefore7pm,
                    // [260726] 각 입력 변경 시 formTouched=true — 자동 채움이 사용자 선택을 되돌리지 않도록
                    onSelect = { dinnerBefore7pm = it; formTouched = true },
                    yesLabel = "예",
                    noLabel = "아니오"
                )
                Spacer(Modifier.height(20.dp))

                // 2️⃣ [260623] 식후 음식 섭취 여부 — 기존 yes/no → 식후 금식 시간 5단계
                ChoiceGroup(
                    question = "2️⃣ ${retroPrefix}저녁식사 후, 자기 전까지 몇시간 금식 유지했나요?",
                    options = listOf(
                        "1h_under" to "1시간 미만",
                        "1to2h" to "1~2시간",
                        "2to3h" to "2~3시간",
                        "3to4h" to "3~4시간",
                        "4h_over" to "4시간 이상",
                    ),
                    selectedCodes = setOfNotNull(noFoodHours),
                    multi = false,
                    onToggle = { noFoodHours = it; formTouched = true }
                )
                Spacer(Modifier.height(20.dp))

                // 3️⃣ 저녁식사 유형 (중복 선택)
                // [피드백 260722] "단백질·지방(육류) 위주" → "고기 위주"로 재환원 (사용자 요청).
                // [v1.1.3] "밀가루(빵·면)" 삭제 + "기타"(자유 입력) 신설.
                ChoiceGroup(
                    question = "3️⃣ ${retroPrefix}저녁식사는 어떤 식사였나요?",
                    options = listOf(
                        "home_korean" to "집밥/한식",
                        "eating_out" to "외식/배달",
                        "meat" to "고기 위주",
                        "light" to "간단식 (샐러드·누룽지 등)",
                        "other" to "기타",
                    ),
                    selectedCodes = dinnerType.toSet(),
                    multi = true,
                    onToggle = { code ->
                        dinnerType = if (code in dinnerType) dinnerType - code else dinnerType + code
                        formTouched = true
                        // [v1.1.3] "기타" 해제 시 자유 입력값도 함께 비움 (남아 있으면 다음 선택 시 오염)
                        if (code == "other" && "other" !in dinnerType) dinnerTypeOther = ""
                    }
                )
                // [v1.1.3] "기타" 선택 시 자유 입력창 노출
                if ("other" in dinnerType) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = dinnerTypeOther,
                        onValueChange = { if (it.length <= 30) { dinnerTypeOther = it; formTouched = true } },
                        placeholder = { Text("예: 파스타, 국수 등", fontSize = 14.sp, color = TextSecondary) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
                Spacer(Modifier.height(20.dp))

                // 4️⃣ 자기 전 소화
                ChoiceGroup(
                    question = "4️⃣ 잠들기 전, 속은 어떠셨나요?",
                    options = listOf(
                        "hungry" to "배고픔",
                        "partial" to "소화가 조금 됨",
                        "full" to "여전히 배부름",
                    ),
                    selectedCodes = setOfNotNull(digestion),
                    multi = false,
                    onToggle = { digestion = it; formTouched = true }
                )
                Spacer(Modifier.height(20.dp))

                // 5️⃣ 식후 운동
                ChoiceGroup(
                    question = "5️⃣ ${retroPrefix}저녁식사 후 운동(걷기 등) 하셨나요?",
                    options = listOf(
                        "none" to "안 함",
                        "10to30" to "10~30분",
                        "30to60" to "30~60분",
                        "60plus" to "1시간 이상",
                    ),
                    selectedCodes = setOfNotNull(postMealExercise),
                    multi = false,
                    onToggle = { postMealExercise = it; formTouched = true }
                )
                Spacer(Modifier.height(20.dp))

                // 6️⃣ 음주 (alcohol=true가 '예')
                YesNoChoice(
                    question = if (retroPrefix.isEmpty()) "6️⃣ 술을 마셨나요?" else "6️⃣ ${retroPrefix}술을 마셨나요?",
                    selected = alcohol,
                    onSelect = { alcohol = it; formTouched = true },
                    yesLabel = "예",
                    noLabel = "아니오"
                )
            }

            when (val s = state) {
                is MorningInputViewModel.UiState.Error -> ErrorMessage(message = s.message)
                else -> Unit
            }

            // 코덱스 P2: 의료 앱 UX — 범위(10~600) 밖이면 버튼 단계에서 차단 (서버 422 회피)
            val glucoseValid = glucoseText.toIntOrNull()?.let { it in 10..600 } == true
            // [v1.1.4-rate] 기존 로그 수정이고 재생성 한도 소진이면 저장 버튼 자체를 비활성화 (컨트랙트: locked=true).
            val regenBlocked = initialValues.isExisting && aiRegen.locked
            // [260705 R3 / 260722 r31] 저녁 회고 모드가 아닐 때만 '입력 완료' 노출.
            if (!effectiveEveningMode) {
                BigButton(
                    text = "입력 완료",
                    onClick = {
                        // [P2.7-b/260623] 회고 항목 중 하나라도 미응답이면 제출 전 확인 (선택사항이지만 실수 방지)
                        if (alcohol == null || dinnerBefore7pm == null || noFoodHours == null ||
                            dinnerType.isEmpty() || digestion == null || postMealExercise == null) {
                            showSkipConfirm = true
                        } else {
                            val g = glucoseText.toIntOrNull() ?: 0
                            vm.submit(g, alcohol, dinnerBefore7pm, noFoodHours, dinnerType.ifEmpty { null }, dinnerTypeOther.ifBlank { null }, digestion, postMealExercise)
                        }
                    },
                    enabled = state !is MorningInputViewModel.UiState.Submitting && glucoseValid && !regenBlocked
                )
                // [v1.1.4-rate polish] 재생성 카운터 안내 — 기존 로그 수정 상태에서만 노출.
                //   회색 캡션이 눈에 잘 안 띈다는 피드백 → 소프트 초록 배지로 재디자인(공용 AiRegenNotice).
                if (initialValues.isExisting) {
                    Spacer(Modifier.height(8.dp))
                    AiRegenNotice(
                        remaining = aiRegen.remaining,
                        locked = aiRegen.locked,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // [260722 r31] 오늘 아침 화면일 때, 저녁 시간대이며 이미 완료한 상태면 '내일 아침용 저녁 회고 입력하기' 진입 버튼 제공.
            //   조건: eveningMode 조건은 만족(=오늘 완료 & 저녁 시간대)이지만 아직 저녁 회고 화면으로 안 넘어감(effectiveEveningMode=false).
            if (!effectiveEveningMode && eveningMode) {
                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = { eveningChoice = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, Primary)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🌙 내일 아침용 저녁 회고 입력하기", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Primary)
                        Text("(오늘 저녁 회고를 미리 입력)", fontSize = 11.sp, color = TextSecondary)
                    }
                }
            }

            // [260705 R3 / 260722 r31] 저녁 회고 임시저장 — 팝업에서 '예' 선택한 저녁 회고 모드에서만.
            if (effectiveEveningMode) {
                Button(
                    onClick = {
                        // [코덱스 P2] 저장할 내용이 하나도 없으면 저장하지 않고 안내만
                        val hasAny = alcohol != null || dinnerBefore7pm != null || noFoodHours != null ||
                            dinnerType.isNotEmpty() || dinnerTypeOther.isNotBlank() || digestion != null || postMealExercise != null
                        if (hasAny) {
                            vm.saveDraft(null, alcohol, dinnerBefore7pm, noFoodHours, dinnerType.ifEmpty { null }, dinnerTypeOther.ifBlank { null }, digestion, postMealExercise)
                            // [260721] 안내 문구도 '내일(M/D) 아침'으로 명확화. 날짜는 tomorrowLabel(동적) 사용.
                            draftMsg = "내일 아침용으로 저장했어요.\n내일($tomorrowLabel) 아침에 공복혈당만 입력하면 완료돼요.\n(내일 안 들어오면 자동으로 초기화돼요.)"
                        } else {
                            draftMsg = "아직 저장할 내용이 없어요.\n저녁식사·금식 시간 등을 체크한 뒤 다시 눌러주세요."
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary),
                    enabled = state !is MorningInputViewModel.UiState.Submitting
                ) {
                    Text("내일 아침 회고 저장하기", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
            // [260722 r31] 저녁 회고 진입 팝업 — 오늘 아침 회고 완료 후 저녁 시간대에 재진입 시 노출.
            //   '네' → 내일 아침용 저녁 회고 화면 (내일 날짜 상단 표시)
            //   '아니오' → 오늘 아침 회고 화면 유지 (수정 가능)
            if (showEveningPrompt) {
                AlertDialog(
                    // [260722] BACK 키 / 배경 탭 = "아니오" 취급 — 팝업이 사라져도 오늘 아침 화면 유지되도록.
                    onDismissRequest = { eveningChoice = false; showEveningPrompt = false },
                    icon = { Text("🌙", fontSize = 32.sp) },
                    title = {
                        Text(
                            "내일 아침 회고를 미리 입력해보세요.",
                            fontSize = 17.sp, fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center, color = TextPrimary
                        )
                    },
                    text = {
                        Text(
                            "오늘 저녁을 미리 입력하고\n내일 아침엔 공복혈당만 입력하세요.",
                            fontSize = 14.sp, color = TextSecondary,
                            textAlign = TextAlign.Center, lineHeight = 21.sp,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = { eveningChoice = true; showEveningPrompt = false },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Primary)
                        ) { Text("네, 미리 입력할게요", fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { eveningChoice = false; showEveningPrompt = false },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("아니오, 오늘 회고만 볼게요", color = TextSecondary, fontSize = 14.sp) }
                    },
                    shape = RoundedCornerShape(20.dp),
                    containerColor = androidx.compose.ui.graphics.Color.White
                )
            }

            draftMsg?.let { msg ->
                // [260705 피드백] 연보라 기본 다이얼로그 → 흰 배경 + 큰 라운드 + 초록 버튼 (깔끔한 디자인 통일)
                AlertDialog(
                    onDismissRequest = { draftMsg = null },
                    icon = { Text("💾", fontSize = 32.sp) },
                    title = { Text("임시저장", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TextPrimary) },
                    text = { Text(msg, fontSize = 14.sp, color = TextSecondary, lineHeight = 21.sp) },
                    confirmButton = {
                        Button(
                            onClick = { draftMsg = null },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Primary)
                        ) { Text("확인", fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                    },
                    shape = RoundedCornerShape(20.dp),
                    containerColor = androidx.compose.ui.graphics.Color.White
                )
            }
            if (showSkipConfirm) {
                AlertDialog(
                    onDismissRequest = { showSkipConfirm = false },
                    icon = { Text("📝", fontSize = 32.sp) },
                    title = { Text("회고를 다 입력하지 않으셨어요", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
                    text = {
                        Text(
                            "전날 회고(저녁식사·식후 금식·음주)를 입력하면 더 정확한 점수와 코칭을 받으실 수 있어요. 그래도 제출할까요?",
                            fontSize = 13.sp, color = TextSecondary, lineHeight = 19.sp
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showSkipConfirm = false
                            val g = glucoseText.toIntOrNull() ?: 0
                            vm.submit(g, alcohol, dinnerBefore7pm, noFoodHours, dinnerType.ifEmpty { null }, dinnerTypeOther.ifBlank { null }, digestion, postMealExercise)
                        }) { Text("그래도 제출", color = Primary, fontWeight = FontWeight.Bold) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showSkipConfirm = false }) { Text("돌아가기", color = TextSecondary) }
                    },
                    shape = RoundedCornerShape(20.dp),
                    containerColor = androidx.compose.ui.graphics.Color.White   // [260705] 흰 배경 통일
                )
            }
            if (glucoseText.isNotBlank() && !glucoseValid) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "공복혈당은 10~600 mg/dL 사이로 입력해주세요",
                    fontSize = 12.sp,
                    color = ErrorRed,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "입력하시면 워치 데이터와 함께 분석되어\n오늘의 점수 + AI 해석이 나옵니다.",
                fontSize = 12.sp,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}

/**
 * [260623 피드백] 전날 회고 단일/중복 선택 위젯 — 시니어 친화(모든 옵션 노출, 큰 탭 영역).
 *   multi=false: 단일 선택(드롭다운 대체) / multi=true: 중복 선택(칩).
 */
@Composable
private fun ChoiceGroup(
    question: String,
    options: List<Pair<String, String>>,
    selectedCodes: Set<String>,
    multi: Boolean,
    onToggle: (String) -> Unit
) {
    Column {
        // [260705 R3] 질문 글자 크기 통일 — 1·6번(YesNoChoice 18sp)과 동일하게
        Text(question, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary, lineHeight = 25.sp)
        if (multi) {
            Spacer(Modifier.height(2.dp))
            Text("여러 개 선택할 수 있어요", fontSize = 12.sp, color = TextSecondary)
        }
        Spacer(Modifier.height(10.dp))
        options.forEach { (code, label) ->
            val sel = code in selectedCodes
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .clickable { onToggle(code) },
                shape = RoundedCornerShape(12.dp),
                color = if (sel) Primary.copy(alpha = 0.12f) else BgCardSoft,
                border = BorderStroke(if (sel) 1.5.dp else 1.dp, if (sel) Primary else Divider)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        label,
                        fontSize = 15.sp,
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                        color = if (sel) PrimaryDark else TextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    if (sel) Text("✓", fontSize = 16.sp, color = Primary, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
