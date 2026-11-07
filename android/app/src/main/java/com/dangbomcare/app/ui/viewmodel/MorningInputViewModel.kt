package com.dangbomcare.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dangbomcare.app.data.ServiceLocator
import com.dangbomcare.app.data.api.DailyLogCreateRequest
import com.dangbomcare.app.data.api.DailyLogResponse
import com.dangbomcare.app.data.repo.ApiResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class MorningInputViewModel : ViewModel() {

    sealed class UiState {
        object Editing : UiState()
        object Submitting : UiState()
        data class Success(val response: DailyLogResponse) : UiState()
        /** 이미 같은 내용으로 저장돼 있음 — 변경 사항 없음 */
        object NoChange : UiState()
        data class Error(val message: String) : UiState()
    }

    /**
     * 화면 초기값. 오늘 이미 입력했다면 그 값으로 채워서 수정 가능.
     */
    data class InitialValues(
        val fastingGlucose: Int? = null,
        val alcohol: Boolean? = null,
        val dinnerBefore7pm: Boolean? = null,
        val noFoodAfterDinner: Boolean? = null,
        // [260623] 전날 회고 입력 4개 추가
        val noFoodAfterDinnerHours: String? = null,
        val dinnerType: List<String>? = null,
        // [260721] "기타" 선택 시 자유텍스트 (예: 파스타)
        val dinnerTypeOther: String? = null,
        val digestion: String? = null,
        val postMealExercise: String? = null,
        val isExisting: Boolean = false,  // 이미 (백엔드에) 입력한 적 있는지
        val isDraft: Boolean = false,     // [260623] 로컬 임시저장(draft)에서 복원된 값인지
        // [260727 P2] 첫 로드가 끝나 "대상 날짜 / 저녁 회고 모드가 확정됨"을 뜻하는 게이트.
        //   화면은 이 값이 true 가 되기 전엔 formKey("날짜|저녁모드") 기반 폼 초기화를 하지 않는다.
        //   (프로세스 사망 복귀 직후엔 ViewModel 이 새로 생성돼 isExisting=false → 저녁 모드가 순간
        //    false 로 계산되고, 그 한 프레임 때문에 rememberSaveable 로 살려둔 입력이 전부 지워졌다.)
        //   별도 StateFlow 로 두지 않고 값과 같은 emission 에 실어 보낸다 — 따로 두면 화면이
        //   "게이트만 열리고 값은 아직 기본값"인 중간 프레임을 볼 수 있어 같은 사고가 재발한다.
        //   한 번 true 가 되면 날짜를 바꿔도 유지한다(아래 setDate 참고).
        val loadSettled: Boolean = false
    )

    private val dailyLogRepo = ServiceLocator.dailyLogRepository
    private val healthRepo = ServiceLocator.healthConnectRepository
    private val watchRepo = ServiceLocator.watchDataRepository

    private val _state = MutableStateFlow<UiState>(UiState.Editing)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _initialValues = MutableStateFlow(InitialValues())
    val initialValues: StateFlow<InitialValues> = _initialValues.asStateFlow()

    // [우노 피드백 3] 입력 대상 날짜 — 기본 오늘. +버튼/캘린더에서 setDate로 과거 날짜 지정 가능.
    private val _selectedDate = MutableStateFlow(LocalDate.now())
    val selectedDate: StateFlow<LocalDate> = _selectedDate.asStateFlow()

    // [우노 3] 캘린더에 "입력된 날" 점 표시용 — 공복혈당이 입력된 날짜 집합 (월별 로드, 누적).
    private val _loggedDates = MutableStateFlow<Set<LocalDate>>(emptySet())
    val loggedDates: StateFlow<Set<LocalDate>> = _loggedDates.asStateFlow()

    // [v1.1.4-rate] AI 재생성 카운터 — GET/POST /daily-logs 응답의 최상위 두 필드 반영.
    //   remaining=0 && locked=true 이면 그 날 AI 재해석 재요청 소진(수정 저장 자체는 서버에서 계속 허용,
    //   단 GPT 호출은 스킵되고 기존 피드백 유지). UI 표시·버튼 disable 근거로 사용.
    data class AiRegenInfo(val remaining: Int = 0, val locked: Boolean = false)
    private val _aiRegen = MutableStateFlow(AiRegenInfo())
    val aiRegen: StateFlow<AiRegenInfo> = _aiRegen.asStateFlow()

    /** 해당 월의 입력 여부를 로드해 캘린더 점 표시에 반영 (달 이동 시마다 호출). */
    fun loadMonth(ym: YearMonth) {
        viewModelScope.launch {
            val from = ym.atDay(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
            val to = ym.atEndOfMonth().format(DateTimeFormatter.ISO_LOCAL_DATE)
            when (val res = dailyLogRepo.list(from, to)) {
                is ApiResult.Success -> {
                    val dates = res.data.mapNotNull { item ->
                        if (item.fastingGlucose != null)
                            runCatching { LocalDate.parse(item.logDate) }.getOrNull()
                        else null
                    }.toSet()
                    _loggedDates.value = _loggedDates.value + dates  // 이전 달 표시 유지(누적)
                }
                is ApiResult.Error -> { /* 무시 — 점 표시는 부가 기능 */ }
            }
        }
    }

    /** 입력 대상 날짜 변경 — 폼을 비우고 그 날짜의 기존 입력값을 로드 (화면에서 호출). */
    fun setDate(date: LocalDate) {
        pruneExpiredDraft()  // [260623] 만료된 임시저장 정리 (다음날 안 들어오면 초기화)
        // [260727 P2] 로드를 하지 않고 빠져나가는 경로에서도 게이트는 열어준다 (화면이 초기화 보류에 갇히지 않게).
        if (date.isAfter(LocalDate.now())) { openLoadGate(); return }          // 미래 날짜 차단
        if (_selectedDate.value == date && _initialValues.value.isExisting) {  // 동일 날짜 재로드 방지
            openLoadGate()
            return
        }
        _selectedDate.value = date
        // 이전 날짜 값 비우기 (폼 리셋 트리거).
        // [260727 P2] loadSettled 는 유지 — 날짜를 바꿀 때마다 게이트를 닫으면 응답이 오는 동안
        //   사용자가 입력한 값이 뒤늦게 도착한 초기화에 지워진다. 게이트가 필요한 구간은
        //   "화면 진입 직후 날짜·모드가 확정되기 전" 한 번뿐이다.
        _initialValues.value = InitialValues(loadSettled = _initialValues.value.loadSettled)
        // [260726] AI 재생성 카운터도 함께 초기화 — 리셋하지 않으면 새 날짜 응답이 도착할 때까지
        //   이전 날짜의 remaining/locked 가 그대로 노출돼(예: 소진 상태) 저장 버튼이 잘못 비활성화된다.
        _aiRegen.value = AiRegenInfo()
        loadForDate(date)
    }

    /** [260727 P2] 값 변경 없이 폼 초기화 게이트만 개방 (로드를 건너뛴 경로용). */
    private fun openLoadGate() {
        val cur = _initialValues.value
        if (!cur.loadSettled) _initialValues.value = cur.copy(loadSettled = true)
    }

    /** 해당 날짜에 이미 입력한 값을 백엔드에서 가져와 초기값으로 세팅. */
    fun loadForDate(date: LocalDate) {
        viewModelScope.launch {
            val iso = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
            when (val res = dailyLogRepo.get(iso)) {
                is ApiResult.Success -> {
                    // [코덱스 P1] 응답이 도착하기 전 사용자가 날짜를 바꿨으면 stale 응답 무시 (폼 오염 방지)
                    if (_selectedDate.value != date) return@launch
                    // [v1.1.4-rate] AI 재생성 카운터 반영 — GET 응답에도 실려옴 (서버 구버전이면 기본 0/false).
                    _aiRegen.value = AiRegenInfo(res.data.aiRegenRemaining, res.data.aiRegenLocked)
                    val log = res.data.log
                    if (log != null && log.fastingGlucose != null) {
                        _initialValues.value = InitialValues(
                            fastingGlucose = log.fastingGlucose,
                            alcohol = log.alcohol,
                            dinnerBefore7pm = log.dinnerBefore7pm,
                            noFoodAfterDinner = log.noFoodAfterDinner,
                            noFoodAfterDinnerHours = log.noFoodAfterDinnerHours,
                            dinnerType = log.dinnerType,
                            dinnerTypeOther = log.dinnerTypeOther,
                            digestion = log.digestion,
                            postMealExercise = log.postMealExercise,
                            isExisting = true,
                            // [260727 P2] 응답 도착 = 날짜·저녁모드 확정 → 값과 함께 폼 초기화 게이트 개방.
                            loadSettled = true
                        )
                    } else {
                        // 백엔드 입력 없음 → 로컬 임시저장(draft)이 있으면 폼에 복원.
                        // [260727 P2] draft 가 없어도 게이트는 열어야 하므로 항상 대입한다.
                        _initialValues.value = loadDraft(date)?.copy(loadSettled = true)
                            ?: InitialValues(loadSettled = true)
                    }
                }
                // 404 = 해당 날짜 백엔드 입력 없음 → 로컬 임시저장(draft) 복원 시도
                is ApiResult.Error -> {
                    if (_selectedDate.value == date) {
                        // [260727 P2] 404/네트워크 오류도 "첫 로드 끝" — 게이트를 열어야 폼 초기화가 재개된다.
                        _initialValues.value = loadDraft(date)?.copy(loadSettled = true)
                            ?: _initialValues.value.copy(loadSettled = true)
                    }
                }
            }
        }
    }

    fun submit(
        fastingGlucose: Int,
        alcohol: Boolean?,
        dinnerBefore7pm: Boolean?,
        noFoodAfterDinnerHours: String?,
        dinnerType: List<String>?,
        dinnerTypeOther: String?,
        digestion: String?,
        postMealExercise: String?
    ) {
        // [v1.0 fix - Codex 2차] 중복 제출 가드 — 버튼 enabled 토글과 state 전환 사이 race로
        //   순간 더블탭 시 같은 입력이 두 번 POST되어 백엔드 unique 제약 충돌 가능. 첫 줄에서 차단.
        if (_state.value is UiState.Submitting) return

        if (fastingGlucose !in 10..600) {
            _state.value = UiState.Error("공복혈당은 10~600 mg/dL 범위로 입력해주세요")
            return
        }

        // ⭐ 변경 사항 감지 — 이미 입력한 적 있고 모든 필드 동일하면 NoChange
        val initial = _initialValues.value
        if (initial.isExisting &&
            initial.fastingGlucose == fastingGlucose &&
            initial.alcohol == alcohol &&
            initial.dinnerBefore7pm == dinnerBefore7pm &&
            initial.noFoodAfterDinnerHours == noFoodAfterDinnerHours &&
            // 저녁식사 유형은 '중복 선택 집합' — 순서 무관 비교(null/빈리스트도 동일 취급)로 불필요 재제출 방지
            initial.dinnerType.orEmpty().toSet() == dinnerType.orEmpty().toSet() &&
            (initial.dinnerTypeOther ?: "") == (dinnerTypeOther ?: "") &&
            initial.digestion == digestion &&
            initial.postMealExercise == postMealExercise
        ) {
            _state.value = UiState.NoChange
            return
        }

        viewModelScope.launch {
            _state.value = UiState.Submitting

            val targetDate = _selectedDate.value
            val isToday = targetDate == LocalDate.now()

            // 1) 워치 데이터 동기화 (어제 자정~지금) — 오늘 입력일 때만. 옵션, 실패해도 진행.
            //    과거 날짜는 백그라운드 동기화로 쌓인 데이터를 사용 (오래된 날짜는 HC에 없을 수 있음).
            if (isToday) {
                try {
                    if (healthRepo.canReadWatchData()) {   // SDK 활성 OR HC 권한 — SDK 폰도 동기화되게
                        val yesterdayData = healthRepo.fetchWatchData(LocalDate.now().minusDays(1))
                        // [Codex P0 데이터손실] 무측정 시 all-null → 빈 값을 전송하면 서버의 정상 데이터를 덮어쓸 수 있음 → 스킵.
                        //   (WatchSyncWorker/RecordsScreen/Settings과 동일한 가드)
                        val hasNoData = yesterdayData.steps == null && yesterdayData.sleepTotalMinutes == null &&
                            yesterdayData.avgHeartRate == null && yesterdayData.spo2Avg == null
                        if (!hasNoData) {
                            val syncRes = watchRepo.post(yesterdayData)
                            if (syncRes is ApiResult.Success) {
                                // 마지막 동기화 시각 기록 (홈 화면 표시용)
                                com.dangbomcare.app.data.health.WatchSyncTracker.mark(
                                    com.dangbomcare.app.data.ServiceLocator.appContext
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    // ignore
                }
            }

            // 공복혈당 측정 시각 — 오늘이면 현재 시각, 과거면 그 날 아침 08:00 (KST)
            val glucoseTime = if (isToday) {
                LocalDateTime.now().atOffset(ZoneOffset.ofHours(9))
            } else {
                targetDate.atTime(8, 0).atOffset(ZoneOffset.ofHours(9))
            }
            val req = DailyLogCreateRequest(
                logDate = targetDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
                fastingGlucose = fastingGlucose,
                fastingGlucoseTime = glucoseTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                alcohol = alcohol,
                dinnerBefore7pm = dinnerBefore7pm,
                // [260623] 식후 금식 시간(5단계) 전송 — 불리언 no_food_after_dinner는 백엔드가 derive
                noFoodAfterDinnerHours = noFoodAfterDinnerHours,
                dinnerType = dinnerType,
                dinnerTypeOther = dinnerTypeOther?.trim()?.ifBlank { null },
                digestion = digestion,
                postMealExercise = postMealExercise,
                // [우노 3] 과거 날짜 입력은 "manual" → 백엔드 EDIT_LOCKED 우회 (지난 날도 수정 가능)
                inputMethod = if (isToday) "morning" else "manual"
            )

            when (val res = dailyLogRepo.create(req)) {
                is ApiResult.Success -> {
                    clearDraftIfFor(targetDate)  // [260623] 제출 완료 → 그 날짜용 임시저장 소진
                    // [v1.1.4-rate] 저장 응답의 재생성 카운터 반영 — 저장 성공 후 화면 이탈 전 상태 갱신.
                    _aiRegen.value = AiRegenInfo(res.data.aiRegenRemaining, res.data.aiRegenLocked)
                    _state.value = UiState.Success(res.data)
                }
                is ApiResult.Error -> _state.value = UiState.Error(res.message)
            }
        }
    }

    // ============================================================
    // [260623 피드백] 임시저장 — '오늘 저녁에 회고 미리 체크 → 다음날 아침 공복혈당 입력 후 제출' 흐름.
    //   · 단일 pending draft. draft.targetDate = 완료할 아침(= 저장한 날의 다음날).
    //   · 그 아침(targetDate)에 입력 화면을 열면 복원. 제출하면 소진.
    //   · targetDate가 지나도 안 들어오면(오늘 > targetDate) 자동 초기화(만료).
    // ============================================================
    // 인코딩: "targetDateISO|glucose|alcohol|dinner|noFoodHours|dinnerType(comma)|digestion|postMealExercise|dinnerTypeOther"
    //   [260721] dinnerTypeOther 9번째 필드 추가. 구버전(8필드) draft는 자동 무효화.
    private val draftStoreKey = "pending_draft"

    // [260722] draft targetDate/만료 판정은 기기 로컬 타임존 아닌 KST(Asia/Seoul)로 고정.
    //   여행/타임존 이동 시 "저녁 저장 → 다음날 아침 복원" 흐름이 어긋나지 않도록 통일.
    private val KST_ZONE: ZoneId = ZoneId.of("Asia/Seoul")

    private fun draftPrefs(): android.content.SharedPreferences =
        ServiceLocator.appContext.getSharedPreferences("dangbom_input_draft", android.content.Context.MODE_PRIVATE)

    private fun boolToStr(b: Boolean?) = when (b) { true -> "1"; false -> "0"; else -> "" }
    private fun strToBool(s: String) = when (s) { "1" -> true; "0" -> false; else -> null }

    /** 오늘 저녁 체크를 '다음날 아침 입력'용으로 임시저장 (공복혈당 없이 가능). */
    fun saveDraft(
        fastingGlucose: Int?,
        alcohol: Boolean?,
        dinnerBefore7pm: Boolean?,
        noFoodAfterDinnerHours: String?,
        dinnerType: List<String>?,
        dinnerTypeOther: String?,
        digestion: String?,
        postMealExercise: String?
    ) {
        // [260722] KST 고정 — 기기 로컬 타임존이 KST가 아닐 때 "다음날" 계산이 어긋나는 문제 방지.
        val targetDate = LocalDate.now(KST_ZONE).plusDays(1)  // 저장한 날의 다음날 아침에 완료
        val encoded = listOf(
            targetDate.toString(),
            fastingGlucose?.toString() ?: "",
            boolToStr(alcohol),
            boolToStr(dinnerBefore7pm),
            noFoodAfterDinnerHours ?: "",
            dinnerType?.joinToString(",") ?: "",
            digestion ?: "",
            postMealExercise ?: "",
            // [260721] dinnerTypeOther — 사용자 자유텍스트. 구분자 "|" 유입 방지 위해 sanitize.
            dinnerTypeOther?.replace("|", "")?.replace(",", "") ?: ""
        ).joinToString("|")
        // [260722] apply() 유지 — 비동기 write. 강제 종료(kill) 시 draft 유실 가능성 있으나,
        //   UI 스레드 블록 회피가 우선. draft는 사용자가 다시 저장 가능한 편의 기능.
        draftPrefs().edit().putString(draftStoreKey, encoded).apply()
    }

    /** 입력일(date)이 draft의 목표 아침이면 복원. 만료된 draft는 자동 삭제 후 null. */
    fun loadDraft(date: LocalDate): InitialValues? {
        val raw = draftPrefs().getString(draftStoreKey, null) ?: return null
        val p = raw.split("|")
        // [260721] v1.1.3 draft는 9필드(dinnerTypeOther 포함). v1.1.2 이전 8필드도 하위호환 복원.
        //   - size == 8: dinnerTypeOther만 null 처리
        //   - size == 9: 그대로 사용
        //   - size < 8: 훨씬 구버전 → 폐기
        if (p.size < 8) { clearDraft(); return null }
        val targetDate = runCatching { LocalDate.parse(p[0]) }.getOrNull()
            ?: run { clearDraft(); return null }
        // 만료: 목표 아침이 지났으면(다음날 안 들어옴) 자동 초기화. [260722] KST 기준.
        if (LocalDate.now(KST_ZONE) > targetDate) { clearDraft(); return null }
        // 그 목표 아침의 입력일 때만 복원
        if (date != targetDate) return null
        val payload = p.subList(1, minOf(p.size, 9))
        if (payload.all { it.isBlank() }) return null
        return InitialValues(
            fastingGlucose = p[1].toIntOrNull(),
            alcohol = strToBool(p[2]),
            dinnerBefore7pm = strToBool(p[3]),
            noFoodAfterDinnerHours = p[4].ifBlank { null },
            // [260722] v1.1.2 마이그레이션 — 이전 버전 draft에 저장된 "flour" 항목 제거.
            //   (v1.1.3에서 dinnerType 선택지에서 flour가 폐지됨. UI 표시 불가 값을 걸러냄.)
            dinnerType = p[5].split(",").filter { it.isNotBlank() && it != "flour" }.ifEmpty { null },
            digestion = p[6].ifBlank { null },
            postMealExercise = p[7].ifBlank { null },
            dinnerTypeOther = p.getOrNull(8)?.ifBlank { null },
            isExisting = false,
            isDraft = true
        )
    }

    fun clearDraft() {
        draftPrefs().edit().remove(draftStoreKey).apply()
    }

    /** 제출 완료한 날짜가 draft의 목표일과 같으면 소진. */
    fun clearDraftIfFor(date: LocalDate) {
        val targetStr = draftPrefs().getString(draftStoreKey, null)?.split("|")?.firstOrNull() ?: return
        if (targetStr == date.toString()) clearDraft()
    }

    /** 만료된(목표일 지난) draft 정리 — 화면 진입 시 호출. [260722] KST 기준. */
    fun pruneExpiredDraft() {
        val targetStr = draftPrefs().getString(draftStoreKey, null)?.split("|")?.firstOrNull() ?: return
        val targetDate = runCatching { LocalDate.parse(targetStr) }.getOrNull()
        if (targetDate == null || LocalDate.now(KST_ZONE) > targetDate) clearDraft()
    }

    /**
     * [260722] 로그아웃/계정 삭제 시 이 ViewModel이 관리하는 사용자 데이터 전량 삭제.
     *   AuthRepository 등에서 호출. 다른 SharedPreferences 파일은 각자 담당 모듈에서 처리.
     */
    fun clearAllUserData() {
        clearDraft()
    }

    fun resetError() {
        if (_state.value is UiState.Error) _state.value = UiState.Editing
    }

    /** NoChange 상태 dismiss (사용자가 확인 누름) */
    fun consumeNoChange() {
        if (_state.value is UiState.NoChange) _state.value = UiState.Editing
    }
}
