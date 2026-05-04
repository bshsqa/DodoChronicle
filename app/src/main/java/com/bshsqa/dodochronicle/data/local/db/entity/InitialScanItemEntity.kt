package com.bshsqa.dodochronicle.data.local.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "initial_scan_items",
    indices = [
        Index("sessionId"),
        Index("status"),
        Index("clusterId"),
        Index(value = ["sessionId", "uri"], unique = true)
    ]
)
data class InitialScanItemEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val uri: String,
    val takenAt: Long,
    val status: String,
    val embeddingJson: String = "[]",
    val clusterId: Int? = null,
    val errorMessage: String = "",
    val updatedAt: Long = 0L
)
