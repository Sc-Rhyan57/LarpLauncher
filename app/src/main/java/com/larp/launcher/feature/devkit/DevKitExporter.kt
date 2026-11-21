package com.larp.launcher.feature.devkit

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.larp.launcher.core.LarpEnvProvider
import com.larp.launcher.domain.model.GameEnv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Exporta artefatos do Dev Kit para o usuário compartilhar com o desenvolvedor de mods.
 *
 * - exportStructureAsZip(): empacota o catálogo IL2CPP extraído (catalog.tsv + meta + status)
 *   e devolve uma Intent ACTION_SEND pronta para compartilhar.
 * - exportAssemblyCSharp(): extrai o Assembly-CSharp.dll do APK do jogo instalado
 *   (caminho assets/bin/Data/Managed/Assembly-CSharp.dll) e devolve uma Intent ACTION_SEND.
 *
 * Os arquivos ficam em context.cacheDir/downloads/ (configurado no file_provider_paths.xml
 * como cache-path "downloads") para que possam ser compartilhados via FileProvider.
 */
class DevKitExporter(
    private val context: Context,
    private val devKitStore: DevKitStore,
    private val envProvider: LarpEnvProvider
) {

    sealed class Result {
        data class Shared(val uri: Uri, val file: File, val displayName: String, val sizeBytes: Long) : Result()
        data class Failed(val reason: String) : Result()
    }

    suspend fun exportStructureAsZip(): Result = withContext(Dispatchers.IO) {
        try {
            val catalogFile = devKitStore.catalogFile()
            if (!catalogFile.isFile) {
                return@withContext Result.Failed("Catálogo ainda não extraído. Abra o jogo com o Dev Kit ativo e aguarde a extração.")
            }
            val metaFile = File(devKitStore.devkitDir(), "catalog.meta")
            val statusFile = File(devKitStore.devkitDir(), "catalog.status")
            val entries = System.currentTimeMillis()
            val zipName = "larp-catalogo-${envProvider.current().gamePackage.substringAfterLast('.')}-$entries.zip"
            val outDir = File(context.cacheDir, "downloads").apply { mkdirs() }
            val zipFile = File(outDir, zipName)
            ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("catalog.tsv"))
                catalogFile.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                if (metaFile.isFile) {
                    zip.putNextEntry(ZipEntry("catalog.meta"))
                    metaFile.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                if (statusFile.isFile) {
                    zip.putNextEntry(ZipEntry("catalog.status"))
                    statusFile.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                zip.putNextEntry(ZipEntry("LEIA-ME.txt"))
                zip.write(
                    ("Catálogo IL2CPP extraído pelo Larp Launcher.\n".toByteArray() +
                        "Use estas informações para desenvolver mods BepInEx para o jogo.\n".toByteArray() +
                        "Formato TSV: kind\\tassembly\\towner\\tmember\\tsignature\\taddress\\toffset\\t[IS]S\n".toByteArray() +
                        "kind: C=class, M=method, F=field\n".toByteArray() +
                        "Jogue este zip no seu assistente de IA favorito para gerar mods :)\n".toByteArray())
                )
                zip.closeEntry()
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zipFile)
            Log.i(TAG, "Catálogo exportado: ${zipFile.absolutePath} (${zipFile.length()} bytes)")
            Result.Shared(uri, zipFile, zipName, zipFile.length())
        } catch (t: Throwable) {
            Log.e(TAG, "Falha ao exportar catálogo", t)
            Result.Failed(t.message ?: "Erro desconhecido")
        }
    }

    suspend fun exportAssemblyCSharp(gameEnv: GameEnv): Result = withContext(Dispatchers.IO) {
        if (!gameEnv.isInstalled) return@withContext Result.Failed("Jogo base não instalado")
        try {
            val apkFile = File(gameEnv.sourceDir)
            if (!apkFile.isFile) return@withContext Result.Failed("APK do jogo não encontrado em ${gameEnv.sourceDir}")

            // Estrutura do APK do Among Us (Unity IL2CPP): DLLs ficam em
            // assets/bin/Data/Managed/Assembly-CSharp.dll (sem subdiretório da ABI).
            // Algumas versões podem estar em assets/bin/Data/Managed/<asp>/
            val candidates = listOf(
                "assets/bin/Data/Managed/Assembly-CSharp.dll",
                "assets/bin/Data/Managed/Assembly-CSharp-firstpass.dll"
            )
            ZipFile(apkFile).use { zip ->
                val found = candidates.firstNotNullOfOrNull { path ->
                    runCatching { zip.getEntry(path) }.getOrNull()?.let { path to it }
                } ?: return@withContext Result.Failed("Assembly-CSharp.dll não encontrado no APK do jogo. Verifique se o jogo é Unity IL2CPP.")

                val (entryPath, entry) = found
                val outDir = File(context.cacheDir, "downloads").apply { mkdirs() }
                val baseName = "Assembly-CSharp-${gameEnv.versionName.ifBlank { "unknown" }}.dll"
                val outFile = File(outDir, baseName)
                BufferedInputStream(zip.getInputStream(entry), 65536).use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output, 65536) }
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
                Log.i(TAG, "Assembly-CSharp.dll extraído: ${outFile.absolutePath} (${outFile.length()} bytes, de entry $entryPath)")
                Result.Shared(uri, outFile, baseName, outFile.length())
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Falha ao extrair Assembly-CSharp.dll", t)
            Result.Failed(t.message ?: "Erro desconhecido")
        }
    }

    fun buildShareIntent(uri: Uri, displayName: String): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = when {
                displayName.endsWith(".zip") -> "application/zip"
                displayName.endsWith(".dll") -> "application/octet-stream"
                else -> "*/*"
            }
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, displayName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    companion object {
        private const val TAG = "LarpDevKitExporter"
    }
}
