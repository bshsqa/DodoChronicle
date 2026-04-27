package com.bshsqa.dodochronicle.kakao

import com.bshsqa.dodochronicle.domain.model.KakaoMessage
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class ParsedResult(val messages: List<KakaoMessage>)

@Singleton
class KakaoParser @Inject constructor() {

    // "날짜+시간, 발신자 : 내용" 형태 — PC/모바일 공통 메시지 패턴
    private val msgPattern = Regex("""^(\d{4}년 \d{1,2}월 \d{1,2}일 (?:오전|오후) \d{1,2}:\d{2}), (.+?) : (.+)$""")

    // 날짜+시간만 있는 줄 — 모바일 포맷 첫 줄 또는 날짜 구분선
    private val dateOnlyPattern = Regex("""^\d{4}년 \d{1,2}월 \d{1,2}일 (?:오전|오후) \d{1,2}:\d{2}$""")

    fun parse(content: String): ParsedResult {
        val lines = content.lines()
        val messages = mutableListOf<KakaoMessage>()
        var currentSender = ""
        var currentSentAt = 0L
        var currentContent = StringBuilder()

        fun flush() {
            if (currentSentAt > 0 && currentContent.isNotEmpty()) {
                val text = currentContent.toString().trim()
                messages.add(
                    KakaoMessage(
                        id = UUID.randomUUID().toString(),
                        roomId = "",
                        sender = currentSender,
                        sentAt = currentSentAt,
                        content = text,
                        contentHash = sha256("$currentSentAt|$text")
                    )
                )
            }
            currentContent.clear()
        }

        for (line in lines) {
            // 모바일 포맷 헤더 2줄 명시적 스킵
            if (line.contains("님과 카카오톡 대화") || line.startsWith("저장한 날짜 :")) continue
            when {
                dateOnlyPattern.matches(line) -> {
                    flush()
                    currentSentAt = 0L
                }
                msgPattern.matches(line) -> {
                    flush()
                    val groups = msgPattern.find(line)!!.groupValues
                    currentSentAt = parseDateTime(groups[1])
                    currentSender = groups[2]
                    currentContent.append(groups[3])
                }
                line.isBlank() -> flush()
                currentSentAt > 0 -> currentContent.append('\n').append(line)
            }
        }
        flush()

        return ParsedResult(messages)
    }

    private fun parseDateTime(s: String): Long {
        val r = Regex("""(\d{4})년 (\d{1,2})월 (\d{1,2})일 (오전|오후) (\d{1,2}):(\d{2})""")
        val g = r.find(s)?.groupValues ?: return 0L
        var hour = g[5].toInt()
        val ampm = g[4]
        if (ampm == "오후" && hour != 12) hour += 12
        if (ampm == "오전" && hour == 12) hour = 0
        return LocalDateTime.of(g[1].toInt(), g[2].toInt(), g[3].toInt(), hour, g[6].toInt())
            .atZone(ZoneId.of("Asia/Seoul"))
            .toInstant().toEpochMilli()
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
