package com.bshsqa.dodochronicle.domain.model

data class KakaoMessage(
    val id: String,
    val roomId: String,
    val sender: String,
    val sentAt: Long,
    val content: String,
    val contentHash: String
)
