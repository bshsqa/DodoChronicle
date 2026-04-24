package com.bshsqa.dodochronicle.data.local.db.dao

import androidx.room.*
import com.bshsqa.dodochronicle.data.local.db.entity.KakaoRoomEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface KakaoRoomDao {
    @Query("SELECT * FROM kakao_rooms ORDER BY roomName ASC")
    fun observeAll(): Flow<List<KakaoRoomEntity>>

    @Query("SELECT * FROM kakao_rooms WHERE roomName = :name LIMIT 1")
    suspend fun getByName(name: String): KakaoRoomEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(room: KakaoRoomEntity)

    @Query("UPDATE kakao_rooms SET lastImportedAt = :timestamp WHERE id = :id")
    suspend fun updateLastImported(id: String, timestamp: Long)
}
