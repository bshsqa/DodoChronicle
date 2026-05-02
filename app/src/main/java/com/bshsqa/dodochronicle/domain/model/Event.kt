package com.bshsqa.dodochronicle.domain.model

import java.time.LocalDate

enum class EventCategory { SAID, DID, PHOTO, OTHER }
enum class EventSource { KAKAO, PHOTO, MANUAL }

data class Event(
    val id: String,
    val childId: String,
    val date: LocalDate,
    val category: EventCategory,
    val content: String,
    val isFavorite: Boolean = false,
    val source: EventSource,
    val createdAt: Long = System.currentTimeMillis(),
    val longContent: String? = null,
    val rawExcerpt: String? = null,
    val isHidden: Boolean = false,
    val textEmbeddingJson: String = "[]"
)
