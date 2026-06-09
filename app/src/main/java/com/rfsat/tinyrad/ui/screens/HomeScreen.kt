package com.rfsat.tinyrad.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import com.rfsat.tinyrad.data.models.UsbConnectionState
import com.rfsat.tinyrad.ui.theme.*
import com.rfsat.tinyrad.viewmodel.TinyRadViewModel

@Composable
fun HomeScreen(
    viewModel: TinyRadViewModel,
    onNavigateToRadar: () -> Unit,
    onNavigateToAbout: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier           = Modifier.fillMaxSize().background(RadarDark).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Spacer(Modifier.height(16.dp))

        // App title
        Text(
            "TinyRAD",
            fontSize   = 36.sp,
            fontWeight = FontWeight.Bold,
            color      = RadarAccent
        )
        Text(
            "FMCW Radar Object Detection",
            fontSize = 14.sp,
            color    = RadarOnSurface.copy(alpha = 0.7f)
        )

        Spacer(Modifier.height(8.dp))

        // Radar animation placeholder (concentric circles)
        RadarSweepIcon(
            isConnected = state.connectionState == UsbConnectionState.CONNECTED,
            isStreaming = state.isStreaming
        )

        // Connection status card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape    = RoundedCornerShape(16.dp),
            colors   = CardDefaults.cardColors(containerColor = RadarDarkMid)
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val dot = when (state.connectionState) {
                        UsbConnectionState.CONNECTED           -> RadarAccent
                        UsbConnectionState.CONNECTING,
                        UsbConnectionState.REQUESTING_PERMISSION -> RadarWarning
                        else                                   -> RadarError
                    }
                    Box(
                        Modifier.size(10.dp).clip(CircleShape).background(dot)
                    )
                    Text(state.deviceName, color = RadarOnSurface, fontWeight = FontWeight.Medium)
                }
                Text(
                    when (state.connectionState) {
                        UsbConnectionState.CONNECTED            -> "USB connected — ready"
                        UsbConnectionState.CONNECTING           -> "Connecting…"
                        UsbConnectionState.REQUESTING_PERMISSION-> "Requesting USB permission…"
                        UsbConnectionState.DISCONNECTED         -> "Disconnected — attach TinyRAD via USB"
                        UsbConnectionState.ERROR                -> "Error: ${state.errorMessage ?: "unknown"}"
                    },
                    fontSize = 13.sp,
                    color    = RadarOnSurface.copy(alpha = 0.6f)
                )
                if (state.isStreaming) {
                    Text(
                        "Streaming  •  ${state.frameRate.let { "%.1f".format(it) }} fps  •  ${state.totalFrames} frames",
                        fontSize = 12.sp,
                        color    = RadarAccent
                    )
                }
            }
        }

        state.errorMessage?.let { err ->
            if (state.connectionState == UsbConnectionState.ERROR) {
                Text(err, color = RadarError, fontSize = 12.sp, textAlign = TextAlign.Center)
            }
        }

        // Action buttons
        when (state.connectionState) {
            UsbConnectionState.DISCONNECTED, UsbConnectionState.ERROR -> {
                Button(
                    onClick = { viewModel.findAndConnect() },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape    = RoundedCornerShape(14.dp),
                    colors   = ButtonDefaults.buttonColors(containerColor = RadarBlue)
                ) {
                    Icon(Icons.Default.Usb, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Connect TinyRAD", fontWeight = FontWeight.SemiBold)
                }
            }
            UsbConnectionState.CONNECTED -> {
                if (!state.isStreaming) {
                    Button(
                        onClick  = {
                            viewModel.startStreaming()
                            onNavigateToRadar()
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape    = RoundedCornerShape(14.dp),
                        colors   = ButtonDefaults.buttonColors(containerColor = RadarAccent.copy(alpha = 0.85f))
                    ) {
                        Icon(Icons.Default.RadarRounded, null, tint = RadarDark)
                        Spacer(Modifier.width(8.dp))
                        Text("Start Radar", fontWeight = FontWeight.SemiBold, color = RadarDark)
                    }
                } else {
                    Button(
                        onClick  = onNavigateToRadar,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape    = RoundedCornerShape(14.dp),
                        colors   = ButtonDefaults.buttonColors(containerColor = RadarAccent.copy(alpha = 0.85f))
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
    Box(
        modifier            = Modifier.size(140.dp),
        contentAlignment    = Alignment.Center
    ) {
        for (r in listOf(65.dp, 45.dp, 25.dp)) {
            Box(
                Modifier.size(r * 2)
                    .clip(CircleShape)
                    .background(Color.Transparent)
                    .then(
                        Modifier.padding(1.dp)
                    )
            )
            // Outline only — draw as bordered box
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
        // Centre dot
        Box(
            Modifier.size(8.dp).clip(CircleShape).background(if (isStreaming) RadarAccent else colour)
        )
        // Sweep line hint
        Box(
            Modifier.height(1.5.dp).width(65.dp)
                .align(Alignment.CenterEnd)
                .background(colour.copy(alpha = 0.8f))
        )
    }
}
