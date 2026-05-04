package com.bshsqa.dodochronicle.domain.usecase

import com.bshsqa.dodochronicle.BuildConfig
import com.bshsqa.dodochronicle.domain.model.EVENT_ARCHIVE_SCHEMA_VERSION
import com.bshsqa.dodochronicle.domain.model.EventArchive
import com.bshsqa.dodochronicle.domain.model.EventCategory
import com.bshsqa.dodochronicle.domain.model.ExportedEvent
import com.bshsqa.dodochronicle.domain.model.ExportedChild
import com.bshsqa.dodochronicle.domain.repository.ChildRepository
import com.bshsqa.dodochronicle.domain.repository.EventRepository
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

class ExportEventsUseCase @Inject constructor(
    private val childRepository: ChildRepository,
    private val eventRepository: EventRepository
) {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    suspend operator fun invoke(): String {
        val child = childRepository.getFirst() ?: error("초기 설정이 필요합니다")
        val events = eventRepository.getAllTextEvents(child.id)
            .filter { it.category != EventCategory.PHOTO }
            .sortedWith(compareByDescending<com.bshsqa.dodochronicle.domain.model.Event> { it.date }.thenBy { it.createdAt })

        val archive = EventArchive(
            schemaVersion = EVENT_ARCHIVE_SCHEMA_VERSION,
            exportedAt = System.currentTimeMillis(),
            appVersion = BuildConfig.VERSION_NAME,
            child = ExportedChild(
                name = child.name,
                birthDate = child.birthDate.toString()
            ),
            events = events.map { event ->
                ExportedEvent(
                    stableKey = stableKey(event.date.toString(), event.category.name, event.content),
                    date = event.date.toString(),
                    category = event.category.name,
                    content = event.content,
                    longContent = event.longContent,
                    rawExcerpt = event.rawExcerpt,
                    isFavorite = event.isFavorite,
                    isHidden = event.isHidden,
                    createdAt = event.createdAt,
                    searchSummary = event.searchSummary,
                    searchTags = event.searchTags,
                    searchAliases = event.searchAliases,
                    relatedKeywords = event.relatedKeywords,
                    searchContextVersion = event.searchContextVersion
                )
            }
        )

        return json.encodeToString(archive)
    }
}
