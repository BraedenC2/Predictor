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

    // This query asks: "Show me what I did in the past when I was doing THIS activity around THIS time?"
    @Query("SELECT * FROM user_events WHERE activityType = :activity AND hourOfDay BETWEEN :startHour AND :endHour")
    suspend fun getContextEvents(activity: String, startHour: Int, endHour: Int): List<UserEvent>
}