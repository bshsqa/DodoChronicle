package com.bshsqa.dodochronicle.presentation.timeline

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bshsqa.dodochronicle.domain.model.Event
import com.bshsqa.dodochronicle.domain.model.EventCategory
import com.bshsqa.dodochronicle.domain.model.EventSource
import com.bshsqa.dodochronicle.domain.repository.ChildRepository
import com.bshsqa.dodochronicle.domain.repository.EventRepository
import com.bshsqa.dodochronicle.domain.usecase.ImportKakaoUseCase
import com.bshsqa.dodochronicle.domain.usecase.ManageEventUseCase
import com.bshsqa.dodochronicle.domain.usecase.SyncNewPhotosUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject

data class TimelineUiState(
    val childName: String = "",
    val birthDate: LocalDate? = null,
    val events: List<Event> = emptyList(),
    val filterCategory: EventCategory? = null,
    val onlyFavorite: Boolean = false,
    val pendingPhotos: List<SyncNewPhotosUseCase.PendingPhoto> = emptyList(),
    val snackbar: String? = null,
    val isLoading: Boolean = false
)

@HiltViewModel
class TimelineViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val childRepository: ChildRepository,
    private val eventRepository: EventRepository,
    private val manageEventUseCase: ManageEventUseCase,
    private val syncUseCase: SyncNewPhotosUseCase,
    private val importKakaoUseCase: ImportKakaoUseCase
) : ViewModel() {

    private val _state = MutableStateFlow(TimelineUiState())
    val state: StateFlow<TimelineUiState> = _state.asStateFlow()

    private val _filterCategory = MutableStateFlow<EventCategory?>(null)
    private val _onlyFavorite = MutableStateFlow(false)
    private var childId: String = ""

    init {
        viewModelScope.launch {
            val child = childRepository.getFirst() ?: return@launch
            childId = child.id
            _state.update { it.copy(childName = child.name, birthDate = child.birthDate) }

            combine(_filterCategory, _onlyFavorite) { cat, fav -> cat to fav }
                .flatMapLatest { (cat, fav) ->
                    eventRepository.observe(childId, cat, fav)
                }
                .collect { events ->
                    _state.update { it.copy(events = events) }
                }
        }
    }

    fun setFilterCategory(category: EventCategory?) {
        _filterCategory.value = category
        _state.update { it.copy(filterCategory = category) }
    }

    fun toggleFavoriteFilter() {
        val new = !_onlyFavorite.value
        _onlyFavorite.value = new
        _state.update { it.copy(onlyFavorite = new) }
    }

    fun toggleFavorite(event: Event) {
        viewModelScope.launch {
            manageEventUseCase.setFavorite(event.id, !event.isFavorite)
        }
    }

    fun deleteEvent(id: String) {
        viewModelScope.launch {
            manageEventUseCase.delete(id)
            _state.update { it.copy(snackbar = "이벤트가 삭제되었습니다") }
        }
    }

    fun addManualEvent(date: LocalDate, category: EventCategory, content: String) {
        viewModelScope.launch {
            manageEventUseCase.addManual(
                Event(
                    id = UUID.randomUUID().toString(),
                    childId = childId,
                    date = date,
                    category = category,
                    content = content,
                    source = EventSource.MANUAL
                )
            )
        }
    }

    fun importKakao(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            val stream = context.contentResolver.openInputStream(uri)
            if (stream == null) {
                _state.update { it.copy(snackbar = "파일을 열 수 없습니다", isLoading = false) }
                return@launch
            }
            when (val r = importKakaoUseCase(stream)) {
                is ImportKakaoUseCase.Result.Success ->
                    _state.update { it.copy(snackbar = "메시지 ${r.addedMessages}개, 이벤트 ${r.addedEvents}개 추가됨") }
                is ImportKakaoUseCase.Result.Error ->
                    _state.update { it.copy(snackbar = r.message) }
            }
            _state.update { it.copy(isLoading = false) }
        }
    }

    fun confirmPendingPhoto(pending: SyncNewPhotosUseCase.PendingPhoto, accept: Boolean) {
        viewModelScope.launch {
            syncUseCase.confirmPhoto(pending, accept, childId)
            _state.update { s ->
                s.copy(pendingPhotos = s.pendingPhotos.filter { it.uri != pending.uri })
            }
        }
    }

    fun dismissSnackbar() = _state.update { it.copy(snackbar = null) }
}
