package com.dangbomcare.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dangbomcare.app.data.ServiceLocator
import com.dangbomcare.app.data.api.MonthlyReportResponse
import com.dangbomcare.app.data.api.WeeklyReportResponse
import com.dangbomcare.app.data.repo.ApiResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ReportViewModel : ViewModel() {

    data class UiState(
        val tab: Tab = Tab.WEEKLY,
        val loading: Boolean = false,
        val weekly: WeeklyReportResponse? = null,
        val monthly: MonthlyReportResponse? = null,
        val weekStart: LocalDate = thisMonday(),
        val year: Int = todayKst().year,
        val month: Int = todayKst().monthValue,
        val error: String? = null,
        // 신규 사용자 안내용 — 가입 후 며칠 지났는지
        val daysSinceSignup: Int? = null,
    )

    enum class Tab { WEEKLY, MONTHLY }

    private val repo = ServiceLocator.reportRepository

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    // [260726] 마지막으로 계산한 '오늘' 기준값 — 자정을 넘겼는지 판정용.
    //   ViewModel 은 하단탭 saveState/restoreState 로 앱 세션 내내 살아 있어서
    //   생성 시점의 '이번 주/이번 달'이 그대로 굳는다(밤 11시 50분에 켜두고 자정을 넘기면 지난 주를 계속 표시).
    private var lastKnownMonday: LocalDate = thisMonday()
    private var lastKnownYear: Int = todayKst().year
    private var lastKnownMonth: Int = todayKst().monthValue

    // [260726] 화면 최초 진입의 ON_RESUME 은 아래 init 로드와 겹치므로 1회만 건너뛴다.
    //   (ViewModel 에 두는 이유: 컴포저블 지역 플래그는 탭 전환으로 재생성되며 리셋돼 매번 새로고침을 건너뛰게 됨 —
    //    기록 탭에서 실제로 났던 버그. VM 은 탭 전환에도 살아 있어 '진짜 최초 1회'만 정확히 걸러낸다.)
    private var skipFirstResume = true

    init {
        loadWeekly()
    }

    /**
     * [260726] 리포트 탭 재진입(ON_RESUME) 새로고침 — 홈/기록에서 방금 입력한 내용이 리포트에 반영되도록.
     *   ⚠️ 기존엔 init 의 loadWeekly() 가 앱 세션당 1회만 실행돼, 아침에 리포트를 본 뒤 입력을 마치고
     *      다시 들어오면 기록 일수·평균·추이가 입력 전 값 그대로였다.
     *   자정 넘김 대응: '오늘'을 다시 계산해 주/월이 바뀌었으면 새 주/새 달로 이동한다.
     *   단 사용자가 일부러 지난 주/지난 달을 보고 있었다면 그 화면을 덮어쓰지 않는다
     *   (직전 계산 시점의 '이번 주/이번 달'을 보고 있던 경우에만 이동).
     */
    fun refresh() {
        // 최초 진입은 init 의 loadWeekly() 가 이미 돌고 있음 → 같은 주를 두 번 요청(AI 요약 중복 생성)하지 않도록 스킵.
        if (skipFirstResume) { skipFirstResume = false; return }
        val monday = thisMonday()
        val today = todayKst()
        val s = _state.value
        // 직전에 '이번 주/이번 달'을 보고 있었는지 (= 사용자가 과거로 이동해 둔 상태가 아님)
        val wasOnCurrentWeek = s.weekStart == lastKnownMonday
        val wasOnCurrentMonth = s.year == lastKnownYear && s.month == lastKnownMonth
        var next = s
        if (wasOnCurrentWeek && s.weekStart != monday) next = next.copy(weekStart = monday)
        if (wasOnCurrentMonth && (s.year != today.year || s.month != today.monthValue)) {
            next = next.copy(year = today.year, month = today.monthValue)
        }
        lastKnownMonday = monday
        lastKnownYear = today.year
        lastKnownMonth = today.monthValue
        // 안 보이는 탭 캐시는 비워 둔다 → 탭 전환 시 selectTab 이 최신 데이터를 다시 불러옴(옛 값 잔상 방지).
        next = if (next.tab == Tab.WEEKLY) next.copy(monthly = null) else next.copy(weekly = null)
        _state.value = next
        if (next.tab == Tab.WEEKLY) loadWeekly() else loadMonthly()
    }

    fun selectTab(tab: Tab) {
        _state.value = _state.value.copy(tab = tab)
        if (tab == Tab.WEEKLY && _state.value.weekly == null) loadWeekly()
        if (tab == Tab.MONTHLY && _state.value.monthly == null) loadMonthly()
    }

    fun loadWeekly() {
        val requested = _state.value.weekStart  // [Codex P1] 호출 시점의 주를 캡처 (기간 전환 레이스 방지)
        viewModelScope.launch {
            // 로딩 시작 시 이전 주 데이터 클리어 → 다른 주 데이터가 잠깐 보이는 잔상 방지
            _state.value = _state.value.copy(loading = true, error = null, weekly = null)
            val date = requested.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val res = repo.weekly(date)
            if (_state.value.weekStart != requested) return@launch  // 응답 도착 전 다른 주로 이동 → stale 무시
            when (res) {
                is ApiResult.Success -> _state.value = _state.value.copy(loading = false, weekly = res.data)
                is ApiResult.Error -> _state.value = _state.value.copy(loading = false, error = res.message)
            }
        }
    }


    fun loadMonthly() {
        val reqYear = _state.value.year; val reqMonth = _state.value.month  // [Codex P1] 호출 시점의 월 캡처 (레이스 방지)
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null, monthly = null)
            val res = repo.monthly(reqYear, reqMonth)
            if (_state.value.year != reqYear || _state.value.month != reqMonth) return@launch  // 다른 월로 이동 → stale 무시
            when (res) {
                is ApiResult.Success -> _state.value = _state.value.copy(loading = false, monthly = res.data)
                is ApiResult.Error -> _state.value = _state.value.copy(loading = false, error = res.message)
            }
        }
    }


    fun previousWeek() {
        _state.value = _state.value.copy(weekStart = _state.value.weekStart.minusWeeks(1))
        loadWeekly()
    }

    fun nextWeek() {
        val next = _state.value.weekStart.plusWeeks(1)
        if (!next.isAfter(thisMonday())) {
            _state.value = _state.value.copy(weekStart = next)
            loadWeekly()
        }
    }

    fun previousMonth() {
        val ym = _state.value
        val newYear = if (ym.month == 1) ym.year - 1 else ym.year
        val newMonth = if (ym.month == 1) 12 else ym.month - 1
        _state.value = ym.copy(year = newYear, month = newMonth)
        loadMonthly()
    }

    fun nextMonth() {
        val ym = _state.value
        val now = todayKst()  // [260726] KST 기준 — 기기 타임존이 달라도 '다음 달' 잠금 경계가 서버와 일치
        val newYear = if (ym.month == 12) ym.year + 1 else ym.year
        val newMonth = if (ym.month == 12) 1 else ym.month + 1
        if (newYear < now.year || (newYear == now.year && newMonth <= now.monthValue)) {
            _state.value = ym.copy(year = newYear, month = newMonth)
            loadMonthly()
        }
    }

    companion object {
        // [260726] 백엔드는 KST 기준으로 날짜를 저장/집계한다 → 리포트의 '오늘/이번 주/이번 달'도 Asia/Seoul 고정.
        //   기기 로컬 타임존(해외·수동 변경)이 KST가 아니면 이번 주 리포트가 서버 집계와 하루 어긋난다.
        //   (WatchSyncActions / MorningInputViewModel 과 동일한 규칙)
        private val KST_ZONE: ZoneId = ZoneId.of("Asia/Seoul")

        private fun todayKst(): LocalDate = LocalDate.now(KST_ZONE)

        private fun thisMonday(): LocalDate {
            val today = todayKst()
            val dow = today.dayOfWeek
            return today.minusDays((dow.value - DayOfWeek.MONDAY.value).toLong())
        }

    }
}
