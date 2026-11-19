package com.larp.launcher.patcher.frida

import android.content.Context
import android.util.Log
import com.larp.launcher.data.local.cache.ModCacheManager
import com.larp.launcher.patcher.NativeLibsInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FridaInjector @Inject constructor(
    private val context: Context,
    private val cache: ModCacheManager
) {

    suspend fun installAgent(agentJs: File, gadgetConfig: String? = null): File = withContext(Dispatchers.IO) {
        val nativeDir = NativeLibsInstaller.ensureInstalled(context)
        val gadget = File(nativeDir, "libfrida-gadget.so").takeIf { it.isFile }
            ?: throw IllegalStateException("libfrida-gadget.so não bundled. Veja docs/ENVS.md (LARP_BUNDLE_FRIDA_GADGET).")
        val agentTarget = File(nativeDir, "libfrida-gadget.script.so")
        agentJs.copyTo(agentTarget, overwrite = true)
        agentTarget.setReadOnly()
        val configTarget = File(nativeDir, "libfrida-gadget.config.so")
        configTarget.writeText(gadgetConfig ?: NativeLibsInstaller.DEFAULT_GADGET_CONFIG)
        configTarget.setReadOnly()
        Log.i(TAG, "Frida agent instalado: ${agentJs.name} -> $agentTarget")
        agentTarget
    }

    suspend fun saveAgent(name: String, agentJs: ByteArray, description: String, author: String): File = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "frida/agents").apply { mkdirs() }
        val id = UUID.randomUUID().toString()
        val agentFile = File(dir, "$id.js")
        agentFile.writeBytes(agentJs)
        val meta = File(dir, "$id.meta.json")
        meta.writeText("""{"id":"$id","name":$name,"description":$description,"author":$author,"createdAt":${System.currentTimeMillis()}}""")
        agentFile
    }

    suspend fun listAgents(): List<FridaAgent> = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "frida/agents")
        if (!dir.isDirectory) return@withContext emptyList()
        dir.listFiles { f -> f.isFile && f.name.endsWith(".js") }
            ?.map { file ->
                val meta = File(dir, file.nameWithoutExtension + ".meta.json")
                val metaJson = if (meta.isFile) meta.readText() else "{}"
                FridaAgent(
                    id = file.nameWithoutExtension,
                    path = file.absolutePath,
                    size = file.length(),
                    name = extractField(metaJson, "name") ?: file.name,
                    description = extractField(metaJson, "description") ?: "",
                    author = extractField(metaJson, "author") ?: "",
                    createdAt = extractField(metaJson, "createdAt")?.toLongOrNull() ?: file.lastModified()
                )
            } ?: emptyList()
    }

    suspend fun deleteAgent(id: String): Boolean = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "frida/agents")
        val agent = File(dir, "$id.js")
        val meta = File(dir, "$id.meta.json")
        val deleted = agent.delete()
        meta.delete()
        deleted
    }

    suspend fun writeGadgetConfig(json: String): File = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "frida").apply { mkdirs() }
        val file = File(dir, "libfrida-gadget.config.so")
        file.writeText(json)
        file
    }

    suspend fun readGadgetConfig(): String = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, "frida/libfrida-gadget.config.so")
        if (file.isFile) file.readText() else NativeLibsInstaller.DEFAULT_GADGET_CONFIG
    }

    suspend fun uninstall(): Boolean = withContext(Dispatchers.IO) {
        NativeLibsInstaller.removeFridaGadget(context)
        true
    }

    fun isGadgetBundled(): Boolean {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        return File(nativeDir, "libfrida-gadget.so").isFile
    }

    private fun extractField(json: String, field: String): String? {
        val pattern = "\"$field\"\\s*:\\s*\"([^\"]+)\"".toRegex()
        return pattern.find(json)?.groupValues?.getOrNull(1)
    }

    companion object {
        private const val TAG = "LarpFrida"
    }
}

data class FridaAgent(
    val id: String,
    val path: String,
    val size: Long,
    val name: String,
    val description: String,
    val author: String,
    val createdAt: Long
)
