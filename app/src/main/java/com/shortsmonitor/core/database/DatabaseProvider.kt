package com.shortsmonitor.core.database

import android.content.Context
import androidx.room.Room

/**
 * 앱 전역에서 하나의 [AppDatabase] 인스턴스를 제공한다.
 */
object DatabaseProvider {

    @Volatile
    private var instance: AppDatabase? = null

    fun get(context: Context): AppDatabase =
        instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                AppDatabase.NAME,
            )
                .addMigrations(*AppDatabase.ALL_MIGRATIONS)
                .build()
                .also { instance = it }
        }
}
