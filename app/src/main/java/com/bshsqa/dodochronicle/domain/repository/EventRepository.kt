package com.bshsqa.dodochronicle.domain.repository

import com.bshsqa.dodochronicle.domain.model.Event
import com.bshsqa.dodochronicle.domain.model.EventCategory
import com.bshsqa.dodochronicle.domain.model.EventSearchContext
import com.bshsqa.dodochronicle.domain.model.PendingPhoto
import com.bshsqa.dodochronicle.domain.model.PhotoRecord
import kotlinx.coroutines.flow.Flow

interface EventRepository {
    fun observe(
        childId: String,
        category: EventCategory? = null,
        onlyFavorite: Boolean = false
    ): Flow<List<Event>>

    suspend fun getById(id: String): Event?
    suspend fun insert(event: Event)
    suspend fun insertAll(events: List<Event>)
    suspend fun getAllTextEvents(childId: String): List<Event>
    suspend fun getEventsNeedingSearchContextUpdate(currentVersion: Int): List<Event>
    suspend fun updateSearchContext(eventId: String, context: EventSearchContext)
    suspend fun setFavorite(id: String, isFavorite: Boolean)
    suspend fun setHidden(id: String, isHidden: Boolean)
    fun observeHidden(childId: String): Flow<List<Event>>
    suspend fun delete(id: String)
    suspend fun deleteAllForChild(childId: String)

    suspend fun insertPhotoRecord(record: PhotoRecord)
    suspend fun insertAllPhotoRecords(records: List<PhotoRecord>)
    suspend fun getLatestPhotoTakenAt(): Long?
    fun observePhotosForEvent(eventId: String): Flow<List<PhotoRecord>>
    fun observePhotoRecordsForChild(childId: String): Flow<List<PhotoRecord>>
    suspend fun deletePhotoRecord(id: String)
    suspend fun deletePhotoRecordsBatch(ids: List<String>)
    suspend fun getAllPhotoUris(): List<String>
    suspend fun getAllPhotoRecords(): List<PhotoRecord>
    suspend fun getAllPendingPhotoUris(): List<String>
    fun observePendingPhotosForChild(childId: String): Flow<List<PendingPhoto>>
    suspend fun upsertPendingPhotos(childId: String, photos: List<PendingPhoto>)
    suspend fun deletePendingPhotos(uris: List<String>)
    suspend fun deleteAllPendingPhotosForChild(childId: String)
    suspend fun deleteAllPhotoRecords()
    suspend fun setPhotoExcludedFromModel(photoRecordId: String, excluded: Boolean)
    suspend fun updatePhotoMissingState(
        photoRecordId: String,
        isMissing: Boolean,
        checkedAt: Long,
        lastSeenAt: Long
    )
    suspend fun getLatest50Embeddings(childId: String): List<FloatArray>
}
