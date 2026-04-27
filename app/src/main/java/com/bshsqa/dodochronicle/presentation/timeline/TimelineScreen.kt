package com.bshsqa.dodochronicle.presentation.timeline

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.bshsqa.dodochronicle.domain.model.Event
import com.bshsqa.dodochronicle.domain.model.EventCategory
import com.bshsqa.dodochronicle.domain.model.KakaoRoom
import com.bshsqa.dodochronicle.domain.model.PhotoRecord
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

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
    var showResetDialog by remember { mutableStateOf(false) }
    var showPendingDialog by remember { mutableStateOf(false) }
    var selectedDetailDate by remember { mutableStateOf<LocalDate?>(null) }
    var kakaoImportAlias by remember { mutableStateOf("") }

    LaunchedEffect(state.needsInit) {
        if (state.needsInit) onNeedsInit()
    }

    val kakaoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.importKakao(it, kakaoImportAlias) }
    }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.snackbar) {
        state.snackbar?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissSnackbar()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.childName.ifBlank { "DodoChronicle" }) },
                actions = {
                    IconButton(onClick = { showKakaoMenu = true }) {
                        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "카카오 import")
                    }
                    IconButton(onClick = viewModel::toggleFavoriteFilter) {
                        Icon(
                            if (state.onlyFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = "즐겨찾기 필터",
                            tint = if (state.onlyFavorite) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(onClick = { showResetDialog = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "설정")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ) {
                Icon(Icons.Default.Add, contentDescription = "이벤트 추가",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer)
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
                        .background(Color.Black.copy(alpha = 0.4f)),
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
                    }
                }
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
            onDismiss = { selectedDetailDate = null },
            onDeleteBatch = { ids ->
                viewModel.deletePhotoEventsBatch(ids)
                selectedDetailDate = null
            },
            onExcludeBatch = { records, excluded ->
                viewModel.setExcludeFromModelBatch(records, excluded)
            },
            onToggleFavorite = viewModel::toggleFavorite,
            onSetFavoriteBatch = { ids, fav -> viewModel.setFavoriteBatch(ids, fav) }
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

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("앱 데이터 초기화") },
            text = { Text("앱 데이터를 초기화합니다.\n갤러리 사진은 변경되지 않습니다.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetDialog = false
                        viewModel.resetApp()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("초기화")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text("취소") }
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
    photos: List<com.bshsqa.dodochronicle.domain.usecase.SyncNewPhotosUseCase.PendingPhoto>,
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
    onDayClick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier
) {
    val grouped = remember(events) { events.groupBy { it.date }.toSortedMap(compareByDescending { it }) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(grouped.keys.toList(), key = { it.toString() }) { date ->
            val dayEvents = grouped[date] ?: emptyList()
            DailyEventCard(
                date = date,
                events = dayEvents,
                onClick = { onDayClick(date) }
            )
        }
    }
}

@Composable
private fun DailyEventCard(
    date: LocalDate,
    events: List<Event>,
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
            // 날짜 헤더
            Text(
                dateLabel,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))

            // 사진 미리보기 (최대 4장)
            if (photos.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    photos.take(4).forEachIndexed { idx, event ->
                        Box(modifier = Modifier.weight(1f).aspectRatio(1f)) {
                            AsyncImage(
                                model = event.content,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
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
                    "• ${event.content}",
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

// ──────────────────────────────────────────────────────────────────────────────
// 날짜 상세 다이얼로그 (사진 다중선택 + 학습 제외)
// ──────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DailyDetailDialog(
    date: LocalDate,
    events: List<Event>,
    onDismiss: () -> Unit,
    onDeleteBatch: (List<String>) -> Unit,
    onExcludeBatch: (List<PhotoRecord>, Boolean) -> Unit,
    onToggleFavorite: (Event) -> Unit,
    onSetFavoriteBatch: (List<String>, Boolean) -> Unit
) {
    val photos = events.filter { it.category == EventCategory.PHOTO }
    val texts  = events.filter { it.category != EventCategory.PHOTO }

    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var selectedTextEvent by remember { mutableStateOf<Event?>(null) }
    val isSelectMode = selectedIds.isNotEmpty()

    // 선택된 사진들의 PhotoRecord를 얻으려면 UI상에서 event.content(uri)로 대리
    // 실제 PhotoRecord 모델은 여기서 직접 접근 불가(VM을 통해야 함)
    // → 단순화: exclude 판단은 Event에 없으므로, 선택 시 isExcluded 상태를 별도 mutableStateMap으로 관리
    val excludedEventIds = remember { mutableStateMapOf<String, Boolean>() }

    val selectedPhotoEvents = photos.filter { it.id in selectedIds }
    val selectedExcludeStates = selectedPhotoEvents.map { excludedEventIds[it.id] ?: false }
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
                            val fakeRecords = selectedPhotoEvents.map { ev ->
                                PhotoRecord(
                                    id = ev.id, eventId = ev.id,
                                    localUri = ev.content, takenAt = 0L,
                                    isExcludedFromModel = currentExcludeState
                                )
                            }
                            val newExcluded = !currentExcludeState
                            onExcludeBatch(fakeRecords, newExcluded)
                            selectedPhotoEvents.forEach { excludedEventIds[it.id] = newExcluded }
                            selectedIds = setOf()
                        },
                        enabled = allSameExcludeState && selectedIds.isNotEmpty()
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
                Text(date.format(DateTimeFormatter.ofPattern("yyyy년 M월 d일")))
            }
        },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                                val isExcluded = excludedEventIds[event.id] ?: false

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
                                                }
                                            },
                                            onLongClick = {
                                                selectedIds = selectedIds + event.id
                                            }
                                        )
                                ) {
                                    AsyncImage(
                                        model = event.content,
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
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
                    item {
                        HorizontalDivider()
                        Spacer(Modifier.height(4.dp))
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
                            Text(event.content,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis)
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
        EventDetailDialog(event = event, onDismiss = { selectedTextEvent = null })
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
// 이벤트 상세 다이얼로그 (longContent + rawExcerpt)
// ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun EventDetailDialog(event: Event, onDismiss: () -> Unit) {
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
                Text(event.content, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!event.longContent.isNullOrBlank()) {
                    item {
                        Text(
                            event.longContent,
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
                                    event.rawExcerpt,
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
