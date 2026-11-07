@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.dangbomcare.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.dangbomcare.app.data.ServiceLocator
import com.dangbomcare.app.data.api.UserSettingsRequest
import com.dangbomcare.app.data.api.UserUpdateRequest
import com.dangbomcare.app.data.health.HealthConnectRepository
import com.dangbomcare.app.ui.components.BigButton
import com.dangbomcare.app.ui.components.ChoicePill
import com.dangbomcare.app.ui.theme.*
import kotlinx.coroutines.launch
import java.time.LocalDate

// ==============================================
// 가입 단계 — 계정 정보 (OAuth/이메일 인증 후 첫 화면)
// 이름 / 성별 / 생년월일
// ==============================================
@Composable
fun AccountInfoScreen(
    onNext: () -> Unit,
    onBack: () -> Unit
) {
    var name by rememberSaveable { mutableStateOf("") }
    var gender by rememberSaveable { mutableStateOf<String?>(null) }
    // 사용자 피드백 — 생년월일을 picker 대신 8자리 키패드 입력 (yyyyMMdd)
    var birthRaw by rememberSaveable { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val imeVisible = WindowInsets.isImeVisible
    val today = remember { LocalDate.now() }

    // 8자리 파싱 + 검증
    val parsedBirth: LocalDate? = remember(birthRaw) {
        if (birthRaw.length != 8) null
        else runCatching {
            LocalDate.of(
                birthRaw.substring(0, 4).toInt(),
                birthRaw.substring(4, 6).toInt(),
                birthRaw.substring(6, 8).toInt()
            )
        }.getOrNull()
    }
    val ageOK = parsedBirth?.let { b ->
        val years = today.year - b.year -
            if (today.monthValue < b.monthValue || (today.monthValue == b.monthValue && today.dayOfMonth < b.dayOfMonth)) 1 else 0
        years in 1..149 && !b.isAfter(today)
    } ?: false

    // 최초 진입 시 기존 user 정보로 prefill (OAuth provider에서 받아온 이름 등)
    LaunchedEffect(Unit) {
        try {
            val res = ServiceLocator.userRepository.getMe()
            if (res is com.dangbomcare.app.data.repo.ApiResult.Success) {
                val u = res.data
                if (name.isBlank() && !u.name.isNullOrBlank()) name = u.name
                if (gender == null && !u.gender.isNullOrBlank()) gender = u.gender
                if (birthRaw.isBlank() && !u.birthDate.isNullOrBlank()) {
                    try {
                        val d = LocalDate.parse(u.birthDate)
                        birthRaw = "%04d%02d%02d".format(d.year, d.monthValue, d.dayOfMonth)
                    } catch (_: Exception) { /* ignore */ }
                }
            }
        } catch (_: Exception) { /* prefill 실패해도 빈 폼 유지 */ }
    }

    Scaffold(
        containerColor = BgSecondary,
        topBar = { OnboardingTopBar(step = 0, total = 0, onBack = onBack) },  // step 표시 X
        bottomBar = {
            if (!imeVisible) {
                Surface(color = BgSecondary, shadowElevation = 8.dp) {
                    Column(
                        modifier = Modifier
                            .navigationBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                    ) {
                        error?.let {
                            Surface(
                                color = ErrorRed.copy(alpha = 0.1f),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
                            ) {
                                Text(
                                    "⚠️ $it",
                                    color = ErrorRed,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                                )
                            }
                        }
                        BigButton(
                            text = if (saving) "저장 중..." else "다음 →",
                            onClick = {
                                error = null
                                val hasIncompleteHangul = name.any { it.code in 0x3131..0x318E }
                                val isValidNameChars = name.matches(Regex("^[가-힣a-zA-Z\\s]+$"))
                                when {
                                    name.isBlank() -> error = "이름을 입력해주세요"
                                    name.length < 2 -> error = "이름을 2자 이상 입력해주세요"
                                    hasIncompleteHangul || !isValidNameChars -> error = "이름은 한글 또는 영문으로 입력해주세요"
                                    gender == null -> error = "성별을 선택해주세요"
                                    parsedBirth == null -> error = "생년월일 8자리를 정확히 입력해주세요 (예: 19900101)"
                                    !ageOK -> error = "만 1~149세 범위로 입력해주세요"
                                    else -> {
                                        saving = true
                                        scope.launch {
                                            val birthIso = parsedBirth.toString()
                                            val req = UserUpdateRequest(
                                                name = name.trim(),
                                                gender = gender,
                                                birthDate = birthIso
                                            )
                                            val res = ServiceLocator.userRepository.updateMe(req)
                                            saving = false
                                            if (res is com.dangbomcare.app.data.repo.ApiResult.Success) onNext()
                                            else if (res is com.dangbomcare.app.data.repo.ApiResult.Error) error = res.message
                                        }
                                    }
                                }
                            },
                            enabled = !saving
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(4.dp))
            Surface(color = LimeLight, shape = RoundedCornerShape(10.dp)) {
                Text(
                    "계정 정보",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = PrimaryDark,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
            Text("당신을 알려주세요", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Text("이름·성별·생년월일은 점수 산출과 AI 분석에 사용돼요", fontSize = 13.sp, color = TextSecondary)
            Spacer(Modifier.height(4.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = BgCard),
                border = BorderStroke(1.dp, Divider)
            ) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    // [UX] 성별 먼저 (한 번 탭) → 이름 (키보드) → 생년월일 (이름 입력 후 자연 이동)
                    // 성별
                    com.dangbomcare.app.ui.components.LabeledField(label = "성별", required = true) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            // 고정 52dp → 최소 52dp. 큰 글씨(시니어 1.4×)에서도 세로로 안 잘림.
                            ChoicePill("남성", gender == "M", { gender = "M" }, modifier = Modifier.weight(1f).heightIn(min = 52.dp))
                            ChoicePill("여성", gender == "F", { gender = "F" }, modifier = Modifier.weight(1f).heightIn(min = 52.dp))
                        }
                    }

                    // 이름
                    val hasIncompleteHangul = name.any { it.code in 0x3131..0x318E }
                    val isValidNameChars = name.matches(Regex("^[가-힣a-zA-Z\\s]+$"))
                    val (nameState, nameMsg) = when {
                        name.isBlank() -> com.dangbomcare.app.ui.components.ValidationState.Hint to "2~16자, 한글 또는 영문"
                        name.length < 2 -> com.dangbomcare.app.ui.components.ValidationState.Invalid to "2자 이상 입력해주세요."
                        name.length > 16 -> com.dangbomcare.app.ui.components.ValidationState.Invalid to "16자 이하로 입력해주세요."
                        hasIncompleteHangul -> com.dangbomcare.app.ui.components.ValidationState.Invalid to "올바른 이름을 입력해주세요. (예: 이경수)"
                        !isValidNameChars -> com.dangbomcare.app.ui.components.ValidationState.Invalid to "한글 또는 영문만 입력해주세요."
                        else -> com.dangbomcare.app.ui.components.ValidationState.Empty to ""
                    }
                    com.dangbomcare.app.ui.components.LabeledField(
                        label = "이름",
                        required = true,
                        validationState = nameState,
                        validationMessage = nameMsg
                    ) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { if (it.length <= 20) name = it },
                            placeholder = { Text("이름을 입력해 주세요") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            // [v1.0 피드백] 다음 누르면 생년월일로 자동 포커스 이동 (키보드 유지)
                            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )
                    }

                    // 생년월일 — 8자리 키패드 입력 (yyyyMMdd)
                    val (birthState, birthMsg) = when {
                        birthRaw.isBlank() -> com.dangbomcare.app.ui.components.ValidationState.Hint to "8자리 (예: 19900101)"
                        birthRaw.length < 8 -> com.dangbomcare.app.ui.components.ValidationState.Empty to ""
                        parsedBirth == null -> com.dangbomcare.app.ui.components.ValidationState.Invalid to "올바른 날짜가 아니에요"
                        !ageOK -> com.dangbomcare.app.ui.components.ValidationState.Invalid to "만 1~149세 범위로 입력해주세요"
                        else -> com.dangbomcare.app.ui.components.ValidationState.Empty to "%d년 %d월 %d일".format(parsedBirth.year, parsedBirth.monthValue, parsedBirth.dayOfMonth)
                    }
                    com.dangbomcare.app.ui.components.LabeledField(
                        label = "생년월일",
                        required = true,
                        validationState = birthState,
                        validationMessage = birthMsg
                    ) {
                        OutlinedTextField(
                            value = birthRaw,
                            onValueChange = { new ->
                                if (new.length <= 8 && new.all { it.isDigit() }) birthRaw = new
                            },
                            placeholder = { Text("19900101") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// 생년월일 picker — 년/월/일 모두 선택. YearMonthPickerDialog의 확장
@Composable
private fun BirthDatePickerDialog(
    initialYear: Int?,
    initialMonth: Int?,
    initialDay: Int?,
    onConfirm: (Int, Int, Int) -> Unit,
    onDismiss: () -> Unit
) {
    val today = remember { LocalDate.now() }
    val currentYear = today.year
    // [코덱스 P0 #3] 만 1~149세만 허용. 미래 출생 + 150세 이상 차단
    val minYear = currentYear - 149
    val maxYear = currentYear - 1
    var selYear by rememberSaveable { mutableStateOf((initialYear ?: (currentYear - 40)).coerceIn(minYear, maxYear)) }
    var selMonth by rememberSaveable { mutableStateOf(initialMonth ?: 1) }
    var selDay by rememberSaveable { mutableStateOf(initialDay ?: 1) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = BgCard,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    "생년월일 선택",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(Modifier.height(16.dp))

                // 년
                Text("년", fontSize = 12.sp, color = TextSecondary)
                Spacer(Modifier.height(4.dp))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .border(1.dp, Divider, RoundedCornerShape(8.dp))
                ) {
                    items((minYear..maxYear).toList().reversed()) { y ->
                        Surface(
                            color = if (selYear == y) Primary.copy(alpha = 0.15f) else androidx.compose.ui.graphics.Color.Transparent,
                            modifier = Modifier.fillMaxWidth().clickable { selYear = y }
                        ) {
                            Text(
                                "${y}년",
                                fontSize = 14.sp,
                                fontWeight = if (selYear == y) FontWeight.Bold else FontWeight.Normal,
                                color = if (selYear == y) Primary else TextPrimary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 월
                    Column(modifier = Modifier.weight(1f)) {
                        Text("월", fontSize = 12.sp, color = TextSecondary)
                        Spacer(Modifier.height(4.dp))
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                                .border(1.dp, Divider, RoundedCornerShape(8.dp))
                        ) {
                            items((1..12).toList()) { m ->
                                Surface(
                                    color = if (selMonth == m) Primary.copy(alpha = 0.15f) else androidx.compose.ui.graphics.Color.Transparent,
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        selMonth = m
                                        // 월 바뀌면 day 범위 조정
                                        val maxDay = LocalDate.of(selYear, m, 1).lengthOfMonth()
                                        if (selDay > maxDay) selDay = maxDay
                                    }
                                ) {
                                    Text(
                                        "${m}월",
                                        fontSize = 14.sp,
                                        fontWeight = if (selMonth == m) FontWeight.Bold else FontWeight.Normal,
                                        color = if (selMonth == m) Primary else TextPrimary,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                    // 일
                    Column(modifier = Modifier.weight(1f)) {
                        Text("일", fontSize = 12.sp, color = TextSecondary)
                        Spacer(Modifier.height(4.dp))
                        val maxDay = LocalDate.of(selYear, selMonth, 1).lengthOfMonth()
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                                .border(1.dp, Divider, RoundedCornerShape(8.dp))
                        ) {
                            items((1..maxDay).toList()) { d ->
                                Surface(
                                    color = if (selDay == d) Primary.copy(alpha = 0.15f) else androidx.compose.ui.graphics.Color.Transparent,
                                    modifier = Modifier.fillMaxWidth().clickable { selDay = d }
                                ) {
                                    Text(
                                        "${d}일",
                                        fontSize = 14.sp,
                                        fontWeight = if (selDay == d) FontWeight.Bold else FontWeight.Normal,
                                        color = if (selDay == d) Primary else TextPrimary,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text("취소", color = TextSecondary)
                    }
                    Button(
                        onClick = {
                            // [코덱스 P0 #3] 미래 날짜 + 만 1세 미만(=오늘 출생) 차단
                            val selected = LocalDate.of(selYear, selMonth, selDay)
                            val beforeBirthday = today.monthValue < selected.monthValue ||
                                (today.monthValue == selected.monthValue && today.dayOfMonth < selected.dayOfMonth)
                            val ageYears = today.year - selected.year - if (beforeBirthday) 1 else 0
                            if (!selected.isAfter(today) && ageYears in 1..149) {
                                onConfirm(selYear, selMonth, selDay)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary)
                    ) {
                        Text("확인", color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// ==============================================
// 온보딩 1/3 — 기본 정보
// ==============================================
@Composable
fun OnboardingBasicScreen(
    onNext: () -> Unit,
    onBack: () -> Unit
) {
    // 이름/성별/생년월일은 AccountInfoScreen에서 처리 — 여기는 신체+당뇨만
    var heightCm by rememberSaveable { mutableStateOf("") }
    var weightKg by rememberSaveable { mutableStateOf("") }
    // [PDF 2 — 개인 공복혈당 범위] 선택 입력 (둘 다 입력하거나 둘 다 비우거나)
    var glucoseLow by rememberSaveable { mutableStateOf("") }
    var glucoseHigh by rememberSaveable { mutableStateOf("") }
    // 진단 시기 기본값 — 현재 년/월
    val today = remember { java.time.LocalDate.now() }
    var diagYear by rememberSaveable { mutableStateOf<Int?>(today.year) }
    var diagMonth by rememberSaveable { mutableStateOf<Int?>(today.monthValue) }
    var dateUnknown by rememberSaveable { mutableStateOf(false) }
    var noDiagnosis by rememberSaveable { mutableStateOf(false) }
    var showYearMonthDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.isImeVisible  // 키보드 표시 여부

    Scaffold(
        containerColor = BgSecondary,
        topBar = { OnboardingTopBar(step = 1, total = 3, onBack = onBack) },
        bottomBar = {
            // 키보드 뜨면 bottomBar 숨김 (내용 가림 방지)
            if (!imeVisible) {
                Surface(color = BgSecondary, shadowElevation = 8.dp) {
                    Column(modifier = Modifier
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp)) {
                        error?.let {
                            Surface(
                                color = ErrorRed.copy(alpha = 0.1f),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
                            ) {
                                Text(
                                    "⚠️ $it",
                                    color = ErrorRed,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                                )
                            }
                        }
                        BigButton(
                            text = if (saving) "저장 중..." else "다음 →",
                        onClick = {
                            error = null
                            val hCm = heightCm.toDoubleOrNull()
                            val wKg = weightKg.toDoubleOrNull()
                            val hasDiagYear = diagYear != null
                            val diagSelected = hasDiagYear || dateUnknown || noDiagnosis
                            // [PDF 2] 공복혈당 범위 — 선택. 둘 다 비우면 미설정, 하나만/잘못 입력하면 차단.
                            val gLow = glucoseLow.toIntOrNull()
                            val gHigh = glucoseHigh.toIntOrNull()
                            val rangeBlank = glucoseLow.isBlank() && glucoseHigh.isBlank()
                            val rangeValid = rangeBlank || (gLow != null && gHigh != null && gLow in 40..400 && gHigh in 40..400 && gLow <= gHigh)
                            when {
                                hCm == null || hCm !in 100.0..250.0 -> error = "키는 100~250cm 범위로 입력해주세요"
                                wKg == null || wKg !in 20.0..200.0 -> error = "체중은 20~200kg 범위로 입력해주세요"
                                !diagSelected -> error = "당뇨 진단 정보를 입력해주세요"
                                !rangeValid -> error = "공복혈당 범위는 상·하한을 모두 입력하고(40~400), 하한이 상한보다 크지 않게 해주세요"
                                else -> {
                                    saving = true
                                    scope.launch {
                                        val diagDate = if (hasDiagYear && !dateUnknown && !noDiagnosis) {
                                            LocalDate.of(diagYear!!, diagMonth ?: 1, 1).toString()
                                        } else null
                                        val req = UserUpdateRequest(
                                            heightCm = hCm,
                                            weightKg = wKg,
                                            diabetesDiagnosedAt = diagDate,
                                            glucoseNormalLow = if (!rangeBlank) gLow else null,
                                            glucoseNormalHigh = if (!rangeBlank) gHigh else null
                                        )
                                        val res = ServiceLocator.userRepository.updateMe(req)
                                        saving = false
                                        if (res is com.dangbomcare.app.data.repo.ApiResult.Success) onNext()
                                        else if (res is com.dangbomcare.app.data.repo.ApiResult.Error) error = res.message
                                    }
                                }
                            }
                        },
                        enabled = !saving
                    )
                }
            }
            }  // if (!imeVisible) 닫기
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()  // ⭐ 키보드 뜨면 컨텐츠 위로 밀어올림
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(4.dp))
            Surface(color = LimeLight, shape = RoundedCornerShape(10.dp)) {
                Text("STEP 1", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = PrimaryDark,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
            }
            Text("신체 정보를 알려주세요", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Text("BMI 계산과 활동량 점수 환산에 사용돼요", fontSize = 13.sp, color = TextSecondary)
            Text("모두 필수 항목입니다", fontSize = 12.sp, color = ErrorRed, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = BgCard),
                border = BorderStroke(1.dp, Divider)
            ) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    // 키 — 잘못된 입력만 안내
                    val heightDbl = heightCm.toDoubleOrNull()
                    val (heightState, heightMsg) = when {
                        heightCm.isBlank() -> com.dangbomcare.app.ui.components.ValidationState.Empty to ""
                        heightDbl == null -> com.dangbomcare.app.ui.components.ValidationState.Invalid to "숫자만 입력해주세요."
                        heightDbl !in 100.0..250.0 -> com.dangbomcare.app.ui.components.ValidationState.Invalid to "100~250cm 범위로 입력해주세요."
                        else -> com.dangbomcare.app.ui.components.ValidationState.Empty to ""
                    }
                    com.dangbomcare.app.ui.components.LabeledField(
                        label = "키",
                        required = true,
                        validationState = heightState,
                        validationMessage = heightMsg
                    ) {
                        OutlinedTextField(
                            value = heightCm,
                            onValueChange = { new ->
                                if (new.length <= 5 && new.matches(Regex("^\\d{0,3}(\\.\\d{0,1})?$"))) {
                                    heightCm = new
                                }
                            },
                            placeholder = { Text("예: 170") },
                            suffix = { Text("cm") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )
                    }

                    // 체중 — 잘못된 입력만 안내
                    val weightDbl = weightKg.toDoubleOrNull()
                    val (weightState, weightMsg) = when {
                        weightKg.isBlank() -> com.dangbomcare.app.ui.components.ValidationState.Empty to ""
                        weightDbl == null -> com.dangbomcare.app.ui.components.ValidationState.Invalid to "숫자만 입력해주세요."
                        weightDbl !in 20.0..200.0 -> com.dangbomcare.app.ui.components.ValidationState.Invalid to "20~200kg 범위로 입력해주세요."
                        else -> com.dangbomcare.app.ui.components.ValidationState.Empty to ""
                    }
                    com.dangbomcare.app.ui.components.LabeledField(
                        label = "체중",
                        required = true,
                        validationState = weightState,
                        validationMessage = weightMsg
                    ) {
                        OutlinedTextField(
                            value = weightKg,
                            onValueChange = { new ->
                                if (new.length <= 5 && new.matches(Regex("^\\d{0,3}(\\.\\d{0,1})?$"))) {
                                    weightKg = new
                                }
                            },
                            placeholder = { Text("예: 65") },
                            suffix = { Text("kg") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                focusManager.clearFocus()
                                keyboardController?.hide()
                            }),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = BgCard),
                border = BorderStroke(1.dp, Divider)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("당뇨 진단", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary, modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("연/월만 입력하시면 돼요", fontSize = 11.sp, color = TextSecondary)
                    Spacer(Modifier.height(10.dp))
                    when {
                        noDiagnosis -> {
                            Surface(color = LimeLight.copy(alpha = 0.4f), shape = RoundedCornerShape(12.dp)) {
                                Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.HelpOutline, null, tint = Primary, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(10.dp))
                                    Text("아직 당뇨 진단을 받지 않음", fontSize = 14.sp, color = PrimaryDark)
                                }
                            }
                        }
                        dateUnknown -> {
                            Surface(color = LimeLight.copy(alpha = 0.4f), shape = RoundedCornerShape(12.dp)) {
                                Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.HelpOutline, null, tint = Primary, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(10.dp))
                                    Text("정확한 시기를 모름", fontSize = 14.sp, color = PrimaryDark)
                                }
                            }
                        }
                        else -> {
                            Surface(
                                color = BgCardSoft,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, Divider),
                                onClick = { showYearMonthDialog = true }
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Filled.CalendarMonth, null, tint = Primary, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        when {
                                            diagYear != null && diagMonth != null -> "${diagYear}년 ${diagMonth}월"
                                            diagYear != null -> "${diagYear}년 (월 모름)"
                                            else -> "진단 시기 선택"
                                        },
                                        fontSize = 15.sp,
                                        color = if (diagYear != null) TextPrimary else TextSecondary,
                                        fontWeight = if (diagYear != null) FontWeight.SemiBold else FontWeight.Normal,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (diagYear != null) {
                                        TextButton(onClick = { diagYear = null; diagMonth = null }) {
                                            Text("지우기", fontSize = 12.sp, color = TextSecondary)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    // 체크박스 1: 정확한 시기 모름
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable {
                        val newVal = !dateUnknown
                        dateUnknown = newVal
                        if (newVal) { diagYear = null; diagMonth = null; noDiagnosis = false }
                    }) {
                        Checkbox(
                            checked = dateUnknown,
                            onCheckedChange = {
                                dateUnknown = it
                                if (it) { diagYear = null; diagMonth = null; noDiagnosis = false }
                            },
                            colors = CheckboxDefaults.colors(checkedColor = Primary)
                        )
                        Text("정확한 시기를 모르겠어요", fontSize = 13.sp, color = TextPrimary)
                    }
                    // 체크박스 2: 아직 진단 안 받음
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable {
                        val newVal = !noDiagnosis
                        noDiagnosis = newVal
                        if (newVal) { diagYear = null; diagMonth = null; dateUnknown = false }
                    }) {
                        Checkbox(
                            checked = noDiagnosis,
                            onCheckedChange = {
                                noDiagnosis = it
                                if (it) { diagYear = null; diagMonth = null; dateUnknown = false }
                            },
                            colors = CheckboxDefaults.colors(checkedColor = Primary)
                        )
                        Text("아직 당뇨 진단을 받지 않았어요", fontSize = 13.sp, color = TextPrimary)
                    }
                }
            }

            // [PDF 2 — 개인 공복혈당 범위] 선택 입력 — 평소 공복혈당 범위로 '점수 기준선' 설정
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = BgCard),
                border = BorderStroke(1.dp, Divider)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("평소 공복혈당 범위", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary, modifier = Modifier.weight(1f))
                        Surface(color = BgCardSoft, shape = RoundedCornerShape(8.dp)) {
                            Text("선택", fontSize = 11.sp, color = TextSecondary, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "평소 아침 공복혈당이 보통 어느 정도인지 범위로 알려주시면, 그 범위를 기준으로 점수를 매겨드려요. 모르시면 건너뛰셔도 돼요.",
                        fontSize = 11.sp, color = TextSecondary, lineHeight = 16.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = glucoseLow,
                            onValueChange = { new -> if (new.length <= 3 && new.all { it.isDigit() }) glucoseLow = new },
                            placeholder = { Text("130") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Right) }),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        )
                        Text("~", fontSize = 18.sp, color = TextSecondary)
                        OutlinedTextField(
                            value = glucoseHigh,
                            onValueChange = { new -> if (new.length <= 3 && new.all { it.isDigit() }) glucoseHigh = new },
                            placeholder = { Text("150") },
                            suffix = { Text("mg/dL") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus(); keyboardController?.hide() }),
                            modifier = Modifier.weight(1.3f),
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                    // 인라인 검증 안내 (둘 다 비움 = 정상, 부분 입력/범위 오류만 표시)
                    val _gl = glucoseLow.toIntOrNull(); val _gh = glucoseHigh.toIntOrNull()
                    val rangeHint = when {
                        glucoseLow.isBlank() && glucoseHigh.isBlank() -> null
                        _gl == null || _gh == null -> "상·하한을 모두 입력해주세요"
                        _gl !in 40..400 || _gh !in 40..400 -> "40~400 mg/dL 범위로 입력해주세요"
                        _gl > _gh -> "하한이 상한보다 클 수 없어요"
                        else -> null
                    }
                    if (rangeHint != null) {
                        Spacer(Modifier.height(6.dp))
                        Text("⚠️ $rangeHint", fontSize = 11.sp, color = ErrorRed)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (showYearMonthDialog) {
        YearMonthPickerDialog(
            initialYear = diagYear,
            initialMonth = diagMonth,
            onConfirm = { y, m -> diagYear = y; diagMonth = m; showYearMonthDialog = false },
            onDismiss = { showYearMonthDialog = false }
        )
    }
}

@Composable
private fun YearMonthPickerDialog(
    initialYear: Int?,
    initialMonth: Int?,
    onConfirm: (Int, Int?) -> Unit,
    onDismiss: () -> Unit
) {
    val today = LocalDate.now()
    val currentYear = today.year
    val currentMonth = today.monthValue
    var selectedYear by remember { mutableStateOf(initialYear ?: currentYear) }
    // [v1.0 피드백] 월 기본값을 현재 월로 초기화 (이전: null = 모름)
    var selectedMonth by remember { mutableStateOf<Int?>(initialMonth ?: currentMonth) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = BgPrimary)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("당뇨 진단 시기", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Spacer(Modifier.height(4.dp))
                Text("월은 모르면 맨 위 '모름' 선택", fontSize = 13.sp, color = TextSecondary)
                Spacer(Modifier.height(20.dp))
                Row(modifier = Modifier.fillMaxWidth().height(260.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        // [v1.0 피드백] "(필수)" 제거 + 글자 키움
                        Text("연도", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextSecondary, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
                        Surface(color = BgCardSoft, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Divider), modifier = Modifier.fillMaxSize()) {
                            val years = (currentYear downTo currentYear - 60).toList()
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                items(years) { y ->
                                    val isSelected = y == selectedYear
                                    Surface(color = if (isSelected) Primary else BgCardSoft, onClick = { selectedYear = y }, modifier = Modifier.fillMaxWidth()) {
                                        Text("${y}년", fontSize = 17.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) androidx.compose.ui.graphics.Color.White else TextPrimary,
                                            modifier = Modifier.padding(vertical = 12.dp, horizontal = 16.dp),
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        // [v1.0 피드백] "(선택)" 제거 + 글자 키움
                        Text("월", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextSecondary, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
                        Surface(color = BgCardSoft, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Divider), modifier = Modifier.fillMaxSize()) {
                            // [v1.0 피드백 재반영] "모름"을 맨 위에 다시 배치
                            // 단, 기본 선택값은 현재 월 → 다이얼로그 열릴 때 현재 월 위치로 자동 스크롤
                            val monthOptions = listOf<Int?>(null) + (1..12).toList()
                            val monthListState = androidx.compose.foundation.lazy.rememberLazyListState()
                            LaunchedEffect(Unit) {
                                val idx = monthOptions.indexOf(selectedMonth).coerceAtLeast(0)
                                monthListState.scrollToItem(idx)
                            }
                            LazyColumn(state = monthListState, modifier = Modifier.fillMaxSize()) {
                                items(monthOptions) { m ->
                                    val isSelected = m == selectedMonth
                                    Surface(color = if (isSelected) Primary else BgCardSoft, onClick = { selectedMonth = m }, modifier = Modifier.fillMaxWidth()) {
                                        Text(
                                            if (m == null) "모름" else "${m}월",
                                            fontSize = 17.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) androidx.compose.ui.graphics.Color.White else TextPrimary,
                                            modifier = Modifier.padding(vertical = 12.dp, horizontal = 16.dp),
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                Surface(color = LimeLight.copy(alpha = 0.4f), shape = RoundedCornerShape(10.dp)) {
                    Text(
                        "선택: ${selectedYear}년" + if (selectedMonth != null) " ${selectedMonth}월" else " (월 모름)",
                        fontSize = 15.sp, fontWeight = FontWeight.Bold, color = PrimaryDark,
                        modifier = Modifier.padding(12.dp).fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) {
                        Text("취소", fontSize = 16.sp)
                    }
                    Button(onClick = { onConfirm(selectedYear, selectedMonth) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = Primary)) {
                        Text("확인", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun LabeledInput(label: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextSecondary)
        Spacer(Modifier.height(6.dp))
        content()
    }
}

// [QA] LocalContext가 ContextWrapper(테마 래퍼 등)일 수 있어 as? Activity가 실패할 수 있음 → 언래핑.
private fun android.content.Context.findActivityOrNull(): android.app.Activity? {
    var c: android.content.Context? = this
    while (c is android.content.ContextWrapper) {
        if (c is android.app.Activity) return c
        c = c.baseContext
    }
    return null
}

// ==============================================
// 온보딩 2/3 — 워치 연결 (간략화: 권한 요청 + 다음)
// ==============================================
@Composable
fun OnboardingWatchScreen(
    onNext: () -> Unit,
    onBack: () -> Unit,
    onShowCompatibleWatches: () -> Unit = {}
) {
    val healthRepo = remember { ServiceLocator.healthConnectRepository }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var permGranted by remember { mutableStateOf(false) }
    var sdkAvailable by remember { mutableStateOf(true) }
    // [SDK화] 삼성 헬스 Data SDK 직독 모드면 헬스커넥트 대신 SDK 흐름으로 문구/권한 처리
    var sdkDirect by remember { mutableStateOf(false) }
    // [QA Fix] 모든 권한/런처 호출(SDK·HC)을 막는 단일 가드 — 빠른 더블탭/recomposition 이중 launch 방지.
    //   요청 시작 전 true, 런처 결과 콜백·SDK 요청 종료(타임아웃 포함) 시 false로 리셋. 버튼도 이 값으로 비활성.
    var permissionRequesting by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // [SDK 자동화] 이 기기가 삼성헬스 Data SDK 후보(삼성헬스 설치 + API29+ + store 초기화 가능)면
        //   권한 전이라도 SDK 흐름으로 판정 — 옵트인(7-tap) 불필요. 비후보 기기는 기존 HC 흐름(무변화).
        //   isPermissionFlowAvailable()는 non-suspend 라 LaunchedEffect 안에서 async-safe.
        sdkDirect = com.dangbomcare.app.BuildConfig.DEBUG ||
            try { ServiceLocator.samsungHealthSource.isPermissionFlowAvailable() } catch (_: Exception) { false }
        if (sdkDirect) {
            sdkAvailable = true
            permGranted = try { ServiceLocator.samsungHealthSource.isReady() } catch (_: Exception) { false }
        } else if (healthRepo.isSdkAvailable()) {
            sdkAvailable = true
            val p = healthRepo.checkPermissions()
            permGranted = p.all  // 모든 권한 받아야 true
        } else {
            sdkAvailable = false
        }
    }

    val permLauncher = rememberLauncherForActivityResult(
        contract = HealthConnectRepository.PERMS_CONTRACT
    ) { granted ->
        // [QA Fix] 런처 결과 도착 = 요청 종료 → 가드 해제(다시 누를 수 있게).
        permissionRequesting = false
        permGranted = granted.containsAll(HealthConnectRepository.PERMISSIONS)
    }

    // [QA Fix] HC 권한 런처 호출을 한 곳으로 — 가드는 호출 직전 set, launch는 try/catch로 감싸 실패 시 가드 해제.
    //   (가드 해제는 정상 흐름에선 위 런처 콜백이 담당. launch 자체가 throw하면 콜백이 안 와서 여기서 풀어줌.)
    fun launchHcPermission() {
        permissionRequesting = true
        try {
            permLauncher.launch(HealthConnectRepository.PERMISSIONS)
        } catch (_: Exception) {
            permissionRequesting = false
        }
    }

    // [SDK화] 삼성 헬스 SDK 자체 권한 다이얼로그 요청.
    //   [SDK 자동화 — 안전 폴백] requestPermissions가 false 반환 또는 throw 하면(미승인/개발자모드 OFF/예외 등)
    //   SDK 흐름을 접고(sdkDirect=false) 곧바로 정상 동작하는 Health Connect 권한 흐름으로 전환 — 사용자가 멈추지 않게.
    fun requestSdkPermission() {
        val act = ctx.findActivityOrNull()
        if (act == null) {
            // 액티비티를 못 얻으면 SDK 다이얼로그 자체가 불가 → HC 가용 시 HC 흐름으로 폴백.
            //   (단일 가드: HC로 넘어가면 launchHcPermission이 가드를 재설정/콜백에서 해제. HC 불가면 가드 해제.)
            if (healthRepo.isSdkAvailable()) {
                sdkDirect = false
                launchHcPermission()
            } else {
                permissionRequesting = false
            }
            return
        }
        scope.launch {
            // [QA Fix #5] SDK 권한 요청에 60초 타임아웃 — null(타임아웃)/false/throw 모두 HC로 폴백.
            //   CancellationException은 정상 취소 전파를 위해 재던짐(아래 catch에서 제외).
            val granted = try {
                kotlinx.coroutines.withTimeoutOrNull(60_000) {
                    ServiceLocator.samsungHealthSource.requestPermissions(act)
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                permissionRequesting = false
                throw ce
            } catch (_: Throwable) {
                false
            }
            if (granted == true) {
                // SDK 권한 OK → 가드 해제(런처 안 띄움).
                permissionRequesting = false
                permGranted = true
            } else {
                // SDK 권한 실패/타임아웃 — HC 가용하면 HC 권한 흐름으로 즉시 폴백(막힘 방지). HC도 없으면 가드 해제 후 상태 유지.
                if (healthRepo.isSdkAvailable()) {
                    sdkDirect = false
                    launchHcPermission()  // 가드는 여기서 유지(launch 직전 재set)·콜백에서 해제.
                } else {
                    permissionRequesting = false
                    permGranted = false
                }
            }
        }
    }

    Scaffold(
        containerColor = BgSecondary,
        topBar = { OnboardingTopBar(step = 2, total = 3, onBack = onBack) },
        bottomBar = {
            Surface(color = BgSecondary, shadowElevation = 8.dp) {
                Column(modifier = Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!permGranted) {
                        BigButton(
                            text = if (permissionRequesting) "요청 중..." else "권한 요청하기",
                            // [QA Fix] 단일 가드로 SDK·HC 모두 보호. 가드는 분기 직전 동기 set →
                            //   빠른 더블탭/recomposition 이중 launch 차단. SDK는 내부에서 가드 관리, HC는 launchHcPermission.
                            onClick = {
                                if (!permissionRequesting) {
                                    if (sdkDirect) {
                                        permissionRequesting = true
                                        requestSdkPermission()
                                    } else {
                                        launchHcPermission()
                                    }
                                }
                            },
                            enabled = !permissionRequesting,
                            primary = true
                        )
                        TextButton(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
                            Text("나중에 설정할게요", fontSize = 13.sp, color = TextSecondary)
                        }
                    } else {
                        BigButton(text = "다음 →", onClick = onNext, primary = true)
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize()
                .verticalScroll(rememberScrollState())   // 큰 글씨에서도 하단('받아올 데이터')이 안 잘리게 스크롤
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            Surface(color = LimeLight, shape = RoundedCornerShape(10.dp)) {
                Text("STEP 2", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = PrimaryDark,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
            }
            Spacer(Modifier.height(8.dp))

            Text("워치 데이터 권한", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Text(
                if (sdkDirect)
                    "삼성 헬스 SDK로 워치 데이터(걸음/수면/심박)를\n직접 가져옵니다."
                else
                    "Health Connect를 통해 워치 데이터(걸음/수면/심박)를\n자동으로 가져옵니다.",
                fontSize = 13.sp,
                color = TextSecondary,
                lineHeight = 19.sp
            )

            Spacer(Modifier.height(16.dp))

            // 상태 표시 — 권한 완료 여부
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (permGranted) LimeLight.copy(alpha = 0.5f) else BgCard
                ),
                border = BorderStroke(1.dp, if (permGranted) Primary.copy(alpha = 0.3f) else Divider)
            ) {
                Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (!sdkAvailable) "❌"
                        else if (permGranted) "✅" else "⌚",
                        fontSize = 32.sp
                    )
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(
                            when {
                                !sdkAvailable -> "Health Connect 미설치"
                                permGranted -> "워치 권한이 허용되었어요"
                                else -> "권한 요청이 필요해요"
                            },
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            when {
                                !sdkAvailable -> "Play 스토어에서 설치 후 다시 시도해주세요"
                                permGranted -> "이 폰에 이미 권한이 저장되어 있어요.\n워치 데이터가 자동으로 동기화됩니다."
                                else -> "아래 [권한 요청하기] 버튼을 눌러주세요"
                            },
                            fontSize = 12.sp,
                            color = TextSecondary,
                            lineHeight = 18.sp
                        )
                    }
                }
            }

            // 권한 항목 안내 (간략하게)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = BgCardSoft)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("받아올 데이터", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = TextSecondary)
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                        PermBadge("🚶", "걸음")
                        PermBadge("😴", "수면")
                        PermBadge("❤️", "심박")
                        PermBadge("💗", "변동")
                        PermBadge("🫁", "산소")
                    }
                }
            }

            // 호환 워치 안내 (compact)
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onShowCompatibleWatches() },
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = BgCard),
                border = BorderStroke(1.dp, Divider)
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Watch, null, tint = Primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "호환 가능한 워치 목록 보기 →",
                        fontSize = 12.sp,
                        color = Primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // [260812] 배터리 최적화 예외 안내 — 워치 없이 앱만 쓰는 사용자에게 특히 중요하다.
            //   Doze 로 30분 동기화가 밀리고 삼성헬스도 백그라운드에서 죽어 걸음이 끊기는 사례가
            //   한의원 피드백으로 확인됐다(9보·58보). 이미 허용돼 있으면 이 카드를 아예 안 그린다.
            run {
                val batCtx = LocalContext.current
                val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
                var batIgnoring by remember {
                    mutableStateOf(com.dangbomcare.app.data.health.BatteryOptimization.isIgnoring(batCtx))
                }
                DisposableEffect(lifecycleOwner) {
                    val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
                        if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                            batIgnoring = com.dangbomcare.app.data.health.BatteryOptimization.isIgnoring(batCtx)
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(obs)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
                }
                if (!batIgnoring) {
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable {
                            com.dangbomcare.app.data.health.BatteryOptimization.request(batCtx)
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = BgCard),
                        border = BorderStroke(1.dp, Divider)
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.BatterySaver, null, tint = Primary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "백그라운드 동기화 허용하기 →",
                                    fontSize = 12.sp,
                                    color = Primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "허용하시면 앱을 켜지 않아도 걸음·수면이 자동으로 저장돼요",
                                    fontSize = 11.sp,
                                    color = TextSecondary,
                                    lineHeight = 15.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactStep(num: Int, title: String, desc: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        border = BorderStroke(1.dp, Divider)
    ) {
        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(28.dp).background(Primary, shape = RoundedCornerShape(50)),
                contentAlignment = Alignment.Center
            ) { Text("$num", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = androidx.compose.ui.graphics.Color.White) }
            Spacer(Modifier.width(8.dp))
            Column {
                Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Text(desc, fontSize = 11.sp, color = TextSecondary)
            }
        }
    }
}

@Composable
private fun PermBadge(emoji: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier.size(36.dp).background(LimeLight, shape = RoundedCornerShape(50)),
            contentAlignment = Alignment.Center
        ) { Text(emoji, fontSize = 18.sp) }
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
    }
}

// [QA fix] "HH:mm" 문자열을 파싱해 해당 로컬 알람 시각으로 반영 (BEDTIME은 setTime 내부에서 DND도 동기화).
//   파싱 실패해도 무시 → 기본값 유지, 온보딩 완료엔 영향 없음.
private fun applyOnboardingAlarmTime(
    context: android.content.Context,
    type: com.dangbomcare.app.notification.AlarmType,
    hhmm: String
) {
    try {
        val parts = hhmm.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: return
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
        if (h in 0..23 && m in 0..59) {
            com.dangbomcare.app.notification.AlarmScheduler.setTime(context, type, h, m)
        }
    } catch (_: Exception) { /* 파싱 실패 무시 */ }
}

// ==============================================
// 온보딩 3/3 — 생활 패턴 (TimePicker + 자동 계산)
// ==============================================
@Composable
fun OnboardingLifestyleScreen(
    onComplete: () -> Unit,
    onBack: () -> Unit
) {
    var glucoseTime by rememberSaveable { mutableStateOf("07:00") }
    var bedTime by rememberSaveable { mutableStateOf("23:00") }
    var dinnerTime by rememberSaveable { mutableStateOf("19:00") }
    // 식사 시각 기본값 — 7:30 / 12:30 / 18:00 (사용자 요청)
    var breakfastTime by rememberSaveable { mutableStateOf("07:30") }
    var lunchTime by rememberSaveable { mutableStateOf("12:30") }
    var supperTime by rememberSaveable { mutableStateOf("18:00") }
    var dinnerManual by rememberSaveable { mutableStateOf(false) }  // 마감 시각 수동 수정 여부

    // ON → 12시 후 취침이라도 경고 X (불가피)
    // OFF → 12시 후 취침이면 "12시 전에 주무시는 게 좋아요" 경고 카드 노출

    var showTimePickerFor by remember { mutableStateOf<String?>(null) }  // "glucose" / "bed" / "dinner" / "breakfast" / "lunch" / "supper"

    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current

    // 시각 문자열 → 분 변환 헬퍼
    fun String.toMinutes(): Int {
        val parts = this.split(":")
        return (parts.getOrNull(0)?.toIntOrNull() ?: 0) * 60 + (parts.getOrNull(1)?.toIntOrNull() ?: 0)
    }
    fun Int.toTimeStr(): String = "%02d:%02d".format(this / 60, this % 60)

    // 저녁 마감 자동 재계산 — 취침 시각 -4시간 OR 저녁 식사 +1시간 중 더 늦은 시각
    // (예: 저녁 19시 + 취침 21시 → max(17, 20) = 20시. 모순 회피)
    fun recalcDinnerDeadline() {
        if (!dinnerManual) {
            val supMin = supperTime.toMinutes()
            val bedMinRaw = bedTime.toMinutes()
            // [Codex P1] 자정 넘김(야간/교대근무) 처리 — 취침이 저녁식사 시각 이하면 '다음날'로 간주(연속 타임라인)
            val bedMin = if (bedMinRaw <= supMin) bedMinRaw + 24 * 60 else bedMinRaw
            val byBed = bedMin - 4 * 60        // 취침 4시간 전
            val bySupper = supMin + 60         // 저녁 식사 1시간 후
            // 더 늦은 시각 채택(저녁 후 1시간 보장), 단 취침은 넘지 않게
            val deadline = maxOf(byBed, bySupper).coerceAtMost(bedMin) % (24 * 60)
            dinnerTime = deadline.toTimeStr()
        }
    }

    // 모순 검증 메시지
    val dinnerWarning: String? = run {
        val supMin = supperTime.toMinutes()
        val dinMin = dinnerTime.toMinutes()
        val bedMin = bedTime.toMinutes()
        // [Codex P1] 자정 넘김 대응 — 저녁식사 기준 '앞으로' 흐른 분으로 비교(야간근무 오탐 방지)
        val dinOffset = ((dinMin - supMin) + 24 * 60) % (24 * 60)   // 식사 → 마감
        val bedFromDin = ((bedMin - dinMin) + 24 * 60) % (24 * 60)  // 마감 → 취침
        when {
            dinOffset > 6 * 60 ->
                "⚠️ 저녁 식사 마감(${dinnerTime})이 저녁 식사 시각(${supperTime}) 기준으로 맞지 않아요. 확인해주세요."
            bedFromDin > 8 * 60 ->
                "💡 식사 마감과 취침 사이 간격이 너무 깁니다. 조정해보세요."
            else -> null
        }
    }

    Scaffold(
        containerColor = BgSecondary,
        topBar = { OnboardingTopBar(step = 3, total = 3, onBack = onBack) },
        bottomBar = {
            Surface(color = BgSecondary, shadowElevation = 8.dp) {
                Column(modifier = Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
                    error?.let {
                        Text(it, color = ErrorRed, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
                    }
                    BigButton(
                        text = if (saving) "저장 중..." else "완료 — 당봄케어 시작",
                        onClick = {
                            saving = true
                            error = null
                            scope.launch {
                                // [Codex P1] 설정/완료 저장 실패해도 온보딩이 끝나던 문제 → 둘 다 성공해야 완료.
                                try {
                                    val sres = ServiceLocator.userRepository.updateSettings(UserSettingsRequest(
                                        glucoseCheckTime = glucoseTime,
                                        dinnerDeadlineTime = dinnerTime,
                                        bedtime = bedTime
                                    ))
                                    val ures = ServiceLocator.userRepository.updateMe(UserUpdateRequest(onboardingCompleted = true))
                                    val ok = sres is com.dangbomcare.app.data.repo.ApiResult.Success &&
                                             ures is com.dangbomcare.app.data.repo.ApiResult.Success
                                    if (ok) {
                                        // [QA fix] 온보딩에서 고른 시각을 로컬 알람·라벨에도 반영.
                                        //   기존엔 updateSettings로 백엔드에만 저장 → 로컬 알람/회고라벨이 기본값(07/19/23시) 유지되어
                                        //   "저녁 20시 골라도 알림 19시·라벨 7시" 불일치. (패키지 리네임으로 전 사용자 재온보딩 → 전원 영향)
                                        applyOnboardingAlarmTime(context, com.dangbomcare.app.notification.AlarmType.MORNING, glucoseTime)
                                        applyOnboardingAlarmTime(context, com.dangbomcare.app.notification.AlarmType.DINNER, dinnerTime)
                                        applyOnboardingAlarmTime(context, com.dangbomcare.app.notification.AlarmType.BEDTIME, bedTime)
                                        // [260705 피드백] 식사 시각(아침/점심/저녁)도 저장 → 설정의 '생활패턴'에서 수정 가능
                                        context.getSharedPreferences("life_pattern", android.content.Context.MODE_PRIVATE)
                                            .edit()
                                            .putString("breakfast", breakfastTime)
                                            .putString("lunch", lunchTime)
                                            .putString("supper", supperTime)
                                            .apply()
                                        onComplete()
                                    } else error = "설정 저장에 실패했어요. 인터넷을 확인하고 다시 시도해주세요."
                                } catch (e: Exception) {
                                    error = "설정 저장 중 오류가 발생했어요. 다시 시도해주세요."
                                } finally {
                                    saving = false
                                }
                            }
                        },
                        enabled = !saving
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())  // ⭐ 식사 시각이 잘리지 않게 스크롤 가능
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(Modifier.height(4.dp))
            Surface(color = LimeLight, shape = RoundedCornerShape(10.dp)) {
                Text("STEP 3 · 마지막", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = PrimaryDark,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
            }
            Text("생활 패턴을 알려주세요", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Text("시각을 누르면 다이얼로 선택할 수 있어요", fontSize = 13.sp, color = TextSecondary)

            // [법적 표현 안전화] "의학적 가이드" → "생활습관 가이드"로 변경
            // 이유: "의학적" 문구는 의료기기/의약품 광고법상 진단/치료 주장으로 해석될 위험
            Surface(
                color = LimeLight.copy(alpha = 0.4f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("💡 생활습관 가이드", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = PrimaryDark)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "• 저녁 식사 = 취침 4시간 전 권장\n• 이른 저녁 식사 후 금식\n  (혈당 안정에 가장 효과적)",
                        fontSize = 11.sp,
                        color = TextPrimary,
                        lineHeight = 16.sp
                    )
                }
            }

            // 1) 공복혈당 측정 시각
            TimeCard(
                icon = Icons.Filled.WaterDrop,
                label = "공복혈당 측정 시각",
                time = glucoseTime,
                onClick = { showTimePickerFor = "glucose" }
            )

            // 2) 취침 시각
            TimeCard(
                icon = Icons.Filled.Bedtime,
                label = "취침 시각",
                time = bedTime,
                onClick = { showTimePickerFor = "bed" }
            )

            // 사용자 피드백 — "야간 교대 근무중" 토글 제거

            // 자정 후 취침 경고 (취침이 24시 이후 또는 새벽 5시 이전)
            run {
                val bedMin = bedTime.toMinutes()
                val isLateNight = bedMin in 0..(5 * 60)
                if (isLateNight) {
                    Surface(
                        color = Warning.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Warning.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("⚠️", fontSize = 16.sp)
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "자정 이후 취침 — 혈당 관리 주의",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Warning
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "자정 전(12시 전) 취침이 혈당 관리에 가장 좋아요.",
                                fontSize = 11.sp,
                                color = TextPrimary,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }

            // 3) 저녁 식사 마감 (자동 또는 수동)
            TimeCard(
                icon = Icons.Filled.RestaurantMenu,
                label = "저녁 식사 마감",
                time = dinnerTime,
                onClick = { showTimePickerFor = "dinner" },
                badge = if (dinnerManual) null else "자동",
                description = if (dinnerManual) "직접 설정함" else "취침 4시간 전 / 저녁 식사 +1시간"
            )

            // 모순 경고 (저녁 마감 < 저녁 시각 등)
            dinnerWarning?.let { warning ->
                Surface(
                    color = ErrorRed.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, ErrorRed.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(warning, fontSize = 12.sp, color = ErrorRed, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "취침 시각 또는 저녁 식사 시각을 조정하시거나, 마감 시각을 직접 설정해주세요.",
                            fontSize = 11.sp,
                            color = TextSecondary,
                            lineHeight = 16.sp
                        )
                    }
                }
            }

            // ── 식사 시각 (점수 산출 + AI 코칭에 활용) ──
            Spacer(Modifier.height(4.dp))
            Text(
                "🍽️ 평소 식사 시각",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                modifier = Modifier.padding(start = 4.dp)
            )
            Text(
                "식후 걷기 보너스 점수 계산에 사용해요",
                fontSize = 11.sp,
                color = TextSecondary,
                modifier = Modifier.padding(start = 4.dp)
            )

            TimeCard(
                icon = Icons.Filled.RestaurantMenu,
                label = "아침 식사",
                time = breakfastTime,
                onClick = { showTimePickerFor = "breakfast" }
            )
            TimeCard(
                icon = Icons.Filled.RestaurantMenu,
                label = "점심 식사",
                time = lunchTime,
                onClick = { showTimePickerFor = "lunch" }
            )
            TimeCard(
                icon = Icons.Filled.RestaurantMenu,
                label = "저녁 식사",
                time = supperTime,
                onClick = { showTimePickerFor = "supper" }
            )
            Spacer(Modifier.height(24.dp))  // bottomBar 가림 방지 여유
        }
    }

    // 시각 선택 팝업
    showTimePickerFor?.let { which ->
        val currentTime = when (which) {
            "glucose" -> glucoseTime
            "bed" -> bedTime
            "dinner" -> dinnerTime
            "breakfast" -> breakfastTime
            "lunch" -> lunchTime
            "supper" -> supperTime
            else -> "00:00"
        }
        WheelTimePickerDialog(
            initial = currentTime,
            title = when (which) {
                "glucose" -> "공복혈당 측정 시각"
                "bed" -> "취침 시각"
                "dinner" -> "저녁 식사 마감 시각"
                "breakfast" -> "아침 식사 시각"
                "lunch" -> "점심 식사 시각"
                "supper" -> "저녁 식사 시각"
                else -> "시각 선택"
            },
            onConfirm = { t ->
                when (which) {
                    "glucose" -> glucoseTime = t
                    "bed" -> {
                        bedTime = t
                        recalcDinnerDeadline()
                    }
                    "dinner" -> {
                        dinnerTime = t
                        dinnerManual = true
                    }
                    "breakfast" -> breakfastTime = t
                    "lunch" -> lunchTime = t
                    "supper" -> {
                        supperTime = t
                        recalcDinnerDeadline()  // 저녁 시각 변경 시도 마감 재계산
                    }
                }
                showTimePickerFor = null
            },
            onDismiss = { showTimePickerFor = null }
        )
    }
}

/**
 * 야간/교대 근무자 토글 카드 (당봄 피드백 #8-3).
 * ON → 자정 후 취침이라도 경고 X (불가피)
 * OFF → 자정 후 취침이면 경고 카드 표시
 */
@Composable
private fun NightShiftToggleCard(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onCheckedChange(!checked) },
        color = if (checked) LimeLight.copy(alpha = 0.4f) else BgCard,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, if (checked) Primary.copy(alpha = 0.4f) else Divider),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("🌙", fontSize = 22.sp)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "야간 / 교대 근무 중이에요",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    "직업상 자정 이후 취침이 불가피한 경우 켜주세요",
                    fontSize = 11.sp,
                    color = TextSecondary,
                    lineHeight = 16.sp
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Primary,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = TextDisabled
                )
            )
        }
    }
}

@Composable
private fun TimeCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    time: String,
    onClick: () -> Unit,
    badge: String? = null,
    description: String? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = if (badge == "자동") LimeLight.copy(alpha = 0.3f) else BgCard),
        border = BorderStroke(1.dp, if (badge == "자동") LimeAccent else Divider)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Primary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    if (badge != null) {
                        Spacer(Modifier.width(6.dp))
                        Surface(color = LimeAccent, shape = RoundedCornerShape(8.dp)) {
                            Text(badge, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = PrimaryDark,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                }
                if (description != null) {
                    Text(description, fontSize = 11.sp, color = TextSecondary)
                }
            }
            Text(time, fontSize = 24.sp, fontWeight = FontWeight.Black, color = Primary)
        }
    }
}

/**
 * 삼성 시계 스타일 휠 시각 선택 다이얼로그.
 * 오전/오후, 시, 분 3개 휠을 세로로 드래그하여 선택.
 */
@Composable
private fun WheelTimePickerDialog(
    initial: String,
    title: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var current by remember { mutableStateOf(initial) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = BgPrimary)
        ) {
            // 가로모드/분할화면(높이 부족)에서 확인·취소 버튼이 밖으로 밀리지 않게 스크롤 허용
            Column(modifier = Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.AccessTime, null, tint = Primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                }
                Spacer(Modifier.height(20.dp))

                com.dangbomcare.app.ui.components.WheelTimePicker(
                    initialTime = initial,
                    onTimeChange = { current = it }
                )

                Spacer(Modifier.height(20.dp))

                // 미리보기
                Surface(color = LimeLight.copy(alpha = 0.4f), shape = RoundedCornerShape(10.dp)) {
                    Text(
                        "선택: ${formatTimeKorean(current)}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = PrimaryDark,
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
                Spacer(Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("취소", fontSize = 15.sp)
                    }
                    Button(
                        onClick = { onConfirm(current) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary)
                    ) {
                        Text("확인", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

private fun formatTimeKorean(time: String): String {
    val parts = time.split(":")
    val h = parts.getOrNull(0)?.toIntOrNull() ?: 0
    val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val period = if (h < 12) "오전" else "오후"
    val h12 = when {
        h == 0 -> 12
        h > 12 -> h - 12
        else -> h
    }
    return "$period ${h12}:%02d".format(m)
}

// ==============================================
// 공통
// ==============================================
@Composable
private fun OnboardingTopBar(step: Int, total: Int, onBack: () -> Unit) {
    TopAppBar(
        // [코덱스 UX] step=0 or total=0이면 "0/0" 숨김 (AccountInfo 같은 비-온보딩 화면용)
        title = {
            if (step > 0 && total > 0) {
                Text("$step / $total", fontSize = 14.sp, color = TextSecondary)
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = BgPrimary)
    )
}
