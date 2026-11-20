package com.larp.launcher.feature.devkit

import android.content.Context
import com.larp.launcher.domain.model.DevKitEntry
import com.larp.launcher.domain.model.DevKitSnapshot
import com.larp.launcher.domain.model.DevKitVariable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DevKitStore @Inject constructor(
    private val context: Context,
    private val json: Json
) {

    fun devkitDir(): File = File(context.filesDir, "devkit").apply { mkdirs() }

    fun variablesFile(): File = File(devkitDir(), "variables.json")

    fun snapshotFile(): File = File(devkitDir(), "snapshot.json")

    suspend fun loadVariables(): List<DevKitVariable> = withContext(Dispatchers.IO) {
        val file = variablesFile()
        if (!file.isFile) return@withContext emptyList()
        runCatching {
            json.decodeFromString<List<DevKitVariable>>(file.readText())
        }.getOrDefault(emptyList())
    }

    suspend fun saveVariables(variables: List<DevKitVariable>) = withContext(Dispatchers.IO) {
        variablesFile().writeText(json.encodeToString(variables))
    }

    suspend fun addVariable(variable: DevKitVariable): List<DevKitVariable> = withContext(Dispatchers.IO) {
        val current = loadVariables()
        val updated = current + variable
        saveVariables(updated)
        updated
    }

    suspend fun updateVariable(variable: DevKitVariable): List<DevKitVariable> = withContext(Dispatchers.IO) {
        val current = loadVariables()
        val updated = current.map { if (it.id == variable.id) variable else it }
        saveVariables(updated)
        updated
    }

    suspend fun deleteVariable(id: String): List<DevKitVariable> = withContext(Dispatchers.IO) {
        val current = loadVariables()
        val updated = current.filterNot { it.id == id }
        saveVariables(updated)
        updated
    }

    suspend fun saveSnapshot(snapshot: DevKitSnapshot): File = withContext(Dispatchers.IO) {
        val file = snapshotFile()
        file.writeText(json.encodeToString(snapshot))
        file
    }

    suspend fun loadSnapshot(): DevKitSnapshot? = withContext(Dispatchers.IO) {
        val file = snapshotFile()
        if (!file.isFile) return@withContext null
        runCatching {
            json.decodeFromString<DevKitSnapshot>(file.readText())
        }.getOrNull()
    }

    fun catalogFile(): File = File(devkitDir(), "catalog.tsv")

    private fun catalogMetaFile(): File = File(devkitDir(), "catalog.meta")

    private fun catalogStatusFile(): File = File(devkitDir(), "catalog.status")

    fun catalogStamp(): String? = catalogMetaFile().takeIf { it.isFile }?.readText()?.substringBefore('\t')

    fun catalogCount(): Int = catalogMetaFile().takeIf { it.isFile }?.readText()?.substringAfter('\t', "0")?.trim()?.toIntOrNull() ?: 0

    fun saveCatalogMeta(stamp: String, count: Int) {
        catalogMetaFile().writeText("$stamp\t$count")
    }

    fun saveCatalogStatus(text: String) {
        catalogStatusFile().writeText(text)
    }

    fun catalogStatus(): String {
        val status = catalogStatusFile().takeIf { it.isFile }?.readText()
        if (!status.isNullOrBlank()) return status
        return if (catalogFile().isFile) "${catalogCount()} entradas extraídas" else "Abra o jogo com o Dev Kit ativo para extrair o catálogo"
    }

    suspend fun searchCatalog(query: String, limit: Int): List<DevKitEntry> = withContext(Dispatchers.IO) {
        val file = catalogFile()
        if (!file.isFile) return@withContext emptyList()
        val terms = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val results = ArrayList<DevKitEntry>(limit)
        file.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (results.size >= limit) break
                if (terms.isEmpty() && !line.startsWith("C\t")) continue
                if (terms.isNotEmpty()) {
                    val haystack = line.lowercase()
                    if (!terms.all { haystack.contains(it) }) continue
                }
                parseEntry(line)?.let { results.add(it) }
            }
        }
        results
    }

    private fun parseEntry(line: String): DevKitEntry? {
        val parts = line.split('\t')
        if (parts.size < 8 || parts[0].isEmpty()) return null
        return DevKitEntry(
            kind = parts[0][0],
            assembly = parts[1],
            owner = parts[2],
            member = parts[3],
            signature = parts[4],
            address = parts[5],
            offset = parts[6],
            isStatic = parts[7] == "S"
        )
    }

    fun newId(): String = UUID.randomUUID().toString()
}
