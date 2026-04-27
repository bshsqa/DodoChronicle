package com.bshsqa.dodochronicle.data.local.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "events",
    foreignKeys = [ForeignKey(
        entity = ChildEntity::class,
        parentColumns = ["id"],
        childColumns = ["childId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("childId"), Index("date")]
)
data class EventEntity(
    @PrimaryKey val id: String,
    val childId: String,
    val date: Long,
    val category: String,
    val content: String,
    val isFavorite: Boolean = false,
    val source: String,
    val createdAt: Long = System.currentTimeMillis(),
    val longContent: String? = null,
    val rawExcerpt: String? = null
)
