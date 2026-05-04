package com.bshsqa.dodochronicle.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bshsqa.dodochronicle.data.local.db.entity.PendingPhotoEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingPhotoDao {
    @Query("SELECT * FROM pending_photos WHERE childId = :childId ORDER BY createdAt ASC")
    fun observeForChild(childId: String): Flow<List<PendingPhotoEntity>>

    @Query("SELECT uri FROM pending_photos")
    suspend fun getAllUris(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(photos: List<PendingPhotoEntity>)

    @Query("DELETE FROM pending_photos WHERE uri IN (:uris)")
    suspend fun deleteByUris(uris: List<String>)

    @Query("DELETE FROM pending_photos WHERE childId = :childId")
    suspend fun deleteAllForChild(childId: String)
}
