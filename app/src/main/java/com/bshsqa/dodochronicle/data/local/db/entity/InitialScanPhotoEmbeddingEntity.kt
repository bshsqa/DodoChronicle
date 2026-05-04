package com.bshsqa.dodochronicle.data.local.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "initial_scan_photo_embeddings",
    indices = [
        Index("sessionId"),
        Index("clusterId"),
        Index("uri")
    ]
)
data class InitialScanPhotoEmbeddingEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val uri: String,
    val takenAt: Long,
    val embeddingJson: String,
    val clusterId: Int
)
