package com.dangbomcare.app.data.health

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * 배터리 최적화 예외 (Doze/App Standby 대응).
 *
 * [260812 한의원 피드백] 워치 없이 앱만 쓰는 환자에게서 걸음수가 9보·58보처럼 비정상적으로
 *   적게 들어오거나 아예 없는 사례가 다수 확인됐다. 원인은 두 갈래인데 둘 다 배터리 최적화다.
 *     1) 우리 앱의 30분 주기 동기화(WatchSyncWorker)가 Doze 로 몇 시간씩 밀린다.
 *     2) 삼성헬스 자체가 백그라운드에서 종료되면 폰 걸음 기록이 끊긴다.
 *   → 시스템 '배터리 최적화 제외' 목록에 들어가면 두 문제 모두 완화된다.
 *
 * ⚠️ 강제할 수 없다. 사용자가 시스템 다이얼로그에서 거부하면 앱은 그대로 동작하고
 *   동기화 주기만 길어진다. 그래서 어디서도 이 결과로 기능을 막지 않는다.
 */
object BatteryOptimization {

    private const val TAG = "BatteryOpt"

    private const val PREFS = "battery_opt_prefs"
    private const val KEY_PROMPTED = "one_time_prompted"

    /**
     * 최초 1회 팝업을 이미 띄웠는가.
     *
     * ⚠️ '허용했는가'가 아니라 '안내했는가'다. 허용 여부는 [isIgnoring] 이 시스템에 직접 묻는다.
     *   배터리 최적화는 안드로이드 시스템 설정이라 앱이 잠글 수 없고, 사용자가 언제든 다시 켤 수 있다.
     *   그래서 이 플래그로는 팝업 재출현만 막고, 꺼진 상태는 홈 배너·설정 행이 계속 알린다.
     */
    fun wasPrompted(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PROMPTED, false)

    fun markPrompted(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_PROMPTED, true).apply()
    }

    /** 최초 1회 팝업을 지금 띄워야 하는가 = 아직 안 띄웠고 + 아직 예외 상태가 아님. */
    fun shouldShowOneTimePrompt(context: Context): Boolean =
        !wasPrompted(context) && !isIgnoring(context)

    /** 이미 배터리 최적화 예외 상태인가. (M 미만은 Doze 자체가 없어 항상 true) */
    fun isIgnoring(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } catch (e: Exception) {
            Log.w(TAG, "isIgnoring 확인 실패: ${e.message}")
            false   // 모르면 '아직 아님'으로 보고 안내를 띄운다 (기능 차단은 없음)
        }
    }

    /**
     * 시스템 '배터리 최적화 무시 허용' 다이얼로그를 띄운다.
     *
     * ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 는 Play 정책상 용도가 제한되지만,
     *   백그라운드 건강데이터 동기화는 허용 사유에 해당한다(사용자가 직접 선택).
     * 일부 기기/롬에서 이 인텐트가 막혀 있으면 [openSettings] 로 폴백한다.
     */
    @SuppressLint("BatteryLife")
    fun request(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        if (isIgnoring(context)) return false
        return try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "요청 다이얼로그 실패 → 설정 화면으로 폴백: ${e.message}")
            openSettings(context)
        }
    }

    // ──────────────────────────────────────────────────────────────
    // 삼성헬스 배터리 최적화 (걸음 기록 자체가 끊기는 근본 원인)
    // ──────────────────────────────────────────────────────────────
    //
    // ⚠️ 우리 앱이 삼성헬스의 설정을 **대신 꺼줄 수는 없다.**
    //   ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 는 호출한 앱 자신에게만 동작하고,
    //   다른 패키지를 지정하면 시스템이 거부한다(앱이 남의 시스템 설정을 바꾸는 것을 OS가 차단).
    //   → 우리가 할 수 있는 최대는 '배터리 최적화 앱 목록' 화면을 열어주는 것까지고,
    //     목록에서 삼성헬스를 찾아 '최적화 안 함'을 고르는 것은 사용자 몫이다.
    //   그래서 화면을 열어주되, 무엇을 눌러야 하는지 단계 안내를 함께 보여준다.

    const val SAMSUNG_HEALTH_PKG = "com.sec.android.app.shealth"

    private const val KEY_SH_PROMPTED = "shealth_opt_prompted"

    /** 삼성헬스가 설치돼 있는가. (미설치면 안내 자체가 무의미) */
    fun isSamsungHealthInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(SAMSUNG_HEALTH_PKG, 0)
        true
    } catch (_: Exception) {
        false
    }

    /**
     * 삼성헬스가 배터리 최적화 예외인가.
     *
     * ⚠️ 이 값은 **참고용**이다. isIgnoringBatteryOptimizations 는 원래 자기 패키지를 묻는 API 라
     *   다른 패키지에 대해서는 기기/롬에 따라 항상 false 를 주거나 예외를 던질 수 있다.
     *   따라서 false 를 '확실히 꺼져 있다'는 근거로 쓰지 않는다 — 안내를 띄울지 말지에만 쓰고,
     *   사용자가 이미 해둔 경우를 위해 안내는 언제나 닫을 수 있게 만든다.
     */
    fun isSamsungHealthIgnoring(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(SAMSUNG_HEALTH_PKG)
        } catch (e: Exception) {
            Log.w(TAG, "삼성헬스 최적화 상태 확인 불가: ${e.message}")
            false
        }
    }

    fun wasSamsungHealthPrompted(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SH_PROMPTED, false)

    fun markSamsungHealthPrompted(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SH_PROMPTED, true).apply()
    }

    /**
     * 삼성헬스 안내를 지금 보여야 하는가.
     *   설치돼 있고 + 아직 안내 안 했고 + (확인 가능한 범위에서) 예외가 아닐 때.
     */
    fun shouldShowSamsungHealthPrompt(context: Context): Boolean =
        isSamsungHealthInstalled(context) &&
            !wasSamsungHealthPrompted(context) &&
            !isSamsungHealthIgnoring(context)

    /**
     * 배터리 최적화 **앱 목록** 화면을 연다. 사용자가 여기서 삼성헬스를 찾아 해제한다.
     *   1순위: 최적화 목록(ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
     *   2순위: 삼성헬스 앱 정보 화면(거기서 배터리 항목으로 들어갈 수 있다)
     *   3순위: 전체 앱 설정
     */
    fun openSamsungHealthBatterySettings(context: Context): Boolean {
        if (openSettings(context)) return true
        return try {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$SAMSUNG_HEALTH_PKG"),
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "삼성헬스 설정 열기 실패: ${e.message}")
            false
        }
    }

    /** 폴백 — 배터리 최적화 설정 목록 화면을 연다(사용자가 직접 찾아 해제). */
    fun openSettings(context: Context): Boolean = try {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        Log.w(TAG, "설정 화면도 실패: ${e.message}")
        false
    }
}
