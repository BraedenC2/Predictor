package com.example.predictor.sensors

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings

class UsageCollector(private val context: Context) {

    fun isPermissionGranted(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun requestPermission() {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun getCurrentApp(): String {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val currentTime = System.currentTimeMillis()

        // Query events from the last 10 seconds to ensure we catch the latest move
        val events = usageStatsManager.queryEvents(currentTime - 1000 * 10, currentTime)
        val usageEvent = UsageEvents.Event()

        var lastApp = "UNKNOWN"
        var lastTime = 0L

        while (events.hasNextEvent()) {
            events.getNextEvent(usageEvent)
            if (usageEvent.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                lastApp = usageEvent.packageName
                lastTime = usageEvent.timeStamp
            }
        }

        return lastApp
    }
}