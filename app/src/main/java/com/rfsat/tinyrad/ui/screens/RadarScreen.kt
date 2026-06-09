package com.rfsat.tinyrad.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rfsat.tinyrad.data.models.*
import com.rfsat.tinyrad.ui.theme.*
import com.rfsat.tinyrad.viewmodel.TinyRadViewModel
import kotlin.math.*

@Composable
fun RadarScreen(
    viewModel: TinyRadViewModel,
    onDisconnect: () -> Unit
) {
    val state   by viewModel.uiState.collectAsState()
    val frame   = state.currentFrame
    val objects = state.trackedObjects

    Column(
        modifier = Modifier.fillMaxSize().background(RadarDark)
    ) {
        // ── Top bar ──────────────────────────────────────────────────────────
        TopBar(
            frameRate    = state.frameRate,
            totalFrames  = state.totalFrames,
            isRecording  = state.isRecording,
            recordingRows= state.recordingRows,
            onRecord     = {
                if (state.isRecording) viewModel.stopRecording()
                else viewModel.startRecording()
            },
            onStop       = {
                viewModel.stopStreaming()
                onDisconnect()
            }
        )

        // ── Main content ─────────────────────────────────────────────────────
        Row(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Left: radar PPI display
            Box(
                modifier    = Modifier.weight(1f).aspectRatio(1f)
                    .background(RadarDarkMid, RoundedCornerShape(12.dp))
            ) {
                RadarPpiView(
                    objects   = objects,
                    maxRangeM = state.config.maxRangeM,
                    modifier  = Modifier.fillMaxSize()
                )
            }

            // Right: object list + stats
            Column(
                modifier             = Modifier.width(160.dp).fillMaxHeight(),
                verticalArrangement  = Arrangement.spacedBy(8.dp)
            ) {
                // Summary card
                Card(
                    shape  = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = RadarDarkMid)
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Objects: ${objects.size}", color = RadarAccent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("FPS: ${"%.1f".format(state.frameRate)}", color = RadarOnSurface, fontSize = 12.sp)
                        frame?.let {
                            Text("Range res: ${"%.2f".format(it.rangeResM)} m", color = RadarOnSurface, fontSize = 11.sp)
                        }
                    }
                }

                // Object list
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(objects) { obj ->
                        ObjectCard(obj)
                    }
                }
            }
        }

        // ── Range-Doppler mini-map ────────────────────────────────────────────
        frame?.let { f ->
            if (f.rangeDopplerMag.isNotEmpty()) {
                RangeDopplerMap(
                    frame    = f,
                    modifier = Modifier.fillMaxWidth().height(100.dp)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

// ── Top bar ───────────────────────────────────────────────────────────────────

@Composable
private fun TopBar(
    frameRate:     Float,
    totalFrames:   Long,
    isRecording:   Boolean,
    recordingRows: Int,
    onRecord:      () -> Unit,
    onStop:        () -> Unit
) {
    Row(
        modifier             = Modifier.fillMaxWidth()
            .background(RadarDarkMid)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment    = Alignment.CenterVertically
    ) {
        Column {
            Text("Live Radar", fontWeight = FontWeight.Bold, color = RadarOnSurface, fontSize = 14.sp)
            Text(
                "${"%.1f".format(frameRate)} fps  •  frame $totalFrames",
                color = RadarOnSurface.copy(alpha = 0.5f), fontSize = 11.sp
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isRecording) {
                Text(
                    "REC $recordingRows rows",
                    color    = RadarError,
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
            }
            IconButton(onClick = onRecord) {
                Icon(
                    if (isRecording) Icons.Default.StopCircle else Icons.Default.FiberManualRecord,
                    contentDescription = if (isRecording) "Stop recording" else "Start recording",
                    tint = if (isRecording) RadarError else RadarOnSurface
                )
            }
            IconButton(onClick = onStop) {
                Icon(Icons.Default.Stop, contentDescription = "Stop streaming", tint = RadarWarning)
            }
        }
    }
}

// ── Plan-Position Indicator (PPI) canvas ─────────────────────────────────────

@Composable
fun RadarPpiView(
    objects:   List<DetectedObject>,
    maxRangeM: Float,
    modifier:  Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val cx    = size.width  / 2f
        val cy    = size.height / 2f
        val radius = minOf(cx, cy) - 8.dp.toPx()

        // Grid rings
        for (i in 1..4) {
            val r = radius * i / 4f
            drawCircle(
                color  = RadarAccent.copy(alpha = 0.15f),
                radius = r,
                center = Offset(cx, cy),
                style  = Stroke(width = 1f)
            )
        }
        // Crosshairs
        drawLine(RadarAccent.copy(alpha = 0.15f), Offset(cx, cy - radius), Offset(cx, cy + radius), 1f)
        drawLine(RadarAccent.copy(alpha = 0.15f), Offset(cx - radius, cy), Offset(cx + radius, cy), 1f)

        // Detected objects
        for (obj in objects) {
            val normDist = (obj.distanceM / maxRangeM).coerceIn(0f, 1f)
            val rPx      = normDist * radius
            val azRad    = Math.toRadians(obj.azimuthDeg.toDouble()).toFloat()
            val ox       = cx + rPx * sin(azRad)
            val oy       = cy - rPx * cos(azRad)
            val dotR     = (8f + obj.snrDb / 5f).coerceIn(6f, 20f)

            val colour = Color(obj.objectClass.colorArgb.toInt())
            drawCircle(colour.copy(alpha = 0.25f), dotR * 1.8f, Offset(ox, oy))
            drawCircle(colour, dotR, Offset(ox, oy))
            // Velocity vector
            if (abs(obj.speedMps) > 0.1f) {
                val vecLen = (abs(obj.speedMps) / 10f * radius * 0.2f).coerceAtMost(radius * 0.25f)
                val sign   = if (obj.isApproaching) -1f else 1f
                drawLine(
                    colour.copy(alpha = 0.8f),
                    Offset(ox, oy),
                    Offset(ox + sign * sin(azRad) * vecLen, oy - sign * cos(azRad) * vecLen),
                    strokeWidth = 2f
                )
            }
        }
    }
}

// ── Per-object card ───────────────────────────────────────────────────────────

@Composable
fun ObjectCard(obj: DetectedObject) {
    val colour = Color(obj.objectClass.colorArgb.toInt())
    Card(
        shape  = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = RadarSurface)
    ) {
        Row(
            modifier          = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(colour))
            Column {
                Text(
                    obj.objectClass.displayName,
                    color      = colour,
                    fontWeight = FontWeight.SemiBold,
                    fontSize   = 12.sp
                )
                Text("${"%.1f".format(obj.distanceM)} m", color = RadarOnSurface, fontSize = 11.sp)
                Text(
                    "${"%.1f".format(obj.speedKmh)} km/h ${if (obj.isApproaching) "▲" else if (obj.isReceding) "▼" else "—"}",
                    color    = if (obj.isApproaching) RadarError else RadarOnSurface.copy(alpha = 0.7f),
                    fontSize = 11.sp
                )
                Text(
                    "${"%.0f".format(obj.confidence * 100)}% conf  SNR ${"%.0f".format(obj.snrDb)}dB",
                    color    = RadarOnSurface.copy(alpha = 0.5f),
                    fontSize = 10.sp
                )
            }
        }
    }
}

// ── Range-Doppler heatmap ─────────────────────────────────────────────────────

@Composable
fun RangeDopplerMap(frame: RadarFrame, modifier: Modifier = Modifier) {
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

        for (r in 0 until nr.coerceAtMost(50)) {    // subsample for perf
            for (d in 0 until nd) {
                val v   = ((mag[r * nd + d] - minM) / rng).coerceIn(0f, 1f)
                val col = heatmapColour(v)
                drawRect(col, Offset(d * pw, r * ph), androidx.compose.ui.geometry.Size(pw, ph))
            }
        }
    }
}

/** Map 0..1 → thermal colour (black → blue → cyan → green → yellow → red) */
private fun heatmapColour(v: Float): Color {
    return when {
        v < 0.2f -> Color(0f, 0f, v / 0.2f, 1f)
        v < 0.4f -> Color(0f, (v - 0.2f) / 0.2f, 1f, 1f)
        v < 0.6f -> Color(0f, 1f, 1f - (v - 0.4f) / 0.2f, 1f)
        v < 0.8f -> Color((v - 0.6f) / 0.2f, 1f, 0f, 1f)
        else     -> Color(1f, 1f - (v - 0.8f) / 0.2f, 0f, 1f)
    }
}
