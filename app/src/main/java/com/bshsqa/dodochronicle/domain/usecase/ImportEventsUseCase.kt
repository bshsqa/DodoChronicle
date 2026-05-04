package com.bshsqa.dodochronicle.domain.usecase

import android.util.Log
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
        Log.i(TAG, "Import started. rawUrl=${url.take(160)}")
        val body = download(url)
        importBody(body, sourceLabel = "url")
    }

    suspend fun fromJson(rawJson: String): Result = withContext(Dispatchers.IO) {
        Log.i(TAG, "Import started from local file. chars=${rawJson.length}")
        if (looksLikeHtml(rawJson)) {
            Log.e(TAG, "Local file is HTML instead of JSON. prefix=${rawJson.take(200).replace('\n', ' ')}")
            error("JSON 파일이 아닙니다.")
        }
        require(rawJson.length <= MAX_ARCHIVE_CHARS) { "파일이 너무 큽니다" }
        importBody(rawJson, sourceLabel = "local")
    }

    private suspend fun importBody(body: String, sourceLabel: String): Result {
        val child = childRepository.getFirst() ?: error("초기 설정이 필요합니다")
        Log.i(TAG, "Import body ready. source=$sourceLabel, chars=${body.length}, prefix=${body.take(120).replace('\n', ' ')}")
        val archive = runCatching { json.decodeFromString<EventArchive>(body) }
            .onFailure { Log.e(TAG, "JSON parse failed. chars=${body.length}", it) }
            .getOrThrow()
        validateArchive(archive)
        Log.i(
            TAG,
            "Archive validated. format=${archive.format}, schemaVersion=${archive.schemaVersion}, events=${archive.events.size}"
        )

        val existingKeys = eventRepository.getAllTextEvents(child.id)
            .filter { it.category != EventCategory.PHOTO }
            .map { stableKey(it.date.toString(), it.category.name, it.content) }
            .toMutableSet()
        Log.i(TAG, "Existing text events loaded. count=${existingKeys.size}")

        var duplicates = 0
        var skipped = 0
        val imports = mutableListOf<Event>()

        archive.events.forEach { exported ->
            val category = runCatching { EventCategory.valueOf(exported.category) }.getOrNull()
            val date = runCatching { LocalDate.parse(exported.date) }.getOrNull()
            val content = exported.content.trim()

            if (category == null || category == EventCategory.PHOTO || date == null || content.isBlank()) {
                skipped++
                Log.w(
                    TAG,
                    "Event skipped. stableKey=${exported.stableKey}, category=${exported.category}, date=${exported.date}, contentBlank=${content.isBlank()}"
                )
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

        Log.i(TAG, "Import finished. imported=${imports.size}, duplicates=$duplicates, skipped=$skipped")
        return Result(imported = imports.size, duplicates = duplicates, skipped = skipped)
    }

    private fun validateArchive(archive: EventArchive) {
        if (archive.format != EVENT_ARCHIVE_FORMAT) {
            Log.e(TAG, "Unsupported archive format. actual=${archive.format}, expected=$EVENT_ARCHIVE_FORMAT")
        }
        require(archive.format == EVENT_ARCHIVE_FORMAT) { "지원하지 않는 파일입니다" }
        if (archive.schemaVersion > EVENT_ARCHIVE_SCHEMA_VERSION) {
            Log.e(
                TAG,
                "Unsupported archive version. actual=${archive.schemaVersion}, supported=$EVENT_ARCHIVE_SCHEMA_VERSION"
            )
        }
        require(archive.schemaVersion <= EVENT_ARCHIVE_SCHEMA_VERSION) { "지원하지 않는 파일 버전입니다" }
    }

    private fun download(rawUrl: String): String {
        val url = normalizeDownloadUrl(rawUrl.trim())
        Log.i(TAG, "Normalized import URL. normalizedUrl=${url.take(200)}")
        require(url.startsWith("https://")) { "HTTPS 링크만 지원합니다" }

        val request = Request.Builder().url(url).get().build()
        httpClient.newCall(request).execute().use { response ->
            Log.i(
                TAG,
                "Download response. code=${response.code}, contentType=${response.body?.contentType()}, contentLength=${response.body?.contentLength()}"
            )
            if (!response.isSuccessful) error("파일을 다운로드할 수 없습니다")
            val body = response.body ?: error("빈 응답입니다")
            val text = body.string()
            Log.i(TAG, "Response body read. chars=${text.length}")
            if (looksLikeHtml(text)) {
                Log.e(TAG, "Download returned HTML instead of JSON. prefix=${text.take(200).replace('\n', ' ')}")
                if (text.contains("accounts.google.com", ignoreCase = true) ||
                    text.contains("ServiceLogin", ignoreCase = true)
                ) {
                    error("파일 접근 권한이 없습니다. Google Drive 공유를 '링크가 있는 모든 사용자'로 바꿔주세요.")
                }
                error("JSON 파일 대신 웹 페이지를 받았습니다. 직접 다운로드 가능한 공유 링크인지 확인해주세요.")
            }
            require(text.length <= MAX_ARCHIVE_CHARS) { "파일이 너무 큽니다" }
            return text
        }
    }

    private fun looksLikeHtml(text: String): Boolean {
        val prefix = text.trimStart().take(80)
        return prefix.startsWith("<!doctype html", ignoreCase = true) ||
            prefix.startsWith("<html", ignoreCase = true)
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
        private const val TAG = "DodoEventImport"
        private const val MAX_ARCHIVE_CHARS = 10 * 1024 * 1024
    }
}

fun stableKey(date: String, category: String, content: String): String =
    listOf(date, category, content.trim().replace(Regex("""\s+"""), " "))
        .joinToString("|")
