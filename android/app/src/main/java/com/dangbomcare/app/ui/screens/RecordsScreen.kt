@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.dangbomcare.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dangbomcare.app.data.ServiceLocator
import com.dangbomcare.app.data.api.AIFeedbackToday
import com.dangbomcare.app.data.api.DailySummaryResponse
import com.dangbomcare.app.data.api.ScoreListItem
import com.dangbomcare.app.data.repo.ApiResult
import com.dangbomcare.app.ui.components.AutoSizeSingleLineText
import com.dangbomcare.app.ui.components.LoadingIndicator
import com.dangbomcare.app.ui.theme.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * [260726] 기록 화면의 '오늘' — 백엔드가 KST 기준으로 날짜를 저장/집계하므로 Asia/Seoul 고정.
 *   기기 로컬 타임존이 KST가 아니면(해외/수동 변경) 캘린더의 오늘 표시·선택 날짜가 서버 데이터와 하루 어긋난다.
 *   (WatchSyncActions / MorningInputViewModel / ReportViewModel 과 동일 규칙)
 */
private fun todayKst(): LocalDate = LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))

/** [260726] KST 기준 이번 달. */
private fun thisMonthKst(): YearMonth = YearMonth.from(todayKst())

// ==============================================
// 기록 탭 ViewModel
// ==============================================
class RecordsViewModel : ViewModel() {
    data class UiState(
        val loading: Boolean = true,  // 월 데이터(캘린더) 로딩
        val dayLoading: Boolean = true,  // 선택일 상세 로딩 (stale 방지)
        val yearMonth: YearMonth = thisMonthKst(),  // [260726] KST 기준
        val scoresByDate: Map<LocalDate, Int> = emptyMap(),  // 점수 색칠용
        val glucoseByDate: Map<LocalDate, Int> = emptyMap(),  // 혈당 그래프용 (최근 7일)
        val monthGlucoseByDate: Map<LocalDate, Int> = emptyMap(),  // [260623] 캘린더 셀 표시용 (그 달 공복혈당)
        val selectedDate: LocalDate = todayKst(),  // [260726] KST 기준
        val selectedSummary: DailySummaryResponse? = null,
        val selectedFeedback: AIFeedbackToday? = null,  // [우노] 선택 날짜 AI 피드백
        val error: String? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    // ⭐ 진행 중 일별 조회 Job — 새 날짜 클릭 시 이전 요청 취소 (stale 방지)
    private var dayJob: Job? = null

    // [260726] 마지막으로 계산한 '오늘' — 자정을 넘겼는지 판정용.
    //   ViewModel 은 하단탭 saveState/restoreState 로 앱 세션 내내 살아 있어서 생성 시점의 '오늘'이 그대로 굳는다
    //   (밤 11시 50분에 켜두고 자정을 넘기면 기록 탭이 계속 어제를 선택 상태로 유지).
    private var lastKnownToday: LocalDate = todayKst()

    init {
        loadMonth(thisMonthKst())
        loadDay(todayKst())
        loadRecentGlucose()
    }

    /** 최근 7일 공복혈당 — 추이 그래프용 (당봄 #16 우회: list API가 수치 안 반환해서 개별 get) */
    fun loadRecentGlucose() {
        viewModelScope.launch {
            val today = todayKst()  // [260726] KST 기준
            val dates = (0..6).map { today.minusDays(it.toLong()) }
            val map = mutableMapOf<LocalDate, Int>()
            for (date in dates) {
                try {
                    val res = ServiceLocator.dailyLogRepository.get(date.toString())
                    if (res is ApiResult.Success) {
                        res.data.log?.fastingGlucose?.let { map[date] = it }
                    }
                } catch (e: Exception) { /* 이 날만 스킵 */ }
            }
            _state.value = _state.value.copy(glucoseByDate = map.toMap())
        }
    }

    fun loadMonth(ym: YearMonth) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, yearMonth = ym)
            val from = ym.atDay(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
            val to = ym.atEndOfMonth().format(DateTimeFormatter.ISO_LOCAL_DATE)
            val res = ServiceLocator.scoreRepository.list(from, to)
            // [코덱스 P1] 빠른 월 이동 시 이전 월 응답이 현재 화면(yearMonth)을 덮지 않도록 stale 가드
            if (_state.value.yearMonth != ym) return@launch
            when (res) {
                is ApiResult.Success -> {
                    val map = res.data.mapNotNull { item ->
                        try {
                            // [Codex P2] totalScore null(점수 미산출)을 0으로 강제하면 캘린더에 '0점'(최악)으로 표시됨 → 제외(미입력 'x'로).
                            // [260623] 캘린더 색은 점수 구간만 사용 → 소수 총점을 Int로 변환해 색칠.
                            val score = item.totalScore?.toInt() ?: return@mapNotNull null
                            LocalDate.parse(item.scoreDate) to score
                        } catch (e: Exception) { null }
                    }.toMap()
                    // [260623] 캘린더 셀에 공복혈당 표시 → 같은 응답의 fasting_glucose로 그 달 혈당맵 구성
                    val gmap = res.data.mapNotNull { item ->
                        try {
                            val g = item.fastingGlucose ?: return@mapNotNull null
                            LocalDate.parse(item.scoreDate) to g
                        } catch (e: Exception) { null }
                    }.toMap()
                    _state.value = _state.value.copy(loading = false, scoresByDate = map, monthGlucoseByDate = gmap)
                }
                is ApiResult.Error -> _state.value = _state.value.copy(loading = false, error = res.message)
            }
        }
    }

    /**
     * 특정 날짜 상세 조회.
     * ⭐ Stale 방지:
     *  1) 이전 진행 중인 Job 취소 — 빠른 클릭 시 응답 순서 뒤바뀜 방지
     *  2) selectedSummary 즉시 null + dayLoading=true — 이전 날짜 데이터 깜빡임 차단
     *  3) 응답의 날짜와 요청한 날짜가 다르면 무시 — race condition 방지
     */
    fun loadDay(date: LocalDate) {
        dayJob?.cancel()
        // 즉시 상태 갱신: 이전 요약/피드백 제거 + 로딩 상태 진입
        _state.value = _state.value.copy(
            selectedDate = date,
            selectedSummary = null,
            selectedFeedback = null,
            dayLoading = true
        )
        dayJob = viewModelScope.launch {
            val iso = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
            // 요약 + AI 피드백 병렬 조회 (AI 피드백은 daily-log 응답에서 추출)
            val (res, feedback) = coroutineScope {
                val s = async { ServiceLocator.scoreRepository.dailySummary(iso) }
                val f = async {
                    try {
                        val logRes = ServiceLocator.dailyLogRepository.get(iso)
                        if (logRes is ApiResult.Success) logRes.data.aiFeedback else null
                    } catch (e: Exception) { null }
                }
                s.await() to f.await()
            }
            // ⭐ 도착한 응답이 지금 선택된 날짜에 대한 것인지 재확인
            if (_state.value.selectedDate != date) return@launch
            when (res) {
                is ApiResult.Success -> {
                    // 백엔드 응답의 날짜 검증 (혹시 잘못된 응답이면 무시)
                    val respDate = try { LocalDate.parse(res.data.date) } catch (e: Exception) { null }
                    if (respDate != null && respDate != date) {
                        _state.value = _state.value.copy(selectedSummary = null, selectedFeedback = null, dayLoading = false)
                    } else {
                        _state.value = _state.value.copy(selectedSummary = res.data, selectedFeedback = feedback, dayLoading = false)
                    }
                }
                is ApiResult.Error -> {
                    _state.value = _state.value.copy(selectedSummary = null, selectedFeedback = feedback, dayLoading = false)
                }
            }
        }
    }

    /**
     * [우노] 화면 복귀(과거 입력 후 등) 시 캘린더 + 상세 + 혈당 새로고침.
     * [260726] 자정 넘김 대응: 재진입 때 '오늘'을 다시 계산해, 날짜가 바뀌었으면 선택 날짜/표시 달을 새 오늘로 옮긴다.
     *   ⚠️ 사용자가 일부러 과거 날짜를 보고 있던 경우는 덮어쓰지 않는다
     *      → 직전 계산 시점의 '오늘'을 보고 있던 경우에만 이동(달도 같은 기준으로 따로 판단).
     */
    fun refresh() {
        val today = todayKst()
        val s = _state.value
        if (today != lastKnownToday) {
            val prevToday = lastKnownToday
            lastKnownToday = today
            val targetDate = if (s.selectedDate == prevToday) today else s.selectedDate
            val targetMonth = if (s.yearMonth == YearMonth.from(prevToday)) YearMonth.from(today) else s.yearMonth
            loadMonth(targetMonth)
            loadDay(targetDate)
            loadRecentGlucose()
            return
        }
        loadMonth(s.yearMonth)
        loadDay(s.selectedDate)
        loadRecentGlucose()
    }

    fun prevMonth() = loadMonth(_state.value.yearMonth.minusMonths(1))
    fun nextMonth() {
        val next = _state.value.yearMonth.plusMonths(1)
        if (!next.isAfter(thisMonthKst())) loadMonth(next)  // [260726] KST 기준 이번 달까지만
    }
}

// ==============================================
// 기록 탭 화면 (11 / 14)
// ==============================================
@Composable
fun RecordsScreen(
    onGotoScoreDetail: (String) -> Unit = {},
    onGotoActivity: (String) -> Unit = {},  // ⭐ 선택 날짜 전달
    onGotoSleep: (String) -> Unit = {},
    onGotoStress: (String) -> Unit = {},
    onGotoGlucose: (String) -> Unit = {},  // 공복혈당 → 그 날짜가 속한 주간 그래프 (당봄 #16)
    onGotoPastInput: (String) -> Unit = {},  // [우노 3] 선택 날짜 공복혈당/회고 입력·수정
    scrollToTopTrigger: Int = 0,
    vm: RecordsViewModel = viewModel()
) {
    val state by vm.state.collectAsState()
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    LaunchedEffect(scrollToTopTrigger) {
        if (scrollToTopTrigger > 0) scrollState.animateScrollTo(0)
    }

    // [우노] 기록 탭에 올 때마다(ON_RESUME) 캘린더/상세/AI 피드백 새로고침 → 홈에서 저장한 최신 내용과 일치.
    //   ⚠️ 이전엔 firstResume 으로 첫 ON_RESUME 을 건너뛰었는데, 탭 전환으로 컴포저블이 재생성되면
    //      firstResume 이 true 로 리셋돼 매번 새로고침을 건너뛰어 → 기록 화면이 옛 피드백을 보여주던 버그.
    //   init 의 최초 로드와 한 번 겹쳐도 무해(loadDay 가 이전 Job 취소)하므로 항상 새로고침한다.
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) vm.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // [v1.0 피드백] 캘린더 날짜 클릭 시 하단 detail 카드로 부드럽게 스크롤.
    //  BringIntoViewRequester는 첫 렌더에서 가끔 실패 → onGloballyPositioned로 Y 좌표 캡처 +
    //  scrollState.animateScrollTo()로 직접 이동하는 방식이 더 안정적.
    var detailCardYpx by remember { mutableStateOf(0) }
    var dateClickTrigger by remember { mutableStateOf(0) }
    LaunchedEffect(dateClickTrigger, state.dayLoading) {
        if (dateClickTrigger > 0 && !state.dayLoading) {
            // 데이터 로딩 완료 후 한 프레임 더 기다림 (Compose 측정 완료 보장)
            delay(80)
            // [우노] 날짜 헤더("M월 d일 (화)")가 상단에 보이도록 — 헤더 Y에서 살짝 위로
            val target = (detailCardYpx - 12).coerceAtLeast(0)
            scrollState.animateScrollTo(target)
        }
    }

    Scaffold(
        containerColor = BgSecondary,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("기록", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgSecondary)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(scrollState).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)  // [우노] 박스 간격 좁힘 (16→10)
        ) {
            Spacer(Modifier.height(8.dp))

            // 사용자 피드백: 상단 "최근 7일 공복혈당" 박스 제거.
            // 공복혈당 상세는 공복혈당 카드 클릭(onGlucoseClick) 시 DetailGlucoseScreen에서 표시됨.

            // 월 헤더
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = vm::prevMonth) {
                    Icon(Icons.Filled.ChevronLeft, "이전 달")
                }
                Text(
                    state.yearMonth.format(DateTimeFormatter.ofPattern("yyyy년 M월", Locale.KOREAN)),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
                IconButton(onClick = vm::nextMonth) {
                    Icon(Icons.Filled.ChevronRight, "다음 달")
                }
            }

            // [사용자 피드백] 데모/예시 데이터 모두 제거 — 실제 흐름 테스트 방해.
            //   캘린더는 실제 점수만 표시. 신규 user는 빈 캘린더.
            val isDemo = false
            val effectiveScores = if (state.loading) emptyMap() else state.scoresByDate
            val effectiveGlucose = if (state.loading) emptyMap() else state.monthGlucoseByDate

            CalendarGrid(
                yearMonth = state.yearMonth,
                scoresByDate = effectiveScores,
                glucoseByDate = effectiveGlucose,
                selectedDate = state.selectedDate,
                onDateClick = { date ->
                    vm.loadDay(date)
                    // [v1.0 피드백] 클릭 시 하단 detail 카드로 스크롤 트리거
                    dateClickTrigger++
                },
                isDemo = isDemo
            )

            if (isDemo) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = LimeLight.copy(alpha = 0.4f))
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("ℹ️", fontSize = 16.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "캘린더는 예시 데이터입니다. 실제로 사용하시면 입력하신 점수가 표시돼요.",
                            fontSize = 12.sp,
                            color = PrimaryDark,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // 선택된 날짜 상세 — 로딩 중에는 스피너 표시 (stale 데이터 깜빡임 방지)
            val summary = state.selectedSummary
            // [v1.0 피드백] summary가 있으면 무조건 카드 영역 노출. 각 카드 내부에서
            //   데이터 없음(점수/혈당/워치 등)은 sync 안내 또는 placeholder로 처리.
            val hasAnyData = summary != null
            // [v1.0 피드백] detail 카드 시작 marker — Y 좌표 캡처해서 animateScrollTo로 이동
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .onGloballyPositioned { coords ->
                        // positionInRoot의 y는 Scaffold 기준 — scrollState.value를 더해서 컨테이너 절대 위치 계산
                        detailCardYpx = (coords.positionInRoot().y + scrollState.value).toInt()
                    }
            )
            if (state.dayLoading) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = BgCard),
                    border = BorderStroke(1.dp, Divider)
                ) {
                    Column(
                        modifier = Modifier.padding(32.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            state.selectedDate.format(
                                DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREAN)
                            ),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Spacer(Modifier.height(12.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            color = Primary,
                            strokeWidth = 2.5.dp
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("불러오는 중...", fontSize = 12.sp, color = TextSecondary)
                    }
                }
            } else if (hasAnyData) {
                val dateStr = state.selectedDate.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
                // [260623 피드백] 걸음/수면/스트레스는 '전날' 데이터 → 상세화면도 전날(선택일-1)로 진입
                val prevStr = state.selectedDate.minusDays(1).format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
                SelectedDateCards(
                    date = state.selectedDate,
                    summary = summary!!,
                    feedback = state.selectedFeedback,
                    onScoreClick = { onGotoScoreDetail(dateStr) },
                    onActivityClick = { onGotoActivity(prevStr) },
                    onSleepClick = { onGotoSleep(prevStr) },
                    onStressClick = { onGotoStress(prevStr) },
                    onGlucoseClick = { onGotoGlucose(dateStr) },  // 공복혈당 → 그 날짜가 속한 주간 그래프 (당봄 #16)
                    onSyncSuccess = { vm.loadDay(state.selectedDate) },  // 동기화 후 자동 갱신
                    // [우노] 날짜 헤더 Y를 직접 캡처 → 이 위치로 스크롤
                    headerModifier = Modifier.onGloballyPositioned { coords ->
                        detailCardYpx = (coords.positionInRoot().y + scrollState.value).toInt()
                    }
                )
            } else {
                // 데이터 없음 안내 (실제 데이터 vs 데모 명확히 구분)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = BgCard),
                    border = BorderStroke(1.dp, Divider)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            state.selectedDate.format(
                                DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREAN)
                            ),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("📭", fontSize = 36.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "이 날은 기록이 없어요",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (state.selectedDate == todayKst()) {  // [260726] KST 기준
                                "오늘 공복혈당을 입력하면 점수가 기록돼요"
                            } else {
                                "이 날은 공복혈당 입력 / 삼성헬스 동기화\n둘 다 안 된 날이에요"
                            },
                            fontSize = 12.sp,
                            color = TextSecondary,
                            textAlign = TextAlign.Center,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
            // [우노 3] 선택 날짜 공복혈당/회고 입력·수정 버튼 (데이터 유무와 무관하게 노출)
            if (!state.dayLoading) {
                Spacer(Modifier.height(12.dp))
                val pastDateStr = state.selectedDate.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
                androidx.compose.material3.OutlinedButton(
                    onClick = { onGotoPastInput(pastDateStr) },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        if (hasAnyData) "📝 이 날 내용 입력 / 수정하기" else "📝 이 날 내용 입력하기",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.height(12.dp))  // 하단 네비는 상위 Scaffold padding에서 이미 반영
        }
    }
}

@Composable
private fun CalendarGrid(
    yearMonth: YearMonth,
    scoresByDate: Map<LocalDate, Int>,
    glucoseByDate: Map<LocalDate, Int> = emptyMap(),
    selectedDate: LocalDate,
    onDateClick: (LocalDate) -> Unit,
    isDemo: Boolean = false
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        border = BorderStroke(1.dp, Divider)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // [260623 피드백] 색 의미 범례 + "숫자=공복혈당" 안내 (상단)
            CalendarLegend()
            Spacer(Modifier.height(10.dp))
            // 요일 헤더
            Row(modifier = Modifier.fillMaxWidth()) {
                listOf("일", "월", "화", "수", "목", "금", "토").forEachIndexed { i, d ->
                    Text(
                        d,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (i == 0) ErrorRed else if (i == 6) Info else TextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f).padding(vertical = 8.dp)
                    )
                }
            }
            HorizontalDivider(color = Divider)
            Spacer(Modifier.height(8.dp))

            // 날짜 그리드
            val firstDay = yearMonth.atDay(1)
            val firstDow = firstDay.dayOfWeek.value % 7  // 일요일=0
            val daysInMonth = yearMonth.lengthOfMonth()
            val today = todayKst()  // [260726] KST 기준

            var dayIdx = 1
            for (week in 0..5) {
                if (dayIdx > daysInMonth) break
                Row(modifier = Modifier.fillMaxWidth()) {
                    for (dow in 0..6) {
                        if (week == 0 && dow < firstDow) {
                            Spacer(modifier = Modifier.weight(1f).heightIn(min = 52.dp))
                        } else if (dayIdx <= daysInMonth) {
                            val date = yearMonth.atDay(dayIdx)
                            val score = scoresByDate[date]
                            val glucose = glucoseByDate[date]
                            val isSelected = date == selectedDate
                            val isToday = date == today
                            val isFuture = date.isAfter(today)
                            CalendarCell(
                                day = dayIdx,
                                score = score,
                                glucose = glucose,
                                isSelected = isSelected,
                                isToday = isToday,
                                isFuture = isFuture,
                                onClick = { if (!isFuture) onDateClick(date) },
                                modifier = Modifier.weight(1f),
                                isDemo = isDemo  // ⭐ P2: 데모 셀 시각 구분
                            )
                            dayIdx++
                        } else {
                            Spacer(modifier = Modifier.weight(1f).heightIn(min = 52.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarCell(
    day: Int,
    score: Int?,
    glucose: Int? = null,   // [260623] 셀에 표시할 공복혈당 수치 (색은 score 기반 유지)
    isSelected: Boolean,
    isToday: Boolean,
    isFuture: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isDemo: Boolean = false  // ⭐ P2: 데모 데이터 시각 구분 (alpha 낮춤 + 사선 점선 테두리)
) {
    // [260705 피드백] 선택일을 초록 '배경'으로 덮으면 점수 색이 안 보임 → 배경은 점수색 유지 + 굵은 초록 '테두리'로 표시
    // [피드백 260714] 파스텔 톤으로 변경 (좋음 하늘색·보통 회색·나쁨 빨강). LimeLight/ScoreOk/ScoreLow → Calendar*
    val baseBgColor = when {
        score != null && score >= 70 -> CalendarGood
        score != null && score >= 50 -> CalendarMid
        score != null -> CalendarBad
        else -> Color.Transparent
    }
    // 데모는 색을 더 옅게
    val bgColor = if (isDemo) baseBgColor.copy(alpha = 0.4f) else baseBgColor
    val borderColor = when {
        isSelected -> Primary
        isToday -> Primary.copy(alpha = 0.5f)
        else -> Divider
    }
    val borderWidth = when {
        isSelected -> 2.5.dp   // 선택 = 굵은 초록 테두리
        isToday -> 1.dp
        else -> 0.5.dp  // 얇은 grid line
    }
    val textColor = when {
        isFuture -> TextDisabled
        isDemo -> TextSecondary  // 데모 텍스트는 옅게
        else -> TextPrimary
    }

    Column(
        modifier = modifier
            // [반응형 최적화] 고정 height → heightIn(min): 시니어 폰트 확대(1.15~1.4x) 시
            //   날짜+마커가 잘리지 않게 셀이 내용에 맞춰 늘어남. (잘림 방지)
            .heightIn(min = 52.dp)
            .padding(1.dp)  // 2 → 1 (grid line 잘 보이게)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .border(borderWidth, borderColor, RoundedCornerShape(8.dp))
            .clickable(enabled = !isFuture) { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // [피드백 260714 → 260719] 셀 폰트 — 날짜/혈당 크기 격차 축소.
        //   이전(11sp/15sp Bold)은 혈당이 너무 크게 튀어 사용자 지적.
        //   리포트 공복혈당 달력과 통일감 있게 12sp/12sp SemiBold로 조정.
        Text(
            "$day",
            fontSize = 12.sp,
            fontWeight = if (isSelected || isToday) FontWeight.Bold else FontWeight.Normal,
            color = textColor.copy(alpha = 0.7f)
        )
        when {
            isDemo && (glucose != null || score != null) -> {
                Text("예시", fontSize = 9.sp, color = textColor.copy(alpha = 0.6f), fontWeight = FontWeight.Bold)
            }
            glucose != null -> {
                // [폰트 스케일 대응] 시니어 확대(1.3x+) 시 3자리 혈당이 셀 폭에 잘리는 문제 방지 —
                //   AutoSizeSingleLineText로 오버플로우 감지 시 폰트 자동 축소(12→8sp).
                AutoSizeSingleLineText(
                    "$glucose",
                    maxFontSize = 12.sp,
                    minFontSize = 8.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = textColor,
                )
            }
            score != null -> Unit
            !isFuture -> {
                Text("x", fontSize = 10.sp, color = TextDisabled, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// [260623 피드백] 캘린더 색(점수) 의미 범례 + "숫자=공복혈당" 안내
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CalendarLegend() {
    Column {
        // [260705 R3] 항목이 폭에 안 들어가면 '항목 통째로' 다음 줄로 (글자 중간 깨짐 원천 방지 — FlowRow)
        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            LegendItem(CalendarGood, "70점 이상: 좋음")
            LegendItem(CalendarMid, "50~69점: 보통")
            LegendItem(CalendarBad, "50점 미만: 나쁨")
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "숫자는 공복혈당(mg/dL), 색은 생활관리 점수예요",
            fontSize = 12.sp,
            color = TextSecondary
        )
    }
}

@Composable
private fun LegendItem(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color)
        )
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 12.sp, color = TextSecondary, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun SelectedDateCards(
    onGlucoseClick: () -> Unit = {},
    date: LocalDate,
    summary: DailySummaryResponse,
    onScoreClick: () -> Unit = {},
    onActivityClick: () -> Unit = {},
    onSleepClick: () -> Unit = {},
    onStressClick: () -> Unit = {},
    onSyncSuccess: () -> Unit = {},
    feedback: AIFeedbackToday? = null,
    headerModifier: Modifier = Modifier
) {
    // [260705 피드백] 오늘 선택 = '전날' 표현 / 과거 선택 = '전날' 대신 실제 날짜 표기 (혼동 방지)
    val isTodaySelected = date == todayKst()  // [260726] KST 기준
    val prevDayLabel = if (isTodaySelected) "전날"
        else date.minusDays(1).format(DateTimeFormatter.ofPattern("M월 d일", Locale.KOREAN))
    Text(
        date.format(DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREAN)),
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        color = TextPrimary,
        modifier = Modifier.padding(top = 8.dp).then(headerModifier)
    )
    Text(
        // [260623 피드백] 공복혈당은 전날 생활의 결과 → 아래 데이터가 그 '전날' 것임을 2줄로 안내
        if (isTodaySelected) "공복혈당에 영향을 준 전날의 데이터입니다.\n각 카드를 누르면 상세 데이터를 볼 수 있어요."
        else "공복혈당에 영향을 준 날의 데이터입니다.\n각 카드를 누르면 상세 데이터를 볼 수 있어요.",
        fontSize = 13.sp,
        color = TextSecondary,
        lineHeight = 18.sp
    )

    // 점수 — 클릭 가능
    if (summary.score != null) {
        DataCard(
            emoji = "🎯",
            title = "점수",
            value = "${summary.score.total ?: "-"}점",
            subtitle = summary.score.label ?: "",
            onClick = onScoreClick
        )
    } else {
        MissingDataCard(emoji = "🎯", title = "점수", message = "점수 미산출")
    }

    // 혈당 — 클릭 시 주간/월간 그래프 (당봄 #16)
    if (summary.glucose != null) {
        DataCard(
            emoji = "🩸",
            title = "공복혈당",
            value = "${summary.glucose.value ?: "-"} mg/dL",
            subtitle = (summary.glucose.status ?: "") + " · 탭하여 주간/월간 추이 보기",
            onClick = onGlucoseClick
        )
    } else {
        DataCard(
            emoji = "🩸",
            title = "공복혈당",
            value = "이 날 입력 안 함",
            subtitle = "탭하여 주간/월간 추이 보기",
            onClick = onGlucoseClick
        )
    }

    // [260623 피드백] 걸음·수면·스트레스는 '전날(date-1)' 데이터 — 전날 생활이 그날 공복혈당·점수를 만들기 때문.
    //   백엔드 daily-summary.activity 도 watch_date=summary_date-1 로 전날 값을 반환한다.
    //   데이터 없어도 3카드 항상 표시 + 카드 안에 이유 안내 ("-" 금지 → "측정 없음 (워치 미착용)" / 걸음은 "0보").
    run {
        val a = summary.activity  // nullable — 전날 워치 데이터(백엔드, 없을 수 있음)
        val dataDate = date.minusDays(1)  // 표시 기준일 = 선택일의 전날

        // 백엔드에 전날 워치 데이터가 없으면 백그라운드로 HC에서 가져와 채움 (성공 시 자동 새로고침)
        WatchDataAutoSync(date = dataDate, hasBackendData = a != null, onSynced = onSyncSuccess)

        // 걸음 — 전날 값(백엔드). 없으면 HC에서 전날 걸음 보충. 데이터 없으면 0보(폰은 워치 없이도 측정).
        var hcSteps by remember(dataDate) { mutableStateOf<Int?>(null) }
        LaunchedEffect(dataDate) {
            try {
                val repo = com.dangbomcare.app.data.ServiceLocator.healthConnectRepository
                if (repo.isSdkAvailable()) hcSteps = repo.fetchWatchData(dataDate).steps
            } catch (_: Exception) { /* keep null */ }
        }
        val stepsValue = a?.steps ?: hcSteps
        DataCard(
            emoji = "🚶",
            title = "$prevDayLabel 걸음 활동",
            value = "${stepsValue?.let { "%,d".format(it) } ?: "0"}보",
            subtitle = if (stepsValue == null) "아직 걸음 데이터가 없어요 (폰으로도 측정되니 곧 표시돼요)"
                       else "탭하여 시간대별·일별 추이 보기",
            onClick = onActivityClick
        )

        // 수면 — 전날 수면. 없으면 "측정 없음 (워치 미착용)" — 절대 "-" 쓰지 않음.
        val sleepText = a?.sleepHours?.let { h ->
            val totalMin = kotlin.math.round(h * 60).toInt()
            "${totalMin / 60}시간 ${totalMin % 60}분"
        }
        DataCard(
            emoji = "😴",
            title = "$prevDayLabel 수면",
            value = sleepText ?: "측정 없음 (워치 미착용)",
            subtitle = if (sleepText == null) "워치를 차고 주무시면 수면이 기록돼요"
                       else "탭하여 수면 단계·시간선 보기",
            onClick = onSleepClick
        )

        // 스트레스 — 전날(dataDate) 스트레스 + 전날 평균 심박. 둘 다 없으면 "측정 없음".
        var stressBpm by remember(dataDate) { mutableStateOf<Int?>(null) }
        var stressHrChecked by remember(dataDate) { mutableStateOf(false) }
        LaunchedEffect(dataDate) {
            try {
                val repo = com.dangbomcare.app.data.ServiceLocator.healthConnectRepository
                if (repo.isSdkAvailable()) {
                    val hourly = repo.fetchHeartRateHourly(dataDate)
                    val nonZero = hourly.filterNotNull().filter { it > 0 }
                    stressBpm = if (nonZero.isNotEmpty()) nonZero.average().toInt() else null
                }
            } catch (_: Exception) { /* keep null */ }
            stressHrChecked = true
        }
        val stressStatus = a?.stress?.takeIf { it.isNotBlank() }
        val stressLabel = when {
            stressStatus != null && stressBpm != null -> "$stressStatus · 평균 ${stressBpm}bpm"
            stressStatus != null -> stressStatus
            stressBpm != null -> "평균 ${stressBpm}bpm"
            !stressHrChecked -> "확인 중..."
            else -> "측정 없음 (워치 미착용)"
        }
        val stressMeasured = stressStatus != null || stressBpm != null
        DataCard(
            emoji = "😌",
            title = "$prevDayLabel 스트레스",
            value = stressLabel,
            subtitle = if (!stressMeasured && stressHrChecked) "워치를 차고 계시면 스트레스가 측정돼요"
                       else "탭하여 시간대별 심박·라인 보기",
            onClick = onStressClick
        )
    }

    // 회고 (음주/저녁/식후 금식 + 신규 3항목: 저녁유형/자기 전 소화/식후 운동) — 사용자 친화적 안내
    // [피드백 260719] 260623 신규 3항목이 기록 화면 회고 카드에서 빠져 있어 추가.
    // [260722] reflection null이면 카드 통째 소멸 → "회고 기록 없음" 안내 카드로 fallback (사용자 지적).
    if (summary.reflection == null) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = BgCard),
            border = BorderStroke(1.dp, Divider)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("📝", fontSize = 20.sp)
                    Spacer(Modifier.width(8.dp))
                    Text("$prevDayLabel 회고", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "이 날의 회고 기록이 없어요.",
                    fontSize = 13.sp, color = TextSecondary
                )
            }
        }
    } else if (summary.reflection != null) {
        val r = summary.reflection
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = BgCard),
            border = BorderStroke(1.dp, Divider)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("📝", fontSize = 20.sp)
                    Spacer(Modifier.width(8.dp))
                    Text("$prevDayLabel 회고", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                }
                Spacer(Modifier.height(12.dp))

                // [피드백 260720] 6항목 모두 통일된 (라벨 왼쪽 · 상태 오른쪽) 스타일로.
                //   null 필드도 "기록 없음" 배지로 항상 렌더링 → 회고에 몇 개 항목이 있는지 명확히 보임.
                val dinnerLabel = com.dangbomcare.app.notification.AlarmScheduler.dinnerDeadlineLabel(
                    androidx.compose.ui.platform.LocalContext.current
                )

                ReflectionRow(
                    icon = "🍷",
                    label = "음주",
                    detail = when (r.alcohol) {
                        false -> "술을 드시지 않았어요"
                        true -> "술을 드셨어요"
                        null -> null
                    },
                    status = when (r.alcohol) {
                        false -> ReflectionStatus.GOOD
                        true -> ReflectionStatus.BAD
                        null -> ReflectionStatus.MISSING
                    }
                )
                Spacer(Modifier.height(8.dp))

                ReflectionRow(
                    icon = "🍚",
                    label = "저녁 마감",
                    detail = when (r.dinnerBefore7pm) {
                        true -> "$dinnerLabel 전에 마치셨어요"
                        false -> "$dinnerLabel 이후에 드셨어요"
                        null -> null
                    },
                    status = when (r.dinnerBefore7pm) {
                        true -> ReflectionStatus.GOOD
                        false -> ReflectionStatus.BAD
                        null -> ReflectionStatus.MISSING
                    }
                )
                Spacer(Modifier.height(8.dp))

                ReflectionRow(
                    icon = "🌙",
                    label = "식후 금식",
                    detail = when (r.noFoodAfterDinner) {
                        true -> "저녁 식사 후 금식을 지키셨어요"
                        false -> "저녁 식사 후 추가로 음식을 드셨어요"
                        null -> null
                    },
                    status = when (r.noFoodAfterDinner) {
                        true -> ReflectionStatus.GOOD
                        false -> ReflectionStatus.BAD
                        null -> ReflectionStatus.MISSING
                    }
                )

                // [피드백 260719/260720] 신규 3항목 — null이어도 "기록 없음" 배지로 항상 표시.
                Spacer(Modifier.height(8.dp))
                // [v1.1.3] "flour" 삭제 + "other"(기타, 자유 입력) 신설.
                //   dinnerTypeOther 있으면 "기타(파스타)" 형식으로 렌더.
                val dinnerOtherText = r.dinnerTypeOther?.takeIf { it.isNotBlank() }
                val dinnerTypeLabel = r.dinnerType?.takeIf { it.isNotEmpty() }?.joinToString(", ") {
                    when (it) {
                        "home_korean" -> "집밥/한식"
                        "eating_out" -> "외식/배달"
                        "meat" -> "육류"
                        "light" -> "간단식"
                        "other" -> dinnerOtherText?.let { t -> "기타($t)" } ?: "기타"
                        "flour" -> "밀가루(폐지)" // legacy — 신규 선택 불가, 기존 데이터 렌더링용
                        else -> it
                    }
                }
                val dinnerTypeStatus = when {
                    r.dinnerType.isNullOrEmpty() -> ReflectionStatus.MISSING
                    r.dinnerType.all { it in listOf("home_korean", "light") } -> ReflectionStatus.GOOD
                    else -> ReflectionStatus.BAD
                }
                ReflectionRow(
                    icon = "🍽",
                    label = "저녁 유형",
                    detail = dinnerTypeLabel,
                    status = dinnerTypeStatus
                )
                Spacer(Modifier.height(8.dp))

                val digestionDetail = when (r.digestion) {
                    "hungry" -> "배고픔 (공복 취침)"
                    "partial" -> "소화가 조금 됨"
                    "full" -> "여전히 배부름"
                    null -> null
                    else -> r.digestion
                }
                val digestionStatus = when (r.digestion) {
                    "hungry", "partial" -> ReflectionStatus.GOOD  // Codex P3: partial도 GOOD
                    "full" -> ReflectionStatus.BAD
                    else -> ReflectionStatus.MISSING
                }
                ReflectionRow(
                    icon = "😴",
                    label = "자기 전 소화",
                    detail = digestionDetail,
                    status = digestionStatus
                )
                Spacer(Modifier.height(8.dp))

                val exerciseDetail = when (r.postMealExercise) {
                    "60plus" -> "1시간 이상"
                    "30to60" -> "30~60분"
                    "10to30" -> "10~30분"
                    "none" -> "안 함"
                    null -> null
                    else -> r.postMealExercise
                }
                val exerciseStatus = when (r.postMealExercise) {
                    "30to60", "60plus" -> ReflectionStatus.GOOD
                    "10to30" -> ReflectionStatus.GOOD  // 10~30분도 있음이 좋음
                    "none" -> ReflectionStatus.BAD
                    else -> ReflectionStatus.MISSING
                }
                ReflectionRow(
                    icon = "🚶",
                    label = "식후 운동",
                    detail = exerciseDetail,
                    status = exerciseStatus
                )
            }
        }
    }

    // [우노] AI 피드백 (그 날짜) — 7일 이내 입력이면 메시지/미션 표시. 7일 초과 과거는 메시지 비어있어 숨김.
    feedback?.takeIf { !it.dailyMessage.isNullOrBlank() }?.let { fb ->
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = LimeLight.copy(alpha = 0.3f)),
            border = BorderStroke(1.dp, Primary.copy(alpha = 0.25f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🤖", fontSize = 18.sp)
                    Spacer(Modifier.width(8.dp))
                    Text("AI 피드백", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = PrimaryDark)
                }
                Spacer(Modifier.height(8.dp))
                Text(fb.dailyMessage ?: "", fontSize = 13.sp, color = TextPrimary, lineHeight = 19.sp)
                fb.mission?.text?.takeIf { it.isNotBlank() }?.let { m ->
                    Spacer(Modifier.height(10.dp))
                    Surface(color = BgCard, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
                            Text("🎯", fontSize = 15.sp)
                            Spacer(Modifier.width(8.dp))
                            Text(m, fontSize = 12.sp, color = TextPrimary, lineHeight = 17.sp)
                        }
                    }
                }
            }
        }
    }
}

/** 회고 항목 상태. */
private enum class ReflectionStatus { GOOD, BAD, MISSING }

/**
 * [피드백 260720] 회고 항목 — 통일된 스타일.
 *   좌측: 아이콘 + 짧은 라벨 (+ 선택적 세부 문구)
 *   우측: 상태 배지 (✓ / × / 기록 없음)
 *   MISSING일 때만 회색 배경. GOOD은 연라임, BAD는 연빨강 배경.
 */
@Composable
private fun ReflectionRow(
    icon: String,
    label: String,
    detail: String? = null,
    status: ReflectionStatus,
) {
    val (bg, mainColor) = when (status) {
        ReflectionStatus.GOOD -> LimeLight.copy(alpha = 0.4f) to PrimaryDark
        ReflectionStatus.BAD -> ErrorRed.copy(alpha = 0.08f) to ErrorRed
        ReflectionStatus.MISSING -> BgCardSoft to TextDisabled
    }
    val badge = when (status) {
        ReflectionStatus.GOOD -> "✓"
        ReflectionStatus.BAD -> "×"
        ReflectionStatus.MISSING -> "기록 없음"
    }
    Surface(color = bg, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(icon, fontSize = 18.sp)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(label, fontSize = 13.sp, color = mainColor, fontWeight = FontWeight.SemiBold)
                detail?.let {
                    Text(it, fontSize = 11.sp, color = mainColor.copy(alpha = 0.75f), lineHeight = 15.sp)
                }
            }
            // 상태 배지: ✓/× 는 크게, "기록 없음"은 작게
            if (status == ReflectionStatus.MISSING) {
                Surface(
                    color = TextDisabled.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(badge, fontSize = 11.sp, color = TextDisabled,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
                }
            } else {
                Text(badge, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = mainColor)
            }
        }
    }
}

// [우노 8] 이번 세션에 자동 워치 동기화 POST가 성공한 날짜 — 성공 후에도 활동 데이터가 없을 때
//   화면 새로고침 ↔ 재동기화 무한 루프 방지용 (화면 재구성과 무관하게 유지).
private val autoSyncedDates = java.util.Collections.synchronizedSet(mutableSetOf<LocalDate>())

/**
 * [우노] 화면에 안 보이는 백그라운드 워치 동기화.
 *   백엔드에 그날 워치 데이터가 없으면 Health Connect에서 '그 날짜' 데이터를 가져와 서버에 올리고
 *   성공 시 onSynced()로 화면을 새로고침한다. (기록 카드는 항상 표시되므로 별도 안내 UI 없음.)
 */
@Composable
private fun WatchDataAutoSync(date: LocalDate, hasBackendData: Boolean, onSynced: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // [260726] remember 제거 — 앱을 켜둔 채 자정을 넘기면 '오늘'이 어제로 굳어 미래 날짜 판정이 어긋났다. KST 기준으로 매 재구성 시 계산.
    val today = todayKst()
    // 백엔드에 이미 데이터 있거나 / 미래 날짜 / 이미 시도한 날짜면 스킵 (무한 루프 방지)
    val shouldSync = !hasBackendData && !date.isAfter(today) && date !in autoSyncedDates
    LaunchedEffect(date, shouldSync) {
        if (!shouldSync) return@LaunchedEffect
        try {
            val repo = com.dangbomcare.app.data.health.HealthConnectRepository(context)
            if (repo.canReadWatchData()) {
                val data = repo.fetchWatchData(date)
                // [코덱스 P0] 무측정 시 steps=null(0 아님) → all-null로 판정해 빈 row 전송 방지(점수 오염 방지)
                val hasNoData = data.steps == null && data.sleepTotalMinutes == null &&
                    data.avgHeartRate == null && data.spo2Avg == null
                if (!hasNoData) {
                    val res = com.dangbomcare.app.data.ServiceLocator.watchDataRepository.post(data)
                    if (res is com.dangbomcare.app.data.repo.ApiResult.Success) {
                        autoSyncedDates.add(date)  // 재동기화 방지
                        onSynced()                 // 화면 자동 갱신
                    }
                }
            }
        } catch (_: Exception) { /* 무시 — 카드는 '측정 없음'으로 표시됨 */ }
    }
}

/**
 * [우노 8 · 미사용] 워치 데이터 없을 때 — '동기화 시도' 버튼/큰 안내 박스 전부 제거.
 *   화면 진입 즉시 자동으로 동기화하고, 성공하면 화면을 새로고침해 데이터를 바로 보여줌.
 *   자동 동기화까지 했는데도 데이터가 진짜 없으면(예: HC에 안 남은 오래된 날짜) 작은 회색 문구만.
 *
 * ⭐ Codex P0-5: summaryDate (화면 표시일) vs watchDate (실제 워치 데이터일) 분리
 * 규칙: "오늘 점수 = 어제 워치 데이터" — summaryDate=5/19 화면이면 watchDate=5/18 동기화
 */
@Composable
private fun SyncMissingCard(date: LocalDate, onSynced: () -> Unit = {}) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val today = todayKst()  // [260726] KST 기준
    val isFuture = date.isAfter(today)
    // [우노] 기록 화면은 '그 날 본인'의 워치 데이터를 표시 → 동기화도 이 날짜(date) 것을 가져온다.
    val watchDate = date

    val alreadySynced = date in autoSyncedDates
    var syncing by remember(date) { mutableStateOf(!isFuture && !alreadySynced) }
    var noData by remember(date) { mutableStateOf(isFuture || alreadySynced) }

    // 진입 즉시 자동 동기화 (버튼 없음). 성공 → 화면 갱신, 실패/데이터 없음 → 작은 회색 문구만.
    if (!isFuture && !alreadySynced) {
        LaunchedEffect(date) {
            syncing = true
            noData = false
            try {
                val repo = com.dangbomcare.app.data.health.HealthConnectRepository(context)
                when {
                    !repo.canReadWatchData() -> noData = true   // SDK 활성 OR HC 권한 — 둘 다 없을 때만 데이터 없음
                    else -> {
                        // [우노] 이 날짜(date) 본인의 워치 데이터를 가져와 동기화
                        val data = repo.fetchWatchData(watchDate)
                        // [코덱스 P0] fetchWatchData는 무측정 시 steps=null 반환(0 아님) → steps==0 체크는 빈 데이터를 못 거름.
                        //   WatchSyncWorker와 동일하게 all-null로 판정해야 서버에 빈 0보 row가 안 생김(점수 오염 방지).
                        val hasNoData = data.steps == null && data.sleepTotalMinutes == null &&
                            data.avgHeartRate == null && data.spo2Avg == null
                        if (hasNoData) {
                            noData = true  // HC에도 데이터 없음 → 빈 값 전송 안 함
                        } else {
                            val res = com.dangbomcare.app.data.ServiceLocator.watchDataRepository.post(data)
                            if (res is com.dangbomcare.app.data.repo.ApiResult.Success) {
                                autoSyncedDates.add(date)  // 재동기화 방지
                                onSynced()                 // 화면 자동 갱신 → 데이터 표시
                            } else {
                                noData = true
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                noData = true
            }
            syncing = false
        }
    }

    when {
        isFuture -> { /* 미래 날짜 — 아무 것도 표시 안 함 */ }
        syncing -> Text(
            "⌚ 워치 데이터 불러오는 중…",
            fontSize = 12.sp,
            color = TextSecondary,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        )
        noData -> Text(
            "이 날은 워치 데이터가 없어요",
            fontSize = 12.sp,
            color = TextDisabled,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        )
    }
}

@Composable
private fun MissingDataCard(emoji: String, title: String, message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = BgCardSoft),
        border = BorderStroke(1.dp, Divider)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(emoji, fontSize = 24.sp, color = TextDisabled)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 12.sp, color = TextDisabled)
                Text(message, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextSecondary)
            }
        }
    }
}

/**
 * 데모 데이터 — 사용자가 빈 날짜를 봐도 어떻게 표시되는지 알 수 있도록.
 * 날짜에 따라 결정적으로 변화하는 데이터 (랜덤이 아닌 일관된 값).
 */
@Composable
private fun DemoRecordCards(date: LocalDate) {
    // 날짜 기반 결정적 값 (랜덤 같지만 일관됨)
    val seed = date.dayOfMonth
    val score = 70 + (seed * 3 % 25)         // 70~94 사이
    val scoreLabel = when {
        score >= 80 -> "좋음"
        score >= 60 -> "보통"
        else -> "개선 가능"
    }
    val glucose = 90 + (seed * 7 % 30)       // 90~119
    val glucoseStatus = when {
        glucose < 100 -> "정상"
        glucose <= 130 -> "당뇨 목표 범위"
        else -> "높음"
    }
    val steps = 5000 + (seed * 200 % 5000)   // 5000~9999
    val sleepHours = 6.5 + (seed % 5) * 0.3  // 6.5~7.7

    DataCard(emoji = "🎯", title = "점수", value = "${score}점", subtitle = scoreLabel)
    DataCard(emoji = "🩸", title = "공복혈당", value = "${glucose} mg/dL", subtitle = glucoseStatus)
    DataCard(emoji = "🚶", title = "활동", value = "${steps}보", subtitle = "수면 %.1fh".format(sleepHours))

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        border = BorderStroke(1.dp, Divider)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📝", fontSize = 18.sp)
                Spacer(Modifier.width(8.dp))
                Text("회고", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // 데모: 짝수일은 음주 X / 홀수일은 음주 O
                Chip("음주", seed % 2 == 1)
                Chip("저녁 19시 전", seed % 3 != 0, goodIfTrue = true)
                Chip("식후 금식", seed % 4 != 0, goodIfTrue = true)
            }
        }
    }
}

/**
 * 최근 7일 공복혈당 추이 — 70/130 점선 표시 (당봄 피드백 #10).
 * 정상 범위 (70~100) 안: 녹색, 100~125: 노랑, 125+ or <70: 빨강
 */
@Composable
private fun GlucoseTrendCard(glucoseByDate: Map<LocalDate, Int>) {
    if (glucoseByDate.isEmpty()) return  // 데이터 없으면 카드 자체 안 보임

    val today = todayKst()  // [260726] KST 기준 — ViewModel 의 최근 7일 조회와 같은 날짜를 봐야 그래프가 비지 않음
    val days = (0..6).map { today.minusDays((6 - it).toLong()) }
    val values = days.map { glucoseByDate[it] }
    val nonNull = values.filterNotNull()
    val avg = if (nonNull.isNotEmpty()) nonNull.average() else 0.0

    // [Codex P2] 상세 화면과 동일하게 목표범위 도출 (의료진 목표 1순위 → 평소범위 → 70~130)
    var targetLow by remember { mutableStateOf(70) }
    var targetHigh by remember { mutableStateOf(130) }
    LaunchedEffect(Unit) {
        var medTarget: Int? = null
        var personalLow: Int? = null
        try { val g = ServiceLocator.medicalGoalRepository.get(); if (g is ApiResult.Success && g.data.hasGoal) medTarget = g.data.targetFastingGlucose } catch (_: Exception) {}
        try { val u = ServiceLocator.userRepository.getMe(); if (u is ApiResult.Success) personalLow = u.data.glucoseNormalLow } catch (_: Exception) {}
        val (lo, hi) = computeGlucoseTargetRange(medTarget, personalLow)
        targetLow = lo; targetHigh = hi
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        border = BorderStroke(1.dp, Divider)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "🩸 최근 7일 공복혈당",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                if (nonNull.isNotEmpty()) {
                    Text(
                        "평균 ${avg.toInt()} mg/dL",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "목표 범위: ${targetLow}~${targetHigh} mg/dL (점선)",
                fontSize = 11.sp,
                color = TextSecondary
            )
            Spacer(Modifier.height(12.dp))
            GlucoseLineChart(days = days, values = values, targetLow = targetLow, targetHigh = targetHigh)
        }
    }
}

@Composable
private fun GlucoseLineChart(days: List<LocalDate>, values: List<Int?>, targetLow: Int = 70, targetHigh: Int = 130) {
    val minRange = 50f
    // ⚠️ 의료 정확성: 200 초과 혈당값 + 목표 상한도 잘리지 않도록 동적 조정
    // 최대값 + 여유 30, 최소 200 보장, 50 단위로 올림
    val dataMax = maxOf(values.filterNotNull().maxOrNull() ?: 200, targetHigh)
    val maxRange = maxOf(200, ((dataMax + 30) / 50) * 50 + (if ((dataMax + 30) % 50 > 0) 50 else 0)).toFloat()
    val rangeSpan = maxRange - minRange
    val textPrimaryArgb = TextPrimary.toArgb()
    val secondaryArgb = TextSecondary.toArgb()

    androidx.compose.foundation.Canvas(
        modifier = Modifier.fillMaxWidth().height(220.dp)
    ) {
        // [반응형] 모든 px 상수를 dp.toPx()로 — 저/고밀도 기기에서 동일 비율 유지 (테스트 기기 density≈2.75 기준 N/2.75)
        val topPad = 10.dp.toPx()
        val bottomPad = 14.5.dp.toPx()
        val sidePad = 10.dp.toPx()
        val w = size.width - sidePad * 2
        val h = size.height - topPad - bottomPad

        fun yFor(v: Int): Float {
            val ratio = ((v - minRange) / rangeSpan).coerceIn(0f, 1f)
            return topPad + h - h * ratio
        }

        // 목표 범위 영역 살짝 음영 (y70=목표 하한, y130=목표 상한 — 변수명 기존 유지)
        val y70 = yFor(targetLow)
        val y130 = yFor(targetHigh)
        drawRect(
            color = Color(0xFF006E1C).copy(alpha = 0.06f),  // 녹색 매우 옅게
            topLeft = androidx.compose.ui.geometry.Offset(sidePad, y130),
            size = androidx.compose.ui.geometry.Size(size.width - sidePad * 2, y70 - y130)
        )

        // 가로 격자 (50, 100, 150, 200)
        val gridPaint = android.graphics.Paint().apply {
            color = secondaryArgb
            textSize = 28f / 2.75f * density   // [반응형] sp 대신 px → density로 스케일
            textAlign = android.graphics.Paint.Align.RIGHT
            isAntiAlias = true
        }
        // 격자선 — 50 단위로 minRange부터 maxRange까지 동적 생성
        val gridLines = generateSequence(minRange.toInt()) { it + 50 }
            .takeWhile { it <= maxRange.toInt() }
            .toList()
        gridLines.forEach { line ->
            val y = yFor(line)
            drawLine(
                color = Divider,
                start = androidx.compose.ui.geometry.Offset(sidePad, y),
                end = androidx.compose.ui.geometry.Offset(size.width - sidePad, y),
                strokeWidth = 1.dp.toPx()
            )
            drawIntoCanvas { c ->
                c.nativeCanvas.drawText("$line", sidePad - 1.5.dp.toPx(), y + 3.dp.toPx(), gridPaint)
            }
        }

        // 70 / 130 점선 (목표 범위)
        val targetLinePaint = android.graphics.Paint().apply {
            color = android.graphics.Color.rgb(0, 110, 28)  // Primary green
            strokeWidth = 1.dp.toPx()
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(4.4.dp.toPx(), 3.dp.toPx()), 0f)
            isAntiAlias = true
        }
        drawIntoCanvas { c ->
            c.nativeCanvas.drawLine(sidePad, y70, size.width - sidePad, y70, targetLinePaint)
            c.nativeCanvas.drawLine(sidePad, y130, size.width - sidePad, y130, targetLinePaint)
        }

        // 점선 라벨 (70 / 130)
        val rangeLabelPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.rgb(0, 110, 28)
            textSize = 22f / 2.75f * density   // [반응형] sp 대신 px → density로 스케일
            textAlign = android.graphics.Paint.Align.LEFT
            isFakeBoldText = true
            isAntiAlias = true
        }
        drawIntoCanvas { c ->
            c.nativeCanvas.drawText("$targetHigh", size.width - sidePad + 1.5.dp.toPx(), y130 + 2.dp.toPx(), rangeLabelPaint)
            c.nativeCanvas.drawText("$targetLow", size.width - sidePad + 1.5.dp.toPx(), y70 + 2.dp.toPx(), rangeLabelPaint)
        }

        // 데이터 포인트 좌표
        val n = days.size
        val stepX = if (n > 1) w / (n - 1) else 0f
        val points = values.mapIndexed { i, v ->
            v?.let { androidx.compose.ui.geometry.Offset(sidePad + i * stepX, yFor(it)) }
        }

        // 라인 연결 (연속된 non-null만)
        for (i in 0 until points.size - 1) {
            val p1 = points[i]
            val p2 = points[i + 1]
            if (p1 != null && p2 != null) {
                drawLine(
                    color = Primary,
                    start = p1,
                    end = p2,
                    strokeWidth = 1.dp.toPx()
                )
            }
        }

        // 점 + 값 라벨 + 요일
        val valuePaint = android.graphics.Paint().apply {
            color = textPrimaryArgb
            textSize = 36f / 2.75f * density   // [반응형] sp 대신 px → density로 스케일
            textAlign = android.graphics.Paint.Align.CENTER
            isFakeBoldText = true
            isAntiAlias = true
        }
        val dayLabelPaint = android.graphics.Paint().apply {
            color = secondaryArgb
            textSize = 30f / 2.75f * density   // [반응형] sp 대신 px → density로 스케일
            textAlign = android.graphics.Paint.Align.CENTER
            isFakeBoldText = true
            isAntiAlias = true
        }
        val dayOfWeekKor = listOf("월", "화", "수", "목", "금", "토", "일")
        val today = todayKst()  // [260726] KST 기준 — '오늘' 라벨이 그래프의 다른 날짜 계산과 어긋나지 않게

        points.forEachIndexed { i, p ->
            // 요일/날짜 라벨 (점 없어도 표시)
            val xCenter = sidePad + i * stepX
            val date = days[i]
            val isToday = date == today
            val dowIdx = (date.dayOfWeek.value - 1).coerceIn(0, 6)  // 월=0
            drawIntoCanvas { c ->
                val dayLabel = if (isToday) "오늘" else dayOfWeekKor[dowIdx]
                c.nativeCanvas.drawText(dayLabel, xCenter, size.height - 6.dp.toPx(), dayLabelPaint)
                c.nativeCanvas.drawText("${date.monthValue}/${date.dayOfMonth}", xCenter, size.height - 0f, dayLabelPaint)
            }

            val v = values[i]
            if (p != null && v != null) {
                // 색상: 목표 하한 미만 = 파랑 / 목표 범위 내 = 녹색 / 목표 상한 초과 = 빨강
                val pointColor = when {
                    v < targetLow -> Color(0xFF1976D2)
                    v in targetLow..targetHigh -> Success
                    else -> ErrorRed
                }
                drawCircle(color = pointColor, radius = 4.dp.toPx(), center = p)
                drawCircle(color = BgCard, radius = 2.2.dp.toPx(), center = p)

                // 값 라벨 (점 위)
                drawIntoCanvas { c ->
                    c.nativeCanvas.drawText("$v", p.x, p.y - 6.dp.toPx(), valuePaint)
                }
            }
        }
    }
}

@Composable
private fun DataCard(
    emoji: String,
    title: String,
    value: String,
    subtitle: String,
    onClick: (() -> Unit)? = null
) {
    val modifier = if (onClick != null) {
        Modifier.fillMaxWidth().clickable { onClick() }
    } else {
        Modifier.fillMaxWidth()
    }
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        border = BorderStroke(1.dp, Divider)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(emoji, fontSize = 28.sp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 12.sp, color = TextSecondary)
                Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                if (subtitle.isNotBlank()) {
                    Text(subtitle, fontSize = 11.sp, color = TextSecondary)
                }
            }
            if (onClick != null) {
                Icon(
                    androidx.compose.material.icons.Icons.Filled.ChevronRight,
                    contentDescription = "상세 보기",
                    tint = TextSecondary
                )
            }
        }
    }
}

@Composable
private fun Chip(label: String, value: Boolean?, goodIfTrue: Boolean = false) {
    val (bg, color) = when {
        value == null -> BgCardSoft to TextDisabled
        value == goodIfTrue -> LimeLight to PrimaryDark
        else -> ErrorRed.copy(alpha = 0.1f) to ErrorRed
    }
    val text = when (value) {
        true -> "$label ✓"
        false -> "$label ✗"
        null -> "$label -"
    }
    Surface(color = bg, shape = RoundedCornerShape(12.dp)) {
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = color, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}
