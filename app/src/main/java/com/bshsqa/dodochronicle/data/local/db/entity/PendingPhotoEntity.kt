package com.bshsqa.dodochronicle.data.local.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "pending_photos",
    foreignKeys = [ForeignKey(
        entity = ChildEntity::class,
        parentColumns = ["id"],
        childColumns = ["childId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("childId"), Index("createdAt")]
)
data class PendingPhotoEntity(
    @PrimaryKey val uri: String,
    val childId: String,
    val takenAt: Long,
    val addedAtSeconds: Long,
    val similarity: Float,
    val faceEmbeddingJson: String,
    val createdAt: Long = System.currentTimeMillis()
)
