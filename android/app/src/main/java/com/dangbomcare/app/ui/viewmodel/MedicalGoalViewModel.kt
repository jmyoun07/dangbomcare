package com.dangbomcare.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dangbomcare.app.data.ServiceLocator
import com.dangbomcare.app.data.api.MedicalGoalRequest
import com.dangbomcare.app.data.api.MedicalGoalResponse
import com.dangbomcare.app.data.repo.ApiResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 의료진 목표 (우노 피드백 4) — 조회 + 저장.
 * PIN 잠금은 화면(MedicalGoalScreen)에서 처리한다. 여기는 데이터만 담당.
 */
class MedicalGoalViewModel : ViewModel() {

    sealed class UiState {
        object Loading : UiState()
        data class Ready(val history: List<MedicalGoalResponse>) : UiState()  // 최신순, [0]=현재
        data class Error(val message: String) : UiState()
    }

    sealed class SaveState {
        object Idle : SaveState()
        object Saving : SaveState()
        object Saved : SaveState()
        data class Error(val message: String) : SaveState()
    }

    private val repo = ServiceLocator.medicalGoalRepository

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _saveState = MutableStateFlow<SaveState>(SaveState.Idle)
    val saveState: StateFlow<SaveState> = _saveState.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = UiState.Loading
            when (val res = repo.history()) {
                is ApiResult.Success -> _state.value = UiState.Ready(res.data)
                is ApiResult.Error -> _state.value = UiState.Error(res.message)
            }
        }
    }

    fun save(req: MedicalGoalRequest) {
        if (_saveState.value is SaveState.Saving) return
        viewModelScope.launch {
            _saveState.value = SaveState.Saving
            when (val res = repo.update(req)) {
                is ApiResult.Success -> {
                    _saveState.value = SaveState.Saved
                    load()  // 이력 재로드 — 방금 저장한 항목이 [0]=현재
                }
                is ApiResult.Error -> _saveState.value = SaveState.Error(res.message)
            }
        }
    }

    fun resetSave() { _saveState.value = SaveState.Idle }
}
