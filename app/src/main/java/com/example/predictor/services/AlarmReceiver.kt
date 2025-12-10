package com.example.predictor.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.predictor.MainActivity
import com.example.predictor.R

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // 1. TRIGGER THE VOICE (The real alarm)
        val serviceIntent = Intent(context, PredictorService::class.java).apply {
            action = PredictorService.ACTION_SPEAK_ALARM
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }

        // 2. SHOW SILENT NOTIFICATION (Visual Wake Up)
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // NEW ID: Changed to force a fresh, silent channel
        val channelId = "predictor_voice_alarm"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Voice Wake Up", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Wakes you up with TTS"
                enableLights(true)
                enableVibration(true) // Keep vibration? (Optional: set to false if you want pure voice)
                setSound(null, null) // SILENT: No beeping!
            }
            notificationManager.createNotificationChannel(channel)
        }

        val appIntent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, appIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentTitle("WAKE UP!")
            .setContentText("Good morning.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pendingIntent, true) // Still lights up screen
            .setAutoCancel(true)
            .setDefaults(0) // Disable default lights/sound
            .build()

        notificationManager.notify(9999, notification)
    }
}