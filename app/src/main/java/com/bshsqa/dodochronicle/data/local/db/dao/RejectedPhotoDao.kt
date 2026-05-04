package com.bshsqa.dodochronicle.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bshsqa.dodochronicle.data.local.db.entity.RejectedPhotoEntity

@Dao
interface RejectedPhotoDao {
    @Query("SELECT uri FROM rejected_photos WHERE childId = :childId")
    suspend fun getUrisForChild(childId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(photos: List<RejectedPhotoEntity>)

    @Query("DELETE FROM rejected_photos WHERE childId = :childId AND addedAtSeconds < :minAddedAtSeconds")
    suspend fun deleteOlderThan(childId: String, minAddedAtSeconds: Long)

    @Query("DELETE FROM rejected_photos WHERE childId = :childId")
    suspend fun deleteAllForChild(childId: String)

    @Query("DELETE FROM rejected_photos")
    suspend fun deleteAll()
}
