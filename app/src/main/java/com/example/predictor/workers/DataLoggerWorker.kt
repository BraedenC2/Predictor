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
            // 1. Gather Time
            val calendar = Calendar.getInstance()
            val hour = calendar.get(Calendar.HOUR_OF_DAY)
            val minute = calendar.get(Calendar.MINUTE)
            val day = calendar.get(Calendar.DAY_OF_WEEK)

            // 2. Gather App Usage
            val usageCollector = UsageCollector(context)
            val currentApp = usageCollector.getCurrentApp()

            // 3. Gather Hardware (Headphones & Location)
            val isHeadphones = checkHeadphones()
            val location = getBestLocation()
            val lat = location?.latitude ?: 0.0
            val lon = location?.longitude ?: 0.0

            // 4. Create the Record
            val event = UserEvent(
                timestamp = System.currentTimeMillis(),
                hourOfDay = hour,
                minute = minute,
                dayOfWeek = day,
                activityType = "UNKNOWN",  // Disabled
                isHeadphonesConnected = isHeadphones,
                wifiSsid = "None",         // Disabled
                latitude = lat,
                longitude = lon,
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

            // 7. SMART ALERT (TRAFFIC PREDICTION)
            // This is the new part. It asks the Brain: "Is it time for Maps?"
            val predictor = BayesianPredictor(context)
            // You can change "com.google.android.apps.maps" to any app you want alerts for
            val mapsProb = predictor.calculateAppProbabilityAtTime("com.google.android.apps.maps", hour)

            // If probability > 40%, send a notification
            if (mapsProb > 0.4) {
                sendDrivingNotification()
            }

            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure()
        }
    }

    // --- Helper Functions ---

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