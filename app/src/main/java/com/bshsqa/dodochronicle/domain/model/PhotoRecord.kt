package com.bshsqa.dodochronicle.domain.model

data class PhotoRecord(
    val id: String,
    val eventId: String,
    val localUri: String,
    val takenAt: Long,
    val faceEmbedding: FloatArray = floatArrayOf(),
    val similarityScore: Float = 0f
)
