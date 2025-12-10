package com.example.predictor.workers

import android.content.Context
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.predictor.database.AppDatabase
import com.example.predictor.database.UserEvent
import com.example.predictor.logic.BayesianPredictor
import com.example.predictor.sensors.ActivityTransitionReceiver
import com.example.predictor.sensors.UsageCollector
import java.util.Calendar

class DataLoggerWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            // 1. Gather Context Data
            val calendar = Calendar.getInstance()
            val hour = calendar.get(Calendar.HOUR_OF_DAY)
            val minute = calendar.get(Calendar.MINUTE) // NEW: Get Minute
            val day = calendar.get(Calendar.DAY_OF_WEEK)

            // 2. Sensors
            val usageCollector = UsageCollector(context)
            // Fix: This now looks back 5 minutes, so it won't return "UNKNOWN" as often
            val currentApp = usageCollector.getCurrentApp()
            val currentActivity = ActivityTransitionReceiver.currentActivity

            // 3. Hardware Checks
            val isHeadphones = checkHeadphones()
            val wifiSsid = checkWifiSsid()

            // 4. Create the Record
            val event = UserEvent(
                timestamp = System.currentTimeMillis(),
                hourOfDay = hour,
                minute = minute, // NEW: Save Minute
                dayOfWeek = day,
                activityType = currentActivity,
                isHeadphonesConnected = isHeadphones,
                wifiSsid = wifiSsid,
                appPackageName = currentApp
            )

            // 5. Save to Database
            val database = AppDatabase.getDatabase(context)
            database.userEventDao().insertEvent(event)

            // 6. Update Widget
            val widgetManager = android.appwidget.AppWidgetManager.getInstance(context)
            val widgetIds = widgetManager.getAppWidgetIds(
                android.content.ComponentName(context, com.example.predictor.PredictorWidget::class.java)
            )
            if (widgetIds.isNotEmpty()) {
                com.example.predictor.PredictorWidget.updateAppWidget(context, widgetManager, widgetIds[0])
            }

            // 7. Smart Alert (Traffic)
            if (currentActivity != "DRIVING" && currentActivity != "IN_VEHICLE") {
                val predictor = BayesianPredictor(context)
                val drivingProb = predictor.calculateActivityProbability("DRIVING", hour)
                if (drivingProb > 0.5) {
                    sendDrivingNotification()
                }
            }

            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure()
        }
    }

    private fun sendDrivingNotification() {
        // (Notification code same as before...)
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val mapsIntent = context.packageManager.getLaunchIntentForPackage("com.google.android.apps.maps")
        if (mapsIntent != null) {
            val pendingIntent = android.app.PendingIntent.getActivity(
                context, 1, mapsIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val notification = androidx.core.app.NotificationCompat.Builder(context, "predictor_channel")
                .setSmallIcon(android.R.drawable.ic_dialog_map)
                .setContentTitle("Commute Predicted")
                .setContentText("Traffic is likely. Open Maps?")
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()
            notificationManager.notify(1001, notification)
        }
    }

    private fun checkHeadphones(): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        for (device in outputs) {
            val type = device.type
            if (type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) {
                return true
            }
        }
        return false
    }

    private fun checkWifiSsid(): String {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork ?: return "None"
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return "None"

        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            val wifiInfo = capabilities.transportInfo as? WifiInfo
            val ssid = wifiInfo?.ssid?.replace("\"", "") ?: "None"
            // Fix: Android sometimes returns literally "<unknown ssid>" when location is flaky
            if (ssid == "<unknown ssid>") return "None"
            return ssid
        }
        return "None"
    }
}