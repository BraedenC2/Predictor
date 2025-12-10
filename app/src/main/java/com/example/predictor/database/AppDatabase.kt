package com.example.predictor.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [UserEvent::class], version = 2, exportSchema = false) // VERSION 2
abstract class AppDatabase : RoomDatabase() {
    abstract fun userEventDao(): UserEventDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "predictor_database"
                )
                    .fallbackToDestructiveMigration() // Wipe old data since we changed the columns
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}