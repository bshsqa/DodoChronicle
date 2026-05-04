package com.bshsqa.dodochronicle.presentation.timeline

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.bshsqa.dodochronicle.R
import com.bshsqa.dodochronicle.domain.model.ContextSearchSort
import com.bshsqa.dodochronicle.domain.model.Event
import com.bshsqa.dodochronicle.domain.model.EventCategory
import com.bshsqa.dodochronicle.domain.model.KakaoRoom
import com.bshsqa.dodochronicle.domain.model.PendingPhoto
import com.bshsqa.dodochronicle.domain.model.PhotoRecord
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class ZoomLevel { YEAR, MONTH, WEEK, DAY }

private fun scaleToZoom(scale: Float) = when {
    scale < 0.5f -> ZoomLevel.YEAR
    scale < 2f -> ZoomLevel.MONTH
    scale < 6f -> ZoomLevel.WEEK
    else -> ZoomLevel.DAY
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineScreen(
    viewModel: TimelineViewModel = hiltViewModel(),
    onNeedsInit: () -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var showKakaoMenu by remember { mutableStateOf(false) }
    var showSettingsMenu by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var showRetryRoomDialog by remember { mutableStateOf(false) }
    var showPendingDialog by remember { mutableStateOf(false) }
    var showHiddenItemsDialog by remember { mutableStateOf(false) }
    var showAddMenu by remember { mutableStateOf(false) }
    var selectedDetailDate by remember { mutableStateOf<LocalDate?>(null) }
    var fullscreenPhotos by remember { mutableStateOf<List<String>?>(null) }
    var fullscreenIndex by remember { mutableStateOf(0) }
    var kakaoImportAlias by remember { mutableStateOf("") }

    LaunchedEffect(state.needsInit) {
        if (state.needsInit) onNeedsInit()
    }

    val kakaoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.importKakao(it, kakaoImportAlias) }
    }
    val legacyPhotoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val uris = mutableListOf<android.net.Uri>()
            result.data?.clipData?.let { clipData ->
                for (i in 0 until clipData.itemCount) {
                    uris.add(clipData.getItemAt(i).uri)
                }
            } ?: result.data?.data?.let { uri ->
                uris.add(uri)
            }
            if (uris.isNotEmpty()) viewModel.addManualPhotos(uris)
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(state.snackbar) {
        state.snackbar?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissSnackbar()
        }
    }

    LaunchedEffect(state.pendingDialogRequestId) {
        if (state.pendingDialogRequestId > 0 && state.pendingPhotos.isNotEmpty()) {
            showPendingDialog = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Image(
                            painter = painterResource(R.drawable.dodo_child_mark),
                            contentDescription = null,
                            modifier = Modifier
                                .size(30.dp)
                                .clip(RoundedCornerShape(9.dp))
                        )
                        Text(state.childName.ifBlank { "DodoChronicle" })
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::toggleFavoriteFilter) {
                        Icon(
                            if (state.onlyFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = "즐겨찾기 필터",
                            tint = if (state.onlyFavorite) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(onClick = { viewModel.setSearchDialogOpen(true) }) {
                        Icon(Icons.Default.Search, contentDescription = "검색")
                    }
                    IconButton(onClick = { showSettingsMenu = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "설정")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            Box {
                FloatingActionButton(
                    onClick = { showAddMenu = true },
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Icon(Icons.Default.Add, contentDescription = "추가",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                DropdownMenu(
                    expanded = showAddMenu,
                    onDismissRequest = { showAddMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("사진 추가") },
                        leadingIcon = { Icon(Icons.Default.AddPhotoAlternate, contentDescription = null) },
                        onClick = {
                            showAddMenu = false
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_PICK,
                                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                            ).apply {
                                putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true)
                            }
                            legacyPhotoPickerLauncher.launch(intent)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("텍스트 이벤트 추가") },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = {
                            showAddMenu = false
                            showAddDialog = true
                        }
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxSize()) {
                CategoryFilterRow(
                    selected = state.filterCategory,
                    onSelect = viewModel::setFilterCategory
                )

                // 검색 활성 시 필터링 안내 바너
                if (state.searchQuery.isNotBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "검색 중: “${state.searchQuery}” ${if (state.isContextSearch) "(문맥)" else "(키워드)"} • ${state.events.size}건",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        IconButton(
                            onClick = viewModel::clearSearch,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.Default.Clear,
                                contentDescription = "검색 종료",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                    if (state.isContextSearch) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SingleChoiceSegmentedButtonRow(
                                modifier = Modifier.widthIn(min = 184.dp)
                            ) {
                                SegmentedButton(
                                    selected = state.contextSearchSort == ContextSearchSort.DATE,
                                    onClick = { viewModel.setContextSearchSort(ContextSearchSort.DATE) },
                                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                                    label = {
                                        Text(
                                            "날짜",
                                            maxLines = 1,
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                                )
                                SegmentedButton(
                                    selected = state.contextSearchSort == ContextSearchSort.RELEVANCE,
                                    onClick = { viewModel.setContextSearchSort(ContextSearchSort.RELEVANCE) },
                                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                                    label = {
                                        Text(
                                            "관련도",
                                            maxLines = 1,
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                                )
                            }
                        }
                    }
                }

                if (state.pendingPhotos.isNotEmpty()) {
                    PendingPhotosBanner(
                        count = state.pendingPhotos.size,
                        onClick = { showPendingDialog = true }
                    )
                }

                if (state.events.isEmpty()) {
                    EmptyTimeline(modifier = Modifier.weight(1f))
                } else {
                    GroupedTimelineContent(
                        events = state.events,
                        birthDate = state.birthDate,
                        searchQuery = state.searchQuery,
                        isContextSearch = state.isContextSearch,
                        contextSearchSort = state.contextSearchSort,
                        photoRecordsByEventId = state.photoRecordsByEventId,
                        onDayClick = { date -> selectedDetailDate = date },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            if (state.isLoading) {
                var elapsedSeconds by remember { mutableLongStateOf(0L) }
                LaunchedEffect(Unit) {
                    val startAt = System.currentTimeMillis()
                    while (true) {
                        elapsedSeconds = (System.currentTimeMillis() - startAt) / 1000
                        kotlinx.coroutines.delay(1000)
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f))
                        .consumeAllPointers(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .background(
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                                shape = RoundedCornerShape(16.dp)
                            )
                            .padding(horizontal = 32.dp, vertical = 24.dp)
                    ) {
                        Image(
                            painter = painterResource(R.drawable.dodo_loading_mark),
                            contentDescription = null,
                            modifier = Modifier
                                .size(76.dp)
                                .clip(RoundedCornerShape(20.dp))
                        )
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Text(
                            "카카오 대화 분석 중...",
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        val progress = state.importProgress
                        if (progress != null) {
                            Text(
                                "청크 ${progress.chunksDone + 1} / ${progress.totalChunks}",
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                progress.dateRange,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        val minutes = elapsedSeconds / 60
                        val seconds = elapsedSeconds % 60
                        Text(
                            "경과: ${if (minutes > 0) "${minutes}분 " else ""}${seconds}초",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                        TextButton(
                            onClick = viewModel::cancelImport,
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) { Text("취소") }
                    }
                }
            }

            if (state.isPhotoSyncRunning || state.isMissingPhotoCheckRunning) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.25f))
                        .consumeAllPointers(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .background(
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                                shape = RoundedCornerShape(16.dp)
                            )
                            .padding(horizontal = 32.dp, vertical = 24.dp)
                    ) {
                        Image(
                            painter = painterResource(R.drawable.dodo_loading_mark),
                            contentDescription = null,
                            modifier = Modifier
                                .size(76.dp)
                                .clip(RoundedCornerShape(20.dp))
                        )
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Text(
                            if (state.isMissingPhotoCheckRunning) {
                                "사진 원본 확인 중..."
                            } else {
                                "신규 사진 분석 중..."
                            },
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            val importDone = state.importDone
            if (importDone != null) {
                ImportDoneOverlay(
                    info = importDone,
                    onConfirm = viewModel::dismissImportResult,
                    onRetry = if (importDone.failedChunks > 0) viewModel::retryImmediate else null
                )
            }
        }
    }

    // 날짜별 상세 다이얼로그
    val detailDate = selectedDetailDate
    if (detailDate != null) {
        val dayEvents = state.events.filter { it.date == detailDate }
        DailyDetailDialog(
            date = detailDate,
            events = dayEvents,
            searchQuery = state.searchQuery,
            isContextSearch = state.isContextSearch,
            onDismiss = { selectedDetailDate = null },
            photoRecordsByEventId = state.photoRecordsByEventId,
            onDeleteBatch = { ids ->
                viewModel.deletePhotoEventsBatch(ids)
                selectedDetailDate = null
            },
            onExcludeBatch = { records, excluded ->
                viewModel.setExcludeFromModelBatch(records, excluded)
            },
            onToggleFavorite = viewModel::toggleFavorite,
            onSetFavoriteBatch = { ids, fav -> viewModel.setFavoriteBatch(ids, fav) },
            onHideTextEvent = { event ->
                viewModel.hideTextEvent(event)
                if (dayEvents.size <= 1) selectedDetailDate = null
            },
            onShowDevicePhotos = { viewModel.loadDevicePhotosForDate(detailDate) },
            onPhotoClick = { photos, index ->
                fullscreenPhotos = photos
                fullscreenIndex = index
            },
            onMissingPhotoClick = {
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("원본 사진을 찾을 수 없습니다")
                }
            }
        )
    }

    val currentFullscreenPhotos = fullscreenPhotos
    if (currentFullscreenPhotos != null) {
        FullscreenPhotoViewer(
            photos = currentFullscreenPhotos,
            initialIndex = fullscreenIndex,
            onDismiss = { fullscreenPhotos = null }
        )
    }

    // 검색 다이얼로그
    if (state.isSearchDialogOpen) {
        TimelineSearchDialog(
            query = state.searchDraftQuery,
            isContextSearch = state.isContextSearchDraft,
            onQueryChange = viewModel::setSearchDraftQuery,
            onContextSearchChange = viewModel::setSearchDraftContextSearch,
            onSearch = viewModel::executeSearch,
            onDismiss = { viewModel.setSearchDialogOpen(false) }
        )
    }

    if (showAddDialog) {
        AddEventDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { date, cat, content ->
                viewModel.addManualEvent(date, cat, content)
                showAddDialog = false
            }
        )
    }

    if (showKakaoMenu) {
        KakaoImportDialog(
            existingRooms = state.kakaoRooms,
            onDismiss = { showKakaoMenu = false },
            onSelectFile = { alias ->
                kakaoImportAlias = alias
                showKakaoMenu = false
                kakaoLauncher.launch("text/*")
            }
        )
    }

    if (showSettingsMenu) {
        SettingsMenuDialog(
            pendingRetryCount = state.pendingRetryRooms.sumOf { it.chunkCount },
            pendingPhotoCount = state.pendingPhotos.size,
            onKakaoImport = {
                showSettingsMenu = false
                showKakaoMenu = true
            },
            onRetry = {
                showSettingsMenu = false
                showRetryRoomDialog = true
            },
            onScan = {
                showSettingsMenu = false
                viewModel.startManualScan()
            },
            isScanRunning = state.isPhotoSyncRunning,
            isMissingPhotoCheckRunning = state.isMissingPhotoCheckRunning,
            onCheckMissingPhotos = {
                showSettingsMenu = false
                viewModel.checkMissingPhotos()
            },
            onPendingPhotos = {
                showSettingsMenu = false
                showPendingDialog = true
            },
            onHiddenItems = {
                showSettingsMenu = false
                showHiddenItemsDialog = true
            },
            onContextUpdate = {
                showSettingsMenu = false
                viewModel.updateSearchContexts()
            },
            onReset = {
                showSettingsMenu = false
                showResetConfirm = true
            },
            onDismiss = { showSettingsMenu = false }
        )
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("앱 데이터 초기화") },
            text = { Text("앱 데이터를 초기화합니다.\n갤러리 사진은 변경되지 않습니다.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetConfirm = false
                        viewModel.resetApp()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("초기화")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text("취소") }
            }
        )
    }

    if (showRetryRoomDialog) {
        RetryRoomDialog(
            rooms = state.pendingRetryRooms,
            onDismiss = { showRetryRoomDialog = false },
            onRetry = { roomId ->
                showRetryRoomDialog = false
                viewModel.retryRoom(roomId)
            }
        )
    }

    if (showPendingDialog && state.pendingPhotos.isNotEmpty()) {
        PendingPhotosDialog(
            photos = state.pendingPhotos,
            onDismiss = { showPendingDialog = false },
            onConfirm = { acceptedUris, rejectedUris ->
                viewModel.processPendingPhotos(acceptedUris, rejectedUris)
                showPendingDialog = false
            }
        )
    }

    state.deviceDayPhotos?.let { devicePhotos ->
        DayPhotosDialog(
            photos = devicePhotos.uris,
            onDismiss = viewModel::dismissDeviceDayPhotos,
            onPhotoClick = { index ->
                fullscreenPhotos = devicePhotos.uris
                fullscreenIndex = index
            }
        )
    }

    if (showHiddenItemsDialog) {
        HiddenItemsDialog(
            events = state.hiddenTextEvents,
            onDismiss = { showHiddenItemsDialog = false },
            onRestore = { ids ->
                viewModel.restoreHiddenTextEvents(ids)
                if (ids.size >= state.hiddenTextEvents.size) showHiddenItemsDialog = false
            }
        )
    }
}

@Composable
private fun CategoryFilterRow(selected: EventCategory?, onSelect: (EventCategory?) -> Unit) {
    val categories = listOf(null, EventCategory.SAID, EventCategory.DID, EventCategory.PHOTO, EventCategory.OTHER)
    val labels = mapOf(null to "전체", EventCategory.SAID to "한 말", EventCategory.DID to "한 일",
        EventCategory.PHOTO to "사진", EventCategory.OTHER to "기타")

    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        categories.forEach { cat ->
            FilterChip(
                selected = selected == cat,
                onClick = { onSelect(cat) },
                label = { Text(labels[cat] ?: "") },
                leadingIcon = if (selected == cat) ({
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                }) else null
            )
        }
    }
}


@Composable
private fun TimelineBar(
    modifier: Modifier,
    startDate: LocalDate,
    today: LocalDate,
    totalDays: Float,
    offsetY: Float,
    baseHeightPx: Float,
    zoomLevel: ZoomLevel,
    events: List<Event>
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val eventDotColor = MaterialTheme.colorScheme.secondary

    Canvas(modifier = modifier.background(surfaceVariant.copy(alpha = 0.3f))) {
        val barX = size.width / 2f
        val topY = offsetY

        drawLine(
            color = primaryColor.copy(alpha = 0.4f),
            start = Offset(barX, 0f),
            end = Offset(barX, size.height),
            strokeWidth = 3f,
            cap = StrokeCap.Round
        )

        val labelDates = generateLabelDates(startDate, today, zoomLevel)
        for (date in labelDates) {
            val days = ChronoUnit.DAYS.between(startDate, date).toFloat()
            val fraction = days / totalDays
            val y = topY + fraction * baseHeightPx
            if (y < 0 || y > size.height) continue

            drawLine(
                color = primaryColor.copy(alpha = 0.6f),
                start = Offset(barX - 8f, y),
                end = Offset(barX + 8f, y),
                strokeWidth = 2f
            )
        }

        for (event in events) {
            val days = ChronoUnit.DAYS.between(startDate, event.date).toFloat()
            val fraction = (days / totalDays).coerceIn(0f, 1f)
            val y = topY + fraction * baseHeightPx
            if (y < 0 || y > size.height) continue
            drawCircle(
                color = eventDotColor,
                radius = 6f,
                center = Offset(barX, y)
            )
        }
    }
}

private fun generateLabelDates(start: LocalDate, end: LocalDate, zoom: ZoomLevel): List<LocalDate> {
    val dates = mutableListOf<LocalDate>()
    var current = when (zoom) {
        ZoomLevel.YEAR -> start.withDayOfYear(1)
        ZoomLevel.MONTH -> start.withDayOfMonth(1)
        ZoomLevel.WEEK -> start
        ZoomLevel.DAY -> start
    }
    val step = when (zoom) {
        ZoomLevel.YEAR -> java.time.Period.ofYears(1)
        ZoomLevel.MONTH -> java.time.Period.ofMonths(1)
        ZoomLevel.WEEK -> java.time.Period.ofWeeks(1)
        ZoomLevel.DAY -> java.time.Period.ofDays(1)
    }
    while (!current.isAfter(end)) {
        dates.add(current)
        current = current.plus(step)
    }
    return dates
}

private fun Modifier.consumeAllPointers(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EventCard(
    event: Event,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val categoryColor = categoryColor(event.category)
    val categoryLabel = categoryLabel(event.category)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = { showMenu = true }
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AssistChip(
                    onClick = {},
                    label = { Text(categoryLabel, style = MaterialTheme.typography.labelSmall) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = categoryColor.copy(alpha = 0.12f),
                        labelColor = categoryColor
                    ),
                    border = BorderStroke(1.dp, categoryColor.copy(alpha = 0.3f))
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (event.isFavorite) {
                        Icon(Icons.Default.Star, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        event.date.format(DateTimeFormatter.ofPattern("yyyy.MM.dd")),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            if (event.category == EventCategory.PHOTO) {
                AsyncImage(
                    model = event.content,
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Text(
                    event.content,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }

    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
        DropdownMenuItem(
            text = { Text(if (event.isFavorite) "즐겨찾기 해제" else "즐겨찾기") },
            leadingIcon = { Icon(if (event.isFavorite) Icons.Default.StarBorder else Icons.Default.Star, null) },
            onClick = { onToggleFavorite(); showMenu = false }
        )
        DropdownMenuItem(
            text = { Text("삭제", color = MaterialTheme.colorScheme.error) },
            leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
            onClick = { onDelete(); showMenu = false }
        )
    }
}

@Composable
private fun categoryColor(category: EventCategory): Color = when (category) {
    EventCategory.SAID -> MaterialTheme.colorScheme.primary
    EventCategory.DID -> MaterialTheme.colorScheme.secondary
    EventCategory.PHOTO -> MaterialTheme.colorScheme.tertiary
    EventCategory.OTHER -> MaterialTheme.colorScheme.outline
}

private fun categoryLabel(category: EventCategory) = when (category) {
    EventCategory.SAID -> "한 말"
    EventCategory.DID -> "한 일"
    EventCategory.PHOTO -> "사진"
    EventCategory.OTHER -> "기타"
}

@Composable
private fun EmptyTimeline(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.AutoAwesome, contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primaryContainer)
        Spacer(Modifier.height(16.dp))
        Text("아직 기록이 없어요", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text("카카오톡 대화를 import하거나\n+ 버튼으로 이벤트를 추가해보세요",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun PendingPhotosBanner(count: Int, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(8.dp))
            Text("확인 필요한 사진이 ${count}장 있습니다. 확인하시겠습니까?",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer)
            Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun PendingPhotosDialog(
    photos: List<PendingPhoto>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>, Set<String>) -> Unit
) {
    val selectedUris = remember { mutableStateListOf(*photos.map { it.uri }.toTypedArray()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("확인 필요한 사진") },
        text = {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.heightIn(max = 400.dp)
            ) {
                gridItems(photos) { photo ->
                    val isSelected = selectedUris.contains(photo.uri)
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                if (isSelected) selectedUris.remove(photo.uri)
                                else selectedUris.add(photo.uri)
                            }
                    ) {
                        AsyncImage(
                            model = photo.uri,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                        if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.3f))
                            )
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp)
                            )
                        } else {
                            Icon(
                                Icons.Default.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val accepted = selectedUris.toSet()
                    val rejected = photos.map { it.uri }.toSet() - accepted
                    onConfirm(accepted, rejected)
                }
            ) { Text("적용") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddEventDialog(onDismiss: () -> Unit, onAdd: (LocalDate, EventCategory, String) -> Unit) {
    var date by remember { mutableStateOf(LocalDate.now()) }
    var category by remember { mutableStateOf(EventCategory.DID) }
    var content by remember { mutableStateOf("") }
    var showDatePicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("이벤트 추가") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = date.format(DateTimeFormatter.ofPattern("yyyy.MM.dd")),
                    onValueChange = {},
                    label = { Text("날짜") },
                    readOnly = true,
                    trailingIcon = {
                        IconButton(onClick = { showDatePicker = true }) {
                            Icon(Icons.Default.CalendarToday, null)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(EventCategory.SAID, EventCategory.DID, EventCategory.OTHER).forEach { cat ->
                        FilterChip(
                            selected = category == cat,
                            onClick = { category = cat },
                            label = { Text(categoryLabel(cat)) }
                        )
                    }
                }

                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("내용") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                    shape = RoundedCornerShape(12.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(date, category, content) },
                enabled = content.isNotBlank()
            ) { Text("추가") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )

    if (showDatePicker) {
        val dateState = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    dateState.selectedDateMillis?.let { ms ->
                        date = java.time.Instant.ofEpochMilli(ms)
                            .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                    }
                    showDatePicker = false
                }) { Text("확인") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("취소") } }
        ) { DatePicker(state = dateState) }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// 날짜별 그룹 타임라인 (메인 뷰 교체 컴포저블)
// ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun GroupedTimelineContent(
    events: List<Event>,
    birthDate: LocalDate?,
    searchQuery: String,
    isContextSearch: Boolean,
    contextSearchSort: ContextSearchSort,
    photoRecordsByEventId: Map<String, PhotoRecord>,
    onDayClick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier
) {
    val grouped = remember(events, isContextSearch, contextSearchSort) {
        val byDate = events.groupBy { it.date }
        if (isContextSearch && contextSearchSort == ContextSearchSort.RELEVANCE) {
            byDate.entries.toList()
        } else {
            byDate.entries.sortedByDescending { it.key }
        }
    }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var showJumpButtons by remember { mutableStateOf(false) }
    val currentIndex by remember {
        derivedStateOf {
            when {
                grouped.isEmpty() -> 0
                !listState.canScrollBackward -> 0
                !listState.canScrollForward -> grouped.lastIndex
                else -> listState.firstVisibleItemIndex.coerceIn(0, grouped.lastIndex)
            }
        }
    }
    val showFastScroller = grouped.size >= 8

    LaunchedEffect(searchQuery, isContextSearch, contextSearchSort) {
        if (searchQuery.isNotBlank()) {
            listState.scrollToItem(0)
        }
    }

    LaunchedEffect(listState.isScrollInProgress, showFastScroller) {
        if (!showFastScroller) {
            showJumpButtons = false
            return@LaunchedEffect
        }
        if (listState.isScrollInProgress) {
            showJumpButtons = true
        } else if (showJumpButtons) {
            delay(2500L)
            showJumpButtons = false
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(grouped, key = { it.key.toString() }) { (date, dayEvents) ->
                DailyEventCard(
                    date = date,
                    events = dayEvents,
                    searchQuery = searchQuery,
                    isContextSearch = isContextSearch,
                    photoRecordsByEventId = photoRecordsByEventId,
                    onClick = { onDayClick(date) }
                )
            }
        }

        if (showFastScroller) {
            TimelineFastScroller(
                groupCount = grouped.size,
                currentIndex = currentIndex,
                labelForIndex = { index ->
                    grouped[index.coerceIn(grouped.indices)].key.format(DateTimeFormatter.ofPattern("yyyy. M. d"))
                },
                onJumpToIndex = { index ->
                    coroutineScope.launch {
                        listState.scrollToItem(index.coerceIn(grouped.indices))
                    }
                },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .padding(top = 8.dp, bottom = 96.dp)
            )

            AnimatedVisibility(
                visible = showJumpButtons,
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 18.dp, bottom = 86.dp)
            ) {
                TimelineJumpButtons(
                    onJumpToTop = {
                        coroutineScope.launch { listState.animateScrollToItem(0) }
                    },
                    onJumpToBottom = {
                        coroutineScope.launch { listState.animateScrollToItem(grouped.lastIndex) }
                    }
                )
            }
        }
    }
}

@Composable
private fun TimelineJumpButtons(
    onJumpToTop: () -> Unit,
    onJumpToBottom: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        shape = RoundedCornerShape(999.dp),
        tonalElevation = 3.dp,
        shadowElevation = 3.dp
    ) {
        Column(
            modifier = Modifier.padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            IconButton(
                onClick = onJumpToTop,
                modifier = Modifier.size(34.dp)
            ) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "맨 위로")
            }
            IconButton(
                onClick = onJumpToBottom,
                modifier = Modifier.size(34.dp)
            ) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "맨 아래로")
            }
        }
    }
}

@Composable
private fun TimelineFastScroller(
    groupCount: Int,
    currentIndex: Int,
    labelForIndex: (Int) -> String,
    onJumpToIndex: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var draggingIndex by remember { mutableStateOf<Int?>(null) }

    BoxWithConstraints(
        modifier = modifier.width(30.dp),
        contentAlignment = Alignment.TopEnd
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopEnd
        ) {
            val density = LocalDensity.current
            val trackHeightPx = with(density) { maxHeight.toPx() }
            val handleHeight = 58.dp
            fun indexForY(y: Float): Int {
                val ratio = (y / trackHeightPx).coerceIn(0f, 1f)
                return (ratio * (groupCount - 1)).toInt().coerceIn(0, groupCount - 1)
            }

            val thumbIndex = draggingIndex ?: currentIndex
            val thumbY = if (groupCount <= 1) {
                0.dp
            } else {
                (maxHeight - handleHeight) * (thumbIndex.toFloat() / (groupCount - 1).toFloat())
            }
            val activeIndex = thumbIndex

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(groupCount) {
                        detectVerticalDragGestures(
                            onDragStart = { offset ->
                                val index = indexForY(offset.y)
                                draggingIndex = index
                                onJumpToIndex(index)
                            },
                            onVerticalDrag = { change, _ ->
                                val index = indexForY(change.position.y)
                                draggingIndex = index
                                onJumpToIndex(index)
                            },
                            onDragEnd = { draggingIndex = null },
                            onDragCancel = { draggingIndex = null }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {}

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(y = thumbY)
                    .width(5.dp)
                    .height(handleHeight)
                    .clip(RoundedCornerShape(topStart = 999.dp, bottomStart = 999.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.88f))
            )

            if (draggingIndex != null) {
                Surface(
                    color = MaterialTheme.colorScheme.inverseSurface,
                    shape = RoundedCornerShape(8.dp),
                    tonalElevation = 2.dp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = (-22).dp, y = thumbY + 12.dp)
                ) {
                    Text(
                        labelForIndex(activeIndex),
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun DailyEventCard(
    date: LocalDate,
    events: List<Event>,
    searchQuery: String,
    isContextSearch: Boolean,
    photoRecordsByEventId: Map<String, PhotoRecord>,
    onClick: () -> Unit
) {
    val photos = events.filter { it.category == EventCategory.PHOTO }
    val texts = events.filter { it.category != EventCategory.PHOTO }
    val dateLabel = date.format(DateTimeFormatter.ofPattern("yyyy년 M월 d일"))

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // 날짜 헤더 + 카테고리 pill 뱃지
            val presentCategories = listOf(
                EventCategory.PHOTO, EventCategory.DID, EventCategory.SAID, EventCategory.OTHER
            ).filter { cat -> events.any { it.category == cat } }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    dateLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    presentCategories.forEach { cat -> CategoryPill(cat) }
                }
            }
            Spacer(Modifier.height(8.dp))

            // 사진 미리보기 (최대 4장)
            if (photos.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    photos.take(4).forEachIndexed { idx, event ->
                        val isMissing = photoRecordsByEventId[event.id]?.isMissing == true
                        Box(modifier = Modifier.weight(1f).aspectRatio(1f)) {
                            if (isMissing) {
                                MissingPhotoPlaceholder(
                                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp))
                                )
                            } else {
                                AsyncImage(
                                    model = event.content,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop
                                )
                            }
                            // 4장 이상이면 마지막 썸네일에 "+N" 오버레이
                            if (idx == 3 && photos.size > 4) {
                                Box(
                                    modifier = Modifier.fillMaxSize()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.Black.copy(alpha = 0.5f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "+${photos.size - 4}",
                                        color = Color.White,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                    // 사진이 4장 미만이면 빈 weight 채우기
                    repeat((4 - photos.size).coerceAtLeast(0)) {
                        Spacer(Modifier.weight(1f))
                    }
                }
                if (texts.isNotEmpty()) Spacer(Modifier.height(8.dp))
            }

            // 텍스트 이벤트 요약
            texts.take(2).forEach { event ->
                Text(
                    highlightedSearchAnnotatedString(
                        text = "• ${event.content}",
                        query = searchQuery,
                        enabled = searchQuery.isNotBlank() && !isContextSearch
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (texts.size > 2) {
                Text(
                    "외 ${texts.size - 2}개",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@Composable
private fun MissingPhotoPlaceholder(
    modifier: Modifier = Modifier,
    label: String? = null
) {
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(6.dp)
        ) {
            Icon(
                Icons.Default.BrokenImage,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
            if (label != null) {
                Text(
                    label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// 날짜 상세 다이얼로그 (사진 다중선택 + 학습 제외)
// ──────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DailyDetailDialog(
    date: LocalDate,
    events: List<Event>,
    searchQuery: String,
    isContextSearch: Boolean,
    onDismiss: () -> Unit,
    photoRecordsByEventId: Map<String, PhotoRecord>,
    onDeleteBatch: (List<String>) -> Unit,
    onExcludeBatch: (List<PhotoRecord>, Boolean) -> Unit,
    onToggleFavorite: (Event) -> Unit,
    onSetFavoriteBatch: (List<String>, Boolean) -> Unit,
    onHideTextEvent: (Event) -> Unit,
    onShowDevicePhotos: () -> Unit,
    onPhotoClick: (photos: List<String>, index: Int) -> Unit = { _, _ -> },
    onMissingPhotoClick: () -> Unit = {}
) {
    val photos = events.filter { it.category == EventCategory.PHOTO }
    val texts  = events.filter { it.category != EventCategory.PHOTO }

    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var selectedTextEvent by remember { mutableStateOf<Event?>(null) }
    val isSelectMode = selectedIds.isNotEmpty()

    val selectedPhotoEvents = photos.filter { it.id in selectedIds }
    val selectedPhotoRecords = selectedPhotoEvents.mapNotNull { photoRecordsByEventId[it.id] }
    val selectedExcludeStates = selectedPhotoRecords.map { it.isExcludedFromModel }
    val allSameExcludeState = selectedExcludeStates.toSet().size <= 1
    val currentExcludeState = selectedExcludeStates.firstOrNull() ?: false

    AlertDialog(
        onDismissRequest = {
            if (!isSelectMode) onDismiss()
            else selectedIds = setOf()
        },
        title = {
            if (isSelectMode) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { selectedIds = setOf() }) {
                        Icon(Icons.Default.Close, contentDescription = "선택 취소")
                    }
                    Text("${selectedIds.size}개 선택됨", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    // 즐겨찾기 토글 (전체 즐겨찾기면 해제, 하나라도 아니면 추가)
                    val allFavorited = selectedPhotoEvents.all { it.isFavorite }
                    IconButton(
                        onClick = {
                            onSetFavoriteBatch(selectedIds.toList(), !allFavorited)
                            selectedIds = setOf()
                        }
                    ) {
                        Icon(
                            if (allFavorited) Icons.Default.StarBorder else Icons.Default.Star,
                            contentDescription = if (allFavorited) "즐겨찾기 해제" else "즐겨찾기",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    // 학습 제외 토글 (혼합 상태면 비활성화)
                    IconButton(
                        onClick = {
                            val newExcluded = !currentExcludeState
                            onExcludeBatch(selectedPhotoRecords, newExcluded)
                            selectedIds = setOf()
                        },
                        enabled = allSameExcludeState &&
                            selectedIds.isNotEmpty() &&
                            selectedPhotoRecords.size == selectedPhotoEvents.size
                    ) {
                        Icon(
                            if (currentExcludeState) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (currentExcludeState) "학습 포함" else "학습 제외",
                            tint = if (allSameExcludeState) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.outline
                        )
                    }
                    // 삭제 버튼 (항상 활성화)
                    IconButton(
                        onClick = {
                            onDeleteBatch(selectedIds.toList())
                            selectedIds = setOf()
                        }
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "삭제",
                            tint = MaterialTheme.colorScheme.error)
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        date.format(DateTimeFormatter.ofPattern("yyyy년 M월 d일")),
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onShowDevicePhotos) { Text("사진+") }
                }
            }
        },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // 작은 날짜 및 사진+ 버튼 제거됨
                // 사진 그리드
                if (photos.isNotEmpty()) {
                    item {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.heightIn(max = 360.dp)
                        ) {
                            gridItems(photos, key = { it.id }) { event ->
                                val isSelected = event.id in selectedIds
                                val photoRecord = photoRecordsByEventId[event.id]
                                val isExcluded = photoRecord?.isExcludedFromModel ?: false
                                val isMissing = photoRecord?.isMissing == true

                                Box(
                                    modifier = Modifier
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .combinedClickable(
                                            onClick = {
                                                if (isSelectMode) {
                                                    selectedIds = if (isSelected)
                                                        selectedIds - event.id
                                                    else selectedIds + event.id
                                                } else {
                                                    if (isMissing) {
                                                        onMissingPhotoClick()
                                                    } else {
                                                        val availablePhotos = photos.filter {
                                                            photoRecordsByEventId[it.id]?.isMissing != true
                                                        }
                                                        onPhotoClick(
                                                            availablePhotos.map { it.content },
                                                            availablePhotos.indexOf(event).coerceAtLeast(0)
                                                        )
                                                    }
                                                }
                                            },
                                            onLongClick = {
                                                selectedIds = selectedIds + event.id
                                            }
                                        )
                                ) {
                                    if (isMissing) {
                                        MissingPhotoPlaceholder(
                                            modifier = Modifier.fillMaxSize(),
                                            label = "원본 없음"
                                        )
                                    } else {
                                        AsyncImage(
                                            model = event.content,
                                            contentDescription = null,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                    }
                                    // 선택 오버레이
                                    if (isSelectMode) {
                                        if (isSelected) {
                                            Box(
                                                modifier = Modifier.fillMaxSize()
                                                    .background(Color.Black.copy(alpha = 0.35f))
                                            )
                                        }
                                        Icon(
                                            if (isSelected) Icons.Default.CheckCircle
                                            else Icons.Default.RadioButtonUnchecked,
                                            contentDescription = null,
                                            tint = if (isSelected) MaterialTheme.colorScheme.primary
                                                   else Color.White,
                                            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                                        )
                                    }
                                    // 학습 제외 마커
                                    if (isExcluded) {
                                        Icon(
                                            Icons.Default.VisibilityOff,
                                            contentDescription = "학습 제외",
                                            tint = Color.White,
                                            modifier = Modifier
                                                .align(Alignment.BottomEnd)
                                                .padding(4.dp)
                                                .size(16.dp)
                                                .background(Color.Black.copy(alpha = 0.5f),
                                                    RoundedCornerShape(4.dp))
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 텍스트 이벤트 목록
                if (texts.isNotEmpty()) {
                    if (photos.isNotEmpty()) {
                        item {
                            HorizontalDivider()
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                    items(texts, key = { it.id }) { event ->
                        var showMenu by remember { mutableStateOf(false) }
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .combinedClickable(
                                    onClick = { selectedTextEvent = event },
                                    onLongClick = { showMenu = true }
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            val color = categoryColor(event.category)
                            AssistChip(
                                onClick = {},
                                label = { Text(categoryLabel(event.category),
                                    style = MaterialTheme.typography.labelSmall) },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = color.copy(alpha = 0.12f),
                                    labelColor = color
                                ),
                                border = BorderStroke(1.dp, color.copy(alpha = 0.3f))
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                highlightedSearchAnnotatedString(
                                    text = event.content,
                                    query = searchQuery,
                                    enabled = searchQuery.isNotBlank() && !isContextSearch
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(if (event.isFavorite) "즐겨찾기 해제" else "즐겨찾기") },
                                leadingIcon = {
                                    Icon(if (event.isFavorite) Icons.Default.StarBorder
                                         else Icons.Default.Star, null)
                                },
                                onClick = { onToggleFavorite(event); showMenu = false }
                            )
                            DropdownMenuItem(
                                text = { Text("숨기기") },
                                leadingIcon = { Icon(Icons.Default.VisibilityOff, null) },
                                onClick = { onHideTextEvent(event); showMenu = false }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!isSelectMode) {
                TextButton(onClick = onDismiss) { Text("닫기") }
            }
        }
    )

    selectedTextEvent?.let { event ->
        EventDetailDialog(
            event = event,
            searchQuery = searchQuery,
            isContextSearch = isContextSearch,
            onDismiss = { selectedTextEvent = null }
        )
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// 카카오 import 다이얼로그 (방 별명 입력 + 기존 방 선택)
// ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun KakaoImportDialog(
    existingRooms: List<KakaoRoom>,
    onDismiss: () -> Unit,
    onSelectFile: (alias: String) -> Unit
) {
    var newAlias by remember { mutableStateOf("") }
    var selectedRoomId by remember { mutableStateOf<String?>(null) }

    val effectiveAlias = if (selectedRoomId != null)
        existingRooms.find { it.id == selectedRoomId }?.roomName ?: ""
    else newAlias

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null) },
        title = { Text("카카오톡 대화 import") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = newAlias,
                    onValueChange = {
                        newAlias = it
                        if (it.isNotEmpty()) selectedRoomId = null
                    },
                    label = { Text("방 별명 (새 방)") },
                    placeholder = { Text("예: 가족 단톡방") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = selectedRoomId == null,
                    singleLine = true,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                )

                if (existingRooms.isNotEmpty()) {
                    Text(
                        "또는 기존 방 선택:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        existingRooms.forEach { room ->
                            FilterChip(
                                selected = selectedRoomId == room.id,
                                onClick = {
                                    if (selectedRoomId == room.id) {
                                        selectedRoomId = null
                                    } else {
                                        selectedRoomId = room.id
                                        newAlias = ""
                                    }
                                },
                                label = { Text(room.roomName) },
                                modifier = Modifier.fillMaxWidth(),
                                leadingIcon = if (selectedRoomId == room.id) ({
                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                }) else null
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSelectFile(effectiveAlias) },
                enabled = effectiveAlias.isNotBlank()
            ) { Text("파일 선택") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        }
    )
}

// ──────────────────────────────────────────────────────────────────────────────
// import 완료 결과 오버레이
// ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun ImportDoneOverlay(info: ImportDoneInfo, onConfirm: () -> Unit, onRetry: (() -> Unit)? = null) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .background(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(20.dp)
                )
                .padding(horizontal = 32.dp, vertical = 28.dp)
        ) {
            Icon(
                if (info.cancelled) Icons.Default.Cancel else Icons.Default.CheckCircle,
                contentDescription = null,
                tint = if (info.cancelled) MaterialTheme.colorScheme.error
                       else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            )
            Text(
                if (info.cancelled) "가져오기 취소됨" else "가져오기 완료",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))

            val minutes = info.elapsedSeconds / 60
            val seconds = info.elapsedSeconds % 60
            val timeStr = if (minutes > 0) "${minutes}분 ${seconds}초" else "${seconds}초"

            listOf(
                "메시지" to "${info.addedMessages}개 저장",
                "이벤트" to "${info.addedEvents}개 추출",
                "소요 시간" to timeStr
            ).forEach { (label, value) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(label, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value, style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium)
                }
            }

            if (info.apiRequests > 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("API 요청", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${info.apiRequests}회 / 토큰 ${"%,d".format(info.totalTokens)}개",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium)
                }
            }
            if (info.failedChunks > 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("실패한 청크", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                    Text("${info.failedChunks}개", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Medium)
                }
            }

            Spacer(Modifier.height(8.dp))
            if (onRetry != null) {
                OutlinedButton(
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("실패한 청크 ${info.failedChunks}개 재시도")
                }
            }
            Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) {
                Text("확인")
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// 설정 선택 메뉴
// ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun SettingsMenuDialog(
    pendingRetryCount: Int,
    pendingPhotoCount: Int,
    onKakaoImport: () -> Unit,
    onRetry: () -> Unit,
    onScan: () -> Unit,
    isScanRunning: Boolean,
    isMissingPhotoCheckRunning: Boolean,
    onCheckMissingPhotos: () -> Unit,
    onPendingPhotos: () -> Unit,
    onHiddenItems: () -> Unit,
    onContextUpdate: () -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
        title = { Text("설정") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = onKakaoImport,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("카카오톡 대화 가져오기", modifier = Modifier.weight(1f))
                }
                HorizontalDivider()
                if (pendingRetryCount > 0) {
                    TextButton(
                        onClick = onRetry,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("대화 분석 재시도 (${pendingRetryCount}건)", modifier = Modifier.weight(1f))
                    }
                    HorizontalDivider()
                }
                TextButton(
                    onClick = onScan,
                    enabled = !isScanRunning,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (isScanRunning) "사진 분석 중..." else "신규 사진 로딩",
                        modifier = Modifier.weight(1f)
                    )
                }
                HorizontalDivider()
                TextButton(
                    onClick = onCheckMissingPhotos,
                    enabled = !isScanRunning && !isMissingPhotoCheckRunning,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(Icons.Default.BrokenImage, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("사진 원본 확인", modifier = Modifier.weight(1f))
                }
                HorizontalDivider()
                if (pendingPhotoCount > 0) {
                    TextButton(
                        onClick = onPendingPhotos,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("확인 필요한 사진 (${pendingPhotoCount}장)", modifier = Modifier.weight(1f))
                    }
                    HorizontalDivider()
                }
                TextButton(
                    onClick = onContextUpdate,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(Icons.Default.Update, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("문맥 업데이트", modifier = Modifier.weight(1f))
                }
                HorizontalDivider()
                TextButton(
                    onClick = onHiddenItems,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(Icons.Default.VisibilityOff, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("숨김 아이템", modifier = Modifier.weight(1f))
                }
                HorizontalDivider()
                TextButton(
                    onClick = onReset,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("앱 데이터 초기화", modifier = Modifier.weight(1f))
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        }
    )
}

// ──────────────────────────────────────────────────────────────────────────────
// 재시도 대화방 선택 다이얼로그
// ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun RetryRoomDialog(
    rooms: List<RetryRoomInfo>,
    onDismiss: () -> Unit,
    onRetry: (roomId: String) -> Unit
) {
    var selectedRoomId by remember { mutableStateOf(rooms.firstOrNull()?.roomId) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Refresh, contentDescription = null) },
        title = { Text("재시도할 대화방 선택") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                rooms.forEach { room ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedRoomId = room.roomId }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedRoomId == room.roomId,
                            onClick = { selectedRoomId = room.roomId }
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(room.roomAlias, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "청크 ${room.chunkCount}개",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { selectedRoomId?.let { onRetry(it) } },
                enabled = selectedRoomId != null
            ) { Text("재시도 시작") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        }
    )
}

// ──────────────────────────────────────────────────────────────────────────────
// 카테고리 pill 뱃지 (날짜카드 헤더용)
// ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun CategoryPill(category: EventCategory) {
    val color = categoryColor(category)
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(50.dp))
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(50.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            categoryLabel(category),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// 이벤트 상세 다이얼로그 (longContent + rawExcerpt)
// ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun EventDetailDialog(
    event: Event,
    searchQuery: String,
    isContextSearch: Boolean,
    onDismiss: () -> Unit
) {
    val categoryColor = categoryColor(event.category)
    val categoryLabel = categoryLabel(event.category)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AssistChip(
                        onClick = {},
                        label = { Text(categoryLabel, style = MaterialTheme.typography.labelSmall) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = categoryColor.copy(alpha = 0.12f),
                            labelColor = categoryColor
                        ),
                        border = BorderStroke(1.dp, categoryColor.copy(alpha = 0.3f))
                    )
                    Text(
                        event.date.format(DateTimeFormatter.ofPattern("yyyy.MM.dd")),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    highlightedSearchAnnotatedString(
                        text = event.content,
                        query = searchQuery,
                        enabled = searchQuery.isNotBlank() && !isContextSearch
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!event.longContent.isNullOrBlank()) {
                    item {
                        Text(
                            highlightedSearchAnnotatedString(
                                text = event.longContent,
                                query = searchQuery,
                                enabled = searchQuery.isNotBlank() && !isContextSearch
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                if (!event.rawExcerpt.isNullOrBlank()) {
                    item {
                        HorizontalDivider()
                        Spacer(Modifier.height(4.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                        ) {
                            Row(modifier = Modifier.padding(12.dp)) {
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .fillMaxHeight()
                                        .background(
                                            MaterialTheme.colorScheme.primary,
                                            androidx.compose.foundation.shape.RoundedCornerShape(2.dp)
                                        )
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    highlightedSearchAnnotatedString(
                                        text = event.rawExcerpt,
                                        query = searchQuery,
                                        enabled = searchQuery.isNotBlank() && !isContextSearch
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("닫기") }
        }
    )
}

@Composable
private fun highlightedSearchAnnotatedString(
    text: String,
    query: String,
    enabled: Boolean
): AnnotatedString {
    if (!enabled || text.isBlank()) return AnnotatedString(text)

    val highlightColor = MaterialTheme.colorScheme.tertiaryContainer
    val highlightTextColor = MaterialTheme.colorScheme.onTertiaryContainer
    val ranges = keywordHighlightRanges(text, query)
    if (ranges.isEmpty()) return AnnotatedString(text)

    return buildAnnotatedString {
        var currentIndex = 0
        for (range in ranges) {
            if (currentIndex < range.first) {
                append(text.substring(currentIndex, range.first))
            }
            withStyle(
                SpanStyle(
                    background = highlightColor,
                    color = highlightTextColor,
                    fontWeight = FontWeight.SemiBold
                )
            ) {
                append(text.substring(range.first, range.last + 1))
            }
            currentIndex = range.last + 1
        }
        if (currentIndex < text.length) {
            append(text.substring(currentIndex))
        }
    }
}

private fun keywordHighlightRanges(text: String, query: String): List<IntRange> {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isBlank()) return emptyList()

    val exactQuoteMatch = Regex("^\"(.+)\"$").find(normalizedQuery)
    val keywords = if (exactQuoteMatch != null) {
        listOf(exactQuoteMatch.groupValues[1]).filter { it.isNotBlank() }
    } else {
        normalizedQuery.split("\\s+".toRegex()).filter { it.isNotBlank() }
    }
    if (keywords.isEmpty()) return emptyList()

    val ranges = mutableListOf<IntRange>()
    for (keyword in keywords) {
        var startIndex = 0
        while (startIndex < text.length) {
            val matchIndex = text.indexOf(keyword, startIndex, ignoreCase = true)
            if (matchIndex < 0) break
            ranges += matchIndex until (matchIndex + keyword.length)
            startIndex = matchIndex + keyword.length
        }
    }

    return ranges
        .sortedBy { it.first }
        .fold(mutableListOf()) { acc, range ->
            val previous = acc.lastOrNull()
            if (previous == null || range.first > previous.last + 1) {
                acc += range
            } else {
                acc[acc.lastIndex] = previous.first..maxOf(previous.last, range.last)
            }
            acc
        }
}

// ──────────────────────────────────────────────────────────────────────────────
// 사진 전체화면 뷰어
// ──────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FullscreenPhotoViewer(
    photos: List<String>,
    initialIndex: Int,
    onDismiss: () -> Unit
) {
    val pagerState = rememberPagerState(initialPage = initialIndex) { photos.size }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                AsyncImage(
                    model = photos[page],
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }

            // 닫기 버튼 (좌상단)
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "닫기",
                    tint = Color.White
                )
            }

            // 인덱스 표시 (우상단)
            Text(
                text = "${pagerState.currentPage + 1} / ${photos.size}",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
            )
        }
    }
}

@Composable
private fun DayPhotosDialog(
    photos: List<String>,
    onDismiss: () -> Unit,
    onPhotoClick: (Int) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("사진+") },
        text = {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.heightIn(max = 420.dp)
            ) {
                gridItems(photos.indices.toList()) { index ->
                    AsyncImage(
                        model = photos[index],
                        contentDescription = null,
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onPhotoClick(index) },
                        contentScale = ContentScale.Crop
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } }
    )
}

@Composable
private fun HiddenItemsDialog(
    events: List<Event>,
    onDismiss: () -> Unit,
    onRestore: (List<String>) -> Unit
) {
    var selectedIds by remember(events) { mutableStateOf(setOf<String>()) }
    val sortedEvents = remember(events) { events.sortedBy { it.date } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("숨김 아이템") },
        text = {
            if (events.isEmpty()) {
                Text("숨김 항목이 없습니다")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(sortedEvents, key = { it.id }) { event ->
                        val selected = event.id in selectedIds
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else Color.Transparent
                                )
                                .clickable {
                                    selectedIds = if (selected) selectedIds - event.id else selectedIds + event.id
                                }
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${event.date} · ${categoryLabel(event.category)}\n${event.content}", modifier = Modifier.weight(1f))
                            Icon(
                                if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = if (selected) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onRestore(selectedIds.toList())
                    selectedIds = setOf()
                },
                enabled = selectedIds.isNotEmpty()
            ) { Text("복구") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("닫기") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimelineSearchDialog(
    query: String,
    isContextSearch: Boolean,
    onQueryChange: (String) -> Unit,
    onContextSearchChange: (Boolean) -> Unit,
    onSearch: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Search, contentDescription = null) },
        title = { Text("타임라인 검색") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    label = { Text("검색어 입력") },
                    placeholder = { Text("예: 병원 방문, \"정확한 문구\"") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { onQueryChange("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "지우기")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = if (isContextSearch)
                        "🔍 문맥 검색: 의미가 비슷한 이벤트를 찾습니다."
                    else
                        "🔤 키워드 검색: 모든 단어가 포함된 이벤트를 찾습니다.\n(\"따옴표\"로 묶으면 정확한 문구를 검색합니다.)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onContextSearchChange(!isContextSearch) }
                ) {
                    Switch(
                        checked = isContextSearch,
                        onCheckedChange = onContextSearchChange
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("문맥 포함 (의미 기반 검색)", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "On-Device AI로 의미가 유사한 이벤트 검색",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSearch, enabled = query.isNotBlank()) {
                Text("검색", color = if (query.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("닫기") }
        }
    )
}
