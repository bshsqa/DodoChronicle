package com.bshsqa.dodochronicle.presentation.init

import android.content.Context
import android.content.Intent
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bshsqa.dodochronicle.domain.model.Child
import com.bshsqa.dodochronicle.domain.model.Event
import com.bshsqa.dodochronicle.domain.model.EventCategory
import com.bshsqa.dodochronicle.domain.model.EventSource
import com.bshsqa.dodochronicle.domain.model.Gender
import com.bshsqa.dodochronicle.domain.model.PhotoRecord
import com.bshsqa.dodochronicle.domain.repository.ChildRepository
import com.bshsqa.dodochronicle.domain.repository.EventRepository
import com.bshsqa.dodochronicle.domain.usecase.UpdateChildEmbeddingUseCase
import com.bshsqa.dodochronicle.ml.FaceEmbedder
import com.bshsqa.dodochronicle.ml.PhotoEmbedding
import com.bshsqa.dodochronicle.service.ScanForegroundService
import com.bshsqa.dodochronicle.service.ScanState
import com.bshsqa.dodochronicle.service.ScanStateHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject

private val KEY_INITIALIZED = booleanPreferencesKey("initialized")

data class ClusterUiModel(val id: Int, val previewUris: List<String>, val count: Int)

sealed class InitStep {
    object ChildInfo : InitStep()
    object Scanning : InitStep()
    object ClusterSelect : InitStep()
    object Done : InitStep()
}

data class InitUiState(
    val step: InitStep = InitStep.ChildInfo,
    val childName: String = "",
    val birthDate: LocalDate? = null,
    val gender: Gender? = null,
    val referencePhotoUri: String = "",
    val scannedCount: Int = 0,
    val totalCount: Int = 0,
    val clusters: List<ClusterUiModel> = emptyList(),
    val selectedClusterIds: Set<Int> = emptySet(),
    val error: String? = null
)

@HiltViewModel
class InitViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val childRepository: ChildRepository,
    private val eventRepository: EventRepository,
    private val updateEmbeddingUseCase: UpdateChildEmbeddingUseCase,
    private val faceEmbedder: FaceEmbedder,
    private val stateHolder: ScanStateHolder,
    private val dataStore: DataStore<Preferences>
) : ViewModel() {

    private val _uiState = MutableStateFlow(InitUiState())
    val uiState: StateFlow<InitUiState> = _uiState.asStateFlow()

    // clusterID → 해당 클러스터에 속하는 PhotoEmbedding 목록
    private var _rawClusterPhotos: Map<Int, List<PhotoEmbedding>> = emptyMap()

    init {
        viewModelScope.launch {
            stateHolder.state.collect { scanState ->
                when (scanState) {
                    is ScanState.Running -> _uiState.update {
                        // 서비스가 실행 중임을 보장: 화면 회전 등 ViewModel 재생성 시에도 Scanning 단계 복원
                        it.copy(
                            step = InitStep.Scanning,
                            scannedCount = scanState.processed,
                            totalCount = scanState.total
                        )
                    }
                    is ScanState.Done -> handleDone(scanState)
                    is ScanState.Failed -> {
                        stateHolder.reset()
                        _uiState.update {
                            it.copy(step = InitStep.ChildInfo, error = scanState.message)
                        }
                    }
                    else -> Unit // Idle, Cancelled — UI 별도 조작 불필요
                }
            }
        }
    }

    private fun handleDone(done: ScanState.Done) {
        _rawClusterPhotos = done.clusters.associate { cluster ->
            cluster.id to done.embeddings.filter { pe ->
                cluster.representativeUris.contains(pe.uri) ||
                    cluster.embeddings.any { it.contentEquals(pe.embedding) }
            }
        }
        val clusterUi = done.clusters.map { c ->
            ClusterUiModel(c.id, c.representativeUris, c.embeddings.size)
        }
        _uiState.update { it.copy(step = InitStep.ClusterSelect, clusters = clusterUi) }
    }

    fun setChildName(name: String) = _uiState.update { it.copy(childName = name) }
    fun setBirthDate(date: LocalDate) = _uiState.update { it.copy(birthDate = date) }
    fun setGender(gender: Gender) = _uiState.update { it.copy(gender = gender) }
    fun setReferencePhoto(uri: String) = _uiState.update { it.copy(referencePhotoUri = uri) }

    fun startScanning() {
        val state = _uiState.value
        if (state.childName.isBlank() || state.birthDate == null
            || state.referencePhotoUri.isBlank() || state.gender == null
        ) {
            _uiState.update { it.copy(error = "사진, 이름, 생년월일, 성별을 모두 입력해주세요") }
            return
        }
        if (!faceEmbedder.isAvailable()) {
            _uiState.update {
                it.copy(error = "얼굴 인식 모델 파일이 없습니다.\nREADME를 참고해 assets/mobile_face_net.tflite 파일을 추가해주세요.")
            }
            return
        }
        _uiState.update { it.copy(step = InitStep.Scanning, error = null) }
        context.startForegroundService(
            Intent(context, ScanForegroundService::class.java).apply {
                action = ScanForegroundService.ACTION_START
            }
        )
    }

    fun cancelScanning() {
        context.startService(
            Intent(context, ScanForegroundService::class.java).apply {
                action = ScanForegroundService.ACTION_CANCEL
            }
        )
        stateHolder.reset()
        _rawClusterPhotos = emptyMap()
        _uiState.update { state ->
            state.copy(
                step = InitStep.ChildInfo,
                scannedCount = 0,
                totalCount = 0,
                clusters = emptyList(),
                selectedClusterIds = emptySet(),
                error = null
            )
        }
    }

    fun toggleCluster(id: Int) {
        _uiState.update { s ->
            val selected = if (id in s.selectedClusterIds) s.selectedClusterIds - id
            else s.selectedClusterIds + id
            s.copy(selectedClusterIds = selected)
        }
    }

    fun confirmClusters() {
        val state = _uiState.value
        if (state.selectedClusterIds.isEmpty()) {
            _uiState.update { it.copy(error = "하나 이상의 그룹을 선택해주세요") }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            // 1. Child 저장
            val child = Child(
                id = UUID.randomUUID().toString(),
                name = state.childName,
                birthDate = state.birthDate!!,
                gender = state.gender!!,
                referencePhotoUri = state.referencePhotoUri,
                faceEmbeddings = emptyList() // UpdateChildEmbeddingUseCase 로 채워질 예정
            )
            childRepository.save(child)

            // 2. 선택된 클러스터의 PhotoEmbedding 목록으로 Event + PhotoRecord 일괄 등록
            val events = mutableListOf<Event>()
            val photoRecords = mutableListOf<PhotoRecord>()

            for (clusterId in state.selectedClusterIds) {
                val photoEmbeddings = _rawClusterPhotos[clusterId] ?: continue
                for (pe in photoEmbeddings) {
                    val date = Instant.ofEpochMilli(pe.takenAt)
                        .atZone(ZoneId.systemDefault()).toLocalDate()
                    val eventId = UUID.randomUUID().toString()
                    events.add(
                        Event(
                            id = eventId,
                            childId = child.id,
                            date = date,
                            category = EventCategory.PHOTO,
                            content = pe.uri,
                            source = EventSource.PHOTO
                        )
                    )
                    photoRecords.add(
                        PhotoRecord(
                            id = UUID.randomUUID().toString(),
                            eventId = eventId,
                            localUri = pe.uri,
                            takenAt = pe.takenAt,
                            faceEmbedding = pe.embedding,
                            similarityScore = 1f
                        )
                    )
                }
            }

            eventRepository.insertAll(events)
            eventRepository.insertAllPhotoRecords(photoRecords)

            // 3. 최신 50장 기준으로 기준 임베딩 벡터 갱신
            updateEmbeddingUseCase(child.id)

            // 4. ScanStateHolder 초기화 후 완료 처리
            stateHolder.reset()
            dataStore.edit { prefs -> prefs[KEY_INITIALIZED] = true }
            _uiState.update { it.copy(step = InitStep.Done) }
        }
    }

    fun dismissError() = _uiState.update { it.copy(error = null) }
}
