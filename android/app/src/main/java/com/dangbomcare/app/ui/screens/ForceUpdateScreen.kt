package com.dangbomcare.app.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dangbomcare.app.BuildConfig
import com.dangbomcare.app.data.api.AppVersionPolicy
import com.dangbomcare.app.ui.theme.*

/**
 * [260904] 강제 업데이트 게이트.
 *
 * 왜: 새 버전(배터리 최적화 안내 등)을 스토어에 올려도 환자가 업데이트했는지 알 수 없고,
 *   구버전을 계속 쓰면 걸음수 유실이 반복된다. → 서버 app_config.min_version_code 보다 낮은 버전은
 *   Splash 에서 이 화면으로 보내고, 업데이트 전에는 앱을 쓰지 못하게 한다(뒤로가기 차단).
 *
 * 값 전달: Splash 가 받은 정책을 [AppUpdateGate.pending] 에 두고 라우팅 — 라우트 인자로 URL 을 넣지 않는다.
 * 버튼: market:// (Play 앱) → 실패 시 서버가 준 store_url(https) 로 폴백.
 *
 * ⚠️ 이 화면은 1.1.19 이상 앱에만 있다. 그 이전 버전은 이 게이트 자체가 없어 서버 값으로 막을 수 없다.
 */
object AppUpdateGate {
    @Volatile
    var pending: AppVersionPolicy? = null

    fun openStore(context: Context, storeUrl: String?) {
        val pkg = context.packageName
        // 서버 app_config.store_url 을 최우선으로 연다.
        //   배포가 Play 내부 테스트(테스터 참여 링크)라서, 테스터 참여 링크나 안내 페이지로 바꿔야 할 수 있다.
        //   market:// 를 먼저 열면 Play 로 설치하지 않은 폰(APK 직접 설치)에서는 "찾을 수 없음" 페이지가 뜨고
        //   startActivity 는 성공으로 끝나 폴백이 동작하지 않는다. → 서버 URL → market:// → 기본 Play 링크 순.
        val candidates = listOfNotNull(
            storeUrl?.takeIf { it.startsWith("https://") },
            "market://details?id=$pkg",
            "https://play.google.com/store/apps/details?id=$pkg",
        )
        for (url in candidates) {
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                )
                return
            } catch (_: Exception) {
                // 다음 후보로
            }
        }
    }
}

@Composable
fun ForceUpdateScreen() {
    val context = LocalContext.current
    val policy = AppUpdateGate.pending
    // 뒤로가기로 빠져나가지 못하게 — 업데이트가 유일한 출구.
    BackHandler(enabled = true) { /* 소비 */ }

    Box(
        modifier = Modifier.fillMaxSize().background(BgPrimary).padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🔄", fontSize = 48.sp)
            Spacer(Modifier.height(16.dp))
            Text(
                "앱 업데이트가 필요해요",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Text(
                policy?.message?.takeIf { it.isNotBlank() }
                    ?: "더 정확한 걸음·수면 기록을 위해 앱을 최신 버전으로 업데이트해 주세요.",
                fontSize = 15.sp,
                lineHeight = 23.sp,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(28.dp))
            Button(
                onClick = { AppUpdateGate.openStore(context, policy?.storeUrl) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("업데이트하기", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "업데이트 후 앱을 다시 열어주세요 · 현재 버전 ${BuildConfig.VERSION_NAME}",
                fontSize = 12.sp,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
        }
    }
}
