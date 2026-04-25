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
        data class Success(val addedMessages: Int, val addedEvents: Int) : Result()
        data class Error(val message: String) : Result()
    }

    suspend operator fun invoke(inputStream: InputStream): Result {
        return try {
            val child = childRepository.getFirst() ?: return Result.Error("아이 정보가 없습니다")
            val content = inputStream.bufferedReader(Charsets.UTF_8).readText()
            val parsed = kakaoParser.parse(content)

            var room = kakaoRepository.getRoomByName(parsed.roomName)
            if (room == null) {
                room = KakaoRoom(
                    id = UUID.randomUUID().toString(),
                    roomName = parsed.roomName
                )
                kakaoRepository.upsertRoom(room)
            }

            val lastImportedAt = kakaoRepository.getLatestMessageSentAt(room.id) ?: 0L
            val newMessages = parsed.messages
                .filter { it.sentAt > lastImportedAt }
                .filter { !kakaoRepository.messageExistsByHash(it.contentHash) }
                .map { it.copy(roomId = room.id) }

            if (newMessages.isEmpty()) return Result.Success(0, 0)

            kakaoRepository.insertMessages(newMessages)

            val latestAt = newMessages.maxOf { it.sentAt }
            kakaoRepository.updateLastImported(room.id, latestAt)

            val textMessages = newMessages.filter { it.content != "사진" && it.content != "동영상" }
            val extractedEvents = geminiClassifier.extractEvents(
                messages = textMessages,
                childName = child.name
            )

            val events = extractedEvents.map { extracted ->
                Event(
                    id = UUID.randomUUID().toString(),
                    childId = child.id,
                    date = extracted.date,
                    category = extracted.category,
                    content = extracted.content,
                    source = EventSource.KAKAO
                )
            }

            if (events.isNotEmpty()) eventRepository.insertAll(events)

            Result.Success(newMessages.size, events.size)
        } catch (e: Exception) {
            Result.Error(e.message ?: "알 수 없는 오류")
        }
    }
}
