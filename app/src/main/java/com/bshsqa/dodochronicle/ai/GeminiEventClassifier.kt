package com.bshsqa.dodochronicle.ai

import com.bshsqa.dodochronicle.domain.model.EventCategory
import com.bshsqa.dodochronicle.domain.model.KakaoMessage
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

data class ExtractedEvent(
    val date: LocalDate,
    val category: EventCategory,
    val content: String
)

@Singleton
class GeminiEventClassifier @Inject constructor(
    private val httpClient: OkHttpClient,
    @Named("gemini_api_key") private val apiKey: String,
    @Named("gemini_model") private val model: String
) {
    private val gson = Gson()
    private val json = "application/json".toMediaType()

    suspend fun extractEvents(
        messages: List<KakaoMessage>,
        childName: String
    ): List<ExtractedEvent> = withContext(Dispatchers.IO) {
        if (messages.isEmpty() || apiKey.isBlank()) return@withContext emptyList()

        val msgText = messages.joinToString("\n") { msg ->
            val date = java.time.Instant.ofEpochMilli(msg.sentAt)
                .atZone(java.time.ZoneId.of("Asia/Seoul"))
                .toLocalDate()
                .format(DateTimeFormatter.ISO_LOCAL_DATE)
            "[$date] ${msg.sender}: ${msg.content}"
        }

        val prompt = buildPrompt(msgText, childName)
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"

        val body = gson.toJson(
            mapOf(
                "contents" to listOf(mapOf("parts" to listOf(mapOf("text" to prompt)))),
                "generationConfig" to mapOf(
                    "responseMimeType" to "application/json",
                    "temperature" to 0.2,
                    "maxOutputTokens" to 2048
                )
            )
        ).toRequestBody(json)

        val request = Request.Builder().url(url).post(body).build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val raw = response.body?.string() ?: return@withContext emptyList()
                parseResponse(raw)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun buildPrompt(messages: String, childName: String) = """
당신은 가족 카카오톡 대화에서 아이의 성장 관련 이벤트를 추출하는 전문가입니다.

아이 이름: $childName

다음 규칙을 따르세요:
- $childName 이(가) 직접 한 말, 행동, 성취, 신체 변화를 이벤트로 추출합니다
- $childName 을(를) 지칭하는 다양한 표현도 아이로 인식하세요.
  예: "우리애기", "애기", "아가", "왕자님", "공주님", "우리 아이", "얘", "쟤", "우리 아들", "우리 딸"
  이름을 직접 언급하지 않더라도 대화 흐름, 발신자, 문맥을 통해 아이를 가리키는지 판단하세요.
- 카테고리: SAID(아이가 한 말), DID(아이가 한 행동/성취), OTHER(기타 아이 관련)
- 아이와 무관한 대화는 무시합니다
- 이벤트 내용은 아이 관점에서 간결하게 서술합니다 (예: "처음으로 뒤집기 성공")
- date는 해당 메시지의 날짜를 사용합니다

대화 내용:
$messages

JSON 배열로만 응답하세요 (다른 텍스트 없이):
[{"date":"YYYY-MM-DD","category":"SAID|DID|OTHER","content":"이벤트 내용"}]

이벤트가 없으면 []
""".trimIndent()

    private data class GeminiResponse(val candidates: List<Candidate>?)
    private data class Candidate(val content: Content?)
    private data class Content(val parts: List<Part>?)
    private data class Part(val text: String?)
    private data class RawEvent(
        @SerializedName("date") val date: String?,
        @SerializedName("category") val category: String?,
        @SerializedName("content") val content: String?
    )

    private fun parseResponse(raw: String): List<ExtractedEvent> {
        return try {
            val geminiRes = gson.fromJson(raw, GeminiResponse::class.java)
            val text = geminiRes.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                ?: return emptyList()
            val cleanText = text.trim().removePrefix("```json").removeSuffix("```").trim()
            val rawEvents = gson.fromJson(cleanText, Array<RawEvent>::class.java)
            rawEvents.mapNotNull { r ->
                try {
                    ExtractedEvent(
                        date = LocalDate.parse(r.date ?: return@mapNotNull null),
                        category = when (r.category) {
                            "SAID" -> EventCategory.SAID
                            "DID" -> EventCategory.DID
                            else -> EventCategory.OTHER
                        },
                        content = r.content ?: return@mapNotNull null
                    )
                } catch (e: Exception) { null }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
