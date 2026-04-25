package com.bshsqa.dodochronicle.domain.usecase

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.bshsqa.dodochronicle.data.local.db.DodoDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ClearAllDataUseCase @Inject constructor(
    private val database: DodoDatabase,
    private val dataStore: DataStore<Preferences>
) {
    suspend operator fun invoke() = withContext(Dispatchers.IO) {
        database.clearAllTables()
        dataStore.updateData { it.toMutablePreferences().apply { clear() } }
    }
}
