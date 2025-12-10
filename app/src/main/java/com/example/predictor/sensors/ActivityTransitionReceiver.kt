package com.example.predictor.sensors

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ActivityTransitionReceiver : BroadcastReceiver() {

    // We will save the latest activity here so other parts of the app can grab it
    companion object {
        var currentActivity: String = "UNKNOWN"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (ActivityTransitionResult.hasResult(intent)) {
            val result = ActivityTransitionResult.extractResult(intent)
            result?.let {
                for (event in it.transitionEvents) {
                    // We only care when you ENTER a state (not when you exit)
                    if (event.transitionType == com.google.android.gms.location.ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
                        currentActivity = getActivityString(event.activityType)
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