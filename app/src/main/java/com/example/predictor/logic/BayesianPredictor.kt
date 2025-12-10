package com.example.predictor.logic

import android.content.Context
import com.example.predictor.database.AppDatabase
import com.example.predictor.database.UserEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

class BayesianPredictor(private val context: Context) {

    // The main function: Returns the Package Name of the predicted app (e.g., "com.spotify.music")
    suspend fun predictTopApp(
        currentActivity: String,
        currentHour: Int,
        isHeadphones: Boolean,
        currentWifi: String
    ): String {
        return withContext(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(context)
            val dao = db.userEventDao()

            // 1. Broad Filter: Get all history for this physical activity (e.g., "WALKING")
            val rawHistory = dao.getEventsByActivity(currentActivity)

            if (rawHistory.isEmpty()) return@withContext "No Prediction"

            // 2. Score the Apps
            val appScores = HashMap<String, Double>()

            for (event in rawHistory) {
                // Filter: Time Window (+/- 2 hours)
                // We handle the "Midnight Problem" (e.g., 23:00 vs 01:00) using modulo math
                val hourDiff = minOf(
                    abs(event.hourOfDay - currentHour),
                    24 - abs(event.hourOfDay - currentHour)
                )

                if (hourDiff <= 2) {
                    var score = 1.0

                    // Weight 1: Recency (Optional - we can assume newer data is slightly better, but skipping for simplicity)

                    // Weight 2: Headphones (Strong predictor for Music/Audiobooks)
                    if (isHeadphones && event.isHeadphonesConnected) {
                        score += 3.0
                    }

                    // Weight 3: Wi-Fi (Strong predictor for "Home" vs "Work" vs "Gym")
                    if (currentWifi != "None" && event.wifiSsid == currentWifi) {
                        score += 2.0
                    }

                    val app = event.appPackageName

                    // FIX: Ignore "UNKNOWN" so we don't crash the widget
                    if (app != "UNKNOWN" &&
                        app != "com.sec.android.app.launcher" &&
                        app != "com.android.systemui") {

                        appScores[app] = (appScores[app] ?: 0.0) + score
                    }
                }
            }

            // 3. Find the Winner
            val winner = appScores.maxByOrNull { it.value }

            // Return the app package name, or a default string if nothing won
            winner?.key ?: "No Prediction"
        }
    }

    // NEW: Calculate how likely a specific activity is at this hour
    suspend fun calculateActivityProbability(
        targetActivity: String,
        currentHour: Int
    ): Double {
        return withContext(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(context)
            val dao = db.userEventDao()
            val allEvents = dao.getAllEvents() // We need to look at everything

            if (allEvents.isEmpty()) return@withContext 0.0

            var totalEventsAtTime = 0
            var targetEventsAtTime = 0

            for (event in allEvents) {
                // Check if this memory happened at the same time (+/- 1 hour)
                val hourDiff = minOf(
                    abs(event.hourOfDay - currentHour),
                    24 - abs(event.hourOfDay - currentHour)
                )

                if (hourDiff <= 1) {
                    totalEventsAtTime++
                    if (event.activityType == targetActivity) {
                        targetEventsAtTime++
                    }
                }
            }

            if (totalEventsAtTime < 5) return@withContext 0.0 // Not enough data yet

            // Return percentage (0.0 to 1.0)
            return@withContext targetEventsAtTime.toDouble() / totalEventsAtTime.toDouble()
        }
    }
}