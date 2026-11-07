package com.dangbomcare.app.notification

import com.dangbomcare.app.data.ServiceLocator
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * 현재 FCM 토큰을 fetch → 로그인 상태면 백엔드(PUT /me/fcm-token)에 등록 (best-effort, 실패 무시).
 *
 * 호출 시점 2곳 모두 필요:
 *  ① Application.onCreate — 앱 재시작 시 (이미 로그인된 상태)
 *  ② 로그인 성공 직후 (NavGraph postLoginRouting) — 신규 설치 후 첫 로그인
 * 둘 다 호출해야 "신규 설치 → 첫 로그인 → 앱 재시작 전까지 푸시 안 옴" 갭이 닫힘.
 * (패키지명 변경으로 전 사용자가 새 설치 = 재로그인하므로 ②가 특히 중요)
 */
@OptIn(DelicateCoroutinesApi::class)
fun registerFcmTokenIfLoggedIn() {
    try {
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                if (token.isNullOrBlank()) return@addOnSuccessListener
                GlobalScope.launch(Dispatchers.IO) {
                    try {
                        val access = ServiceLocator.tokenStorage.getAccessTokenBlocking()
                        if (!access.isNullOrBlank()) {
                            ServiceLocator.userRepository.updateFcmToken(token)
                        }
                    } catch (_: Throwable) { /* best-effort */ }
                }
            }
            .addOnFailureListener { e ->
                android.util.Log.w("FcmRegistrar", "FCM token fetch failed: ${e.message}")
            }
    } catch (_: Throwable) {
        // Firebase 초기화 실패 등 — 무시
    }
}
