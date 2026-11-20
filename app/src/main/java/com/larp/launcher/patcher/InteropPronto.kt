package com.larp.launcher.patcher

import android.content.Context
import android.util.Log
import com.larp.launcher.core.LarpEnvProvider
import com.larp.launcher.data.local.cache.ModCacheManager
import com.larp.launcher.data.remote.api.LarpApi
import com.larp.launcher.data.remote.dto.ModTicketDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InteropPronto @Inject constructor(
    private val context: Context,
    private val api: LarpApi,
    private val cache: ModCacheManager,
    private val httpClient: OkHttpClient,
    private val hashDoInterop: HashDoInterop,
    private val envProvider: LarpEnvProvider
) {

    suspend fun preparar(listener: ModSync.Listener) = withContext(Dispatchers.IO) {
        try {
            val pm = context.packageManager
            val pkgInfo = pm.getPackageInfo(envProvider.current().gamePackage, 0)
            val versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) pkgInfo.longVersionCode else pkgInfo.versionCode.toLong()
            val appInfo = pkgInfo.applicationInfo ?: throw IOException("ApplicationInfo nulo para o jogo")
            val libil2cpp = File(GameEnv.get()?.gameNativeLibDir ?: File(appInfo.nativeLibraryDir), "libil2cpp.so")
            val bepinexDir = cache.bepinexDir()
            val interopDir = File(bepinexDir, "interop")
            val marcaFile = File(interopDir, MARCA)
            val marca = "$versionCode:${pkgInfo.lastUpdateTime}"
            if (marca == ler(marcaFile) && File(interopDir, "assembly-hash.txt").isFile) {
                return@withContext
            }
            val hashFile = File(interopDir, "assembly-hash.txt")
            val unityLibs = File(bepinexDir, "unity-libs")
            if (hashFile.isFile && libil2cpp.isFile && hashDoInterop.calcular(libil2cpp, unityLibs) == ler(hashFile)) {
                gravar(marcaFile, marca)
                return@withContext
            }
            try {
                val ticket: ModTicketDto = api.getModTicket("interop-android-$versionCode")
                val stepName = if (Locale.getDefault().language == "pt") "preparo do jogo" else "game setup"
                val interopPkg = File(context.cacheDir, "interop.rgm")
                listener.onStep(stepName, true, 0f)
                downloadFile(ticket.downloadUrl, interopPkg) { read, total ->
                    val p = if (total > 0) read.toFloat() / total else -1f
                    kotlinx.coroutines.runBlocking { listener.onStep(stepName, true, p) }
                }
                try {
                    if (ticket.checksum.equals(ModPackage.sha256(interopPkg), ignoreCase = true)) {
                        listener.onStep(stepName, false, -1f)
                        apagarDlls(interopDir)
                        apagarDlls(unityLibs)
                        ModPackage.extract(
                            interopPkg,
                            android.util.Base64.decode(ticket.key, android.util.Base64.DEFAULT),
                            bepinexDir
                        )
                        interopPkg.delete()
                        val metadata = File(bepinexDir, "regis-interop.json")
                        val json = ler(metadata)
                        metadata.delete()
                        val md5 = hashDoInterop.md5(libil2cpp)
                        val expectedMd5 = extractField(json, "libil2cppMd5")
                        if (expectedMd5 != null && !md5.equals(expectedMd5, ignoreCase = true)) {
                            Log.w(TAG, "Interop pronto: gerado de outro libil2cpp.so ($expectedMd5 != $md5); o celular gera.")
                            apagarDlls(interopDir)
                            return@withContext
                        }
                        gravar(hashFile, hashDoInterop.calcular(libil2cpp, unityLibs))
                        gravar(marcaFile, marca)
                        Log.i(TAG, "Interop pronto instalado para o jogo $versionCode")
                    } else {
                        Log.w(TAG, "Interop pronto: download corrompido; o celular gera.")
                    }
                } finally {
                    interopPkg.delete()
                }
            } catch (e: Exception) {
                Log.i(TAG, "Interop pronto: o servidor não tem para o jogo $versionCode; o celular gera.", e)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Interop pronto: não instalado", e)
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

    private fun apagarDlls(dir: File) {
        dir.listFiles()?.forEach { f -> if (f.isFile && f.name.endsWith(".dll")) f.delete() }
    }

    private fun ler(file: File): String {
        if (!file.isFile) return ""
        return runCatching {
            file.inputStream().use { stream ->
                val len = minOf(file.length(), 65536L).toInt()
                val buf = ByteArray(len)
                var read = 0
                while (read < len) {
                    val n = stream.read(buf, read, len - read)
                    if (n <= 0) break
                    read += n
                }
                String(buf, 0, read, Charsets.UTF_8).trim()
            }
        }.getOrDefault("")
    }

    private fun gravar(file: File, content: String) {
        file.parentFile?.mkdirs()
        file.outputStream().use { it.write(content.toByteArray(Charsets.UTF_8)) }
    }

    private fun extractField(json: String, field: String): String? {
        val pattern = "\"$field\"\\s*:\\s*\"([^\"]+)\"".toRegex()
        return pattern.find(json)?.groupValues?.getOrNull(1)
    }

    companion object {
        private const val TAG = "LarpLauncher"
        private const val MARCA = ".larp-interop"
    }
}
