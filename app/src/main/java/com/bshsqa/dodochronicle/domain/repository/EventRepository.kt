package com.bshsqa.dodochronicle.domain.repository

import com.bshsqa.dodochronicle.domain.model.Event
import com.bshsqa.dodochronicle.domain.model.EventCategory
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
    suspend fun setFavorite(id: String, isFavorite: Boolean)
    suspend fun delete(id: String)
    suspend fun deleteAllForChild(childId: String)

    suspend fun insertPhotoRecord(record: PhotoRecord)
    suspend fun getLatestPhotoTakenAt(): Long?
    fun observePhotosForEvent(eventId: String): Flow<List<PhotoRecord>>
    suspend fun deletePhotoRecord(id: String)
    suspend fun getAllPhotoUris(): List<String>
    suspend fun deleteAllPhotoRecords()
}
