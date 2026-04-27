package com.bshsqa.dodochronicle.domain.repository

import com.bshsqa.dodochronicle.domain.model.KakaoMessage
import com.bshsqa.dodochronicle.domain.model.KakaoRoom
import kotlinx.coroutines.flow.Flow

interface KakaoRepository {
    fun observeRooms(): Flow<List<KakaoRoom>>
    suspend fun getAllRooms(): List<KakaoRoom>
    suspend fun getRoomByName(name: String): KakaoRoom?
    suspend fun upsertRoom(room: KakaoRoom)
    suspend fun updateLastImported(roomId: String, timestamp: Long)
    suspend fun messageExistsByHashInRoom(roomId: String, hash: String): Boolean
    suspend fun insertMessages(messages: List<KakaoMessage>)
    suspend fun getLatestMessageSentAt(roomId: String): Long?
    suspend fun deleteAll()
}
