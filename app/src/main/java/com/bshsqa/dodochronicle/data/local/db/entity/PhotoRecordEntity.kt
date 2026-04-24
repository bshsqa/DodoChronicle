package com.bshsqa.dodochronicle.data.local.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "photo_records",
    foreignKeys = [ForeignKey(
        entity = EventEntity::class,
        parentColumns = ["id"],
        childColumns = ["eventId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("eventId"), Index("localUri", unique = true)]
)
data class PhotoRecordEntity(
    @PrimaryKey val id: String,
    val eventId: String,
    val localUri: String,
    val takenAt: Long,
    val faceEmbeddingJson: String = "[]",
    val similarityScore: Float = 0f
)
