package com.TinyRAD.ui.screens

import com.TinyRAD.BuildConfig

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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.TinyRAD.data.repository.AppLog
import com.TinyRAD.data.repository.LogEntry
import com.TinyRAD.data.repository.LogLevel
import com.TinyRAD.ui.theme.*
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneOffset.UTC)

// ── Log Screen ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(onBack: () -> Unit) {
    val allEntries by AppLog.entries.collectAsState()
    var activeFilter by remember { mutableStateOf<LogLevel?>(null) }

    val displayed = remember(allEntries, activeFilter) {
        if (activeFilter == null) allEntries
        else allEntries.filter { it.level == activeFilter }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(displayed.size) {
        if (displayed.isNotEmpty()) listState.scrollToItem(displayed.size - 1)
    }

    Scaffold(
        // Insets are already consumed by the root Scaffold in MainActivity.
        // Without this, system-bar insets would be applied twice (edge-to-edge bug).
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        // TopAppBar removed in v3.3.0. Log is a bottom-navigation tab, so the
        // back arrow was redundant and the bar carried no actions. The filter
        // chip row below now serves as the visual header.
        containerColor = RadarDark
    ) { pad ->
        Column(modifier = Modifier.padding(pad)) {

            // ── Filter chips ─────────────────────────────────────────────────
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(RadarDarkMid)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment     = Alignment.CenterVertically
            ) {
                item {
                    FilterChip(
                        label = "ALL", count = allEntries.size, colour = RadarAccent,
                        selected = activeFilter == null, onClick = { activeFilter = null }
                    )
                }
                items(LogLevel.entries.reversed()) { level ->
                    val n = allEntries.count { it.level == level }
                    if (n > 0) {
                        FilterChip(
                            label    = level.name.take(4),
                            count    = n,
                            colour   = levelColour(level),
                            selected = activeFilter == level,
                            onClick  = { activeFilter = if (activeFilter == level) null else level }
                        )
                    }
                }
            }

            // ── Log file path — visible white/cream text, not invisible grey ─
            AppLog.logFilePath()?.let { path ->
                Text(
                    "File: $path",
                    color      = RadarOnSurface.copy(alpha = 0.75f),   // was 0.35 — now readable
                    fontSize   = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis,
                    modifier   = Modifier
                        .fillMaxWidth()
                        .background(RadarDarkMid)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            // ── Log list ─────────────────────────────────────────────────────
            if (displayed.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (activeFilter != null) "No ${activeFilter!!.name} messages"
                        else "No log entries yet",
                        color = RadarOnSurface.copy(alpha = 0.4f), fontSize = 13.sp
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
                    items(displayed.size, key = { it }) { i ->
                        LogRow(displayed[i])
                    }
                }
            }
        }
    }
}

// ── Filter chip ───────────────────────────────────────────────────────────────

@Composable
private fun FilterChip(
    label: String, count: Int,
    colour: androidx.compose.ui.graphics.Color,
    selected: Boolean, onClick: () -> Unit
) {
    val bg = if (selected) colour.copy(alpha = 0.22f) else RadarSurface.copy(alpha = 0.5f)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
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

// ── Log row ───────────────────────────────────────────────────────────────────

@Composable
private fun LogRow(entry: LogEntry) {
    val (bg, fg) = when (entry.level) {
        LogLevel.ERROR   -> RadarError.copy(alpha = 0.14f)   to RadarError
        LogLevel.WARNING -> RadarWarning.copy(alpha = 0.12f) to RadarWarning
        LogLevel.INFO    -> RadarDarkMid                     to RadarOnSurface
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
            color = RadarAccent.copy(alpha = 0.65f), fontSize = 10.sp,
            fontFamily = FontFamily.Monospace, modifier = Modifier.width(80.dp)
        )
        Text(
            entry.level.name.take(4),
            color = fg, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold, modifier = Modifier.width(36.dp)
        )
        Text(
            entry.message,
            color = fg, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f)
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
        // Insets are already consumed by the root Scaffold in MainActivity.
        // Without this, system-bar insets would be applied twice (edge-to-edge bug).
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
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
            modifier = Modifier
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("TinyRAD", fontWeight = FontWeight.Bold, color = RadarAccent, fontSize = 28.sp)
            Text("v${BuildConfig.VERSION_NAME}",
                color = RadarOnSurface.copy(alpha = 0.6f))
            Text("FMCW Radar Object Detection", color = RadarOnSurface, fontSize = 13.sp)

            Text(
                "TinyRAD turns your Android phone into a portable FMCW radar monitor for " +
                "the Analog Devices EV-TINYRAD24G evaluation board, connected via USB-OTG. " +
                "It streams live range/Doppler data from the radar's onboard firmware and " +
                "classifies detected objects in real time — distinguishing humans, animals, " +
                "ground vehicles and aerial vehicles. The app provides live visualisation, " +
                "an event log and detection history, making it a handy field tool for " +
                "researchers, engineers and hobbyists evaluating 24 GHz FMCW radar hardware.",
                color     = RadarOnSurface.copy(alpha = 0.85f),
                fontSize  = 12.sp,
                textAlign = TextAlign.Justify,
                modifier  = Modifier.fillMaxWidth()
            )

            HorizontalDivider(color = RadarSurface)

            // Clickable RFSAT row
            AboutLinkRow(
                label  = "Developer",
                value  = "RFSAT Limited",
                url    = "https://www.rfsat.com",
                onOpen = { openUrl("https://www.rfsat.com") }
            )

            Text(
                "RFSAT Limited (Research for Science, Art and Technology) is a non-profit " +
                "R&D SME based in Dublin, Ireland, with a research office in Athens, Greece. " +
                "It carries out applied research and technology development under national " +
                "and EU funding programmes — including Horizon Europe — spanning digital " +
                "signal processing, micro-embedded sensing, autonomous systems, GNSS " +
                "positioning, and environmental and e-Health monitoring.",
                color     = RadarOnSurface.copy(alpha = 0.6f),
                fontSize  = 11.sp,
                textAlign = TextAlign.Justify,
                modifier  = Modifier.fillMaxWidth()
            )

            // Clickable Analog Devices row
            AboutLinkRow(
                label  = "Radar HW",
                value  = "Analog Devices EV-TINYRAD24G",
                url    = "analog.com/eval-tinyrad",
                onOpen = {
                    openUrl(
                        "https://www.analog.com/en/resources/evaluation-hardware-and-software/" +
                        "evaluation-boards-kits/eval-tinyrad.html"
                    )
                }
            )

            InfoRow("Firmware",  "R 3.0.3  (VID 0x064B / PID 0x7823)")
            InfoRow("Interface", "USB Host — vendor bulk (OTG)")
            InfoRow("Licence",   "MIT © 2026 RFSAT Limited")

            HorizontalDivider(color = RadarSurface)

            Text(
                "Object classes: Human · Animal · Ground Vehicle · Aerial Vehicle",
                color = RadarOnSurface.copy(alpha = 0.55f), fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun AboutLinkRow(label: String, value: String, url: String, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onOpen)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Text(label, color = RadarOnSurface.copy(alpha = 0.5f), fontSize = 13.sp,
            modifier = Modifier.width(90.dp))
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End
        ) {
            Text(
                value,
                color          = RadarAccent,
                fontSize       = 13.sp,
                fontWeight     = FontWeight.Medium,
                textDecoration = TextDecoration.Underline
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = "Open in browser",
                tint     = RadarAccent.copy(alpha = 0.7f),
                modifier = Modifier.size(13.dp)
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = RadarOnSurface.copy(alpha = 0.5f), fontSize = 13.sp)
        Text(value, color = RadarOnSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}
