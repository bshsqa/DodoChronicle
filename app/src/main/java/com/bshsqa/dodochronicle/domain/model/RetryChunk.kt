package com.bshsqa.dodochronicle.domain.model

data class RetryChunk(
    val id: String,
    val roomId: String,
    val roomAlias: String,
    val sentAtStart: Long,
    val sentAtEnd: Long,
    val dateRange: String
)
