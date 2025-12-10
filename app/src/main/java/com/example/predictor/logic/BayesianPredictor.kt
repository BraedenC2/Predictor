package com.example.predictor.logic

import android.content.Context
import android.location.Location
import com.example.predictor.database.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import kotlin.math.abs

class BayesianPredictor(private val context: Context) {

    // REMOVED: currentWifi parameter
    suspend fun predictTopApp(
        currentActivity: String,
        currentHour: Int,
        isHeadphones: Boolean,
        currentLat: Double,
        currentLong: Double
    ): String {
        return withContext(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(context)
            val dao = db.userEventDao()

            // 30 days history
            val thirtyDaysAgo = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
            val rawHistory = dao.getRecentEvents(thirtyDaysAgo)

            if (rawHistory.isEmpty()) return@withContext "No Prediction"

            val appScores = HashMap<String, Double>()
            val currentDayOfWeek = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)

            for (event in rawHistory) {
                var score = 1.0

                // 1. Time Score
                val hourDiff = minOf(
                    abs(event.hourOfDay - currentHour),
                    24 - abs(event.hourOfDay - currentHour)
                )
                if (hourDiff == 0) score += 3.0
                else if (hourDiff <= 1) score += 1.5
                else if (hourDiff <= 2) score += 0.5
                else continue

                // 2. Recency Decay
                val daysOld = (System.currentTimeMillis() - event.timestamp) / (1000 * 60 * 60 * 24)
                val decayFactor = 1.0 / (1.0 + daysOld)

                // 3. Context Multipliers

                // REMOVED: WiFi Logic

                // Activity
                if (currentActivity != "UNKNOWN" && event.activityType == currentActivity) {
                    score += 3.0
                }

                // Headphones
                if (isHeadphones && event.isHeadphonesConnected) {
                    score += 3.0
                }

                // Day of Week
                if (event.dayOfWeek == currentDayOfWeek) {
                    score += 1.0
                }

                // GPS Location
                val distance = calculateDistance(currentLat, currentLong, event.latitude, event.longitude)
                if (distance < 100) {
                    score += 5.0
                }

                val finalEventScore = score * decayFactor

                val app = event.appPackageName
                if (app != "UNKNOWN" &&
                    app != "com.sec.android.app.launcher" &&
                    app != "com.android.systemui" &&
                    app != "com.google.android.inputmethod.latin") {
                    appScores[app] = (appScores[app] ?: 0.0) + finalEventScore
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

    suspend fun calculateAppProbabilityAtTime(targetPackage: String, currentHour: Int): Double {
        return withContext(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(context)
            val dao = db.userEventDao()
            val thirtyDaysAgo = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
            val allEvents = dao.getRecentEvents(thirtyDaysAgo)

            if (allEvents.isEmpty()) return@withContext 0.0

            var totalEventsAtTime = 0
            var targetEventsAtTime = 0

            for (event in allEvents) {
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

            if (totalEventsAtTime < 5) return@withContext 0.0

            return@withContext targetEventsAtTime.toDouble() / totalEventsAtTime.toDouble()
        }
    }
}