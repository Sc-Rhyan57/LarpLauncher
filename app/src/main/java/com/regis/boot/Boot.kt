package com.regis.boot

import android.system.Os
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CopyOnWriteArrayList

object Boot {

    private const val TAG = "LarpBoot"
    private const val TAIL_BYTES = 24_000L
    private const val MAX_HISTORY = 200
    private val STATUS_FILES = listOf("fusion.stage", "fusion.msg", "fusion.log")

    private val history = CopyOnWriteArrayList<String>()
    @Volatile private var localStage = 0
    @Volatile private var localMessage = ""

    private fun statusDir(): File? =
        Os.getenv("FUSION_LOG_DIR")?.takeIf { it.isNotBlank() }?.let { File(it) }

    private fun readStatus(name: String): String? {
        val dir = statusDir() ?: return null
        val file = File(dir, name)
        return runCatching { if (file.isFile) file.readText().trim() else null }.getOrNull()
    }

    val stage: Int get() = readStatus("fusion.stage")?.toIntOrNull() ?: localStage

    val message: String get() = readStatus("fusion.msg")?.takeIf { it.isNotEmpty() } ?: localMessage

    @JvmStatic
    fun status(stage: Int, message: String?) {
        localStage = stage
        val text = message.orEmpty()
        localMessage = text
        note(text)
        Log.i(TAG, "stage=$stage $text")
    }

    @JvmStatic
    fun note(text: String) {
        if (text.isBlank()) return
        history.add(text)
        while (history.size > MAX_HISTORY) history.removeAt(0)
    }

    @JvmStatic
    fun lines(): List<String> = history.toList() + fusionLog()

    @JvmStatic
    fun clear() {
        localStage = 0
        localMessage = ""
        history.clear()
        val dir = statusDir() ?: return
        // guarda o log da execução anterior (se o jogo crashou, é a única pista)
        runCatching {
            val last = File(dir, "fusion.log")
            val prev = File(dir, "fusion.prev.log")
            if (last.isFile && last.length() > 0) { prev.delete(); last.renameTo(prev) }
        }
        STATUS_FILES.forEach { name -> runCatching { File(dir, name).delete() } }
    }

    @JvmStatic
    fun previousLog(dir: File): List<String> = runCatching {
        val f = File(dir, "fusion.prev.log")
        if (!f.isFile) emptyList() else {
            RandomAccessFile(f, "r").use { raf ->
                val start = maxOf(0L, raf.length() - 6000L)
                raf.seek(start)
                val b = ByteArray((raf.length() - start).toInt()); raf.readFully(b)
                String(b, Charsets.UTF_8).lines().filter { it.isNotBlank() }
            }
        }
    }.getOrDefault(emptyList())

    private fun fusionLog(): List<String> {
        val dir = statusDir() ?: return emptyList()
        val file = File(dir, "fusion.log")
        if (!file.isFile) return emptyList()
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val size = raf.length()
                val start = maxOf(0L, size - TAIL_BYTES)
                raf.seek(start)
                val buffer = ByteArray((size - start).toInt())
                raf.readFully(buffer)
                val all = String(buffer, Charsets.UTF_8).lines().filter { it.isNotBlank() }
                if (start > 0) all.drop(1) else all
            }
        }.getOrDefault(emptyList())
    }
}
