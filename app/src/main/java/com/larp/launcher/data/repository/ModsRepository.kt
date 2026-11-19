package com.larp.launcher.data.repository

import com.larp.launcher.data.local.cache.ModCacheManager
import com.larp.launcher.data.local.db.InstalledModDao
import com.larp.launcher.data.local.db.InstalledModEntity
import com.larp.launcher.data.local.db.LocalModDao
import com.larp.launcher.data.local.db.LocalModEntity
import com.larp.launcher.data.remote.api.LarpApi
import com.larp.launcher.data.remote.dto.ModInstallRequest
import com.larp.launcher.domain.model.LocalMod
import com.larp.launcher.domain.model.ModTicket
import com.larp.launcher.domain.model.ServerMod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModsRepository @Inject constructor(
    private val api: LarpApi,
    private val cache: ModCacheManager,
    private val installedDao: InstalledModDao,
    private val localDao: LocalModDao,
    private val httpClient: OkHttpClient
) {

    suspend fun listServerMods(cursor: String? = null): List<ServerMod> = withContext(Dispatchers.IO) {
        val installed = installedDao.allIds().toSet()
        val response = api.listMods(cursor = cursor)
        response.mods.map { it.toDomain(it.id in installed) }
    }

    suspend fun getMod(id: String): ServerMod = withContext(Dispatchers.IO) {
        val installed = installedDao.allIds().contains(id)
        api.getMod(id).toDomain(installed)
    }

    suspend fun requestTicket(id: String): ModTicket = withContext(Dispatchers.IO) {
        val ticket = api.getModTicket(id)
        ModTicket(
            id = ticket.id,
            name = ticket.name,
            downloadUrl = ticket.downloadUrl,
            key = ticket.key,
            checksum = ticket.checksum,
            size = ticket.size
        )
    }

    suspend fun downloadTicket(ticket: ModTicket, onProgress: (Long, Long) -> Unit): File = withContext(Dispatchers.IO) {
        val target = cache.downloadedPackage(ticket.id)
        val request = Request.Builder().url(ticket.downloadUrl).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Download falhou: HTTP ${response.code}")
            val body = response.body ?: error("Resposta sem corpo")
            val total = body.contentLength().takeIf { it > 0 } ?: ticket.size
            target.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                var read = 0L
                body.byteStream().use { input ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        out.write(buffer, 0, n)
                        read += n
                        onProgress(read, total)
                    }
                }
            }
        }
        val actual = cache.computeSha256(target)
        if (!actual.equals(ticket.checksum, ignoreCase = true)) {
            target.delete()
            error("Checksum divergente. Esperado ${ticket.checksum}, atual $actual")
        }
        target
    }

    suspend fun installMod(serverMod: ServerMod, pkg: File, keyBase64: String): Boolean = withContext(Dispatchers.IO) {
        val out = cache.bepinexPluginsDir()
        out.mkdirs()
        val keyBytes = android.util.Base64.decode(keyBase64, android.util.Base64.DEFAULT)
        com.larp.launcher.patcher.ModPackage.extract(pkg, keyBytes, out)
        installedDao.upsert(
            InstalledModEntity(
                id = serverMod.id,
                name = serverMod.name,
                version = serverMod.version,
                checksum = serverMod.checksum,
                installedAt = System.currentTimeMillis(),
                enabled = true
            )
        )
        pkg.delete()
        true
    }

    suspend fun installMods(modIds: List<String>): Boolean = withContext(Dispatchers.IO) {
        api.installMods(ModInstallRequest(modIds))
        true
    }

    suspend fun uninstallMod(id: String) = withContext(Dispatchers.IO) {
        installedDao.delete(id)
    }

    fun observeLocalMods(): Flow<List<LocalMod>> =
        localDao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun addLocalMod(mod: LocalMod, dllBytes: ByteArray, coverBytes: ByteArray?) = withContext(Dispatchers.IO) {
        val dllFile = File(cache.localRoot(), "${mod.id}.dll")
        dllFile.writeBytes(dllBytes)
        if (coverBytes != null) {
            File(cache.localRoot(), "${mod.id}.cover.png").writeBytes(coverBytes)
        }
        val entity = mod.copy(
            dllPath = dllFile.absolutePath,
            coverPath = coverBytes?.let { File(cache.localRoot(), "${mod.id}.cover.png").absolutePath },
            fileSize = dllFile.length(),
            checksum = cache.computeSha256(dllFile),
            updatedAt = System.currentTimeMillis()
        ).toEntity()
        localDao.upsert(entity)
    }

    suspend fun updateLocalMod(mod: LocalMod) = withContext(Dispatchers.IO) {
        localDao.update(mod.copy(updatedAt = System.currentTimeMillis()).toEntity())
    }

    suspend fun deleteLocalMod(id: String) = withContext(Dispatchers.IO) {
        cache.deleteLocalMod(id)
        localDao.delete(id)
    }

    suspend fun toggleLocalMod(id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        localDao.setEnabled(id, enabled)
    }

    suspend fun clearCache(): Int = cache.clearCache()

    private fun com.larp.launcher.data.remote.dto.ModDto.toDomain(installed: Boolean): ServerMod = ServerMod(
        id = id,
        name = name,
        description = description,
        author = author,
        version = version,
        size = size,
        checksum = checksum,
        coverUrl = coverUrl,
        tags = tags,
        isOfficial = isOfficial,
        updatedAt = updatedAt,
        installed = installed
    )

    private fun LocalModEntity.toDomain(): LocalMod = LocalMod(
        id = id,
        name = name,
        description = description,
        author = author,
        version = version,
        dllPath = dllPath,
        coverPath = coverPath,
        createdAt = createdAt,
        updatedAt = updatedAt,
        fileSize = fileSize,
        checksum = checksum,
        enabled = enabled
    )

    private fun LocalMod.toEntity(): LocalModEntity = LocalModEntity(
        id = id,
        name = name,
        description = description,
        author = author,
        version = version,
        dllPath = dllPath,
        coverPath = coverPath,
        createdAt = createdAt,
        updatedAt = updatedAt,
        fileSize = fileSize,
        checksum = checksum,
        enabled = enabled
    )
}
