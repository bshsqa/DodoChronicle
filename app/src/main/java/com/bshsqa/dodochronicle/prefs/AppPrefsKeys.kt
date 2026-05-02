package com.bshsqa.dodochronicle.prefs

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey

object AppPrefsKeys {
    val INITIALIZED = booleanPreferencesKey("initialized")
    val INITIAL_PHOTO_SYNC_CUTOFF_AT = longPreferencesKey("initial_photo_sync_cutoff_at")
    val TEXT_EMBEDDING_MODEL_VERSION = longPreferencesKey("text_embedding_model_version")
}
