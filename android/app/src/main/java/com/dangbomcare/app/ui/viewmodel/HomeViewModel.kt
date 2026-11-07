package com.dangbomcare.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dangbomcare.app.data.ServiceLocator
import com.dangbomcare.app.data.api.AIFeedbackToday
import com.dangbomcare.app.data.api.MedicalGoalResponse
import com.dangbomcare.app.data.api.ScoreTodayResponse
import com.dangbomcare.app.data.api.UserMeResponse
import com.dangbomcare.app.data.repo.ApiResult
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HomeViewModel : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val user: UserMeResponse? = null,
        val score: ScoreTodayResponse? = null,
        val feedback: AIFeedbackToday? = null,
        val medicalGoal: MedicalGoalResponse? = null,  // [우노 4] 의료진 목표 (홈 상단 표시)
        val error: String? = null,
        val needsMorningInput: Boolean = false
    )

    private val userRepo = ServiceLocator.userRepository
    private val scoreRepo = ServiceLocator.scoreRepository
    private val goalRepo = ServiceLocator.medicalGoalRepository

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            // 이미 데이터가 있으면 로딩 표시 안 함 (캐시된 데이터 즉시 보임, 백그라운드 refresh)
            val hasCachedData = _state.value.score != null || _state.value.user != null
            if (!hasCachedData) {
                _state.value = _state.value.copy(loading = true, error = null)
            } else {
                // [버그 fix] 사용자 피드백 — 로그인 직후 팝업이 0.1초 뜨고/사라지고/다시 뜨는 점멸 발생.
                //   원인: 캐시 데이터가 있을 때 needsMorningInput=false로 강제 reset → 백엔드 응답 도착 시 다시 true → 점멸.
                //   해결: 캐시 reset 시 needsMorningInput은 그대로 유지. 응답 도착 시 새 값으로만 갱신.
                _state.value = _state.value.copy(error = null)
            }

            // ⭐ API 병렬 호출 — 직렬 호출 시 ~2초 → 병렬 ~0.5초
            val userRes: ApiResult<UserMeResponse>
            val scoreRes: ApiResult<ScoreTodayResponse>
            val feedbackRes: ApiResult<AIFeedbackToday>
            val goalRes: ApiResult<MedicalGoalResponse>
            coroutineScope {
                val u = async { userRepo.getMe() }
                val s = async { scoreRepo.today() }
                val f = async { scoreRepo.todayFeedback() }
                val g = async { goalRepo.get() }  // [우노 4] 의료진 목표
                userRes = u.await()
                scoreRes = s.await()
                feedbackRes = f.await()
                goalRes = g.await()
            }

            val user = (userRes as? ApiResult.Success)?.data
            val score = (scoreRes as? ApiResult.Success)?.data
            val feedback = (feedbackRes as? ApiResult.Success)?.data
            val goal = (goalRes as? ApiResult.Success)?.data

            // 코덱스 P0 — score API 실패 분기 정교화.
            // NOT_FOUND만 "입력 필요"로 해석. 그 외 에러는 명시적 오류 화면으로 보냄.
            val scoreError = scoreRes as? ApiResult.Error
            val needsMorning = scoreError != null && scoreError.code == "NOT_FOUND"

            val error = when {
                userRes is ApiResult.Error -> userRes.message
                // score 또는 feedback API가 NOT_FOUND가 아닌 다른 에러로 실패하면 명시적 표시
                scoreError != null && scoreError.code != "NOT_FOUND" ->
                    "점수 데이터 불러오기 실패: ${scoreError.message ?: "잠시 후 다시 시도해주세요"}"
                feedbackRes is ApiResult.Error && (feedbackRes.code ?: "") != "NOT_FOUND" -> null  // feedback 없음은 무시
                else -> null
            }

            _state.value = UiState(
                loading = false,
                user = user,
                score = score,
                feedback = feedback,
                medicalGoal = goal,
                needsMorningInput = needsMorning,
                error = error
            )
        }
    }
}
