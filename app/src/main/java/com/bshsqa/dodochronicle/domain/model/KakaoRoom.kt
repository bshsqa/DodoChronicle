package com.bshsqa.dodochronicle.domain.model

data class KakaoRoom(
    val id: String,
    val roomName: String,
    val lastImportedAt: Long = 0L
)
