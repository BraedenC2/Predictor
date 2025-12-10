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

    // NEW: We fetch all events for a specific physical activity (e.g., "WALKING")
    // The "Brain" will handle the complex time math later.
    @Query("SELECT * FROM user_events WHERE activityType = :activity")
    suspend fun getEventsByActivity(activity: String): List<UserEvent>
}