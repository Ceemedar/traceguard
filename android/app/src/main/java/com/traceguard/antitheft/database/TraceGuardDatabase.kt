package com.traceguard.antitheft.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [LocationEntity::class], version = 1, exportSchema = false)
abstract class TraceGuardDatabase : RoomDatabase() {
    abstract fun locationDao(): LocationDao

    companion object {
        @Volatile private var instance: TraceGuardDatabase? = null

        fun get(context: Context): TraceGuardDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                TraceGuardDatabase::class.java,
                "traceguard.db",
            ).build().also { instance = it }
        }
    }
}
