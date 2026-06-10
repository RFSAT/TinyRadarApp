package com.rfsat.tinyrad.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rfsat.tinyrad.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CsvViewerScreen(filePath: String, onBack: () -> Unit) {
    val file = remember { File(filePath) }

    // Load file on IO thread
    var headers by remember { mutableStateOf<List<String>>(emptyList()) }
    var rows    by remember { mutableStateOf<List<List<String>>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error   by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(filePath) {
        withContext(Dispatchers.IO) {
            try {
                val lines = file.readLines()
                // Skip comment lines starting with #
                val dataLines = lines.filter { !it.startsWith("#") && it.isNotBlank() }
                if (dataLines.isEmpty()) { error = "No data in file"; loading = false; return@withContext }
                val h = dataLines.first().split(",")
                val r = dataLines.drop(1).take(500).map { it.split(",") }  // max 500 rows in viewer
                headers = h; rows = r
            } catch (e: Exception) {
                error = e.message
            }
            loading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title  = { Text(file.name, fontSize = 13.sp, maxLines = 1) },
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
        when {
            loading -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = RadarAccent)
            }
            error != null -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text("Error: $error", color = RadarError)
            }
            else -> {
                val hScroll = rememberScrollState()
                Column(Modifier.fillMaxSize().padding(pad)) {
                    // File info bar
                    Text(
                        "${rows.size} rows  •  ${headers.size} columns  •  ${"%.1f".format(file.length() / 1024f)} KB",
                        color    = RadarOnSurface.copy(alpha = 0.5f),
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                    // Scrollable table
                    Box(modifier = Modifier.fillMaxSize().horizontalScroll(hScroll)) {
                        LazyColumn(modifier = Modifier.fillMaxWidth()) {
                            // Header row
                            item {
                                TableRow(
                                    cells     = headers,
                                    isHeader  = true,
                                    rowIndex  = -1
                                )
                            }
                            // Data rows
                            itemsIndexed(rows) { idx, row ->
                                TableRow(cells = row, isHeader = false, rowIndex = idx)
                            }
                            // Truncation notice
                            if (rows.size == 500) {
                                item {
                                    Text(
                                        "Showing first 500 rows. Export the full file for complete data.",
                                        color    = RadarWarning.copy(alpha = 0.7f),
                                        fontSize = 10.sp,
                                        modifier = Modifier.padding(8.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TableRow(cells: List<String>, isHeader: Boolean, rowIndex: Int) {
    val bg = when {
        isHeader       -> RadarSurface
        rowIndex % 2 == 0 -> RadarDarkMid
        else           -> RadarDark
    }
    Row(
        modifier = Modifier
            .background(bg)
            .padding(vertical = if (isHeader) 5.dp else 3.dp)
    ) {
        cells.forEach { cell ->
            Text(
                cell.trim(),
                color      = if (isHeader) RadarAccent else RadarOnSurface.copy(alpha = 0.85f),
                fontSize   = if (isHeader) 10.sp else 9.sp,
                fontWeight = if (isHeader) FontWeight.Bold else FontWeight.Normal,
                fontFamily = FontFamily.Monospace,
                modifier   = Modifier
                    .width(110.dp)
                    .padding(horizontal = 4.dp),
                maxLines   = 1
            )
        }
    }
}
