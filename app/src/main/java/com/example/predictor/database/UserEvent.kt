package com.example.predictor.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_events")
data class UserEvent(
    @PrimaryKey(autoGenerate = true) val uid: Int = 0,
    val timestamp: Long,
    val hourOfDay: Int,
    val minute: Int,                // NEW: Precision tracking (0-59)
    val dayOfWeek: Int,
    val activityType: String,
    val isHeadphonesConnected: Boolean,
    val wifiSsid: String,
    val appPackageName: String
)