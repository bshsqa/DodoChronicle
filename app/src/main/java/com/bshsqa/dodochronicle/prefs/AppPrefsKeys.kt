package com.bshsqa.dodochronicle.prefs

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

object AppPrefsKeys {
    val INITIALIZED = booleanPreferencesKey("initialized")
    val INITIAL_PHOTO_SYNC_CUTOFF_AT = longPreferencesKey("initial_photo_sync_cutoff_at")
    val LAST_PHOTO_SYNC_ADDED_AT_SECONDS = longPreferencesKey("last_photo_sync_added_at_seconds")
    val SEARCH_CONTEXT_INDEX_COMPLETED_VERSION = intPreferencesKey("search_context_index_completed_version")
    val GEMINI_API_KEY = stringPreferencesKey("gemini_api_key")
    val GEMINI_MODEL_ID = stringPreferencesKey("gemini_model_id")
    val GEMINI_MODEL_LIST_JSON = stringPreferencesKey("gemini_model_list_json")
    val GEMINI_MODEL_LIST_FETCHED_AT = longPreferencesKey("gemini_model_list_fetched_at")
}
