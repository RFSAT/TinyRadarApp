package com.TinyRAD.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.TinyRAD.data.models.TinyRadConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("tinyrad_prefs")

class PreferencesRepository(private val context: Context) {

    private object Keys {
        val START_FREQ    = floatPreferencesKey("start_freq_ghz")
        val BANDWIDTH     = floatPreferencesKey("bandwidth_mhz")
        val FRAMES_PER_S  = intPreferencesKey("frames_per_sec")
        val MAX_RANGE     = floatPreferencesKey("max_range_m")
        val MAX_SPEED     = floatPreferencesKey("max_speed_mps")
        val CFAR_THRESH   = floatPreferencesKey("cfar_threshold")
        val MIN_SNR       = floatPreferencesKey("min_snr_db")
        // ── UI preferences (not radar hardware config) ────────────────────────
        val HIDE_LOG_TAB  = booleanPreferencesKey("hide_log_tab")
        val IMMERSIVE     = booleanPreferencesKey("immersive_mode")
    }

    val configFlow: Flow<TinyRadConfig> = context.dataStore.data.map { prefs ->
        TinyRadConfig(
            startFreqGHz  = prefs[Keys.START_FREQ]   ?: 24.0f,
            bandwidthMHz  = prefs[Keys.BANDWIDTH]    ?: 250f,
            framesPerSec  = prefs[Keys.FRAMES_PER_S] ?: 10,
            maxRangeM     = prefs[Keys.MAX_RANGE]    ?: 50f,
            maxSpeedMps   = prefs[Keys.MAX_SPEED]    ?: 30f,
            cfar_threshold= prefs[Keys.CFAR_THRESH]  ?: 15f,
            minSnrDb      = prefs[Keys.MIN_SNR]      ?: 10f
        )
    }

    /**
     * UI preference: hide the "Log" tab from the bottom navigation bar.
     * Kept separate from [configFlow] because it is a pure display preference
     * and must never be pushed to the radar hardware.
     */
    val hideLogTabFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.HIDE_LOG_TAB] ?: false
    }

    suspend fun setHideLogTab(hide: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.HIDE_LOG_TAB] = hide }
    }

    /**
     * UI preference: immersive full-screen mode — hides the status bar and the
     * system navigation bar. Default false so the app behaves normally until
     * the user opts in. Change the `?: false` below to `?: true` to make
     * immersive mode the out-of-the-box default.
     */
    val immersiveModeFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.IMMERSIVE] ?: false
    }

    suspend fun setImmersiveMode(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.IMMERSIVE] = enabled }
    }

    suspend fun saveConfig(cfg: TinyRadConfig) {
        context.dataStore.edit { prefs ->
            prefs[Keys.START_FREQ]   = cfg.startFreqGHz
            prefs[Keys.BANDWIDTH]    = cfg.bandwidthMHz
            prefs[Keys.FRAMES_PER_S] = cfg.framesPerSec
            prefs[Keys.MAX_RANGE]    = cfg.maxRangeM
            prefs[Keys.MAX_SPEED]    = cfg.maxSpeedMps
            prefs[Keys.CFAR_THRESH]  = cfg.cfar_threshold
            prefs[Keys.MIN_SNR]      = cfg.minSnrDb
        }
    }
}
