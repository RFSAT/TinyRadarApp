package com.TinyRAD.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.TinyRAD.BuildConfig
import com.TinyRAD.data.models.UsbConnectionState
import com.TinyRAD.ui.theme.*
import com.TinyRAD.viewmodel.TinyRadViewModel

@Composable
fun HomeScreen(
    viewModel: TinyRadViewModel,
    onNavigateToRadar:   () -> Unit,
    onNavigateToConnect: () -> Unit,
    onNavigateToAbout:   () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier            = Modifier.fillMaxSize().background(RadarDark).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Spacer(Modifier.height(16.dp))

        Text("TinyRAD", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = RadarAccent)
        Text(
            "FMCW Radar Object Detection",
            fontSize = 14.sp,
            color    = RadarOnSurface.copy(alpha = 0.7f)
        )
        Text(
            "Version ${BuildConfig.VERSION_NAME}",
            fontSize = 12.sp,
            color    = RadarOnSurface.copy(alpha = 0.45f)
        )

        Spacer(Modifier.height(8.dp))

        RadarSweepIcon(
            isConnected = uiState.connectionState == UsbConnectionState.CONNECTED,
            isStreaming  = uiState.isStreaming
        )

        // Connection status card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape    = RoundedCornerShape(16.dp),
            colors   = CardDefaults.cardColors(containerColor = RadarDarkMid)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment      = Alignment.CenterVertically,
                    horizontalArrangement  = Arrangement.spacedBy(12.dp)
                ) {
                    val dotColour = when (uiState.connectionState) {
                        UsbConnectionState.CONNECTED                                 -> RadarAccent
                        UsbConnectionState.CONNECTING,
                        UsbConnectionState.REQUESTING_PERMISSION                     -> RadarWarning
                        else                                                         -> RadarError
                    }
                    Box(Modifier.size(10.dp).clip(CircleShape).background(dotColour))
                    Text(uiState.deviceName, color = RadarOnSurface, fontWeight = FontWeight.Medium)
                }
                Text(
                    when (uiState.connectionState) {
                        UsbConnectionState.CONNECTED             -> "USB connected — ready"
                        UsbConnectionState.CONNECTING            -> "Connecting…"
                        UsbConnectionState.REQUESTING_PERMISSION -> "Requesting USB permission…"
                        UsbConnectionState.DISCONNECTED          -> "Disconnected — attach TinyRAD via USB"
                        UsbConnectionState.ERROR                 ->
                            "Error: ${uiState.errorMessage ?: "unknown"}"
                    },
                    fontSize = 13.sp,
                    color    = RadarOnSurface.copy(alpha = 0.6f)
                )
                if (uiState.isStreaming) {
                    Text(
                        "Streaming  •  ${"%.1f".format(uiState.frameRate)} fps  •  ${uiState.totalFrames} frames",
                        fontSize = 12.sp,
                        color    = RadarAccent
                    )
                }
            }
        }

        if (uiState.connectionState == UsbConnectionState.ERROR) {
            uiState.errorMessage?.let { err ->
                Text(err, color = RadarError, fontSize = 12.sp, textAlign = TextAlign.Center)
            }
        }

        // Action buttons
        when (uiState.connectionState) {
            UsbConnectionState.DISCONNECTED, UsbConnectionState.ERROR -> {
                Button(
                    onClick  = { viewModel.findAndConnect() },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape    = RoundedCornerShape(14.dp),
                    colors   = ButtonDefaults.buttonColors(containerColor = RadarBlue)
                ) {
                    Icon(Icons.Default.Usb, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Connect TinyRAD", fontWeight = FontWeight.SemiBold)
                }
                // Fallback: show full USB device list (covers BF707 / custom firmware PIDs)
                OutlinedButton(
                    onClick  = onNavigateToConnect,
                    modifier = Modifier.fillMaxWidth(),
                    shape    = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.List, null, tint = RadarOnSurface.copy(alpha = 0.7f))
                    Spacer(Modifier.width(8.dp))
                    Text("Browse all USB devices", color = RadarOnSurface.copy(alpha = 0.7f))
                }
            }
            UsbConnectionState.CONNECTED -> {
                if (!uiState.isStreaming) {
                    Button(
                        onClick  = { viewModel.startStreaming(); onNavigateToRadar() },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape    = RoundedCornerShape(14.dp),
                        colors   = ButtonDefaults.buttonColors(
                            containerColor = RadarAccent.copy(alpha = 0.85f)
                        )
                    ) {
                        Icon(Icons.Default.TrackChanges, null, tint = RadarDark)
                        Spacer(Modifier.width(8.dp))
                        Text("Start Radar", fontWeight = FontWeight.SemiBold, color = RadarDark)
                    }
                } else {
                    Button(
                        onClick  = onNavigateToRadar,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape    = RoundedCornerShape(14.dp),
                        colors   = ButtonDefaults.buttonColors(
                            containerColor = RadarAccent.copy(alpha = 0.85f)
                        )
                    ) {
                        Icon(Icons.Default.OpenInFull, null, tint = RadarDark)
                        Spacer(Modifier.width(8.dp))
                        Text("Open Live View", fontWeight = FontWeight.SemiBold, color = RadarDark)
                    }
                    OutlinedButton(
                        onClick  = { viewModel.disconnect() },
                        modifier = Modifier.fillMaxWidth(),
                        shape    = RoundedCornerShape(14.dp)
                    ) {
                        Text("Disconnect", color = RadarError)
                    }
                }
            }
            else -> {
                CircularProgressIndicator(color = RadarBlue, modifier = Modifier.size(36.dp))
            }
        }

        Spacer(Modifier.weight(1f))

        TextButton(onClick = onNavigateToAbout) {
            Text("About / Credits", color = RadarOnSurface.copy(alpha = 0.4f), fontSize = 12.sp)
        }
    }
}

@Composable
fun RadarSweepIcon(isConnected: Boolean, isStreaming: Boolean) {
    val colour = if (isConnected) RadarAccent else RadarOnSurface.copy(alpha = 0.3f)
    Box(modifier = Modifier.size(140.dp), contentAlignment = Alignment.Center) {
        for (r in listOf(65.dp, 45.dp, 25.dp)) {
            Surface(
                modifier = Modifier.size(r * 2),
                shape    = CircleShape,
                color    = Color.Transparent,
                border   = androidx.compose.foundation.BorderStroke(
                    width = 1.5.dp,
                    color = colour.copy(alpha = 0.5f)
                )
            ) {}
        }
        Box(Modifier.size(8.dp).clip(CircleShape)
            .background(if (isStreaming) RadarAccent else colour))
        Box(
            Modifier.height(1.5.dp).width(65.dp)
                .align(Alignment.CenterEnd)
                .background(colour.copy(alpha = 0.8f))
        )
    }
}
