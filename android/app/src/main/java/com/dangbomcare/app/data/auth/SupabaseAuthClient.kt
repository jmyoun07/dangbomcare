package com.dangbomcare.app.data.auth

import com.dangbomcare.app.BuildConfig
import com.dangbomcare.app.data.api.SupabaseAuthResponse
import com.dangbomcare.app.data.api.SupabaseSignInRequest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

interface SupabaseAuthApi {

    @POST("auth/v1/token")
    suspend fun signIn(
        @Query("grant_type") grantType: String = "password",
        @Body req: SupabaseSignInRequest
    ): Response<SupabaseAuthResponse>

    @POST("auth/v1/signup")
    suspend fun signUp(
        @Body req: SupabaseSignInRequest,
        // 이메일 인증 클릭 후 돌아올 deeplink. 명시 안 하면 Supabase Site URL(보통 localhost)로 가버림.
        @Query("redirect_to") redirectTo: String? = "dangdangcare://login-callback"
    ): Response<SupabaseAuthResponse>

    @POST("auth/v1/token")
    suspend fun refresh(
        @Query("grant_type") grantType: String = "refresh_token",
        @Body req: SupabaseRefreshRequest
    ): Response<SupabaseAuthResponse>

    // [v1.0] 이메일 인증 메일 재발송 (가입 후 메일 못 받았을 때)
    // type: "signup" (가입 확인 재발송)
    @POST("auth/v1/resend")
    suspend fun resendEmailConfirmation(
        @Body req: SupabaseResendRequest
    ): Response<Unit>

    // [v1.0] 비밀번호 재설정 메일 발송
    // redirect_to에 딥링크를 넣어 사용자가 클릭하면 앱으로 복귀하도록 함
    @POST("auth/v1/recover")
    suspend fun recoverPassword(
        @Body req: SupabaseRecoverRequest
    ): Response<Unit>

    // [v1.0] 새 비밀번호 설정 (로그인 세션 필요 — 비밀번호 재설정 메일 클릭 후 호출)
    @retrofit2.http.PUT("auth/v1/user")
    suspend fun updateUser(
        @retrofit2.http.Header("Authorization") authorization: String,
        @Body req: SupabaseUpdateUserRequest
    ): Response<SupabaseAuthResponse>

    // [v1.0] PKCE token_hash 검증 → 세션 발급
    // Supabase 신규 프로젝트는 비번 재설정/이메일 인증 메일에 token_hash + type 를 query로 줌
    @POST("auth/v1/verify")
    suspend fun verifyTokenHash(
        @Body req: SupabaseTokenHashRequest
    ): Response<SupabaseAuthResponse>
}

data class SupabaseRefreshRequest(
    val refresh_token: String
)

// [v1.0] 이메일 인증 메일 재발송 요청
data class SupabaseResendRequest(
    val email: String,
    val type: String = "signup"  // "signup" / "email_change"
)

// [v1.0] 비밀번호 재설정 메일 발송 요청
data class SupabaseRecoverRequest(
    val email: String
)

// [v1.0] 비밀번호 변경 요청 (auth/v1/user PUT)
data class SupabaseUpdateUserRequest(
    val password: String
)

// [v1.0] PKCE token_hash 검증 요청 — 비번 재설정 메일 등 신규 흐름
data class SupabaseTokenHashRequest(
    val type: String,         // "recovery" / "signup" / "email_change" 등
    val token_hash: String    // 메일 링크의 token_hash 파라미터
)

object SupabaseAuthClient {

    val api: SupabaseAuthApi by lazy {
        val anonKey = BuildConfig.SUPABASE_ANON_KEY
        val supabaseUrl = BuildConfig.SUPABASE_URL.ifBlank { "https://example.supabase.co" }

        val apiKeyInterceptor = Interceptor { chain ->
            val req = chain.request().newBuilder()
                .header("apikey", anonKey)
                .header("Content-Type", "application/json")
                .build()
            chain.proceed(req)
        }

        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY else HttpLoggingInterceptor.Level.NONE
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(apiKeyInterceptor)
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .build()

        Retrofit.Builder()
            .baseUrl("$supabaseUrl/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(SupabaseAuthApi::class.java)
    }
}
