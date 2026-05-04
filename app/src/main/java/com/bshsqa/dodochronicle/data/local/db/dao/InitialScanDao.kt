package com.bshsqa.dodochronicle.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bshsqa.dodochronicle.data.local.db.entity.InitialScanPhotoEmbeddingEntity
import com.bshsqa.dodochronicle.data.local.db.entity.InitialScanSessionEntity

@Dao
interface InitialScanDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: InitialScanSessionEntity)

    @Query("UPDATE initial_scan_sessions SET status = :status, totalCount = :totalCount, processedCount = :processedCount, elapsedSeconds = :elapsedSeconds WHERE id = :sessionId")
    suspend fun updateProgress(
        sessionId: String,
        status: String,
        totalCount: Int,
        processedCount: Int,
        elapsedSeconds: Long
    )

    @Query("UPDATE initial_scan_sessions SET status = 'COMPLETED', completedAt = :completedAt, totalCount = :totalCount, processedCount = :processedCount, elapsedSeconds = :elapsedSeconds WHERE id = :sessionId")
    suspend fun markCompleted(
        sessionId: String,
        completedAt: Long,
        totalCount: Int,
        processedCount: Int,
        elapsedSeconds: Long
    )

    @Query("SELECT * FROM initial_scan_sessions WHERE status = 'COMPLETED' ORDER BY completedAt DESC LIMIT 1")
    suspend fun getLatestCompletedSession(): InitialScanSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEmbeddings(items: List<InitialScanPhotoEmbeddingEntity>)

    @Query("SELECT * FROM initial_scan_photo_embeddings WHERE sessionId = :sessionId")
    suspend fun getEmbeddings(sessionId: String): List<InitialScanPhotoEmbeddingEntity>

    @Query("DELETE FROM initial_scan_photo_embeddings WHERE sessionId = :sessionId")
    suspend fun deleteEmbeddings(sessionId: String)

    @Query("DELETE FROM initial_scan_sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: String)

    @Query("DELETE FROM initial_scan_photo_embeddings")
    suspend fun deleteAllEmbeddings()

    @Query("DELETE FROM initial_scan_sessions")
    suspend fun deleteAllSessions()
}
