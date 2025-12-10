package com.example.predictor.logic

import android.content.Context
import android.location.Location
import com.example.predictor.database.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

class BayesianPredictor(private val context: Context) {

    suspend fun predictTopApp(
        currentActivity: String, // Kept for compatibility, but ignored
        currentHour: Int,
        isHeadphones: Boolean,
        currentWifi: String,     // Kept for compatibility, but ignored
        currentLat: Double,
        currentLong: Double
    ): String {
        return withContext(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(context)
            val dao = db.userEventDao()

            // FIX: Get ALL history (We don't filter by Activity anymore)
            val rawHistory = dao.getAllEvents()

            if (rawHistory.isEmpty()) return@withContext "No Prediction"

            val appScores = HashMap<String, Double>()

            for (event in rawHistory) {
                // 1. Time Score (+/- 2 hours)
                val hourDiff = minOf(
                    abs(event.hourOfDay - currentHour),
                    24 - abs(event.hourOfDay - currentHour)
                )

                if (hourDiff <= 2) {
                    var score = 1.0

                    // 2. Headphone Score
                    if (isHeadphones && event.isHeadphonesConnected) score += 3.0

                    // 3. Location Score (The new "Super Predictor")
                    // If within 100 meters, give massive points
                    val distance = calculateDistance(currentLat, currentLong, event.latitude, event.longitude)
                    if (distance < 100) {
                        score += 5.0
                    }

                    val app = event.appPackageName
                    if (app != "UNKNOWN" &&
                        app != "com.sec.android.app.launcher" &&
                        app != "com.android.systemui") {
                        appScores[app] = (appScores[app] ?: 0.0) + score
                    }
                }
            }

            val winner = appScores.maxByOrNull { it.value }
            winner?.key ?: "No Prediction"
        }
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        if (lat1 == 0.0 || lat2 == 0.0) return Float.MAX_VALUE
        val result = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, result)
        return result[0]
    }

    // NEW LOGIC: Instead of predicting "Driving" (Activity), we predict "Maps" (App)
    suspend fun calculateAppProbabilityAtTime(targetPackage: String, currentHour: Int): Double {
        return withContext(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(context)
            val dao = db.userEventDao()
            val allEvents = dao.getAllEvents()

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
                    if (event.appPackageName == targetPackage) {
                        targetEventsAtTime++
                    }
                }
            }

            if (totalEventsAtTime < 5) return@withContext 0.0 // Need at least 5 data points to guess

            // Return percentage (0.0 to 1.0)
            return@withContext targetEventsAtTime.toDouble() / totalEventsAtTime.toDouble()
        }
    }
}