package com.example.predictor.services

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.example.predictor.MainActivity
import com.example.predictor.R

class PredictorService : Service() {

    private lateinit var sensorReceiver: SensorReceiver
    private val SLEEP_GOAL_MINUTES = 500L // 8 hours 20 minutes
    private val MIN_SLEEP_THRESHOLD_MINUTES = 15L // Sleep must be > 15m to count

    override fun onCreate() {
        super.onCreate()
        startForegroundService()

        // Register Screen & Headphone Listeners
        sensorReceiver = SensorReceiver()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_HEADSET_PLUG)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(sensorReceiver, filter, RECEIVER_EXPORTED)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(sensorReceiver)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundService() {
        val channelId = "predictor_service_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Predictor Core", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Predictor is Active")
            .setContentText("Monitoring Sleep & Context...")
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .build()

        startForeground(1, notification)
    }

    // --- SLEEP LOGIC ---

    private fun handleScreenOff() {
        val prefs = getSharedPreferences("PredictorSleep", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val calendar = java.util.Calendar.getInstance()
        val currentHour = calendar.get(java.util.Calendar.HOUR_OF_DAY)

        // 1. SMART CHECK: Is it actually "Bedtime"? (e.g., 9 PM to 6 AM)
        // If it's day time, we ignore this event completely (don't set alarm).
        val isBedtime = currentHour >= 21 || currentHour < 6
        if (!isBedtime) {
            android.util.Log.d("PredictorSleep", "Screen Off during day. Ignoring.")
            return
        }

        // 2. Check for New Day Reset (If last sleep was > 12 hours ago)
        val lastWakeTime = prefs.getLong("last_wake_time", 0)
        if (now - lastWakeTime > 12 * 60 * 60 * 1000) {
            prefs.edit().putLong("sleep_bank_minutes", 0).apply() // New Day, New Goal
        }

        // 3. Calculate Needed Sleep
        val banked = prefs.getLong("sleep_bank_minutes", 0)
        val needed = (SLEEP_GOAL_MINUTES - banked).coerceAtLeast(0)

        // 4. Mark Sleep Start Time
        prefs.edit().putLong("sleep_start_timestamp", now).apply()

        // 5. Set Alarm (Only if we need sleep)
        if (needed > 0) {
            val wakeUpTime = now + (needed * 60 * 1000)
            setSystemAlarm(wakeUpTime)
            android.util.Log.d("PredictorSleep", "Sleep Alarm set for ${needed}m from now")
        }
    }

    private fun handleScreenOn() {
        val prefs = getSharedPreferences("PredictorSleep", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()

        // 1. How long was the screen off?
        val sleepStart = prefs.getLong("sleep_start_timestamp", 0)
        if (sleepStart == 0L) return

        val durationMillis = now - sleepStart
        val durationMinutes = durationMillis / 1000 / 60

        // 2. The "Smart Check"
        if (durationMinutes >= MIN_SLEEP_THRESHOLD_MINUTES) {
            // It was real sleep. Deposit into Bank.
            val currentBank = prefs.getLong("sleep_bank_minutes", 0)
            val newBank = currentBank + durationMinutes

            prefs.edit()
                .putLong("sleep_bank_minutes", newBank)
                .putLong("last_wake_time", now) // Mark this as wake up time
                .apply()

            // Cancel the alarm (User is awake now)
            cancelSystemAlarm()

            // Notify User
            val remaining = (SLEEP_GOAL_MINUTES - newBank).coerceAtLeast(0)
            sendNotification("Sleep Tracked", "Banked: ${durationMinutes}m. Remaining Need: ${remaining}m")
        } else {
            // It was a false alarm (checked notifications in night). Ignore it.
            // But cancel the alarm because the user is awake NOW.
            // (The alarm will re-set correctly next time Screen turns OFF).
            cancelSystemAlarm()
        }
    }

    private fun setSystemAlarm(triggerTime: Long) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!alarmManager.canScheduleExactAlarms()) return
        }

        // FIX: Target the AlarmReceiver, NOT MainActivity
        val intent = Intent(this, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this, 999, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val clockInfo = AlarmManager.AlarmClockInfo(triggerTime, pendingIntent)
        alarmManager.setAlarmClock(clockInfo, pendingIntent)
    }

    private fun cancelSystemAlarm() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // FIX: Must match the intent used in setSystemAlarm
        val intent = Intent(this, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this, 999, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }
    private fun sendNotification(title: String, text: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        val notification = NotificationCompat.Builder(this, "predictor_service_channel")
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        notificationManager.notify(2002, notification)
    }

    // --- RECEIVER ---

    inner class SensorReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> handleScreenOff()
                Intent.ACTION_SCREEN_ON -> handleScreenOn()

                Intent.ACTION_HEADSET_PLUG -> {
                    // (Keep your Auto-DJ logic here if you want it)
                    val state = intent.getIntExtra("state", -1)
                    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val prefs = context.getSharedPreferences("PredictorPrefs", Context.MODE_PRIVATE)

                    if (state == 1) { // Plugged In
                        val learnedVol = prefs.getInt("user_headphone_vol", -1)
                        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        val targetVol = if (learnedVol != -1) learnedVol.coerceAtMost(maxVol) else (maxVol * 0.7).toInt()
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, AudioManager.FLAG_SHOW_UI)
                    } else if (state == 0) { // Unplugged
                        val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                        prefs.edit().putInt("user_headphone_vol", currentVol).apply()
                        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (maxVol * 0.3).toInt(), 0)
                    }
                }
            }
        }
    }
}