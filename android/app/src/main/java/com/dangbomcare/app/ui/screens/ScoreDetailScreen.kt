@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.dangbomcare.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dangbomcare.app.data.api.ScoreBreakdown
import com.dangbomcare.app.data.api.ScoreInner
import com.dangbomcare.app.data.repo.ApiResult
import com.dangbomcare.app.ui.components.*
import com.dangbomcare.app.ui.theme.*
import com.dangbomcare.app.ui.viewmodel.HomeViewModel
import java.time.LocalDate

@Composable
fun ScoreDetailScreen(
    onBack: () -> Unit,
    vm: HomeViewModel = viewModel(),
    initialDate: String? = null
) {
    val state by vm.state.collectAsState()

    val today = remember { LocalDate.now() }
    // [사용자 피드백] 캘린더에서 클릭한 날짜로 진입할 수 있게 initialDate 사용
    var selectedDate by remember {
        mutableStateOf(
            initialDate?.takeIf { it.isNotEmpty() }
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?: today
        )
    }

    // 다른 날짜 fetch 캐시 (api 재호출 절감)
    val dailyCache = remember { mutableStateMapOf<LocalDate, ScoreInner?>() }
    // [260726 의료안전] 그 날 실측 공복혈당 캐시.
    //   glucose 점수 0점은 백엔드에서 <70(저혈당)과 >180(초고혈당) 양쪽에 모두 부여되므로
    //   점수만으로는 정반대 상황을 구분할 수 없다 → daily-summary 의 실측 수치를 같이 들고 설명 문구를 가른다.
    val glucoseByDate = remember { mutableStateMapOf<LocalDate, Int?>() }
    // [260727 고객피드백 5] 그 날 실측 수면 시간(분) 캐시.
    //   ⚠️ [260729 통합검증] 아래 문장은 260728 개편 **이전** 계약을 말하던 주석이라 정정한다.
    //     수면 점수에 **0점 구간은 존재하지 않는다**(6~20점, score_service.sleep_score L331-341
    //     "0점 구간은 의도적으로 없다"). 0점처럼 보이는 것은 미측정(None)뿐이다.
    //     이 캐시가 필요한 진짜 이유는 그대로다 — **같은 점수가 부족/과다 양쪽에 붙기 때문**이다
    //     (예: 14점 = 5~6시간 과 11~12시간, 16점 = 4~5시간 아님·10~11시간 …).
    //     점수만으로는 정반대 상황을 구분할 수 없어 "10시간 48분 잤는데 수면이 짧다"가 뜨던 버그 →
    //     daily-summary 의 activity.sleep_hours(= 점수 산출에 쓰인 바로 그 워치 값)로 문구를 가른다.
    //     (배점표 정본은 이 파일 L721·L762 와 백엔드 sleep_score. 0점 분기를 되살리지 마라.)
    val sleepMinByDate = remember { mutableStateMapOf<LocalDate, Int?>() }
    var fetching by remember { mutableStateOf(false) }
    var fetchError by remember { mutableStateOf(false) }  // [코덱스 P1] 전송 오류는 캐시 X, 재시도 가능
    // [PDF 2 — 개인 공복혈당 범위] 채점 기준선을 점수 설명에 노출하려 /me 로드 (미설정이면 절대 기준 설명)
    var glucoseRangeLow by remember { mutableStateOf<Int?>(null) }
    var glucoseRangeHigh by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) {
        val res = com.dangbomcare.app.data.ServiceLocator.userRepository.getMe()
        if (res is com.dangbomcare.app.data.repo.ApiResult.Success) {
            glucoseRangeLow = res.data.glucoseNormalLow
            glucoseRangeHigh = res.data.glucoseNormalHigh
        }
    }
    // [버그수정] 이전엔 공유 HomeViewModel의 state.score 변경 때마다 dailyCache.clear()+cacheVersion++ 했는데,
    //   fetch 도중 캐시가 비워져 selectedScore=null → "입력된 기록 없어요"가 과거 모든 날짜에서 뜨던 레이스였음.
    //   점수 상세는 진입마다 새 인스턴스(빈 캐시)라 무효화가 불필요 → selectedDate 바뀔 때만 조회한다.

    // ScoreInner: 선택 날짜의 점수
    val selectedScore: ScoreInner? = if (selectedDate == today) {
        // 오늘은 HomeViewModel state 사용 (ScoreTodayResponse → ScoreInner 변환)
        state.score?.let { s ->
            ScoreInner(
                totalScore = s.totalScore,
                label = s.label,
                breakdown = s.breakdown
            )
        }
    } else {
        dailyCache[selectedDate]
    }

    // 비교용 직전일 점수
    val previousDate = selectedDate.minusDays(1)
    val previousScore: ScoreInner? = if (previousDate == today) {
        state.score?.let { s ->
            ScoreInner(totalScore = s.totalScore, label = s.label, breakdown = s.breakdown)
        }
    } else {
        dailyCache[previousDate]
    }

    // [버그수정] 점수 상세는 daily-log(회고 row 필요 → 없으면 404)가 아니라
    //   daily_summary(점수 = LifestyleScore 기준)에서 가져온다 → 기록 화면/캘린더와 동일 소스라 점수가 항상 일치.
    LaunchedEffect(selectedDate) {
        fetchError = false
        // 선택 날짜 — 성공 시에만 캐시(점수 or null). 전송 오류는 캐시 X → 다음에 재시도 가능.
        if (selectedDate != today && !dailyCache.containsKey(selectedDate)) {
            fetching = true
            try {
                when (val res =
                    com.dangbomcare.app.data.ServiceLocator.scoreRepository.dailySummary(selectedDate.toString())) {
                    is ApiResult.Success -> {
                        dailyCache[selectedDate] = res.data.score?.let {
                            ScoreInner(totalScore = it.total, label = it.label, breakdown = it.breakdown)
                        }
                        // [260726] 같은 응답에 실측 공복혈당이 들어있음 — 추가 왕복 없이 같이 캐시
                        glucoseByDate[selectedDate] = res.data.glucose?.value
                        // [260727] 실측 수면 시간도 같은 응답에 있음 (activity.sleep_hours, 전날 워치 = 점수 산출 기준)
                        sleepMinByDate[selectedDate] = toSleepMinutes(res.data.activity?.sleepHours)
                    }
                    is ApiResult.Error -> fetchError = true   // 전송 오류 — 캐시하지 않음
                }
            } finally {
                fetching = false   // [코덱스 P1] 취소/예외에도 로딩 플래그 해제 (영구 로딩 방지)
            }
        } else if (selectedDate == today && !glucoseByDate.containsKey(today)) {
            // [260726 의료안전] 오늘 점수는 HomeViewModel(ScoreTodayResponse)에서 오는데 그 응답엔
            //   안전상태도 실측 공복혈당도 없다(백엔드 scores.py 확인). 설명 문구를 저혈당/초고혈당으로
            //   가르기 위해 daily-summary 에서 '수치만' 가져온다. 실패해도 기존 문구로 폴백.
            //   [260727] 수면 시간(activity.sleep_hours)도 같은 이유(같은 점수가 부족/과다 양쪽에 붙는다)로 함께 가져온다.
            when (val res =
                com.dangbomcare.app.data.ServiceLocator.scoreRepository.dailySummary(today.toString())) {
                is ApiResult.Success -> {
                    glucoseByDate[today] = res.data.glucose?.value
                    sleepMinByDate[today] = toSleepMinutes(res.data.activity?.sleepHours)
                }
                is ApiResult.Error -> { /* 설명 보조 정보라 오류는 무시 */ }
            }
        }
        // 비교일 — 부가 정보라 오류는 무시 (성공 시에만 캐시)
        if (previousDate != today && !dailyCache.containsKey(previousDate)) {
            when (val res =
                com.dangbomcare.app.data.ServiceLocator.scoreRepository.dailySummary(previousDate.toString())) {
                is ApiResult.Success -> {
                    dailyCache[previousDate] = res.data.score?.let {
                        ScoreInner(totalScore = it.total, label = it.label, breakdown = it.breakdown)
                    }
                    // [260727 버그] 비교일 응답에서 실측 수치를 버리고 있었다.
                    //   ← 화살표로 그 날짜로 이동하면 dailyCache 에 키가 이미 있어 위 상세 fetch 가 통째로 스킵되고,
                    //     glucose/sleep 수치가 없어 설명 문구가 '수치 없음' 폴백으로 떨어졌다(=수면 구간 표기 실패).
                    //   같은 응답에 이미 들어있으므로 추가 왕복 없이 함께 캐시한다.
                    glucoseByDate[previousDate] = res.data.glucose?.value
                    sleepMinByDate[previousDate] = toSleepMinutes(res.data.activity?.sleepHours)
                }
                is ApiResult.Error -> { /* 비교일 오류는 무시 */ }
            }
        }
    }

    Scaffold(
        containerColor = BgSecondary,
        topBar = {
            TopAppBar(
                title = { Text("점수 상세", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
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
            Spacer(Modifier.height(4.dp))

            // 날짜 네비게이션 (좌우 화살표)
            DateNavBar(
                date = selectedDate,
                today = today,
                onPrev = { selectedDate = selectedDate.minusDays(1) },
                onNext = {
                    if (selectedDate < today) selectedDate = selectedDate.plusDays(1)
                },
                nextEnabled = selectedDate < today
            )

            when {
                (state.loading && selectedDate == today) || fetching ->
                    LoadingIndicator(modifier = Modifier.height(200.dp))

                // [코덱스 P1] 전송 오류(캐시 안 됨) → "기록 없음"이 아니라 재시도 안내
                selectedDate != today && fetchError && !dailyCache.containsKey(selectedDate) ->
                    ErrorMessage(message = "점수를 불러오지 못했어요. 인터넷을 확인하고 잠시 후 다시 열어주세요.")

                // [버그수정] 과거 날짜는 fetch 완료(캐시에 키 존재) 전까지 로딩 표시 → "없어요" 깜빡임 방지
                selectedDate != today && !dailyCache.containsKey(selectedDate) ->
                    LoadingIndicator(modifier = Modifier.height(200.dp))

                selectedScore == null -> {
                    ErrorMessage(
                        message = if (selectedDate == today) "오늘 점수 데이터가 아직 없어요. 아침 입력을 먼저 완료해주세요."
                                  else "${selectedDate.monthValue}월 ${selectedDate.dayOfMonth}일은 입력된 기록이 없어요."
                    )
                }

                else -> {
                    val b: ScoreBreakdown = selectedScore.breakdown
                        ?: ScoreBreakdown()  // 모든 필드 null
                    val totalScore = selectedScore.totalScore ?: 0.0
                    val totalLabel = selectedScore.label ?: ""

                    // 점수 게이지 + 직전일 대비 차이
                    val prevTotal = previousScore?.totalScore
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = BgCard)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp).fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            ScoreGauge(score = totalScore, label = totalLabel)
                            if (prevTotal != null) {
                                Spacer(Modifier.height(12.dp))
                                val diff = totalScore - prevTotal
                                val (arrow, color, sign) = when {
                                    diff > 0 -> Triple("▲", Success, "+")
                                    diff < 0 -> Triple("▼", ErrorRed, "")
                                    else -> Triple("•", TextSecondary, "")
                                }
                                Text(
                                    "직전일 대비 $arrow $sign${fmtPts(diff)}점 (직전일 ${fmtPts(prevTotal)}점)",
                                    fontSize = 12.sp,
                                    color = color,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    // 항목별 점수 — 직전일 대비 비교 함께 표시
                    // [사용자 피드백] 정규화 제거 — 측정된 항목 점수만 단순 합산. 합산 = 총점.
                    val prevB: ScoreBreakdown = previousScore?.breakdown ?: ScoreBreakdown()
                    // [저녁 마감시간 동적화] 설정한 저녁식사 시각 라벨 (하드코딩 '7시' 제거)
                    val dinnerLbl = com.dangbomcare.app.notification.AlarmScheduler
                        .dinnerHourLabel(androidx.compose.ui.platform.LocalContext.current)

                    // [260623 피드백] 점수 재배분 — 혈당15·걸음10·식후금식10(5단계)·식사유형5·소화5·식후운동10 등
                    SectionCard(title = "항목별 점수") {
                        ScoreRow(
                            label = "공복혈당", sourceLabel = "오늘 아침",
                            value = b.glucose, max = 15,
                            previousValue = prevB.glucose,
                            description = describeGlucose(
                                b.glucose, glucoseByDate[selectedDate], glucoseRangeLow, glucoseRangeHigh
                            )
                        )
                        ScoreRow(
                            label = "걸음수", sourceLabel = "전날",
                            value = b.steps, max = 10,
                            previousValue = prevB.steps,
                            description = describeSteps(b.steps, b.stepsPostMealBonus)
                        )
                        ScoreRow(
                            label = "수면", sourceLabel = "전날",
                            value = b.sleep, max = 20,
                            previousValue = prevB.sleep,
                            description = describeSleep(b.sleep, sleepMinByDate[selectedDate])
                        )
                        ScoreRow(
                            label = "$dinnerLbl 전 저녁", sourceLabel = "전날",
                            value = b.dinnerBefore7pm, max = 10,
                            previousValue = prevB.dinnerBefore7pm,
                            description = describeDinner(b.dinnerBefore7pm, dinnerLbl)
                        )
                        ScoreRow(
                            label = "식후 음식 섭취 여부", sourceLabel = "전날",
                            value = b.noFoodAfterDinner, max = 10,
                            previousValue = prevB.noFoodAfterDinner,
                            description = describeNoFood(b.noFoodAfterDinner)
                        )
                        ScoreRow(
                            label = "저녁식사 유형", sourceLabel = "전날",
                            value = b.dinnerType, max = 5,
                            previousValue = prevB.dinnerType,
                            description = describeDinnerType(b.dinnerType)
                        )
                        ScoreRow(
                            label = "자기 전 소화", sourceLabel = "전날",
                            value = b.digestion, max = 5,
                            previousValue = prevB.digestion,
                            description = describeDigestion(b.digestion)
                        )
                        ScoreRow(
                            label = "식후 운동", sourceLabel = "전날",
                            value = b.postMealExercise, max = 10,
                            previousValue = prevB.postMealExercise,
                            description = describePostMealExercise(b.postMealExercise)
                        )
                        ScoreRow(
                            label = "음주", sourceLabel = "전날",
                            value = b.alcohol, max = 10,
                            previousValue = prevB.alcohol,
                            // [260728] 저혈당 주의 안내를 그 날 실측 공복혈당 + 개인 평소 범위로 게이트
                            //   (rangeHigh 까지 넘겨 백엔드 _is_glucose_actually_low 와 동일 규칙 적용)
                            description = describeAlcohol(
                                b.alcohol, glucoseByDate[selectedDate], glucoseRangeLow, glucoseRangeHigh
                            )
                        )
                        ScoreRow(
                            label = "스트레스", sourceLabel = "전날",
                            value = b.stress, max = 5,
                            previousValue = prevB.stress,
                            description = describeStress(b.stress)
                        )
                    }

                    MissingDataCard(b)

                    Spacer(Modifier.height(40.dp))
                }
            }
        }
    }
}

/**
 * 날짜 네비게이션 바 — 좌우 화살표 + 가운데 선택 날짜.
 * - 오늘이면 "오늘 (5/20 수) — 어제 + 오늘 공복혈당"
 * - 그 외엔 "5/19 (월) — 5/18 + 5/19 공복혈당"
 */
@Composable
private fun DateNavBar(
    date: LocalDate,
    today: LocalDate,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    nextEnabled: Boolean,
) {
    val dow = when (date.dayOfWeek.value) {
        1 -> "월"; 2 -> "화"; 3 -> "수"; 4 -> "목"
        5 -> "금"; 6 -> "토"; else -> "일"
    }
    // [피드백 260714] 제목을 날짜(요일) 형식으로 — "전날 종합 점수" → "7/13(월) 점수".
    //   점수 산출 근거(전날 생활 + 오늘 공복혈당)는 서브라벨로 유지.
    val mainLabel = "${date.monthValue}/${date.dayOfMonth}($dow) 점수"
    val subLabel = if (date == today)
        "오늘 아침 공복혈당 기준 · 전날 생활을 평가한 점수"
    else
        "이 날 아침 공복혈당 기준 · 전날 생활을 평가한 점수"

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onPrev) {
                Icon(Icons.Filled.ChevronLeft, contentDescription = "이전 날짜", tint = Primary)
            }
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    mainLabel,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    textAlign = TextAlign.Center
                )
                Text(
                    subLabel,
                    fontSize = 10.sp,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )
            }
            IconButton(onClick = onNext, enabled = nextEnabled) {
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = "다음 날짜",
                    tint = if (nextEnabled) Primary else TextDisabled
                )
            }
        }
    }
}

/**
 * 어제 워치 + 입력 실제 데이터 카드 — Health Connect에서 직접 어제 데이터 읽음.
 * 점수와 함께 실측 수치(걸음 4,839보 / 수면 8h 39m 등)를 보여줘서 사용자 신뢰 확보.
 */
@Composable
private fun YesterdayDataCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var steps by remember { mutableStateOf<Int?>(null) }
    var sleepMin by remember { mutableStateOf<Int?>(null) }
    var sleepDeep by remember { mutableStateOf<Int?>(null) }
    var sleepRem by remember { mutableStateOf<Int?>(null) }
    var hrAvg by remember { mutableStateOf<Int?>(null) }
    var glucose by remember { mutableStateOf<Int?>(null) }
    var sdkAvailable by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        try {
            val repo = com.dangbomcare.app.data.health.HealthConnectRepository(context)
            if (!repo.isSdkAvailable()) {
                sdkAvailable = false
                loading = false
                return@LaunchedEffect
            }
            val data = repo.fetchWatchData(java.time.LocalDate.now().minusDays(1))
            steps = data.steps?.takeIf { it > 0 }
            sleepMin = data.sleepTotalMinutes
            sleepDeep = data.sleepDeepMinutes
            sleepRem = data.sleepRemMinutes
            hrAvg = data.avgHeartRate
        } catch (e: Exception) {
            // 권한 없거나 HC 미설치 → null 유지
        }
        // 오늘 공복혈당 입력 (점수 계산에 들어간 값)
        try {
            val today = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
            when (val res = com.dangbomcare.app.data.ServiceLocator.dailyLogRepository.list(today, today)) {
                is com.dangbomcare.app.data.repo.ApiResult.Success ->
                    glucose = res.data.firstOrNull()?.fastingGlucose
                else -> {}
            }
        } catch (e: Exception) { /* ignore */ }
        loading = false
    }

    // 오늘 입력값 (공복혈당)
    SectionCard(title = "🌅 오늘 아침 입력") {
        if (loading) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Primary, strokeWidth = 2.dp)
            }
        } else {
            DataRow("🩸 공복혈당", glucose?.let { "$it mg/dL" } ?: "입력 안 함")
        }
    }

    // 전날 워치 데이터
    SectionCard(title = "⌚ 전날 워치 데이터") {
        if (loading) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Primary, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Health Connect 읽는 중...", fontSize = 13.sp, color = TextSecondary)
            }
        } else if (!sdkAvailable) {
            Text(
                "Health Connect SDK가 설치돼 있지 않아요.\nPlay 스토어에서 'Health Connect' 설치 후 다시 시도해주세요.",
                fontSize = 13.sp,
                color = TextSecondary,
                lineHeight = 18.sp
            )
        } else {
            DataRow("🚶 걸음수", steps?.let { "%,d보".format(it) } ?: "삼성헬스 동기화 안 됨")
            DataRow(
                "😴 수면",
                sleepMin?.let { "${it / 60}시간 ${it % 60}분" } ?: "삼성헬스 동기화 안 됨"
            )
            if (sleepDeep != null || sleepRem != null) {
                DataRow(
                    "    └ 체력 회복 / 뇌 휴식",
                    "${sleepDeep ?: 0}분 / ${sleepRem ?: 0}분",
                    indent = true
                )
            }
            DataRow("❤️ 평균 심박", hrAvg?.let { "$it bpm" } ?: "삼성헬스 동기화 안 됨")
        }
    }
}

@Composable
private fun DataRow(label: String, value: String, indent: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            fontSize = if (indent) 12.sp else 14.sp,
            color = if (indent) TextSecondary else TextPrimary,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            fontSize = if (indent) 12.sp else 15.sp,
            fontWeight = if (indent) FontWeight.Normal else FontWeight.Bold,
            color = if (value.contains("안 됨") || value.contains("안 함")) TextSecondary else TextPrimary
        )
    }
}

// [260623] 점수 표시 — 정수면 정수로, 소수(2.5)면 그대로 (10.0→"10", 2.5→"2.5").
private fun fmtPts(v: Double): String = if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()

// 정수 점수용 오버로드 — 기존 호출부(혈당/걸음 등 Int?)는 그대로, 내부에서 Double로 위임.
@Composable
private fun ScoreRow(
    label: String, value: Int?, max: Int, bonus: Int? = null,
    description: String? = null, previousValue: Int? = null, sourceLabel: String? = null,
) = ScoreRow(label, value?.toDouble(), max, bonus, description, previousValue?.toDouble(), sourceLabel)

@Composable
private fun ScoreRow(
    label: String,
    value: Double?,
    max: Int,
    bonus: Int? = null,
    description: String? = null,
    previousValue: Double? = null,
    sourceLabel: String? = null,   // "전날", "오늘 아침" 등 시점 라벨
) {
    val isUnscored = value == null
    val displayValue = value ?: 0.0
    val ratio = (displayValue.toFloat() / max).coerceIn(0f, 1f)
    val color = when {
        isUnscored -> TextDisabled
        ratio >= 0.7 -> Success
        ratio >= 0.4 -> Warning
        else -> ErrorRed
    }
    // [260726 접근성] Color.kt 규정 — 노랑 원색(Warning)은 흰 배경 대비 <3:1 이라 '글자'엔 쓰면 안 된다.
    //   진행바(그래픽)엔 원색 그대로 두고, 점수 숫자 '글자'만 진한 앰버(WarningText)로 교체.
    val valueTextColor = if (color == Warning) WarningText else color
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontSize = 15.sp, color = TextPrimary)
                if (sourceLabel != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "($sourceLabel)",
                        fontSize = 11.sp,
                        color = TextSecondary,
                        fontWeight = FontWeight.Normal
                    )
                }
            }
            // 직전일 대비 비교 (양쪽 점수 있을 때만)
            if (!isUnscored && previousValue != null) {
                val diff = displayValue - previousValue
                val (arrow, diffColor, sign) = when {
                    diff > 0 -> Triple("▲", Success, "+")
                    diff < 0 -> Triple("▼", ErrorRed, "")
                    else -> Triple("•", TextSecondary, "")
                }
                Text(
                    "$arrow $sign${fmtPts(diff)}",
                    fontSize = 11.sp,
                    color = diffColor,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
            Text(
                if (isUnscored) "미입력 / $max" else "${fmtPts(displayValue)} / $max",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = valueTextColor
            )
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { if (isUnscored) 0f else ratio },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            color = color,
            trackColor = BgCardSoft
        )
        // 한 줄 설명 박스 (당봄 피드백 #4)
        if (description != null) {
            Spacer(Modifier.height(6.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = BgCardSoft
            ) {
                Text(
                    description,
                    fontSize = 12.sp,
                    color = TextSecondary,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
        // 보너스 점수 — 끼니별로 명확하게 (당봄 피드백 #5)
        if (bonus != null && bonus > 0) {
            Spacer(Modifier.height(4.dp))
            Text(
                "+ 식사 후 30분~2시간 사이 1,000보 이상 걸으면 식사당 +3점 (현재 +${bonus}점)",
                fontSize = 11.sp,
                color = Success,
                lineHeight = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

// ============================================================
// 항목별 한 줄 설명 함수 (당봄 피드백 #4)
// ============================================================
// [260727 고객피드백 4] 구분자 규칙 — "그날 내 기록 : 권장/코멘트".
//   기존엔 대시(—)를 썼는데 "5시간 미만 — 7시간 이상 수면 권장"이
//   "5시간 미만에서 7시간 이상 수면을 권장한다"로 읽힌다는 지적을 받았다.
//   → 모든 describeXxx 는 기록과 권장 사이를 콜론(" : ")으로 구분한다.

// [260727] 백엔드 daily-summary 의 activity.sleep_hours(소수 1자리) → 분.
//   RecordsScreen 과 같은 변환식(round(h*60))이라 화면 간 표기가 항상 일치한다.
private fun toSleepMinutes(sleepHours: Double?): Int? =
    sleepHours?.let { kotlin.math.round(it * 60).toInt() }

// [260623] 혈당 15점 체계 — 실제 3구간 분류(정상<100/목표100~130/높음>130)는 입력 화면·일일종합에서도 수치로 노출.
// [260726 의료안전] 점수만으로 설명하던 걸 실측 수치(glucose)와 개인 범위(rangeLow/High)를 함께 보도록 수정.
//   ① 백엔드 glucose_score()는 <70(저혈당)과 >180(초고혈당)에 **똑같이 0점**을 준다.
//      0점을 한 문구로 뭉개면 혈당 48인 환자에게 당분 섭취 안내 대신 "관리가 필요"만 보인다 → 반드시 갈라야 함.
//   ② 10점도 '100~130(당뇨 목표)'과 '130 초과지만 내 평소 범위(rangeHigh) 이내' 두 경우라
//      무조건 "당뇨 목표 범위"로 단정하면 사실과 다르다.
//   glucose 가 null(수치 조회 실패)이면 단정하지 않는 중립 문구로 폴백한다.
//
// [260728 고객피드백 ②] 실측 수치가 **개인 평소 범위 하한보다 낮은** 날을 '범위 안'으로 뭉개면 안 된다.
//   사고 사례: 99 mg/dL · 평소 범위 120~140 인데 "평소 범위(120~140) 안에서 잘 유지되고 계세요".
//   → 하한 미만이면 "평소 범위보다 낮게 유지되고 있어요" 로 방향을 말한다.
//   가드는 describeAlcohol / 백엔드 _is_glucose_actually_low 와 **동일** —
//   rangeLow·rangeHigh 가 둘 다 있고 low <= high 일 때만 개인 범위를 근거로 쓴다(한쪽만 온 응답에서 갈리지 않게).
//   ※ 저혈당(score 0, <70) 안내는 위에서 이미 return 되므로 이 문구가 저혈당 경고를 덮지 않는다.
private fun belowUsualRangeText(glucose: Int?, rangeLow: Int?, rangeHigh: Int?): String? {
    if (glucose == null || rangeLow == null || rangeHigh == null) return null
    if (rangeLow > rangeHigh) return null
    if (glucose >= rangeLow) return null
    return "✓ 공복혈당 ${glucose} mg/dL : 평소 범위(${rangeLow}~${rangeHigh})보다 낮게 유지되고 있어요"
}

private fun describeGlucose(
    score: Int?,
    glucose: Int? = null,
    rangeLow: Int? = null,
    rangeHigh: Int? = null,
): String {
    if (score == null) return "공복혈당을 입력하면 점수가 계산됩니다"
    if (score == 0) {
        // 저혈당 안내 문구는 백엔드 glucose_safety_message 와 같은 톤으로 통일 (약물·용량 지시 금지)
        return when {
            glucose == null ->
                "⚠ 공복혈당이 안전 범위를 벗어났어요 : 수치를 확인하고 의료진 상담을 권장해요"
            glucose < 55 ->
                "⚠ 공복혈당 ${glucose} mg/dL : 저혈당이에요. 주스·사탕 등 단 것을 바로 드시고 15분 뒤 다시 재보세요"
            glucose < 70 ->
                "⚠ 저혈당 의심 (${glucose} mg/dL) : 가벼운 간식으로 당분을 보충하고 15분 뒤 다시 재보세요. 운동은 잠시 미뤄주세요"
            else ->
                "⚠ 공복혈당 ${glucose} mg/dL : 많이 높아요. 무리한 활동은 피하시고 의료진 상담을 권장해요"
        }
    }
    // [260728 ②] 평소 범위 하한 미만이면 '범위 안'이라고 말하지 않고 방향(낮음)을 먼저 말한다.
    val belowUsual = belowUsualRangeText(glucose, rangeLow, rangeHigh)
    return when (score) {
        15 -> belowUsual
            ?: if (glucose != null) "✓ 공복혈당 ${glucose} mg/dL : 안정적이에요, 잘 관리하고 계세요"
            else "✓ 공복혈당이 안정적이에요 : 잘 관리하고 계세요"
        // 10점은 두 갈래다 — ① 100~130(당뇨 목표) ② 130 초과지만 내 평소 범위(rangeHigh) 이내.
        //   수치를 모르면 어느 쪽인지 단정할 수 없으므로 둘 다 언급하는 문구로 남긴다.
        //   [260728 ②] 목표 구간이더라도 내 평소 범위 하한보다 낮으면 '범위 안'이 아니라 '평소보다 낮음'이 맞다.
        10 -> belowUsual ?: when {
            glucose == null ->
                "✓ 공복혈당 양호 : 당뇨 목표 범위(100~130) 또는 내 평소 범위 안이에요"
            glucose <= 130 ->
                "✓ 공복혈당 ${glucose} mg/dL : 당뇨 목표 범위(100~130) 안이에요"
            rangeHigh != null ->
                "✓ 공복혈당 ${glucose} mg/dL : 당뇨 목표(100~130)보다는 높지만 " +
                    "내 평소 범위(${if (rangeLow != null) "${rangeLow}~${rangeHigh}" else "${rangeHigh} 이하"}) 안이에요"
            else ->
                "✓ 공복혈당 ${glucose} mg/dL : 당뇨 목표(100~130)보다는 높지만 내 평소 범위 안이라 양호로 봤어요"
        }
        6 -> "○ 공복혈당이 조금 높은 편 : 식단·운동을 점검해보세요"
        3 -> "⚠ 공복혈당이 높은 편 : 의료진 상담을 권장해요"
        else -> "⚠ 공복혈당 관리가 필요해요 : 의료진 상담을 권장해요"
    }
}

private fun describeSteps(score: Int?, bonus: Int?): String {
    if (score == null) return "워치 데이터가 없어 점수를 계산할 수 없어요"
    val bonusInt = bonus ?: 0
    val base = score - bonusInt
    return when {
        base >= 10 -> "✓ 8,000보 이상 : 활동량 우수 (기본 ${base}점)"
        base >= 8 -> "✓ 6,000~7,999보 : 양호 (기본 ${base}점)"
        base >= 6 -> "○ 4,000~5,999보 : 보통 (기본 ${base}점)"
        base >= 3 -> "△ 2,000~3,999보 : 부족 (기본 ${base}점)"
        else -> "△ 2,000보 미만 : 매우 부족 (기본 ${base}점)"
    }
}

/**
 * 수면 시간(분) → 점수. 백엔드 score_service.sleep_score 와 **동일 경계**여야 한다
 *   (어긋나면 화면에 보이는 점수와 라벨이 모순된다).
 *
 * [260728 고객피드백] 수면은 0점이 없다. 0점은 **수면 데이터 자체가 없는 경우(null)** 뿐이다.
 *   적정(7~9시간) 20점에서 멀어질수록 낮아지는데 **부족 쪽 3점씩 / 과다 쪽 2점씩(비대칭)**이다.
 *   수면 부족이 인슐린 저항성을 즉각 올리는 더 강한 요인이라서다. 최저는 부족 6점 / 과다 10점.
 *   적정은 양 끝 포함, 부족 쪽은 [하한, 상한), 과다 쪽은 (하한, 상한].
 *   h = 분/60 이므로 분 단위 정수로 그대로 비교할 수 있다(7.0h = 420분, 9.0h = 540분).
 */
private fun sleepScoreForMinutes(m: Int): Int = when {
    m in 420..540 -> 20     // 7.0 <= h <= 9.0 (적정)
    // 부족 쪽 — 3점씩
    m in 360..419 -> 17     // 6~7시간
    m in 300..359 -> 14     // 5~6시간
    m in 240..299 -> 11     // 4~5시간
    m in 180..239 -> 8      // 3~4시간
    m < 180 -> 6            // 3시간 미만
    // 과다 쪽 — 2점씩
    m in 541..600 -> 18     // 9~10시간
    m in 601..660 -> 16     // 10~11시간
    m in 661..720 -> 14     // 11~12시간
    m in 721..780 -> 12     // 12~13시간
    else -> 10              // 13시간 초과
}

/**
 * 수면 분 → (아이콘, 상태 라벨). **점수가 아니라 분으로 판정한다.**
 *   배점이 비대칭이 되면서 같은 점수가 양쪽에 걸치거나(14점 = 5~6시간 또는 11~12시간)
 *   한쪽에만 존재해(17점은 부족 쪽만, 18점은 과다 쪽만) 점수로는 방향·심각도를 알 수 없다.
 *   적정에서 몇 칸 떨어졌는지로 아이콘을 정하고, 420분 기준으로 부족/과다를 가른다.
 */
private fun sleepStateForMinutes(m: Int): Pair<String, String> = when {
    m in 420..540 -> "✓" to "적정"
    m in 360..419 -> "○" to "조금 부족"
    m in 541..600 -> "○" to "조금 김"
    m in 300..359 -> "△" to "수면 부족"
    m in 601..660 -> "△" to "수면 과다"
    m in 240..299 -> "△" to "수면 부족"
    m in 661..720 -> "△" to "수면 과다"
    m < 420 -> "⚠" to "수면 매우 부족"
    else -> "⚠" to "수면 매우 과다"
}

/**
 * 수면 설명 — [260728 고객피드백] 새 차등 배점(20~10점, 0점 없음)에 맞춰 재작성.
 *
 * 점수 기반 판정은 더 이상 성립하지 않는다: 같은 점수가 부족·과다 양쪽에 존재한다
 *   (18점 = 6~7시간 **또는** 9~10시간). → 실측 수면 분으로 구간을 판정한다.
 *   과다수면을 "수면 부족"으로 쓰지 않도록 방향을 반드시 가른다(260727 수정 버그의 재발 방지).
 *
 * 단, sleepMinutes 는 백엔드가 sleep_hours 를 소수 1자리로 반올림해 내려준 값이라
 *   경계에서 원래 분과 최대 3분 차이가 난다(6시간 59분 → 7.0시간 → 420분).
 *   그래서 분으로 다시 계산한 점수가 화면 점수와 다르면 구간·방향을 단정하지 않는다.
 */
private fun describeSleep(score: Int?, sleepMinutes: Int?): String {
    if (score == null) return "워치 수면 데이터가 없어요"
    // 실측 시간을 못 받았을 때(폴백) — 점수만으로는 부족/과다를 알 수 없으므로 구간을 단정하지 않는다.
    //   비대칭 배점이라 17점은 부족 쪽만, 18점은 과다 쪽만 존재한다 → 한쪽만 아는 점수는 방향을 밝힐 수 있으나
    //   14점처럼 양쪽에 걸치는 값이 있어 일괄 규칙으로는 안전하지 않다. 방향을 말하지 않는다.
    if (sleepMinutes == null || sleepMinutes <= 0) return when {
        score >= 20 -> "✓ 7~9시간 적정 수면 : 혈당 관리에 최적"
        score >= 17 -> "○ 적정 수면(7~9시간)에 가까워요 : 7~9시간 수면 권장"
        score >= 11 -> "△ 적정 수면(7~9시간)에서 벗어났어요 : 7~9시간 수면 권장"
        else -> "⚠ 적정 수면(7~9시간)에서 많이 벗어났어요 : 7~9시간 수면 권장"
    }
    val hhmm = "${sleepMinutes / 60}시간 ${sleepMinutes % 60}분"
    val (icon, state) = sleepStateForMinutes(sleepMinutes)
    // 반올림 경계 — 백엔드가 sleep_hours 를 소수 1자리로 내려주므로 분이 최대 3분 어긋난다.
    //   분으로 다시 계산한 점수가 화면 점수와 다르면 구간을 단정하지 않고 시간만 보여준다.
    //   ⚠️ [260730] 이때 아이콘도 붙이면 안 된다 — 6시간 59분(점수 17)이 반올림으로 420분이 되면
    //     sleepStateForMinutes 는 "✓ 적정"을 주는데 점수는 감점(17)이라 '✓ 적정'과 감점이 함께 보인다.
    //     경계에서는 상태 기호 없이 시간과 권장만 보여준다.
    if (sleepScoreForMinutes(sleepMinutes) != score) return "$hhmm : 7~9시간 수면 권장"
    if (state == "적정") return "✓ $hhmm (적정) : 7~9시간 수면이라 혈당 관리에 좋아요"
    return "$icon $hhmm ($state) : 7~9시간 수면 권장"
}

private fun describeDinner(score: Int?, deadlineLabel: String): String = when (score) {
    null -> "전날 회고에서 저녁 식사 시각을 입력해주세요"
    10 -> "✓ $deadlineLabel 전 저녁 마침 : 야간 혈당 안정에 도움"
    0 -> "△ $deadlineLabel 이후 저녁 : 다음엔 일찍 드셔보세요"
    else -> ""
}

// [260727 고객피드백 3] 의학적으로 반대로 쓰여 있던 문구 교정.
//   술은 간이 알코올을 처리하는 동안 밤사이 혈당을 끌어올리지 못하게 만들어
//   **다음 날 아침 공복혈당이 오히려 낮게** 나오는 경향이 있다(저혈당 주의).
//   기존 문구는 "다음날 공복혈당이 올라갈 수 있어요"로 정반대였다.
//   단정은 피하고(경향/수 있어요), 안주 많이 먹은 예외도 짧게 덧붙인다. 어려운 의학용어는 쓰지 않는다.
//
// [260728 고객피드백] 저혈당 주의 안내가 **음주만 하면 무조건** 떠서 과했다.
//   → 방향 설명("낮추는 경향")은 수치와 무관하게 항상 맞으니 그대로 두고,
//     행동 지시성 주의 안내는 그 날 공복혈당이 **실제로 낮게 나왔을 때만** 붙인다.
//     기준: 개인 평소 범위가 있으면 그 하한, 없으면 100 mg/dL. 수치가 없으면(null) 추측하지 않고 생략.
//   ※ glucose < 70 저혈당 구간의 안전 안내는 describeGlucose 가 음주 여부와 무관하게 계속 띄운다(안전망 유지).
//
// ⚠️ 게이트 판정은 백엔드 fallback_messages._is_glucose_actually_low() 와 **한 글자도 다르면 안 된다.**
//   세 층(프롬프트·fallback·앱)이 같은 상황에서 같은 안내를 내야 하기 때문이다.
//   그래서 rangeLow 하나만 보지 않고 rangeHigh 까지 받아 **둘 다 있고 low <= high 일 때만** 개인 범위를 쓴다.
//   (users 테이블 CHECK 제약이 '둘 다 있거나 둘 다 없음'을 보장하지만, 앱은 두 필드를 각각 nullable 로
//    받으므로 한쪽만 온 응답에서 조건이 갈리지 않도록 여기서도 같은 규칙을 명시한다.)
private fun describeAlcohol(
    score: Int?,
    glucose: Int? = null,
    rangeLow: Int? = null,
    rangeHigh: Int? = null,
): String = when (score) {
    null -> "전날 회고에서 음주 여부를 입력해주세요"
    // 기존 "다음날 혈당·수면 안정"은 단정이라 관찰형으로 완화.
    10 -> "✓ 음주 없음 : 혈당과 잠이 흔들릴 요인이 하나 줄었어요"
    0 -> {
        val base = "△ 음주 있음 : 술은 다음 날 아침 공복혈당을 오히려 낮추는 경향이 있어요"
        // 수치를 모르면 주의 안내를 띄우지 않는다(추측 금지).
        val threshold =
            if (rangeLow != null && rangeHigh != null && rangeLow <= rangeHigh) rangeLow else 100
        val actuallyLow = glucose != null && glucose < threshold
        // [260728 R4 최종 교차검증] 두 곳을 고쳤다.
        //   (1) 줄표('—') 제거 — 260727 고객 피드백: 시니어에게 줄표 앞뒤가 한 문장처럼 이어져 읽혀
        //       "낮게 나왔으니 단 것을 드세요"가 아니라 다른 뜻으로 오독된다. 마침표로 끊는다.
        //   (2) "안주를 많이 드셨다면" 의 **양 추정** 제거 — 안주를 얼마나 드셨는지는 어느 계층에도
        //       데이터가 없다. 백엔드 ai_prompts.py / fallback_messages.py 와 같은 기준(양이 아니라
        //       '함께 드신 음식이 있는지')으로 통일한다.
        if (actuallyLow)
            "$base. 오늘 ${glucose} mg/dL 로 실제 낮게 나왔어요. 어지럽거나 식은땀이 나면 바로 단 것을 드세요"
        else
            "$base (함께 드신 안주나 식사 후 추가 음식이 있으면 반대로 높아질 수 있어요)"
    }
    else -> ""
}

// [260623] 식후 금식 5단계 (2/4/6/8/10)
private fun describeNoFood(score: Int?): String = when (score) {
    null -> "전날 회고에서 식후 음식 섭취 여부를 입력해주세요"
    10 -> "✓ 4시간 이상 비움 : 야간 혈당 안정에 매우 좋아요"
    8 -> "✓ 3~4시간 비움 : 잘 지키셨어요"
    6 -> "○ 2~3시간 비움 : 양호해요"
    4 -> "△ 1~2시간 만에 드심 : 식후 공복 시간을 더 늘려보세요"
    2 -> "△ 1시간 안에 드심 : 식후엔 좀 더 비워보세요"
    0 -> "△ 식후 추가 섭취 : 다음날 공복혈당이 올라갈 수 있어요"
    else -> ""
}

// [260623] 신규 3항목 설명
private fun describeDinnerType(score: Int?): String = when (score) {
    null -> "전날 회고에서 저녁식사 유형을 선택해주세요"
    5 -> "✓ 집밥 한식·간단식 위주 : 혈당 부담이 적어요"
    // [v1.1.3] 옵션 개편 — "밀가루" 삭제, "기타"(자유 입력) 신설. 외식/배달/육류/기타 포함 시 감점 안내
    0 -> "△ 외식·배달·육류·기타 포함 : 다음엔 집밥·간단식 비중을 늘려보세요"
    else -> ""
}

private fun describeDigestion(score: Double?): String = when (score) {
    null -> "전날 회고에서 자기 전 소화 상태를 선택해주세요"
    5.0 -> "✓ 속이 편하게 비운 상태로 취침 : 야간 혈당 안정에 도움 (굶으라는 뜻은 아니에요)"
    2.5 -> "○ 소화가 조금 된 상태 : 무난해요"
    0.0 -> "△ 배부른 상태로 취침 : 저녁을 일찍·가볍게 드셔보세요"
    else -> ""
}

private fun describePostMealExercise(score: Int?): String = when (score) {
    null -> "전날 회고에서 식후 운동을 선택해주세요"
    10 -> "✓ 1시간 이상 : 식후 혈당이 급하게 오르는 걸 크게 줄였어요"
    6 -> "✓ 30~60분 : 식후 활동 좋아요"
    3 -> "○ 10~30분 : 조금 더 늘리면 더 좋아요"
    0 -> "△ 식후 운동 없음 : 식후 10분 걷기부터 시작해보세요"
    else -> ""
}

private fun describeStress(score: Int?): String = when (score) {
    null -> "워치 스트레스 데이터가 없어요"
    // [260727] "코르티솔"은 시니어에게 어려운 의학용어 → 쉬운 말로 교체
    5 -> "✓ 편안함 : 몸이 잘 쉰 상태였어요"
    3 -> "○ 보통 : 일상 수준의 스트레스"
    1 -> "△ 다소 높음 : 마음 돌봄 권장"
    else -> ""
}

/**
 * 누락된 데이터 항목별로 구체적 안내 메시지 카드.
 * (당봄 피드백: "수면 데이터 누락 시 명시" 등 구체적 멘트 요구)
 */
@Composable
private fun MissingDataCard(b: ScoreBreakdown) {
    // [260728 통합검증] 같은 화면(점수 상세)의 항목 설명은 전부 " : " 로 통일됐는데
    //   이 카드만 대시(—)가 남아 있었다. 고객 지적(#4)의 이유("앞은 내 기록, 뒤는 권장인데
    //   대시로는 한 문장처럼 읽힌다")가 여기에도 똑같이 적용되므로 콜론으로 맞춘다.
    val missing = buildList {
        if (b.glucose == null) add("🩸 오늘 아침 공복혈당이 입력되지 않았어요 : 오늘 아침 입력하시면 점수가 완성돼요")
        if (b.steps == null) add("🚶 전날 걸음 수가 측정되지 않았어요 : 워치 착용·동기화를 확인해주세요")
        if (b.sleep == null) add("😴 전날 수면 데이터가 측정되지 않았어요 : 잘 때도 워치를 착용해주세요")
        if (b.stress == null) add("😌 전날 스트레스 데이터가 없어요 : 워치 착용 시간이 충분해야 분석이 가능해요")
        if (b.dinnerBefore7pm == null) add("🍚 전날 저녁 식사 시각이 입력되지 않았어요")
        if (b.alcohol == null) add("🍷 전날 음주 여부가 입력되지 않았어요")
        if (b.noFoodAfterDinner == null) add("🍜 전날 식후 추가 식사 여부가 입력되지 않았어요")
        // [260702] 신규 회고 3항목도 누락 안내 (안 하면 무엇을 더 입력해야 점수가 완성되는지 몰라요)
        if (b.dinnerType == null) add("🍽️ 전날 저녁식사 유형이 입력되지 않았어요")
        if (b.digestion == null) add("🌙 전날 자기 전 소화 상태가 입력되지 않았어요")
        if (b.postMealExercise == null) add("🏃 전날 식후 운동 여부가 입력되지 않았어요")
    }
    if (missing.isEmpty()) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Warning.copy(alpha = 0.12f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "⚠️ 측정되지 않은 항목 ${missing.size}개",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            Spacer(Modifier.height(8.dp))
            missing.forEach { msg ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text("• ", fontSize = 13.sp, color = Warning, fontWeight = FontWeight.Bold)
                    Text(msg, fontSize = 13.sp, color = TextPrimary, lineHeight = 19.sp)
                }
            }
        }
    }
}
