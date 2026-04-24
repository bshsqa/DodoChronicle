package com.bshsqa.dodochronicle.domain.model

import java.time.LocalDate

data class Child(
    val id: String,
    val name: String,
    val birthDate: LocalDate,
    val referencePhotoUri: String,
    val faceEmbeddings: List<FloatArray> = emptyList()
)
