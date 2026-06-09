package com.rfsat.tinyrad.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rfsat.tinyrad.data.repository.AppLog
import com.rfsat.tinyrad.data.repository.LogEntry
import com.rfsat.tinyrad.data.repository.LogLevel
import com.rfsat.tinyrad.ui.theme.*
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneOffset.UTC)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(onBack: () -> Unit) {
    val entries by AppLog.entries.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.scrollToItem(entries.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title  = { Text("Event Log") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor    = RadarDarkMid,
                    titleContentColor = RadarOnSurface
                )
            )
        },
        containerColor = RadarDark
    ) { pad ->
        LazyColumn(
            state   = listState,
            modifier = Modifier.padding(pad).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(entries) { entry ->
                LogRow(entry)
            }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    val (bg, fg) = when (entry.level) {
        LogLevel.ERROR   -> RadarError.copy(alpha = 0.12f) to RadarError
        LogLevel.WARNING -> RadarWarning.copy(alpha = 0.12f) to RadarWarning
        LogLevel.INFO    -> RadarDarkMid to RadarOnSurface
        LogLevel.DEBUG   -> RadarDark to RadarOnSurface.copy(alpha = 0.45f)
    }
    Row(
        modifier = Modifier.fillMaxWidth().background(bg, RoundedCornerShape(4.dp)).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            TIME_FMT.format(entry.timestamp),
            color      = RadarAccent.copy(alpha = 0.6f),
            fontSize   = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier   = Modifier.width(80.dp)
        )
        Text(
            entry.level.name.take(4),
            color      = fg,
            fontSize   = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            modifier   = Modifier.width(36.dp)
        )
        Text(
            entry.message,
            color      = fg,
            fontSize   = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier   = Modifier.weight(1f)
        )
    }
}

// ── About Screen ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title  = { Text("About") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor    = RadarDarkMid,
                    titleContentColor = RadarOnSurface
                )
            )
        },
        containerColor = RadarDark
    ) { pad ->
        Column(
            modifier            = Modifier.padding(pad).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("TinyRAD",   fontWeight = FontWeight.Bold,   color = RadarAccent, fontSize = 28.sp)
            Text("v1.0",      color = RadarOnSurface.copy(alpha = 0.6f))
            Text("FMCW Radar Object Detection Application",
                color = RadarOnSurface, fontSize = 13.sp)

            HorizontalDivider(color = RadarSurface)

            InfoRow("Developer",  "RFSAT Limited")
            InfoRow("Project",    "ENACT — Environmental Monitoring")
            InfoRow("Grant",      "Horizon Europe 101157101")
            InfoRow("Radar HW",   "Analog Devices TinyRAD")
            InfoRow("Interface",  "USB CDC-ACM (OTG)")
            InfoRow("Licence",    "MIT © 2026 RFSAT Limited")

            HorizontalDivider(color = RadarSurface)

            Text(
                "Object classes detected: Human · Animal · Ground Vehicle · Aerial Vehicle",
                color    = RadarOnSurface.copy(alpha = 0.55f),
                fontSize = 11.sp
            )
            Text(
                "Views and opinions expressed are those of the author(s) only and do not " +
                "necessarily reflect those of the European Union.",
                color    = RadarOnSurface.copy(alpha = 0.4f),
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = RadarOnSurface.copy(alpha = 0.5f), fontSize = 13.sp)
        Text(value, color = RadarOnSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}
