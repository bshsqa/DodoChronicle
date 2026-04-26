package com.bshsqa.dodochronicle.domain.usecase

import com.bshsqa.dodochronicle.domain.model.Event
import com.bshsqa.dodochronicle.domain.repository.EventRepository
import javax.inject.Inject

class ManageEventUseCase @Inject constructor(
    private val eventRepository: EventRepository
) {
    suspend fun addManual(event: Event) = eventRepository.insert(event)
    suspend fun delete(id: String) = eventRepository.delete(id)
    suspend fun setFavorite(id: String, isFavorite: Boolean) = eventRepository.setFavorite(id, isFavorite)
    suspend fun removePhoto(photoRecordId: String) = eventRepository.deletePhotoRecord(photoRecordId)
}
