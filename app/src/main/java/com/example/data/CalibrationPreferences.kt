package com.example.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.CalibrationBounds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.rudraXDataStore by preferencesDataStore(name = "rudrax_air_mouse_prefs")

class CalibrationPreferences(private val context: Context) {

    val calibrationBoundsFlow: Flow<CalibrationBounds> = context.rudraXDataStore.data.map { prefs ->
        CalibrationBounds(
            minX = prefs[KEY_MIN_X] ?: 0.15f,
            maxX = prefs[KEY_MAX_X] ?: 0.85f,
            minY = prefs[KEY_MIN_Y] ?: 0.15f,
            maxY = prefs[KEY_MAX_Y] ?: 0.85f,
            isCustomCalibrated = prefs[KEY_IS_CALIBRATED] ?: false
        )
    }

    val sensitivityFlow: Flow<Float> = context.rudraXDataStore.data.map { prefs ->
        prefs[KEY_SENSITIVITY] ?: 1.35f
    }

    suspend fun saveCalibrationBounds(bounds: CalibrationBounds) {
        context.rudraXDataStore.edit { prefs ->
            prefs[KEY_MIN_X] = bounds.minX
            prefs[KEY_MAX_X] = bounds.maxX
            prefs[KEY_MIN_Y] = bounds.minY
            prefs[KEY_MAX_Y] = bounds.maxY
            prefs[KEY_IS_CALIBRATED] = bounds.isCustomCalibrated
        }
    }

    suspend fun saveSensitivity(sensitivity: Float) {
        context.rudraXDataStore.edit { prefs ->
            prefs[KEY_SENSITIVITY] = sensitivity.coerceIn(0.5f, 3.0f)
        }
    }

    companion object {
        private val KEY_MIN_X = floatPreferencesKey("calib_min_x")
        private val KEY_MAX_X = floatPreferencesKey("calib_max_x")
        private val KEY_MIN_Y = floatPreferencesKey("calib_min_y")
        private val KEY_MAX_Y = floatPreferencesKey("calib_max_y")
        private val KEY_IS_CALIBRATED = booleanPreferencesKey("calib_is_custom")
        private val KEY_SENSITIVITY = floatPreferencesKey("pointer_sensitivity")
    }
}
