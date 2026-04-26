package com.bshsqa.dodochronicle.presentation.init

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.bshsqa.dodochronicle.domain.model.Gender
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InitScreen(viewModel: InitViewModel, onInitComplete: () -> Unit) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(state.step) {
        if (state.step is InitStep.Done) onInitComplete()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = state.step,
            transitionSpec = {
                slideInHorizontally { it } + fadeIn() togetherWith
                        slideOutHorizontally { -it } + fadeOut()
            },
            label = "init_step"
        ) { step ->
            when (step) {
                is InitStep.ChildInfo -> ChildInfoStep(state, viewModel)
                is InitStep.Scanning -> ScanningStep(state, viewModel)
                is InitStep.ClusterSelect -> ClusterSelectStep(state, viewModel)
                is InitStep.Done -> Box(Modifier.fillMaxSize())
            }
        }
    }

    state.error?.let { err ->
        AlertDialog(
            onDismissRequest = viewModel::dismissError,
            title = { Text("오류") },
            text = { Text(err) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissError) { Text("확인") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChildInfoStep(state: InitUiState, vm: InitViewModel) {
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { vm.setReferencePhoto(it.toString()) } }

    var showDatePicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Spacer(Modifier.height(32.dp))
        Icon(
            imageVector = Icons.Default.ChildCare,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp)
        )
        Text("아이 정보 입력", style = MaterialTheme.typography.headlineMedium)
        Text(
            "사진 분류를 시작하기 전에\n아이의 기본 정보를 알려주세요",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { photoPicker.launch("image/*") },
            contentAlignment = Alignment.Center
        ) {
            if (state.referencePhotoUri.isNotBlank()) {
                AsyncImage(
                    model = state.referencePhotoUri,
                    contentDescription = "아이 사진",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.AddAPhoto, contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text("사진 선택", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        OutlinedTextField(
            value = state.childName,
            onValueChange = vm::setChildName,
            label = { Text("아이 이름") },
            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        OutlinedTextField(
            value = state.birthDate?.format(DateTimeFormatter.ofPattern("yyyy년 M월 d일")) ?: "",
            onValueChange = {},
            label = { Text("생년월일") },
            leadingIcon = { Icon(Icons.Default.Cake, contentDescription = null) },
            trailingIcon = {
                IconButton(onClick = { showDatePicker = true }) {
                    Icon(Icons.Default.CalendarToday, contentDescription = "날짜 선택")
                }
            },
            modifier = Modifier.fillMaxWidth(),
            readOnly = true,
            shape = RoundedCornerShape(12.dp)
        )

        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                "성별",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            @OptIn(ExperimentalMaterial3Api::class)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = state.gender == Gender.MALE,
                    onClick = { vm.setGender(Gender.MALE) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    icon = {}
                ) {
                    Text("남아")
                }
                SegmentedButton(
                    selected = state.gender == Gender.FEMALE,
                    onClick = { vm.setGender(Gender.FEMALE) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    icon = {}
                ) {
                    Text("여아")
                }
            }
        }

        Spacer(Modifier.weight(1f))

        Button(
            onClick = vm::startScanning,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(12.dp),
            enabled = state.childName.isNotBlank()
                    && state.birthDate != null
                    && state.referencePhotoUri.isNotBlank()
                    && state.gender != null
        ) {
            Icon(Icons.Default.Search, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("사진 분류 시작", style = MaterialTheme.typography.titleMedium)
        }
    }

    if (showDatePicker) {
        val dateState = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    dateState.selectedDateMillis?.let { ms ->
                        val date = java.time.Instant.ofEpochMilli(ms)
                            .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                        vm.setBirthDate(date)
                    }
                    showDatePicker = false
                }) { Text("확인") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("취소") } }
        ) { DatePicker(state = dateState) }
    }
}

@Composable
private fun ScanningStep(state: InitUiState, vm: InitViewModel) {
    val progress = if (state.totalCount > 0) state.scannedCount.toFloat() / state.totalCount else 0f
    val percent = (progress * 100).toInt()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) { /* 스캔 중 배경 제스처 차단 */ }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.FaceRetouchingNatural,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(32.dp))
            Text("사진 분석 중...", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "${state.scannedCount} / ${state.totalCount} 장 완료 ($percent%)",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
                strokeCap = StrokeCap.Round
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "얼굴 인식 중입니다. 잠시 기다려주세요.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(32.dp))
            OutlinedButton(onClick = vm::cancelScanning) {
                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("취소")
            }
        }
    }
}

@Composable
private fun ClusterSelectStep(state: InitUiState, vm: InitViewModel) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(32.dp))
        Text("아이 그룹 선택", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "인식된 인물 그룹에서 아이의 그룹을 선택해주세요.\n여러 그룹을 선택할 수 있습니다.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(state.clusters) { cluster ->
                ClusterCard(
                    cluster = cluster,
                    isSelected = cluster.id in state.selectedClusterIds,
                    onClick = { vm.toggleCluster(cluster.id) }
                )
            }
        }

        Button(
            onClick = vm::confirmClusters,
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).height(52.dp),
            shape = RoundedCornerShape(12.dp),
            enabled = state.selectedClusterIds.isNotEmpty()
        ) {
            Icon(Icons.Default.Check, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("선택 완료 (${state.selectedClusterIds.size}개 그룹)", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun ClusterCard(cluster: ClusterUiModel, isSelected: Boolean, onClick: () -> Unit) {
    val border = if (isSelected)
        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
    else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)

    Card(
        modifier = Modifier.aspectRatio(1f).clickable { onClick() },
        border = border,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Box(Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = false
            ) {
                items(cluster.previewUris.take(9)) { uri ->
                    AsyncImage(
                        model = uri,
                        contentDescription = null,
                        modifier = Modifier.aspectRatio(1f),
                        contentScale = ContentScale.Crop
                    )
                }
            }
            if (isSelected) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp)
                        .size(24.dp)
                        .background(MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Check, contentDescription = "선택됨",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(16.dp))
                }
            }
            Box(
                Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f))
                    .padding(4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("${cluster.count}장", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}
