package com.bshsqa.dodochronicle.data.repository

import com.bshsqa.dodochronicle.data.local.db.dao.EventDao
import com.bshsqa.dodochronicle.data.local.db.dao.PhotoRecordDao
import com.bshsqa.dodochronicle.data.local.db.entity.EventEntity
import com.bshsqa.dodochronicle.data.local.db.entity.PhotoRecordEntity
import com.bshsqa.dodochronicle.domain.model.Event
import com.bshsqa.dodochronicle.domain.model.EventCategory
import com.bshsqa.dodochronicle.domain.model.EventSource
import com.bshsqa.dodochronicle.domain.model.PhotoRecord
import com.bshsqa.dodochronicle.domain.repository.EventRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EventRepositoryImpl @Inject constructor(
    private val eventDao: EventDao,
    private val photoDao: PhotoRecordDao
) : EventRepository {

    override fun observe(childId: String, category: EventCategory?, onlyFavorite: Boolean): Flow<List<Event>> =
        eventDao.observe(childId, category?.name, onlyFavorite).map { list -> list.map { it.toDomain() } }

    override suspend fun getById(id: String): Event? = eventDao.getById(id)?.toDomain()
    override suspend fun insert(event: Event) = eventDao.insert(event.toEntity())
    override suspend fun insertAll(events: List<Event>) = eventDao.insertAll(events.map { it.toEntity() })
    override suspend fun setFavorite(id: String, isFavorite: Boolean) = eventDao.setFavorite(id, isFavorite)
    override suspend fun delete(id: String) = eventDao.deleteById(id)
    override suspend fun deleteAllForChild(childId: String) = eventDao.deleteAllForChild(childId)

    override suspend fun insertPhotoRecord(record: PhotoRecord) = photoDao.insert(record.toEntity())
    override suspend fun insertAllPhotoRecords(records: List<PhotoRecord>) =
        photoDao.insertAll(records.map { it.toEntity() })
    override suspend fun getLatestPhotoTakenAt(): Long? = photoDao.getLatestTakenAt()
    override suspend fun getAllPhotoUris(): List<String> = photoDao.getAllUris()
    override suspend fun deletePhotoRecord(id: String) = photoDao.deleteById(id)
    override suspend fun deletePhotoRecordsBatch(ids: List<String>) {
        ids.forEach { photoDao.deleteById(it) }
    }
    override suspend fun deleteAllPhotoRecords() = photoDao.deleteAll()
    override suspend fun setPhotoExcludedFromModel(photoRecordId: String, excluded: Boolean) =
        photoDao.setExcludedFromModel(photoRecordId, excluded)
    override suspend fun getLatest50Embeddings(): List<FloatArray> =
        photoDao.getLatest50Embeddings().map { json ->
            try { Json.decodeFromString<List<Float>>(json).toFloatArray() } catch (e: Exception) { floatArrayOf() }
        }.filter { it.isNotEmpty() }

    override fun observePhotosForEvent(eventId: String): Flow<List<PhotoRecord>> =
        photoDao.observeForEvent(eventId).map { list -> list.map { it.toDomain() } }

    private fun EventEntity.toDomain() = Event(
        id = id, childId = childId,
        date = LocalDate.ofEpochDay(date),
        category = EventCategory.valueOf(category),
        content = content, isFavorite = isFavorite,
        source = EventSource.valueOf(source),
        createdAt = createdAt
    )

    private fun Event.toEntity() = EventEntity(
        id = id, childId = childId,
        date = date.toEpochDay(),
        category = category.name,
        content = content, isFavorite = isFavorite,
        source = source.name
    )

    private fun PhotoRecordEntity.toDomain() = PhotoRecord(
        id = id, eventId = eventId, localUri = localUri, takenAt = takenAt,
        faceEmbedding = try { Json.decodeFromString<List<Float>>(faceEmbeddingJson).toFloatArray() } catch (e: Exception) { floatArrayOf() },
        similarityScore = similarityScore,
        isExcludedFromModel = isExcludedFromModel
    )

    private fun PhotoRecord.toEntity() = PhotoRecordEntity(
        id = id, eventId = eventId, localUri = localUri, takenAt = takenAt,
        faceEmbeddingJson = Json.encodeToString(faceEmbedding.toList()),
        similarityScore = similarityScore,
        isExcludedFromModel = isExcludedFromModel
    )
}
