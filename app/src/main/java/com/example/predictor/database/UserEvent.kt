package com.example.predictor.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_events")
data class UserEvent(
    @PrimaryKey(autoGenerate = true) val uid: Int = 0,
    val timestamp: Long,
    val hourOfDay: Int,
    val minute: Int,
    val dayOfWeek: Int,
    val activityType: String,
    val isHeadphonesConnected: Boolean,
    val wifiSsid: String,
    val latitude: Double,   // NEW: Location Data
    val longitude: Double,  // NEW: Location Data
    val appPackageName: String
)