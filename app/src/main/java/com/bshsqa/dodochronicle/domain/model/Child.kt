package com.bshsqa.dodochronicle.domain.model

import java.time.LocalDate

enum class Gender { MALE, FEMALE }

data class Child(
    val id: String,
    val name: String,
    val birthDate: LocalDate,
    val gender: Gender,
    val referencePhotoUri: String,
    val faceEmbeddings: List<FloatArray> = emptyList()
)
