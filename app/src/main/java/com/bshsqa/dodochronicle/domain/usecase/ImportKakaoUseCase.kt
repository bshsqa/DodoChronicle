package com.bshsqa.dodochronicle.domain.usecase

import com.bshsqa.dodochronicle.ai.GeminiEventClassifier
import com.bshsqa.dodochronicle.domain.model.Event
import com.bshsqa.dodochronicle.domain.model.EventSource
import com.bshsqa.dodochronicle.domain.model.KakaoRoom
import com.bshsqa.dodochronicle.domain.repository.ChildRepository
import com.bshsqa.dodochronicle.domain.repository.EventRepository
import com.bshsqa.dodochronicle.domain.repository.KakaoRepository
import com.bshsqa.dodochronicle.kakao.KakaoParser
import java.io.InputStream
import java.util.UUID
import javax.inject.Inject

class ImportKakaoUseCase @Inject constructor(
    private val kakaoParser: KakaoParser,
    private val kakaoRepository: KakaoRepository,
    private val eventRepository: EventRepository,
    private val childRepository: ChildRepository,
    private val geminiClassifier: GeminiEventClassifier
) {
    sealed class Result {
        data class Success(
            val addedMessages: Int,
            val addedEvents: Int,
            val apiRequests: Int = 0,
            val totalTokens: Int = 0
        ) : Result()
        data class Error(val message: String) : Result()
    }

    suspend operator fun invoke(inputStream: InputStream, roomAlias: String): Result {
        return try {
            val child = childRepository.getFirst() ?: return Result.Error("아이 정보가 없습니다")
            val content = inputStream.bufferedReader(Charsets.UTF_8).readText()
            val parsed = kakaoParser.parse(content)

            var room = kakaoRepository.getRoomByName(roomAlias)
            if (room == null) {
                room = KakaoRoom(
                    id = UUID.randomUUID().toString(),
                    roomName = roomAlias
                )
                kakaoRepository.upsertRoom(room)
            }

            val lastImportedAt = kakaoRepository.getLatestMessageSentAt(room.id) ?: 0L
            val newMessages = parsed.messages
                .filter { it.sentAt > lastImportedAt }
                .filter { !kakaoRepository.messageExistsByHash(it.contentHash) }
                .map { it.copy(roomId = room.id) }

            if (newMessages.isEmpty()) return Result.Success(0, 0)

            // AI 추출을 DB 쓰기 전에 실행 — 실패 시 DB에 아무것도 쓰이지 않아 재시도 가능
            val textMessages = newMessages.filter { it.content != "사진" && it.content != "동영상" }
            val extractionResult = geminiClassifier.extractEvents(
                messages = textMessages,
                childName = child.name
            )

            val events = extractionResult.events.map { extracted ->
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

            kakaoRepository.insertMessages(newMessages)

            val latestAt = newMessages.maxOf { it.sentAt }
            kakaoRepository.updateLastImported(room.id, latestAt)

            if (events.isNotEmpty()) eventRepository.insertAll(events)

            Result.Success(
                addedMessages = newMessages.size,
                addedEvents = events.size,
                apiRequests = extractionResult.stats.requestCount,
                totalTokens = extractionResult.stats.totalTokens
            )
        } catch (e: Exception) {
            Result.Error(e.message ?: "알 수 없는 오류")
        }
    }
}
