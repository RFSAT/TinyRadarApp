package com.TinyRAD.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.TinyRAD.data.models.TinyRadConfig
import com.TinyRAD.ui.theme.*
import com.TinyRAD.viewmodel.TinyRadViewModel

@Composable
fun SettingsScreen(
    viewModel: TinyRadViewModel,
    onBack:    () -> Unit
) {
    val state  by viewModel.uiState.collectAsState()
    val hideLogTab by viewModel.hideLogTab.collectAsState()
    val immersiveMode by viewModel.immersiveMode.collectAsState()
    var cfg    by remember(state.config) { mutableStateOf<com.TinyRAD.data.models.TinyRadConfig>(state.config) }

    Scaffold(
        // Insets are already consumed by the root Scaffold in MainActivity.
        // Without this, system-bar insets would be applied twice (edge-to-edge bug).
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        // TopAppBar removed in v3.3.0. Settings is a bottom-navigation tab, so
        // the back arrow was redundant, and the check action duplicated the
        // "Apply Configuration" button at the foot of the content below.
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

            Section("Timing & Update Rate") {
                // Chirps per frame controls both update rate and Doppler resolution.
                // Fewer chirps = faster updates, coarser velocity measurement.
                // 16 chirps × 40ms/chirp = 640ms/frame ≈ 1.5 fps (recommended)
                // 32 chirps × 40ms/chirp = 1.3s/frame  ≈ 0.8 fps
                // 80 chirps × 40ms/chirp = 3.2s/frame  ≈ 0.3 fps (best Doppler)
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Chirps per frame", color = RadarOnSurface, fontSize = 12.sp)
                        Text("${cfg.chirpsPerFrame}  (~${"%.1f".format(1000f / (cfg.chirpsPerFrame * 40f))} fps)",
                            color = RadarAccent, fontSize = 12.sp)
                    }
                    // Steps: 4, 8, 16, 32, 64, 80
                    val steps = listOf(4, 8, 16, 32, 64, 80)
                    val idx = steps.indexOfFirst { it >= cfg.chirpsPerFrame }.coerceAtLeast(0)
                    Slider(
                        value         = idx.toFloat(),
                        onValueChange = { cfg = cfg.copy(chirpsPerFrame = steps[it.toInt().coerceIn(0, steps.size-1)]) },
                        valueRange    = 0f..(steps.size - 1).toFloat(),
                        steps         = steps.size - 2,
                        colors        = SliderDefaults.colors(thumbColor = RadarAccent,
                            activeTrackColor = RadarAccent, inactiveTrackColor = RadarSurface)
                    )
                    Text("4=fast/coarse  →  80=slow/precise Doppler",
                        color = RadarOnSurface.copy(alpha = 0.45f), fontSize = 10.sp)
                }
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

            Section("Interface") {
                // UI preferences — persisted immediately, independent of the
                // "Apply Configuration" button (which pushes radar HW settings).
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Full-screen mode", color = RadarOnSurface, fontSize = 12.sp)
                        Text(
                            "Hides the status bar and the system navigation bar. Swipe from a screen edge to reveal them briefly.",
                            color = RadarOnSurface.copy(alpha = 0.45f), fontSize = 10.sp
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked         = immersiveMode,
                        onCheckedChange = { viewModel.setImmersiveMode(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor   = RadarAccent,
                            checkedTrackColor   = RadarAccent.copy(alpha = 0.4f),
                            uncheckedThumbColor = RadarOnSurface.copy(alpha = 0.6f),
                            uncheckedTrackColor = RadarSurface
                        )
                    )
                }

                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Hide \"Log\" tab", color = RadarOnSurface, fontSize = 12.sp)
                        Text(
                            "Removes the Log tab from the bottom bar. Logging continues in the background.",
                            color = RadarOnSurface.copy(alpha = 0.45f), fontSize = 10.sp
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked         = hideLogTab,
                        onCheckedChange = { viewModel.setHideLogTab(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor   = RadarAccent,
                            checkedTrackColor   = RadarAccent.copy(alpha = 0.4f),
                            uncheckedThumbColor = RadarOnSurface.copy(alpha = 0.6f),
                            uncheckedTrackColor = RadarSurface
                        )
                    )
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
