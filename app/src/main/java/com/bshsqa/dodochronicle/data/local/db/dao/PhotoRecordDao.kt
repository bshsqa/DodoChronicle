package com.bshsqa.dodochronicle.data.local.db.dao

import androidx.room.*
import com.bshsqa.dodochronicle.data.local.db.entity.PhotoRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PhotoRecordDao {
    @Query("SELECT * FROM photo_records WHERE eventId = :eventId")
    fun observeForEvent(eventId: String): Flow<List<PhotoRecordEntity>>

    @Query("SELECT localUri FROM photo_records")
    suspend fun getAllUris(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: PhotoRecordEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(records: List<PhotoRecordEntity>)

    @Query("DELETE FROM photo_records WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT MAX(takenAt) FROM photo_records")
    suspend fun getLatestTakenAt(): Long?
}
