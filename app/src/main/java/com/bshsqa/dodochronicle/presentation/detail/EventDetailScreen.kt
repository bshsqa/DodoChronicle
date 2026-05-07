package com.bshsqa.dodochronicle.presentation.detail

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.bshsqa.dodochronicle.domain.model.Event
import com.bshsqa.dodochronicle.domain.model.EventCategory
import com.bshsqa.dodochronicle.domain.model.PhotoRecord
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventDetailScreen(
    eventId: String,
    onBack: () -> Unit,
    viewModel: EventDetailViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(state.isDeleted) {
        if (state.isDeleted) onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("이벤트 상세") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::toggleFavorite) {
                        Icon(
                            if (state.event?.isFavorite == true) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = "즐겨찾기",
                            tint = if (state.event?.isFavorite == true) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(onClick = viewModel::deleteEvent) {
                        Icon(Icons.Default.Delete, contentDescription = "삭제",
                            tint = MaterialTheme.colorScheme.error)
                    }
                }
            )
        }
    ) { padding ->
        state.event?.let { event ->
            EventDetailContent(
                event = event,
                photos = state.photos,
                onRemovePhoto = viewModel::removePhoto,
                modifier = Modifier.fillMaxSize().padding(padding)
            )
        } ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun EventDetailContent(
    event: Event,
    photos: List<PhotoRecord>,
    onRemovePhoto: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val (icon, label) = categoryIconLabel(event.category)
                    AssistChip(
                        onClick = {},
                        label = { Text(label) },
                        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                    if (event.isFavorite) {
                        Icon(Icons.Default.Star, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    event.date.format(DateTimeFormatter.ofPattern("yyyy년 M월 d일")),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(Modifier.height(4.dp))
                val sourceLabel = when (event.source.name) {
                    "KAKAO" -> "카카오톡에서"
                    "PHOTO" -> "사진 기록"
                    "MANUAL" -> "직접 추가"
                    else -> ""
                }
                Text(sourceLabel, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
            }
        }

        if (event.category != EventCategory.PHOTO) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("내용", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text(event.content, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }

        if (photos.isNotEmpty()) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("사진 (${photos.size}장)", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(photos) { photo ->
                            PhotoItem(photo = photo, onRemove = { onRemovePhoto(photo.id) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PhotoItem(photo: PhotoRecord, onRemove: () -> Unit) {
    var showConfirm by remember { mutableStateOf(false) }

    Box(modifier = Modifier.size(160.dp)) {
        AsyncImage(
            model = photo.localUri,
            contentDescription = null,
            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.Crop
        )
        IconButton(
            onClick = { showConfirm = true },
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            Icon(Icons.Default.RemoveCircle, contentDescription = "사진 제거",
                tint = MaterialTheme.colorScheme.error)
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("사진 제거") },
            text = { Text("이 사진을 타임라인에서 제거할까요?\n기기의 사진은 삭제되지 않습니다.") },
            confirmButton = {
                TextButton(onClick = { onRemove(); showConfirm = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text("제거")
                }
            },
            dismissButton = { TextButton(onClick = { showConfirm = false }) { Text("취소") } }
        )
    }
}

private fun categoryIconLabel(category: EventCategory) = when (category) {
    EventCategory.SAID -> Icons.Default.RecordVoiceOver to "한 말"
    EventCategory.DID -> Icons.Default.EmojiEvents to "한 일"
    EventCategory.PHOTO -> Icons.Default.PhotoCamera to "사진"
    EventCategory.OTHER -> Icons.AutoMirrored.Filled.Notes to "기타"
}
