package com.bshsqa.dodochronicle.domain.repository

import com.bshsqa.dodochronicle.domain.model.RetryChunk
import kotlinx.coroutines.flow.Flow

interface RetryChunkRepository {
    fun observeAll(): Flow<List<RetryChunk>>
    fun observeCount(): Flow<Int>
    suspend fun getByRoom(roomId: String): List<RetryChunk>
    suspend fun save(chunks: List<RetryChunk>)
    suspend fun delete(ids: List<String>)
    suspend fun deleteAll()
}
