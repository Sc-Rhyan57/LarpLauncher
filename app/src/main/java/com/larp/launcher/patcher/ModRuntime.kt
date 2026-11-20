package com.larp.launcher.patcher

import android.content.Context
import android.system.Os
import android.util.Log
import com.larp.launcher.core.LarpEnvProvider
import com.larp.launcher.data.local.cache.ModCacheManager
import com.larp.launcher.domain.model.GameEnv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.util.regex.Pattern
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModRuntime @Inject constructor(
    private val context: Context,
    private val cache: ModCacheManager,
    private val envProvider: LarpEnvProvider,
    private val runtimeDownloader: RuntimeDownloader
) {

    private val prefs by lazy { context.getSharedPreferences("larp_runtime", Context.MODE_PRIVATE) }

    suspend fun prepare(
        gameEnv: GameEnv,
        privateBepInEx: Boolean,
        onProgress: (String, Float) -> Unit = { _, _ -> }
    ): Boolean = withContext(Dispatchers.IO) {
        onProgress("Verificando runtime de mods…", 0.02f)
        val externalFiles = context.getExternalFilesDir(null) ?: throw IOException("Armazenamento externo indisponível")
        val dotnetDir = cache.dotnetDir()
        val bepinexDir = if (privateBepInEx) cache.bepinexDir() else File(externalFiles, "BepInEx")
        val gameDataDir = cache.gameDataDir()
        val libil2cpp = File(gameEnv.nativeLibraryDir, "libil2cpp.so")
        val libunity = File(gameEnv.nativeLibraryDir, "libunity.so")

        if (!runtimeDownloader.hasRuntime()) {
            Log.i(TAG, "Runtime indisponível (sem assets e sem downloads); tentando baixar")
            onProgress("Baixando runtime de mods…", 0.05f)
            runtimeDownloader.ensureRuntime(onProgress)
        }

        if (!runtimeDownloader.hasRuntime()) {
            Log.i(TAG, "Runtime ausente; abrindo o jogo original")
            return@withContext false
        }

        val gameLastUpdate = runCatching {
            context.packageManager.getPackageInfo(envProvider.current().gamePackage, 0).lastUpdateTime
        }.getOrDefault(0L)

        val dotnetFingerprint = runtimeDownloader.dotnetZip().let { if (it.isFile) it.length().toString() else "asset" }
        val dotnetKey = "bundle_dotnet_$dotnetFingerprint"
        if (prefs.getInt(dotnetKey, 0) != BUNDLE_VERSION || !File(dotnetDir, "System.Private.CoreLib.dll").isFile) {
            Log.i(TAG, "Extraindo .NET")
            onProgress("Extraindo runtime .NET…", 0.1f)
            deleteTree(dotnetDir)
            unzipSource(runtimeDownloader.dotnetSource(), dotnetDir, false)
            prefs.edit().putInt(dotnetKey, BUNDLE_VERSION).apply()
        }

        val bepFingerprint = runtimeDownloader.bepinexZip().let { if (it.isFile) it.length().toString() else "asset" }
        val bepKeyBase = if (privateBepInEx) "bundle_bep_privado_" else "bundle_bep_externo_"
        val bepKey = bepKeyBase + bepFingerprint
        if (prefs.getInt(bepKey, 0) != BUNDLE_VERSION || !File(bepinexDir, "core/BepInEx.Core.dll").isFile) {
            val bepinexSource = runtimeDownloader.bepinexSource()
            if (bepinexSource != null) {
                Log.i(TAG, "Extraindo BepInEx em $bepinexDir")
                onProgress("Extraindo BepInEx…", 0.45f)
                deleteTree(File(bepinexDir, "core"))
                unzipSource(bepinexSource, bepinexDir, true)
                prefs.edit().putInt(bepKey, BUNDLE_VERSION).apply()
            } else {
                Log.i(TAG, "BepInEx ausente — rodando só com .NET runtime + hooks nativos")
                onProgress("Sem BepInEx — prosseguindo…", 0.45f)
            }
        }

        val metadataFile = File(gameDataDir, "Managed/Metadata/global-metadata.dat")
        var unityVersion = prefs.getString(KEY_UNITY_VERSION, null)
        if (prefs.getLong(KEY_GAME_LAST_UPDATE, 0L) != gameLastUpdate || !metadataFile.isFile || unityVersion == null) {
            Log.i(TAG, "Copiando metadados do jogo")
            onProgress("Lendo dados do jogo…", 0.7f)
            metadataFile.parentFile?.mkdirs()
            ZipFile(gameEnv.sourceDir).use { zip ->
                val entry = zip.getEntry(METADATA_ENTRY) ?: throw IOException("global-metadata.dat não está no APK do jogo")
                zip.getInputStream(entry).use { input ->
                    metadataFile.outputStream().use { output -> input.copyTo(output, 65536) }
                }
            }
            unityVersion = findUnityVersion(libunity) ?: throw IOException("Não achei a versão do Unity em libunity.so")
            prefs.edit()
                .putLong(KEY_GAME_LAST_UPDATE, gameLastUpdate)
                .putString(KEY_UNITY_VERSION, unityVersion)
                .apply()
        }

        onProgress("Configurando ambiente de mods…", 0.92f)
        val launcherLibDir = context.applicationInfo.nativeLibraryDir
        setenv("FUSION_BEPINEX_PATH", bepinexDir.absolutePath)
        setenv("FUSION_GAME_BINARY", libil2cpp.absolutePath)
        setenv("BEPINEX_GAME_ASSEMBLY_PATH", libil2cpp.absolutePath)
        setenv("FUSION_GAME_DATA_DIR", gameDataDir.absolutePath)
        setenv("FUSION_APP_DATA_DIR", File(externalFiles, "dados").absolutePath)
        setenv("FUSION_UNITY_VERSION", unityVersion)
        setenv("DOTNET_GCConserveMemory", "7")
        val libDirs = listOf(
            dotnetDir.absolutePath,
            context.filesDir.resolve("native").absolutePath,
            launcherLibDir,
            gameEnv.nativeLibraryDir
        ).distinct().joinToString(":")
        setenv("LARP_DOTNET_DIR", dotnetDir.absolutePath)
        setenv("LARP_LIB_DIRS", libDirs)
        setenv("REGIS_DOTNET_DIR", dotnetDir.absolutePath)
        setenv("REGIS_LIB_DIRS", libDirs)
        setenv("FUSION_LOG_DIR", bepinexDir.absolutePath)
        setenv("LARP_MODLOADERS", "libfusion.so")
        setenv("LARP_GAME_LIB_DIR", gameEnv.nativeLibraryDir)
        setenv("HOME", context.filesDir.absolutePath)
        setenv("TMPDIR", context.cacheDir.absolutePath)
        setenv("DOTNET_EnableWriteXorExecute", "0")
        setenv("SSL_CERT_DIR", "/apex/com.android.conscrypt/cacerts:/system/etc/security/cacerts")
        Log.i(TAG, "Mods: Unity $unityVersion, BepInEx em $bepinexDir")
        onProgress("Runtime de mods pronto", 1f)
        true
    }

    private fun unzipSource(source: RuntimeDownloader.RuntimeSource, outputDir: File, onlyCoreIfExists: Boolean) {
        val root = outputDir.canonicalPath + File.separator
        source.open().use { stream ->
            ZipInputStream(BufferedInputStream(stream, 65536)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val target = File(outputDir, entry.name)
                    val targetCanonical = target.canonicalPath + File.separator
                    if (!targetCanonical.startsWith(root)) {
                        throw IOException("Entrada inválida no pacote: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else if (!onlyCoreIfExists || !target.isFile || entry.name.startsWith("core/")) {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { out -> zip.copyTo(out, 65536) }
                    }
                }
            }
        }
    }

    private fun deleteTree(file: File) {
        file.listFiles()?.forEach { deleteTree(it) }
        file.delete()
    }

    private fun setenv(key: String, value: String) {
        try {
            Os.setenv(key, value, true)
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao setar $key", e)
        }
    }

    companion object {
        private const val TAG = "LarpLauncher"
        private const val BUNDLE_VERSION = 2
        private const val METADATA_ENTRY = "assets/bin/Data/Managed/Metadata/global-metadata.dat"
        private const val KEY_GAME_LAST_UPDATE = "game"
        private const val KEY_UNITY_VERSION = "unity"

        fun findUnityVersion(libunity: File): String? {
            val pattern = Pattern.compile("20[12]\\d\\.\\d+\\.\\d{1,3}[abfp]\\d{1,3}")
            val buffer = ByteArray(1048576)
            libunity.inputStream().use { input ->
                var filled = 0
                while (true) {
                    val n = input.read(buffer, filled, buffer.size - filled)
                    if (n <= 0) return null
                    filled += n
                    val s = String(buffer, 0, filled, Charsets.ISO_8859_1)
                    val matcher = pattern.matcher(s)
                    if (matcher.find()) return matcher.group()
                    val keep = minOf(32, filled)
                    System.arraycopy(buffer, filled - keep, buffer, 0, keep)
                    filled = keep
                }
            }
        }
    }
}

private fun java.io.InputStream.copyTo(out: java.io.OutputStream, bufferSize: Int) {
    val buffer = ByteArray(bufferSize)
    while (true) {
        val n = read(buffer)
        if (n <= 0) break
        out.write(buffer, 0, n)
    }
}
