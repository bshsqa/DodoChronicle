package com.bshsqa.dodochronicle.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.bshsqa.dodochronicle.domain.model.GeminiModelOption
import com.bshsqa.dodochronicle.domain.model.GeminiSettings
import com.bshsqa.dodochronicle.domain.repository.GeminiSettingsRepository
import com.bshsqa.dodochronicle.prefs.AppPrefsKeys
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject

class GeminiSettingsRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val httpClient: OkHttpClient
) : GeminiSettingsRepository {
    private val gson = Gson()

    override val settingsFlow: Flow<GeminiSettings> = dataStore.data.map { prefs ->
        prefs.toSettings()
    }

    override suspend fun getSettings(): GeminiSettings = dataStore.data.first().toSettings()

    override suspend fun save(apiKey: String, modelId: String) {
        val normalizedModelId = normalizeModelId(modelId)
        dataStore.edit { prefs ->
            prefs[AppPrefsKeys.GEMINI_API_KEY] = apiKey.trim()
            prefs[AppPrefsKeys.GEMINI_MODEL_ID] = normalizedModelId
        }
    }

    override suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(AppPrefsKeys.GEMINI_API_KEY)
            prefs.remove(AppPrefsKeys.GEMINI_MODEL_ID)
            prefs.remove(AppPrefsKeys.GEMINI_MODEL_LIST_JSON)
            prefs.remove(AppPrefsKeys.GEMINI_MODEL_LIST_FETCHED_AT)
        }
    }

    override suspend fun getCachedModels(): List<GeminiModelOption> {
        val json = dataStore.data.first()[AppPrefsKeys.GEMINI_MODEL_LIST_JSON].orEmpty()
        return decodeModelOptions(json)
    }

    override suspend fun fetchModels(apiKey: String): List<GeminiModelOption> {
        val cleanKey = apiKey.trim()
        require(cleanKey.isNotBlank()) { "Gemini API 키를 먼저 입력해주세요." }

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models")
            .header("x-goog-api-key", cleanKey)
            .get()
            .build()

        val raw = httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                if (response.code == 400 || response.code == 401 || response.code == 403) {
                    error("Gemini API 키를 확인해주세요.")
                }
                error("모델 목록을 불러오지 못했습니다. 네트워크를 확인해주세요.")
            }
            body
        }

        val response = runCatching { gson.fromJson(raw, GeminiModelsResponse::class.java) }.getOrNull()
            ?: error("모델 목록 응답을 읽지 못했습니다.")

        val options = response.models.orEmpty()
            .filter { it.supportedGenerationMethods.orEmpty().contains("generateContent") }
            .map { model ->
                val id = normalizeModelId(model.name.orEmpty())
                GeminiModelOption(
                    id = id,
                    label = model.displayName?.takeIf { it.isNotBlank() } ?: id.removePrefix("models/"),
                    description = model.description.orEmpty()
                )
            }
            .distinctBy { it.id }

        require(options.isNotEmpty()) { "사용 가능한 Gemini 생성 모델이 없습니다." }

        dataStore.edit { prefs ->
            prefs[AppPrefsKeys.GEMINI_MODEL_LIST_JSON] = gson.toJson(options)
            prefs[AppPrefsKeys.GEMINI_MODEL_LIST_FETCHED_AT] = System.currentTimeMillis()
        }
        return options
    }

    private fun Preferences.toSettings(): GeminiSettings {
        val savedKey = this[AppPrefsKeys.GEMINI_API_KEY].orEmpty()
        val savedModel = this[AppPrefsKeys.GEMINI_MODEL_ID].orEmpty()
        if (savedKey.isNotBlank() && savedModel.isNotBlank()) {
            return GeminiSettings(savedKey, normalizeModelId(savedModel))
        }

        return GeminiSettings()
    }

    private fun decodeModelOptions(json: String): List<GeminiModelOption> =
        runCatching {
            gson.fromJson(json, Array<GeminiModelOption>::class.java).orEmpty().toList()
        }.getOrDefault(emptyList())

    private fun normalizeModelId(modelId: String): String {
        val clean = modelId.trim()
        return if (clean.isBlank() || clean.startsWith("models/")) clean else "models/$clean"
    }

    private data class GeminiModelsResponse(
        @SerializedName("models") val models: List<GeminiModelInfo>?
    )

    private data class GeminiModelInfo(
        @SerializedName("name") val name: String?,
        @SerializedName("displayName") val displayName: String?,
        @SerializedName("description") val description: String?,
        @SerializedName("supportedGenerationMethods") val supportedGenerationMethods: List<String>?
    )
}
