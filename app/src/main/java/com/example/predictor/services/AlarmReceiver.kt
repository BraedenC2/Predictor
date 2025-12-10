package com.example.predictor.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.predictor.MainActivity
import com.example.predictor.R

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "predictor_alarm_channel"

        // 1. Setup the Sound (Use the System Alarm Sound)
        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)

        // 2. Create the Channel (High Priority + Sound)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Sleep Alarm", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Wakes you up"
                enableLights(true)
                enableVibration(true)
                setSound(alarmSound, AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
            }
            notificationManager.createNotificationChannel(channel)
        }

        // 3. Create the Notification (Full Screen Alert)
        val appIntent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, appIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentTitle("WAKE UP!")
            .setContentText("You have reached your sleep goal.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pendingIntent, true) // Pops up over lock screen
            .setAutoCancel(true)
            .setSound(alarmSound)
            .build()

        // 4. Fire!
        notificationManager.notify(9999, notification)
    }
}