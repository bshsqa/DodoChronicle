package com.bshsqa.dodochronicle.domain.usecase

import android.util.Log
import com.bshsqa.dodochronicle.ai.ChunkProgress
import com.bshsqa.dodochronicle.ai.ExtractedEvent
import com.bshsqa.dodochronicle.ai.GeminiEventClassifier
import com.bshsqa.dodochronicle.domain.model.Event
import com.bshsqa.dodochronicle.domain.model.EventSource
import com.bshsqa.dodochronicle.domain.model.KakaoRoom
import com.bshsqa.dodochronicle.domain.model.RetryChunk
import com.bshsqa.dodochronicle.domain.repository.ChildRepository
import com.bshsqa.dodochronicle.domain.repository.EventRepository
import com.bshsqa.dodochronicle.domain.repository.KakaoRepository
import com.bshsqa.dodochronicle.domain.repository.RetryChunkRepository
import com.bshsqa.dodochronicle.kakao.KakaoParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.UUID
import javax.inject.Inject

class ImportKakaoUseCase @Inject constructor(
    private val kakaoParser: KakaoParser,
    private val kakaoRepository: KakaoRepository,
    private val eventRepository: EventRepository,
    private val childRepository: ChildRepository,
    private val geminiClassifier: GeminiEventClassifier,
    private val retryChunkRepository: RetryChunkRepository
) {
    sealed class Result {
        data class Success(
            val addedMessages: Int,
            val addedEvents: Int,
            val apiRequests: Int = 0,
            val totalTokens: Int = 0,
            val failedChunks: Int = 0,
            val apiKeyMissing: Boolean = false,
            val cancelled: Boolean = false
        ) : Result()
        data class Error(val message: String) : Result()
    }

    suspend operator fun invoke(
        inputStream: InputStream,
        roomAlias: String,
        onProgress: ((ChunkProgress) -> Unit)? = null,
        isCancelled: (() -> Boolean)? = null
    ): Result = withContext(Dispatchers.IO) {
        try {
            val child = childRepository.getFirst() ?: return@withContext Result.Error("아이 정보가 없습니다")
            val content = inputStream.bufferedReader(Charsets.UTF_8).readText()
            val parsed = kakaoParser.parse(content)

            var room = kakaoRepository.getRoomByName(roomAlias)
            if (room == null) {
                room = KakaoRoom(id = UUID.randomUUID().toString(), roomName = roomAlias)
                kakaoRepository.upsertRoom(room)
            }

            val lastImportedAt = kakaoRepository.getLatestMessageSentAt(room.id) ?: 0L
            val existingHashes = kakaoRepository.getAllHashesForRoom(room.id)
            val newMessages = parsed.messages
                .filter { it.sentAt > lastImportedAt }
                .filter { it.contentHash !in existingHashes }
                .map { it.copy(roomId = room.id) }
                .sortedBy { it.sentAt }

            if (newMessages.isEmpty()) return@withContext Result.Success(0, 0)

            val textMessages = newMessages.filter { it.content != "사진" && it.content != "동영상" }
            if (!geminiClassifier.hasApiKey || textMessages.isEmpty()) {
                kakaoRepository.insertMessages(newMessages)
                kakaoRepository.updateLastImported(room.id, newMessages.last().sentAt)
                return@withContext Result.Success(
                    addedMessages = newMessages.size,
                    addedEvents = 0,
                    apiKeyMissing = !geminiClassifier.hasApiKey
                )
            }

            val chunks = geminiClassifier.buildChunks(textMessages)
            var lastSavedSentAt = lastImportedAt
            var totalAddedMessages = 0
            var totalAddedEvents = 0
            var totalRequests = 0
            var totalTokens = 0
            var failedChunks = 0
            var cancelled = false

            for ((index, chunk) in chunks.withIndex()) {
                if (isCancelled?.invoke() == true) {
                    cancelled = true
                    break
                }

                if (index > 0) delay(12000L)

                val chunkDates = chunk.map {
                    java.time.Instant.ofEpochMilli(it.sentAt)
                        .atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalDate()
                }
                val dateRangeStr = "${chunkDates.first()} ~ ${chunkDates.last()}"
                onProgress?.invoke(ChunkProgress(index, chunks.size, dateRangeStr))

                val chunkEndTime = if (index < chunks.size - 1) {
                    chunks[index + 1].first().sentAt - 1L
                } else {
                    newMessages.last().sentAt
                }

                val chunkMessages = newMessages.filter { it.sentAt > lastSavedSentAt && it.sentAt <= chunkEndTime }

                var attempt = 0
                var apiEvents = emptyList<ExtractedEvent>()
                var apiTokens = 0
                var apiSucceeded = false

                while (attempt < 3 && !apiSucceeded) {
                    if (attempt > 0) {
                        if (isCancelled?.invoke() == true) break
                        delay(12000L)
                    }
                    try {
                        val (events, tokens) = geminiClassifier.processChunk(
                            chunk, child.name, child.birthDate, child.gender
                        )
                        apiEvents = events
                        apiTokens = tokens
                        if (tokens > 0 || events.isNotEmpty()) {
                            apiSucceeded = true
                        } else {
                            attempt++
                        }
                    } catch (e: Exception) {
                        Log.e("ImportKakao", "Chunk $index attempt $attempt failed: ${e.message}", e)
                        attempt++
                    }
                }

                kakaoRepository.insertMessages(chunkMessages)
                kakaoRepository.updateLastImported(room.id, chunkEndTime)
                lastSavedSentAt = chunkEndTime
                totalAddedMessages += chunkMessages.size

                if (apiSucceeded) {
                    val eventList = apiEvents.map { extracted ->
                        Event(
                            id = UUID.randomUUID().toString(),
                            childId = child.id,
                            date = extracted.date,
                            category = extracted.category,
                            content = extracted.content,
                            longContent = extracted.longContent,
                            rawExcerpt = extracted.rawExcerpt,
                            source = EventSource.KAKAO
                        )
                    }
                    if (eventList.isNotEmpty()) eventRepository.insertAll(eventList)
                    totalAddedEvents += eventList.size
                    totalRequests++
                    totalTokens += apiTokens
                } else {
                    failedChunks++
                    retryChunkRepository.save(listOf(RetryChunk(
                        id = UUID.randomUUID().toString(),
                        roomId = room.id,
                        roomAlias = roomAlias,
                        sentAtStart = chunk.first().sentAt,
                        sentAtEnd = chunk.last().sentAt,
                        dateRange = dateRangeStr
                    )))
                }
            }

            Result.Success(
                addedMessages = totalAddedMessages,
                addedEvents = totalAddedEvents,
                apiRequests = totalRequests,
                totalTokens = totalTokens,
                failedChunks = failedChunks,
                cancelled = cancelled
            )
        } catch (e: Exception) {
            Result.Error(e.message ?: "알 수 없는 오류")
        }
    }
}
