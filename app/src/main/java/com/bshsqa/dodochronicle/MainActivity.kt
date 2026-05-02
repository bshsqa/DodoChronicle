package com.bshsqa.dodochronicle

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.bshsqa.dodochronicle.prefs.AppPrefsKeys
import com.bshsqa.dodochronicle.presentation.navigation.AppNavigation
import com.bshsqa.dodochronicle.presentation.navigation.Screen
import com.bshsqa.dodochronicle.presentation.theme.DodoTheme
import com.bshsqa.dodochronicle.worker.PhotoSyncWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var dataStore: DataStore<Preferences>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            DodoTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var permissionsGranted by remember { mutableStateOf(false) }
                    var initialized by remember { mutableStateOf<Boolean?>(null) }

                    LaunchedEffect(Unit) {
                        val isInitialized = dataStore.data
                            .map { it[AppPrefsKeys.INITIALIZED] ?: false }
                            .firstOrNull() ?: false
                        initialized = isInitialized
                        if (isInitialized) {
                            PhotoSyncWorker.schedule(applicationContext)
                        }
                    }

                    val permissions = buildList {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            add(Manifest.permission.READ_MEDIA_IMAGES)
                            add(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            add(Manifest.permission.READ_EXTERNAL_STORAGE)
                        }
                    }

                    val permissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions()
                    ) { results ->
                        val photoPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            Manifest.permission.READ_MEDIA_IMAGES
                        } else {
                            Manifest.permission.READ_EXTERNAL_STORAGE
                        }
                        permissionsGranted = results[photoPermission] == true
                    }

                    LaunchedEffect(Unit) {
                        permissionLauncher.launch(permissions.toTypedArray())
                    }

                    when {
                        initialized == null -> Unit
                        permissionsGranted -> {
                            val startDestination = if (initialized == true) {
                                Screen.Timeline.route
                            } else {
                                Screen.Init.route
                            }
                            AppNavigation(startDestination = startDestination)
                        }
                        else -> {
                            Text(
                                text = "사진 접근 권한이 필요합니다.\n설정에서 권한을 허용해주세요.",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(32.dp),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            }
        }
    }
}
