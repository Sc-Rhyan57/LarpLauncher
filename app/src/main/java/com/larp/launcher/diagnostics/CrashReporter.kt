package com.larp.launcher.diagnostics

import android.content.Context
import android.os.FileObserver
import android.util.Log
import com.larp.launcher.core.console.Console
import com.larp.launcher.core.console.ConsoleEntry
import java.io.File
import java.io.RandomAccessFile

object CrashReporter {
    private const val TAG = "Crash"
    private const val TAIL_BYTES = 80_000L
    private const val STALE_MS = 300_000L
    private var observers = ArrayList<FileObserver>()

    private fun dirs(context: Context): List<File> = listOfNotNull(
        File(context.filesDir, "BepInEx"),
        context.getExternalFilesDir(null)?.let { File(it, "BepInEx") }
    )

    fun installJavaHandler(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val dir = File(context.filesDir, "BepInEx").apply { mkdirs() }
        Thread.setDefaultUncaughtExceptionHandler { thread, t ->
            runCatching {
                File(dir, "crash.log").writeText(
                    "==== CRASH JAVA (Fatal) ====\nthread: ${thread.name}\n" +
                        t.stackTraceToString().take(20000) + "\n==== FIM DO CRASH ====\n"
                )
            }
            previous?.uncaughtException(thread, t)
        }
    }

    fun watch(context: Context, console: Console) {
        dirs(context).forEach { dir ->
            Thread { consume(dir, console) }.start()
            dir.mkdirs()
            val obs = object : FileObserver(dir, CLOSE_WRITE or MOVED_TO) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == "crash.log") Thread { consume(dir, console) }.start()
                }
            }
            obs.startWatching()
            observers.add(obs)
        }
    }

    private fun tail(file: File, bytes: Long = TAIL_BYTES): String = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val start = maxOf(0L, raf.length() - bytes)
            raf.seek(start)
            val buffer = ByteArray((raf.length() - start).toInt())
            raf.readFully(buffer)
            val text = String(buffer, Charsets.UTF_8)
            if (start > 0) text.substringAfter('\n', text) else text
        }
    }.getOrDefault("")

    private fun recent(file: File, crashTime: Long): Boolean =
        file.isFile && file.length() > 0 && file.lastModified() >= crashTime - STALE_MS

    @Synchronized
    private fun consume(dir: File, console: Console) {
        val file = File(dir, "crash.log")
        if (!file.isFile || file.length() == 0L) return
        val crashTime = file.lastModified()
        val report = runCatching { file.readText() }.getOrDefault("").trim()
        runCatching { File(dir, "crash.prev.log").apply { delete() }.let { file.renameTo(it) } }
        val lines = report.lines().filter { it.isNotBlank() }
        val native = lines.firstOrNull()?.contains("NATIVO") == true
        val title = if (native) {
            val signal = lines.firstOrNull { it.startsWith("sinal:") }?.removePrefix("sinal: ")?.substringBefore(" addr=").orEmpty()
            val stage = lines.firstOrNull { it.startsWith("etapa:") }?.removePrefix("etapa: ")?.substringBefore(" (stage=").orEmpty()
            "$signal em $stage"
        } else {
            lines.getOrNull(2).orEmpty()
        }
        console.log(ConsoleEntry.Level.Error, TAG, "O jogo crashou: $title", report)

        val pid = Regex("pid=(\\d+)").find(report)?.groupValues?.get(1)
        if (pid != null) {
            runCatching {
                Thread.sleep(1500)
                val proc = ProcessBuilder("logcat", "-d", "-v", "threadtime", "--pid=$pid", "-t", "800")
                    .redirectErrorStream(true).start()
                val text = proc.inputStream.bufferedReader().readText()
                proc.waitFor()
                if (text.isNotBlank()) {
                    console.log(ConsoleEntry.Level.Warn, TAG, "Logcat do jogo (pid $pid)", text.lines().takeLast(400).joinToString("\n"))
                }
            }
        }

        val fusion = File(dir, "fusion.log")
        val fusionText = if (fusion.isFile) tail(fusion) else ""
        val fusionLines = fusionText.lines()
        val idx = fusionLines.indexOfLast { it.contains("Unhandled exception") }
        if (idx >= 0) {
            val block = fusionLines.drop(idx).takeWhile { !it.startsWith("==== CRASH") }
            val head = block.first().substringAfter("Unhandled exception.").trim().take(200)
            console.log(ConsoleEntry.Level.Error, TAG, "Excecao .NET sem tratamento: $head", block.joinToString("\n"))
        }
        if (fusionText.isNotBlank()) {
            console.log(ConsoleEntry.Level.Warn, TAG, "Log completo do jogo (fusion.log)", fusionLines.takeLast(400).joinToString("\n"))
        }
        val bepinexLog = listOf(File(dir, "LogOutput.log"), File(dir, "logs/LogOutput.log"), File(dir, "BepInEx/LogOutput.log"))
            .firstOrNull { recent(it, crashTime) }
        if (bepinexLog != null) {
            console.log(ConsoleEntry.Level.Warn, TAG, "Log do BepInEx (${bepinexLog.name})", tail(bepinexLog).lines().takeLast(200).joinToString("\n"))
        }
        val preloader = File(dir, "preloader.log")
        if (recent(preloader, crashTime)) {
            console.log(ConsoleEntry.Level.Warn, TAG, "Log do preloader (preloader.log)", tail(preloader).lines().takeLast(200).joinToString("\n"))
        }
        Log.e(TAG, "crash reportado no console")
    }
}
