package com.example.predictor.workers

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.predictor.database.AppDatabase
import com.example.predictor.database.UserEvent
import com.example.predictor.logic.BayesianPredictor
import com.example.predictor.sensors.UsageCollector
import java.util.Calendar

class DataLoggerWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            // 1. Gather Context
            val calendar = Calendar.getInstance()
            val hour = calendar.get(Calendar.HOUR_OF_DAY)
            val minute = calendar.get(Calendar.MINUTE)
            val day = calendar.get(Calendar.DAY_OF_WEEK)

            val usageCollector = UsageCollector(context)
            val currentApp = usageCollector.getCurrentApp()

            // Sensors
            val isHeadphones = checkHeadphones()
            val location = getBestLocation()
            val lat = location?.latitude ?: 0.0
            val lon = location?.longitude ?: 0.0

            // 2. AUTO-DJ LOGIC (The New Feature)
            handleAutoDj(isHeadphones)

            // 3. Create Record
            val event = UserEvent(
                timestamp = System.currentTimeMillis(),
                hourOfDay = hour,
                minute = minute,
                dayOfWeek = day,
                activityType = "UNKNOWN",
                isHeadphonesConnected = isHeadphones,
                wifiSsid = "None",
                latitude = lat,
                longitude = lon,
                appPackageName = currentApp
            )

            // 4. Save to DB
            val database = AppDatabase.getDatabase(context)
            database.userEventDao().insertEvent(event)

            // 5. Update Widget
            val widgetManager = android.appwidget.AppWidgetManager.getInstance(context)
            val widgetIds = widgetManager.getAppWidgetIds(
                android.content.ComponentName(context, com.example.predictor.PredictorWidget::class.java)
            )
            if (widgetIds.isNotEmpty()) {
                com.example.predictor.PredictorWidget.updateAppWidget(context, widgetManager, widgetIds[0])
            }

            // 6. Smart Traffic Alert
            val predictor = BayesianPredictor(context)
            val mapsProb = predictor.calculateAppProbabilityAtTime("com.google.android.apps.maps", hour)
            if (mapsProb > 0.4) {
                sendDrivingNotification()
            }

            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure()
        }
    }

    private fun handleAutoDj(currentHeadphones: Boolean) {
        val prefs = context.getSharedPreferences("PredictorPrefs", Context.MODE_PRIVATE)
        val lastHeadphones = prefs.getBoolean("last_headphones", false)
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // DEBUG LOGS (Search for "PredictorDJ" in Logcat)
        android.util.Log.d("PredictorDJ", "Checking DJ... Current: $currentHeadphones, Last: $lastHeadphones")

        // CASE 1: Headphones just plugged IN
        if (currentHeadphones && !lastHeadphones) {
            android.util.Log.d("PredictorDJ", "Headphones PLUGGED IN! Boosting Volume.")

            // Save current volume
            val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            prefs.edit().putInt("restore_volume", currentVol).apply()

            // Set to 70%
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val targetVol = (maxVol * 0.7).toInt()

            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, AudioManager.FLAG_SHOW_UI)
        }
        // CASE 2: Headphones just unplugged
        else if (!currentHeadphones && lastHeadphones) {
            android.util.Log.d("PredictorDJ", "Headphones REMOVED! Restoring Volume.")

            // Restore old volume
            val restoreVol = prefs.getInt("restore_volume", 5)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, restoreVol, AudioManager.FLAG_SHOW_UI)
        }

        // Save state
        prefs.edit().putBoolean("last_headphones", currentHeadphones).apply()
    }

    private fun sendDrivingNotification() {
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

    @SuppressLint("MissingPermission")
    private fun getBestLocation(): Location? {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = locationManager.getProviders(true)
        var bestLocation: Location? = null
        for (provider in providers) {
            val l = locationManager.getLastKnownLocation(provider) ?: continue
            if (bestLocation == null || l.accuracy < bestLocation.accuracy) {
                bestLocation = l
            }
        }
        return bestLocation
    }

    private fun checkHeadphones(): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return outputs.any {
            it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        }
    }
}