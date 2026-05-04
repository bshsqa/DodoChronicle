package com.bshsqa.dodochronicle.prefs

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey

object AppPrefsKeys {
    val INITIALIZED = booleanPreferencesKey("initialized")
    val INITIAL_PHOTO_SYNC_CUTOFF_AT = longPreferencesKey("initial_photo_sync_cutoff_at")
    val LAST_PHOTO_SYNC_ADDED_AT_SECONDS = longPreferencesKey("last_photo_sync_added_at_seconds")
    val SEARCH_CONTEXT_INDEX_COMPLETED_VERSION = intPreferencesKey("search_context_index_completed_version")
}
