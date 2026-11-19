package com.larp.launcher.patcher

import android.content.Context
import com.larp.launcher.data.local.cache.ModCacheManager
import com.larp.launcher.data.remote.api.LarpApi
import com.larp.launcher.data.remote.dto.ModTicketDto
import com.larp.launcher.domain.model.ModTicket
import com.larp.launcher.domain.model.PatchProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModSync @Inject constructor(
    private val context: Context,
    private val api: LarpApi,
    private val cache: ModCacheManager,
    private val httpClient: OkHttpClient,
    private val interop: InteropPronto
) {

    fun interface Listener {
        suspend fun onStep(name: String, downloading: Boolean, progress: Float)
    }

    suspend fun run(modIds: List<String>, listener: Listener): List<File> = withContext(Dispatchers.IO) {
        cache.packagesDir().mkdirs()
        val pluginsDir = cache.bepinexPluginsDir()
        removeDownloadedUnity()
        val seen = HashSet<String>()
        val packages = ArrayList<File>(modIds.size)
        val keys = ArrayList<ByteArray>(modIds.size)

        for (id in modIds) {
            val ticket: ModTicketDto = api.getModTicket(id)
            val name = ticket.name.ifBlank { id }
            val fileName = "$id-${ticket.version.replace(Regex("[^A-Za-z0-9._-]"), "_")}.rgm"
            val target = File(cache.packagesDir(), fileName)
            val sha = ticket.checksum
            if (!target.isFile || !sha.equals(ModPackage.sha256(target), ignoreCase = true)) {
                val part = File(cache.packagesDir(), "$fileName.part")
                listener.onStep(name, true, 0f)
                downloadFile(ticket.downloadUrl, part) { read, total ->
                    val p = if (total > 0) read.toFloat() / total else -1f
                    kotlinx.coroutines.runBlocking { listener.onStep(name, true, p) }
                }
                if (!sha.equals(ModPackage.sha256(part), ignoreCase = true)) {
                    part.delete()
                    throw IOException("Download corrompido: $name")
                }
                target.delete()
                if (!part.renameTo(target)) {
                    throw IOException("Não consegui gravar $fileName")
                }
            }
            seen.add(fileName)
            packages.add(target)
            keys.add(android.util.Base64.decode(ticket.key, android.util.Base64.DEFAULT))
        }

        cache.packagesDir().listFiles()?.forEach { f ->
            if (f.isFile && !seen.contains(f.name)) f.delete()
        }

        deleteTree(pluginsDir)
        pluginsDir.mkdirs()
        for ((index, pkg) in packages.withIndex()) {
            listener.onStep(modIds.getOrNull(index) ?: pkg.name, false, -1f)
            ModPackage.extract(pkg, keys[index], pluginsDir)
        }
        interop.preparar { name, downloading, progress ->
            listener.onStep(name, downloading, progress)
        }
        packages
    }

    private fun removeDownloadedUnity() {
        val natlib = File(context.filesDir, "natlib")
        val version = File(natlib, "versao.txt")
        if (version.isFile) {
            File(natlib, "libunity.so").delete()
            version.delete()
        }
    }

    private fun downloadFile(url: String, target: File, onProgress: (Long, Long) -> Unit) {
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("Resposta vazia")
            val total = body.contentLength()
            target.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                var read = 0L
                body.byteStream().use { stream ->
                    while (true) {
                        val n = stream.read(buffer)
                        if (n <= 0) break
                        out.write(buffer, 0, n)
                        read += n
                        onProgress(read, total)
                    }
                }
            }
        }
    }

    private fun deleteTree(file: File) {
        file.listFiles()?.forEach { deleteTree(it) }
        file.delete()
    }
}
