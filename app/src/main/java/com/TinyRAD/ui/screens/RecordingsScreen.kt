package com.TinyRAD.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.TinyRAD.ui.theme.*
import com.TinyRAD.viewmodel.TinyRadViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingsScreen(viewModel: TinyRadViewModel, onBack: () -> Unit, onViewFile: (String) -> Unit = {}) {
    val context = LocalContext.current
    var files   by remember { mutableStateOf(viewModel.listRecordings()) }
    var deleteTarget by remember { mutableStateOf<File?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title  = { Text("Recordings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { files = viewModel.listRecordings() }) {
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
        if (files.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text("No recordings yet", color = RadarOnSurface.copy(alpha = 0.4f))
            }
        } else {
            LazyColumn(
                modifier            = Modifier.padding(pad).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(files) { file ->
                    RecordingItem(
                        file     = file,
                        onShare  = {
                            val uri = FileProvider.getUriForFile(
                                context, "${context.packageName}.fileprovider", file
                            )
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type     = "text/csv"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, "Share CSV"))
                        },
                        onView   = { onViewFile(file.absolutePath) },
                        onDelete = { deleteTarget = file }
                    )
                }
            }
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title  = { Text("Delete recording?") },
            text   = { Text(target.name, color = RadarOnSurface) },
            confirmButton = {
                TextButton(onClick = {
                    target.delete()
                    files       = viewModel.listRecordings()
                    deleteTarget = null
                }) { Text("Delete", color = RadarError) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
            containerColor = RadarDarkMid
        )
    }
}

@Composable
private fun RecordingItem(file: File, onShare: () -> Unit, onDelete: () -> Unit, onView: () -> Unit) {
    val sdf = remember { SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()) }
    Card(
        shape  = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = RadarDarkMid)
    ) {
        Row(
            modifier          = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(file.nameWithoutExtension, color = RadarOnSurface, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                Text(
                    "${sdf.format(Date(file.lastModified()))}  •  ${"%.1f".format(file.length() / 1024f)} KB",
                    color    = RadarOnSurface.copy(alpha = 0.5f),
                    fontSize = 11.sp
                )
            }
            IconButton(onClick = onView)   { Icon(Icons.Default.TableChart, "View",   tint = RadarAccent) }
            IconButton(onClick = onShare)  { Icon(Icons.Default.Share,      "Share",  tint = RadarAccent) }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete,     "Delete", tint = RadarError) }
        }
    }
}
