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

    // FIX: Listen for both STARTING (Enter) and STOPPING (Exit) an activity
    private val transitions = listOf(
        getTransition(DetectedActivity.STILL, ActivityTransition.ACTIVITY_TRANSITION_ENTER),
        getTransition(DetectedActivity.WALKING, ActivityTransition.ACTIVITY_TRANSITION_ENTER),
        getTransition(DetectedActivity.RUNNING, ActivityTransition.ACTIVITY_TRANSITION_ENTER),
        getTransition(DetectedActivity.IN_VEHICLE, ActivityTransition.ACTIVITY_TRANSITION_ENTER),

        getTransition(DetectedActivity.STILL, ActivityTransition.ACTIVITY_TRANSITION_EXIT),
        getTransition(DetectedActivity.WALKING, ActivityTransition.ACTIVITY_TRANSITION_EXIT),
        getTransition(DetectedActivity.RUNNING, ActivityTransition.ACTIVITY_TRANSITION_EXIT),
        getTransition(DetectedActivity.IN_VEHICLE, ActivityTransition.ACTIVITY_TRANSITION_EXIT)
    )

    private fun getTransition(activityType: Int, transitionType: Int): ActivityTransition {
        return ActivityTransition.Builder()
            .setActivityType(activityType)
            .setActivityTransition(transitionType)
            .build()
    }

    private val pendingIntent: PendingIntent by lazy {
        val intent = Intent(context, ActivityTransitionReceiver::class.java)
        PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
    }

    @SuppressLint("MissingPermission")
    fun startMonitoring() {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED) {
            val request = ActivityTransitionRequest(transitions)
            client.requestActivityTransitionUpdates(request, pendingIntent)
                .addOnSuccessListener { }
                .addOnFailureListener { }
        }
    }

    @SuppressLint("MissingPermission")
    fun stopMonitoring() {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED) {
            client.removeActivityTransitionUpdates(pendingIntent)
        }
    }
}