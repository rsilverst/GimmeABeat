package com.rsilverst.gimmeabeat

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map

private val Context.prefStore by preferencesDataStore(name = "user_prefs")

object Preferences {
    private val HR_MULTIPLIER = floatPreferencesKey("hr_multiplier")
    private val GENRE = stringPreferencesKey("genre")

    const val DEFAULT_MULTIPLIER = 1.0f

    fun multiplierFlow(context: Context): Flow<Float> =
        context.prefStore.data.map { it[HR_MULTIPLIER] ?: DEFAULT_MULTIPLIER }

    suspend fun setMultiplier(context: Context, value: Float) {
        context.prefStore.edit { it[HR_MULTIPLIER] = value }
    }

    suspend fun currentMultiplier(context: Context): Float =
        multiplierFlow(context).firstOrNull() ?: DEFAULT_MULTIPLIER

    fun genreFlow(context: Context): Flow<String?> =
        context.prefStore.data.map { it[GENRE] }

    suspend fun setGenre(context: Context, value: String?) {
        context.prefStore.edit { prefs ->
            if (value.isNullOrBlank()) prefs.remove(GENRE) else prefs[GENRE] = value
        }
    }

    suspend fun currentGenre(context: Context): String? =
        genreFlow(context).firstOrNull()
}
