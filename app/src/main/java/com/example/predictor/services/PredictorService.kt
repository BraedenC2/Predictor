package com.example.predictor.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.predictor.R

class PredictorService : Service() {

    private lateinit var headsetReceiver: HeadsetReceiver

    override fun onCreate() {
        super.onCreate()
        startForegroundService()

        // Register the "Instant Ear" Listener
        headsetReceiver = HeadsetReceiver()
        val filter = IntentFilter(Intent.ACTION_HEADSET_PLUG)
        // Also listen for Bluetooth connections if needed
        // filter.addAction(android.bluetooth.BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
        registerReceiver(headsetReceiver, filter, RECEIVER_EXPORTED)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(headsetReceiver)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundService() {
        val channelId = "predictor_service_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Predictor Always-On",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Predictor is Active")
            .setContentText("Listening for headphones & context...")
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .build()

        // ID > 0 is required
        startForeground(1, notification)
    }

    // INNER CLASS: The Trigger
    inner class HeadsetReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_HEADSET_PLUG) {
                val state = intent.getIntExtra("state", -1)
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

                when (state) {
                    1 -> { // PLUGGED IN
                        // Save current vol? (Optional logic here)
                        // Boost Volume
                        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        val targetVol = (maxVol * 0.7).toInt()
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
                    }
                    0 -> { // UNPLUGGED
                        // Restore Volume (e.g. set to 30%)
                        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        val quietVol = (maxVol * 0.3).toInt()
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, quietVol, 0)
                    }
                }
            }
        }
    }
}