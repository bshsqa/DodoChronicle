package com.bshsqa.dodochronicle.presentation.timeline

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.bshsqa.dodochronicle.domain.model.Event
import com.bshsqa.dodochronicle.domain.model.EventCategory
import com.bshsqa.dodochronicle.domain.usecase.SyncNewPhotosUseCase
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

private val DATE_FMT_YEAR = DateTimeFormatter.ofPattern("yyyy")
private val DATE_FMT_MONTH = DateTimeFormatter.ofPattern("yy.MM")
private val DATE_FMT_WEEK = DateTimeFormatter.ofPattern("MM/dd")
private val DATE_FMT_DAY = DateTimeFormatter.ofPattern("d일")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineScreen(
    viewModel: TimelineViewModel = hiltViewModel(),
    onEventClick: (String) -> Unit
) {
    val state by viewModel.state.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var showKakaoMenu by remember { mutableStateOf(false) }

    val kakaoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.importKakao(it) }
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
                        Icon(Icons.Default.Chat, contentDescription = "카카오 import")
                    }
                    IconButton(onClick = viewModel::toggleFavoriteFilter) {
                        Icon(
                            if (state.onlyFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = "즐겨찾기 필터",
                            tint = if (state.onlyFavorite) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                        )
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
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // 카테고리 필터 칩
            CategoryFilterRow(
                selected = state.filterCategory,
                onSelect = viewModel::setFilterCategory
            )

            if (state.events.isEmpty()) {
                EmptyTimeline(modifier = Modifier.weight(1f))
            } else {
                TimelineContent(
                    events = state.events,
                    birthDate = state.birthDate,
                    onEventClick = onEventClick,
                    onToggleFavorite = viewModel::toggleFavorite,
                    onDelete = viewModel::deleteEvent,
                    modifier = Modifier.weight(1f)
                )
            }

            // 확인 대기 사진 배너
            if (state.pendingPhotos.isNotEmpty()) {
                PendingPhotosBanner(
                    count = state.pendingPhotos.size,
                    onConfirm = { viewModel.confirmPendingPhoto(state.pendingPhotos.first(), true) },
                    onReject = { viewModel.confirmPendingPhoto(state.pendingPhotos.first(), false) }
                )
            }
        }
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
        AlertDialog(
            onDismissRequest = { showKakaoMenu = false },
            icon = { Icon(Icons.Default.Chat, contentDescription = null) },
            title = { Text("카카오톡 대화 import") },
            text = { Text(".txt 파일을 선택하면 대화를 분석하여 이벤트를 추출합니다.") },
            confirmButton = {
                TextButton(onClick = { kakaoLauncher.launch("text/*"); showKakaoMenu = false }) {
                    Text("파일 선택")
                }
            },
            dismissButton = {
                TextButton(onClick = { showKakaoMenu = false }) { Text("취소") }
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
private fun TimelineContent(
    events: List<Event>,
    birthDate: LocalDate?,
    onEventClick: (String) -> Unit,
    onToggleFavorite: (Event) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var scale by remember { mutableFloatStateOf(1f) }
    val density = LocalDensity.current
    val today = LocalDate.now()
    val startDate = birthDate ?: (today.minusYears(3))
    val totalDays = ChronoUnit.DAYS.between(startDate, today).toFloat().coerceAtLeast(1f)

    // 초기 offsetY: 오늘(타임라인 맨 아래)이 화면 하단에 오도록 설정
    val baseHeightInitial = with(density) { 2000.dp.toPx() }
    var offsetY by remember { mutableFloatStateOf(-(baseHeightInitial - with(density) { 400.dp.toPx() })) }
    val zoomLevel by remember { derivedStateOf { scaleToZoom(scale) } }

    val timelineBarWidth = 56.dp

    Box(modifier = modifier
        .fillMaxSize()
        .pointerInput(Unit) {
            awaitEachGesture {
                var lastPan = Offset.Zero
                var lastZoom = 1f
                awaitFirstDown(requireUnconsumed = false)
                do {
                    val event = awaitPointerEvent()
                    val zoom = event.calculateZoom()
                    val pan = event.calculatePan()
                    if (zoom != 1f || pan != Offset.Zero) {
                        scale = (scale * zoom).coerceIn(0.2f, 12f)
                        offsetY = (offsetY + pan.y)
                        event.changes.forEach { it.consume() }
                    }
                } while (event.changes.any { it.pressed })
            }
        }
    ) {
        val baseHeightPx = baseHeightInitial * scale
        val clampedOffset = offsetY.coerceIn(-baseHeightPx + with(density) { 200.dp.toPx() }, 0f)

        Row(modifier = Modifier.fillMaxSize()) {
            // 이벤트 카드 영역 (왼쪽)
            Box(
                modifier = Modifier.weight(1f).fillMaxHeight()
            ) {
                events.forEach { event ->
                    val daysSinceBirth = ChronoUnit.DAYS.between(startDate, event.date).toFloat()
                    val fraction = (daysSinceBirth / totalDays).coerceIn(0f, 1f)
                    val yPx = fraction * baseHeightPx + clampedOffset

                    Box(modifier = Modifier
                        .offset { IntOffset(0, yPx.toInt()) }
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        EventCard(
                            event = event,
                            onClick = { onEventClick(event.id) },
                            onToggleFavorite = { onToggleFavorite(event) },
                            onDelete = { onDelete(event.id) }
                        )
                    }
                }
            }

            // 타임라인 바 (오른쪽)
            TimelineBar(
                modifier = Modifier.width(timelineBarWidth).fillMaxHeight(),
                startDate = startDate,
                today = today,
                totalDays = totalDays,
                scale = scale,
                offsetY = clampedOffset,
                baseHeightPx = baseHeightPx,
                zoomLevel = zoomLevel,
                events = events
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
    scale: Float,
    offsetY: Float,
    baseHeightPx: Float,
    zoomLevel: ZoomLevel,
    events: List<Event>
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val eventDotColor = MaterialTheme.colorScheme.secondary

    Canvas(modifier = modifier.background(surfaceVariant.copy(alpha = 0.3f))) {
        val barX = size.width / 2f
        val topY = offsetY
        val bottomY = topY + baseHeightPx

        // 메인 타임라인 선
        drawLine(
            color = primaryColor.copy(alpha = 0.4f),
            start = Offset(barX, 0f),
            end = Offset(barX, size.height),
            strokeWidth = 3f,
            cap = StrokeCap.Round
        )

        // 날짜 레이블 및 눈금
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

        // 이벤트 점
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
                    border = AssistChipDefaults.assistChipBorder(enabled = true, borderColor = categoryColor.copy(alpha = 0.3f))
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
private fun PendingPhotosBanner(count: Int, onConfirm: () -> Unit, onReject: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.HelpOutline, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(8.dp))
            Text("확인 필요한 사진 ${count}장",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer)
            TextButton(onClick = onReject) { Text("아니오") }
            TextButton(onClick = onConfirm) { Text("맞아요") }
        }
    }
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
