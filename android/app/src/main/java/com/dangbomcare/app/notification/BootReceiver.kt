package com.dangbomcare.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 부팅 후 / 앱 업데이트 후 알람 재등록.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            AlarmScheduler.rescheduleAllEnabled(context)
        }
    }
}
