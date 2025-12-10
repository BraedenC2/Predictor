package com.example.predictor.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_events")
data class UserEvent(
    @PrimaryKey(autoGenerate = true) val uid: Int = 0,
    val timestamp: Long,            // When it happened (Unix time)
    val hourOfDay: Int,             // 0-23
    val dayOfWeek: Int,             // 1-7 (Sun-Sat)
    val activityType: String,       // "Walking", "Still", "In_Vehicle"
    val isHeadphonesConnected: Boolean,
    val wifiSsid: String,           // "Home_WiFi", "Starbucks", or "None"
    val appPackageName: String      // "com.spotify.music"
)