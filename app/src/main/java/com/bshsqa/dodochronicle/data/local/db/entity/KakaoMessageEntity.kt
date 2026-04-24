package com.bshsqa.dodochronicle.data.local.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "kakao_messages",
    foreignKeys = [ForeignKey(
        entity = KakaoRoomEntity::class,
        parentColumns = ["id"],
        childColumns = ["roomId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("roomId"), Index("contentHash", unique = true)]
)
data class KakaoMessageEntity(
    @PrimaryKey val id: String,
    val roomId: String,
    val sender: String,
    val sentAt: Long,
    val content: String,
    val contentHash: String
)
