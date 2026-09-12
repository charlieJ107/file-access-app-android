package space.zhuoling.fileaccess.core.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.fileAccessSettings by preferencesDataStore(name = "settings")

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.fileAccessSettings
    fun observe(): Flow<AppSettings> = store.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }.map { values ->
        AppSettings(
            theme = values[THEME] ?: "system",
            showHiddenFiles = values[SHOW_HIDDEN] ?: false,
            showFileNamesInNotifications = values[NOTIFICATION_NAMES] ?: false,
        )
    }

    suspend fun setTheme(theme: String) {
        require(theme in setOf("system", "light", "dark"))
        store.edit { it[THEME] = theme }
    }
    suspend fun setShowHiddenFiles(value: Boolean) { store.edit { it[SHOW_HIDDEN] = value } }
    suspend fun setShowFileNamesInNotifications(value: Boolean) {
        store.edit { it[NOTIFICATION_NAMES] = value }
    }

    private companion object {
        val THEME = stringPreferencesKey("theme")
        val SHOW_HIDDEN = booleanPreferencesKey("show_hidden_files")
        val NOTIFICATION_NAMES = booleanPreferencesKey("notification_file_names")
    }
}
