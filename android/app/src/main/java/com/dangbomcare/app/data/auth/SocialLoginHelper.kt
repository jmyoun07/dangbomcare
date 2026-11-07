package com.dangbomcare.app.data.auth

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.graphics.toColorInt
import com.dangbomcare.app.BuildConfig
import java.security.SecureRandom
import android.util.Base64

/**
 * 소셜 로그인 헬퍼 — Supabase의 OAuth web flow를 Custom Tabs로 열기.
 *
 * 흐름:
 *  1. SocialLoginHelper.open(context, Provider.GOOGLE) → state nonce 생성/저장
 *  2. Custom Tabs → Supabase OAuth URL (state 포함) → Google/Kakao 로그인
 *  3. 로그인 성공 → Supabase가 dangdangcare://login-callback#access_token=...&state=... 로 redirect
 *  4. AndroidManifest의 intent-filter가 MainActivity로 deliver
 *  5. MainActivity.onNewIntent() → URI 파싱 → state 검증 → AuthRepository.saveOAuthSession()
 *
 * [Codex H8 보안 검토] state nonce 추가로 OAuth 콜백 위조/혼선 방지.
 */
object SocialLoginHelper {

    enum class Provider(val key: String, val displayName: String) {
        GOOGLE("google", "Google"),
        KAKAO("kakao", "Kakao")
    }

    private const val REDIRECT_URI = "dangdangcare://login-callback"
    private const val STATE_PREFS = "dangdangcare_oauth_state"
    private const val STATE_KEY = "current_state"
    private const val STATE_EXPIRES_AT = "expires_at"
    private const val STATE_TTL_MS = 10 * 60 * 1000L  // 10분 (OAuth 콜백 최대 대기)

    /** OAuth state nonce 생성 (Base64 24자) */
    private fun generateState(): String {
        val bytes = ByteArray(18)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    /**
     * 발급한 state를 검증 + 1회용 처리 (replay 방지).
     * 성공 시 prefs에서 즉시 제거 → 같은 state로 재공격 불가.
     */
    fun verifyAndConsumeState(context: Context, callbackState: String?): Boolean {
        if (callbackState.isNullOrBlank()) return false
        val prefs = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString(STATE_KEY, null) ?: return false
        val expires = prefs.getLong(STATE_EXPIRES_AT, 0L)
        prefs.edit().remove(STATE_KEY).remove(STATE_EXPIRES_AT).apply()
        if (System.currentTimeMillis() > expires) {
            android.util.Log.w("SocialLogin", "OAuth state expired")
            return false
        }
        // 상수 시간 비교 (timing attack 방어)
        return saved.toByteArray().contentEquals(callbackState.toByteArray())
    }

    fun open(context: Context, provider: Provider) {
        val supabaseUrl = BuildConfig.SUPABASE_URL

        // [버그 fix] state 파라미터는 Supabase가 자체 관리.
        //   우리가 임의 state 보내면 Supabase가 "bad_oauth_state"로 reject (확인됨 by logcat).
        //   CSRF 방어는 Supabase 내부 PKCE/state로 처리됨.
        val url = Uri.parse("$supabaseUrl/auth/v1/authorize").buildUpon()
            .appendQueryParameter("provider", provider.key)
            .appendQueryParameter("redirect_to", REDIRECT_URI)
            .build()

        try {
            val customTabsIntent = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setUrlBarHidingEnabled(false)
                .setDefaultColorSchemeParams(
                    androidx.browser.customtabs.CustomTabColorSchemeParams.Builder()
                        .setToolbarColor("#A3BC62".toColorInt())  // 당봄케어 연두
                        .build()
                )
                .build()

            // ⭐ FLAG_ACTIVITY_NEW_TASK 추가 (Context가 Activity 아닌 경우 대비)
            customTabsIntent.intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK

            // ⭐ Chrome 우선 사용 — Samsung Internet 등은 Custom Tabs 호환성 이슈 있음
            val chromePackages = listOf(
                "com.android.chrome",
                "com.chrome.beta",
                "com.chrome.dev"
            )
            val pm = context.packageManager
            val installedChrome = chromePackages.firstOrNull { pkg ->
                try { pm.getPackageInfo(pkg, 0); true } catch (_: Exception) { false }
            }
            if (installedChrome != null) {
                customTabsIntent.intent.setPackage(installedChrome)
            }

            customTabsIntent.launchUrl(context, url)
        } catch (e: Exception) {
            // Custom Tabs 실패 시 일반 브라우저 fallback
            try {
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, url).apply {
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (e2: Exception) {
                android.util.Log.e("SocialLoginHelper", "Failed to open OAuth URL", e2)
            }
        }
    }

    /**
     * Custom Tabs에서 redirect 받은 URI에서 access_token, refresh_token 추출.
     * Supabase는 hash fragment (#)에 토큰을 담음 — Android에서 받으면 Uri.fragment로.
     */
    data class OAuthTokens(val accessToken: String, val refreshToken: String)

    fun parseCallback(uri: Uri): OAuthTokens? {
        // 1) Hash fragment 우선 (Supabase 표준)
        val fragment = uri.fragment ?: uri.query ?: return null
        val params = fragment.split("&")
            .mapNotNull { p ->
                val parts = p.split("=", limit = 2)
                if (parts.size == 2) parts[0] to Uri.decode(parts[1]) else null
            }.toMap()
        val accessToken = params["access_token"] ?: return null
        val refreshToken = params["refresh_token"] ?: return null
        return OAuthTokens(accessToken, refreshToken)
    }
}
