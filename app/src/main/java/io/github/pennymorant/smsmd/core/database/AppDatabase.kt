package io.github.pennymorant.smsmd.core.database

import androidx.room3.Database
import androidx.room3.RoomDatabase

@Database(
    entities = [ForwardJobEntity::class, DeliveryLogEntity::class, DeliveryStatsEntity::class],
    version = 4,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun forwardDao(): ForwardDao
}
