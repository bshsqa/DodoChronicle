package com.bshsqa.dodochronicle.domain.model

data class PendingPhoto(
    val uri: String,
    val takenAt: Long,
    val addedAtSeconds: Long = 0L,
    val similarity: Float,
    val faceEmbedding: FloatArray
)
