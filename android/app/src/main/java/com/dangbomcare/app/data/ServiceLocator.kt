package com.dangbomcare.app.data

import android.content.Context
import com.dangbomcare.app.data.api.ApiService
import com.dangbomcare.app.data.api.RetrofitFactory
import com.dangbomcare.app.data.auth.AuthRepository
import com.dangbomcare.app.data.auth.TokenStorage
import com.dangbomcare.app.data.health.HealthConnectRepository
import com.dangbomcare.app.data.health.SamsungHealthSource
import com.dangbomcare.app.data.repo.CsvExportRepository
import com.dangbomcare.app.data.repo.DailyLogRepository
import com.dangbomcare.app.data.repo.MedicalGoalRepository
import com.dangbomcare.app.data.repo.ReportRepository
import com.dangbomcare.app.data.repo.ScoreRepository
import com.dangbomcare.app.data.repo.UserRepository
import com.dangbomcare.app.data.repo.WatchDataRepository

/**
 * 간단한 수동 DI — Hilt 등 무거운 DI 프레임워크 생략 (MVP 시간 절약)
 * Application.onCreate()에서 init() 1회 호출.
 */
object ServiceLocator {

    lateinit var appContext: Context
        private set

    val tokenStorage: TokenStorage by lazy { TokenStorage(appContext) }

    // [P1] 세션 만료 전역 이벤트 — 401 refresh 실패 시 RetrofitFactory가 emit, NavGraph가 collect
    private val _sessionExpired = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(
        replay = 0, extraBufferCapacity = 1
    )
    val sessionExpired: kotlinx.coroutines.flow.SharedFlow<Unit> = _sessionExpired

    fun notifySessionExpired() {
        _sessionExpired.tryEmit(Unit)
    }

    val apiService: ApiService by lazy {
        RetrofitFactory.create(tokenStorage)
    }

    val authRepository: AuthRepository by lazy {
        AuthRepository(tokenStorage, apiService)
    }

    // Samsung Health Data SDK 직접 읽기 소스 (삼성헬스→HC 동기화 불완전 폰 대응)
    val samsungHealthSource: SamsungHealthSource by lazy { SamsungHealthSource(appContext) }

    val healthConnectRepository: HealthConnectRepository by lazy {
        HealthConnectRepository(appContext)
    }

    val userRepository: UserRepository by lazy { UserRepository(apiService) }
    val dailyLogRepository: DailyLogRepository by lazy { DailyLogRepository(apiService) }
    val watchDataRepository: WatchDataRepository by lazy { WatchDataRepository(apiService) }
    val scoreRepository: ScoreRepository by lazy { ScoreRepository(apiService) }
    val reportRepository: ReportRepository by lazy { ReportRepository(apiService) }
    val csvExportRepository: CsvExportRepository by lazy { CsvExportRepository(apiService) }
    val medicalGoalRepository: MedicalGoalRepository by lazy { MedicalGoalRepository(apiService) }

    fun init(context: Context) {
        appContext = context.applicationContext
        // [중요] SDK 소스를 static으로 주입 → 화면들이 new HealthConnectRepository(context)를
        // 직접 생성해도 SDK 직접읽기 경로가 적용됨 (싱글톤 미사용 화면 대응).
        HealthConnectRepository.samsungSource = samsungHealthSource
    }
}
