package com.bshsqa.dodochronicle.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bshsqa.dodochronicle.domain.usecase.ClearAllDataUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(val isLoading: Boolean = false)

sealed interface SettingsEvent {
    object NavigateToInit : SettingsEvent
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val clearAllDataUseCase: ClearAllDataUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<SettingsEvent>()
    val events: SharedFlow<SettingsEvent> = _events.asSharedFlow()

    fun clearAll() {
        viewModelScope.launch {
            _uiState.value = SettingsUiState(isLoading = true)
            clearAllDataUseCase()
            _events.emit(SettingsEvent.NavigateToInit)
        }
    }
}
