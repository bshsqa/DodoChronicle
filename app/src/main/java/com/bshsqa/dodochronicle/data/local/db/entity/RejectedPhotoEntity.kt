package com.bshsqa.dodochronicle.data.local.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "rejected_photos",
    foreignKeys = [ForeignKey(
        entity = ChildEntity::class,
        parentColumns = ["id"],
        childColumns = ["childId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("childId"), Index("addedAtSeconds")]
)
data class RejectedPhotoEntity(
    @PrimaryKey val uri: String,
    val childId: String,
    val addedAtSeconds: Long,
    val rejectedAt: Long = System.currentTimeMillis()
)
