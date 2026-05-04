package com.bshsqa.dodochronicle.domain.model

const val SEARCH_CONTEXT_INDEX_VERSION = 1

data class EventSearchContext(
    val searchSummary: String = "",
    val searchTags: List<String> = emptyList(),
    val searchAliases: List<String> = emptyList(),
    val relatedKeywords: List<String> = emptyList(),
    val searchContextVersion: Int = SEARCH_CONTEXT_INDEX_VERSION
) {
    companion object {
        fun fallback(content: String): EventSearchContext = EventSearchContext(
            searchSummary = content.trim().take(80),
            searchContextVersion = SEARCH_CONTEXT_INDEX_VERSION
        )
    }
}

enum class ContextSearchSort {
    DATE,
    RELEVANCE
}
