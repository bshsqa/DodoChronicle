package com.bshsqa.dodochronicle.domain.model

data class GeminiSettings(
    val apiKey: String = "",
    val modelId: String = ""
) {
    val isConfigured: Boolean get() = apiKey.isNotBlank() && modelId.isNotBlank()
}

data class GeminiModelOption(
    val id: String,
    val label: String,
    val description: String = ""
)
