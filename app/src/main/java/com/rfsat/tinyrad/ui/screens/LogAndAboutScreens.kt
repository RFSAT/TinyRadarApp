package com.rfsat.tinyrad.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rfsat.tinyrad.data.repository.AppLog
import com.rfsat.tinyrad.data.repository.LogEntry
import com.rfsat.tinyrad.data.repository.LogLevel
import com.rfsat.tinyrad.ui.theme.*
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneOffset.UTC)

// ── Log Screen ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(onBack: () -> Unit) {
    val allEntries by AppLog.entries.collectAsState()

    // Filter chip state — null = show all
    var activeFilter by remember { mutableStateOf<LogLevel?>(null) }

    val displayed = remember(allEntries, activeFilter) {
        if (activeFilter == null) allEntries
        else allEntries.filter { it.level == activeFilter }
    }

    val listState = rememberLazyListState()

    // Auto-scroll to bottom whenever new entries arrive in the current filter view
    LaunchedEffect(displayed.size) {
        if (displayed.isNotEmpty()) listState.scrollToItem(displayed.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Event Log") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
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
        Column(modifier = Modifier.padding(pad)) {

            // ── Filter chips ─────────────────────────────────────────────────
            LazyRow(
                modifier            = Modifier
                    .fillMaxWidth()
                    .background(RadarDarkMid)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // "All" chip
                item {
                    LevelFilterChip(
                        label    = "ALL",
                        count    = allEntries.size,
                        selected = activeFilter == null,
                        colour   = RadarAccent,
                        onClick  = { activeFilter = null }
                    )
                }
                // Per-level chips — only show levels that have entries
                items(LogLevel.entries.reversed()) { level ->
                    val n = allEntries.count { it.level == level }
                    if (n > 0) {
                        LevelFilterChip(
                            label    = level.name.take(4),
                            count    = n,
                            selected = activeFilter == level,
                            colour   = levelColour(level),
                            onClick  = {
                                activeFilter = if (activeFilter == level) null else level
                            }
                        )
                    }
                }
            }

            // ── Log list ─────────────────────────────────────────────────────
            if (displayed.isEmpty()) {
                Box(
                    modifier         = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (activeFilter != null) "No ${activeFilter!!.name} messages"
                        else "No log entries yet",
                        color    = RadarOnSurface.copy(alpha = 0.4f),
                        fontSize = 13.sp
                    )
                }
            } else {
                LazyColumn(
                    state               = listState,
                    modifier            = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(displayed, key = { it.hashCode() * 31 + it.timestamp.toEpochMilli() }) {
                        LogRow(it)
                    }
                }
            }
        }
    }
}

@Composable
private fun LevelFilterChip(
    label:    String,
    count:    Int,
    selected: Boolean,
    colour:   androidx.compose.ui.graphics.Color,
    onClick:  () -> Unit
) {
    val bg     = if (selected) colour.copy(alpha = 0.22f) else RadarSurface.copy(alpha = 0.5f)
    val border = if (selected) colour else RadarSurface

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment      = Alignment.CenterVertically,
        horizontalArrangement  = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            label,
            color      = if (selected) colour else RadarOnSurface.copy(alpha = 0.6f),
            fontSize   = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            fontFamily = FontFamily.Monospace
        )
        Text(
            count.toString(),
            color    = if (selected) colour.copy(alpha = 0.8f) else RadarOnSurface.copy(alpha = 0.4f),
            fontSize = 10.sp
        )
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    val (bg, fg) = when (entry.level) {
        LogLevel.ERROR   -> RadarError.copy(alpha = 0.14f)   to RadarError
        LogLevel.WARNING -> RadarWarning.copy(alpha = 0.12f) to RadarWarning
        LogLevel.INFO    -> RadarDarkMid                     to RadarOnSurface
        // DEBUG: brighter than before — 0.78 alpha instead of 0.45
        LogLevel.DEBUG   -> RadarDark                        to RadarOnSurface.copy(alpha = 0.78f)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(4.dp))
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            TIME_FMT.format(entry.timestamp),
            color      = RadarAccent.copy(alpha = 0.65f),
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

private fun levelColour(level: LogLevel) = when (level) {
    LogLevel.ERROR   -> RadarError
    LogLevel.WARNING -> RadarWarning
    LogLevel.INFO    -> RadarOnSurface
    LogLevel.DEBUG   -> RadarOnSurface.copy(alpha = 0.78f)
}

// ── About Screen ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    fun openUrl(url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title  = { Text("About") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
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
            modifier            = Modifier
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "TinyRAD",
                fontWeight = FontWeight.Bold,
                color      = RadarAccent,
                fontSize   = 28.sp
            )
            Text(
                "v2.2",
                color    = RadarOnSurface.copy(alpha = 0.6f),
                fontSize = 14.sp
            )
            Text(
                "FMCW Radar Object Detection",
                color    = RadarOnSurface,
                fontSize = 13.sp
            )

            HorizontalDivider(color = RadarSurface)

            InfoRow("Developer",  "RFSAT Limited")
            InfoRow("Radar HW",   "Analog Devices EV-TINYRAD24G")
            InfoRow("Firmware",   "R 3.0.3 (VID 0x064B / PID 0x7823)")
            InfoRow("Interface",  "USB Host — vendor bulk (OTG)")
            InfoRow("Licence",    "MIT © 2026 RFSAT Limited")

            HorizontalDivider(color = RadarSurface)

            // ── Web links ────────────────────────────────────────────────────
            Text(
                "Resources",
                color      = RadarAccent,
                fontWeight = FontWeight.SemiBold,
                fontSize   = 12.sp,
                modifier   = Modifier.align(Alignment.Start)
            )

            LinkRow(
                label   = "RFSAT Limited",
                url     = "https://www.rfsat.com",
                onClick = { openUrl("https://www.rfsat.com") }
            )

            LinkRow(
                label   = "TinyRAD Evaluation Board",
                url     = "analog.com — EV-TINYRAD24G",
                onClick = {
                    openUrl(
                        "https://www.analog.com/en/resources/evaluation-hardware-and-software/" +
                        "evaluation-boards-kits/eval-tinyrad.html"
                    )
                }
            )

            HorizontalDivider(color = RadarSurface)

            Text(
                "Object classes: Human · Animal · Ground Vehicle · Aerial Vehicle",
                color    = RadarOnSurface.copy(alpha = 0.55f),
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Text(label, color = RadarOnSurface.copy(alpha = 0.5f), fontSize = 13.sp)
        Text(value, color = RadarOnSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun LinkRow(label: String, url: String, onClick: () -> Unit) {
    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(RadarDarkMid)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                color      = RadarAccent,
                fontSize   = 13.sp,
                fontWeight = FontWeight.Medium,
                textDecoration = TextDecoration.Underline
            )
            Text(
                url,
                color    = RadarOnSurface.copy(alpha = 0.45f),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        Icon(
            Icons.Default.OpenInNew,
            contentDescription = "Open in browser",
            tint     = RadarAccent.copy(alpha = 0.6f),
            modifier = Modifier.size(16.dp)
        )
    }
}
