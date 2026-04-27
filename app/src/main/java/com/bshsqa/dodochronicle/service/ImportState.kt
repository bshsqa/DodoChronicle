package com.bshsqa.dodochronicle.service

sealed class ImportState {
    object Idle : ImportState()
    data class Running(val chunksDone: Int, val totalChunks: Int, val dateRange: String) : ImportState()
    data class Done(
        val addedMessages: Int,
        val addedEvents: Int,
        val apiRequests: Int,
        val totalTokens: Int,
        val failedChunks: Int,
        val elapsedSeconds: Long,
        val cancelled: Boolean
    ) : ImportState()
    data class Error(val message: String) : ImportState()
}
