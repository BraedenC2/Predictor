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
            val day = calendar.get(Calendar.DAY_OF_WEEK) // 1=Sunday, 2=Monday, etc.

            // 2. Sensors
            val usageCollector = UsageCollector(context)
            val currentApp = usageCollector.getCurrentApp()

            // We get the activity from the static variable in our Receiver
            val currentActivity = ActivityTransitionReceiver.currentActivity

            // 3. Hardware Checks
            val isHeadphones = checkHeadphones()
            val wifiSsid = checkWifiSsid()

            // 4. Create the Record
            val event = UserEvent(
                timestamp = System.currentTimeMillis(),
                hourOfDay = hour,
                dayOfWeek = day,
                activityType = currentActivity,
                isHeadphonesConnected = isHeadphones,
                wifiSsid = wifiSsid,
                appPackageName = currentApp
            )

            // 5. Save to Database
            val database = AppDatabase.getDatabase(context)
            database.userEventDao().insertEvent(event)

            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure()
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

    // FIX: Updated to use ConnectivityManager instead of the deprecated WifiManager.connectionInfo
    private fun checkWifiSsid(): String {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork ?: return "None"
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return "None"

        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            val wifiInfo = capabilities.transportInfo as? WifiInfo
            // Returns "None" if ssid is null or just quotes
            return wifiInfo?.ssid?.replace("\"", "") ?: "None"
        }
        return "None"
    }
}