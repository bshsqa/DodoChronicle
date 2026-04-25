package com.bshsqa.dodochronicle.data.local.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "children")
data class ChildEntity(
    @PrimaryKey val id: String,
    val name: String,
    val birthDate: Long,
    val gender: String,
    val referencePhotoUri: String,
    val faceEmbeddingsJson: String = "[]",
    val createdAt: Long = System.currentTimeMillis()
)
