package com.bshsqa.dodochronicle.presentation.timeline

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bshsqa.dodochronicle.BuildConfig
import com.bshsqa.dodochronicle.ai.GeminiEventClassifier
import com.bshsqa.dodochronicle.data.local.db.dao.InitialScanDao
import com.bshsqa.dodochronicle.data.local.db.entity.InitialScanItemEntity
import com.bshsqa.dodochronicle.domain.model.ContextSearchSort
import com.bshsqa.dodochronicle.domain.model.Event
import com.bshsqa.dodochronicle.domain.model.EventCategory
import com.bshsqa.dodochronicle.domain.model.EventSearchContext
import com.bshsqa.dodochronicle.domain.model.EventSource
import com.bshsqa.dodochronicle.domain.model.GeminiModelOption
import com.bshsqa.dodochronicle.domain.model.KakaoRoom
import com.bshsqa.dodochronicle.domain.model.PendingPhoto
import com.bshsqa.dodochronicle.domain.model.PhotoRecord
import com.bshsqa.dodochronicle.domain.model.SEARCH_CONTEXT_INDEX_VERSION
import com.bshsqa.dodochronicle.domain.repository.ChildRepository
import com.bshsqa.dodochronicle.domain.repository.EventRepository
import com.bshsqa.dodochronicle.domain.repository.GeminiSettingsRepository
import com.bshsqa.dodochronicle.domain.repository.KakaoRepository
import com.bshsqa.dodochronicle.domain.repository.RetryChunkRepository
import com.bshsqa.dodochronicle.domain.usecase.ManageEventUseCase
import com.bshsqa.dodochronicle.domain.usecase.CheckMissingPhotosUseCase
import com.bshsqa.dodochronicle.domain.usecase.ExportEventsUseCase
import com.bshsqa.dodochronicle.domain.usecase.ImportEventsUseCase
import com.bshsqa.dodochronicle.domain.usecase.RetryFailedChunksUseCase
import com.bshsqa.dodochronicle.domain.usecase.SyncNewPhotosUseCase
import com.bshsqa.dodochronicle.domain.usecase.SyncNewPhotosUseCase.PhotoCandidate
import com.bshsqa.dodochronicle.domain.usecase.UpdateChildEmbeddingUseCase
import com.bshsqa.dodochronicle.media.PhotoDateResolver
import com.bshsqa.dodochronicle.media.PhotoDateSource
import com.bshsqa.dodochronicle.service.ImportState
import com.bshsqa.dodochronicle.service.ImportStateHolder
import com.bshsqa.dodochronicle.service.KakaoImportService
import com.bshsqa.dodochronicle.service.ScanForegroundService
import com.bshsqa.dodochronicle.service.ScanState
import com.bshsqa.dodochronicle.service.ScanStateHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.bshsqa.dodochronicle.prefs.AppPrefsKeys
import kotlinx.coroutines.flow.first
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

data class ImportProgress(
    val chunksDone: Int,
    val totalChunks: Int,
    val dateRange: String
)

data class ImportDoneInfo(
    val addedMessages: Int,
    val addedEvents: Int,
    val apiRequests: Int,
    val totalTokens: Int,
    val failedChunks: Int,
    val elapsedSeconds: Long,
    val cancelled: Boolean
)

data class RetryRoomInfo(
    val roomId: String,
    val roomAlias: String,
    val chunkCount: Int
)

data class DeviceDayPhotos(
    val date: LocalDate,
    val uris: List<String>
)

data class InitialScanClusterUiModel(
    val id: Int,
    val previewUris: List<String>,
    val count: Int
)

data class InitialScanBannerInfo(
    val sessionId: String,
    val status: String,
    val processedCount: Int,
    val totalCount: Int,
    val elapsedSeconds: Long,
    val clusterCount: Int = 0
) {
    val isCompleted: Boolean get() = status == "COMPLETED" || status == "PARTIALLY_APPLIED"
}

private const val PHOTO_SYNC_OVERLAP_SECONDS = 86_400L

data class TimelineUiState(
    val childName: String = "",
    val childReferencePhotoUri: String = "",
    val birthDate: LocalDate? = null,
    val events: List<Event> = emptyList(),
    val filterCategory: EventCategory? = null,
    val onlyFavorite: Boolean = false,
    val pendingPhotos: List<PendingPhoto> = emptyList(),
    val kakaoRooms: List<KakaoRoom> = emptyList(),
    val snackbar: String? = null,
    val isLoading: Boolean = false,
    val isPhotoSyncRunning: Boolean = false,
    val isMissingPhotoCheckRunning: Boolean = false,
    val pendingDialogRequestId: Int = 0,
    val importProgress: ImportProgress? = null,
    val importDone: ImportDoneInfo? = null,
    val needsInit: Boolean = false,
    val pendingRetryRooms: List<RetryRoomInfo> = emptyList(),
    val isScanRunning: Boolean = false,
    val hiddenTextEvents: List<Event> = emptyList(),
    val photoRecordsByEventId: Map<String, PhotoRecord> = emptyMap(),
    val deviceDayPhotos: DeviceDayPhotos? = null,
    val searchQuery: String = "",
    val isContextSearch: Boolean = false,
    val isSearchDialogOpen: Boolean = false,
    val searchDraftQuery: String = "",
    val isContextSearchDraft: Boolean = false,
    val contextSearchSort: ContextSearchSort = ContextSearchSort.DATE,
    val contextUpdateProgress: ImportProgress? = null,
    val isEventImportRunning: Boolean = false,
    val geminiApiKeyConfigured: Boolean = false,
    val selectedGeminiModelId: String = "",
    val geminiModelOptions: List<GeminiModelOption> = emptyList(),
    val isGeminiModelLoading: Boolean = false,
    val initialScanBanner: InitialScanBannerInfo? = null,
    val initialScanClusters: List<InitialScanClusterUiModel> = emptyList(),
    val selectedInitialScanClusterIds: Set<Int> = emptySet()
)

@HiltViewModel
class TimelineViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val childRepository: ChildRepository,
    private val eventRepository: EventRepository,
    private val kakaoRepository: KakaoRepository,
    private val manageEventUseCase: ManageEventUseCase,
    private val checkMissingPhotosUseCase: CheckMissingPhotosUseCase,
    private val exportEventsUseCase: ExportEventsUseCase,
    private val importEventsUseCase: ImportEventsUseCase,
    private val syncUseCase: SyncNewPhotosUseCase,
    private val updateEmbeddingUseCase: UpdateChildEmbeddingUseCase,
    private val importStateHolder: ImportStateHolder,
    private val scanStateHolder: ScanStateHolder,
    private val retryChunkRepository: RetryChunkRepository,
    private val retryFailedChunksUseCase: RetryFailedChunksUseCase,
    private val dataStore: DataStore<Preferences>,
    private val geminiClassifier: GeminiEventClassifier,
    private val geminiSettingsRepository: GeminiSettingsRepository,
    private val initialScanDao: InitialScanDao,
    private val photoDateResolver: PhotoDateResolver
) : ViewModel() {

    private val _state = MutableStateFlow(TimelineUiState())
    val state: StateFlow<TimelineUiState> = _state.asStateFlow()

    private val _filterCategory = MutableStateFlow<EventCategory?>(null)
    private val _onlyFavorite = MutableStateFlow(false)
    private var childId: String = ""

    // roomId of the most recently imported room, kept for immediate retry from ImportDoneOverlay
    private var lastImportRoomId: String? = null
    private var lastImportAlias: String = ""
    private var photoSyncInProgress: Boolean = false
    private var hasObservedPendingPhotos: Boolean = false

    private suspend fun restoreOrResumeInitialScan() {
        val session = initialScanDao.getResumableSession() ?: return
        when (session.status) {
            "PREPARING_ITEMS", "RUNNING", "PAUSED" -> {
                _state.update {
                    it.copy(
                        isScanRunning = true,
                        initialScanBanner = InitialScanBannerInfo(
                            sessionId = session.id,
                            status = session.status,
                            processedCount = session.processedCount,
                            totalCount = session.totalCount,
                            elapsedSeconds = session.elapsedSeconds
                        )
                    )
                }
                context.startForegroundService(
                    Intent(context, ScanForegroundService::class.java).apply {
                        action = ScanForegroundService.ACTION_START
                        putExtra(ScanForegroundService.EXTRA_SESSION_ID, session.id)
                    }
                )
            }
            "COMPLETED", "PARTIALLY_APPLIED" -> {
                loadInitialScanClusters(session.id)
                _state.update {
                    it.copy(
                        initialScanBanner = InitialScanBannerInfo(
                            sessionId = session.id,
                            status = session.status,
                            processedCount = session.processedCount,
                            totalCount = session.totalCount,
                            elapsedSeconds = session.elapsedSeconds,
                            clusterCount = it.initialScanClusters.size
                        )
                    )
                }
            }
        }
    }

    private suspend fun loadInitialScanClusters(sessionId: String) {
        val rows = initialScanDao.getProcessedItems(sessionId)
        val previewUrisByCluster = initialScanDao.getClusters(sessionId).associate { row ->
            val previews = runCatching {
                Json.decodeFromString<List<String>>(row.representativeUrisJson)
            }.getOrDefault(emptyList())
            row.clusterId to previews
        }
        val clusters = rows
            .groupBy { it.clusterId ?: -1 }
            .filterKeys { it >= 0 }
            .map { (clusterId, items) ->
                InitialScanClusterUiModel(
                    id = clusterId,
                    previewUris = previewUrisByCluster[clusterId].orEmpty().ifEmpty {
                        items.take(9).map { it.uri }
                    },
                    count = items.size
                )
            }
            .sortedByDescending { it.count }
        _state.update {
            it.copy(
                initialScanClusters = clusters,
                selectedInitialScanClusterIds = it.selectedInitialScanClusterIds.intersect(
                    clusters.map { cluster -> cluster.id }.toSet()
                )
            )
        }
    }

    fun toggleInitialScanCluster(clusterId: Int) {
        _state.update { state ->
            val selected = if (clusterId in state.selectedInitialScanClusterIds) {
                state.selectedInitialScanClusterIds - clusterId
            } else {
                state.selectedInitialScanClusterIds + clusterId
            }
            state.copy(selectedInitialScanClusterIds = selected)
        }
    }

    fun addSelectedInitialScanClusters() {
        viewModelScope.launch {
            val banner = _state.value.initialScanBanner ?: return@launch
            val selectedClusterIds = _state.value.selectedInitialScanClusterIds
            if (selectedClusterIds.isEmpty()) {
                _state.update { it.copy(snackbar = "추가할 사진 그룹을 선택해주세요") }
                return@launch
            }
            val rows = initialScanDao.getProcessedItems(banner.sessionId)
                .filter { it.clusterId in selectedClusterIds }
            val events = mutableListOf<Event>()
            val records = mutableListOf<PhotoRecord>()
            rows.forEach { row ->
                val embedding = row.embeddingJson.toFloatArrayOrNull() ?: return@forEach
                val eventId = UUID.randomUUID().toString()
                events += Event(
                    id = eventId,
                    childId = childId,
                    date = Instant.ofEpochMilli(row.takenAt).atZone(ZoneId.systemDefault()).toLocalDate(),
                    category = EventCategory.PHOTO,
                    content = row.uri,
                    source = EventSource.PHOTO
                )
                records += PhotoRecord(
                    id = UUID.randomUUID().toString(),
                    eventId = eventId,
                    localUri = row.uri,
                    takenAt = row.takenAt,
                    faceEmbedding = embedding,
                    similarityScore = 1f
                )
            }
            eventRepository.insertAll(events)
            eventRepository.insertAllPhotoRecords(records)
            initialScanDao.deleteItemsByClusters(banner.sessionId, selectedClusterIds.toList())
            initialScanDao.deleteClustersByIds(banner.sessionId, selectedClusterIds.toList())
            updateEmbeddingUseCase(childId)
            loadInitialScanClusters(banner.sessionId)
            val remaining = _state.value.initialScanClusters.size
            if (remaining == 0) {
                clearInitialScanCache(banner.sessionId)
                _state.update {
                    it.copy(
                        initialScanBanner = null,
                        selectedInitialScanClusterIds = emptySet(),
                        snackbar = "${events.size}장 사진을 추가했습니다"
                    )
                }
            } else {
                initialScanDao.updateStatus(banner.sessionId, "PARTIALLY_APPLIED")
                _state.update {
                    it.copy(
                        initialScanBanner = banner.copy(status = "PARTIALLY_APPLIED", clusterCount = remaining),
                        selectedInitialScanClusterIds = emptySet(),
                        snackbar = "${events.size}장 사진을 추가했습니다"
                    )
                }
            }
        }
    }

    fun completeInitialScanSelection() {
        viewModelScope.launch {
            val sessionId = _state.value.initialScanBanner?.sessionId ?: return@launch
            clearInitialScanCache(sessionId)
            _state.update {
                it.copy(
                    initialScanBanner = null,
                    initialScanClusters = emptyList(),
                    selectedInitialScanClusterIds = emptySet(),
                    snackbar = "초기 사진 그룹 선택을 완료했습니다"
                )
            }
        }
    }

    private suspend fun clearInitialScanCache(sessionId: String) {
        initialScanDao.deleteEmbeddings(sessionId)
        initialScanDao.deleteItems(sessionId)
        initialScanDao.deleteClusters(sessionId)
        initialScanDao.deleteSession(sessionId)
        scanStateHolder.reset()
    }

    private suspend fun clearAllInitialScanCache() {
        initialScanDao.deleteAllEmbeddings()
        initialScanDao.deleteAllItems()
        initialScanDao.deleteAllClusters()
        initialScanDao.deleteAllSessions()
    }

    init {
        viewModelScope.launch {
            val child = childRepository.getFirst()
            if (child == null) {
                _state.update { it.copy(needsInit = true) }
                return@launch
            }
            childId = child.id
            _state.update {
                it.copy(
                    childName = child.name,
                    childReferencePhotoUri = child.referencePhotoUri,
                    birthDate = child.birthDate
                )
            }
            restoreOrResumeInitialScan()

            launch {
                geminiSettingsRepository.settingsFlow.collect { settings ->
                    val cachedModels = geminiSettingsRepository.getCachedModels()
                    _state.update {
                        it.copy(
                            geminiApiKeyConfigured = settings.apiKey.isNotBlank(),
                            selectedGeminiModelId = settings.modelId,
                            geminiModelOptions = mergeModelOptions(cachedModels, settings.modelId)
                        )
                    }
                }
            }

            if (child.faceEmbeddings.isNotEmpty()) {
                launch { syncNewPhotos(manual = false) }
            }

            launch {
                kakaoRepository.observeRooms().collect { rooms ->
                    _state.update { it.copy(kakaoRooms = rooms) }
                }
            }

            launch {
                retryChunkRepository.observeAll().collect { chunks ->
                    val grouped = chunks.groupBy { it.roomId }
                    _state.update { s ->
                        s.copy(pendingRetryRooms = grouped.map { (roomId, roomChunks) ->
                            RetryRoomInfo(roomId, roomChunks.first().roomAlias, roomChunks.size)
                        })
                    }
                }
            }

            launch {
                scanStateHolder.state.collect { scanState ->
                    when (scanState) {
                        is ScanState.Running -> _state.update {
                            it.copy(
                                isScanRunning = true,
                                initialScanBanner = InitialScanBannerInfo(
                                    sessionId = scanState.sessionId,
                                    status = "RUNNING",
                                    processedCount = scanState.processed,
                                    totalCount = scanState.total,
                                    elapsedSeconds = scanState.elapsedSeconds,
                                    clusterCount = it.initialScanBanner?.clusterCount ?: 0
                                )
                            )
                        }
                        is ScanState.Done -> {
                            loadInitialScanClusters(scanState.sessionId)
                            _state.update {
                                it.copy(
                                    isScanRunning = false,
                                    initialScanBanner = InitialScanBannerInfo(
                                        sessionId = scanState.sessionId,
                                        status = "COMPLETED",
                                        processedCount = scanState.embeddings.size,
                                        totalCount = scanState.embeddings.size,
                                        elapsedSeconds = scanState.elapsedSeconds,
                                        clusterCount = scanState.clusters.size
                                    )
                                )
                            }
                        }
                        is ScanState.Failed -> {
                            clearAllInitialScanCache()
                            scanStateHolder.reset()
                            _state.update {
                                it.copy(
                                    isScanRunning = false,
                                    initialScanBanner = null,
                                    initialScanClusters = emptyList(),
                                    selectedInitialScanClusterIds = emptySet(),
                                    snackbar = scanState.message
                                )
                            }
                        }
                        else -> _state.update { it.copy(isScanRunning = false) }
                    }
                }
            }

            launch {
                importStateHolder.state.collect { importState ->
                    when (importState) {
                        is ImportState.Idle -> _state.update {
                            it.copy(isLoading = false, importProgress = null)
                        }
                        is ImportState.Running -> _state.update {
                            it.copy(
                                isLoading = true,
                                importProgress = if (importState.totalChunks == 0) null
                                else ImportProgress(importState.chunksDone, importState.totalChunks, importState.dateRange)
                            )
                        }
                        is ImportState.Done -> {
                            // resolve roomId for immediate retry if there are failed chunks
                            if (importState.failedChunks > 0 && lastImportAlias.isNotBlank()) {
                                launch {
                                    val room = kakaoRepository.getRoomByName(lastImportAlias)
                                    lastImportRoomId = room?.id
                                }
                            }
                            importStateHolder.reset()
                            _state.update {
                                it.copy(
                                    isLoading = false,
                                    importProgress = null,
                                    importDone = ImportDoneInfo(
                                        addedMessages = importState.addedMessages,
                                        addedEvents = importState.addedEvents,
                                        apiRequests = importState.apiRequests,
                                        totalTokens = importState.totalTokens,
                                        failedChunks = importState.failedChunks,
                                        elapsedSeconds = importState.elapsedSeconds,
                                        cancelled = importState.cancelled
                                    )
                                )
                            }
                        }
                        is ImportState.Error -> {
                            importStateHolder.reset()
                            _state.update {
                                it.copy(snackbar = importState.message, isLoading = false, importProgress = null)
                            }
                        }
                    }
                }
            }

            launch {
                combine(_filterCategory, _onlyFavorite) { cat, fav -> cat to fav }
                    .flatMapLatest { (cat, fav) ->
                        eventRepository.observe(childId, cat, fav)
                    }
                    .combine(
                        _state.map {
                            Triple(it.searchQuery, it.isContextSearch, it.contextSearchSort)
                        }.distinctUntilChanged()
                    ) { events, searchState ->
                        events to searchState
                    }
                    .mapLatest { (events, searchState) ->
                        val query = searchState.first
                        val isContextSearch = searchState.second
                        val sort = searchState.third
                        if (query.isBlank()) {
                            events
                        } else if (isContextSearch) {
                            val scoredEvents = scoreContextSearch(events, query)
                            debugLogContextSearch(query, scoredEvents)
                            when (sort) {
                                ContextSearchSort.DATE -> scoredEvents.map { it.event }
                                ContextSearchSort.RELEVANCE -> scoredEvents
                                    .sortedWith(
                                        compareByDescending<ContextSearchCandidate> { it.score }
                                            .thenBy { it.event.date }
                                    )
                                    .map { it.event }
                            }
                        } else {
                            val keywords = query.split("\\s+".toRegex()).filter { it.isNotBlank() }
                            events.filter { event ->
                                if (event.category == EventCategory.PHOTO) false
                                else {
                                    val fullText = "${event.content} ${event.longContent ?: ""} ${event.rawExcerpt ?: ""}"
                                    val exactQuoteMatch = Regex("^\"(.+)\"$").find(query.trim())
                                    if (exactQuoteMatch != null) {
                                        fullText.contains(exactQuoteMatch.groupValues[1], ignoreCase = true)
                                    } else {
                                        keywords.all { kw -> fullText.contains(kw, ignoreCase = true) }
                                    }
                                }
                            }
                        }
                    }
                    .collect { filteredEvents ->
                        _state.update { it.copy(events = filteredEvents) }
                    }
            }

            launch {
                eventRepository.observePhotoRecordsForChild(childId).collect { records ->
                    _state.update {
                        it.copy(photoRecordsByEventId = records.associateBy { record -> record.eventId })
                    }
                }
            }

            launch {
                eventRepository.observePendingPhotosForChild(childId).collect { pending ->
                    _state.update {
                        val shouldNotify = hasObservedPendingPhotos &&
                            pending.size > it.pendingPhotos.size &&
                            !it.isPhotoSyncRunning
                        hasObservedPendingPhotos = true
                        it.copy(
                            pendingPhotos = pending,
                            snackbar = if (shouldNotify) {
                                "확인 필요한 사진이 있습니다"
                            } else {
                                it.snackbar
                            }
                        )
                    }
                }
            }

            launch {
                eventRepository.observeHidden(childId).collect { events ->
                    _state.update {
                        it.copy(hiddenTextEvents = events.filter { e -> e.category != EventCategory.PHOTO })
                    }
                }
            }
        }
    }

    private suspend fun syncNewPhotos(manual: Boolean) {
        try {
            if (photoSyncInProgress) return
            photoSyncInProgress = true
            if (manual) {
                _state.update { it.copy(isPhotoSyncRunning = true) }
            }
            val child = childRepository.getFirst()
            if (child?.faceEmbeddings.isNullOrEmpty()) {
                _state.update {
                    it.copy(
                        isPhotoSyncRunning = false,
                        snackbar = if (manual) "초기 사진 그룹을 먼저 추가해주세요" else it.snackbar
                    )
                }
                photoSyncInProgress = false
                return
            }

            val prefs = dataStore.data.first()
            val lastAddedAt = prefs[AppPrefsKeys.LAST_PHOTO_SYNC_ADDED_AT_SECONDS] ?: 0L
            val initialCutoffAt = (prefs[AppPrefsKeys.INITIAL_PHOTO_SYNC_CUTOFF_AT] ?: 0L) / 1000L
            val syncAfter = maxOf(lastAddedAt, initialCutoffAt)
            val newPhotos = queryNewPhotos(syncAfter)
            if (newPhotos.isEmpty()) {
                _state.update {
                    it.copy(
                        isPhotoSyncRunning = false,
                        snackbar = if (manual) "새로 추가할 사진이 없습니다" else it.snackbar
                    )
                }
                photoSyncInProgress = false
                return
            }

            var finalProgress: SyncNewPhotosUseCase.SyncProgress? = null

            syncUseCase.invoke(newPhotos).collect { progress ->
                finalProgress = progress
            }
            val maxAddedAt = newPhotos.maxOfOrNull { it.addedAtSeconds } ?: 0L
            if (maxAddedAt > 0L) {
                dataStore.edit { editPrefs ->
                    editPrefs[AppPrefsKeys.LAST_PHOTO_SYNC_ADDED_AT_SECONDS] = maxAddedAt
                }
            }

            val progress = finalProgress
            val pendingCount = progress?.needsConfirmation?.size ?: 0
            val autoAdded = progress?.autoAdded ?: 0
            _state.update {
                it.copy(
                    isPhotoSyncRunning = false,
                    pendingDialogRequestId = if (manual && pendingCount > 0) {
                        it.pendingDialogRequestId + 1
                    } else {
                        it.pendingDialogRequestId
                    },
                    snackbar = buildPhotoSyncSnackbar(autoAdded, pendingCount, manual)
                )
            }
            photoSyncInProgress = false
        } catch (_: Exception) {
            _state.update {
                it.copy(
                    isPhotoSyncRunning = false,
                    snackbar = if (manual) "사진 로딩에 실패했습니다" else it.snackbar
                )
            }
            photoSyncInProgress = false
        }
    }

    private fun queryNewPhotos(afterAddedAtSeconds: Long): List<PhotoCandidate> {
        val uris = mutableListOf<PhotoCandidate>()
        val limit = BuildConfig.PHOTO_SCAN_LIMIT
        val queryStart = (afterAddedAtSeconds - PHOTO_SYNC_OVERLAP_SECONDS).coerceAtLeast(0L)

        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.DATE_MODIFIED
            ),
            "${MediaStore.Images.Media.DATE_ADDED} >= ?",
            arrayOf(queryStart.toString()),
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val takenCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val addedCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val modifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            var count = 0
            while (cursor.moveToNext() && (limit < 0 || count < limit)) {
                val id = cursor.getLong(idCol)
                val added = cursor.getLong(addedCol)
                val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                val resolvedDate = photoDateResolver.resolve(
                    context.contentResolver,
                    PhotoDateSource(
                        uri = uri,
                        dateTakenMillis = cursor.getLong(takenCol),
                        dateAddedSeconds = added,
                        dateModifiedSeconds = cursor.getLong(modifiedCol)
                    )
                )
                if (added > 0L && resolvedDate != null) {
                    uris.add(PhotoCandidate(uri.toString(), resolvedDate.takenAtMillis, added))
                }
                count++
            }
        }
        return uris
    }

    private fun buildPhotoSyncSnackbar(autoAdded: Int, pendingCount: Int, manual: Boolean): String? =
        when {
            autoAdded > 0 -> "${autoAdded}장 사진을 자동 추가했습니다"
            manual && pendingCount > 0 -> "확인 필요한 사진이 있습니다"
            manual -> "새로 추가할 사진이 없습니다"
            else -> null
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

    fun setFavoriteBatch(eventIds: List<String>, isFavorite: Boolean) {
        viewModelScope.launch {
            eventIds.forEach { manageEventUseCase.setFavorite(it, isFavorite) }
        }
    }

    fun deleteEvent(id: String) {
        viewModelScope.launch {
            manageEventUseCase.delete(id)
            updateEmbeddingUseCase(childId)
            _state.update { it.copy(snackbar = "이벤트가 삭제되었습니다") }
        }
    }

    fun deletePhotoEventsBatch(eventIds: List<String>) {
        viewModelScope.launch {
            manageEventUseCase.deleteEventsBatch(eventIds)
            updateEmbeddingUseCase(childId)
            _state.update { it.copy(snackbar = "${eventIds.size}개 사진이 삭제되었습니다") }
        }
    }

    fun setExcludeFromModelBatch(photoRecords: List<PhotoRecord>, excluded: Boolean) {
        viewModelScope.launch {
            photoRecords.forEach { record ->
                manageEventUseCase.setExcludeFromModel(record.id, excluded)
            }
            updateEmbeddingUseCase(childId)
            val msg = if (excluded) "학습에서 제외되었습니다" else "학습에 포함되었습니다"
            _state.update { it.copy(snackbar = msg) }
        }
    }

    fun setSearchDraftQuery(query: String) {
        _state.update { it.copy(searchDraftQuery = query) }
    }

    fun setSearchDraftContextSearch(isContextSearch: Boolean) {
        _state.update { it.copy(isContextSearchDraft = isContextSearch) }
    }

    fun setSearchDialogOpen(isOpen: Boolean) {
        _state.update {
            if (isOpen) {
                it.copy(
                    isSearchDialogOpen = true,
                    searchDraftQuery = it.searchQuery,
                    isContextSearchDraft = it.isContextSearch
                )
            } else {
                it.copy(isSearchDialogOpen = false)
            }
        }
    }

    fun executeSearch() {
        _state.update {
            val query = it.searchDraftQuery.trim()
            if (query.isBlank()) {
                it.copy(
                    searchQuery = "",
                    contextSearchSort = ContextSearchSort.DATE,
                    isSearchDialogOpen = false
                )
            } else {
                it.copy(
                    searchQuery = query,
                    isContextSearch = it.isContextSearchDraft,
                    contextSearchSort = ContextSearchSort.DATE,
                    isSearchDialogOpen = false
                )
            }
        }
    }

    fun clearSearch() {
        _state.update {
            it.copy(
                searchQuery = "",
                isSearchDialogOpen = false,
                searchDraftQuery = "",
                isContextSearchDraft = false,
                contextSearchSort = ContextSearchSort.DATE
            )
        }
    }

    fun setContextSearchSort(sort: ContextSearchSort) {
        _state.update { it.copy(contextSearchSort = sort) }
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
                    source = EventSource.MANUAL,
                    searchSummary = if (category != EventCategory.PHOTO) content.trim() else "",
                    searchContextVersion = if (category != EventCategory.PHOTO) {
                        SEARCH_CONTEXT_INDEX_VERSION
                    } else {
                        0
                    }
                )
            )
        }
    }

    fun hideTextEvent(event: Event) {
        if (event.category == EventCategory.PHOTO) return
        viewModelScope.launch {
            manageEventUseCase.setHidden(event.id, true)
            _state.update { it.copy(snackbar = "텍스트 이벤트를 숨겼습니다") }
        }
    }

    fun restoreHiddenTextEvent(eventId: String) {
        viewModelScope.launch {
            manageEventUseCase.setHidden(eventId, false)
            _state.update { it.copy(snackbar = "숨김 이벤트를 복원했습니다") }
        }
    }

    fun restoreHiddenTextEvents(eventIds: List<String>) {
        if (eventIds.isEmpty()) return
        viewModelScope.launch {
            eventIds.forEach { id -> manageEventUseCase.setHidden(id, false) }
            _state.update { it.copy(snackbar = "${eventIds.size}개 이벤트를 복원했습니다") }
        }
    }

    fun loadDevicePhotosForDate(date: LocalDate) {
        viewModelScope.launch {
            _state.update { it.copy(deviceDayPhotos = DeviceDayPhotos(date, queryPhotosForDate(date))) }
        }
    }

    fun dismissDeviceDayPhotos() {
        _state.update { it.copy(deviceDayPhotos = null) }
    }

    fun addManualPhotos(uris: List<Uri>) {
        viewModelScope.launch {
            val existingUris = eventRepository.getAllPhotoUris().toSet()
            var added = 0
            uris.distinct().forEach { uri ->
                val uriString = uri.toString()
                if (uriString in existingUris) return@forEach
                val takenAt = queryTakenAt(uri) ?: System.currentTimeMillis()
                val date = Instant.ofEpochMilli(takenAt).atZone(ZoneId.systemDefault()).toLocalDate()
                val eventId = UUID.randomUUID().toString()
                eventRepository.insert(
                    Event(
                        id = eventId, childId = childId, date = date,
                        category = EventCategory.PHOTO, content = uriString, source = EventSource.MANUAL
                    )
                )
                eventRepository.insertPhotoRecord(
                    PhotoRecord(
                        id = UUID.randomUUID().toString(),
                        eventId = eventId,
                        localUri = uriString,
                        takenAt = takenAt,
                        isExcludedFromModel = true
                    )
                )
                added++
            }
            _state.update { it.copy(snackbar = "${added}장 추가됨") }
        }
    }

    private fun queryTakenAt(uri: Uri): Long? {
        return context.contentResolver.query(
            uri,
            arrayOf(
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.DATE_MODIFIED
            ),
            null, null, null
        )?.use { c ->
            if (!c.moveToFirst()) return@use null
            val takenIdx = c.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
            val addedIdx = c.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
            val modifiedIdx = c.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED)

            photoDateResolver.resolve(
                context.contentResolver,
                PhotoDateSource(
                    uri = uri,
                    dateTakenMillis = if (takenIdx >= 0) c.getLong(takenIdx) else null,
                    dateAddedSeconds = if (addedIdx >= 0) c.getLong(addedIdx) else null,
                    dateModifiedSeconds = if (modifiedIdx >= 0) c.getLong(modifiedIdx) else null
                )
            )?.takenAtMillis
        }
    }

    private fun queryPhotosForDate(date: LocalDate): List<String> {
        val start = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val uris = mutableListOf<String>()

        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN),
            "${MediaStore.Images.Media.DATE_TAKEN} >= ? AND ${MediaStore.Images.Media.DATE_TAKEN} < ?",
            arrayOf(start.toString(), end.toString()),
            "${MediaStore.Images.Media.DATE_TAKEN} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                uris.add(Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString()).toString())
            }
        }

        return uris
    }

    fun importKakao(uri: Uri, roomAlias: String) {
        if (!_state.value.isGeminiConfigured()) {
            _state.update { it.copy(snackbar = "Gemini API 키와 모델을 먼저 설정해주세요.") }
            return
        }
        lastImportAlias = roomAlias
        lastImportRoomId = null
        _state.update { it.copy(isLoading = true, importProgress = null, importDone = null) }
        context.startForegroundService(
            Intent(context, KakaoImportService::class.java).apply {
                action = KakaoImportService.ACTION_START
                putExtra(KakaoImportService.EXTRA_URI, uri.toString())
                putExtra(KakaoImportService.EXTRA_ROOM_ALIAS, roomAlias)
            }
        )
    }

    fun cancelImport() {
        context.startService(
            Intent(context, KakaoImportService::class.java).apply {
                action = KakaoImportService.ACTION_CANCEL
            }
        )
    }

    fun dismissImportResult() {
        importStateHolder.reset()
        _state.update { it.copy(importDone = null) }
    }

    /** ImportDoneOverlay의 즉시 재시도 버튼에서 호출 */
    fun retryImmediate() {
        val roomId = lastImportRoomId ?: return
        retryRoom(roomId)
    }

    /** 설정 메뉴의 재시도 방 선택 후 호출 */
    fun retryRoom(roomId: String) {
        if (!_state.value.isGeminiConfigured()) {
            _state.update { it.copy(snackbar = "Gemini API 키와 모델을 먼저 설정해주세요.") }
            return
        }
        _state.update { it.copy(isLoading = true, importProgress = null, importDone = null) }
        viewModelScope.launch {
            val startTime = System.currentTimeMillis()
            val result = retryFailedChunksUseCase(
                roomId = roomId,
                onProgress = { progress ->
                    _state.update { s ->
                        s.copy(importProgress = ImportProgress(
                            chunksDone = progress.chunkIndex,
                            totalChunks = progress.totalChunks,
                            dateRange = progress.dateRange
                        ))
                    }
                }
            )
            val elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000

            if (result.remainingFailedChunks > 0) {
                lastImportRoomId = roomId
            } else {
                lastImportRoomId = null
            }

            _state.update {
                it.copy(
                    isLoading = false,
                    importProgress = null,
                    importDone = ImportDoneInfo(
                        addedMessages = 0,
                        addedEvents = result.addedEvents,
                        apiRequests = result.apiRequests,
                        totalTokens = result.totalTokens,
                        failedChunks = result.remainingFailedChunks,
                        elapsedSeconds = elapsedSeconds,
                        cancelled = false
                    )
                )
            }
        }
    }

    fun confirmPendingPhoto(pending: PendingPhoto, accept: Boolean) {
        viewModelScope.launch {
            syncUseCase.confirmPhoto(pending, accept, childId)
            if (accept) updateEmbeddingUseCase(childId)
        }
    }

    fun processPendingPhotos(acceptedUris: Set<String>, rejectedUris: Set<String>) {
        viewModelScope.launch {
            val currentPending = _state.value.pendingPhotos
            val accepted = currentPending.filter { it.uri in acceptedUris }
            val rejected = currentPending.filter { it.uri in rejectedUris }

            for (pending in accepted) {
                syncUseCase.confirmPhoto(pending, true, childId)
            }
            for (pending in rejected) {
                syncUseCase.confirmPhoto(pending, false, childId)
            }

            if (accepted.isNotEmpty()) updateEmbeddingUseCase(childId)

            if (rejected.isNotEmpty()) {
                eventRepository.upsertRejectedPhotos(childId, rejected)
            }
            val maxAddedAt = currentPending.maxOfOrNull { it.addedAtSeconds }
                ?: (dataStore.data.first()[AppPrefsKeys.LAST_PHOTO_SYNC_ADDED_AT_SECONDS] ?: 0L)
            val minRejectedAddedAt = (maxAddedAt - PHOTO_SYNC_OVERLAP_SECONDS).coerceAtLeast(0L)
            eventRepository.pruneRejectedPhotos(childId, minRejectedAddedAt)
            eventRepository.deletePendingPhotos(rejected.map { it.uri })
        }
    }

    fun startManualScan() {
        viewModelScope.launch {
            syncNewPhotos(manual = true)
        }
    }

    fun checkMissingPhotos() {
        viewModelScope.launch {
            _state.update { it.copy(isMissingPhotoCheckRunning = true) }
            try {
                val result = checkMissingPhotosUseCase()
                if (result.missing > 0 || result.restored > 0) {
                    updateEmbeddingUseCase(childId)
                }
                val message = when {
                    result.checked == 0 -> "확인할 사진이 없습니다"
                    result.missing > 0 && result.restored > 0 ->
                        "원본을 찾을 수 없는 사진 ${result.missing}장, 복구된 사진 ${result.restored}장"
                    result.missing > 0 -> "원본을 찾을 수 없는 사진 ${result.missing}장을 표시했습니다"
                    result.restored > 0 -> "사진 ${result.restored}장의 원본을 다시 확인했습니다"
                    else -> "모든 사진 원본을 확인했습니다"
                }
                _state.update { it.copy(isMissingPhotoCheckRunning = false, snackbar = message) }
            } catch (_: Exception) {
                _state.update {
                    it.copy(isMissingPhotoCheckRunning = false, snackbar = "사진 원본 확인에 실패했습니다")
                }
            }
        }
    }

    fun loadGeminiModels(apiKeyInput: String) {
        viewModelScope.launch {
            val key = apiKeyInput.trim().ifBlank {
                geminiSettingsRepository.getSettings().apiKey
            }
            if (key.isBlank()) {
                _state.update { it.copy(snackbar = "Gemini API 키를 먼저 입력해주세요.") }
                return@launch
            }

            _state.update { it.copy(isGeminiModelLoading = true) }
            try {
                val models = geminiSettingsRepository.fetchModels(key)
                _state.update { state ->
                    state.copy(
                        isGeminiModelLoading = false,
                        geminiModelOptions = models,
                        selectedGeminiModelId = state.selectedGeminiModelId.ifBlank { models.first().id },
                        snackbar = "Gemini 모델 목록을 불러왔습니다."
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isGeminiModelLoading = false,
                        snackbar = e.message ?: "모델 목록을 불러오지 못했습니다."
                    )
                }
            }
        }
    }

    fun saveGeminiSettings(apiKeyInput: String, modelId: String) {
        viewModelScope.launch {
            try {
                val existing = geminiSettingsRepository.getSettings()
                val key = apiKeyInput.trim().ifBlank { existing.apiKey }
                if (key.isBlank()) {
                    _state.update { it.copy(snackbar = "Gemini API 키를 먼저 입력해주세요.") }
                    return@launch
                }
                if (modelId.isBlank()) {
                    _state.update { it.copy(snackbar = "Gemini 모델을 선택해주세요.") }
                    return@launch
                }
                geminiSettingsRepository.save(key, modelId)
                _state.update { it.copy(snackbar = "Gemini API 설정을 저장했습니다.") }
            } catch (e: Exception) {
                _state.update { it.copy(snackbar = e.message ?: "Gemini API 설정 저장에 실패했습니다.") }
            }
        }
    }

    fun clearGeminiSettings() {
        viewModelScope.launch {
            geminiSettingsRepository.clear()
            _state.update {
                it.copy(
                    geminiApiKeyConfigured = false,
                    selectedGeminiModelId = "",
                    geminiModelOptions = emptyList(),
                    snackbar = "Gemini API 설정을 삭제했습니다."
                )
            }
        }
    }

    fun updateSearchContexts() {
        viewModelScope.launch {
            val completedVersion = dataStore.data.first()[AppPrefsKeys.SEARCH_CONTEXT_INDEX_COMPLETED_VERSION] ?: 0
            if (completedVersion >= SEARCH_CONTEXT_INDEX_VERSION) {
                _state.update { it.copy(snackbar = "문맥 인덱스가 이미 최신입니다.") }
                return@launch
            }
            if (!geminiClassifier.isConfigured()) {
                _state.update { it.copy(snackbar = "Gemini API 키와 모델을 먼저 설정해주세요.") }
                return@launch
            }

            val targets = eventRepository.getEventsNeedingSearchContextUpdate(SEARCH_CONTEXT_INDEX_VERSION)
            if (targets.isEmpty()) {
                dataStore.edit { prefs ->
                    prefs[AppPrefsKeys.SEARCH_CONTEXT_INDEX_COMPLETED_VERSION] = SEARCH_CONTEXT_INDEX_VERSION
                }
                _state.update { it.copy(snackbar = "문맥 인덱스가 이미 최신입니다.") }
                return@launch
            }

            _state.update {
                it.copy(
                    isLoading = true,
                    contextUpdateProgress = ImportProgress(0, targets.size, "문맥 업데이트")
                )
            }

            var processed = 0
            var failed = false
            targets.chunked(10).forEachIndexed { index, batch ->
                if (index > 0) kotlinx.coroutines.delay(12_000L)
                val result = geminiClassifier.generateSearchContexts(batch)
                if (result.contextsByEventId.isEmpty()) {
                    failed = true
                    return@forEachIndexed
                }
                batch.forEach { event ->
                    val context = result.contextsByEventId[event.id]
                        ?: EventSearchContext.fallback(event.content)
                    try {
                        eventRepository.updateSearchContext(event.id, context)
                        processed++
                    } catch (e: Exception) {
                        failed = true
                        Log.e("TimelineContextSearch", "Failed to update search context", e)
                    }
                }
                _state.update {
                    it.copy(
                        contextUpdateProgress = ImportProgress(
                            chunksDone = processed,
                            totalChunks = targets.size,
                            dateRange = "문맥 업데이트"
                        )
                    )
                }
            }

            if (!failed) {
                dataStore.edit { prefs ->
                    prefs[AppPrefsKeys.SEARCH_CONTEXT_INDEX_COMPLETED_VERSION] = SEARCH_CONTEXT_INDEX_VERSION
                }
            }
            _state.update {
                it.copy(
                    isLoading = false,
                    contextUpdateProgress = null,
                    snackbar = if (failed) "일부 문맥 업데이트에 실패했습니다." else "문맥 업데이트가 완료되었습니다."
                )
            }
        }
    }

    fun exportEvents(onReady: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val json = exportEventsUseCase()
                onReady(json)
            } catch (e: Exception) {
                _state.update { it.copy(snackbar = e.message ?: "이벤트 내보내기에 실패했습니다") }
            }
        }
    }

    fun importEventsFromUrl(url: String) {
        viewModelScope.launch {
            _state.update { it.copy(isEventImportRunning = true) }
            try {
                val result = importEventsUseCase(url)
                _state.update {
                    it.copy(
                        isEventImportRunning = false,
                        snackbar = "이벤트 ${result.imported}개를 가져왔습니다. 중복 ${result.duplicates}개, 오류 ${result.skipped}개는 제외했습니다."
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isEventImportRunning = false,
                        snackbar = e.message ?: "이벤트 가져오기에 실패했습니다"
                    )
                }
            }
        }
    }

    fun importEventsFromJson(json: String) {
        viewModelScope.launch {
            _state.update { it.copy(isEventImportRunning = true) }
            try {
                val result = importEventsUseCase.fromJson(json)
                _state.update {
                    it.copy(
                        isEventImportRunning = false,
                        snackbar = "이벤트 ${result.imported}개를 가져왔습니다. 중복 ${result.duplicates}개, 오류 ${result.skipped}개는 제외했습니다."
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isEventImportRunning = false,
                        snackbar = e.message ?: "이벤트 가져오기에 실패했습니다"
                    )
                }
            }
        }
    }

    fun showSnackbar(message: String) {
        _state.update { it.copy(snackbar = message) }
    }

    private fun mergeModelOptions(
        cachedModels: List<GeminiModelOption>,
        selectedModelId: String
    ): List<GeminiModelOption> {
        if (selectedModelId.isBlank()) return cachedModels
        if (cachedModels.any { it.id == selectedModelId }) return cachedModels
        return listOf(
            GeminiModelOption(
                id = selectedModelId,
                label = selectedModelId.removePrefix("models/")
            )
        ) + cachedModels
    }

    private fun TimelineUiState.isGeminiConfigured(): Boolean =
        geminiApiKeyConfigured && selectedGeminiModelId.isNotBlank()

    fun dismissSnackbar() = _state.update { it.copy(snackbar = null) }

    fun updateChildReferencePhoto(uri: String) {
        viewModelScope.launch {
            val child = childRepository.getFirst()
            if (child == null) {
                _state.update { it.copy(snackbar = "아이 정보를 찾을 수 없습니다.") }
                return@launch
            }
            childRepository.save(child.copy(referencePhotoUri = uri))
            _state.update {
                it.copy(
                    childReferencePhotoUri = uri,
                    snackbar = "대표 사진을 변경했습니다."
                )
            }
        }
    }

    fun resetApp() {
        viewModelScope.launch {
            context.startService(
                Intent(context, ScanForegroundService::class.java).apply {
                    action = ScanForegroundService.ACTION_CANCEL
                }
            )
            scanStateHolder.reset()
            clearAllInitialScanCache()
            if (childId.isNotEmpty()) {
                eventRepository.deleteAllForChild(childId)
                eventRepository.deleteAllPendingPhotosForChild(childId)
                eventRepository.deleteAllRejectedPhotosForChild(childId)
            }
            eventRepository.deleteAllPhotoRecords()
            childRepository.deleteAll()
            kakaoRepository.deleteAll()
            retryChunkRepository.deleteAll()
            dataStore.edit { it.clear() }
            _state.update {
                it.copy(
                    needsInit = true,
                    isScanRunning = false,
                    initialScanBanner = null,
                    initialScanClusters = emptyList(),
                    selectedInitialScanClusterIds = emptySet()
                )
            }
        }
    }

    private fun scoreContextSearch(events: List<Event>, query: String): List<ContextSearchCandidate> {
        val tokens = tokenizeSearchQuery(query)
        if (tokens.isEmpty()) return emptyList()
        val phrase = query.trim().lowercase()

        return events.mapNotNull { event ->
            if (event.category == EventCategory.PHOTO) return@mapNotNull null
            val score = scoreEvent(event, tokens, phrase)
            val matchedPrimaryOrAlias = matchesAny(
                listOf(event.content, event.longContent.orEmpty(), event.rawExcerpt.orEmpty()) + event.searchAliases,
                tokens
            )
            if (shouldIncludeContextResult(tokens.size, score, matchedPrimaryOrAlias)) {
                ContextSearchCandidate(event, score)
            } else {
                null
            }
        }
    }

    private fun scoreEvent(event: Event, tokens: List<String>, phrase: String): Int {
        var score = 0
        score += fieldScore(event.content, tokens, 5)
        score += fieldScore(event.longContent.orEmpty(), tokens, 3)
        score += fieldScore(event.rawExcerpt.orEmpty(), tokens, 3)
        score += fieldScore(event.searchSummary, tokens, 3)
        score += listScore(event.searchTags, tokens, 2)
        score += listScore(event.searchAliases, tokens, 3)
        score += listScore(event.relatedKeywords, tokens, 1)

        val allSearchText = buildContextSearchText(event).lowercase()
        if (phrase.isNotBlank() && allSearchText.contains(phrase)) score += 4
        if (tokens.all { token -> allSearchText.contains(token) }) score += 3
        return score
    }

    private fun fieldScore(text: String, tokens: List<String>, weight: Int): Int {
        val lower = text.lowercase()
        return tokens.count { token -> lower.contains(token) } * weight
    }

    private fun listScore(values: List<String>, tokens: List<String>, weight: Int): Int =
        values.sumOf { value -> fieldScore(value, tokens, weight) }

    private fun matchesAny(values: List<String>, tokens: List<String>): Boolean =
        values.any { value ->
            val lower = value.lowercase()
            tokens.any { lower.contains(it) }
        }

    private fun shouldIncludeContextResult(
        tokenCount: Int,
        score: Int,
        matchedPrimaryOrAlias: Boolean
    ): Boolean {
        if (score <= 0) return false
        if (tokenCount <= 1) return score >= 2 || matchedPrimaryOrAlias
        return score >= 3 || matchedPrimaryOrAlias
    }

    private fun buildContextSearchText(event: Event): String =
        listOf(
            event.content,
            event.longContent.orEmpty(),
            event.rawExcerpt.orEmpty(),
            event.searchSummary,
            event.searchTags.joinToString(" "),
            event.searchAliases.joinToString(" "),
            event.relatedKeywords.joinToString(" ")
        ).joinToString(" ")

    private fun tokenizeSearchQuery(query: String): List<String> =
        query.trim()
            .lowercase()
            .split("\\s+".toRegex())
            .flatMap { token -> normalizeContextSearchToken(token) }
            .distinct()

    private fun normalizeContextSearchToken(raw: String): List<String> {
        val cleaned = raw
            .replace(Regex("""^[^\p{L}\p{N}]+|[^\p{L}\p{N}]+$"""), "")
            .trim()
        if (cleaned.isBlank()) return emptyList()

        val stripped = stripKoreanParticle(cleaned)
        val candidates = listOf(cleaned, stripped)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        return candidates.filter { token ->
            token !in CONTEXT_SEARCH_STOPWORDS &&
                token !in CONTEXT_SEARCH_WEAK_ONE_SYLLABLES &&
                (token.length >= 2 || token in CONTEXT_SEARCH_ONE_SYLLABLE_KEYWORDS)
        }
    }

    private fun stripKoreanParticle(token: String): String {
        val particles = listOf(
            "\uc73c\ub85c\ubd80\ud130", "\ub85c\ubd80\ud130", "\uc5d0\uc11c\ub294", "\uc5d0\uc11c", "\uc5d0\uac8c",
            "\ud55c\ud14c", "\uc73c\ub85c", "\ubd80\ud130", "\uae4c\uc9c0", "\ucc98\ub7fc", "\ubcf4\ub2e4",
            "\ud558\uace0", "\uc774\ub791", "\ub791", "\uc640", "\uacfc", "\uc740", "\ub294", "\uc774", "\uac00",
            "\uc744", "\ub97c", "\uc5d0", "\ub85c", "\ub3c4", "\ub9cc", "\uc758"
        )
        return particles.firstNotNullOfOrNull { particle ->
            token
                .takeIf { it.length > particle.length + 1 && it.endsWith(particle) }
                ?.dropLast(particle.length)
        } ?: token
    }

    private fun debugLogContextSearch(query: String, scoredEvents: List<ContextSearchCandidate>) {
        val topMatches = scoredEvents.take(5).joinToString(separator = " | ") { candidate ->
            "${candidate.score}:${candidate.event.content.take(24)}"
        }
        Log.d(
            "TimelineContextSearch",
            "query=\"$query\" total=${scoredEvents.size} top=$topMatches"
        )
    }
}

private data class ContextSearchCandidate(
    val event: Event,
    val score: Int
)

private fun String.toFloatArrayOrNull(): FloatArray? =
    runCatching { Json.decodeFromString<List<Float>>(this).toFloatArray() }.getOrNull()

private val CONTEXT_SEARCH_STOPWORDS = setOf(
    "\uac83", "\uac70", "\ub54c", "\ub0a0", "\uad00\ub828", "\uae30\ub85d", "\uc77c\uc0c1",
    "\uc624\ub298", "\uc5b4\uc81c", "\ub0b4\uc77c", "\uc544\uc774", "\uc544\uae30", "\uc6b0\ub9ac",
    "\uc5c4\ub9c8", "\uc544\ube60"
)

private val CONTEXT_SEARCH_WEAK_ONE_SYLLABLES = setOf(
    "\uac10", "\uc634", "\ubd04", "\ud568", "\ub428", "\uac04", "\uc628", "\ubcf8",
    "\ud55c", "\ud560", "\uac00", "\uc640"
)

private val CONTEXT_SEARCH_ONE_SYLLABLE_KEYWORDS = setOf(
    "\uc5f4", "\uc57d", "\ubc25", "\uc7a0", "\ub625", "\ud53c", "\ubb3c", "\ucc45", "\ub9d0"
)
