package com.example.predictor.sensors

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity

class ActivitySensor(private val context: Context) {

    private val client = ActivityRecognition.getClient(context)

    // We list the movements we care about
    private val transitions = listOf(
        getTransition(DetectedActivity.STILL),
        getTransition(DetectedActivity.WALKING),
        getTransition(DetectedActivity.RUNNING),
        getTransition(DetectedActivity.IN_VEHICLE)
    )

    private fun getTransition(activityType: Int): ActivityTransition {
        return ActivityTransition.Builder()
            .setActivityType(activityType)
            .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
            .build()
    }

    private val pendingIntent: PendingIntent by lazy {
        val intent = Intent(context, ActivityTransitionReceiver::class.java)
        // FLAG_MUTABLE is required for Android 12+ (API 31+)
        PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
    }

    @SuppressLint("MissingPermission") // We check permissions before calling this
    fun startMonitoring() {
        // Ensure we have permission
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED) {
            val request = ActivityTransitionRequest(transitions)
            client.requestActivityTransitionUpdates(request, pendingIntent)
                .addOnSuccessListener {
                    // Successfully registered
                }
                .addOnFailureListener {
                    // Failed to register
                }
        }
    }

    @SuppressLint("MissingPermission")
    fun stopMonitoring() {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED) {
            client.removeActivityTransitionUpdates(pendingIntent)
        }
    }
}