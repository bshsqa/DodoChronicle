package com.bshsqa.dodochronicle.data.repository

import com.bshsqa.dodochronicle.data.local.db.dao.KakaoMessageDao
import com.bshsqa.dodochronicle.data.local.db.dao.KakaoRoomDao
import com.bshsqa.dodochronicle.data.local.db.entity.KakaoMessageEntity
import com.bshsqa.dodochronicle.data.local.db.entity.KakaoRoomEntity
import com.bshsqa.dodochronicle.domain.model.KakaoMessage
import com.bshsqa.dodochronicle.domain.model.KakaoRoom
import com.bshsqa.dodochronicle.domain.repository.KakaoRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KakaoRepositoryImpl @Inject constructor(
    private val roomDao: KakaoRoomDao,
    private val messageDao: KakaoMessageDao
) : KakaoRepository {

    override fun observeRooms(): Flow<List<KakaoRoom>> =
        roomDao.observeAll().map { list -> list.map { KakaoRoom(it.id, it.roomName, it.lastImportedAt) } }

    override suspend fun getAllRooms(): List<KakaoRoom> =
        roomDao.getAll().map { KakaoRoom(it.id, it.roomName, it.lastImportedAt) }

    override suspend fun getRoomByName(name: String): KakaoRoom? =
        roomDao.getByName(name)?.let { KakaoRoom(it.id, it.roomName, it.lastImportedAt) }

    override suspend fun upsertRoom(room: KakaoRoom) =
        roomDao.upsert(KakaoRoomEntity(room.id, room.roomName, room.lastImportedAt))

    override suspend fun updateLastImported(roomId: String, timestamp: Long) =
        roomDao.updateLastImported(roomId, timestamp)

    override suspend fun messageExistsByHash(hash: String): Boolean =
        messageDao.existsByHash(hash)

    override suspend fun insertMessages(messages: List<KakaoMessage>) =
        messageDao.insertAll(messages.map {
            KakaoMessageEntity(it.id, it.roomId, it.sender, it.sentAt, it.content, it.contentHash)
        })

    override suspend fun getLatestMessageSentAt(roomId: String): Long? =
        messageDao.getLatestSentAt(roomId)

    override suspend fun deleteAll() {
        messageDao.deleteAll()
        roomDao.deleteAll()
    }
}
