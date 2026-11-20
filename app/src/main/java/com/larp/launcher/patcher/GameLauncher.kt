package com.larp.launcher.patcher

import android.content.Context
import android.content.Intent
import com.larp.launcher.data.local.cache.ModCacheManager
import com.larp.launcher.data.repository.GameEnvRepository
import com.larp.launcher.domain.model.GameEnv
import com.larp.launcher.domain.model.PatchProgress
import com.larp.launcher.feature.launcher.GameActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GameLauncher @Inject constructor(
    private val context: Context,
    private val gameEnvRepo: GameEnvRepository,
    private val modRuntime: ModRuntime,
    private val modSync: ModSync,
    private val cache: ModCacheManager,
    private val runtimeDownloader: RuntimeDownloader
) {

    private fun workScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun launchWithMods(modIds: List<String>, privateBepInEx: Boolean = true, gamePackage: String = GameActivity.DEFAULT_GAME_PACKAGE): Flow<PatchProgress> = callbackFlow {
        trySend(PatchProgress(PatchProgress.Stage.Downloading, "Iniciando sincronização…", 0f))
        val workScope = workScope()
        val job = workScope.launch {
            try {
                val env = gameEnvRepo.detect(gamePackage)
                if (!env.isInstalled) {
                    trySend(PatchProgress(PatchProgress.Stage.Failed, "Jogo base não instalado", 0f, IllegalStateException("Jogo não instalado: $gamePackage")))
                    return@launch
                }
                if (!runtimeDownloader.hasRuntime()) {
                    trySend(PatchProgress(PatchProgress.Stage.Downloading, "Baixando runtime de mods…", 0.05f))
                    runtimeDownloader.ensureRuntime { msg, p ->
                        trySend(PatchProgress(PatchProgress.Stage.Downloading, msg, p))
                    }
                }
                modSync.run(modIds) { name, downloading, progress ->
                    val stage = if (downloading) PatchProgress.Stage.Downloading else PatchProgress.Stage.Decrypting
                    val msg = if (downloading) "Baixando $name…" else "Descompactando $name…"
                    trySend(PatchProgress(stage, msg, progress))
                }
                trySend(PatchProgress(PatchProgress.Stage.Preparing, "Preparando runtime .NET…", 0f))
                NativeLibsInstaller.ensureInstalled(context)
                trySend(PatchProgress(PatchProgress.Stage.Ready, "Abrindo jogo com injeção…", 1f))
                startGame(env, privateBepInEx)
            } catch (t: Throwable) {
                trySend(PatchProgress(PatchProgress.Stage.Failed, t.message ?: "Erro", 0f, t))
            }
        }
        job.join()
        channel.close()
        awaitClose { job.cancel() }
    }.flowOn(Dispatchers.IO)

    fun launchLocalMods(dllPaths: List<String>, privateBepInEx: Boolean = true, gamePackage: String = GameActivity.DEFAULT_GAME_PACKAGE): Flow<PatchProgress> = callbackFlow {
        val workScope = workScope()
        val job = workScope.launch {
            try {
                val env = gameEnvRepo.detect(gamePackage)
                if (!env.isInstalled) {
                    trySend(PatchProgress(PatchProgress.Stage.Failed, "Jogo base não instalado", 0f, IllegalStateException("Jogo não instalado: $gamePackage")))
                    return@launch
                }
                if (!runtimeDownloader.hasRuntime()) {
                    trySend(PatchProgress(PatchProgress.Stage.Downloading, "Baixando runtime de mods…", 0.05f))
                    runtimeDownloader.ensureRuntime { msg, p ->
                        trySend(PatchProgress(PatchProgress.Stage.Downloading, msg, p))
                    }
                }
                if (!runtimeDownloader.hasRuntime()) {
                    val msg = "Runtime indisponível. Configure as URLs em Ajustes → Runtime."
                    trySend(PatchProgress(PatchProgress.Stage.Failed, msg, 0f, IllegalStateException(msg)))
                    return@launch
                }
                trySend(PatchProgress(PatchProgress.Stage.Installing, "Copiando DLLs locais…", 0.1f))
                val plugins = cache.bepinexPluginsDir().apply { mkdirs() }
                dllPaths.forEachIndexed { index, src ->
                    val file = File(src)
                    if (file.isFile) file.copyTo(File(plugins, file.name), overwrite = true)
                    trySend(PatchProgress(PatchProgress.Stage.Installing, "Copiando ${file.name}…", 0.1f + 0.4f * (index + 1) / dllPaths.size))
                }
                trySend(PatchProgress(PatchProgress.Stage.Preparing, "Preparando runtime .NET…", 0.6f))
                NativeLibsInstaller.ensureInstalled(context)
                trySend(PatchProgress(PatchProgress.Stage.Ready, "Abrindo jogo com injeção…", 1f))
                startGame(env, privateBepInEx)
            } catch (t: Throwable) {
                trySend(PatchProgress(PatchProgress.Stage.Failed, t.message ?: "Erro", 0f, t))
            }
        }
        job.join()
        channel.close()
        awaitClose { job.cancel() }
    }.flowOn(Dispatchers.IO)

    fun launchFridaAgent(agentJsPath: String, gamePackage: String = GameActivity.DEFAULT_GAME_PACKAGE): Flow<PatchProgress> = callbackFlow {
        val workScope = workScope()
        val job = workScope.launch {
            try {
                val env = gameEnvRepo.detect(gamePackage)
                if (!env.isInstalled) {
                    trySend(PatchProgress(PatchProgress.Stage.Failed, "Jogo base não instalado", 0f, IllegalStateException("Jogo não instalado: $gamePackage")))
                    return@launch
                }
                trySend(PatchProgress(PatchProgress.Stage.Installing, "Instalando Frida gadget…", 0f))
                val nativeDir = NativeLibsInstaller.ensureInstalled(context)
                val gadget = File(nativeDir, "libfrida-gadget.so").takeIf { it.isFile }
                    ?: throw IllegalStateException("libfrida-gadget.so não bundled no APK")
                val agent = File(agentJsPath)
                val config = File(context.filesDir, "frida/libfrida-gadget.config.so").takeIf { it.isFile }
                NativeLibsInstaller.installFridaGadget(context, gadget, agent, config)
                trySend(PatchProgress(PatchProgress.Stage.Preparing, "Preparando runtime…", 0f))
                modRuntime.prepare(env, privateBepInEx = false)
                trySend(PatchProgress(PatchProgress.Stage.Ready, "Abrindo jogo com Frida…", 1f))
                val hasRuntime = "dotnet-arm64.zip" in context.assets.list("").orEmpty()
                startGame(env, privateBepInEx = false, noMods = !hasRuntime)
            } catch (t: Throwable) {
                trySend(PatchProgress(PatchProgress.Stage.Failed, t.message ?: "Erro", 0f, t))
            }
        }
        job.join()
        channel.close()
        awaitClose { job.cancel() }
    }.flowOn(Dispatchers.IO)

    fun launchVanilla(gamePackage: String = GameActivity.DEFAULT_GAME_PACKAGE): Flow<PatchProgress> = callbackFlow {
        val workScope = workScope()
        val job = workScope.launch {
            try {
                val env = gameEnvRepo.detect(gamePackage)
                if (!env.isInstalled) {
                    trySend(PatchProgress(PatchProgress.Stage.Failed, "Jogo base não instalado", 0f, IllegalStateException("Jogo não instalado: $gamePackage")))
                    return@launch
                }
                NativeLibsInstaller.ensureInstalled(context)
                trySend(PatchProgress(PatchProgress.Stage.Ready, "Abrindo jogo sem mods…", 1f))
                startGame(env, privateBepInEx = true, noMods = true)
            } catch (t: Throwable) {
                trySend(PatchProgress(PatchProgress.Stage.Failed, t.message ?: "Erro", 0f, t))
            }
        }
        job.join()
        channel.close()
        awaitClose { job.cancel() }
    }.flowOn(Dispatchers.IO)

    private fun startGame(env: GameEnv, privateBepInEx: Boolean, noMods: Boolean = false) {
        GameEnvFactory.reset()
        val intent = GameActivity.newIntent(context, env.packageName, privateBepInEx, noMods)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
