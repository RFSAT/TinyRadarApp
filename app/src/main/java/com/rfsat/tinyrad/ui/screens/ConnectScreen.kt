package com.rfsat.tinyrad.ui.screens

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rfsat.tinyrad.ui.theme.*
import com.rfsat.tinyrad.viewmodel.TinyRadViewModel

/**
 * Displays all attached USB devices and lets the user pick one manually.
 * This is the fallback when VID/PID auto-detection doesn't match
 * (e.g. custom TinyRAD firmware with different IDs, or BF707 Bulk Device
 * appearing under a different PID than expected).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(
    viewModel: TinyRadViewModel,
    onBack:    () -> Unit
) {
    val context    = LocalContext.current
    val usbManager = context.getSystemService(android.content.Context.USB_SERVICE) as UsbManager

    // Refresh device list whenever this screen is shown
    var devices by remember { mutableStateOf(usbManager.deviceList.values.toList()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title  = { Text("Select USB Device") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = { devices = usbManager.deviceList.values.toList() }) {
                        Icon(Icons.Default.Refresh, "Refresh", tint = RadarAccent)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor    = RadarDarkMid,
                    titleContentColor = RadarOnSurface
                )
            )
        },
        containerColor = RadarDark
    ) { pad ->
        Column(
            modifier = Modifier.padding(pad).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Guidance card
            Card(
                shape  = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = RadarDarkMid)
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "USB Host device list",
                        fontWeight = FontWeight.SemiBold,
                        color      = RadarAccent,
                        fontSize   = 13.sp
                    )
                    Text(
                        "Tap any device to request permission and connect. " +
                        "The TinyRAD board typically appears as a CDC-ACM or Bulk device. " +
                        "If the list is empty, check that your cable supports USB-OTG " +
                        "data transfer (not charge-only).",
                        color    = RadarOnSurface.copy(alpha = 0.7f),
                        fontSize = 12.sp
                    )
                }
            }

            if (devices.isEmpty()) {
                Box(
                    modifier            = Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment    = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Default.UsbOff,
                            contentDescription = null,
                            tint   = RadarOnSurface.copy(alpha = 0.3f),
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            "No USB devices detected",
                            color    = RadarOnSurface.copy(alpha = 0.5f),
                            fontSize = 14.sp
                        )
                        Text(
                            "• Use a USB-OTG / USB-C adapter cable\n" +
                            "• Ensure the phone is set to USB host mode\n" +
                            "• Check the TinyRAD board is powered",
                            color    = RadarOnSurface.copy(alpha = 0.4f),
                            fontSize = 12.sp
                        )
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(devices) { device ->
                        UsbDeviceCard(
                            device          = device,
                            hasPermission   = usbManager.hasPermission(device),
                            onConnect       = {
                                viewModel.connectDevice(device)
                                onBack()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UsbDeviceCard(
    device:       UsbDevice,
    hasPermission: Boolean,
    onConnect:    () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onConnect() },
        shape    = RoundedCornerShape(10.dp),
        colors   = CardDefaults.cardColors(containerColor = RadarDarkMid)
    ) {
        Row(
            modifier          = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                Icons.Default.Usb,
                contentDescription = null,
                tint     = if (hasPermission) RadarAccent else RadarOnSurface.copy(alpha = 0.5f),
                modifier = Modifier.size(28.dp)
            )
            Column(Modifier.weight(1f)) {
                Text(
                    device.productName ?: device.deviceName,
                    color      = RadarOnSurface,
                    fontWeight = FontWeight.Medium,
                    fontSize   = 13.sp
                )
                Text(
                    device.manufacturerName ?: "Unknown manufacturer",
                    color    = RadarOnSurface.copy(alpha = 0.5f),
                    fontSize = 11.sp
                )
                Text(
                    "VID:${device.vendorId.toString(16).uppercase().padStart(4,'0')}  " +
                    "PID:${device.productId.toString(16).uppercase().padStart(4,'0')}  " +
                    "${device.interfaceCount} iface(s)",
                    color      = RadarAccent.copy(alpha = 0.6f),
                    fontSize   = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                if (hasPermission) {
                    Text("PERMITTED", color = RadarAccent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = "Connect",
                    tint = RadarOnSurface.copy(alpha = 0.4f)
                )
            }
        }
    }
}
