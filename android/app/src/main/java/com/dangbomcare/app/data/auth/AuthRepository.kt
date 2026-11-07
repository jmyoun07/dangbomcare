package com.dangbomcare.app.data.auth

import android.content.Context
import com.dangbomcare.app.data.ServiceLocator
import com.dangbomcare.app.data.api.ApiService
import com.dangbomcare.app.data.api.AutoConfirmRequest
import com.dangbomcare.app.data.api.SupabaseSignInRequest
import kotlinx.coroutines.flow.firstOrNull

class AuthRepository(
    private val tokenStorage: TokenStorage,
    private val apiService: ApiService
) {

    sealed class AuthResult {
        object Success : AuthResult()
        data class Error(val code: String, val message: String) : AuthResult()
    }

    /**
     * Supabase Auth 에러 응답을 한국어 사용자 친화 메시지로 변환.
     * 응답 본문 예: {"code":429,"error_code":"over_email_send_rate_limit","msg":"..."}
     */
    private fun friendlySupabaseError(httpCode: Int, body: String): String {
        // error_code 추출
        val errorCode = Regex(""""error_code"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.getOrNull(1).orEmpty()
        val msg = Regex(""""msg"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.getOrNull(1).orEmpty()
        // "after N seconds" 추출 (rate limit용)
        val secs = Regex("""after\s+(\d+)\s*seconds""").find(msg)?.groupValues?.getOrNull(1)

        return when {
            errorCode == "over_email_send_rate_limit" || (httpCode == 429 && msg.contains("seconds")) -> {
                val waitText = secs?.let { secsStr ->
                    val s = secsStr.toIntOrNull() ?: 60
                    when {
                        s >= 3600 -> "${s / 3600}시간 후"
                        s >= 60 -> "${s / 60}분 후"
                        else -> "${s}초 후"
                    }
                } ?: "약 1시간 후"
                "이메일 발송 제한 (Supabase 무료 SMTP는 시간당 3~4회 제한).\n$waitText 다시 시도해주세요."
            }
            errorCode == "signup_disabled" ->
                "현재 회원가입이 중단되어 있습니다. 잠시 후 다시 시도해주세요."
            errorCode == "email_address_invalid" || msg.contains("invalid") && msg.contains("email") ->
                "유효하지 않은 이메일 형식입니다."
            errorCode == "user_already_exists" || errorCode == "email_exists" || msg.contains("already") ->
                "이미 가입된 이메일입니다. 로그인 화면으로 이동해주세요."
            errorCode == "weak_password" || msg.contains("password") && msg.contains("weak") ->
                "비밀번호가 너무 약합니다. 6자 이상 입력해주세요."
            errorCode == "invalid_credentials" || (httpCode == 400 && msg.contains("credentials")) ->
                "이메일 또는 비밀번호가 올바르지 않습니다."
            errorCode == "email_not_confirmed" ->
                "이메일 인증이 완료되지 않았습니다. 받은 메일을 확인해주세요."
            httpCode == 429 ->
                "요청이 너무 많아요. 잠시 후 다시 시도해주세요."
            httpCode in 500..599 ->
                "서버 일시 오류입니다. 잠시 후 다시 시도해주세요."
            else -> msg.ifBlank { "오류가 발생했습니다 (${httpCode})" }
        }
    }

    suspend fun signIn(email: String, password: String): AuthResult {
        return try {
            val res = SupabaseAuthClient.api.signIn(req = SupabaseSignInRequest(email, password))
            if (res.isSuccessful) {
                val body = res.body() ?: return AuthResult.Error("EMPTY", "응답이 비어있습니다")
                val userId = body.userId ?: return AuthResult.Error("NO_USER", "사용자 정보를 받지 못했습니다")
                if (!body.hasSession) {
                    return AuthResult.Error(
                        "EMAIL_CONFIRM",
                        "이메일 인증이 아직 완료되지 않았어요. 받으신 메일의 링크를 먼저 눌러주세요."
                    )
                }
                val accessToken = body.accessToken
                    ?: return AuthResult.Error("NO_SESSION", "로그인 세션을 받지 못했습니다. 다시 시도해주세요.")
                tokenStorage.saveTokens(accessToken, body.refreshToken ?: "", userId, email)
                AuthResult.Success
            } else {
                val errBody = res.errorBody()?.string().orEmpty()
                AuthResult.Error("AUTH_FAIL", friendlySupabaseError(res.code(), errBody))
            }
        } catch (e: Exception) {
            AuthResult.Error("NETWORK", "네트워크 오류가 발생했습니다. 잠시 후 다시 시도해주세요.")
        }
    }

    suspend fun signUp(email: String, password: String): AuthResult {
        return try {
            val res = SupabaseAuthClient.api.signUp(SupabaseSignInRequest(email, password))
            if (res.isSuccessful) {
                val body = res.body() ?: return AuthResult.Error("EMPTY", "응답이 비어있습니다")
                val userId = body.userId ?: return AuthResult.Error("NO_USER", "사용자 정보를 받지 못했습니다")

                // [v1.0] 중복 가입 감지 — Supabase는 보안상 이미 가입된 메일이면 200 응답을 주지만
                // identities가 빈 배열로 옴. top-level과 user 내부 둘 다 체크 (Supabase 응답 형식 양쪽 지원).
                if (com.dangbomcare.app.BuildConfig.DEBUG) {
                    android.util.Log.d("AuthRepo", "signUp response: hasSession=${body.hasSession}, " +
                        "topIdentities=${body.identities?.size}, userIdentities=${body.user?.identities?.size}, " +
                        "isExistingUser=${body.isExistingUser}, emailConfirmedAt=${body.emailConfirmedAt}, " +
                        "userEmailConfirmedAt=${body.user?.emailConfirmedAt}")
                }
                if (body.isExistingUser) {
                    return AuthResult.Error(
                        "EMAIL_ALREADY_EXISTS",
                        "이미 가입된 이메일입니다.\n로그인 화면에서 비밀번호를 입력해주세요."
                    )
                }

                // 즉시 세션 발급된 경우 (이메일 인증 OFF) → 토큰 저장 + 성공
                if (body.hasSession) {
                    val accessToken = body.accessToken
                        ?: return AuthResult.Error("NO_SESSION", "회원가입 세션을 받지 못했습니다. 다시 시도해주세요.")
                    tokenStorage.saveTokens(accessToken, body.refreshToken ?: "", userId, email)
                    return AuthResult.Success
                }

                // [P0 보안] dev-auto-confirm은 debug 빌드에서만 사용 (release에서는 이메일 인증 우회 불가)
                if (com.dangbomcare.app.BuildConfig.DEBUG) {
                    val confirmed = tryAutoConfirm(email)
                    if (confirmed) {
                        val signInResult = signIn(email, password)
                        return signInResult
                    }
                }

                // [v1.0] 이메일 인증 대기 → UI는 EmailVerifyWaitScreen으로 이동
                AuthResult.Error(
                    "EMAIL_CONFIRMATION_REQUIRED",
                    "${email}로 인증 메일을 보냈어요."
                )
            } else {
                val errBody = res.errorBody()?.string().orEmpty()
                AuthResult.Error("SIGNUP_FAIL", friendlySupabaseError(res.code(), errBody))
            }
        } catch (e: Exception) {
            AuthResult.Error("NETWORK", "네트워크 오류가 발생했습니다. 잠시 후 다시 시도해주세요.")
        }
    }

    /** 백엔드 dev-auto-confirm 호출 (실패 시 false). */
    private suspend fun tryAutoConfirm(email: String): Boolean {
        return try {
            val res = apiService.devAutoConfirm(AutoConfirmRequest(email = email))
            res.isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    /**
     * [260726 보안] 딥링크 콜백이 '지금 로그인된 계정과 다른 계정'의 세션으로 덮어쓰는 것을 막는다.
     *   실제 위험: 환자 A가 앱을 쓰는 중 메일함에 남아있던 옛 매직링크/재설정 링크를 누르면
     *   토큰만 계정 B로 바뀌고 화면은 A의 데이터를 그대로 보여준다 → 아침 입력(공복혈당)이 B 계정에 기록.
     *   같은 계정 재인증(user_id 동일)은 정상 흐름이므로 그대로 통과시킨다.
     */
    private suspend fun isForeignSessionOverwrite(incomingUserId: String): Boolean {
        if (!tokenStorage.isLoggedIn()) return false                 // 로그인 상태 아님 → 정상 로그인
        val currentUserId = tokenStorage.userIdFlow.firstOrNull()
        if (currentUserId.isNullOrBlank()) return false              // 계정 식별 불가 → 기존 동작 유지
        return currentUserId != incomingUserId
    }

    /** [260726] 위 방어에 걸렸을 때 돌려줄 결과 — MainActivity가 code로 판별해 Toast/로그를 남긴다. */
    private val sessionConflictError = AuthResult.Error(
        "SESSION_CONFLICT",
        "이미 다른 계정으로 로그인되어 있어요.\n설정에서 로그아웃한 뒤 링크를 다시 눌러주세요."
    )

    /**
     * OAuth 콜백으로 받은 토큰을 저장 (Google / Kakao 로그인).
     * refresh_token으로 user info 가져와서 user_id + email 추출.
     */
    suspend fun saveOAuthSession(accessToken: String, refreshToken: String): AuthResult {
        return try {
            // refresh로 사용자 정보 확인 (user_id, email)
            val res = SupabaseAuthClient.api.refresh(
                req = SupabaseRefreshRequest(refresh_token = refreshToken)
            )
            if (res.isSuccessful) {
                val body = res.body() ?: return AuthResult.Error("EMPTY", "응답이 비어있습니다")
                val userId = body.userId ?: return AuthResult.Error("NO_USER", "사용자 정보 없음")
                // [260726] 토큰 저장 전에 계정 일치 확인 — 다른 계정이면 기존 세션 유지하고 콜백 무시
                if (isForeignSessionOverwrite(userId)) {
                    android.util.Log.w("AuthRepo", "[260726] OAuth 콜백 무시 — 다른 계정 세션 덮어쓰기 차단")
                    return sessionConflictError
                }
                val email = body.user?.email ?: ""
                tokenStorage.saveTokens(
                    accessToken = body.accessToken ?: accessToken,
                    refreshToken = body.refreshToken ?: refreshToken,
                    userId = userId,
                    email = email
                )
                AuthResult.Success
            } else {
                // refresh 실패 시에도 access_token 만이라도 저장 시도
                AuthResult.Error("OAUTH_REFRESH_FAIL", "OAuth 세션을 확인할 수 없습니다. 다시 로그인해주세요.")
            }
        } catch (e: Exception) {
            AuthResult.Error("NETWORK", "네트워크 오류")
        }
    }

    /**
     * [v1.0] 이메일 인증 완료 확인 — "인증 완료 확인" 버튼이 호출.
     * 내부적으로 signIn을 시도하여 이메일 인증 상태를 확인:
     *  - signIn 성공 → 이메일 인증 완료 → 세션 저장 + Success
     *  - "email_not_confirmed" 에러 → 아직 미인증
     *  - 그 외 → 일반 에러
     */
    suspend fun checkEmailVerified(email: String, password: String): AuthResult {
        return try {
            val res = SupabaseAuthClient.api.signIn(req = SupabaseSignInRequest(email, password))
            if (res.isSuccessful) {
                val body = res.body() ?: return AuthResult.Error("EMPTY", "응답이 비어있습니다")
                val userId = body.userId ?: return AuthResult.Error("NO_USER", "사용자 정보를 받지 못했습니다")
                val accessToken = body.accessToken
                    ?: return AuthResult.Error("EMAIL_NOT_CONFIRMED", "아직 이메일 인증이 완료되지 않았어요.\n메일함의 인증 링크를 먼저 눌러주세요.")
                tokenStorage.saveTokens(accessToken, body.refreshToken ?: "", userId, email)
                AuthResult.Success
            } else {
                val errBody = res.errorBody()?.string().orEmpty()
                val errorCode = Regex(""""error_code"\s*:\s*"([^"]+)"""").find(errBody)?.groupValues?.getOrNull(1).orEmpty()
                if (errorCode == "email_not_confirmed" || errBody.contains("not confirmed", ignoreCase = true)) {
                    AuthResult.Error(
                        "EMAIL_NOT_CONFIRMED",
                        "아직 이메일 인증이 완료되지 않았어요.\n메일함의 인증 링크를 먼저 눌러주세요."
                    )
                } else {
                    AuthResult.Error("CHECK_FAIL", friendlySupabaseError(res.code(), errBody))
                }
            }
        } catch (e: Exception) {
            AuthResult.Error("NETWORK", "네트워크 오류가 발생했습니다. 잠시 후 다시 시도해주세요.")
        }
    }

    /**
     * [v1.0] 회원가입 인증 메일 재발송 (사용자가 메일 못 받았을 때).
     */
    suspend fun resendSignupConfirmation(email: String): AuthResult {
        return try {
            val res = SupabaseAuthClient.api.resendEmailConfirmation(
                SupabaseResendRequest(email = email, type = "signup")
            )
            if (res.isSuccessful) {
                AuthResult.Success
            } else {
                val errBody = res.errorBody()?.string().orEmpty()
                AuthResult.Error("RESEND_FAIL", friendlySupabaseError(res.code(), errBody))
            }
        } catch (e: Exception) {
            AuthResult.Error("NETWORK", "네트워크 오류가 발생했습니다.")
        }
    }

    /**
     * [v1.0] 비밀번호 재설정 메일 발송.
     */
    suspend fun requestPasswordReset(email: String): AuthResult {
        return try {
            val res = SupabaseAuthClient.api.recoverPassword(
                SupabaseRecoverRequest(email = email)
            )
            if (res.isSuccessful) {
                AuthResult.Success
            } else {
                val errBody = res.errorBody()?.string().orEmpty()
                AuthResult.Error("RECOVER_FAIL", friendlySupabaseError(res.code(), errBody))
            }
        } catch (e: Exception) {
            AuthResult.Error("NETWORK", "네트워크 오류가 발생했습니다.")
        }
    }

    /**
     * [v1.0] 새 비밀번호 설정 (비밀번호 재설정 메일 클릭 후 → 세션 활성 상태에서 호출).
     */
    suspend fun updatePassword(newPassword: String): AuthResult {
        return try {
            val accessToken = tokenStorage.getAccessTokenBlocking()
                ?: return AuthResult.Error("NO_SESSION", "세션이 만료되었어요. 비밀번호 재설정 메일을 다시 받아주세요.")
            val res = SupabaseAuthClient.api.updateUser(
                authorization = "Bearer $accessToken",
                req = SupabaseUpdateUserRequest(password = newPassword)
            )
            if (res.isSuccessful) {
                AuthResult.Success
            } else {
                val errBody = res.errorBody()?.string().orEmpty()
                AuthResult.Error("UPDATE_PW_FAIL", friendlySupabaseError(res.code(), errBody))
            }
        } catch (e: Exception) {
            AuthResult.Error("NETWORK", "네트워크 오류가 발생했습니다.")
        }
    }

    /**
     * [v1.0] PKCE 흐름의 token_hash로 세션 발급.
     *   - Supabase 신규 프로젝트는 비번 재설정/이메일 인증 메일 링크를
     *     `dangdangcare://login-callback?token_hash=xxx&type=recovery` 형태로 발송.
     *   - 이걸 verify endpoint로 보내면 access_token 발급 → 세션 저장.
     */
    suspend fun verifyTokenHash(tokenHash: String, type: String): AuthResult {
        return try {
            val res = SupabaseAuthClient.api.verifyTokenHash(
                SupabaseTokenHashRequest(type = type, token_hash = tokenHash)
            )
            if (res.isSuccessful) {
                val body = res.body() ?: return AuthResult.Error("EMPTY", "응답이 비어있습니다")
                val userId = body.userId ?: return AuthResult.Error("NO_USER", "사용자 정보 없음")
                // [260726] PKCE 링크(token_hash)도 동일 방어 — 다른 계정 링크로는 세션을 갈아끼우지 않는다.
                if (isForeignSessionOverwrite(userId)) {
                    android.util.Log.w("AuthRepo", "[260726] token_hash 콜백 무시 — 다른 계정 세션 덮어쓰기 차단")
                    return sessionConflictError
                }
                val accessToken = body.accessToken
                    ?: return AuthResult.Error("NO_SESSION", "세션 토큰을 받지 못했습니다.")
                // [코덱스 P1] refresh_token이 없으면 반쪽 세션 — 이후 토큰 갱신 실패 → 명시적 에러
                val refreshToken = body.refreshToken
                    ?: return AuthResult.Error("NO_SESSION", "갱신 토큰을 받지 못했습니다. 메일 링크를 다시 받아주세요.")
                val email = body.user?.email ?: body.email ?: ""
                tokenStorage.saveTokens(accessToken, refreshToken, userId, email)
                AuthResult.Success
            } else {
                val errBody = res.errorBody()?.string().orEmpty()
                AuthResult.Error("VERIFY_FAIL", friendlySupabaseError(res.code(), errBody))
            }
        } catch (e: Exception) {
            AuthResult.Error("NETWORK", "네트워크 오류가 발생했습니다.")
        }
    }

    /**
     * 로그아웃 — 토큰 + 이전 사용자별 SharedPreferences 전부 정리.
     *   이 정리를 안 하면 새 사용자가 로그인해도 이전 사용자의 draft/알림/생활패턴이
     *   폼에 그대로 채워짐. (계정 격리)
     */
    suspend fun signOut() {
        tokenStorage.clear()
        val context: Context = ServiceLocator.appContext
        listOf(
            "dangbom_input_draft",   // 아침 입력 draft
            "life_pattern",          // 생활패턴 시각
            "dangdangcare_alarms",   // 알람 시각·ON/OFF
            // [260726] 아래 2개가 남아 다음 사용자에게 그대로 적용되던 문제 수정.
            //   dangdangcare_watch_sync: initial_backfill_done=true 가 남으면 새 사용자는 90일 백필을
            //   영구히 못 받아 월간 리포트가 빈 채로 남는다. (last_sync_ms/full_refresh 도 이전 사용자 값)
            "dangdangcare_watch_sync",
            //   dangdangcare_milestones: 이전 사용자의 주간 달성 축하 표시 여부가 남아 새 사용자 팝업이 누락된다.
            "dangdangcare_milestones",
        ).forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE)
                .edit().clear().apply()
        }
    }

    suspend fun isLoggedIn(): Boolean = tokenStorage.isLoggedIn()

    /**
     * access_token 만료 시 refresh_token으로 새 액세스 토큰 발급.
     * 성공하면 DataStore에 새 토큰 저장 + true 반환.
     * refresh_token도 만료/없음이면 false (재로그인 필요).
     */
    suspend fun tryRefresh(): Boolean {
        val refresh = tokenStorage.getRefreshTokenBlocking() ?: return false
        if (refresh.isBlank()) return false
        return try {
            val res = SupabaseAuthClient.api.refresh(req = SupabaseRefreshRequest(refresh_token = refresh))
            if (res.isSuccessful) {
                val body = res.body() ?: return false
                val userId = body.userId ?: return false
                val newAccess = body.accessToken ?: return false
                val email = tokenStorage.emailFlow.firstOrNull() ?: ""
                tokenStorage.saveTokens(
                    accessToken = newAccess,
                    refreshToken = body.refreshToken ?: refresh,
                    userId = userId,
                    email = email
                )
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }
}
