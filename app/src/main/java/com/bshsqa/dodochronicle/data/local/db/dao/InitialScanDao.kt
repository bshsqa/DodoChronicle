package com.bshsqa.dodochronicle.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.bshsqa.dodochronicle.data.local.db.entity.InitialScanClusterEntity
import com.bshsqa.dodochronicle.data.local.db.entity.InitialScanItemEntity
import com.bshsqa.dodochronicle.data.local.db.entity.InitialScanPhotoEmbeddingEntity
import com.bshsqa.dodochronicle.data.local.db.entity.InitialScanSessionEntity

@Dao
interface InitialScanDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: InitialScanSessionEntity)

    @Query("UPDATE initial_scan_sessions SET status = :status, totalCount = :totalCount, processedCount = :processedCount, elapsedSeconds = :elapsedSeconds, lastCheckpointAt = :checkpointAt WHERE id = :sessionId")
    suspend fun updateProgress(
        sessionId: String,
        status: String,
        totalCount: Int,
        processedCount: Int,
        elapsedSeconds: Long,
        checkpointAt: Long = System.currentTimeMillis()
    )

    @Query("UPDATE initial_scan_sessions SET status = 'COMPLETED', completedAt = :completedAt, totalCount = :totalCount, processedCount = :processedCount, elapsedSeconds = :elapsedSeconds, lastCheckpointAt = :completedAt WHERE id = :sessionId")
    suspend fun markCompleted(
        sessionId: String,
        completedAt: Long,
        totalCount: Int,
        processedCount: Int,
        elapsedSeconds: Long
    )

    @Query("SELECT * FROM initial_scan_sessions WHERE status = 'COMPLETED' ORDER BY completedAt DESC LIMIT 1")
    suspend fun getLatestCompletedSession(): InitialScanSessionEntity?

    @Query("SELECT * FROM initial_scan_sessions WHERE id = :sessionId LIMIT 1")
    suspend fun getSession(sessionId: String): InitialScanSessionEntity?

    @Query("SELECT * FROM initial_scan_sessions WHERE status IN ('RUNNING', 'PAUSED', 'COMPLETED') ORDER BY startedAt DESC LIMIT 1")
    suspend fun getResumableSession(): InitialScanSessionEntity?

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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<InitialScanItemEntity>)

    @Update
    suspend fun updateItems(items: List<InitialScanItemEntity>)

    @Query("SELECT COUNT(*) FROM initial_scan_items WHERE sessionId = :sessionId")
    suspend fun countItems(sessionId: String): Int

    @Query("SELECT COUNT(*) FROM initial_scan_items WHERE sessionId = :sessionId AND status IN ('PROCESSED', 'NO_FACE', 'FAILED')")
    suspend fun countFinishedItems(sessionId: String): Int

    @Query("SELECT * FROM initial_scan_items WHERE sessionId = :sessionId AND status IN ('PENDING', 'PROCESSING') ORDER BY takenAt DESC LIMIT :limit")
    suspend fun getNextPendingItems(sessionId: String, limit: Int): List<InitialScanItemEntity>

    @Query("SELECT * FROM initial_scan_items WHERE sessionId = :sessionId AND status = 'PROCESSED' AND clusterId IS NOT NULL")
    suspend fun getProcessedItems(sessionId: String): List<InitialScanItemEntity>

    @Query("UPDATE initial_scan_items SET status = 'PENDING' WHERE sessionId = :sessionId AND status = 'PROCESSING'")
    suspend fun resetProcessingItems(sessionId: String)

    @Query("DELETE FROM initial_scan_items WHERE sessionId = :sessionId")
    suspend fun deleteItems(sessionId: String)

    @Query("DELETE FROM initial_scan_items")
    suspend fun deleteAllItems()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertClusters(clusters: List<InitialScanClusterEntity>)

    @Query("SELECT * FROM initial_scan_clusters WHERE sessionId = :sessionId")
    suspend fun getClusters(sessionId: String): List<InitialScanClusterEntity>

    @Query("DELETE FROM initial_scan_clusters WHERE sessionId = :sessionId")
    suspend fun deleteClusters(sessionId: String)

    @Query("DELETE FROM initial_scan_clusters")
    suspend fun deleteAllClusters()

    @Transaction
    suspend fun saveCheckpoint(
        sessionId: String,
        totalCount: Int,
        processedCount: Int,
        elapsedSeconds: Long,
        items: List<InitialScanItemEntity>,
        clusters: List<InitialScanClusterEntity>
    ) {
        updateItems(items)
        if (clusters.isNotEmpty()) upsertClusters(clusters)
        updateProgress(sessionId, "RUNNING", totalCount, processedCount, elapsedSeconds)
    }
}
