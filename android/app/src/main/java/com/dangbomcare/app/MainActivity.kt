package com.dangbomcare.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.dangbomcare.app.data.ServiceLocator
import com.dangbomcare.app.data.auth.AuthRepository
import com.dangbomcare.app.data.auth.SocialLoginHelper
import com.dangbomcare.app.data.health.WatchSyncWorker
import com.dangbomcare.app.ui.navigation.DangbomCareNavGraph
import com.dangbomcare.app.ui.theme.DangbomCareTheme
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 사용자가 거부해도 앱은 동작 — 알림만 못 받음 */ }

    // OAuth 콜백 도착 알림 — 로그인 화면이 관찰
    private val _oauthEvent = MutableStateFlow<OAuthEvent?>(null)
    val oauthEvent: StateFlow<OAuthEvent?> = _oauthEvent.asStateFlow()

    sealed class OAuthEvent {
        object Success : OAuthEvent()
        // [v1.0] 비밀번호 재설정 메일 클릭 → 세션 저장 후 PasswordResetNewPasswordScreen으로 이동
        object PasswordResetReady : OAuthEvent()
        data class Error(val message: String) : OAuthEvent()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        requestNotificationPermissionIfNeeded()
        // [Codex 검토] 알림 채널 등록 + 워치 자동 동기화 worker schedule()은
        // DangbomCareApp.onCreate에서 처리 (Activity 재진입마다 중복 X).
        handleOAuthCallback(intent)
        setContent {
            DangbomCareTheme {
                DangbomCareNavGraph()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 사용자 피드백 — "앱을 켰을 때는 항상 동기화"
        // 포그라운드 진입 시 워치 동기화 트리거 (단 마지막 동기화로부터 5분 이상 지났을 때만 — 과도한 호출 방지)
        triggerForegroundSyncIfDue()
        // [260904] 앱 버전·배터리 최적화·HC 권한 상태를 서버에 보고 (상태 변화 시 즉시, 아니면 6시간에 1회).
        //   시스템 설정에서 최적화를 풀고 돌아오는 순간도 onResume 이라 바로 반영된다.
        com.dangbomcare.app.data.ClientStatusReporter.reportIfDue(this, lifecycleScope)
    }

    private fun triggerForegroundSyncIfDue() {
        val lastMs = com.dangbomcare.app.data.health.WatchSyncTracker.lastSyncMs(this) ?: 0L
        val sinceMs = System.currentTimeMillis() - lastMs
        if (sinceMs < FOREGROUND_SYNC_THROTTLE_MS) return
        // Worker 내부에서 토큰/권한 체크 후 안전하게 skip 또는 실행
        WatchSyncWorker.enqueueImmediateSync(applicationContext)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOAuthCallback(intent)
    }

    /** OAuth callback URI 처리 (dangdangcare://login-callback#access_token=...) */
    private fun handleOAuthCallback(intent: Intent?) {
        val data = intent?.data ?: return
        // [P0 보안] 토큰 노출 방지 — scheme/host만 디버그 빌드에서 출력, fragment/query는 출력 X
        if (BuildConfig.DEBUG) {
            android.util.Log.d("OAuthCallback", "받은 URI scheme=${data.scheme}, host=${data.host}")
        }
        if (data.scheme != "dangdangcare" || data.host != "login-callback") return

        // Supabase가 error 보낸 경우 (e.g., 사용자 인증 거부 / 링크 만료)
        // 에러는 query에 올 수도, fragment에 올 수도 있어서 둘 다 체크
        val fragmentParams = parseFragmentParams(data)
        val errorCode = data.getQueryParameter("error_code")
            ?: data.getQueryParameter("error")
            ?: fragmentParams["error_code"]
            ?: fragmentParams["error"]
        if (!errorCode.isNullOrBlank()) {
            val errorDesc = data.getQueryParameter("error_description")
                ?: fragmentParams["error_description"]
                ?: errorCode
            android.util.Log.w("OAuthCallback", "Supabase error: $errorCode — $errorDesc")
            // 친화적 에러 메시지
            val friendlyMsg = when {
                errorCode == "access_denied" || errorCode == "otp_expired" ||
                    errorDesc.contains("expired", ignoreCase = true) ->
                    "메일 링크가 만료되었거나 이미 사용되었어요.\n새 메일을 다시 받아주세요."
                errorCode == "invalid_request" ->
                    "메일 링크가 유효하지 않아요. 새 메일을 받아주세요."
                else -> "인증 실패: ${errorDesc.replace("+", " ")}"
            }
            _oauthEvent.value = OAuthEvent.Error(friendlyMsg)
            return
        }

        // [v1.0] 콜백 type 파싱 — "recovery"면 비밀번호 재설정 흐름, 그 외(signup/login)는 일반 로그인
        // [코덱스 P1] type 화이트리스트 검증 — 알 수 없는 타입은 거부 (flow 꼬임 방지)
        val callbackType = parseCallbackType(data)
        val allowedTypes = setOf("signup", "magiclink", "recovery", "email_change", "invite", null)
        if (callbackType !in allowedTypes) {
            android.util.Log.w("OAuthCallback", "지원하지 않는 callback type: $callbackType")
            _oauthEvent.value = OAuthEvent.Error("지원하지 않는 인증 링크예요. 새 메일을 다시 받아주세요.")
            return
        }

        // 1) 옛 방식 (implicit) — fragment에 access_token + refresh_token
        // [코덱스 P0 알림] custom scheme implicit는 가로채기 위험 있음 — v1.1에서 HTTPS App Link + PKCE로 전환 예정
        val tokens = SocialLoginHelper.parseCallback(data)
        if (tokens != null) {
            if (BuildConfig.DEBUG) android.util.Log.d("OAuthCallback", "implicit flow OK, type=$callbackType")
            // [코덱스 P1] unstructured CoroutineScope → lifecycleScope (Activity lifecycle bound)
            lifecycleScope.launch {
                val authRepo = ServiceLocator.authRepository
                val result = withContext(Dispatchers.IO) {
                    authRepo.saveOAuthSession(tokens.accessToken, tokens.refreshToken)
                }
                emitCallbackResult(result, callbackType)
            }
            return
        }

        // 2) 새 방식 (PKCE) — query에 token_hash + type (Supabase 신규 프로젝트 기본 흐름)
        val tokenHash = data.getQueryParameter("token_hash")
        val pkceType = data.getQueryParameter("type")
        if (!tokenHash.isNullOrBlank() && !pkceType.isNullOrBlank()) {
            if (BuildConfig.DEBUG) android.util.Log.d("OAuthCallback", "PKCE flow detected, type=$pkceType")
            // [코덱스 P1] lifecycleScope
            lifecycleScope.launch {
                val authRepo = ServiceLocator.authRepository
                val result = withContext(Dispatchers.IO) {
                    authRepo.verifyTokenHash(tokenHash, pkceType)
                }
                emitCallbackResult(result, pkceType)
            }
            return
        }

        // 3) 둘 다 아니면 → 토큰 정보 없음
        if (BuildConfig.DEBUG) {
            // 디버그 빌드에서만 — 토큰 노출 위험 있으니 release에는 절대 추가 X
            android.util.Log.w("OAuthCallback", "no tokens — full fragment=[${data.fragment}]")
            android.util.Log.w("OAuthCallback", "no tokens — full query=[${data.query}]")
        }
        _oauthEvent.value = OAuthEvent.Error("로그인 정보를 받지 못했어요")
    }

    /**
     * [260726] 콜백 처리 결과를 화면 이벤트로 반영 (implicit / PKCE 공통).
     *   세션 충돌(SESSION_CONFLICT)로 덮어쓰기를 막은 경우, 사용자는 홈 등 로그인 화면이 아닌 곳에서
     *   링크를 눌렀을 수 있어 OAuthEvent를 아무도 보지 못한다 → Toast + 로그로 흔적을 남긴다.
     *   ("눌렀는데 아무 일도 안 일어난다"로 끝나면 시니어 환자가 원인을 알 수 없음)
     */
    private fun emitCallbackResult(result: AuthRepository.AuthResult, callbackType: String?) {
        // [260726] 세션 충돌은 '로그인된 상태'에서만 발생 → 로그인/환영 화면은 떠 있지 않다.
        //   OAuthEvent로 남기면 나중에 로그아웃하고 로그인 화면에 갔을 때 뒤늦게 뜨므로,
        //   여기서 Toast + 로그로만 알리고 이벤트는 남기지 않는다.
        if (result is AuthRepository.AuthResult.Error && result.code == "SESSION_CONFLICT") {
            android.util.Log.w(
                "OAuthCallback",
                "[260726] 다른 계정 인증 링크 — 기존 세션 유지하고 콜백 무시 (type=$callbackType)"
            )
            android.widget.Toast.makeText(this, result.message, android.widget.Toast.LENGTH_LONG).show()
            return
        }
        _oauthEvent.value = when (result) {
            is AuthRepository.AuthResult.Success -> {
                if (callbackType == "recovery") OAuthEvent.PasswordResetReady
                else { WatchSyncWorker.enqueueImmediateSync(applicationContext); OAuthEvent.Success }
            }
            is AuthRepository.AuthResult.Error -> OAuthEvent.Error(result.message)
        }
    }

    /** OAuth callback URI에서 type 파라미터 추출 ("recovery" 등) */
    private fun parseCallbackType(uri: android.net.Uri): String? {
        val raw = uri.fragment ?: uri.query ?: return null
        return raw.split("&")
            .firstOrNull { it.startsWith("type=") }
            ?.removePrefix("type=")
            ?.let { android.net.Uri.decode(it) }
    }

    /** Fragment 문자열을 key→value Map으로 파싱 (Supabase 에러/토큰이 fragment로 옴) */
    private fun parseFragmentParams(uri: android.net.Uri): Map<String, String> {
        val raw = uri.fragment ?: return emptyMap()
        return raw.split("&")
            .mapNotNull { p ->
                val parts = p.split("=", limit = 2)
                if (parts.size == 2) parts[0] to android.net.Uri.decode(parts[1]) else null
            }.toMap()
    }

    fun consumeOAuthEvent() {
        _oauthEvent.value = null
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
    }

    companion object {
        @JvmStatic
        var instance: MainActivity? = null
            private set

        // 포그라운드 진입 시 동기화 throttle (5분) — 앱 토글 시 N번 호출되는 것 방지
        private const val FOREGROUND_SYNC_THROTTLE_MS: Long = 5 * 60 * 1000L
    }
}
