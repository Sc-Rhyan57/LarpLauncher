package com.larp.launcher.diagnostics

import android.content.Context
import android.os.FileObserver
import android.util.Log
import com.larp.launcher.core.console.Console
import java.io.File

/** Mostra no Console do launcher os crashes do processo :game (nativos e Java). */
object CrashReporter {
    private const val TAG = "Crash"
    private var observers = ArrayList<FileObserver>()

    private fun dirs(context: Context): List<File> = listOfNotNull(
        File(context.filesDir, "BepInEx"),
        context.getExternalFilesDir(null)?.let { File(it, "BepInEx") }
    )

    /** Chamar em todo processo: captura excecoes Java nao tratadas. */
    fun installJavaHandler(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val dir = File(context.filesDir, "BepInEx").apply { mkdirs() }
        Thread.setDefaultUncaughtExceptionHandler { thread, t ->
            runCatching {
                File(dir, "crash.log").writeText(
                    "==== CRASH JAVA (Fatal) ====\nthread: ${thread.name}\n" +
                        t.stackTraceToString().take(6000) + "\n==== FIM DO CRASH ====\n"
                )
            }
            previous?.uncaughtException(thread, t)
        }
    }

    /** Chamar so no processo principal: le crash.log existente e observa novos. */
    fun watch(context: Context, console: Console) {
        dirs(context).forEach { dir ->
            consume(dir, console)
            dir.mkdirs()
            val obs = object : FileObserver(dir, CLOSE_WRITE or MOVED_TO) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == "crash.log") consume(dir, console)
                }
            }
            obs.startWatching()
            observers.add(obs)
        }
    }

    @Synchronized
    private fun consume(dir: File, console: Console) {
        val file = File(dir, "crash.log")
        if (!file.isFile || file.length() == 0L) return
        val lines = runCatching { file.readLines() }.getOrNull().orEmpty().filter { it.isNotBlank() }
        runCatching { File(dir, "crash.prev.log").apply { delete() }.let { file.renameTo(it) } }
        console.e(TAG, "O jogo crashou! Relatorio abaixo:")
        lines.forEach { console.e(TAG, it) }
        val extra = listOf(File(dir, "LogOutput.log"), File(dir, "logs/LogOutput.log"), File(dir, "preloader.log"))
            .firstOrNull { it.isFile }
        if (extra != null) {
            console.w(TAG, "Ultimas linhas de ${extra.name}:")
            runCatching { extra.readLines().takeLast(15) }.getOrNull().orEmpty().forEach { console.w(TAG, it) }
        }
        Log.e(TAG, "crash reportado no console")
    }
}
