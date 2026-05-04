package com.bshsqa.dodochronicle.domain.repository

import com.bshsqa.dodochronicle.domain.model.GeminiModelOption
import com.bshsqa.dodochronicle.domain.model.GeminiSettings
import kotlinx.coroutines.flow.Flow

interface GeminiSettingsRepository {
    val settingsFlow: Flow<GeminiSettings>
    suspend fun getSettings(): GeminiSettings
    suspend fun save(apiKey: String, modelId: String)
    suspend fun clear()
    suspend fun getCachedModels(): List<GeminiModelOption>
    suspend fun fetchModels(apiKey: String): List<GeminiModelOption>
}
