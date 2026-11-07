package com.dangbomcare.app.data.auth

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.tokenDataStore by preferencesDataStore(name = "dangdangcare_auth")

/**
 * JWT 액세스 토큰 / 리프레시 토큰 / 사용자 ID 저장소.
 * Supabase Auth 응답을 받아서 보관 → Retrofit 인터셉터가 사용.
 *
 * [Codex H9 보안 검토] 토큰은 Android Keystore 기반 AES-256-GCM으로 암호화 저장.
 * 평문 키(access_token, refresh_token)는 마이그레이션 후 제거됨.
 */
class TokenStorage(private val context: Context) {

    // 신규 암호화 키 (Base64 IV+암호문)
    private val accessTokenEncKey = stringPreferencesKey("access_token_enc")
    private val refreshTokenEncKey = stringPreferencesKey("refresh_token_enc")

    // 레거시 평문 키 — 마이그레이션 후 자동 삭제
    private val legacyAccessTokenKey = stringPreferencesKey("access_token")
    private val legacyRefreshTokenKey = stringPreferencesKey("refresh_token")

    private val userIdKey = stringPreferencesKey("user_id")
    private val emailKey = stringPreferencesKey("email")

    val accessTokenFlow: Flow<String?> = context.tokenDataStore.data.map { prefs ->
        readAndMaybeMigrate(prefs, accessTokenEncKey, legacyAccessTokenKey)
    }
    val userIdFlow: Flow<String?> = context.tokenDataStore.data.map { it[userIdKey] }
    val emailFlow: Flow<String?> = context.tokenDataStore.data.map { it[emailKey] }

    /** 동기 접근 — Retrofit 인터셉터에서 사용. */
    fun getAccessTokenBlocking(): String? = runBlocking {
        val prefs = context.tokenDataStore.data.first()
        readAndMaybeMigrate(prefs, accessTokenEncKey, legacyAccessTokenKey)
    }

    /** Refresh token 동기 접근. */
    fun getRefreshTokenBlocking(): String? = runBlocking {
        val prefs = context.tokenDataStore.data.first()
        readAndMaybeMigrate(prefs, refreshTokenEncKey, legacyRefreshTokenKey)
    }

    /**
     * 신규 암호화 키 우선 조회 → 없으면 레거시 평문 키 조회 + 암호화해서 마이그레이션.
     */
    private fun readAndMaybeMigrate(
        prefs: androidx.datastore.preferences.core.Preferences,
        encKey: androidx.datastore.preferences.core.Preferences.Key<String>,
        legacyKey: androidx.datastore.preferences.core.Preferences.Key<String>,
    ): String? {
        // 1) 암호화된 신규 키 우선
        prefs[encKey]?.let { return TokenCrypto.decrypt(it) }

        // 2) 레거시 평문 키 → 마이그레이션
        val legacy = prefs[legacyKey] ?: return null
        runBlocking {
            try {
                val encrypted = TokenCrypto.encrypt(legacy)
                context.tokenDataStore.edit { p ->
                    p[encKey] = encrypted
                    p.remove(legacyKey)
                }
                android.util.Log.i("TokenStorage", "Migrated legacy plaintext token → encrypted")
            } catch (e: Exception) {
                android.util.Log.w("TokenStorage", "Migration failed — keeping legacy", e)
            }
        }
        return legacy
    }

    suspend fun saveTokens(accessToken: String, refreshToken: String, userId: String, email: String) {
        val encAccess = TokenCrypto.encrypt(accessToken)
        val encRefresh = TokenCrypto.encrypt(refreshToken)
        context.tokenDataStore.edit { prefs ->
            prefs[accessTokenEncKey] = encAccess
            prefs[refreshTokenEncKey] = encRefresh
            prefs[userIdKey] = userId
            prefs[emailKey] = email
            // 레거시 평문 키 함께 정리
            prefs.remove(legacyAccessTokenKey)
            prefs.remove(legacyRefreshTokenKey)
        }
    }

    suspend fun clear() {
        context.tokenDataStore.edit { it.clear() }
        // Keystore 키 자체는 유지 — 다음 로그인에서 재사용 (디바이스 한 번 등록되면 안정적)
    }

    suspend fun isLoggedIn(): Boolean {
        val prefs = context.tokenDataStore.data.first()
        return readAndMaybeMigrate(prefs, accessTokenEncKey, legacyAccessTokenKey)?.isNotBlank() == true
    }
}
