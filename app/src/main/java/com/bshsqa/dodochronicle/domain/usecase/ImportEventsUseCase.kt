package com.bshsqa.dodochronicle.domain.usecase

import com.bshsqa.dodochronicle.domain.model.EVENT_ARCHIVE_FORMAT
import com.bshsqa.dodochronicle.domain.model.EVENT_ARCHIVE_SCHEMA_VERSION
import com.bshsqa.dodochronicle.domain.model.Event
import com.bshsqa.dodochronicle.domain.model.EventArchive
import com.bshsqa.dodochronicle.domain.model.EventCategory
import com.bshsqa.dodochronicle.domain.model.EventSource
import com.bshsqa.dodochronicle.domain.repository.ChildRepository
import com.bshsqa.dodochronicle.domain.repository.EventRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject

class ImportEventsUseCase @Inject constructor(
    private val childRepository: ChildRepository,
    private val eventRepository: EventRepository,
    private val httpClient: OkHttpClient
) {
    data class Result(
        val imported: Int,
        val duplicates: Int,
        val skipped: Int
    )

    private val json = Json {
        ignoreUnknownKeys = true
    }

    suspend operator fun invoke(url: String): Result = withContext(Dispatchers.IO) {
        val child = childRepository.getFirst() ?: error("초기 설정이 필요합니다")
        val body = download(url)
        val archive = json.decodeFromString<EventArchive>(body)
        validateArchive(archive)

        val existingKeys = eventRepository.getAllTextEvents(child.id)
            .filter { it.category != EventCategory.PHOTO }
            .map { stableKey(it.date.toString(), it.category.name, it.content) }
            .toMutableSet()

        var duplicates = 0
        var skipped = 0
        val imports = mutableListOf<Event>()

        archive.events.forEach { exported ->
            val category = runCatching { EventCategory.valueOf(exported.category) }.getOrNull()
            val date = runCatching { LocalDate.parse(exported.date) }.getOrNull()
            val content = exported.content.trim()

            if (category == null || category == EventCategory.PHOTO || date == null || content.isBlank()) {
                skipped++
                return@forEach
            }

            val key = stableKey(date.toString(), category.name, content)
            if (key in existingKeys) {
                duplicates++
                return@forEach
            }

            existingKeys += key
            imports += Event(
                id = UUID.randomUUID().toString(),
                childId = child.id,
                date = date,
                category = category,
                content = content,
                isFavorite = exported.isFavorite,
                source = EventSource.MANUAL,
                createdAt = exported.createdAt,
                longContent = exported.longContent,
                rawExcerpt = exported.rawExcerpt,
                isHidden = exported.isHidden,
                searchSummary = exported.searchSummary,
                searchTags = exported.searchTags,
                searchAliases = exported.searchAliases,
                relatedKeywords = exported.relatedKeywords,
                searchContextVersion = exported.searchContextVersion
            )
        }

        if (imports.isNotEmpty()) {
            eventRepository.insertAll(imports)
        }

        Result(imported = imports.size, duplicates = duplicates, skipped = skipped)
    }

    private fun validateArchive(archive: EventArchive) {
        require(archive.format == EVENT_ARCHIVE_FORMAT) { "지원하지 않는 파일입니다" }
        require(archive.schemaVersion <= EVENT_ARCHIVE_SCHEMA_VERSION) { "지원하지 않는 파일 버전입니다" }
    }

    private fun download(rawUrl: String): String {
        val url = normalizeDownloadUrl(rawUrl.trim())
        require(url.startsWith("https://")) { "HTTPS 링크만 지원합니다" }

        val request = Request.Builder().url(url).get().build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("파일을 다운로드할 수 없습니다")
            val body = response.body ?: error("빈 응답입니다")
            val text = body.string()
            require(text.length <= MAX_ARCHIVE_CHARS) { "파일이 너무 큽니다" }
            return text
        }
    }

    private fun normalizeDownloadUrl(url: String): String {
        val filePathMatch = Regex("""drive\.google\.com/file/d/([^/]+)/""").find(url)
        if (filePathMatch != null) {
            return "https://drive.google.com/uc?export=download&id=${filePathMatch.groupValues[1]}"
        }

        val openIdMatch = Regex("""drive\.google\.com/open\?id=([^&]+)""").find(url)
        if (openIdMatch != null) {
            return "https://drive.google.com/uc?export=download&id=${openIdMatch.groupValues[1]}"
        }

        return url
    }

    companion object {
        private const val MAX_ARCHIVE_CHARS = 10 * 1024 * 1024
    }
}

fun stableKey(date: String, category: String, content: String): String =
    listOf(date, category, content.trim().replace(Regex("""\s+"""), " "))
        .joinToString("|")
