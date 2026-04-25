package com.bshsqa.dodochronicle.presentation.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onNavigateToInit: () -> Unit,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is SettingsEvent.NavigateToInit -> onNavigateToInit()
            }
        }
    }

    var showReinitDialog by remember { mutableStateOf(false) }
    var showClearAllDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("설정") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "뒤로")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            SettingsItem(
                icon = Icons.Default.Refresh,
                title = "초기화 재실행",
                subtitle = "아이 정보와 모든 데이터를 삭제하고 처음부터 다시 시작합니다",
                onClick = { showReinitDialog = true },
                tint = MaterialTheme.colorScheme.primary
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingsItem(
                icon = Icons.Default.DeleteForever,
                title = "데이터 전체 초기화",
                subtitle = "앱의 모든 저장 데이터를 완전히 삭제합니다. 되돌릴 수 없습니다",
                onClick = { showClearAllDialog = true },
                tint = MaterialTheme.colorScheme.error
            )
        }

        if (uiState.isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }

    if (showReinitDialog) {
        AlertDialog(
            onDismissRequest = { showReinitDialog = false },
            icon = { Icon(Icons.Default.Refresh, contentDescription = null) },
            title = { Text("초기화 재실행") },
            text = { Text("아이 정보, 사진 기록, 카카오톡 이벤트, 대화 데이터가 모두 삭제됩니다.\n초기화 마법사부터 다시 시작합니다.") },
            confirmButton = {
                TextButton(
                    onClick = { showReinitDialog = false; viewModel.clearAll() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                ) { Text("초기화") }
            },
            dismissButton = {
                TextButton(onClick = { showReinitDialog = false }) { Text("취소") }
            }
        )
    }

    if (showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            icon = {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("데이터 전체 초기화") },
            text = { Text("앱에 저장된 모든 데이터가 영구적으로 삭제됩니다.\n\n이 작업은 되돌릴 수 없습니다.") },
            confirmButton = {
                TextButton(
                    onClick = { showClearAllDialog = false; viewModel.clearAll() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("모두 삭제") }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllDialog = false }) { Text("취소") }
            }
        )
    }
}

@Composable
private fun SettingsItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = tint)
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}
