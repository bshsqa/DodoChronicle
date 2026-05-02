package com.bshsqa.dodochronicle.domain.usecase

import android.util.Log
import com.bshsqa.dodochronicle.ai.ChunkProgress
import com.bshsqa.dodochronicle.ai.GeminiEventClassifier
import com.bshsqa.dodochronicle.domain.model.Event
import com.bshsqa.dodochronicle.domain.model.EventSource
import com.bshsqa.dodochronicle.domain.repository.ChildRepository
import com.bshsqa.dodochronicle.domain.repository.EventRepository
import com.bshsqa.dodochronicle.domain.repository.KakaoRepository
import com.bshsqa.dodochronicle.domain.repository.RetryChunkRepository
import com.bshsqa.dodochronicle.ml.TextEmbeddingEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject

class RetryFailedChunksUseCase @Inject constructor(
    private val retryChunkRepository: RetryChunkRepository,
    private val kakaoRepository: KakaoRepository,
    private val eventRepository: EventRepository,
    private val childRepository: ChildRepository,
    private val geminiClassifier: GeminiEventClassifier,
    private val textEmbeddingEngine: TextEmbeddingEngine
) {
    data class Result(
        val addedEvents: Int,
        val apiRequests: Int,
        val totalTokens: Int,
        val remainingFailedChunks: Int
    )

    suspend operator fun invoke(
        roomId: String,
        onProgress: ((ChunkProgress) -> Unit)? = null,
        isCancelled: (() -> Boolean)? = null
    ): Result = withContext(Dispatchers.IO) {
        val child = childRepository.getFirst()
            ?: return@withContext Result(0, 0, 0, 0)

        val retryChunks = retryChunkRepository.getByRoom(roomId).sortedBy { it.sentAtStart }
        if (retryChunks.isEmpty()) return@withContext Result(0, 0, 0, 0)

        var totalAddedEvents = 0
        var totalRequests = 0
        var totalTokens = 0
        var remainingFailedChunks = 0

        retryChunks.forEachIndexed { index, retryChunk ->
            if (isCancelled?.invoke() == true) {
                remainingFailedChunks += retryChunks.size - index
                return@withContext Result(totalAddedEvents, totalRequests, totalTokens, remainingFailedChunks)
            }

            if (index > 0) delay(12_000L)

            onProgress?.invoke(ChunkProgress(index, retryChunks.size, retryChunk.dateRange))

            val messages = kakaoRepository
                .getMessagesInRange(roomId, retryChunk.sentAtStart, retryChunk.sentAtEnd)
                .filter { it.content != "사진" && it.content != "동영상" }

            if (messages.isEmpty()) {
                retryChunkRepository.delete(listOf(retryChunk.id))
                return@forEachIndexed
            }

            try {
                val (events, tokens) = geminiClassifier.processChunk(
                    messages, child.name, child.birthDate, child.gender
                )

                val eventList = events.map { extracted ->
                    val combinedText = "${extracted.content}\n${extracted.longContent ?: ""}".trim()
                    val embedding = textEmbeddingEngine.getEmbedding(combinedText)
                    Event(
                        id = UUID.randomUUID().toString(),
                        childId = child.id,
                        date = extracted.date,
                        category = extracted.category,
                        content = extracted.content,
                        longContent = extracted.longContent,
                        rawExcerpt = extracted.rawExcerpt,
                        source = EventSource.KAKAO,
                        textEmbeddingJson = Json.encodeToString(embedding.toList())
                    )
                }
                if (eventList.isNotEmpty()) eventRepository.insertAll(eventList)
                totalAddedEvents += eventList.size

                if (tokens > 0 || events.isNotEmpty()) {
                    totalRequests++
                    retryChunkRepository.delete(listOf(retryChunk.id))
                } else {
                    remainingFailedChunks++
                }
                totalTokens += tokens

            } catch (e: Exception) {
                Log.e("RetryChunks", "Chunk retry failed: ${e.message}", e)
                remainingFailedChunks++
            }
        }

        Result(totalAddedEvents, totalRequests, totalTokens, remainingFailedChunks)
    }
}
