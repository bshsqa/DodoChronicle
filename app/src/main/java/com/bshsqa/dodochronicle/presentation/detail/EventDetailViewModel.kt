package com.bshsqa.dodochronicle.presentation.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bshsqa.dodochronicle.domain.model.Event
import com.bshsqa.dodochronicle.domain.model.PhotoRecord
import com.bshsqa.dodochronicle.domain.repository.EventRepository
import com.bshsqa.dodochronicle.domain.usecase.ManageEventUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EventDetailUiState(
    val event: Event? = null,
    val photos: List<PhotoRecord> = emptyList(),
    val isDeleted: Boolean = false
)

@HiltViewModel
class EventDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val eventRepository: EventRepository,
    private val manageEventUseCase: ManageEventUseCase
) : ViewModel() {

    private val eventId: String = savedStateHandle["eventId"] ?: ""
    private val _state = MutableStateFlow(EventDetailUiState())
    val state: StateFlow<EventDetailUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val event = eventRepository.getById(eventId) ?: return@launch
            _state.update { it.copy(event = event) }
            eventRepository.observePhotosForEvent(eventId).collect { photos ->
                _state.update { it.copy(photos = photos) }
            }
        }
    }

    fun toggleFavorite() {
        val event = _state.value.event ?: return
        viewModelScope.launch {
            manageEventUseCase.setFavorite(event.id, !event.isFavorite)
            _state.update { it.copy(event = event.copy(isFavorite = !event.isFavorite)) }
        }
    }

    fun deleteEvent() {
        viewModelScope.launch {
            manageEventUseCase.delete(eventId)
            _state.update { it.copy(isDeleted = true) }
        }
    }

    fun removePhoto(photoId: String) {
        viewModelScope.launch { manageEventUseCase.removePhoto(photoId) }
    }
}
