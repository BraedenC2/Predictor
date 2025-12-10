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
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import com.example.predictor.MainActivity
import com.example.predictor.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class PredictorService : Service(), TextToSpeech.OnInitListener {

    private lateinit var sensorReceiver: SensorReceiver
    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    // CONFIGURATION
    private val SLEEP_GOAL_MINUTES = 500L // 8h 20m
    private val NEW_NIGHT_HOUR = 22      // 10 PM
    private val WAKE_UP_HOUR = 6         // 6 AM
    private val FALSE_SLEEP_MS = 60 * 60 * 1000L // 1 Hour
    private val INSOMNIA_DELAY_MS = 20 * 60 * 1000L // 20 Minutes

    companion object {
        const val ACTION_SPEAK_ALARM = "com.example.predictor.SPEAK_ALARM"
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundService()

        // Initialize TTS
        tts = TextToSpeech(this, this)

        // Register Sensors
        sensorReceiver = SensorReceiver()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_HEADSET_PLUG)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(sensorReceiver, filter, RECEIVER_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle "Speak" Command from AlarmReceiver
        if (intent?.action == ACTION_SPEAK_ALARM) {
            speakMorningMessage()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(sensorReceiver)
        tts?.stop()
        tts?.shutdown()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            isTtsReady = (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED)
        }
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
            .setContentText("Tracking Sleep & Context...")
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .build()

        startForeground(1, notification)
    }

    // --- TTS LOGIC ---

    private fun speakMorningMessage() {
        if (!isTtsReady) return

        val calendar = Calendar.getInstance()

        // Formats: "10:30 AM", "Wednesday", "December 10, 2025"
        val timeFormat = SimpleDateFormat("h:mm a", Locale.US)
        val dayNameFormat = SimpleDateFormat("EEEE", Locale.US)
        val fullDateFormat = SimpleDateFormat("MMMM d, yyyy", Locale.US)

        val timeStr = timeFormat.format(calendar.time)
        val dayName = dayNameFormat.format(calendar.time)
        val dateStr = fullDateFormat.format(calendar.time)

        val message = "Good Morning, Braeden. The time right now is $timeStr. Today is $dayName, $dateStr."

        // Set volume to max for the alarm speech
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol, 0)

        // Speak!
        tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "morning_alarm")
    }

    // --- SLEEP LOGIC ---

    private fun handleScreenOff() {
        val prefs = getSharedPreferences("PredictorSleep", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val calendar = Calendar.getInstance()
        val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
        val currentDay = calendar.get(Calendar.DAY_OF_YEAR)

        if (currentHour in WAKE_UP_HOUR until NEW_NIGHT_HOUR) return

        val lastResetDay = prefs.getInt("last_reset_day", -1)
        if (currentHour >= NEW_NIGHT_HOUR && currentDay != lastResetDay) {
            prefs.edit().putLong("sleep_bank_minutes", 0).putInt("last_reset_day", currentDay).apply()
        }

        val requireDelay = prefs.getBoolean("require_resume_delay", false)
        val startTime = if (requireDelay) now + INSOMNIA_DELAY_MS else now

        prefs.edit().putLong("sleep_start_timestamp", startTime).putBoolean("require_resume_delay", false).apply()

        val banked = prefs.getLong("sleep_bank_minutes", 0)
        val needed = (SLEEP_GOAL_MINUTES - banked).coerceAtLeast(0)

        if (needed > 0) {
            val wakeUpTime = startTime + (needed * 60 * 1000)
            setSystemAlarm(wakeUpTime)
        }
    }

    private fun handleScreenOn() {
        val prefs = getSharedPreferences("PredictorSleep", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val calendar = Calendar.getInstance()
        val currentHour = calendar.get(Calendar.HOUR_OF_DAY)

        val sleepStart = prefs.getLong("sleep_start_timestamp", 0)
        if (sleepStart == 0L) return

        val durationMillis = now - sleepStart
        val durationMinutes = durationMillis / 1000 / 60

        if (currentHour >= WAKE_UP_HOUR) {
            if (durationMinutes > 0) {
                val currentBank = prefs.getLong("sleep_bank_minutes", 0)
                val newBank = currentBank + durationMinutes
                prefs.edit().putLong("sleep_bank_minutes", newBank).apply()

                val debt = (SLEEP_GOAL_MINUTES - newBank)
                val status = if (debt <= 0) "Goal Reached!" else "Debt: ${debt}m"
                sendNotification("Good Morning", "Total Sleep: ${newBank}m. $status")
            }
            cancelSystemAlarm()
            return
        }

        if (durationMillis < 0) {
            cancelSystemAlarm()
            return
        }

        if (durationMillis < FALSE_SLEEP_MS) {
            cancelSystemAlarm()
        } else {
            val currentBank = prefs.getLong("sleep_bank_minutes", 0)
            val newBank = currentBank + durationMinutes
            prefs.edit().putLong("sleep_bank_minutes", newBank).putBoolean("require_resume_delay", true).apply()
            cancelSystemAlarm()
            sendNotification("Sleep Paused", "Banked: ${durationMinutes}m. Go back to sleep.")
        }
    }

    private fun setSystemAlarm(triggerTime: Long) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!alarmManager.canScheduleExactAlarms()) return
        }
        val intent = Intent(this, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(this, 999, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerTime, pendingIntent), pendingIntent)
    }

    private fun cancelSystemAlarm() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(this, 999, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
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
                    val state = intent.getIntExtra("state", -1)
                    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val prefs = context.getSharedPreferences("PredictorPrefs", Context.MODE_PRIVATE)

                    if (state == 1) {
                        val learnedVol = prefs.getInt("user_headphone_vol", -1)
                        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        val targetVol = if (learnedVol != -1) learnedVol.coerceAtMost(maxVol) else (maxVol * 0.7).toInt()
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, AudioManager.FLAG_SHOW_UI)
                    } else if (state == 0) {
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