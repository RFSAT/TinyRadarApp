package com.rfsat.tinyrad.data.repository

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

enum class LogLevel { DEBUG, INFO, WARNING, ERROR }

data class LogEntry(
    val level:     LogLevel,
    val message:   String,
    val timestamp: Instant = Instant.now()
)

/**
 * Application-wide in-memory log with optional file mirror.
 *
 * File logging:
 *   Call [AppLog.init(context)] once from Application or MainActivity.
 *   Writes to <external-files>/TinyRAD/tinyrad_log.txt (rotates at ~512 KB).
 *   File is flushed after every ERROR entry and every 20 entries otherwise,
 *   so the last commands before a crash are always on disk.
 */
object AppLog {

    private const val MAX_ENTRIES  = 200
    private const val MAX_LOG_BYTES = 512 * 1024L   // 512 KB before rotation
    private const val FLUSH_EVERY  = 20

    private val TIME_FMT = DateTimeFormatter
        .ofPattern("HH:mm:ss.SSS")
        .withZone(ZoneOffset.UTC)

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries = _entries.asStateFlow()

    // ── File writer ───────────────────────────────────────────────────────────

    @Volatile private var logFile:  File?        = null
    @Volatile private var writer:   PrintWriter? = null
    @Volatile private var writeCount = 0

    /**
     * Initialise file logging. Safe to call multiple times; second call is a no-op.
     * Call from Application.onCreate() or MainActivity.onCreate().
     */
    fun init(context: Context) {
        if (logFile != null) return
        try {
            val dir  = File(context.getExternalFilesDir(null), "TinyRAD").also { it.mkdirs() }
            val file = File(dir, "tinyrad_log.txt")
            logFile  = file
            openWriter(file)
            info("Log file: ${file.absolutePath}")
        } catch (e: Exception) {
            android.util.Log.e("AppLog", "Could not open log file: ${e.message}")
        }
    }

    private fun openWriter(file: File) {
        writer?.close()
        // Rotate if too large
        if (file.exists() && file.length() > MAX_LOG_BYTES) {
            val bak = File(file.parent, "tinyrad_log_prev.txt")
            bak.delete()
            file.renameTo(bak)
        }
        writer = PrintWriter(FileWriter(file, true), false)
        writeCount = 0
    }

    /** Return the current log file path, or null if file logging is not active. */
    fun logFilePath(): String? = logFile?.absolutePath

    // ── Add entry ─────────────────────────────────────────────────────────────

    private fun add(level: LogLevel, msg: String) {
        val entry   = LogEntry(level, msg)
        val updated = (_entries.value + entry).takeLast(MAX_ENTRIES)
        _entries.value = updated

        // Write to file
        try {
            val w = writer ?: return
            val ts = TIME_FMT.format(entry.timestamp)
            w.println("$ts  ${level.name.padEnd(7)}  $msg")
            writeCount++
            // Flush immediately on ERROR so crash logs are always complete
            if (level == LogLevel.ERROR || writeCount % FLUSH_EVERY == 0) {
                w.flush()
            }
            // Rotate if file grew too large
            logFile?.let { f ->
                if (f.length() > MAX_LOG_BYTES) openWriter(f)
            }
        } catch (_: Exception) {}
    }

    fun debug(msg: String)   = add(LogLevel.DEBUG,   msg)
    fun info(msg: String)    = add(LogLevel.INFO,     msg)
    fun warn(msg: String)    = add(LogLevel.WARNING,  msg)
    fun error(msg: String)   = add(LogLevel.ERROR,    msg)

    /** Flush and close the file writer (call from onDestroy). */
    fun close() {
        writer?.flush()
        writer?.close()
        writer = null
    }
}
