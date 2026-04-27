package com.bshsqa.dodochronicle.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScanStateHolder @Inject constructor() {
    private val _state = MutableStateFlow<ScanState>(ScanState.Idle)
    val state: StateFlow<ScanState> = _state.asStateFlow()

    fun emit(state: ScanState) {
        _state.value = state
    }

    fun reset() {
        _state.value = ScanState.Idle
    }
}
