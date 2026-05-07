package com.bshsqa.dodochronicle.presentation.init

import android.content.Context
import android.content.Intent
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bshsqa.dodochronicle.data.local.db.dao.InitialScanDao
import com.bshsqa.dodochronicle.data.local.db.entity.InitialScanSessionEntity
import com.bshsqa.dodochronicle.domain.model.Child
import com.bshsqa.dodochronicle.domain.model.Gender
import com.bshsqa.dodochronicle.domain.repository.ChildRepository
import com.bshsqa.dodochronicle.ml.FaceEmbedder
import com.bshsqa.dodochronicle.prefs.AppPrefsKeys
import com.bshsqa.dodochronicle.service.ScanForegroundService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject

sealed class InitStep {
    object ChildInfo : InitStep()
    object Done : InitStep()
}

data class InitUiState(
    val step: InitStep = InitStep.ChildInfo,
    val childName: String = "",
    val birthDate: LocalDate? = null,
    val gender: Gender? = null,
    val referencePhotoUri: String = "",
    val error: String? = null
)

@HiltViewModel
class InitViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val childRepository: ChildRepository,
    private val faceEmbedder: FaceEmbedder,
    private val initialScanDao: InitialScanDao,
    private val dataStore: DataStore<Preferences>
) : ViewModel() {

    private val _uiState = MutableStateFlow(InitUiState())
    val uiState: StateFlow<InitUiState> = _uiState.asStateFlow()

    fun setChildName(name: String) = _uiState.update { it.copy(childName = name) }
    fun setBirthDate(date: LocalDate) = _uiState.update { it.copy(birthDate = date) }
    fun setGender(gender: Gender) = _uiState.update { it.copy(gender = gender) }
    fun setReferencePhoto(uri: String) = _uiState.update { it.copy(referencePhotoUri = uri) }

    fun startScanning() {
        val state = _uiState.value
        if (state.childName.isBlank() || state.birthDate == null ||
            state.referencePhotoUri.isBlank() || state.gender == null
        ) {
            _uiState.update { it.copy(error = "사진, 이름, 생년월일, 성별을 모두 입력해주세요.") }
            return
        }
        if (!faceEmbedder.isAvailable()) {
            _uiState.update {
                it.copy(
                    error = "얼굴 인식 모델 파일이 없습니다.\n" +
                        "README를 참고해 assets/mobile_face_net.tflite 파일을 추가해주세요."
                )
            }
            return
        }

        val birthDate = state.birthDate ?: return
        val gender = state.gender ?: return
        val sessionId = UUID.randomUUID().toString()

        viewModelScope.launch(Dispatchers.IO) {
            val child = Child(
                id = UUID.randomUUID().toString(),
                name = state.childName.trim(),
                birthDate = birthDate,
                gender = gender,
                referencePhotoUri = state.referencePhotoUri,
                faceEmbeddings = emptyList()
            )

            clearAllInitialScanCache()
            childRepository.save(child)
            initialScanDao.upsertSession(
                InitialScanSessionEntity(
                    id = sessionId,
                    childName = child.name,
                    birthDate = birthDate.toString(),
                    gender = gender.name,
                    referencePhotoUri = state.referencePhotoUri,
                    status = "RUNNING",
                    startedAt = System.currentTimeMillis()
                )
            )
            context.startForegroundService(
                Intent(context, ScanForegroundService::class.java).apply {
                    action = ScanForegroundService.ACTION_START
                    putExtra(ScanForegroundService.EXTRA_SESSION_ID, sessionId)
                }
            )
            dataStore.edit { prefs ->
                prefs[AppPrefsKeys.INITIALIZED] = true
                prefs[AppPrefsKeys.INITIAL_PHOTO_SYNC_CUTOFF_AT] = System.currentTimeMillis()
            }
            _uiState.update { it.copy(step = InitStep.Done, error = null) }
        }
    }

    fun dismissError() = _uiState.update { it.copy(error = null) }

    private suspend fun clearAllInitialScanCache() {
        initialScanDao.deleteAllEmbeddings()
        initialScanDao.deleteAllItems()
        initialScanDao.deleteAllClusters()
        initialScanDao.deleteAllSessions()
    }
}
