package com.bshsqa.dodochronicle.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImportStateHolder @Inject constructor() {
    private val _state = MutableStateFlow<ImportState>(ImportState.Idle)
    val state: StateFlow<ImportState> = _state.asStateFlow()

    fun emit(state: ImportState) {
        _state.value = state
    }

    fun reset() {
        _state.value = ImportState.Idle
    }
}
