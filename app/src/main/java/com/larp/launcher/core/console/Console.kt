package com.larp.launcher.core.console

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class ConsoleEntry(
    val timestamp: Long,
    val level: Level,
    val tag: String,
    val message: String,
    val throwable: String? = null
) {
    enum class Level { Verbose, Debug, Info, Warn, Error }
}

@Singleton
class Console @Inject constructor(
    private val context: Context,
    private val json: Json
) {
    private val maxEntries = 500
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val _entries = MutableStateFlow<List<ConsoleEntry>>(emptyList())
    val entries: StateFlow<List<ConsoleEntry>> = _entries.asStateFlow()

    private val lock = Any()

    private val logFile: File
        get() {
            val dir = File(context.filesDir, "console").apply { mkdirs() }
            return File(dir, "log.jsonl")
        }

    init {
        reload()
    }

    fun v(tag: String, msg: String) = append(ConsoleEntry.Level.Verbose, tag, msg)
    fun d(tag: String, msg: String) = append(ConsoleEntry.Level.Debug, tag, msg)
    fun i(tag: String, msg: String) = append(ConsoleEntry.Level.Info, tag, msg)
    fun w(tag: String, msg: String, t: Throwable? = null) = append(ConsoleEntry.Level.Warn, tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = append(ConsoleEntry.Level.Error, tag, msg, t)

    fun log(level: ConsoleEntry.Level, tag: String, msg: String, details: String?) = append(level, tag, msg, null, details)

    fun clear() {
        synchronized(lock) {
            runCatching { logFile.writeText("") }
            _entries.value = emptyList()
        }
    }

    fun reload() {
        synchronized(lock) {
            runCatching {
                val file = logFile
                if (!file.isFile) return@runCatching
                val lines = file.readLines().takeLast(maxEntries)
                val list = lines.mapNotNull { line ->
                    runCatching { json.decodeFromString(ConsoleEntry.serializer(), line) }.getOrNull()
                }
                if (list != _entries.value) _entries.value = list
                if (file.length() > MAX_FILE_BYTES) file.writeText(lines.joinToString("\n", postfix = "\n"))
            }
        }
    }

    fun formatTimestamp(entry: ConsoleEntry): String = dateFormat.format(Date(entry.timestamp))

    private fun append(level: ConsoleEntry.Level, tag: String, msg: String, t: Throwable? = null, details: String? = null) {
        val entry = ConsoleEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = msg,
            throwable = details?.take(MAX_DETAILS) ?: t?.let { unwrap(it).stackTraceToString().take(2000) }
        )
        synchronized(lock) {
            val current = _entries.value.toMutableList()
            current.add(entry)
            if (current.size > maxEntries) {
                current.subList(0, current.size - maxEntries).clear()
            }
            _entries.value = current
            runCatching {
                logFile.appendText(json.encodeToString(ConsoleEntry.serializer(), entry).replace('\n', ' ') + "\n")
            }
        }
        when (level) {
            ConsoleEntry.Level.Verbose -> Log.v(tag, msg)
            ConsoleEntry.Level.Debug -> Log.d(tag, msg)
            ConsoleEntry.Level.Info -> Log.i(tag, msg)
            ConsoleEntry.Level.Warn -> Log.w(tag, msg, t)
            ConsoleEntry.Level.Error -> Log.e(tag, msg, t)
        }
    }

    private fun unwrap(t: Throwable): Throwable {
        var cur: Throwable = t
        while (cur.cause != null && cur.cause !== cur) cur = cur.cause!!
        return cur
    }

    private companion object {
        const val MAX_FILE_BYTES = 1_000_000L
        const val MAX_DETAILS = 60_000
    }
}
