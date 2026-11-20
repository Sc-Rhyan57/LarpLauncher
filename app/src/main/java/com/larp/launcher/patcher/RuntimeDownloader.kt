package com.larp.launcher.patcher

import android.content.Context
import android.util.Log
import com.larp.launcher.core.LarpEnvProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RuntimeDownloader @Inject constructor(
    private val context: Context,
    private val client: OkHttpClient,
    private val envProvider: LarpEnvProvider
) {

    fun runtimeDir(): File = File(context.filesDir, "runtime").apply { mkdirs() }

    fun dotnetZip(): File = File(runtimeDir(), "dotnet-arm64.zip")

    fun bepinexZip(): File = File(runtimeDir(), "BepInEx-arm64.zip")

    fun hasDotnetAsset(): Boolean =
        context.assets.list("").orEmpty().contains("dotnet-arm64.zip")

    fun hasBepinexAsset(): Boolean =
        context.assets.list("").orEmpty().contains("BepInEx-arm64.zip")

    fun hasDotnet(): Boolean = hasDotnetAsset() || dotnetZip().isFile

    fun hasBepinex(): Boolean = hasBepinexAsset() || bepinexZip().isFile

    fun hasRuntime(): Boolean = hasDotnet() && hasBepinex()

    fun dotnetSource(): RuntimeSource =
        if (hasDotnetAsset()) RuntimeSource.Asset(context, "dotnet-arm64.zip")
        else if (dotnetZip().isFile) RuntimeSource.File(dotnetZip())
        else throw IOException("dotnet-arm64.zip indisponível")

    fun bepinexSource(): RuntimeSource =
        if (hasBepinexAsset()) RuntimeSource.Asset(context, "BepInEx-arm64.zip")
        else if (bepinexZip().isFile) RuntimeSource.File(bepinexZip())
        else throw IOException("BepInEx-arm64.zip indisponível")

    suspend fun downloadDotnet(onProgress: suspend (Float) -> Unit = {}): File = withContext(Dispatchers.IO) {
        val env = envProvider.current()
        val url = env.runtimeDotnetUrl.trim()
        if (url.isBlank()) throw IOException("URL do runtime .NET não configurada em Ajustes")
        downloadTo(url, dotnetZip(), onProgress)
    }

    suspend fun downloadBepinex(onProgress: suspend (Float) -> Unit = {}): File = withContext(Dispatchers.IO) {
        val env = envProvider.current()
        val url = env.runtimeBepinexUrl.trim()
        if (url.isBlank()) throw IOException("URL do BepInEx não configurada em Ajustes")
        downloadTo(url, bepinexZip(), onProgress)
    }

    suspend fun ensureRuntime(onProgress: suspend (String, Float) -> Unit): EnsureResult = withContext(Dispatchers.IO) {
        val missing = mutableListOf<String>()
        if (!hasDotnet()) missing.add("dotnet")
        if (!hasBepinex()) missing.add("bepinex")
        if (missing.isEmpty()) return@withContext EnsureResult.AlreadyAvailable

        var total = 0f
        for (target in missing) {
            when (target) {
                "dotnet" -> {
                    if (hasDotnetAsset()) continue
                    onProgress("Baixando runtime .NET…", total)
                    val base = total
                    downloadDotnet { p ->
                        onProgress("Baixando runtime .NET…", base + p * 0.5f)
                    }
                    total += 0.5f
                }
                "bepinex" -> {
                    if (hasBepinexAsset()) continue
                    onProgress("Baixando BepInEx…", total)
                    val base = total
                    downloadBepinex { p ->
                        onProgress("Baixando BepInEx…", base + p * 0.5f)
                    }
                    total = 1f
                }
            }
        }
        onProgress("Runtime pronto", 1f)
        EnsureResult.Downloaded
    }

    private suspend fun downloadTo(url: String, target: File, onProgress: suspend (Float) -> Unit): File {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".part")
        if (tmp.exists()) tmp.delete()
        Log.i(TAG, "Baixando $url -> ${target.name}")
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} ao baixar ${target.name} de $url")
            }
            val body = response.body ?: throw IOException("Resposta vazia ao baixar ${target.name}")
            val total = body.contentLength()
            RandomAccessFile(tmp, "rw").use { raf ->
                raf.setLength(0)
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        raf.write(buffer, 0, n)
                        read += n
                        if (total > 0) onProgress(read.toFloat() / total.toFloat())
                    }
                    if (total > 0 && read != total) {
                        throw IOException("Download incompleto de ${target.name}: $read/$total bytes")
                    }
                }
            }
        }
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        Log.i(TAG, "${target.name} baixado (${target.length()} bytes)")
        return target
    }

    fun deleteDownloaded() {
        listOf(
            dotnetZip(),
            bepinexZip(),
            File(dotnetZip().parentFile, dotnetZip().name + ".part"),
            File(bepinexZip().parentFile, bepinexZip().name + ".part")
        ).forEach { it.delete() }
    }

    sealed class EnsureResult {
        object AlreadyAvailable : EnsureResult()
        object Downloaded : EnsureResult()
    }

    sealed class RuntimeSource {
        abstract fun open(): java.io.InputStream
        data class Asset(val context: Context, val name: String) : RuntimeSource() {
            override fun open() = context.assets.open(name)
        }
        data class File(val file: java.io.File) : RuntimeSource() {
            override fun open() = file.inputStream()
        }
    }

    companion object {
        private const val TAG = "LarpRuntime"
    }
}
