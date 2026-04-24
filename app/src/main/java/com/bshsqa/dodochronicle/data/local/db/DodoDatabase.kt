package com.bshsqa.dodochronicle.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
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
    version = 1,
    exportSchema = false
)
abstract class DodoDatabase : RoomDatabase() {
    abstract fun childDao(): ChildDao
    abstract fun eventDao(): EventDao
    abstract fun photoRecordDao(): PhotoRecordDao
    abstract fun kakaoRoomDao(): KakaoRoomDao
    abstract fun kakaoMessageDao(): KakaoMessageDao
}
