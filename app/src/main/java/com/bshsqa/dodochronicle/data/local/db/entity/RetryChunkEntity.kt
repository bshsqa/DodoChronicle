package com.bshsqa.dodochronicle.data.local.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "retry_chunks")
data class RetryChunkEntity(
    @PrimaryKey val id: String,
    val roomId: String,
    val roomAlias: String,
    val sentAtStart: Long,
    val sentAtEnd: Long,
    val dateRange: String,
    val createdAt: Long = System.currentTimeMillis()
)
