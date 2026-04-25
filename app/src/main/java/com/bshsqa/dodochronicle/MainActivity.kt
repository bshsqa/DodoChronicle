package com.bshsqa.dodochronicle

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.bshsqa.dodochronicle.presentation.navigation.AppNavigation
import com.bshsqa.dodochronicle.presentation.navigation.Screen
import com.bshsqa.dodochronicle.presentation.theme.DodoTheme
import com.bshsqa.dodochronicle.worker.PhotoSyncWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

private val KEY_INITIALIZED = booleanPreferencesKey("initialized")

private fun photoPermission() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
    Manifest.permission.READ_MEDIA_IMAGES
else
    Manifest.permission.READ_EXTERNAL_STORAGE

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var dataStore: DataStore<Preferences>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        lifecycleScope.launch {
            val initialized = dataStore.data.map { it[KEY_INITIALIZED] ?: false }.firstOrNull() ?: false
            if (initialized) PhotoSyncWorker.schedule(applicationContext)

            setContent {
                DodoTheme {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        val context = LocalContext.current
                        val lifecycleOwner = context as LifecycleOwner

                        val startDest = if (initialized) Screen.Timeline.route else Screen.Init.route

                        var permissionsGranted by remember {
                            mutableStateOf(
                                ContextCompat.checkSelfPermission(context, photoPermission()) == PackageManager.PERMISSION_GRANTED
                            )
                        }

                        // resume 시 권한 상태 재확인 (사용자가 시스템 설정에서 허용하고 돌아올 때)
                        DisposableEffect(lifecycleOwner) {
                            val observer = LifecycleEventObserver { _, event ->
                                if (event == Lifecycle.Event.ON_RESUME) {
                                    permissionsGranted = ContextCompat.checkSelfPermission(
                                        context, photoPermission()
                                    ) == PackageManager.PERMISSION_GRANTED
                                }
                            }
                            lifecycleOwner.lifecycle.addObserver(observer)
                            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                        }

                        val permissions = buildList {
                            add(photoPermission())
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                                add(Manifest.permission.POST_NOTIFICATIONS)
                        }

                        val permLauncher = rememberLauncherForActivityResult(
                            ActivityResultContracts.RequestMultiplePermissions()
                        ) { results ->
                            permissionsGranted = results[photoPermission()] == true
                        }

                        LaunchedEffect(Unit) {
                            if (!permissionsGranted) permLauncher.launch(permissions.toTypedArray())
                        }

                        if (permissionsGranted) {
                            AppNavigation(startDestination = startDest)
                        } else {
                            PermissionDeniedScreen(
                                onRequestPermission = { permLauncher.launch(permissions.toTypedArray()) },
                                onOpenSettings = {
                                    context.startActivity(
                                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                            data = Uri.fromParts("package", context.packageName, null)
                                        }
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionDeniedScreen(
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "사진 접근 권한이 필요합니다",
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "아이 사진 인식과 타임라인 구성을 위해\n갤러리 접근 권한이 필요합니다.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = onRequestPermission, modifier = Modifier.fillMaxWidth()) {
            Text("권한 허용하기")
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
            Text("앱 설정에서 허용하기")
        }
    }
}
