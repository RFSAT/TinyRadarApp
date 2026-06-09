package com.rfsat.tinyrad.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rfsat.tinyrad.data.models.TinyRadConfig
import com.rfsat.tinyrad.ui.theme.*
import com.rfsat.tinyrad.viewmodel.TinyRadViewModel

@Composable
fun SettingsScreen(
    viewModel: TinyRadViewModel,
    onBack:    () -> Unit
) {
    val state  by viewModel.uiState.collectAsState()
    var cfg    by remember(state.config) { mutableStateOf<com.rfsat.tinyrad.data.models.TinyRadConfig>(state.config) }

    Scaffold(
        topBar = {
            @OptIn(ExperimentalMaterial3Api::class)
            TopAppBar(
                title  = { Text("Radar Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        viewModel.applyConfig(cfg)
                        onBack()
                    }) {
                        Icon(Icons.Default.Check, "Apply", tint = RadarAccent)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = RadarDarkMid,
                    titleContentColor = RadarOnSurface
                )
            )
        },
        containerColor = RadarDark
    ) { pad ->
        Column(
            modifier = Modifier.padding(pad).verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Section("RF Configuration") {
                SliderField("Start Frequency (GHz)", cfg.startFreqGHz, 24f, 24.25f) {
                    cfg = cfg.copy(startFreqGHz = it)
                }
                SliderField("Bandwidth (MHz)", cfg.bandwidthMHz, 50f, 500f) {
                    cfg = cfg.copy(bandwidthMHz = it)
                }
            }

            Section("Timing") {
                IntSliderField("Frames/second", cfg.framesPerSec, 1, 50) {
                    cfg = cfg.copy(framesPerSec = it)
                }
                IntSliderField("Chirp duration (µs)", cfg.chirpDurationUs, 64, 2048) {
                    cfg = cfg.copy(chirpDurationUs = it)
                }
            }

            Section("Detection Gate") {
                SliderField("Max range (m)", cfg.maxRangeM, 1f, 100f) {
                    cfg = cfg.copy(maxRangeM = it)
                }
                SliderField("Max speed (m/s)", cfg.maxSpeedMps, 1f, 80f) {
                    cfg = cfg.copy(maxSpeedMps = it)
                }
                SliderField("Min SNR (dB)", cfg.minSnrDb, 0f, 30f) {
                    cfg = cfg.copy(minSnrDb = it)
                }
            }

            Section("CFAR") {
                IntSliderField("Guard cells", cfg.cfar_guard, 1, 8) {
                    cfg = cfg.copy(cfar_guard = it)
                }
                IntSliderField("Training cells", cfg.cfar_training, 4, 32) {
                    cfg = cfg.copy(cfar_training = it)
                }
                SliderField("Threshold (dB)", cfg.cfar_threshold, 5f, 30f) {
                    cfg = cfg.copy(cfar_threshold = it)
                }
            }

            Button(
                onClick  = { viewModel.applyConfig(cfg); onBack() },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape    = RoundedCornerShape(14.dp),
                colors   = ButtonDefaults.buttonColors(containerColor = RadarBlue)
            ) {
                Icon(Icons.Default.Check, null)
                Spacer(Modifier.width(8.dp))
                Text("Apply Configuration", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape  = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = RadarDarkMid)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = RadarAccent, fontSize = 13.sp)
            content()
        }
    }
}

@Composable
private fun SliderField(label: String, value: Float, min: Float, max: Float, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = RadarOnSurface, fontSize = 12.sp)
            Text("${"%.2f".format(value)}", color = RadarAccent, fontSize = 12.sp)
        }
        Slider(
            value         = value,
            onValueChange = onChange,
            valueRange    = min..max,
            colors        = SliderDefaults.colors(
                thumbColor        = RadarAccent,
                activeTrackColor  = RadarAccent,
                inactiveTrackColor= RadarSurface
            )
        )
    }
}

@Composable
private fun IntSliderField(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    SliderField(label, value.toFloat(), min.toFloat(), max.toFloat()) { onChange(it.toInt()) }
}
