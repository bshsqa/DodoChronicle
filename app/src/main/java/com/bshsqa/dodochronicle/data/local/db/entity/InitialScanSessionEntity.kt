package com.bshsqa.dodochronicle.data.local.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "initial_scan_sessions")
data class InitialScanSessionEntity(
    @PrimaryKey val id: String,
    val childName: String,
    val birthDate: String,
    val gender: String,
    val referencePhotoUri: String,
    val status: String,
    val startedAt: Long,
    val completedAt: Long? = null,
    val totalCount: Int = 0,
    val processedCount: Int = 0,
    val elapsedSeconds: Long = 0L,
    val lastCheckpointAt: Long = 0L
)
