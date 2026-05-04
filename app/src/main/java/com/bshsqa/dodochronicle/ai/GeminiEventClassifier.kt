package com.bshsqa.dodochronicle.ai

import com.bshsqa.dodochronicle.domain.model.Event
import com.bshsqa.dodochronicle.domain.model.EventCategory
import com.bshsqa.dodochronicle.domain.model.EventSearchContext
import com.bshsqa.dodochronicle.domain.model.Gender
import com.bshsqa.dodochronicle.domain.model.KakaoMessage
import com.bshsqa.dodochronicle.domain.model.SEARCH_CONTEXT_INDEX_VERSION
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

data class ExtractedEvent(
    val date: LocalDate,
    val category: EventCategory,
    val content: String,
    val longContent: String? = null,
    val rawExcerpt: String? = null,
    val searchSummary: String = "",
    val searchTags: List<String> = emptyList(),
    val searchAliases: List<String> = emptyList(),
    val relatedKeywords: List<String> = emptyList()
)

data class ExtractionStats(
    val requestCount: Int,
    val totalTokens: Int,
    val failedChunks: Int = 0,
    val apiKeyMissing: Boolean = false
)

data class ExtractionResult(val events: List<ExtractedEvent>, val stats: ExtractionStats)

data class ChunkExtractionResult(
    val events: List<ExtractedEvent>,
    val totalTokens: Int,
    val requestCount: Int
)

data class SearchContextBatchResult(
    val contextsByEventId: Map<String, EventSearchContext>,
    val tokens: Int
)

data class ChunkProgress(
    val chunkIndex: Int,
    val totalChunks: Int,
    val dateRange: String
)

private data class EventParseResult(
    val events: List<ExtractedEvent>,
    val brokenEvents: List<BrokenEvent>,
    val totalTokens: Int,
    val responseText: String,
    val wholeJsonFailed: Boolean
)

private data class BrokenEvent(
    val rawJson: String,
    val reason: String
)

@Singleton
class GeminiEventClassifier @Inject constructor(
    private val httpClient: OkHttpClient,
    @Named("gemini_api_key") private val apiKey: String,
    @Named("gemini_model") private val model: String
) {
    private val gson = Gson()
    private val json = "application/json".toMediaType()

    val hasApiKey: Boolean get() = apiKey.isNotBlank()

    suspend fun extractEvents(
        messages: List<KakaoMessage>,
        childName: String,
        birthDate: LocalDate,
        gender: Gender,
        chunkSize: Int = 400,
        onProgress: ((ChunkProgress) -> Unit)? = null
    ): ExtractionResult = withContext(Dispatchers.IO) {
        if (messages.isEmpty()) return@withContext ExtractionResult(emptyList(), ExtractionStats(0, 0))
        if (apiKey.isBlank()) return@withContext ExtractionResult(emptyList(), ExtractionStats(0, 0, apiKeyMissing = true))

        val chunks = buildChunks(messages, chunkSize)
        val allEvents = mutableListOf<ExtractedEvent>()
        var totalRequests = 0
        var totalTokens = 0
        var failedChunks = 0

        chunks.forEachIndexed { index, chunk ->
            if (onProgress != null) {
                val dates = chunk.map { Instant.ofEpochMilli(it.sentAt).atZone(ZoneId.of("Asia/Seoul")).toLocalDate() }
                onProgress(ChunkProgress(index, chunks.size, "${dates.first()} ~ ${dates.last()}"))
            }
            if (index > 0) delay(12000)
            try {
                val result = processChunk(chunk, childName, birthDate, gender)
                allEvents += result.events
                totalTokens += result.totalTokens
                totalRequests += result.requestCount
                if (result.requestCount == 0 && result.events.isEmpty()) failedChunks++
            } catch (e: Exception) {
                Log.e("Gemini", "Chunk failed: ${e.message}", e)
                failedChunks++
            }
        }

        ExtractionResult(allEvents, ExtractionStats(totalRequests, totalTokens, failedChunks))
    }

    internal fun buildChunks(messages: List<KakaoMessage>, chunkSize: Int = 400): List<List<KakaoMessage>> {
        val byDate = messages
            .groupBy { Instant.ofEpochMilli(it.sentAt).atZone(ZoneId.of("Asia/Seoul")).toLocalDate() }
            .toSortedMap()

        val chunks = mutableListOf<List<KakaoMessage>>()
        var currentChunk = mutableListOf<KakaoMessage>()

        for ((_, dayMsgs) in byDate) {
            if (currentChunk.isNotEmpty() && currentChunk.size + dayMsgs.size > chunkSize) {
                chunks.add(currentChunk.toList())
                currentChunk = mutableListOf()
            }
            currentChunk.addAll(dayMsgs)
        }
        if (currentChunk.isNotEmpty()) chunks.add(currentChunk.toList())
        return chunks
    }

    internal suspend fun processChunk(
        chunk: List<KakaoMessage>,
        childName: String,
        birthDate: LocalDate,
        gender: Gender
    ): ChunkExtractionResult = withContext(Dispatchers.IO) {
        val chunkStartDate = Instant.ofEpochMilli(chunk.first().sentAt)
            .atZone(ZoneId.of("Asia/Seoul")).toLocalDate()
        val chunkEndDate = Instant.ofEpochMilli(chunk.last().sentAt)
            .atZone(ZoneId.of("Asia/Seoul")).toLocalDate()

        val msgText = chunk.joinToString("\n") { msg ->
            val date = Instant.ofEpochMilli(msg.sentAt)
                .atZone(ZoneId.of("Asia/Seoul"))
                .toLocalDate()
                .format(DateTimeFormatter.ISO_LOCAL_DATE)
            "[$date] ${msg.sender}: ${msg.content}"
        }

        val prompt = buildPrompt(msgText, childName, birthDate, gender, chunkStartDate, chunkEndDate)
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"

        val raw = executeGeminiRequest(url, prompt)
            ?: return@withContext ChunkExtractionResult(emptyList(), 0, 0)
        Log.d("Gemini", "Response: $raw")
        val parsed = parseResponse(raw)
        var repairTokens = 0
        var repairRequests = 0
        val repairedEvents = if (parsed.wholeJsonFailed || parsed.brokenEvents.isNotEmpty()) {
            val repairPrompt = buildRepairPrompt(
                originalMessages = msgText,
                previousResponse = parsed.responseText.ifBlank { raw },
                brokenEvents = parsed.brokenEvents,
                wholeJsonFailed = parsed.wholeJsonFailed
            )
            delay(1_000L)
            val repairRaw = executeGeminiRequest(url, repairPrompt)
            if (repairRaw != null) {
                repairRequests = 1
                val repaired = parseResponse(repairRaw)
                repairTokens = repaired.totalTokens
                if (!repaired.wholeJsonFailed) repaired.events else emptyList()
            } else {
                emptyList()
            }
        } else {
            emptyList()
        }
        val events = parsed.events + repairedEvents
        val totalTokens = parsed.totalTokens + repairTokens
        val requestCount = 1 + repairRequests
        Log.d("Gemini", "Parsed ${events.size} events, $totalTokens tokens, $requestCount requests")
        ChunkExtractionResult(events, totalTokens, requestCount)
    }

    suspend fun generateSearchContexts(events: List<Event>): SearchContextBatchResult = withContext(Dispatchers.IO) {
        if (events.isEmpty() || apiKey.isBlank()) {
            return@withContext SearchContextBatchResult(emptyMap(), 0)
        }

        val prompt = buildSearchContextPrompt(events)
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        val body = gson.toJson(
            mapOf(
                "contents" to listOf(mapOf("parts" to listOf(mapOf("text" to prompt)))),
                "generationConfig" to mapOf(
                    "responseMimeType" to "application/json",
                    "temperature" to 0.2,
                    "maxOutputTokens" to 8192
                )
            )
        ).toRequestBody(json)

        val request = Request.Builder().url(url).post(body).build()
        httpClient.newCall(request).execute().use { response ->
            val raw = response.body?.string()
            if (!response.isSuccessful || raw == null) {
                Log.e("Gemini", "Search context HTTP ${response.code}: $raw")
                return@use SearchContextBatchResult(emptyMap(), 0)
            }
            parseSearchContextResponse(raw)
        }
    }

    private fun executeGeminiRequest(url: String, prompt: String): String? {
        val body = gson.toJson(
            mapOf(
                "contents" to listOf(mapOf("parts" to listOf(mapOf("text" to prompt)))),
                "generationConfig" to mapOf(
                    "responseMimeType" to "application/json",
                    "temperature" to 0.2,
                    "maxOutputTokens" to 8192
                )
            )
        ).toRequestBody(json)

        val request = Request.Builder().url(url).post(body).build()
        return httpClient.newCall(request).execute().use { response ->
            val raw = response.body?.string()
            if (!response.isSuccessful || raw == null) {
                Log.e("Gemini", "HTTP ${response.code}: $raw")
                null
            } else {
                raw
            }
        }
    }

    private fun calculateAge(birthDate: LocalDate, refDate: LocalDate): String {
        val months = ChronoUnit.MONTHS.between(birthDate, refDate).coerceAtLeast(0)
        return if (months < 24) "${months}개월" else "${months / 12}세 ${months % 12}개월"
    }

    private fun buildPrompt(
        messages: String,
        childName: String,
        birthDate: LocalDate,
        gender: Gender,
        chunkStartDate: LocalDate,
        chunkEndDate: LocalDate
    ): String {
        val ageStart = calculateAge(birthDate, chunkStartDate)
        val ageEnd = calculateAge(birthDate, chunkEndDate)
        val ageStr = if (ageStart == ageEnd) ageStart else "$ageStart ~ $ageEnd"
        val genderStr = if (gender == Gender.MALE) "남아" else "여아"

        return """
당신은 가족 카카오톡 대화에서 아이의 성장 관련 이벤트를 추출하는 전문가입니다.

아이 이름: $childName
생년월일: $birthDate
이 대화 시점 나이: $ageStr ($genderStr)

다음 규칙을 따르세요:
- $childName 이(가) 직접 한 말, 행동, 성취, 신체 변화를 이벤트로 추출합니다
- $childName 을(를) 지칭하는 다양한 표현도 아이로 인식하세요.
  예: "우리애기", "애기", "아가", "왕자님", "공주님", "우리 아이", "얘", "쟤", "우리 아들", "우리 딸"
  이름을 직접 언급하지 않더라도 대화 흐름, 발신자, 문맥을 통해 아이를 가리키는지 판단하세요.
- 카테고리: SAID(아이가 한 말), DID(아이가 한 행동/성취), OTHER(기타 아이 관련)
- 아이와 무관한 대화는 무시합니다
- date는 해당 메시지의 날짜를 사용합니다 (YYYY-MM-DD)
- content: 아이 관점에서 한 줄 요약 (예: "처음으로 뒤집기 성공")
- longContent: 더 자세한 설명 (2~3문장, 맥락 포함)
- rawExcerpt: 해당 이벤트와 직접 관련된 원본 대화 5~10줄 발췌. 이벤트와 무관한 대화는 포함하지 않음.

대화 내용:
$messages

JSON 배열로만 응답하세요 (다른 텍스트 없이):
[{"date":"YYYY-MM-DD","category":"SAID|DID|OTHER","content":"한 줄 요약","longContent":"상세 설명","rawExcerpt":"원본 대화 발췌","searchSummary":"80자 이내 검색 요약","searchTags":["짧은 태그"],"searchAliases":["사용자가 검색할 표현"],"relatedKeywords":["관련 키워드"]}]

검색 보조 데이터 규칙:
- searchSummary는 80자 이내 1문장
- searchTags 최대 10개, searchAliases 최대 5개, relatedKeywords 최대 12개
- 원문에서 합리적으로 추론 가능한 표현만 포함
- 새로운 사실, 장소, 인물, 진단명은 만들지 않음
- 근거가 약하면 빈 배열 사용
- 아이, 일상, 기록, 오늘 같은 일반 단어는 제외

이벤트가 없으면 []
""".trimIndent()
    }

    private fun buildSearchContextPrompt(events: List<Event>): String {
        val eventText = events.joinToString("\n") { event ->
            """
            {
              "eventId": "${event.id}",
              "date": "${event.date}",
              "category": "${event.category}",
              "content": ${gson.toJson(event.content)},
              "longContent": ${gson.toJson(event.longContent ?: "")},
              "rawExcerpt": ${gson.toJson(event.rawExcerpt ?: "")}
            }
            """.trimIndent()
        }
        return """
Create search context metadata for each event.

Return JSON only:
{"items":[{"eventId":"...","searchSummary":"...","searchTags":[],"searchAliases":[],"relatedKeywords":[]}]}

Rules:
- Generate only expressions reasonably inferred from the event text.
- Do not invent new facts, places, people, or diagnosis names.
- searchSummary: one Korean sentence, max 80 chars.
- searchTags: max 10 short Korean nouns/phrases.
- searchAliases: max 5 natural Korean search phrases.
- relatedKeywords: max 12 short Korean keywords.
- Empty arrays are allowed when evidence is weak.
- Avoid generic words like 아이, 일상, 기록, 오늘.
- Do not repeat equivalent expressions.

Events:
$eventText
""".trimIndent()
    }

    private fun buildRepairPrompt(
        originalMessages: String,
        previousResponse: String,
        brokenEvents: List<BrokenEvent>,
        wholeJsonFailed: Boolean
    ): String {
        val brokenText = if (wholeJsonFailed) {
            "The previous response could not be parsed as the required JSON array."
        } else {
            brokenEvents.joinToString("\n") { broken ->
                "- reason=${broken.reason}, raw=${broken.rawJson}"
            }
        }
        return """
Repair the previous Gemini event extraction response.

Return JSON array only. Do not add explanations.
Use this exact event schema:
[{"date":"YYYY-MM-DD","category":"SAID|DID|OTHER","content":"...","longContent":"...","rawExcerpt":"...","searchSummary":"...","searchTags":[],"searchAliases":[],"relatedKeywords":[]}]

Rules:
- Do not create unrelated new events.
- Preserve the intent of the previous response when possible.
- Use the original KakaoTalk messages only to fix invalid or missing required fields.
- Drop events that cannot be repaired from the original messages.
- Keep searchSummary within 80 Korean characters.
- searchTags max 10, searchAliases max 5, relatedKeywords max 12.
- If the problem is broken individual events, return only the repaired versions of those broken events.
- If the problem is whole JSON parsing failure, return the full repaired event array.

Problem:
$brokenText

Original KakaoTalk messages:
$originalMessages

Previous response:
$previousResponse
""".trimIndent()
    }

    private data class GeminiResponse(val candidates: List<Candidate>?, val usageMetadata: UsageMetadata?)
    private data class Candidate(val content: Content?)
    private data class Content(val parts: List<Part>?)
    private data class Part(val text: String?)
    private data class UsageMetadata(val totalTokenCount: Int = 0)
    private data class RawSearchContextResponse(
        @SerializedName("items") val items: List<RawSearchContextItem>?
    )
    private data class RawSearchContextItem(
        @SerializedName("eventId") val eventId: String?,
        @SerializedName("searchSummary") val searchSummary: String?,
        @SerializedName("searchTags") val searchTags: List<String>?,
        @SerializedName("searchAliases") val searchAliases: List<String>?,
        @SerializedName("relatedKeywords") val relatedKeywords: List<String>?
    )

    private fun parseResponse(raw: String): EventParseResult {
        return try {
            val geminiRes = gson.fromJson(raw, GeminiResponse::class.java)
            val totalTokens = geminiRes.usageMetadata?.totalTokenCount ?: 0
            val text = geminiRes.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                ?: return EventParseResult(emptyList(), emptyList(), totalTokens, "", true)
            val cleanText = cleanJsonText(text)
            val jsonElement = try {
                JsonParser.parseString(cleanText)
            } catch (_: Exception) {
                return EventParseResult(emptyList(), emptyList(), totalTokens, cleanText, true)
            }
            val eventArray = extractEventArray(jsonElement)
                ?: return EventParseResult(emptyList(), emptyList(), totalTokens, cleanText, true)

            val events = mutableListOf<ExtractedEvent>()
            val brokenEvents = mutableListOf<BrokenEvent>()
            eventArray.forEach { item ->
                val obj = item.asJsonObjectOrNull()
                if (obj == null) {
                    brokenEvents += BrokenEvent(item.toString(), "event is not an object")
                    return@forEach
                }
                val parsedEvent = parseEventObject(obj)
                if (parsedEvent == null) {
                    brokenEvents += BrokenEvent(obj.toString(), "required field is missing or invalid")
                } else {
                    events += parsedEvent
                }
            }
            EventParseResult(events, brokenEvents, totalTokens, cleanText, false)
        } catch (_: Exception) {
            EventParseResult(emptyList(), emptyList(), 0, raw, true)
        }
    }

    private fun parseEventObject(obj: JsonObject): ExtractedEvent? {
        val content = stringValue(obj.get("content"))?.takeIf { it.isNotBlank() } ?: return null
        val dateText = stringValue(obj.get("date")) ?: return null
        val date = try {
            LocalDate.parse(dateText)
        } catch (_: Exception) {
            return null
        }
        val category = when (stringValue(obj.get("category"))) {
            "SAID" -> EventCategory.SAID
            "DID" -> EventCategory.DID
            "OTHER", null -> EventCategory.OTHER
            else -> EventCategory.OTHER
        }
        return ExtractedEvent(
            date = date,
            category = category,
            content = content,
            longContent = stringValue(obj.get("longContent"))?.takeIf { it.isNotBlank() },
            rawExcerpt = stringValue(obj.get("rawExcerpt"))?.takeIf { it.isNotBlank() },
            searchSummary = cleanSummary(stringValue(obj.get("searchSummary")), content),
            searchTags = cleanTerms(obj.get("searchTags"), 10),
            searchAliases = cleanTerms(obj.get("searchAliases"), 5),
            relatedKeywords = cleanTerms(obj.get("relatedKeywords"), 12)
        )
    }

    private fun parseSearchContextResponse(raw: String): SearchContextBatchResult {
        return try {
            val geminiRes = gson.fromJson(raw, GeminiResponse::class.java)
            val totalTokens = geminiRes.usageMetadata?.totalTokenCount ?: 0
            val text = geminiRes.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                ?: return SearchContextBatchResult(emptyMap(), totalTokens)
            val cleanText = cleanJsonText(text)
            val rawItems = gson.fromJson(cleanText, RawSearchContextResponse::class.java).items.orEmpty()
            val contexts = rawItems.mapNotNull { item ->
                val eventId = item.eventId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                eventId to EventSearchContext(
                    searchSummary = cleanSummary(item.searchSummary, ""),
                    searchTags = cleanTerms(item.searchTags, 10),
                    searchAliases = cleanTerms(item.searchAliases, 5),
                    relatedKeywords = cleanTerms(item.relatedKeywords, 12),
                    searchContextVersion = SEARCH_CONTEXT_INDEX_VERSION
                )
            }.toMap()
            SearchContextBatchResult(contexts, totalTokens)
        } catch (_: Exception) {
            SearchContextBatchResult(emptyMap(), 0)
        }
    }

    private fun cleanSummary(value: String?, fallback: String): String {
        val summary = value.orEmpty().trim().take(80)
        return summary.ifBlank { fallback.trim().take(80) }
    }

    private fun cleanTerms(element: JsonElement?, maxCount: Int): List<String> {
        val values = when {
            element == null || element.isJsonNull -> emptyList()
            element.isJsonArray -> element.asJsonArray.mapNotNull { stringValue(it) }
            element.isJsonPrimitive -> stringValue(element)
                .orEmpty()
                .split(",", "，", "/", "|")
                .flatMap { part -> part.split("\\s+".toRegex()) }
            else -> emptyList()
        }
        return cleanTerms(values, maxCount)
    }

    private fun cleanJsonText(text: String): String {
        val trimmed = text.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val firstArray = trimmed.indexOf('[')
        val lastArray = trimmed.lastIndexOf(']')
        if (firstArray >= 0 && lastArray > firstArray) {
            return trimmed.substring(firstArray, lastArray + 1)
        }
        val firstObject = trimmed.indexOf('{')
        val lastObject = trimmed.lastIndexOf('}')
        return if (firstObject >= 0 && lastObject > firstObject) {
            trimmed.substring(firstObject, lastObject + 1)
        } else {
            trimmed
        }
    }

    private fun extractEventArray(element: JsonElement): JsonArray? {
        if (element.isJsonArray) return element.asJsonArray
        val obj = element.asJsonObjectOrNull() ?: return null
        return listOf("events", "items", "data")
            .firstNotNullOfOrNull { key -> obj.get(key)?.takeIf { it.isJsonArray }?.asJsonArray }
    }

    private fun JsonElement.asJsonObjectOrNull(): JsonObject? =
        if (isJsonObject) asJsonObject else null

    private fun stringValue(element: JsonElement?): String? {
        if (element == null || element.isJsonNull || !element.isJsonPrimitive) return null
        val primitive = element.asJsonPrimitive
        return try {
            when {
                primitive.isString -> primitive.asString
                primitive.isNumber -> primitive.asNumber.toString()
                primitive.isBoolean -> primitive.asBoolean.toString()
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun cleanTerms(values: List<String>?, maxCount: Int): List<String> {
        val generic = setOf("아이", "일상", "기록", "오늘")
        return values.orEmpty()
            .map { it.trim() }
            .filter { it.isNotBlank() && it !in generic }
            .map { it.take(24) }
            .distinct()
            .take(maxCount)
    }
}
