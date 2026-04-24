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
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
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
                        var permissionsGranted by remember { mutableStateOf(false) }
                        val startDest = if (initialized) Screen.Timeline.route else Screen.Init.route

                        val permissions = buildList {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                                add(Manifest.permission.READ_MEDIA_IMAGES)
                            else
                                add(Manifest.permission.READ_EXTERNAL_STORAGE)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                                add(Manifest.permission.POST_NOTIFICATIONS)
                        }

                        val permLauncher = rememberLauncherForActivityResult(
                            ActivityResultContracts.RequestMultiplePermissions()
                        ) { results ->
                            permissionsGranted = results.values.any { it }
                        }

                        LaunchedEffect(Unit) {
                            permLauncher.launch(permissions.toTypedArray())
                        }

                        if (permissionsGranted || true) {
                            AppNavigation(startDestination = startDest)
                        }
                    }
                }
            }
        }
    }
}
