package com.bshsqa.dodochronicle.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.bshsqa.dodochronicle.data.local.db.dao.*
import com.bshsqa.dodochronicle.data.local.db.entity.*

@Database(
    entities = [
        ChildEntity::class,
        EventEntity::class,
        PhotoRecordEntity::class,
        KakaoRoomEntity::class,
        KakaoMessageEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class DodoDatabase : RoomDatabase() {
    abstract fun childDao(): ChildDao
    abstract fun eventDao(): EventDao
    abstract fun photoRecordDao(): PhotoRecordDao
    abstract fun kakaoRoomDao(): KakaoRoomDao
    abstract fun kakaoMessageDao(): KakaoMessageDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE children ADD COLUMN gender TEXT NOT NULL DEFAULT 'MALE'"
                )
            }
        }
    }
}
