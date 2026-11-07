package com.dangbomcare.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dangbomcare.app.data.ServiceLocator
import com.dangbomcare.app.data.api.DailySummaryResponse
import com.dangbomcare.app.data.repo.ApiResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class DailySummaryViewModel : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val date: LocalDate = LocalDate.now(),
        val data: DailySummaryResponse? = null,
        val error: String? = null
    )

    private val repo = ServiceLocator.scoreRepository

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    // ⭐ Codex P0-4: 진행 중 조회 Job — 빠른 날짜 전환 시 응답 순서 race 방지
    private var loadJob: Job? = null

    init {
        load(LocalDate.now())
    }

    fun load(date: LocalDate) {
        // 이전 요청 취소
        loadJob?.cancel()
        // 즉시 상태 갱신: 데이터 비우고 로딩 진입 — 이전 날짜 데이터 깜빡임 차단
        _state.value = _state.value.copy(loading = true, error = null, date = date, data = null)
        loadJob = viewModelScope.launch {
            val res = repo.dailySummary(date.format(DateTimeFormatter.ISO_LOCAL_DATE))
            // 도착 시점 선택 날짜와 다르면 무시
            if (_state.value.date != date) return@launch
            when (res) {
                is ApiResult.Success -> {
                    // 응답 date 검증 (혹시 다른 날짜면 데이터로 사용 X)
                    val respDate = try { LocalDate.parse(res.data.date) } catch (e: Exception) { null }
                    if (respDate != null && respDate != date) {
                        _state.value = _state.value.copy(loading = false, data = null)
                    } else {
                        _state.value = _state.value.copy(loading = false, data = res.data)
                    }
                }
                is ApiResult.Error -> _state.value = _state.value.copy(loading = false, error = res.message)
            }
        }
    }

    fun goPreviousDay() = load(_state.value.date.minusDays(1))
    fun goNextDay() {
        val next = _state.value.date.plusDays(1)
        if (!next.isAfter(LocalDate.now())) load(next)
    }
}
