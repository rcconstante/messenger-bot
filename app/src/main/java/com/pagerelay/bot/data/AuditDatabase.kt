package com.pagerelay.bot.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [AuditLogEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AuditDatabase : RoomDatabase() {

    abstract fun auditLogDao(): AuditLogDao

    companion object {
        private const val DATABASE_NAME = "pagerelay_audit.db"

        @Volatile
        private var instance: AuditDatabase? = null

        fun getInstance(context: Context): AuditDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AuditDatabase::class.java,
                    DATABASE_NAME
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
