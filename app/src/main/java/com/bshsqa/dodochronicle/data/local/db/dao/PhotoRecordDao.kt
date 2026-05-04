package com.bshsqa.dodochronicle.data.local.db.dao

import androidx.room.*
import com.bshsqa.dodochronicle.data.local.db.entity.PhotoRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PhotoRecordDao {
    @Query("SELECT * FROM photo_records WHERE eventId = :eventId")
    fun observeForEvent(eventId: String): Flow<List<PhotoRecordEntity>>

    @Query("""
        SELECT photo_records.* FROM photo_records
        INNER JOIN events ON events.id = photo_records.eventId
        WHERE events.childId = :childId
    """)
    fun observeForChild(childId: String): Flow<List<PhotoRecordEntity>>

    @Query("SELECT localUri FROM photo_records")
    suspend fun getAllUris(): List<String>

    @Query("SELECT * FROM photo_records")
    suspend fun getAll(): List<PhotoRecordEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: PhotoRecordEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(records: List<PhotoRecordEntity>)

    @Query("DELETE FROM photo_records WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT MAX(takenAt) FROM photo_records")
    suspend fun getLatestTakenAt(): Long?

    @Query("UPDATE photo_records SET isExcludedFromModel = :excluded WHERE id = :id")
    suspend fun setExcludedFromModel(id: String, excluded: Boolean)

    @Query("""
        UPDATE photo_records
        SET isMissing = :isMissing,
            lastSeenAt = :lastSeenAt,
            missingCheckedAt = :checkedAt
        WHERE id = :id
    """)
    suspend fun updateMissingState(
        id: String,
        isMissing: Boolean,
        lastSeenAt: Long,
        checkedAt: Long
    )

    @Query("""
        SELECT photo_records.faceEmbeddingJson FROM photo_records
        INNER JOIN events ON events.id = photo_records.eventId
        WHERE events.childId = :childId
        AND photo_records.isExcludedFromModel = 0
        AND photo_records.isMissing = 0
        ORDER BY takenAt DESC
        LIMIT 50
    """)
    suspend fun getLatest50Embeddings(childId: String): List<String>

    @Query("DELETE FROM photo_records")
    suspend fun deleteAll()
}
