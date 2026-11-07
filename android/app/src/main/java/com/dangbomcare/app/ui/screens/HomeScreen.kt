@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.dangbomcare.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.TipsAndUpdates
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dangbomcare.app.data.api.AIFeedbackToday
import com.dangbomcare.app.data.api.ScoreTodayResponse
import com.dangbomcare.app.data.api.UserMeResponse
import com.dangbomcare.app.ui.components.AiRegenNotice
import com.dangbomcare.app.ui.components.LoadingIndicator
import com.dangbomcare.app.ui.components.ErrorMessage
import com.dangbomcare.app.ui.theme.*
import com.dangbomcare.app.ui.viewmodel.HomeViewModel
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * 홈 자동 동기화 스로틀 — 마지막 동기화가 이 시간 이내면 ON_RESUME 재동기화 스킵.
 * 앱을 진짜 새로 열면(보통 분~시간 단위) 매번 동기화되지만, 권한 다이얼로그/다른 화면 잠깐 다녀오며
 * 빠르게 resume 가 연속으로 튀는 경우(~90초 내)엔 이중 실행을 막는다.
 * ([Fix2] 기존 12분 → 90초로 낮춤: '앱 열 때마다 동기화'가 목적이라 짧은 디바운스만 둠.)
 */
private const val AUTO_SYNC_THROTTLE_MS = 90 * 1000L  // 90초

@Composable
fun HomeScreen(
    onGotoMorningInput: () -> Unit,
    onGotoDetailActivity: () -> Unit,
    onGotoDetailSleep: () -> Unit,
    onGotoDetailStress: () -> Unit,
    onGotoAiFeedback: () -> Unit,
    onGotoScoreDetail: () -> Unit = {},
    scrollToTopTrigger: Int = 0,
    vm: HomeViewModel = viewModel()
) {
    // 하단바 동일 탭 재클릭 시 최상단 스크롤 트리거
    val scrollState = androidx.compose.foundation.rememberScrollState()
    androidx.compose.runtime.LaunchedEffect(scrollToTopTrigger) {
        if (scrollToTopTrigger > 0) scrollState.animateScrollTo(0)
    }
    val state by vm.state.collectAsState()
    var showInputPrompt by remember { mutableStateOf(false) }
    // [사용자 피드백] 사용자가 한번 dismiss/입력화면 진입했으면 그 세션 동안 팝업 X.
    //   process 죽기 전까지 유지 (rememberSaveable). 진짜 입력 완료(needsMorningInput=false)면 자연스럽게 사라짐.
    var morningPromptDismissed by rememberSaveable { mutableStateOf(false) }
    var milestoneDays by remember { mutableStateOf<Int?>(null) }
    // [피드백 260714] 매월 자동 공복혈당 범위 재정의 — needs_recalc=true 이면 팝업 노출
    var rangeSuggestion by remember { mutableStateOf<com.dangbomcare.app.data.api.GlucoseRangeSuggestion?>(null) }
    // 세션 내 한 번만 fetch (dismiss/save 이후 재조회 안 함)
    var rangeCheckDone by rememberSaveable { mutableStateOf(false) }
    // [Codex 리뷰 #1] ackScope는 다이얼로그 밖(HomeScreen 상위) scope로 — 다이얼로그 disposal 시
    //   let 내부 rememberCoroutineScope는 즉시 취소돼 ack HTTP가 서버 도달 전에 죽을 수 있음.
    //   상위 scope로 빼면 최소한 HomeScreen composition 살아있는 동안 완료 보장.
    val rangeAckScope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    // [피드백 260714] 홈 첫 진입 시 이번 달 재정의 팝업 필요 여부 확인 — 세션당 1회.
    //   백엔드가 needs_recalc + suggested_low/high(지난 달 데이터, ≥10일 시 IQR·<10일 시 기존값) 반환.
    //   suggested가 null이어도 표시 (기존 범위·회원가입값이 그대로 채워짐).
    LaunchedEffect(Unit) {
        if (rangeCheckDone) return@LaunchedEffect
        try {
            val res = com.dangbomcare.app.data.ServiceLocator.apiService.getGlucoseRangeSuggestion()
            val body = res.body()
            if (res.isSuccessful && body != null && body.needsRecalc) {
                rangeSuggestion = body
            } else {
                rangeCheckDone = true
            }
        } catch (_: Exception) {
            rangeCheckDone = true
        }
    }

    // [피드백 260714] 공복혈당 범위 재정의 팝업 — 지난 달력 한 달 데이터 기반, 사용자 편집.
    rangeSuggestion?.let { sug ->
        // 초기값 우선순위: 계산된 권장값 → 현재 범위 → 빈 문자열
        val initLow = sug.suggestedLow ?: sug.currentLow
        val initHigh = sug.suggestedHigh ?: sug.currentHigh
        var lowInput by remember(sug) { mutableStateOf(initLow?.toString() ?: "") }
        var highInput by remember(sug) { mutableStateOf(initHigh?.toString() ?: "") }
        var saving by remember { mutableStateOf(false) }
        var saveError by remember { mutableStateOf<String?>(null) }
        // ackScope는 위쪽에서 정의된 rangeAckScope 사용 (다이얼로그 dispose 후에도 살아있음)
        val onClose: () -> Unit = {
            rangeSuggestion = null
            rangeCheckDone = true
            rangeAckScope.launch {
                runCatching { com.dangbomcare.app.data.ServiceLocator.apiService.ackGlucoseRangeCheck() }
            }
        }
        // 지난 달 표기 ("7월") — 응답에 based_on_month 있으면 사용, 없으면 "지난 달"
        val monthLabel = sug.basedOnMonth?.let { "${it}월" } ?: "지난 달"
        // [피드백 260719] "N일 기록 기준"이 "N일자의 기록"으로 오해될 수 있어 "N일치 기록"으로 명확화
        val bodyMsg = when {
            sug.basedOnDays >= 15 ->
                "${monthLabel} 한 달 동안 기입된 공복혈당을 바탕으로 평균 공복혈당 범위를 계산했어요."
            sug.basedOnDays >= 10 ->
                "${monthLabel} 한 달 동안 기입된 공복혈당을 바탕으로 평균 공복혈당 범위를 계산했어요.\n" +
                "다만 입력 일수가 적어 산출값이 정확하지 않을 수 있어요."
            else ->
                "${monthLabel}에 입력하신 공복혈당 기록(${sug.basedOnDays}일)이 부족해서 " +
                "기존 범위를 그대로 유지했어요. 필요하면 직접 수정해주세요."
        }
        // [피드백 260719] 배경색 흰색 강제(테마 보라 톤 방지) + 초록 액센트 + 글씨 크기 확대
        AlertDialog(
            onDismissRequest = onClose,
            containerColor = androidx.compose.ui.graphics.Color.White,
            iconContentColor = Primary,
            titleContentColor = TextPrimary,
            textContentColor = TextPrimary,
            icon = { Text("🩸", fontSize = 44.sp) },
            title = {
                Text("이번 달 공복혈당 범위",
                    fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    textAlign = TextAlign.Center)
            },
            text = {
                Column {
                    Text(bodyMsg, fontSize = 15.sp, color = TextPrimary, lineHeight = 22.sp)
                    Spacer(Modifier.height(14.dp))
                    val cur = if (sug.currentLow != null && sug.currentHigh != null)
                        "현재 범위: ${sug.currentLow}~${sug.currentHigh} mg/dL"
                    else "현재 범위: 미설정"
                    Text(cur, fontSize = 14.sp, color = TextSecondary)
                    if (sug.basedOnDays >= 10 && sug.suggestedLow != null && sug.suggestedHigh != null) {
                        // [피드백 260720] 괄호 전체가 다음 줄로 넘어가게 상하 배치.
                        //   문구도 "(6월에 기록된 25일치 기준)"으로 수정.
                        Text(
                            "권장: ${sug.suggestedLow}~${sug.suggestedHigh} mg/dL",
                            fontSize = 14.sp, color = Primary, fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "(${monthLabel}에 기록된 ${sug.basedOnDays}일치 기준)",
                            fontSize = 13.sp, color = Primary
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = lowInput,
                            onValueChange = { s -> if (s.length <= 3 && s.all { c -> c.isDigit() }) lowInput = s },
                            label = { Text("하한", fontSize = 14.sp) },
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold),
                            singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Primary,
                                focusedLabelColor = Primary,
                                cursorColor = Primary,
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("~", fontSize = 22.sp, color = TextSecondary)
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(
                            value = highInput,
                            onValueChange = { s -> if (s.length <= 3 && s.all { c -> c.isDigit() }) highInput = s },
                            label = { Text("상한", fontSize = 14.sp) },
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold),
                            singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Primary,
                                focusedLabelColor = Primary,
                                cursorColor = Primary,
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    saveError?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, fontSize = 13.sp, color = ErrorRed)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val lo = lowInput.toIntOrNull()
                        val hi = highInput.toIntOrNull()
                        when {
                            lo == null || hi == null -> saveError = "숫자를 입력해주세요"
                            lo < 40 || hi > 400 -> saveError = "40~400 범위로 입력해주세요"
                            lo >= hi -> saveError = "하한은 상한보다 작아야 해요"
                            else -> {
                                saving = true
                                saveError = null
                                rangeAckScope.launch {
                                    try {
                                        val req = com.dangbomcare.app.data.api.UserUpdateRequest(
                                            glucoseNormalLow = lo, glucoseNormalHigh = hi
                                        )
                                        val r = com.dangbomcare.app.data.ServiceLocator.apiService.updateMe(req)
                                        if (r.isSuccessful) {
                                            runCatching { com.dangbomcare.app.data.ServiceLocator.apiService.ackGlucoseRangeCheck() }
                                            rangeSuggestion = null
                                            rangeCheckDone = true
                                            vm.load()   // 홈 재로딩(범위 반영된 점수)
                                        } else {
                                            saveError = "저장 실패 (${r.code()})"
                                        }
                                    } catch (e: Exception) {
                                        saveError = "저장 실패: ${e.message}"
                                    } finally {
                                        saving = false
                                    }
                                }
                            }
                        }
                    },
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) {
                    Text(if (saving) "저장 중…" else "저장", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = onClose) {
                    Text("나중에", fontSize = 15.sp, color = TextSecondary)
                }
            },
            shape = RoundedCornerShape(20.dp)
        )
    }

    // 주간 달성 milestone 감지 — 3/5/7일 도달 시 1회 축하 팝업
    LaunchedEffect(state.feedback?.streak?.weeklyCompletedDays) {
        val streak = state.feedback?.streak?.weeklyCompletedDays ?: 0
        val prefs = context.getSharedPreferences("dangdangcare_milestones", android.content.Context.MODE_PRIVATE)
        val weekKey = "shown_${java.time.LocalDate.now().with(java.time.DayOfWeek.MONDAY)}"
        val lastShown = prefs.getInt(weekKey, 0)
        if (streak in listOf(3, 5, 7) && streak > lastShown) {
            milestoneDays = streak
            prefs.edit().putInt(weekKey, streak).apply()
        }
    }

    // Milestone 다이얼로그
    milestoneDays?.let { days ->
        AlertDialog(
            onDismissRequest = { milestoneDays = null },
            icon = { Text(if (days >= 7) "🏆" else "🎉", fontSize = 48.sp) },
            title = {
                Text(
                    "${days}일 연속 입력 달성!",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Text(
                    when (days) {
                        3 -> "사흘 연속 잘 기록해주셨어요.\n작은 습관이 큰 변화를 만들어요."
                        5 -> "닷새 연속 훌륭해요.\n주간 목표가 코앞이에요!"
                        else -> "한 주 매일 입력 달성!\n혈당 관리의 가장 큰 무기는 꾸준함입니다."
                    },
                    fontSize = 14.sp,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = { milestoneDays = null },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) {
                    Text("고마워요!", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            },
            shape = RoundedCornerShape(20.dp)
        )
    }

    // [자동 동기화/새로고침] 실시간 활동 카드 + 워치 동기화 시각 텍스트가 sync 후 다시 그려지도록 하는 키.
    //   sync 성공으로 ++ 하면 (1) TodayActivityCard(refreshKey)가 재-fetch, (2) HomeHeader 의
    //   formatLastSync 텍스트가 다시 계산됨 → "17분 전" 같은 낡은 값이 "방금 전"으로 갱신.
    val activityRefreshKey = remember { mutableStateOf(0) }
    // ON_RESUME 에서 IO 동기화를 띄우기 위한 스코프 (LifecycleEventObserver 람다는 코루틴 컨텍스트가 아님).
    val syncScope = rememberCoroutineScope()

    // ⭐ 화면 진입/앱 포그라운드(ON_RESUME)마다: (1) 서버 데이터 reload, (2) 워치 오늘+어제 자동 동기화.
    //   ⌚ [Fix2 자동 동기화] LaunchedEffect(Unit)(최초 1회)에서 ON_RESUME 으로 이동 →
    //     백그라운드에서 돌아와 앱을 다시 열 때마다 워치 데이터가 올라가고 동기화 시각이 갱신됨.
    //   - 가입 직후/앱 열기만으로 오늘+어제 데이터가 서버에 올라가게 (점수·리포트가 비지 않도록).
    //   - WatchSyncWorker(30분 주기)/MorningInput/설정 90일 백필과 동일한 안전 가드를 공통 함수로 재사용.
    //   - 조용히(블로킹 스피너 X) IO에서 실행, 예외는 절대 화면에 영향 주지 않게 try/catch (취소는 재-throw).
    //   - 스로틀: 마지막 동기화가 ~90초 이내면 스킵 → 빠른 resume 연타엔 이중 실행 안 함(진짜 앱 열기는 동기화).
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                vm.load()
                syncScope.launch {
                    try {
                        // 스로틀 — 최근 동기화(WatchSyncTracker.lastSyncMs)가 90초 이내면 스킵.
                        val lastMs = com.dangbomcare.app.data.health.WatchSyncTracker.lastSyncMs(context)
                        val fresh = lastMs != null &&
                            (System.currentTimeMillis() - lastMs) < AUTO_SYNC_THROTTLE_MS
                        if (!fresh) {
                            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                // 공통 안전 동기화: canReadWatchData 게이트 + 오늘/어제 fetch + all-null 가드 + mark()
                                com.dangbomcare.app.data.health.WatchSyncActions.syncTodayAndYesterday(context)
                            }
                            // 새 데이터가 올라갔으면 실시간 활동 카드 재-fetch + 동기화 시각 텍스트 갱신.
                            if (result.anyPosted) activityRefreshKey.value++
                        }
                    } catch (ce: kotlinx.coroutines.CancellationException) {
                        throw ce  // 취소는 삼키지 말고 전파
                    } catch (_: Exception) {
                        // 동기화 실패는 홈 화면에 영향 X (조용히 무시)
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 입력 안 했을 때 자동으로 팝업 — 단 사용자가 dismiss/진입했던 세션이면 다시 X
    // [사용자 피드백] 아침 알림 시간 -2시간 이전 (예: 알림 7시면 새벽 5시 이전)엔 팝업 안 띄움
    val homeCtx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(state.needsMorningInput, state.loading, morningPromptDismissed) {
        if (!state.loading && state.needsMorningInput && state.score == null && !morningPromptDismissed) {
            val (mh, mm) = com.dangbomcare.app.notification.AlarmScheduler.getHourMinute(
                homeCtx, com.dangbomcare.app.notification.AlarmType.MORNING
            )
            val morningMinutes = mh * 60 + mm
            val now = java.time.LocalTime.now()
            val nowMinutes = now.hour * 60 + now.minute
            // 팝업 허용: morning_time - 2h 이후 ~ 다음날 morning_time - 2h 이전
            // 단순 룰: 현재 시각이 (morning_time - 2시간)보다 이르면 팝업 X
            val tooEarly = nowMinutes < morningMinutes - 120
            // 예외: morning_time이 2~3시면 새벽 0~24 어디든 OK (드물지만 안전)
            showInputPrompt = !tooEarly || morningMinutes < 120
        } else if (!state.needsMorningInput) {
            // 진짜 입력 완료 → 팝업 + dismiss flag 모두 reset
            showInputPrompt = false
            morningPromptDismissed = false
        } else {
            showInputPrompt = false
        }
    }

    Scaffold(
        containerColor = BgSecondary,
        topBar = { HomeHeader(state.user, syncRefreshKey = activityRefreshKey.value) }
    ) { padding ->
        // 공복혈당 입력 팝업 — 시간대별 인사
        val hour = java.time.LocalTime.now().hour
        val (greetingEmoji, greetingTitle, greetingBody) = when (hour) {
            in 5..10 -> Triple(
                "☀️",
                "좋은 아침이에요!",
                "오늘 공복혈당을 아직 입력하지 않으셨어요.\n지금 입력해주시면 AI 코치가 분석해드려요."
            )
            in 11..13 -> Triple(
                "🌤️",
                "오늘도 잘 보내고 계신가요?",
                "오늘 공복혈당을 아직 입력 안 하셨네요.\n지금이라도 입력하시면 AI 코치가 분석해드려요."
            )
            in 14..17 -> Triple(
                "🌇",
                "오후도 힘내세요",
                "오늘 공복혈당이 아직 기록되지 않았어요.\n지금이라도 입력하시면 데이터가 쌓여요."
            )
            in 18..21 -> Triple(
                "🌙",
                "저녁 잘 보내고 계신가요?",
                "오늘 공복혈당 기록을 놓치셨네요.\n내일 아침에는 일어나자마자 측정해 보세요."
            )
            else -> Triple(
                "🌃",
                "늦은 시간이네요",
                "오늘 공복혈당이 아직 기록되지 않았어요.\n내일 아침 일어나서 측정하시면 좋아요."
            )
        }
        if (showInputPrompt) {
            AlertDialog(
                onDismissRequest = {
                    showInputPrompt = false
                    morningPromptDismissed = true  // 외부 터치/뒤로가기로 닫음 → 세션 동안 다시 X
                },
                icon = { Text(greetingEmoji, fontSize = 36.sp) },
                title = {
                    Text(
                        greetingTitle,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                },
                text = {
                    Text(
                        greetingBody,
                        fontSize = 14.sp,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showInputPrompt = false
                            morningPromptDismissed = true  // 입력 화면 진입 — 뒤로가기로 돌아와도 팝업 X
                            onGotoMorningInput()
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary)
                    ) {
                        Text("지금 입력하기", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showInputPrompt = false
                            morningPromptDismissed = true  // "나중에" 명시 클릭 — 다시 X
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("다음에 입력하기", color = TextSecondary)
                    }
                },
                shape = RoundedCornerShape(20.dp),
                containerColor = BgPrimary
            )
        }
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp),
            // [v1.0 피드백] 박스 간격 줄여 스크롤 길이 축소 (12→6)
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // [우노 4] 의료진 목표 — 첫 화면 상단에 표시 (목표 없으면 렌더 안 함)
            DoctorGoalCard(state.medicalGoal)

            // ⌚ 워치 동기화 필요 배너 — 권한이 풀린 상태(폰 OS 업데이트 등)면 홈에서 바로 재연결.
            // [260812] 배터리 최적화 안내 (팝업 1회 + 꺼진 동안 배너 상시)
            HomeBatteryOptimizationNotice()

            // [260821] 삼성헬스 절전 안내 — 걸음 데이터의 실제 출처가 삼성헬스라 이쪽도 풀어야 한다.
            HomeSamsungHealthBatteryNotice()

            HomeWatchSyncBanner(onSynced = { activityRefreshKey.value++; vm.load() })

            when {
                state.loading -> {
                    Spacer(Modifier.height(80.dp))
                    LoadingIndicator()
                }
                state.error != null -> {
                    // 데모 fallback 제거 — 항상 명확한 오류 화면만 표시
                    Spacer(Modifier.height(60.dp))
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("⚠️", fontSize = 48.sp)
                        Spacer(Modifier.height(12.dp))
                        Text("데이터를 불러올 수 없어요", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Spacer(Modifier.height(6.dp))
                        Text("네트워크 연결을 확인하시고 다시 시도해주세요", fontSize = 13.sp, color = TextSecondary, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(20.dp))
                        Button(onClick = vm::load, shape = RoundedCornerShape(12.dp)) {
                            Text("다시 시도", fontWeight = FontWeight.Bold)
                        }
                    }
                }
                state.needsMorningInput || state.score == null -> {
                    // [사용자 피드백] 입력 안 한 상태 — 예시 데이터 X.
                    //   AI/미션은 안내 멘트, 활동은 실시간 워치 데이터 그대로 표시.
                    Spacer(Modifier.height(4.dp))

                    // 1) 입력 유도 큰 카드
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("📝", fontSize = 48.sp)
                        Spacer(Modifier.height(12.dp))
                        Text("오늘 공복혈당을 입력해주세요", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "입력하시면 AI 코치가 전날 활동과 함께 분석을 시작해드려요",
                            fontSize = 13.sp,
                            color = TextSecondary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = onGotoMorningInput, shape = RoundedCornerShape(12.dp)) {
                            Text("지금 입력하기", fontWeight = FontWeight.Bold)
                        }
                    }

                    // 2) AI 피드백 — 안내 멘트
                    PendingFeedbackCard()
                    Spacer(Modifier.height(12.dp))

                    // 3) 오늘의 미션 — 안내 멘트
                    PendingMissionCard()
                    Spacer(Modifier.height(20.dp))

                    // 4) 워치 실시간 활동 (HC에서 직접 fetch — 입력 무관)
                    Text(
                        "워치에서 가져온 오늘 활동",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextSecondary,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    TodayActivityCard(
                        onGotoActivity = onGotoDetailActivity,
                        onGotoStress = onGotoDetailStress,
                        refreshKey = activityRefreshKey.value
                    )
                }
                else -> {
                    Spacer(Modifier.height(4.dp))
                    HomeContent(
                        state = state,
                        onGotoDetailActivity = onGotoDetailActivity,
                        onGotoDetailSleep = onGotoDetailSleep,
                        onGotoDetailStress = onGotoDetailStress,
                        onGotoAiFeedback = onGotoAiFeedback,
                        onGotoScoreDetail = onGotoScoreDetail,
                        activityRefreshKey = activityRefreshKey.value
                    )
                }
            }
            Spacer(Modifier.height(24.dp))  // 하단 네비는 Scaffold padding이 처리
        }
    }
}

@Composable
private fun HomeHeader(user: UserMeResponse?, syncRefreshKey: Int = 0) {
    val today = LocalDate.now()
    val hour = java.time.LocalTime.now().hour
    val context = androidx.compose.ui.platform.LocalContext.current
    val dateStr = today.format(DateTimeFormatter.ofPattern("yyyy년 M월 d일 · E요일", Locale.KOREAN))
    // 시간대별 인사
    val (greetingEmoji, greetingPrefix) = when (hour) {
        in 5..10 -> "🌅" to "좋은 아침이에요"
        in 11..13 -> "🌤️" to "안녕하세요"
        in 14..17 -> "🌇" to "좋은 오후예요"
        in 18..21 -> "🌙" to "좋은 저녁이에요"
        else -> "🌃" to "안녕하세요"
    }
    // [Fix2] sync 후 activityRefreshKey 가 바뀌면 이 값을 다시 계산해 "N분 전 동기화" → "방금 전 동기화"로 갱신.
    //   remember(syncRefreshKey) 로 키를 걸어 sync 직후 recomposition 에서 새 시각이 반영되게 함.
    val syncLabel = remember(syncRefreshKey) {
        com.dangbomcare.app.data.health.WatchSyncTracker.formatLastSync(context)
    }

    Surface(color = BgSecondary) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("$dateStr $greetingEmoji", fontSize = 12.sp, color = TextSecondary)
                Spacer(Modifier.height(2.dp))
                // 이름 있으면 시간대별 인사 + 이름님
                val userName = user?.name?.takeIf { it.isNotBlank() }
                if (userName != null) {
                    // 한 Text로 병합 — 긴 이름·큰 글씨에서 잘리는 대신 말줄임 처리
                    Text(
                        androidx.compose.ui.text.buildAnnotatedString {
                            append("$greetingPrefix, ")
                            withStyle(androidx.compose.ui.text.SpanStyle(color = Primary)) { append(userName) }
                            append("님")
                        },
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                } else {
                    Text("$greetingPrefix 👋", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                }
                Spacer(Modifier.height(4.dp))
                // 워치 동기화 최종 시각 (시니어 안심용)
                Text("⌚ $syncLabel", fontSize = 11.sp, color = TextSecondary)
            }
            // [v1.0 피드백 — 복구] 단순한 IconButton + 빨간 점
            var showNotifPanel by remember { mutableStateOf(false) }
            IconButton(onClick = { showNotifPanel = true }) {
                Box {
                    Icon(
                        Icons.Filled.Notifications,
                        contentDescription = "알림",
                        tint = TextPrimary
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(ErrorRed)
                    )
                }
            }
            if (showNotifPanel) {
                NotificationPanelDialog(onDismiss = { showNotifPanel = false })
            }
        }
    }
}

/**
 * 인앱 알림 패널 (당봄 #11).
 * 종 아이콘 클릭 시 표시 — 실시간 점수 멘트, 식후 산책 알림, 마일스톤 등.
 */
@Composable
private fun NotificationPanelDialog(onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var todaySteps by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(Unit) {
        try {
            val repo = com.dangbomcare.app.data.health.HealthConnectRepository(context)
            if (repo.isSdkAvailable()) {
                // [코덱스 P1] KST 기준 — 자정 전후/해외 timezone에서 어긋남 방지
                val today = repo.fetchWatchData(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")))
                todaySteps = today.steps
            }
        } catch (e: Exception) { /* */ }
    }

    val now = java.time.LocalTime.now()
    // [저녁 마감시간 동적화] 설정한 저녁식사 시각 → 알림 발화 시간대 + 문구에 반영 (하드코딩 '7시' 제거)
    val dinnerHour = remember { com.dangbomcare.app.notification.AlarmScheduler.dinnerHour(context) }
    val dinnerLabel = remember { com.dangbomcare.app.notification.AlarmScheduler.dinnerHourLabel(context) }
    val notifications = remember(todaySteps, now, dinnerHour, dinnerLabel) {
        buildNotificationList(todaySteps, now.hour, dinnerHour, dinnerLabel)
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = BgCard,
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .heightIn(max = 600.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                // ── 헤더: 컬러 원형 + 종 아이콘 + 제목 ──
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(LimeLight.copy(alpha = 0.4f))
                            .border(2.dp, Primary.copy(alpha = 0.3f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Notifications,
                            contentDescription = null,
                            tint = Primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("오늘의 알림", fontSize = 18.sp, fontWeight = FontWeight.Black, color = TextPrimary)
                        Text(
                            "${notifications.size}건의 안내",
                            fontSize = 11.sp,
                            color = Primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "닫기", tint = TextSecondary)
                    }
                }

                Spacer(Modifier.height(18.dp))

                // ── 본문 ──
                if (notifications.isEmpty()) {
                    Surface(
                        color = BgCardSoft,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("😌", fontSize = 36.sp)
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "지금은 알림이 없어요",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "오늘 활동을 좀 더 지켜보세요",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        notifications.forEach { n ->
                            Surface(
                                color = BgCardSoft,
                                shape = RoundedCornerShape(14.dp),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp, Divider.copy(alpha = 0.5f)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(Primary.copy(alpha = 0.10f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(n.emoji, fontSize = 18.sp)
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            n.title,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary
                                        )
                                        Spacer(Modifier.height(3.dp))
                                        Text(
                                            n.body,
                                            fontSize = 12.sp,
                                            color = TextSecondary,
                                            lineHeight = 18.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class InAppNotification(val emoji: String, val title: String, val body: String)

private fun buildNotificationList(currentSteps: Int?, hour: Int, dinnerHour: Int, dinnerLabel: String): List<InAppNotification> {
    val list = mutableListOf<InAppNotification>()

    // 1. 실시간 점수 유도
    if (currentSteps != null) {
        if (currentSteps >= 8000) {
            list.add(InAppNotification(
                "🏆",
                "오늘 목표 달성!",
                "8,000보를 달성하셨어요. 활동 점수 만점입니다."
            ))
        } else if (hour < 21) {
            val remaining = 8000 - currentSteps
            val potential = when {
                currentSteps >= 6000 -> 2
                currentSteps >= 4000 -> 4
                currentSteps >= 2000 -> 7
                else -> 10
            }
            list.add(InAppNotification(
                "🚶",
                "걸음 목표까지 ${"%,d".format(remaining)}보 남았어요",
                "조금 더 걸으시면 활동 점수가 +${potential}점 올라가요."
            ))
        }
    }

    // 2. 시간대별 권유
    // dinnerHour이 정상 범위(12~23)일 때만 저녁 알림 range 유효 — 0/음수/24+ 이상치는 빈 range로 매칭 회피
    val dinnerRange = if (dinnerHour in 12..23) (dinnerHour - 1)..dinnerHour else IntRange.EMPTY
    when (hour) {
        in 12..13 -> list.add(InAppNotification(
            "🍚",
            "점심 식후 산책 어떠세요?",
            "식후 30분~2시간 사이 1,000보 이상 걸으면 +3점 보너스!"
        ))
        in dinnerRange -> list.add(InAppNotification(
            "🍚",
            "저녁 $dinnerLabel 전 식사 챙기세요",
            "오늘 $dinnerLabel 전 식사 마치시면 라이프스타일 +10점."
        ))
        in 20..21 -> list.add(InAppNotification(
            "🍜",
            "식후 금식 챌린지",
            "저녁 식사 후 잠들기 전까지 아무것도 안 드시면 다음날 혈당이 안정돼요."
        ))
        in 22..23 -> list.add(InAppNotification(
            "😴",
            "취침 시각이 다가와요",
            "23시 전 취침이 혈당 관리에 가장 효과적입니다."
        ))
    }

    return list
}

/**
 * AI 피드백 자리에 들어가는 안내 카드 — 입력 전 사용자에게 무엇이 들어올지 안내.
 */
@Composable
private fun PendingFeedbackCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFD9E2E6))
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🤖", fontSize = 18.sp)
                Spacer(Modifier.width(8.dp))
                Text("오늘의 AI 피드백", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Slate)
            }
            Spacer(Modifier.height(10.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = BgPrimary)
            ) {
                Text(
                    "아직 오늘 입력을 하지 않으셨어요.\n공복혈당과 전날 회고를 입력하시면 AI 코치가 바로 분석해드릴게요.",
                    fontSize = 14.sp,
                    color = TextPrimary,
                    lineHeight = 22.sp,
                    modifier = Modifier.padding(14.dp)
                )
            }
        }
    }
}

/**
 * 미션 자리에 들어가는 안내 카드 — 입력 후 미션이 생성된다는 안내.
 */
@Composable
private fun PendingMissionCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, Divider)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🎯", fontSize = 18.sp)
                Spacer(Modifier.width(8.dp))
                Text("오늘의 미션", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "오늘 입력을 마치면 AI 코치가 오늘의 미션을 정해드려요.\n예) \"오늘 8천보 걷기\", \"7시간 이상 자기\"",
                fontSize = 13.sp,
                color = TextSecondary,
                lineHeight = 20.sp
            )
        }
    }
}

/**
 * 데모 홈 콘텐츠 — 네트워크 에러 시 실제 화면이 어떻게 보이는지 안내용. (현재 호출 X — dead code)
 */
@Composable
private fun DemoHomeContent() {
    // 1) 격려 배너 (데모)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Row(
            modifier = Modifier
                .background(Brush.horizontalGradient(listOf(LimeAccent, Color(0xFFB5DD6F), LimeAccent)))
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(44.dp).clip(CircleShape).background(Color.White),
                contentAlignment = Alignment.Center
            ) { Text("🏆", fontSize = 22.sp) }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("전날보다 +7점, 훌륭해요!", fontSize = 14.sp, fontWeight = FontWeight.Black, color = Color(0xFF0A2614))
                Text("3일 연속 점수 상승 중 🔥", fontSize = 11.sp, color = PrimaryDark)
            }
            Text("+7", fontSize = 24.sp, fontWeight = FontWeight.Black, color = Color(0xFF0A2614))
        }
    }

    // 2) 점수 카드 (데모)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, Divider)
    ) {
        Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("라이프스타일 점수", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextSecondary)
            Spacer(Modifier.height(12.dp))
            ScoreDonut(score = 82.0, label = "좋음", size = 144.dp)
            Spacer(Modifier.height(12.dp))
            Text("이번 주 평균 78점", fontSize = 11.sp, color = TextSecondary)
        }
    }

    // 3) AI 메시지 카드 (데모)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFD9E2E6))
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🤖", fontSize = 18.sp)
                Spacer(Modifier.width(8.dp))
                Text("오늘의 AI 피드백 (예시)", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Slate)
            }
            Spacer(Modifier.height(10.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = BgPrimary)
            ) {
                Text(
                    // [260728 고객피드백 ③] 예시 문구가 "음주 → 공복혈당 상승"으로 반대로 쓰여 있었다.
                    //   술을 드신 밤에는 간이 알코올을 분해하느라 밤사이 혈당을 올려주는 일을 제대로 못 해
                    //   **다음날 아침 공복혈당이 오히려 낮게** 나오는 경우가 많다.
                    //   (미호출 데모 문구지만 되살아날 수 있어 방향을 바로잡아 둔다. 없는 수치 '30% 줄어'도 삭제)
                    "전날 🍷 음주가 있으셨네요. 술을 드신 다음날 아침은 공복혈당이 오히려 낮게 나오는 경우가 많아요. " +
                        "😴 수면도 6시간으로 짧고 🍚 저녁 식사를 늦게 마치셔서 컨디션이 무거우실 수 있어요.",
                    fontSize = 14.sp,
                    color = TextPrimary,
                    lineHeight = 22.sp,
                    modifier = Modifier.padding(14.dp)
                )
            }
        }
    }

    // 4) 미션 카드 (데모)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, Divider)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🎯", fontSize = 18.sp)
                Spacer(Modifier.width(6.dp))
                Text("오늘의 미션 (예시)", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary, modifier = Modifier.weight(1f))
                Surface(color = LimeLight, shape = RoundedCornerShape(12.dp)) {
                    Text("🔥 5/7일", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = PrimaryDark,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("점심 식후 10분 걷기", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { 0.7f },
                modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)),
                color = LimeAccent,
                trackColor = BgCardSoft
            )
            Spacer(Modifier.height(4.dp))
            Text("진행률 70% (140/200보)", fontSize = 11.sp, color = TextSecondary)
        }
    }

    // 5) 오늘 활동 (데모 — 실제 데이터 없을 때 형식 안내)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, Divider)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("오늘 활동 (예시)", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier.size(56.dp).clip(CircleShape).background(LimeLight),
                        contentAlignment = Alignment.Center
                    ) { Text("🚶", fontSize = 22.sp) }
                    Spacer(Modifier.height(6.dp))
                    Text("4,231", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Text("오늘의 걸음 수", fontSize = 10.sp, color = TextSecondary)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier.size(56.dp).clip(CircleShape).background(LimeLight),
                        contentAlignment = Alignment.Center
                    ) { Text("😴", fontSize = 22.sp) }
                    Spacer(Modifier.height(6.dp))
                    Text("7.2시간", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    // [피드백 260714 정리 H9] '어젯밤 잠' → '수면' (실제 활동 카드와 라벨 통일)
                    Text("수면", fontSize = 10.sp, color = TextSecondary)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier.size(56.dp).clip(CircleShape).background(LimeLight),
                        contentAlignment = Alignment.Center
                    ) { Text("😌", fontSize = 22.sp) }
                    Spacer(Modifier.height(6.dp))
                    Text("적정", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Text("스트레스", fontSize = 10.sp, color = TextSecondary)
                }
            }
        }
    }
}

@Composable
private fun EmptyHome(onStart: () -> Unit, userName: String?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = LimeLight)
    ) {
        Column(
            modifier = Modifier.padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("☀️", fontSize = 56.sp)
            Spacer(Modifier.height(12.dp))
            Text(
                "${userName ?: "환자"}님,\n오늘 공복혈당을 입력해주세요",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = PrimaryDark,
                textAlign = TextAlign.Center,
                lineHeight = 28.sp
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "AI 코치가 전날 워치 데이터와 함께 분석해드려요",
                fontSize = 13.sp,
                color = PrimaryDark,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Primary)
            ) {
                Text("⊕ 지금 입력하기", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun trimGoalNumber(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

/**
 * [우노 4] 의료진 목표 카드 — 진료 시 의료진이 설정한 목표를 홈 첫 화면 상단에 표시.
 *   목표가 없으면(null/hasGoal=false) 아무것도 그리지 않음. 환자는 보기 전용.
 */
@Composable
private fun DoctorGoalCard(goal: com.dangbomcare.app.data.api.MedicalGoalResponse?) {
    if (goal == null || !goal.hasGoal) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Primary.copy(alpha = 0.06f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Primary.copy(alpha = 0.35f))
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {  // [우노] 더 작게
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🏥", fontSize = 16.sp)
                Spacer(Modifier.width(6.dp))
                Text(
                    "의료진 목표",
                    fontSize = 14.sp, fontWeight = FontWeight.Bold, color = PrimaryDark,
                    modifier = Modifier.weight(1f)
                )
                goal.targetPeriod?.takeIf { it.isNotBlank() }?.let { raw ->
                    val periodLabel = runCatching { java.time.LocalDate.parse(raw) }.getOrNull()
                        ?.let { d -> "${d.year}.${d.monthValue}.${d.dayOfMonth}까지" } ?: raw
                    Surface(color = Primary.copy(alpha = 0.15f), shape = RoundedCornerShape(8.dp)) {
                        Text(
                            periodLabel, fontSize = 11.sp, color = PrimaryDark, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(7.dp))
            goal.targetFastingGlucose?.let { DoctorGoalRow("공복혈당", "$it mg/dL") }
            goal.targetHba1c?.let { DoctorGoalRow("당화혈색소", "${trimGoalNumber(it)} %") }
            goal.targetPancreas?.let { DoctorGoalRow("췌장수치", trimGoalNumber(it)) }
            goal.lifestyleGoals?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(7.dp))
                Surface(color = BgCard, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
                        Text("📋", fontSize = 13.sp)
                        Spacer(Modifier.width(6.dp))
                        Text(it, fontSize = 12.sp, color = TextPrimary, lineHeight = 17.sp)
                    }
                }
            }
            // [피드백 260714] 담당 의료진 이름 — 있으면 카드 하단에 조용히 표시
            goal.setBy?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    "— $it",
                    fontSize = 11.sp, color = TextSecondary,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End
                )
            }
        }
    }
}

@Composable
private fun DoctorGoalRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // [robustness] 고정 width(72) → widthIn(min) : 1.4× 시니어 폰트에서 "당화혈색소"(5자)가
        //   72dp를 넘어 줄바꿈되며 행이 어긋나던 문제 방지. 평상시엔 72dp로 정렬 유지, 필요시만 확장.
        Text(label, fontSize = 12.sp, color = TextSecondary, maxLines = 1,
            modifier = Modifier.widthIn(min = 72.dp))
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = PrimaryDark)
    }
}

@Composable
private fun HomeContent(
    state: HomeViewModel.UiState,
    onGotoDetailActivity: () -> Unit,
    onGotoDetailSleep: () -> Unit,
    onGotoDetailStress: () -> Unit,
    onGotoAiFeedback: () -> Unit,
    onGotoScoreDetail: () -> Unit = {},
    activityRefreshKey: Int = 0
) {
    val score = state.score!!
    val change = if (score.yesterdayScore != null) score.totalScore - score.yesterdayScore else 0.0

    // [260726 의료안전] 오늘 혈당 위험 게이트.
    //   /scores/today 응답(ScoreTodayResponse)에는 안전상태 필드도, 실측 공복혈당도 없다(백엔드 scores.py 확인).
    //   앱이 이미 받는 신호 중 유일한 판정 근거 = breakdown.glucose 점수.
    //   백엔드 glucose_score()는 <70(저혈당)과 >180(초고혈당)에만 0점을 주므로 0 = 혈당 위험 구간,
    //   null = 미입력(위험 아님). 위험이면 백엔드가 미션·격려를 억제하는 것과 동일하게
    //   앱 자체 축하/독려 카드도 렌더하지 않는다 (경고보다 위에 "훌륭해요!"가 뜨던 문제).
    val glucoseUnsafe = score.breakdown.glucose == 0

    // ① 점수 변화 격려 배너 (배너 카운트업은 정수 — 반올림 표시)
    if (change != 0.0 && !glucoseUnsafe) {
        EncouragementBanner(change = Math.round(change).toInt(), weeklyStreak = state.feedback?.streak?.weeklyCompletedDays ?: 0)
    }

    // ② CARD 1 - 라이프스타일 점수 (클릭 시 산출 내역으로 이동)
    LifestyleScoreCard(score, onGotoScoreDetail)
    // 실시간 활동 점수 카드는 TodayActivityCard 내부에서 이미 표시 — 중복 방지 (Codex 검토)

    // ③ CARD 2 - 오늘의 AI 피드백
    //   [260722] feedback == null 이거나 dailyMessage 없으면 안내 카드로 fallback (이전엔 카드 통째 소멸)
    val _msg = state.feedback?.dailyMessage
    if (!_msg.isNullOrBlank()) {
        AiInterpretationCard(
            message = _msg,
            onClick = onGotoAiFeedback
        )
    } else {
        PendingFeedbackCard()
    }

    // ④ CARD 3 - 오늘의 미션
    //   [260722] mission 없거나 text 비면 안내 카드로 fallback (이전엔 카드 통째 소멸)
    val _mis = state.feedback?.mission?.takeIf { !it.text.isNullOrBlank() }
    if (_mis != null) {
        TodayMissionCard(_mis, state.feedback.streak?.weeklyCompletedDays ?: 0, state.feedback.streak?.weeklyTotalDays ?: 7)
    } else {
        PendingMissionCard()
    }

    // ⑤ CARD 4 - 오늘 활동 3대 지표 (실시간 워치 데이터)
    TodayActivityCard(
        onGotoActivity = onGotoDetailActivity,
        onGotoStress = onGotoDetailStress,
        refreshKey = activityRefreshKey,
        // [260726 의료안전] 혈당 위험 구간이면 실시간 독려/축하 힌트도 억제
        glucoseUnsafe = glucoseUnsafe
    )
}

/**
 * 오늘 활동 카드 — Health Connect 에서 직접 오늘 데이터 읽음.
 * 어제 데이터는 백엔드 점수 페이지에서, 오늘은 실시간으로 폰에서 직접 읽기.
 */
@Composable
private fun TodayActivityCard(
    onGotoActivity: () -> Unit,
    onGotoStress: () -> Unit,
    // [자동 동기화] 홈 진입 동기화로 새 데이터가 올라가면 값이 바뀌어 이 카드를 다시 fetch 하도록 키로 사용.
    refreshKey: Int = 0,
    // [260726 의료안전] 오늘 공복혈당이 위험 구간(breakdown.glucose == 0 → <70 또는 >180)이면 true.
    //   아침 입력 전 화면 등 판정 근거가 없는 호출부는 기본 false(억제 안 함).
    glucoseUnsafe: Boolean = false
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var todaySteps by remember { mutableStateOf<Int?>(null) }
    var todayAvgBpm by remember { mutableStateOf<Int?>(null) }
    var yesterdayAvgBpm by remember { mutableStateOf<Int?>(null) }
    var loading by remember { mutableStateOf(true) }

    // 진입 시 1회 갱신 (refreshKey 변경 시 재-fetch)
    //  • 오늘 걸음수: fetchWatchData(today)
    //  • 어젯밤 잠: fetchWatchData(yesterday)
    //  • 스트레스(어제 평균 BPM): fetchHeartRateHourly(yesterday)
    LaunchedEffect(refreshKey) {
        try {
            val repo = com.dangbomcare.app.data.health.HealthConnectRepository(context)
            if (repo.isSdkAvailable()) {
                // [코덱스 P1] KST 기준 — 자정 전후 timezone 차이 회피
                val today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))
                val yesterday = today.minusDays(1)
                val todayData = repo.fetchWatchData(today)
                todaySteps = todayData.steps
                // [260623] 어젯밤 잠은 실시간 카드에서 제외 → yesterday 수면 fetch 불필요 (제거)
                // [우노] 스트레스 = '오늘' 우선. 오늘 심박 있으면 오늘, 없으면 어제로 fallback.
                //   값과 라벨의 시점을 일치시키려고 오늘/어제 평균 심박을 따로 저장 (라벨은 UI에서 결정).
                val todayHr = repo.fetchHeartRateHourly(today).filterNotNull().filter { it > 0 }
                val yestHr = repo.fetchHeartRateHourly(yesterday).filterNotNull().filter { it > 0 }
                todayAvgBpm = if (todayHr.isNotEmpty()) todayHr.average().toInt() else null
                yesterdayAvgBpm = if (yestHr.isNotEmpty()) yestHr.average().toInt() else null
            }
        } catch (e: Exception) {
            // 권한 없거나 HC 미설치 → null 유지
        }
        loading = false
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, Divider)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("실시간 활동 보기", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Text("워치 데이터를 직접 가져옵니다", fontSize = 10.sp, color = TextSecondary)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(if (loading) TextSecondary else Primary))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (loading) "로딩 중" else if (todaySteps != null) "실시간" else "동기화 필요",
                        fontSize = 10.sp,
                        color = TextSecondary
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                MetricMini(
                    emoji = "🚶",
                    // [우노] 걸음 수는 워치 없이 폰으로도 측정됨 → "미착용" 금지. 데이터 없으면 0으로 표기.
                    value = if (loading) "측정 중" else (todaySteps?.let { "%,d".format(it) } ?: "0"),
                    label = "오늘의 걸음 수",
                    // [피드백 260714] 원 채워짐 기준을 활동 점수 만점(8,000보)과 통일 — 이전엔 10,000 기준이라 9,126보 걸어도 91%만 채워지던 버그.
                    ratio = (todaySteps?.let { (it / 8000f).coerceIn(0f, 1f) }) ?: 0f,
                    color = Primary,
                    onClick = onGotoActivity,
                    modifier = Modifier.weight(1f)
                )
                // [260623 피드백] '어젯밤 잠'은 실시간이 아닌 고정값(어제 수면) → 실시간 활동 카드에서 제외.
                //   수면은 점수 상세/수면 상세 화면에서 확인. (lastNightSleepHours는 다른 곳 미사용)
                // 당봄 피드백 — "적정"이라고만 표시하지 말고 실제 stress 상태 + 평균 BPM
                // 평균 BPM → 라벨 매핑 (DetailScreens.stressLevelFromHr과 동일 룰)
                // 다른 카드와 동일하게 한 줄 value로 유지 (다중 줄이면 layout 어긋남)
                // [우노] 스트레스 — '오늘' 우선, 없으면 '어제'. 둘 다 없으면 '오늘 스트레스 / 미착용'.
                //   값·라벨 시점을 일치시키고, 보기 나쁜 "—" 대신 "미착용"으로 표기.
                val stressBpm = todayAvgBpm ?: yesterdayAvgBpm
                val stressWord = when {
                    stressBpm == null || stressBpm <= 0 -> null
                    stressBpm < 65 -> "편안함"
                    stressBpm < 75 -> "낮음"
                    stressBpm < 85 -> "보통"
                    else -> "높음"
                }
                val isYesterdayStress = todayAvgBpm == null && yesterdayAvgBpm != null
                // [UI] 레벨 + bpm 을 한 줄 value 로 합침 ("낮음 · 69 bpm") → 걸음·잠과 동일하게
                //   [value 한 줄] + [label 한 줄] 구조라 세 칸의 라벨이 같은 높이에 정렬됨.
                //   긴 칸 폭에서도 안 잘리도록 MetricMini 의 value 는 autoSize 로 shrink-to-fit.
                val stressValue = when {
                    stressWord != null && stressBpm != null -> "$stressWord · $stressBpm bpm"
                    loading -> "측정 중"
                    else -> "미착용"
                }
                val stressLabel = if (isYesterdayStress) "전날 스트레스" else "오늘 스트레스"
                MetricMini(
                    emoji = "😌",
                    value = stressValue,
                    label = stressLabel,
                    ratio = 1f,
                    color = LimeAccent,
                    onClick = onGotoStress,
                    isFilled = true,
                    modifier = Modifier.weight(1f)
                )
            }

            // 실시간 점수 유도 멘트 (당봄 #12)
            // [260726 의료안전] 오늘 혈당이 위험 구간이면 "식후 산책으로 시작해보세요!" 같은 운동 독려·축하 문구를
            //   띄우지 않는다. 백엔드가 안전상태 비정상일 때 미션/격려를 억제하는 것과 동일한 방어.
            if (!glucoseUnsafe) todaySteps?.let { steps ->
                Spacer(Modifier.height(12.dp))
                RealtimeScoreHintCard(steps = steps)
            }
        }
    }
}

/**
 * 실시간 점수 유도 멘트 카드 (당봄 #12).
 * 현재 걸음 수 기반 잔여 점수 안내 — 시간대별로 톤 차등 적용.
 * milestone_service.realtime_score_hint와 동일 로직 (클라이언트 측 계산).
 */
@Composable
private fun RealtimeScoreHintCard(steps: Int) {
    val now = java.time.LocalTime.now().hour
    val hint = computeRealtimeHint(steps, now) ?: return

    val (bgColor, textColor) = when (hint.tone) {
        "celebrate" -> LimeLight to PrimaryDark
        "encourage" -> BgCardSoft to TextPrimary
        else -> BgCardSoft to TextSecondary
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = bgColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (hint.tone == "celebrate") "🎯" else "💪",
                fontSize = 18.sp
            )
            Spacer(Modifier.width(8.dp))
            Text(
                hint.message,
                fontSize = 12.sp,
                color = textColor,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 17.sp
            )
        }
    }
}

private data class RealtimeHintData(val message: String, val tone: String)

private fun computeRealtimeHint(currentSteps: Int, currentHour: Int, goal: Int = 8000): RealtimeHintData? {
    fun stepsToBaseScore(s: Int): Int = when {
        s >= 8000 -> 10
        s >= 6000 -> 8
        s >= 4000 -> 6
        s >= 2000 -> 3
        else -> 0
    }
    val curBase = stepsToBaseScore(currentSteps)
    val remainingToGoal = (goal - currentSteps).coerceAtLeast(0)
    val potentialGain = 10 - curBase

    return when {
        currentSteps >= goal -> RealtimeHintData(
            "🏆 오늘 목표 달성! 활동 점수 만점이에요",
            "celebrate"
        )
        currentHour >= 21 -> RealtimeHintData(
            "오늘 %,d보 걸으셨어요. 내일 다시 도전해보세요 😊".format(currentSteps),
            "rest"
        )
        curBase == 0 -> RealtimeHintData(
            "오늘 활동이 아직 적어요. 식후 산책으로 시작해보세요!",
            "encourage"
        )
        remainingToGoal <= 500 -> RealtimeHintData(
            "%,d보만 더 걸으면 만점!".format(remainingToGoal),
            "celebrate"
        )
        else -> RealtimeHintData(
            "지금까지 %,d보 · %,d보만 더 걸으면 +%d점 올라가요".format(currentSteps, remainingToGoal, potentialGain),
            "encourage"
        )
    }
}

@Composable
private fun EncouragementBanner(change: Int, weeklyStreak: Int) {
    val (emoji, title, sub) = when {
        change > 0 -> Triple("🏆", "전날보다 +${change}점, 훌륭해요!", "꾸준히 관리하고 계세요 🔥")
        change < 0 -> Triple("💪", "전날보다 ${change}점, 괜찮아요", "내일은 더 잘할 수 있어요")
        else -> Triple("👍", "전날과 같은 점수예요", "꾸준한 게 가장 중요해요")
    }
    // 점수 상승 시 spring scale 애니메이션 (등장 효과)
    var visible by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) { visible = true }
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (visible) 1f else 0.8f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessLow
        ),
        label = "banner-scale"
    )
    val alpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 350),
        label = "banner-alpha"
    )
    // 점수 카운트업 (+7 → 0에서 7까지)
    val animatedChange by androidx.compose.animation.core.animateIntAsState(
        targetValue = if (visible) change else 0,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 700, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "banner-change"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Row(
            modifier = Modifier
                .background(Brush.horizontalGradient(listOf(LimeAccent, Color(0xFFB5DD6F), LimeAccent)))
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(44.dp).clip(CircleShape).background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                Text(emoji, fontSize = 22.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Black, color = Color(0xFF0A2614))
                Text(sub, fontSize = 11.sp, color = PrimaryDark)
            }
            if (change != 0) {
                Text(
                    if (animatedChange > 0) "+$animatedChange" else "$animatedChange",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                    color = Color(0xFF0A2614)
                )
            }
        }
    }
}

@Composable
private fun LifestyleScoreCard(score: ScoreTodayResponse, onClick: () -> Unit = {}) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, Divider)
    ) {
        Column(
            modifier = Modifier.padding(20.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("전날 하루의 종합 점수", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Text("전날 생활을 평가한 점수예요", fontSize = 10.sp, color = TextSecondary)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("점수 상세", fontSize = 12.sp, color = Primary, fontWeight = FontWeight.SemiBold)
                    Icon(
                        imageVector = Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = Primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            // 도넛 차트
            ScoreDonut(score = score.totalScore, label = score.label, size = 144.dp)

            Spacer(Modifier.height(16.dp))

            // 7일 미니 바차트 (TODO: 실제 데이터 받아오면 교체. 지금은 단순 표시)
            // 임시: 오늘 점수만 강조
            WeeklyMiniChart(todayScore = score.totalScore.toInt())

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(10.dp))

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "이번 주 평균 ${score.weeklyAvg?.toInt() ?: "-"}점",
                    fontSize = 11.sp,
                    color = TextSecondary,
                    modifier = Modifier.weight(1f)
                )
                if (score.yesterdayScore != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // TrendingUp icon deprecated → 한글 화살표 텍스트로 대체 (AutoMirrored 없음)
                        val diff = score.totalScore - score.yesterdayScore
                        val diffText = if (diff % 1.0 == 0.0) "${diff.toInt()}" else "$diff"
                        Text(
                            if (diff >= 0) "▲" else "▼",
                            fontSize = 10.sp,
                            color = if (diff >= 0) Primary else ErrorRed
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(
                            "전날 ${if (diff >= 0) "+" else ""}$diffText",
                            fontSize = 11.sp,
                            color = if (diff >= 0) Primary else ErrorRed,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ScoreDonut(score: Double, label: String, size: androidx.compose.ui.unit.Dp) {
    // [260623] 소화 2.5점 → 총점 소수 가능. 정수면 정수로 표시.
    val scoreText = if (score % 1.0 == 0.0) "${score.toInt()}" else "$score"
    // 접근성: TalkBack 등 스크린 리더용 요약 (시니어 사용자 대상 - 코덱스 H)
    Box(
        modifier = Modifier
            .size(size)
            .semantics {
                contentDescription = "전날 하루 종합 점수 ${scoreText}점, $label"
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val stroke = 12.dp.toPx()
            val pad = stroke / 2
            // 배경 원
            drawArc(
                color = Color(0xFFE4E2E1),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(pad, pad),
                size = Size(this.size.width - stroke, this.size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            // 점수 원호 — [P1] 0~100 clamp (혹시 비정상 값 들어와도 호 안 깨짐)
            val clampedScore = score.coerceIn(0.0, 100.0)
            val sweep = (clampedScore.toFloat() / 100f) * 360f
            val color = when {
                clampedScore >= 70.0 -> Primary
                clampedScore >= 50.0 -> ScoreOk
                else -> ScoreLow
            }
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(pad, pad),
                size = Size(this.size.width - stroke, this.size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    scoreText,
                    fontSize = 42.sp,
                    fontWeight = FontWeight.Black,
                    color = Primary
                )
                Text("점", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Primary)
            }
            Spacer(Modifier.height(2.dp))
            val labelEmoji = when {
                score >= 70.0 -> "🟢"
                score >= 50.0 -> "🟡"
                else -> "🔴"
            }
            Surface(
                color = LimeLight,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    "$labelEmoji $label",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = PrimaryDark,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp)
                )
            }
        }
    }
}

/**
 * ⌚ 워치 동기화 필요 배너 (홈 최상단).
 *  • 워치 읽기 권한이 풀린 상태(예: 폰 OS 업데이트로 헬스 권한 초기화)에서만 표시.
 *  • 탭 → 설정에 안 가고 홈에서 바로 권한 재요청 + 동기화 (SettingsScreen.WatchSyncControl 재사용).
 *  • 동기화로 권한 복구되면 재확인 후 자동으로 사라지고 onSynced로 홈 새로고침.
 */
/**
 * [260821] 삼성헬스 배터리 최적화 안내.
 *
 * 왜 필요한가: 걸음 데이터의 실제 출처는 삼성헬스다. 우리 앱만 최적화 예외로 만들어도
 *   삼성헬스가 백그라운드에서 종료되면 걸음 기록 자체가 생기지 않아 9보·58보 같은 값이 남는다.
 *
 * ⚠️ 우리가 대신 꺼줄 수 없다(OS 차단). 목록 화면까지만 열어주고 나머지는 사용자가 해야 하므로,
 *   무엇을 눌러야 하는지 단계로 보여준다. '완료했어요'를 누르면 다시 뜨지 않는다
 *   — 다른 앱 상태는 정확히 확인할 수 없어서, 사용자의 확인을 신뢰하는 수밖에 없다.
 */
@Composable
private fun HomeSamsungHealthBatteryNotice() {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var show by remember {
        mutableStateOf(com.dangbomcare.app.data.health.BatteryOptimization.shouldShowSamsungHealthPrompt(context))
    }
    var showGuide by remember { mutableStateOf(false) }

    // [260821] 설정 화면에 다녀오면 삼성헬스 상태를 다시 확인해, '제한 없음'으로 바뀌었으면
    //   '완료했어요'를 누르지 않아도 배너가 저절로 사라진다.
    //   ⚠️ 다른 앱의 최적화 상태 조회는 기기/롬에 따라 항상 false 를 주거나 예외가 날 수 있다.
    //     그래서 true 로 바뀐 것만 '해제됨'의 근거로 쓰고(양성일 때만 신뢰),
    //     false 라고 해서 배너를 다시 띄우거나 유지 판단을 뒤집지는 않는다.
    //     조회가 안 되는 기기에서는 기존대로 '완료했어요'가 닫는 수단으로 남는다.
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME && show) {
                if (com.dangbomcare.app.data.health.BatteryOptimization.isSamsungHealthIgnoring(context)) {
                    com.dangbomcare.app.data.health.BatteryOptimization.markSamsungHealthPrompted(context)
                    show = false
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    if (!show) return

    if (showGuide) {
        AlertDialog(
            onDismissRequest = { showGuide = false },
            icon = { Text("👟", fontSize = 28.sp) },
            title = { Text("삼성헬스 설정 방법", fontSize = 17.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "걸음 수는 삼성헬스가 기록해요. 삼성헬스도 절전에서 빼주셔야 걸음이 빠지지 않아요.",
                        fontSize = 14.sp, lineHeight = 21.sp, color = TextSecondary
                    )
                    Spacer(Modifier.height(14.dp))
                    listOf(
                        "설정 화면이 열리면 목록에서 삼성헬스를 찾으세요",
                        "삼성헬스를 누르고 '최적화 안 함'을 선택하세요",
                        "설정을 마치고 앱으로 돌아오면 안내가 사라져요",
                    ).forEachIndexed { i, s ->
                        Row(modifier = Modifier.padding(vertical = 3.dp)) {
                            Text("${i + 1}.", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Primary,
                                modifier = Modifier.padding(end = 8.dp))
                            Text(s, fontSize = 14.sp, lineHeight = 20.sp, color = TextPrimary)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showGuide = false
                        com.dangbomcare.app.data.health.BatteryOptimization
                            .openSamsungHealthBatterySettings(context)
                    },
                    shape = RoundedCornerShape(12.dp)
                ) { Text("설정 열기", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                // [260821] '완료했어요'(영구 닫기) → '닫기'(이번만).
                //   실제로 해제하지 않았는데 눌러서 안내가 영영 사라지는 부작용이 있었다.
                //   해제 여부는 ON_RESUME 자동 감지가 판단하므로, 여기서는 플래그를 저장하지 않는다.
                //   → 해결될 때까지 앱을 다시 켤 때마다 배너가 뜬다.
                TextButton(onClick = { showGuide = false }) { Text("닫기", color = TextSecondary) }
            },
            shape = RoundedCornerShape(20.dp),
            containerColor = BgPrimary
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth().clickable { showGuide = true },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFFB74D))
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("👟", fontSize = 24.sp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("걸음 수가 잘 안 들어오나요?", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Spacer(Modifier.height(2.dp))
                Text("삼성헬스 절전 설정을 바꾸면 해결돼요 · 눌러서 방법 보기",
                    fontSize = 12.sp, color = TextSecondary, lineHeight = 16.sp)
            }
            Text("→", fontSize = 20.sp, color = Primary)
        }
    }
}

/**
 * [260812] 배터리 최적화 배너 + 최초 1회 팝업.
 *
 * 왜 두 겹인가:
 *   • 팝업(1회) — 기존 사용자는 온보딩을 이미 지나서 업데이트만으로는 아무것도 안 보인다.
 *     첫 진입 때 한 번은 확실히 알려야 한다. 이미 안내했으면 다시 뜨지 않는다.
 *   • 배너(상시) — 배터리 최적화는 시스템 설정이라 앱이 잠글 수 없다. 사용자가 나중에 다시 켜면
 *     조용히 동기화가 밀리기 시작하므로, 꺼진 동안에는 배너로 계속 알리고 눌러서 복구하게 한다.
 *
 * 허용된 상태면 팝업도 배너도 아예 렌더하지 않는다.
 */
@Composable
private fun HomeBatteryOptimizationNotice() {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var ignoring by remember {
        mutableStateOf(com.dangbomcare.app.data.health.BatteryOptimization.isIgnoring(context))
    }
    var showDialog by remember {
        mutableStateOf(com.dangbomcare.app.data.health.BatteryOptimization.shouldShowOneTimePrompt(context))
    }
    // 시스템 설정에서 바꾸고 돌아오면 즉시 반영 (허용했으면 배너가 사라져야 한다)
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                ignoring = com.dangbomcare.app.data.health.BatteryOptimization.isIgnoring(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    if (ignoring) return

    if (showDialog) {
        AlertDialog(
            onDismissRequest = {
                // 바깥 터치로 닫아도 '안내함'으로 기록 — 매번 뜨면 성가시다. 대신 배너가 남는다.
                com.dangbomcare.app.data.health.BatteryOptimization.markPrompted(context)
                showDialog = false
            },
            icon = { Text("🔋", fontSize = 28.sp) },
            title = { Text("자동 저장을 켜주세요", fontSize = 17.sp, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "휴대폰 배터리 절약 기능 때문에 걸음·수면이 늦게 들어오거나 빠질 수 있어요.\n\n허용하시면 앱을 켜지 않아도 자동으로 저장돼요.",
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                    color = TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        com.dangbomcare.app.data.health.BatteryOptimization.markPrompted(context)
                        showDialog = false
                        com.dangbomcare.app.data.health.BatteryOptimization.request(context)
                    },
                    shape = RoundedCornerShape(12.dp)
                ) { Text("허용하기", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = {
                    com.dangbomcare.app.data.health.BatteryOptimization.markPrompted(context)
                    showDialog = false
                }) { Text("나중에", color = TextSecondary) }
            },
            shape = RoundedCornerShape(20.dp),
            containerColor = BgPrimary
        )
    }

    // 배너 — 꺼져 있는 동안 계속 노출. 허용하면 ON_RESUME 에서 ignoring=true 가 되어 사라진다.
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { com.dangbomcare.app.data.health.BatteryOptimization.request(context) },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFFB74D))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("🔋", fontSize = 24.sp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("자동 저장이 꺼져 있어요", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Spacer(Modifier.height(2.dp))
                Text(
                    "걸음·수면이 빠질 수 있어요 · 눌러서 허용하기",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    lineHeight = 16.sp
                )
            }
            Text("→", fontSize = 20.sp, color = Primary)
        }
    }
}

@Composable
private fun HomeWatchSyncBanner(onSynced: () -> Unit = {}) {
    val healthRepo = remember { com.dangbomcare.app.data.ServiceLocator.healthConnectRepository }
    val recheck = remember { mutableStateOf(0) }
    val needsSync = remember { mutableStateOf(false) }
    LaunchedEffect(recheck.value) {
        needsSync.value = try {
            !kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { healthRepo.canReadWatchData() }
        } catch (_: Exception) { false }
    }
    if (!needsSync.value) return

    WatchSyncControl(onSynced = { recheck.value++; onSynced() }) { busy, statusMsg, onTap ->
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !busy) { onTap() },
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFFB74D))
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("⌚", fontSize = 24.sp)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("워치 데이터를 가져오려면 눌러주세요", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (busy) statusMsg else "폰 업데이트 등으로 동기화가 풀렸을 수 있어요 · 눌러서 다시 연결",
                        fontSize = 12.sp,
                        color = if (statusMsg.startsWith("⚠️") || statusMsg.startsWith("❌")) ErrorRed else TextSecondary,
                        lineHeight = 16.sp
                    )
                }
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Primary)
                } else {
                    Text("🔄", fontSize = 20.sp)
                }
            }
        }
    }
}

@Composable
private fun WeeklyMiniChart(todayScore: Int) {
    // 월~일 7일 표시 (리포트/상세 화면과 동일 기준). 오늘은 강조, 일요일은 빨강.
    // 실제 데이터는 백엔드 scoreRepository.list로 조회
    val today = remember { java.time.LocalDate.now() }
    // 이번 주 월요일 시작 (리포트/상세 화면과 동일 기준)
    val daysOfWeek = remember(today) {
        val mondayOfWeek = today.with(java.time.DayOfWeek.MONDAY)
        (0..6).map { mondayOfWeek.plusDays(it.toLong()) }
    }
    val labels = listOf("월", "화", "수", "목", "금", "토", "일")

    // 백엔드에서 일별 점수 조회
    var scoresByDate by remember { mutableStateOf<Map<java.time.LocalDate, Int>>(emptyMap()) }
    LaunchedEffect(daysOfWeek[0]) {
        try {
            val from = daysOfWeek[0].format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
            val to = daysOfWeek[6].format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
            when (val res = com.dangbomcare.app.data.ServiceLocator.scoreRepository.list(from, to)) {
                is com.dangbomcare.app.data.repo.ApiResult.Success ->
                    scoresByDate = res.data.mapNotNull { item ->
                        try {
                            java.time.LocalDate.parse(item.scoreDate) to ((item.totalScore ?: 0.0).toInt())
                        } catch (e: Exception) { null }
                    }.toMap()
                else -> {}
            }
        } catch (e: Exception) { /* ignore */ }
    }

    // 당봄 #1: 박스 → 원형 게이지 + 점수만 표시
    // 좁은 화면(폴드 커버 ~320dp)에서 36dp 원 7개가 잘리지 않게 셀 폭에 맞춰 축소
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
    val ringSize = ((maxWidth - 4.dp * 6) / 7).coerceAtMost(36.dp)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        daysOfWeek.forEachIndexed { i, date ->
            val score = if (date == today) todayScore else (scoresByDate[date] ?: 0)
            val isToday = date == today
            val isFuture = date.isAfter(today)
            val ringColor = when {
                isFuture || score == 0 -> Color(0xFFE4E2E1)  // 미입력·미래
                score >= 70 -> Primary
                score >= 50 -> ScoreOk
                else -> ScoreLow
            }
            // [260726 접근성] Color.kt 규정 — 노랑 원색(ScoreOk)은 흰 배경 대비 <3:1 이라 '글자'엔 못 쓴다.
            //   원호(Canvas)엔 ringColor 원색을 그대로 두고, 가운데 숫자 글자만 진한 앰버로 교체.
            val scoreTextColor = if (ringColor == ScoreOk) WarningText else ringColor
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 작은 원형 게이지 (점수만큼 차오름)
                Box(
                    modifier = Modifier.size(ringSize),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.foundation.Canvas(modifier = Modifier.size(ringSize)) {
                        val stroke = 3.dp.toPx()
                        val pad = stroke / 2
                        // 배경 원
                        drawArc(
                            color = Color(0xFFE4E2E1),
                            startAngle = -90f,
                            sweepAngle = 360f,
                            useCenter = false,
                            topLeft = Offset(pad, pad),
                            size = Size(this.size.width - stroke, this.size.height - stroke),
                            style = Stroke(width = stroke, cap = StrokeCap.Round)
                        )
                        // 점수만큼 채우는 원호
                        if (!isFuture && score > 0) {
                            val sweep = (score.coerceIn(0, 100) / 100f) * 360f
                            drawArc(
                                color = ringColor,
                                startAngle = -90f,
                                sweepAngle = sweep,
                                useCenter = false,
                                topLeft = Offset(pad, pad),
                                size = Size(this.size.width - stroke, this.size.height - stroke),
                                style = Stroke(width = stroke, cap = StrokeCap.Round)
                            )
                        }
                    }
                    // 점수만 표시 (미래·미입력은 "-")
                    Text(
                        when {
                            isFuture -> ""
                            score == 0 -> "-"
                            else -> "$score"
                        },
                        fontSize = if (isToday) 13.sp else 11.sp,
                        fontWeight = if (isToday) FontWeight.Black else FontWeight.SemiBold,
                        color = if (isFuture) TextDisabled else scoreTextColor
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    if (isToday) "오늘" else labels[i],
                    fontSize = 10.sp,
                    color = if (isToday) Primary else if (i == 6) ErrorRed.copy(alpha = 0.7f) else TextSecondary,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
    }  // BoxWithConstraints 끝
}

@Composable
private fun AiInterpretationCard(
    message: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFD9E2E6))
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(32.dp).clip(CircleShape).background(Slate),
                    contentAlignment = Alignment.Center
                ) {
                    Text("🤖", fontSize = 16.sp)
                }
                Spacer(Modifier.width(8.dp))
                Text("오늘의 AI 피드백", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1A2326))
                Spacer(Modifier.weight(1f))
                Text("방금 분석", fontSize = 10.sp, color = TextSecondary)
            }
            Spacer(Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = BgPrimary)
            ) {
                Text(
                    message,
                    fontSize = 14.sp,
                    color = TextPrimary,
                    lineHeight = 22.sp,
                    modifier = Modifier.padding(14.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("전날 데이터 자세히 보기", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Slate)
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = Slate, modifier = Modifier.size(18.dp))
            }
            // [260730] AI 재생성 카운터('수정 N회 남음')는 홈에서 제거 — 회고/아침 입력 화면에만 남긴다.
            //   홈은 결과를 보는 곳이라 수정 횟수 노출이 불필요하고 시선을 분산시켰다.
        }
    }
}

@Composable
private fun TodayMissionCard(
    mission: com.dangbomcare.app.data.api.MissionInfo,
    streakDays: Int,
    streakTotal: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, Divider)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🎯", fontSize = 18.sp)
                Spacer(Modifier.width(6.dp))
                Text("오늘의 미션", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary, modifier = Modifier.weight(1f))
                Surface(color = LimeLight, shape = RoundedCornerShape(12.dp)) {
                    Text(
                        "🔥 ${streakDays}/${streakTotal}일",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = PrimaryDark,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            // [사용자 피드백] 미션은 2~3가지 행동 묶음(\n 구분).
            //   [260730] 각 행동을 앱 표준 글머리기호 "•"로 렌더해 다른 화면(BulletText 등)과 통일.
            //   GPT/캐시가 섞어 넣은 기존 기호(●·-* 등)는 벗겨내고 "•"로 다시 붙인다.
            val _missionText = mission.text ?: "오늘의 미션"
            val _actions = _missionText.split("\n")
                .map { it.trim().trimStart('●', '•', '·', '-', '*', '▶', '‣', '·').trim() }
                .filter { it.isNotBlank() }
            if (_actions.size >= 2) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    _actions.forEach { act ->
                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                "•",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Text(
                                act,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                lineHeight = 24.sp
                            )
                        }
                    }
                }
            } else {
                Text(
                    _actions.firstOrNull() ?: _missionText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    lineHeight = 24.sp
                )
            }

            // 진행률 — 백엔드 mission_progress는 0.0~1.0 (fraction). 바는 그대로, 라벨은 ×100.
            if (mission.progress > 0) {
                val frac = mission.progress.toFloat().coerceIn(0f, 1f)
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { frac },
                    modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)),
                    color = if (mission.completed) Success else LimeAccent,
                    trackColor = BgCardSoft
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (mission.completed) "✅ 달성!" else "진행률 ${(frac * 100).toInt()}% (목표 70% 이상)",
                    fontSize = 11.sp,
                    color = if (mission.completed) Success else TextSecondary
                )
            }

            mission.tip?.let { tip ->
                Spacer(Modifier.height(12.dp))
                Surface(color = LimeLight.copy(alpha = 0.5f), shape = RoundedCornerShape(10.dp)) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        // [260722] 아이콘 색을 노란(앰버)으로. 백엔드 tip 문구 앞의 "💡" 이모지 중복 제거.
                        Icon(
                            Icons.Filled.TipsAndUpdates, null,
                            tint = androidx.compose.ui.graphics.Color(0xFFFFB300),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            tip.trim().removePrefix("💡").trim(),
                            fontSize = 12.sp, color = TextPrimary, lineHeight = 18.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun YesterdayActivityCard(
    onGotoActivity: () -> Unit,
    onGotoSleep: () -> Unit,
    onGotoStress: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, Divider)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("전날 활동", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary, modifier = Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(Primary))
                    Spacer(Modifier.width(4.dp))
                    Text("동기화됨", fontSize = 10.sp, color = TextSecondary)
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                MetricMini(
                    emoji = "🚶",
                    value = "7,234",
                    label = "걸음",
                    ratio = 0.72f,
                    color = Primary,
                    onClick = onGotoActivity
                )
                MetricMini(
                    emoji = "😴",
                    value = "7시간",
                    label = "수면",
                    ratio = 0.85f,
                    color = Slate,
                    onClick = onGotoSleep
                )
                MetricMini(
                    emoji = "😌",
                    value = "적정",
                    label = "스트레스",
                    ratio = 1f,
                    color = LimeAccent,
                    onClick = onGotoStress,
                    isFilled = true
                )
            }
        }
    }
}

@Composable
private fun MetricMini(
    emoji: String,
    value: String,
    label: String,
    ratio: Float,
    color: Color,
    onClick: () -> Unit,
    isFilled: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(
        // [UI] 호출부에서 weight(1f)로 3등분 → 서클이 각 칸 가운데(전체 1/3 지점)에 정확히 정렬
        modifier = modifier.clickable { onClick() }.padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(modifier = Modifier.size(64.dp), contentAlignment = Alignment.Center) {
            if (isFilled) {
                Box(modifier = Modifier.fillMaxSize().clip(CircleShape).background(LimeLight))
            } else {
                Canvas(modifier = Modifier.size(64.dp)) {
                    val stroke = 6.dp.toPx()
                    val pad = stroke / 2
                    drawArc(
                        color = Color(0xFFE4E2E1),
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = Offset(pad, pad),
                        size = Size(this.size.width - stroke, this.size.height - stroke),
                        style = Stroke(width = stroke, cap = StrokeCap.Round)
                    )
                    drawArc(
                        color = color,
                        startAngle = -90f,
                        sweepAngle = ratio * 360f,
                        useCenter = false,
                        topLeft = Offset(pad, pad),
                        size = Size(this.size.width - stroke, this.size.height - stroke),
                        style = Stroke(width = stroke, cap = StrokeCap.Round)
                    )
                }
            }
            Text(emoji, fontSize = 22.sp)
        }
        Spacer(Modifier.height(6.dp))
        // [UI] 값 = 항상 한 줄. autoSize(14sp→8sp)로 긴 값("편안함 · 99 bpm")만 자동 축소.
        //   ⚠️ autoSize는 값마다 글자 크기가 달라져 높이가 미세하게 달라짐 → 라벨이 어긋남.
        //   그래서 '고정 높이 박스'에 세로 중앙 정렬 → 세 칸의 라벨 시작 높이가 항상 동일.
        Box(modifier = Modifier.fillMaxWidth().height(30.dp), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.text.BasicText(
                text = value,
                modifier = Modifier.fillMaxWidth(),
                style = androidx.compose.ui.text.TextStyle(
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                ),
                maxLines = 1,
                softWrap = false,
                overflow = androidx.compose.ui.text.style.TextOverflow.Visible,
                autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(
                    minFontSize = 8.sp,
                    maxFontSize = 14.sp,
                    stepSize = 0.5.sp
                )
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 10.sp, color = TextSecondary, maxLines = 1,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/**
 * 실시간 점수 유도 멘트 카드 (당봄 #12).
 * 현재 걸음 수 + 시각 → 잔여 점수 + 행동 권유 자동 생성.
 */
@Composable
fun RealtimeScoreHintCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var currentSteps by remember { mutableStateOf<Int?>(null) }
    val currentHour = remember { java.time.LocalTime.now().hour }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        try {
            val repo = com.dangbomcare.app.data.health.HealthConnectRepository(context)
            if (repo.isSdkAvailable()) {
                val data = repo.fetchWatchData(java.time.LocalDate.now())
                currentSteps = data.steps
            }
        } catch (_: Exception) { /* 데이터 없으면 카드 안 보임 */ }
    }

    val steps = currentSteps ?: return  // 데이터 없으면 카드 표시 안 함

    // 점수 계산 (steps_score 로직과 동일)
    val baseScore = when {
        steps >= 8000 -> 10
        steps >= 6000 -> 8
        steps >= 4000 -> 6
        steps >= 2000 -> 3
        else -> 0
    }
    val maxScore = 10
    val potentialGain = maxScore - baseScore
    val goalSteps = 8000
    val remaining = maxOf(0, goalSteps - steps)

    // 시간대별 차등 메시지
    val (emoji, message, tone) = when {
        steps >= goalSteps -> Triple(
            "🏆",
            "오늘 목표 달성! 활동 점수 만점이에요",
            ToneStyle.CELEBRATE
        )
        currentHour >= 21 -> Triple(
            "🌙",
            "오늘 ${"%,d".format(steps)}보 걸으셨어요. 내일 다시 도전해보세요",
            ToneStyle.REST
        )
        baseScore == 0 -> Triple(
            "🚶",
            "오늘 활동이 아직 적어요. 6,000보까지 약 ${"%,d".format(6000 - steps)}보 남았어요",
            ToneStyle.ENCOURAGE
        )
        remaining <= 500 -> Triple(
            "🎯",
            "${"%,d".format(remaining)}보만 더 걸으면 만점!",
            ToneStyle.CELEBRATE
        )
        else -> Triple(
            "💪",
            "지금까지 ${"%,d".format(steps)}보 · ${"%,d".format(remaining)}보만 더 걸으면 +${potentialGain}점 올라가요",
            ToneStyle.ENCOURAGE
        )
    }

    val (bgColor, borderColor, textColor) = when (tone) {
        ToneStyle.CELEBRATE -> Triple(Color(0xFFFFE08A).copy(alpha = 0.3f), Color(0xFFFF9500), Color(0xFF6B3D00))
        ToneStyle.ENCOURAGE -> Triple(Primary.copy(alpha = 0.08f), Primary.copy(alpha = 0.4f), PrimaryDark)
        ToneStyle.REST -> Triple(BgCardSoft, Divider, TextPrimary)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(emoji, fontSize = 28.sp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "실시간 활동 점수",
                    fontSize = 11.sp,
                    color = textColor.copy(alpha = 0.7f),
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    message,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor,
                    lineHeight = 18.sp
                )
                Spacer(Modifier.height(8.dp))
                // 진행률 막대
                LinearProgressIndicator(
                    progress = { (steps.toFloat() / goalSteps).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = borderColor,
                    trackColor = BgCardSoft
                )
            }
        }
    }
}

private enum class ToneStyle { CELEBRATE, ENCOURAGE, REST }
