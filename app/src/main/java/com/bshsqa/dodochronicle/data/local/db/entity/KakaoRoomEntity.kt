package com.bshsqa.dodochronicle.data.local.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "kakao_rooms")
data class KakaoRoomEntity(
    @PrimaryKey val id: String,
    val roomName: String,
    val lastImportedAt: Long = 0L
)
