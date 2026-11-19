package com.larp.launcher.data.local.cache

import android.content.Context
import com.larp.launcher.domain.model.LocalMod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

class ModCacheManager(private val context: Context) {

    fun cacheRoot(): File = File(context.filesDir, "mods/cache").apply { mkdirs() }

    fun localRoot(): File = File(context.filesDir, "mods/local").apply { mkdirs() }

    fun bepinexDir(): File = File(context.filesDir, "BepInEx").apply { mkdirs() }

    fun bepinexPluginsDir(): File = File(bepinexDir(), "plugins").apply { mkdirs() }

    fun dotnetDir(): File = File(context.filesDir, "dotnet").apply { mkdirs() }

    fun gameDataDir(): File = File(context.filesDir, "game_data").apply { mkdirs() }

    fun diagDir(): File = File(context.filesDir, "diag").apply { mkdirs() }

    fun packagesDir(): File = File(context.filesDir, "pacotes").apply { mkdirs() }

    fun coverFile(id: String): File = File(localRoot(), "$id.cover.png")

    fun dllFile(id: String, originalName: String): File = File(localRoot(), "$id.${originalName.substringAfterLast('.', "dll")}")

    fun downloadedPackage(id: String): File = File(packagesDir(), "$id.pkg")

    suspend fun computeSha256(file: File): String = withContext(Dispatchers.IO) {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                md.update(buffer, 0, read)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun saveLocalDll(mod: LocalMod, dllBytes: ByteArray): File = withContext(Dispatchers.IO) {
        val target = File(localRoot(), "${mod.id}.dll")
        target.writeBytes(dllBytes)
        target
    }

    suspend fun clearCache(): Int = withContext(Dispatchers.IO) {
        var deleted = 0
        cacheRoot().listFiles()?.forEach {
            if (it.delete()) deleted++
        }
        deleted
    }

    suspend fun deleteLocalMod(id: String) = withContext(Dispatchers.IO) {
        listOf(
            File(localRoot(), "$id.dll"),
            File(localRoot(), "$id.cover.png"),
            File(localRoot(), "$id.meta.json")
        ).forEach { it.takeIf { f -> f.exists() }?.delete() }
    }
}
