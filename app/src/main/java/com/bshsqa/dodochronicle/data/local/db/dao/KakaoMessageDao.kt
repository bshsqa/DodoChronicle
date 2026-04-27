package com.bshsqa.dodochronicle.data.local.db.dao

import androidx.room.*
import com.bshsqa.dodochronicle.data.local.db.entity.KakaoMessageEntity

@Dao
interface KakaoMessageDao {
    @Query("SELECT * FROM kakao_messages WHERE roomId = :roomId AND sentAt > :after ORDER BY sentAt ASC")
    suspend fun getAfter(roomId: String, after: Long): List<KakaoMessageEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM kakao_messages WHERE contentHash = :hash AND roomId = :roomId)")
    suspend fun existsByHashInRoom(roomId: String, hash: String): Boolean

    @Query("SELECT contentHash FROM kakao_messages WHERE roomId = :roomId")
    suspend fun getAllHashesForRoom(roomId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(messages: List<KakaoMessageEntity>)

    @Query("SELECT MAX(sentAt) FROM kakao_messages WHERE roomId = :roomId")
    suspend fun getLatestSentAt(roomId: String): Long?

    @Query("DELETE FROM kakao_messages")
    suspend fun deleteAll()
}
