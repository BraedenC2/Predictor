package com.example.predictor.sensors

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity

class ActivityTransitionReceiver : BroadcastReceiver() {

    companion object {
        var currentActivity: String = "UNKNOWN"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (ActivityTransitionResult.hasResult(intent)) {
            val result = ActivityTransitionResult.extractResult(intent)
            result?.let {
                for (event in it.transitionEvents) {
                    if (event.transitionType == com.google.android.gms.location.ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
                        currentActivity = getActivityString(event.activityType)
                    } else if (event.transitionType == com.google.android.gms.location.ActivityTransition.ACTIVITY_TRANSITION_EXIT) {
                        // FIX: If we stop doing something, reset to UNKNOWN until we get a new "Enter" event
                        // This prevents getting stuck in "WALKING" forever
                        currentActivity = "UNKNOWN"
                    }
                }
            }
        }
    }

    private fun getActivityString(type: Int): String {
        return when (type) {
            DetectedActivity.STILL -> "STILL"
            DetectedActivity.WALKING -> "WALKING"
            DetectedActivity.RUNNING -> "RUNNING"
            DetectedActivity.IN_VEHICLE -> "DRIVING"
            DetectedActivity.ON_BICYCLE -> "CYCLING"
            else -> "UNKNOWN"
        }
    }
}