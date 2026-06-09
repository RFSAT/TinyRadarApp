package com.rfsat.tinyrad.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

enum class LogLevel { DEBUG, INFO, WARNING, ERROR }

data class LogEntry(
    val level:      LogLevel,
    val message:    String,
    val timestamp:  Instant = Instant.now()
)

object AppLog {
    private const val MAX = 500

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries = _entries.asStateFlow()

    private fun add(level: LogLevel, msg: String) {
        val updated = (_entries.value + LogEntry(level, msg)).takeLast(MAX)
        _entries.value = updated
    }

    fun debug(msg: String)   = add(LogLevel.DEBUG,   msg)
    fun info(msg: String)    = add(LogLevel.INFO,     msg)
    fun warn(msg: String)    = add(LogLevel.WARNING,  msg)
    fun error(msg: String)   = add(LogLevel.ERROR,    msg)
}
