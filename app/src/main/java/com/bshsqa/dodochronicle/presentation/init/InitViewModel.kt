package com.bshsqa.dodochronicle.presentation.init

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bshsqa.dodochronicle.BuildConfig
import com.bshsqa.dodochronicle.domain.model.Child
import com.bshsqa.dodochronicle.domain.model.Gender
import com.bshsqa.dodochronicle.domain.repository.ChildRepository
import com.bshsqa.dodochronicle.ml.FaceCluster
import com.bshsqa.dodochronicle.ml.FaceClusteringEngine
import com.bshsqa.dodochronicle.ml.FaceDetectorHelper
import com.bshsqa.dodochronicle.ml.FaceEmbedder
import com.bshsqa.dodochronicle.ml.PhotoEmbedding
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
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
    private val faceDetector: FaceDetectorHelper,
    private val faceEmbedder: FaceEmbedder,
    private val clusteringEngine: FaceClusteringEngine,
    private val dataStore: DataStore<Preferences>
) : ViewModel() {

    private val _uiState = MutableStateFlow(InitUiState())
    val uiState: StateFlow<InitUiState> = _uiState.asStateFlow()

    private var _rawClusters: List<FaceCluster> = emptyList()
    private var scanJob: Job? = null

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
        _uiState.update { it.copy(step = InitStep.Scanning, error = null) }
        scanJob = viewModelScope.launch(Dispatchers.IO) { performScan() }
    }

    fun cancelScanning() {
        scanJob?.cancel()
        scanJob = null
        _rawClusters = emptyList()
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

    private suspend fun performScan() {
        val photoUris = queryPhotos()
        val total = photoUris.size
        _uiState.update { it.copy(totalCount = total) }

        val embeddings = mutableListOf<PhotoEmbedding>()
        var processed = 0

        for ((uri, takenAt) in photoUris) {
            currentCoroutineContext().ensureActive()
            val bmp = loadBitmap(uri)
            if (bmp != null) {
                val faces = faceDetector.detectFaces(bmp)
                if (faces.isNotEmpty()) {
                    val emb = faceEmbedder.embed(bmp, faces.first())
                    if (emb != null) embeddings.add(PhotoEmbedding(uri, takenAt, emb))
                }
            }
            processed++
            _uiState.update { it.copy(scannedCount = processed) }
        }

        val clusters = clusteringEngine.cluster(embeddings)
        if (clusters.isEmpty()) {
            _uiState.update { it.copy(step = InitStep.ChildInfo, error = "아이 얼굴이 감지된 사진이 없습니다. 사진을 다시 선택해주세요") }
            return
        }
        _rawClusters = clusters
        val clusterUi = clusters.map { c ->
            ClusterUiModel(c.id, c.representativeUris, c.embeddings.size)
        }
        _uiState.update { it.copy(step = InitStep.ClusterSelect, clusters = clusterUi) }
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
            val embeddings = _rawClusters
                .filter { it.id in state.selectedClusterIds }
                .map { it.averageEmbedding }
            val child = Child(
                id = UUID.randomUUID().toString(),
                name = state.childName,
                birthDate = state.birthDate!!,
                gender = state.gender!!,
                referencePhotoUri = state.referencePhotoUri,
                faceEmbeddings = embeddings
            )
            childRepository.save(child)
            dataStore.edit { prefs -> prefs[KEY_INITIALIZED] = true }
            _uiState.update { it.copy(step = InitStep.Done) }
        }
    }

    fun dismissError() = _uiState.update { it.copy(error = null) }

    private fun queryPhotos(): List<Pair<String, Long>> {
        val uris = mutableListOf<Pair<String, Long>>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"
        val limit = BuildConfig.PHOTO_SCAN_LIMIT

        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, null, null, sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            var count = 0
            while (cursor.moveToNext() && (limit < 0 || count < limit)) {
                val id = cursor.getLong(idCol)
                val takenAt = cursor.getLong(dateCol)
                val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                uris.add(uri.toString() to takenAt)
                count++
            }
        }
        return uris
    }

    private suspend fun loadBitmap(uri: String) = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(Uri.parse(uri))?.use {
                android.graphics.BitmapFactory.decodeStream(it)
            }
        } catch (e: Exception) { null }
    }
}
