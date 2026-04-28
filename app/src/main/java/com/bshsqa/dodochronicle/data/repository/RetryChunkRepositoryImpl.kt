package com.bshsqa.dodochronicle.data.repository

import com.bshsqa.dodochronicle.data.local.db.dao.RetryChunkDao
import com.bshsqa.dodochronicle.data.local.db.entity.RetryChunkEntity
import com.bshsqa.dodochronicle.domain.model.RetryChunk
import com.bshsqa.dodochronicle.domain.repository.RetryChunkRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RetryChunkRepositoryImpl @Inject constructor(
    private val dao: RetryChunkDao
) : RetryChunkRepository {

    override fun observeAll(): Flow<List<RetryChunk>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeCount(): Flow<Int> = dao.observeCount()

    override suspend fun getByRoom(roomId: String): List<RetryChunk> =
        dao.getByRoom(roomId).map { it.toDomain() }

    override suspend fun save(chunks: List<RetryChunk>) =
        dao.insertAll(chunks.map { it.toEntity() })

    override suspend fun delete(ids: List<String>) = dao.deleteByIds(ids)

    override suspend fun deleteAll() = dao.deleteAll()
}

private fun RetryChunkEntity.toDomain() =
    RetryChunk(id, roomId, roomAlias, sentAtStart, sentAtEnd, dateRange)

private fun RetryChunk.toEntity() =
    RetryChunkEntity(id, roomId, roomAlias, sentAtStart, sentAtEnd, dateRange)
