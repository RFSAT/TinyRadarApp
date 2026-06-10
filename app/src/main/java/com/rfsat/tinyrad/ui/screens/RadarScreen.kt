package com.rfsat.tinyrad.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rfsat.tinyrad.data.models.*
import com.rfsat.tinyrad.ui.theme.*
import com.rfsat.tinyrad.viewmodel.TinyRadViewModel
import kotlin.math.*

// ── View mode enum ─────────────────────────────────────────────────────────

private enum class RadarViewMode(val label: String) {
    FMCW("FMCW"),
    RANGE_DOPPLER("Range-Doppler"),
    DBF("DBF"),
    RANGE_TIME("Range-Time")
}

// ── Radar Screen ─────────────────────────────────────────────────────────────

@Composable
fun RadarScreen(
    viewModel: TinyRadViewModel,
    onDisconnect: () -> Unit
) {
    val state   by viewModel.uiState.collectAsState()
    val frame   = state.currentFrame
    val objects = state.trackedObjects
    var viewMode by remember { mutableStateOf(RadarViewMode.RANGE_DOPPLER) }

    // Ring buffer for Range-Time display (last 64 range profiles)
    val rangeTimeBuffer = remember { ArrayDeque<FloatArray>(64) }
    LaunchedEffect(frame) {
        frame?.let { f ->
            if (f.rangeBins > 0 && f.rangeDopplerMag.isNotEmpty()) {
                // Sum Doppler bins for each range bin → range profile
                val profile = FloatArray(f.rangeBins) { r ->
                    var sum = 0f
                    for (d in 0 until f.dopplerBins) sum += f.rangeDopplerMag[r * f.dopplerBins + d]
                    sum / f.dopplerBins
                }
                rangeTimeBuffer.addLast(profile)
                while (rangeTimeBuffer.size > 64) rangeTimeBuffer.removeFirst()
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(RadarDark)) {

        // ── Top bar ──────────────────────────────────────────────────────────
        TopBar(
            frameRate    = state.frameRate,
            totalFrames  = state.totalFrames,
            rangeResM    = frame?.rangeResM ?: state.config.let { 3e8f / (2f * 250e6f) },
            isRecording  = state.isRecording,
            recordingRows= state.recordingRows,
            onRecord     = { if (state.isRecording) viewModel.stopRecording() else viewModel.startRecording() },
            onStop       = { viewModel.stopStreaming(); onDisconnect() }
        )

        // ── 180° semicircle radar view — full screen width ───────────────────
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val viewW = maxWidth
            // Semicircle height = half of width so it fits portrait
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(viewW / 2)
                    .background(RadarDarkMid)
            ) {
                SemicircleRadarView(
                    objects   = objects,
                    maxRangeM = state.config.maxRangeM,
                    modifier  = Modifier.fillMaxSize()
                )
            }
        }

        // ── Object list (compact rows under radar view) ───────────────────────
        if (objects.isNotEmpty()) {
            LazyColumn(
                modifier            = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 160.dp)
                    .background(RadarDark)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                item {
                    ObjectListHeader()
                }
                items(objects) { obj ->
                    ObjectListRow(obj)
                }
            }
        }

        // ── View mode selector tabs ───────────────────────────────────────────
        LazyRow(
            modifier            = Modifier
                .fillMaxWidth()
                .background(RadarDarkMid)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(RadarViewMode.entries) { mode ->
                val selected = mode == viewMode
                Surface(
                    onClick = { viewMode = mode },
                    shape   = RoundedCornerShape(6.dp),
                    color   = if (selected) RadarBlue else RadarSurface
                ) {
                    Text(
                        mode.label,
                        modifier  = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        fontSize  = 11.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color     = if (selected) Color.White else RadarOnSurface.copy(alpha = 0.7f)
                    )
                }
            }
        }

        // ── Bottom panel — view-specific chart ───────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 6.dp, vertical = 4.dp)
        ) {
            when (viewMode) {
                RadarViewMode.FMCW -> FmcwPanel(state.config, frame)
                RadarViewMode.RANGE_DOPPLER ->
                    frame?.let { RangeDopplerPanel(it, Modifier.fillMaxSize()) }
                RadarViewMode.DBF ->
                    frame?.let { DbfPanel(it, objects, Modifier.fillMaxSize()) }
                RadarViewMode.RANGE_TIME ->
                    RangeTimePanel(rangeTimeBuffer.toList(), frame, Modifier.fillMaxSize())
            }
        }
    }
}

// ── Top bar ───────────────────────────────────────────────────────────────────

@Composable
private fun TopBar(
    frameRate:    Float,
    totalFrames:  Long,
    rangeResM:    Float,
    isRecording:  Boolean,
    recordingRows: Int,
    onRecord:     () -> Unit,
    onStop:       () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(RadarDarkMid)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Column {
            Text("Live Radar", fontWeight = FontWeight.Bold, color = RadarOnSurface, fontSize = 13.sp)
            Text(
                "${"%.2f".format(frameRate)} fps  •  frame $totalFrames  •  ${"%.2f".format(rangeResM)}m/bin",
                color = RadarOnSurface.copy(alpha = 0.5f), fontSize = 10.sp
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (isRecording) {
                Text("REC $recordingRows", color = RadarError, fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.CenterVertically))
            }
            IconButton(onClick = onRecord, modifier = Modifier.size(36.dp)) {
                Icon(
                    if (isRecording) Icons.Default.StopCircle else Icons.Default.FiberManualRecord,
                    null, tint = if (isRecording) RadarError else RadarOnSurface
                )
            }
            IconButton(onClick = onStop, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Stop, null, tint = RadarWarning)
            }
        }
    }
}

// ── 180° Semicircle radar view ────────────────────────────────────────────────
// Origin at bottom-centre; objects sweep from -90° (left) to +90° (right)
// with range increasing upward. The board faces "up" = 0°.

@Composable
fun SemicircleRadarView(
    objects:   List<DetectedObject>,
    maxRangeM: Float,
    modifier:  Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w      = size.width
        val h      = size.height
        val cx     = w / 2f
        val cy     = h                  // origin at bottom centre
        val radius = minOf(w / 2f, h) - 4.dp.toPx()

        // Range rings (4 rings, semicircles only)
        for (i in 1..4) {
            val r    = radius * i / 4f
            val rng  = maxRangeM * i / 4
            // Draw semicircle arc (top half only: 180°→0°, i.e. PI→0 in standard coords)
            val path = Path().apply {
                moveTo(cx - r, cy)
                // arcTo from left to right across the top
                addArc(
                    left   = cx - r, top    = cy - r,
                    right  = cx + r, bottom = cy + r,
                    startAngleDegrees = 180f, sweepAngleDegrees = 180f
                )
            }
            drawPath(path, color = RadarAccent.copy(alpha = 0.2f),
                style = Stroke(width = if (i == 4) 1.5f else 0.7f))
            // Range label
            drawContext.canvas.nativeCanvas.drawText(
                "${"%.0f".format(rng)}m",
                cx + 4.dp.toPx(), cy - r + 10.dp.toPx(),
                android.graphics.Paint().apply {
                    color = android.graphics.Color.argb(120, 0, 229, 255)
                    textSize = 9.dp.toPx()
                }
            )
        }

        // Azimuth spokes at -90°, -45°, 0°, +45°, +90°
        for (azDeg in listOf(-90f, -45f, 0f, 45f, 90f)) {
            val azRad = Math.toRadians(azDeg.toDouble()).toFloat()
            drawLine(
                color       = RadarAccent.copy(alpha = 0.12f),
                start       = Offset(cx, cy),
                end         = Offset(cx + radius * sin(azRad), cy - radius * cos(azRad)),
                strokeWidth = 0.7f
            )
        }

        // Horizontal baseline
        drawLine(RadarAccent.copy(alpha = 0.25f),
            Offset(cx - radius, cy), Offset(cx + radius, cy), 1f)

        // Detected objects
        for (obj in objects) {
            val normDist = (obj.distanceM / maxRangeM).coerceIn(0f, 1f)
            val rPx      = normDist * radius
            val azRad    = Math.toRadians(obj.azimuthDeg.toDouble()).toFloat()
            val ox       = cx + rPx * sin(azRad)
            val oy       = cy - rPx * cos(azRad)

            // Clamp to semicircle area (don't draw below baseline)
            if (oy > cy + 2f) continue

            val colour  = Color(obj.objectClass.colorArgb.toInt())
            // Glow ring
            val dotR    = (10f + obj.snrDb / 4f).coerceIn(8f, 24f)
            drawCircle(colour.copy(alpha = 0.35f), dotR * 2.2f, Offset(ox, oy))
            // Solid dot — brighter than before
            drawCircle(colour.copy(alpha = 0.95f), dotR, Offset(ox, oy))
            drawCircle(Color.White.copy(alpha = 0.6f), dotR * 0.35f, Offset(ox, oy))

            // Velocity vector
            if (abs(obj.speedMps) > 0.1f) {
                val vLen  = (abs(obj.speedMps) / 10f * radius * 0.18f).coerceAtMost(radius * 0.2f)
                val sign  = if (obj.isApproaching) -1f else 1f
                drawLine(colour.copy(alpha = 0.9f),
                    Offset(ox, oy),
                    Offset(ox + sign * sin(azRad) * vLen, oy - sign * cos(azRad) * vLen),
                    strokeWidth = 2.5f)
            }
        }
    }
}

// ── Object list header + compact rows ────────────────────────────────────────

@Composable
private fun ObjectListHeader() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        listOf("Class" to 0.22f, "Dist" to 0.13f, "Speed" to 0.15f,
               "Dir" to 0.13f, "Conf" to 0.10f, "SNR" to 0.12f, "Az°" to 0.10f)
            .forEach { (label, w) ->
                Text(label, color = RadarAccent.copy(alpha = 0.7f),
                    fontSize = 9.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(w))
            }
    }
}

@Composable
private fun ObjectListRow(obj: DetectedObject) {
    val colour  = Color(obj.objectClass.colorArgb.toInt())
    val speedStr = "${"%.1f".format(abs(obj.speedKmh))}${if (obj.isApproaching) "▲" else if (obj.isReceding) "▼" else "–"}"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(RadarDarkMid.copy(alpha = 0.6f), RoundedCornerShape(3.dp))
            .padding(horizontal = 4.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment     = Alignment.CenterVertically
    ) {
        // class indicator dot + name
        Row(modifier = Modifier.weight(0.22f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(colour))
            Text(obj.objectClass.displayName.take(7), color = colour, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
        Text("${"%.1f".format(obj.distanceM)}m",  color = RadarOnSurface, fontSize = 9.sp, modifier = Modifier.weight(0.13f))
        Text("$speedStr km/h",
            color = if (obj.isApproaching) RadarError else RadarOnSurface.copy(alpha = 0.85f),
            fontSize = 9.sp, modifier = Modifier.weight(0.15f))
        Text(obj.direction.displayName.take(6), color = RadarOnSurface.copy(alpha = 0.7f), fontSize = 9.sp, modifier = Modifier.weight(0.13f))
        Text("${"%.0f".format(obj.confidence * 100)}%", color = RadarOnSurface.copy(alpha = 0.7f), fontSize = 9.sp, modifier = Modifier.weight(0.10f))
        Text("${"%.1f".format(obj.snrDb)}dB", color = RadarOnSurface.copy(alpha = 0.7f), fontSize = 9.sp, modifier = Modifier.weight(0.12f))
        Text("${"%.0f".format(obj.azimuthDeg)}°", color = RadarOnSurface.copy(alpha = 0.6f), fontSize = 9.sp, modifier = Modifier.weight(0.10f))
    }
}

// ── FMCW parameters panel ────────────────────────────────────────────────────

@Composable
private fun FmcwPanel(cfg: TinyRadConfig, frame: RadarFrame?) {
    val rangeResM = 3e8f / (2f * cfg.bandwidthMHz * 1e6f)
    val maxRange  = cfg.maxRangeM
    Column(
        modifier = Modifier.fillMaxSize().background(RadarDarkMid, RoundedCornerShape(8.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("FMCW Parameters", color = RadarAccent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        HorizontalDivider(color = RadarSurface)
        val items = listOf(
            "Bandwidth"       to "${"%.0f".format(cfg.bandwidthMHz)} MHz",
            "Range resolution" to "${"%.3f".format(rangeResM)} m",
            "Min range"       to "0.00 m",
            "Max range"       to "${"%.1f".format(maxRange)} m",
            "Histogram points" to "${frame?.rangeBins ?: "—"}",
            "Chirp period"    to "40 ms",
            "Samples/chirp"   to "128",
            "Rx channels"     to "4"
        )
        items.forEach { (k, v) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(k, color = RadarOnSurface.copy(alpha = 0.6f), fontSize = 11.sp)
                Text(v, color = RadarOnSurface, fontSize = 11.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

// ── Range-Doppler panel ───────────────────────────────────────────────────────

@Composable
fun RangeDopplerPanel(frame: RadarFrame, modifier: Modifier = Modifier) {
    val cfg = remember { mapOf("minRange" to 0f, "maxRange" to 50f) }
    Canvas(modifier = modifier.background(RadarDarkMid, RoundedCornerShape(8.dp))) {
        if (frame.rangeBins == 0 || frame.dopplerBins == 0) return@Canvas
        val mag  = frame.rangeDopplerMag
        val nr   = frame.rangeBins
        val nd   = frame.dopplerBins
        val minM = mag.minOrNull() ?: -60f
        val maxM = mag.maxOrNull() ?: 0f
        val rng  = (maxM - minM).coerceAtLeast(1f)
        val pw   = size.width  / nd
        val ph   = size.height / nr

        for (r in 0 until nr) {
            for (d in 0 until nd) {
                val v   = ((mag[r * nd + d] - minM) / rng).coerceIn(0f, 1f)
                drawRect(radarColormap(v), Offset(d * pw, r * ph), Size(pw.coerceAtLeast(1f), ph.coerceAtLeast(1f)))
            }
        }
        // Axis labels via native canvas
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.argb(160, 176, 196, 222)
            textSize = 8.dp.toPx()
        }
        drawContext.canvas.nativeCanvas.apply {
            drawText("0 m/s", size.width / 2f, size.height - 2.dp.toPx(), paint)
            drawText("Range →", 2.dp.toPx(), 10.dp.toPx(), paint)
        }
    }
}

// ── DBF (Digital Beam-Forming) panel — azimuth-range map ─────────────────────
// Single Rx: azimuth is estimated from Doppler sign; with full 4-Rx
// phase processing this would be a proper beamformed image.

@Composable
private fun DbfPanel(frame: RadarFrame, objects: List<DetectedObject>, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.background(RadarDarkMid, RoundedCornerShape(8.dp))) {
        // Placeholder: draw detected objects as azimuth-range dots on a grid
        val w = size.width; val h = size.height
        // Azimuth axis: -90° to +90° → 0..w
        // Range axis:   0..maxRange → h..0
        val maxRng = 50f
        for (i in 1..4) {
            val y = h - h * i / 4f
            drawLine(RadarAccent.copy(alpha = 0.1f), Offset(0f, y), Offset(w, y), 0.5f)
            drawLine(RadarAccent.copy(alpha = 0.1f), Offset(w * i / 4f, 0f), Offset(w * i / 4f, h), 0.5f)
        }
        for (obj in objects) {
            val x = w * (obj.azimuthDeg + 90f) / 180f
            val y = h - h * (obj.distanceM / maxRng).coerceIn(0f, 1f)
            val c = Color(obj.objectClass.colorArgb.toInt())
            drawCircle(c.copy(alpha = 0.4f), 16f, Offset(x, y))
            drawCircle(c.copy(alpha = 0.9f), 7f,  Offset(x, y))
        }
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.argb(140, 176, 196, 222); textSize = 8.dp.toPx()
        }
        drawContext.canvas.nativeCanvas.apply {
            drawText("-90°", 2.dp.toPx(), h - 2.dp.toPx(), paint)
            drawText("0°", w/2f - 8.dp.toPx(), h - 2.dp.toPx(), paint)
            drawText("+90°", w - 24.dp.toPx(), h - 2.dp.toPx(), paint)
            drawText("Range →", 2.dp.toPx(), 10.dp.toPx(), paint)
        }
    }
}

// ── Range-Time panel ─────────────────────────────────────────────────────────
// Horizontal axis = time (frames), vertical = range bins
// Colour mapped from cyan (max negative) through blue/black/red to yellow (max positive)

@Composable
private fun RangeTimePanel(
    buffer: List<FloatArray>,
    frame:  RadarFrame?,
    modifier: Modifier = Modifier
) {
    if (buffer.isEmpty()) {
        Box(modifier = modifier.background(RadarDarkMid, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center) {
            Text("Acquiring range-time data…", color = RadarOnSurface.copy(alpha = 0.4f), fontSize = 11.sp)
        }
        return
    }
    Canvas(modifier = modifier.background(RadarDarkMid, RoundedCornerShape(8.dp))) {
        val nT   = buffer.size
        val nR   = buffer.first().size
        val pw   = size.width  / nT
        val ph   = size.height / nR

        // Find global min/max for normalisation
        var gMin = Float.MAX_VALUE; var gMax = Float.MIN_VALUE
        buffer.forEach { p -> p.forEach { v -> if (v < gMin) gMin = v; if (v > gMax) gMax = v } }
        val rng = (gMax - gMin).coerceAtLeast(1f)

        buffer.forEachIndexed { t, profile ->
            profile.forEachIndexed { r, v ->
                val norm = ((v - gMin) / rng).coerceIn(0f, 1f)
                drawRect(radarColormap(norm),
                    Offset(t * pw, r * ph), Size(pw.coerceAtLeast(1f), ph.coerceAtLeast(1f)))
            }
        }
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.argb(140, 176, 196, 222); textSize = 8.dp.toPx()
        }
        drawContext.canvas.nativeCanvas.apply {
            drawText("Time →", 2.dp.toPx(), 10.dp.toPx(), paint)
            drawText("Range ↓", 2.dp.toPx(), size.height - 2.dp.toPx(), paint)
        }
    }
}

// ── Colour maps ───────────────────────────────────────────────────────────────

/** Radar-style colourmap: cyan→blue→black→red→yellow (symmetric around 0.5) */
fun radarColormap(v: Float): Color = when {
    v < 0.1f -> Color(0f,       v / 0.1f,    1f,          1f)   // cyan → blue
    v < 0.3f -> Color(0f,       1f-(v-0.1f)/0.2f, 1f,     1f)   // blue → deep blue
    v < 0.5f -> Color(0f,       0f,          1f-(v-0.3f)/0.2f,  1f) // deep blue → black
    v < 0.7f -> Color((v-0.5f)/0.2f, 0f,    0f,          1f)   // black → red
    v < 0.9f -> Color(1f,       (v-0.7f)/0.2f, 0f,        1f)   // red → yellow
    else     -> Color(1f,       1f,          (v-0.9f)/0.1f, 1f) // yellow → white
}
