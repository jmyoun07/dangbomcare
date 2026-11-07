package com.dangbomcare.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dangbomcare.app.data.ServiceLocator
import com.dangbomcare.app.data.auth.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * [코덱스 P0] 회원가입 직후 "인증 완료 확인" signIn 재시도용 임시 자격정보 보관소.
 *   - Navigation route param/SavedStateHandle에 비밀번호 평문 저장 X (크래시 로그/백스택 노출 위험)
 *   - 프로세스 in-memory only — 앱 종료 시 자동 소멸
 *   - 회원가입 완료 또는 EmailVerifyWaitScreen 이탈 시 즉시 clear()
 *
 * [코덱스 P1 반영] email/password를 분리 @Volatile로 두면 set 중간 상태에서 다른 스레드가 mismatch를
 *   읽을 수 있음. AtomicReference로 한 객체를 원자적으로 교체.
 */
object PendingSignupCredentials {
    private data class Pending(val email: String, val password: String)
    private val ref = java.util.concurrent.atomic.AtomicReference<Pending?>(null)

    val email: String? get() = ref.get()?.email

    fun set(email: String, password: String) {
        ref.set(Pending(email, password))
    }
    /** 요청한 email과 보관된 email이 일치할 때만 password 반환 (race로 다른 가입의 비번 노출 방지). */
    fun getPassword(forEmail: String): String? =
        ref.get()?.takeIf { it.email == forEmail }?.password
    fun clear() {
        ref.set(null)
    }
}

class AuthViewModel : ViewModel() {

    sealed class UiState {
        object Idle : UiState()
        object Loading : UiState()
        object Success : UiState()
        data class Error(val message: String, val code: String = "") : UiState()
        // [v1.0] 회원가입 후 이메일 인증 대기 — UI는 EmailVerifyWaitScreen으로 이동
        // [코덱스 P0 수정] password는 더 이상 state로 전달 X (PendingSignupCredentials 사용)
        data class EmailConfirmationRequired(val email: String) : UiState()
    }

    private val authRepo = ServiceLocator.authRepository

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun signIn(email: String, password: String) {
        if (email.isBlank() || password.isBlank()) {
            _state.value = UiState.Error("이메일과 비밀번호를 입력해주세요")
            return
        }
        viewModelScope.launch {
            _state.value = UiState.Loading
            val res = authRepo.signIn(email.trim(), password)
            _state.value = when (res) {
                is AuthRepository.AuthResult.Success -> UiState.Success
                is AuthRepository.AuthResult.Error -> UiState.Error(res.message, res.code)
            }
        }
    }

    fun signUp(email: String, password: String) {
        if (email.isBlank() || password.length < 6) {
            _state.value = UiState.Error("이메일과 6자 이상 비밀번호를 입력해주세요")
            return
        }
        viewModelScope.launch {
            _state.value = UiState.Loading
            val res = authRepo.signUp(email.trim(), password)
            _state.value = when (res) {
                is AuthRepository.AuthResult.Success -> UiState.Success
                is AuthRepository.AuthResult.Error -> {
                    // [v1.0] 이메일 인증 대기 → EmailVerifyWaitScreen으로 이동
                    // [코덱스 P0] password는 route/state X → 메모리 singleton에 임시 보관
                    if (res.code == "EMAIL_CONFIRMATION_REQUIRED") {
                        PendingSignupCredentials.set(email.trim(), password)
                        UiState.EmailConfirmationRequired(email.trim())
                    } else {
                        UiState.Error(res.message, res.code)
                    }
                }
            }
        }
    }

    /** [v1.0] "인증 완료 확인" 버튼 — signIn 재시도해서 이메일 인증 상태 검증. */
    fun checkEmailVerified(email: String, password: String) {
        viewModelScope.launch {
            _state.value = UiState.Loading
            val res = authRepo.checkEmailVerified(email.trim(), password)
            _state.value = when (res) {
                is AuthRepository.AuthResult.Success -> UiState.Success
                is AuthRepository.AuthResult.Error -> UiState.Error(res.message, res.code)
            }
        }
    }

    /** [v1.0] 인증 메일 재발송. */
    fun resendConfirmation(email: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val res = authRepo.resendSignupConfirmation(email.trim())
            when (res) {
                is AuthRepository.AuthResult.Success -> onResult(true, "인증 메일을 다시 보냈어요.")
                is AuthRepository.AuthResult.Error -> onResult(false, res.message)
            }
        }
    }

    /** [v1.0] 비밀번호 재설정 메일 발송. */
    fun requestPasswordReset(email: String) {
        if (email.isBlank()) {
            _state.value = UiState.Error("이메일을 입력해주세요")
            return
        }
        viewModelScope.launch {
            _state.value = UiState.Loading
            val res = authRepo.requestPasswordReset(email.trim())
            _state.value = when (res) {
                is AuthRepository.AuthResult.Success -> UiState.Success
                is AuthRepository.AuthResult.Error -> UiState.Error(res.message, res.code)
            }
        }
    }

    /** [v1.0] 새 비밀번호 저장. */
    fun updatePassword(newPassword: String) {
        if (newPassword.length < 6) {
            _state.value = UiState.Error("6자 이상 입력해주세요")
            return
        }
        viewModelScope.launch {
            _state.value = UiState.Loading
            val res = authRepo.updatePassword(newPassword)
            _state.value = when (res) {
                is AuthRepository.AuthResult.Success -> UiState.Success
                is AuthRepository.AuthResult.Error -> UiState.Error(res.message, res.code)
            }
        }
    }

    /** 외부 상태 초기화 (화면 전환 시 Idle로 reset). */
    fun resetState() {
        _state.value = UiState.Idle
    }

    /**
     * [260722 P0 프라이버시 CRITICAL — 근본수정]
     * 기존: viewModelScope.launch만 던지고 즉시 반환 → 호출자(SettingsScreen)가 곧바로 onLogout()으로
     *      네비게이션 popUpTo(0){inclusive=true} → 백스택 파괴 → ViewModel 파괴 → 코루틴 취소 →
     *      SharedPreferences clear가 미실행되어 사용자 데이터/토큰 잔존.
     * 수정: onDone 콜백 인자로 받아, authRepo.signOut() 완료 후에만 호출자에게 통지.
     *      호출자는 onDone 안에서 네비게이션을 수행해야 함.
     */
    fun signOut(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            authRepo.signOut()
            _state.value = UiState.Idle
            onDone()
        }
    }

    suspend fun isLoggedIn(): Boolean = authRepo.isLoggedIn()

    fun resetError() {
        if (_state.value is UiState.Error) _state.value = UiState.Idle
    }

    /** OAuth 콜백 에러 등 외부에서 발생한 에러 메시지 표시. */
    fun setExternalError(message: String) {
        _state.value = UiState.Error(message)
    }
}
