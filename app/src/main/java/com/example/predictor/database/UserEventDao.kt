package com.example.predictor.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface UserEventDao {
    @Insert
    suspend fun insertEvent(event: UserEvent)

    @Query("SELECT * FROM user_events")
    suspend fun getAllEvents(): List<UserEvent>

    // NEW: Fetch only the last 30 days of data to keep predictions fast.
    @Query("SELECT * FROM user_events WHERE timestamp > :minTimestamp")
    suspend fun getRecentEvents(minTimestamp: Long): List<UserEvent>

    @Query("SELECT * FROM user_events WHERE activityType = :activity")
    suspend fun getEventsByActivity(activity: String): List<UserEvent>
}