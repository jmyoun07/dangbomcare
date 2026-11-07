package com.dangbomcare.app.data.api

import com.dangbomcare.app.BuildConfig
import com.dangbomcare.app.data.auth.SupabaseAuthClient
import com.dangbomcare.app.data.auth.SupabaseRefreshRequest
import com.dangbomcare.app.data.auth.TokenStorage
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitFactory {

    fun create(tokenStorage: TokenStorage): ApiService {
        val authInterceptor = Interceptor { chain ->
            val original = chain.request()
            val token = tokenStorage.getAccessTokenBlocking()
            val builder = original.newBuilder()
                .header("ngrok-skip-browser-warning", "true")  // ngrok free 인터스티셜 페이지 우회
            if (!token.isNullOrBlank()) {
                builder.header("Authorization", "Bearer $token")
            }
            chain.proceed(builder.build())
        }

        // [v1.0 보안 B1] debug 빌드에서도 Authorization 헤더(토큰)는 redact.
        // 본문은 BODY 레벨로 보되 헤더는 BASIC 수준만 + 민감 헤더 마스킹.
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY
            else HttpLoggingInterceptor.Level.NONE
            // Authorization 헤더 값 마스킹 (debug logcat 노출 차단)
            redactHeader("Authorization")
            redactHeader("apikey")
            redactHeader("Cookie")
        }

        // [Codex H12] 전역 401 재시도 Authenticator.
        // access token 만료 시 refresh로 자동 갱신 + 동일 요청 재시도.
        // 무한 루프 방지: 한 요청당 1번만 재시도.
        val tokenAuthenticator = TokenAuthenticator(tokenStorage)

        val client = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(logging)
            .authenticator(tokenAuthenticator)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}

/**
 * 401 자동 처리 — refresh token으로 access token 갱신 후 원 요청 재시도.
 *
 * [Codex H12 적용] 기존에는 토큰 만료 시 사용 중인 모든 API가 실패했음.
 * 이제 refresh 성공 시 자동 재시도.
 * [260726] 실패 처리 기준: refresh_token이 실제로 거부된 경우(400/401)에만 세션 파기.
 *   네트워크·5xx·429 등 일시 장애는 토큰을 유지하고 이번 요청만 실패시킨다.
 */
private class TokenAuthenticator(
    private val tokenStorage: TokenStorage,
) : Authenticator {

    // [260726] refresh 직렬화용 락.
    //   HomeViewModel이 API를 4개 병렬 호출해서 토큰 만료 시점에 refresh가 동시에 4번 발생했다.
    //   Supabase는 refresh_token을 회전시키므로 늦게 도착한 요청은 '이미 사용된 토큰'으로 갱신을 시도해
    //   실패하고, 그 실패가 방금 저장된 정상 토큰까지 지워 멀쩡한 세션이 로그아웃됐다.
    private val refreshMutex = Mutex()

    override fun authenticate(route: Route?, response: Response): Request? {
        // 이미 한 번 재시도했으면 더 안 함 (무한 루프 방지)
        if (response.priorResponse != null) return null

        // 인증 헤더 자체가 없으면 (공개 endpoint) skip
        val oldAuth = response.request.header("Authorization") ?: return null
        // [260726] 이 요청이 실패할 때 쓴 토큰 — 락 진입 후 "이미 다른 요청이 갱신했는지" 판별에 사용
        val failedToken = oldAuth.removePrefix("Bearer ").trim()

        // refresh 동기 호출 (runBlocking — OkHttp Authenticator는 동기 인터페이스)
        val newToken = runBlocking {
            refreshMutex.withLock {
                // [260726] 락을 기다리는 동안 다른 요청이 이미 갱신했으면 그 토큰으로 바로 재시도한다.
                //   (중복 refresh + refresh_token 회전 충돌 방지)
                val current = tokenStorage.accessTokenFlow.firstOrNull()
                if (!current.isNullOrBlank() && current != failedToken) {
                    return@withLock current
                }

                val refresh = tokenStorage.getRefreshTokenBlocking()
                if (refresh.isNullOrBlank()) {
                    // [회귀 P1] refresh 자체가 없는 경우도 세션 만료 (복구 수단 없음)
                    tokenStorage.clear()
                    com.dangbomcare.app.data.ServiceLocator.notifySessionExpired()
                    return@withLock null
                }

                try {
                    val res = SupabaseAuthClient.api.refresh(
                        req = SupabaseRefreshRequest(refresh_token = refresh)
                    )
                    val body = res.body()
                    val newAccess = body?.accessToken
                    val userId = body?.userId
                    when {
                        res.isSuccessful && newAccess != null && userId != null -> {
                            val newRefresh = body.refreshToken ?: refresh
                            val email = tokenStorage.emailFlow.firstOrNull() ?: ""
                            tokenStorage.saveTokens(newAccess, newRefresh, userId, email)
                            newAccess
                        }
                        // [260726] 세션 파기는 "refresh_token이 실제로 무효"라는 응답일 때만 한다.
                        //   Supabase는 만료/폐기된 refresh_token에 400(invalid_grant) 또는 401을 준다.
                        res.code() == 400 || res.code() == 401 -> {
                            android.util.Log.w("TokenAuthenticator", "refresh rejected (${res.code()}) — session expired")
                            tokenStorage.clear()
                            com.dangbomcare.app.data.ServiceLocator.notifySessionExpired()
                            null
                        }
                        else -> {
                            // [260726] 429·5xx·프록시 502, 그리고 2xx인데 토큰이 비어있는 경우.
                            //   토큰이 무효라는 근거가 없으므로 세션은 유지하고 이번 요청만 실패시킨다.
                            //   (여기서 로그아웃시키면 시니어 환자가 서버 일시 장애마다 재로그인해야 함)
                            android.util.Log.w("TokenAuthenticator", "refresh failed temporarily (code=${res.code()}) — keeping tokens")
                            null
                        }
                    }
                } catch (e: java.io.IOException) {
                    // [v1.0 fix - Codex 2차] 네트워크 일시 장애(WiFi 약함/타임아웃)는 토큰 보존.
                    //   이전엔 IOException도 만료 처리해서 시니어 환자가 약한 WiFi 환경에서 자주 WELCOME 튕김 →
                    //   다시 로그인 부담. 네트워크 회복되면 다음 호출이 정상 401 또는 200 응답 → 그때 처리.
                    android.util.Log.w("TokenAuthenticator", "network error during refresh — keeping tokens: ${e.message}")
                    null  // 이번 요청은 실패 (재시도 신호) but 세션은 유지
                } catch (e: Exception) {
                    // [260726] 파싱 실패 등 예외도 '토큰이 무효'라는 근거가 아니다 → 세션 유지.
                    //   (이전에는 여기서 토큰을 지워, 응답 형식이 깨진 프록시 한 번에 로그아웃됐다)
                    android.util.Log.w("TokenAuthenticator", "unexpected error during refresh — keeping tokens: ${e.message}")
                    null
                }
            }
        }

        return if (newToken != null) {
            response.request.newBuilder()
                .removeHeader("Authorization")
                .header("Authorization", "Bearer $newToken")
                .build()
        } else null
    }
}
