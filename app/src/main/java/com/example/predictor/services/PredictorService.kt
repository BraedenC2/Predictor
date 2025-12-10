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
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import com.example.predictor.MainActivity
import com.example.predictor.R
import com.example.predictor.logic.BayesianPredictor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class PredictorService : Service(), TextToSpeech.OnInitListener {

    private lateinit var sensorReceiver: SensorReceiver
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private val scope = CoroutineScope(Dispatchers.Main)

    // CONFIGURATION (Now actually used!)
    private val SLEEP_GOAL_MINUTES = 500L       // 8h 20m
    private val NEW_NIGHT_HOUR = 22             // 10 PM
    private val WAKE_UP_HOUR = 6                // 6 AM
    private val FALSE_SLEEP_MS = 60 * 60 * 1000L // 1 Hour
    private val INSOMNIA_DELAY_MS = 20 * 60 * 1000L // 20 Minutes

    companion object {
        const val ACTION_SPEAK_ALARM = "com.example.predictor.SPEAK_ALARM"
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundService()

        tts = TextToSpeech(this, this)

        sensorReceiver = SensorReceiver()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_HEADSET_PLUG)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT) // Detects Unlock
        }
        registerReceiver(sensorReceiver, filter, RECEIVER_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
            .setContentText("Reading your mind...")
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .build()

        startForeground(1, notification)
    }

    // --- WOW FEATURE 1: MIND READER (App Telepathy) ---
    private fun attemptMindReading() {
        scope.launch {
            // 1. Gather Context
            val calendar = Calendar.getInstance()
            val hour = calendar.get(Calendar.HOUR_OF_DAY)
            // Defaulting location for speed/privacy on unlock
            val lat = 0.0
            val lon = 0.0
            val isHeadphones = checkHeadphones()

            // 2. Predict
            val predictor = BayesianPredictor(this@PredictorService)
            val predictedApp = predictor.predictTopApp("UNKNOWN", hour, isHeadphones, lat, lon)

            if (predictedApp != "No Prediction") {
                launchApp(predictedApp)
            }
        }
    }

    private fun launchApp(packageName: String) {
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // --- WOW FEATURE 2: THE PHANTOM DJ ---
    private fun triggerPhantomDj() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol, 0)

        // Simulate Media Button Press
        val eventDown = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY)
        val eventUp = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY)

        audioManager.dispatchMediaKeyEvent(eventDown)
        audioManager.dispatchMediaKeyEvent(eventUp)

        if (isTtsReady) {
            tts?.speak("Phantom DJ Active", TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    // --- SLEEP TRACKER LOGIC (Restored) ---
    private fun handleScreenOff() {
        val prefs = getSharedPreferences("PredictorSleep", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val calendar = Calendar.getInstance()
        val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
        val currentDay = calendar.get(Calendar.DAY_OF_YEAR)

        // If it's daytime, ignore
        if (currentHour in WAKE_UP_HOUR until NEW_NIGHT_HOUR) return

        // New Night Reset
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

        // Reset alarm logic when waking up
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

        // Insomnia / False Sleep logic
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

    // --- ALARM SPEAKER ---
    private fun speakMorningMessage() {
        if (!isTtsReady) return
        val calendar = Calendar.getInstance()
        val timeFormat = SimpleDateFormat("h:mm a", Locale.US)
        val message = "Good Morning. It is ${timeFormat.format(calendar.time)}."
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol, 0)
        tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "morning_alarm")
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

    private fun checkHeadphones(): Boolean {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return devices.any {
            it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        }
    }

    // --- RECEIVER ---
    inner class SensorReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> handleScreenOff()
                Intent.ACTION_SCREEN_ON -> handleScreenOn()

                // WOW FEATURE 1: App Telepathy
                Intent.ACTION_USER_PRESENT -> {
                    attemptMindReading()
                }

                // WOW FEATURE 2: Phantom DJ
                Intent.ACTION_HEADSET_PLUG -> {
                    val state = intent.getIntExtra("state", -1)
                    if (state == 1) { // Plugged In
                        triggerPhantomDj()
                    }
                }
            }
        }
    }
}