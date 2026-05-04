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
import com.bshsqa.dodochronicle.domain.model.ContextSearchSort
import com.bshsqa.dodochronicle.domain.model.Event
import com.bshsqa.dodochronicle.domain.model.EventCategory
import com.bshsqa.dodochronicle.domain.model.EventSearchContext
import com.bshsqa.dodochronicle.domain.model.EventSource
import com.bshsqa.dodochronicle.domain.model.KakaoRoom
import com.bshsqa.dodochronicle.domain.model.PhotoRecord
import com.bshsqa.dodochronicle.domain.model.SEARCH_CONTEXT_INDEX_VERSION
import com.bshsqa.dodochronicle.domain.repository.ChildRepository
import com.bshsqa.dodochronicle.domain.repository.EventRepository
import com.bshsqa.dodochronicle.domain.repository.KakaoRepository
import com.bshsqa.dodochronicle.domain.repository.RetryChunkRepository
import com.bshsqa.dodochronicle.domain.usecase.ManageEventUseCase
import com.bshsqa.dodochronicle.domain.usecase.RetryFailedChunksUseCase
import com.bshsqa.dodochronicle.domain.usecase.SyncNewPhotosUseCase
import com.bshsqa.dodochronicle.domain.usecase.UpdateChildEmbeddingUseCase
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

data class TimelineUiState(
    val childName: String = "",
    val birthDate: LocalDate? = null,
    val events: List<Event> = emptyList(),
    val filterCategory: EventCategory? = null,
    val onlyFavorite: Boolean = false,
    val pendingPhotos: List<SyncNewPhotosUseCase.PendingPhoto> = emptyList(),
    val kakaoRooms: List<KakaoRoom> = emptyList(),
    val snackbar: String? = null,
    val isLoading: Boolean = false,
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
    val contextUpdateProgress: ImportProgress? = null
)

@HiltViewModel
class TimelineViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val childRepository: ChildRepository,
    private val eventRepository: EventRepository,
    private val kakaoRepository: KakaoRepository,
    private val manageEventUseCase: ManageEventUseCase,
    private val syncUseCase: SyncNewPhotosUseCase,
    private val updateEmbeddingUseCase: UpdateChildEmbeddingUseCase,
    private val importStateHolder: ImportStateHolder,
    private val scanStateHolder: ScanStateHolder,
    private val retryChunkRepository: RetryChunkRepository,
    private val retryFailedChunksUseCase: RetryFailedChunksUseCase,
    private val dataStore: DataStore<Preferences>,
    private val geminiClassifier: GeminiEventClassifier
) : ViewModel() {

    private val _state = MutableStateFlow(TimelineUiState())
    val state: StateFlow<TimelineUiState> = _state.asStateFlow()

    private val _filterCategory = MutableStateFlow<EventCategory?>(null)
    private val _onlyFavorite = MutableStateFlow(false)
    private var childId: String = ""

    // roomId of the most recently imported room, kept for immediate retry from ImportDoneOverlay
    private var lastImportRoomId: String? = null
    private var lastImportAlias: String = ""

    init {
        viewModelScope.launch {
            val child = childRepository.getFirst()
            if (child == null) {
                _state.update { it.copy(needsInit = true) }
                return@launch
            }
            childId = child.id
            _state.update { it.copy(childName = child.name, birthDate = child.birthDate) }

            launch { syncNewPhotos() }

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
                    _state.update { it.copy(isScanRunning = scanState is ScanState.Running) }
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
                eventRepository.observeHidden(childId).collect { events ->
                    _state.update {
                        it.copy(hiddenTextEvents = events.filter { e -> e.category != EventCategory.PHOTO })
                    }
                }
            }
        }
    }

    private suspend fun syncNewPhotos() {
        try {
            val lastPhotoTakenAt = eventRepository.getLatestPhotoTakenAt() ?: 0L
            val initialCutoffAt = dataStore.data.first()[AppPrefsKeys.INITIAL_PHOTO_SYNC_CUTOFF_AT] ?: 0L
            val syncAfter = maxOf(lastPhotoTakenAt, initialCutoffAt)
            val newPhotos = queryNewPhotos(syncAfter)
            if (newPhotos.isEmpty()) return

            syncUseCase.invoke(newPhotos).collect { progress ->
                _state.update { it.copy(pendingPhotos = progress.needsConfirmation) }
            }
        } catch (_: Exception) {
            // Sync failure is non-fatal; pending photos remain empty
        }
    }

    private fun queryNewPhotos(after: Long): List<Pair<String, Long>> {
        val uris = mutableListOf<Pair<String, Long>>()
        val limit = BuildConfig.PHOTO_SCAN_LIMIT

        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN),
            "${MediaStore.Images.Media.DATE_TAKEN} > ?",
            arrayOf(after.toString()),
            "${MediaStore.Images.Media.DATE_TAKEN} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            var count = 0
            while (cursor.moveToNext() && (limit < 0 || count < limit)) {
                val id = cursor.getLong(idCol)
                val taken = cursor.getLong(dateCol)
                val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                uris.add(uri.toString() to taken)
                count++
            }
        }
        return uris
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
            arrayOf(MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED),
            null, null, null
        )?.use { c ->
            if (!c.moveToFirst()) return@use null
            val takenIdx = c.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
            val addedIdx = c.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
            
            val taken = if (takenIdx >= 0) c.getLong(takenIdx) else 0L
            if (taken > 0) return@use taken
            
            val added = if (addedIdx >= 0) c.getLong(addedIdx) else 0L
            if (added > 0) added * 1000L else null
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

    fun confirmPendingPhoto(pending: SyncNewPhotosUseCase.PendingPhoto, accept: Boolean) {
        viewModelScope.launch {
            syncUseCase.confirmPhoto(pending, accept, childId)
            if (accept) updateEmbeddingUseCase(childId)
            _state.update { s ->
                s.copy(pendingPhotos = s.pendingPhotos.filter { it.uri != pending.uri })
            }
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

            _state.update { s ->
                val processedUris = acceptedUris + rejectedUris
                s.copy(pendingPhotos = s.pendingPhotos.filter { it.uri !in processedUris })
            }
        }
    }

    fun startManualScan() {
        val intent = Intent(context, ScanForegroundService::class.java).apply {
            action = ScanForegroundService.ACTION_START
        }
        androidx.core.content.ContextCompat.startForegroundService(context, intent)
    }

    fun updateSearchContexts() {
        viewModelScope.launch {
            val completedVersion = dataStore.data.first()[AppPrefsKeys.SEARCH_CONTEXT_INDEX_COMPLETED_VERSION] ?: 0
            if (completedVersion >= SEARCH_CONTEXT_INDEX_VERSION) {
                _state.update { it.copy(snackbar = "문맥 인덱스가 이미 최신입니다.") }
                return@launch
            }
            if (!geminiClassifier.hasApiKey) {
                _state.update { it.copy(snackbar = "Gemini API 키가 없어 문맥 업데이트를 실행할 수 없습니다.") }
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

    fun dismissSnackbar() = _state.update { it.copy(snackbar = null) }

    fun resetApp() {
        viewModelScope.launch {
            if (childId.isNotEmpty()) {
                eventRepository.deleteAllForChild(childId)
            }
            eventRepository.deleteAllPhotoRecords()
            childRepository.deleteAll()
            kakaoRepository.deleteAll()
            retryChunkRepository.deleteAll()
            dataStore.edit { it.clear() }
            _state.update { it.copy(needsInit = true) }
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
            .filter { it.isNotBlank() }

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
