package com.bshsqa.dodochronicle.domain.model

import kotlinx.serialization.Serializable

const val EVENT_ARCHIVE_FORMAT = "dodochronicle.events"
const val EVENT_ARCHIVE_SCHEMA_VERSION = 1

@Serializable
data class EventArchive(
    val format: String = EVENT_ARCHIVE_FORMAT,
    val schemaVersion: Int = EVENT_ARCHIVE_SCHEMA_VERSION,
    val exportedAt: Long,
    val appVersion: String,
    val child: ExportedChild,
    val events: List<ExportedEvent>
)

@Serializable
data class ExportedChild(
    val name: String,
    val birthDate: String
)

@Serializable
data class ExportedEvent(
    val stableKey: String,
    val date: String,
    val category: String,
    val content: String,
    val longContent: String? = null,
    val rawExcerpt: String? = null,
    val isFavorite: Boolean = false,
    val isHidden: Boolean = false,
    val createdAt: Long,
    val searchSummary: String = "",
    val searchTags: List<String> = emptyList(),
    val searchAliases: List<String> = emptyList(),
    val relatedKeywords: List<String> = emptyList(),
    val searchContextVersion: Int = 0
)
