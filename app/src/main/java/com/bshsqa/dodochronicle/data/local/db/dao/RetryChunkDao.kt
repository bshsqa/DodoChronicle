package com.bshsqa.dodochronicle.data.local.db.dao

import androidx.room.*
import com.bshsqa.dodochronicle.data.local.db.entity.RetryChunkEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RetryChunkDao {

    @Query("SELECT * FROM retry_chunks ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<RetryChunkEntity>>

    @Query("SELECT COUNT(*) FROM retry_chunks")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM retry_chunks WHERE roomId = :roomId ORDER BY sentAtStart ASC")
    suspend fun getByRoom(roomId: String): List<RetryChunkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chunks: List<RetryChunkEntity>)

    @Query("DELETE FROM retry_chunks WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM retry_chunks")
    suspend fun deleteAll()
}
