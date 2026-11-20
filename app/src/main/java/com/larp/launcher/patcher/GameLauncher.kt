package com.larp.launcher.patcher

import android.content.Context
import com.larp.launcher.data.local.cache.ModCacheManager
import com.larp.launcher.data.repository.GameEnvRepository
import com.larp.launcher.domain.model.GameEnv
import com.larp.launcher.domain.model.PatchProgress
import com.larp.launcher.feature.launcher.GameActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
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

    fun launchWithMods(modIds: List<String>, privateBepInEx: Boolean = true, gamePackage: String = GameActivity.DEFAULT_GAME_PACKAGE): Flow<PatchProgress> = flow {
        emit(PatchProgress(PatchProgress.Stage.Downloading, "Iniciando sincronização…", 0f))
        val env = gameEnvRepo.detect(gamePackage)
        if (!env.isInstalled) {
            emit(PatchProgress(PatchProgress.Stage.Failed, "Jogo base não instalado", 0f, IllegalStateException("Jogo não instalado: $gamePackage")))
            return@flow
        }
        if (!runtimeDownloader.hasRuntime()) {
            emit(PatchProgress(PatchProgress.Stage.Downloading, "Baixando runtime de mods…", 0.05f))
            runtimeDownloader.ensureRuntime { msg, p ->
                emit(PatchProgress(PatchProgress.Stage.Downloading, msg, p))
            }
        }
        modSync.run(modIds) { name, downloading, progress ->
            val stage = if (downloading) PatchProgress.Stage.Downloading else PatchProgress.Stage.Decrypting
            val msg = if (downloading) "Baixando $name…" else "Descompactando $name…"
            emit(PatchProgress(stage, msg, progress))
        }
        emit(PatchProgress(PatchProgress.Stage.Preparing, "Preparando runtime .NET…", 0f))
        NativeLibsInstaller.ensureInstalled(context)
        emit(PatchProgress(PatchProgress.Stage.Ready, "Abrindo jogo com injeção…", 1f))
        startGame(env, privateBepInEx)
    }.catch { t ->
        emit(PatchProgress(PatchProgress.Stage.Failed, t.message ?: "Erro", 0f, t))
    }.flowOn(Dispatchers.IO)

    fun launchLocalMods(dllPaths: List<String>, privateBepInEx: Boolean = true, gamePackage: String = GameActivity.DEFAULT_GAME_PACKAGE): Flow<PatchProgress> = flow {
        val env = gameEnvRepo.detect(gamePackage)
        if (!env.isInstalled) {
            emit(PatchProgress(PatchProgress.Stage.Failed, "Jogo base não instalado", 0f, IllegalStateException("Jogo não instalado: $gamePackage")))
            return@flow
        }
        if (!runtimeDownloader.hasRuntime()) {
            emit(PatchProgress(PatchProgress.Stage.Downloading, "Baixando runtime de mods…", 0.05f))
            runtimeDownloader.ensureRuntime { msg, p ->
                emit(PatchProgress(PatchProgress.Stage.Downloading, msg, p))
            }
        }
        if (!runtimeDownloader.hasRuntime()) {
            val msg = "Runtime indisponível. Configure as URLs em Ajustes → Runtime."
            emit(PatchProgress(PatchProgress.Stage.Failed, msg, 0f, IllegalStateException(msg)))
            return@flow
        }
        emit(PatchProgress(PatchProgress.Stage.Installing, "Copiando DLLs locais…", 0.1f))
        val plugins = cache.bepinexPluginsDir().apply { mkdirs() }
        dllPaths.forEachIndexed { index, src ->
            val file = File(src)
            if (file.isFile) file.copyTo(File(plugins, file.name), overwrite = true)
            emit(PatchProgress(PatchProgress.Stage.Installing, "Copiando ${file.name}…", 0.1f + 0.4f * (index + 1) / dllPaths.size))
        }
        emit(PatchProgress(PatchProgress.Stage.Preparing, "Preparando runtime .NET…", 0.6f))
        NativeLibsInstaller.ensureInstalled(context)
        emit(PatchProgress(PatchProgress.Stage.Ready, "Abrindo jogo com injeção…", 1f))
        startGame(env, privateBepInEx)
    }.catch { t ->
        emit(PatchProgress(PatchProgress.Stage.Failed, t.message ?: "Erro", 0f, t))
    }.flowOn(Dispatchers.IO)

    fun launchFridaAgent(agentJsPath: String, gamePackage: String = GameActivity.DEFAULT_GAME_PACKAGE): Flow<PatchProgress> = flow {
        val env = gameEnvRepo.detect(gamePackage)
        if (!env.isInstalled) {
            emit(PatchProgress(PatchProgress.Stage.Failed, "Jogo base não instalado", 0f, IllegalStateException("Jogo não instalado: $gamePackage")))
            return@flow
        }
        emit(PatchProgress(PatchProgress.Stage.Installing, "Instalando Frida gadget…", 0f))
        val nativeDir = NativeLibsInstaller.ensureInstalled(context)
        val gadget = File(nativeDir, "libfrida-gadget.so").takeIf { it.isFile }
            ?: throw IllegalStateException("libfrida-gadget.so não bundled no APK")
        val agent = File(agentJsPath)
        val config = File(context.filesDir, "frida/libfrida-gadget.config.so").takeIf { it.isFile }
        NativeLibsInstaller.installFridaGadget(context, gadget, agent, config)
        emit(PatchProgress(PatchProgress.Stage.Preparing, "Preparando runtime…", 0f))
        modRuntime.prepare(env, privateBepInEx = false)
        emit(PatchProgress(PatchProgress.Stage.Ready, "Abrindo jogo com Frida…", 1f))
        val hasRuntime = "dotnet-arm64.zip" in context.assets.list("").orEmpty()
        startGame(env, privateBepInEx = false, noMods = !hasRuntime)
    }.catch { t ->
        emit(PatchProgress(PatchProgress.Stage.Failed, t.message ?: "Erro", 0f, t))
    }.flowOn(Dispatchers.IO)

    fun launchVanilla(gamePackage: String = GameActivity.DEFAULT_GAME_PACKAGE): Flow<PatchProgress> = flow {
        val env = gameEnvRepo.detect(gamePackage)
        if (!env.isInstalled) {
            emit(PatchProgress(PatchProgress.Stage.Failed, "Jogo base não instalado", 0f, IllegalStateException("Jogo não instalado: $gamePackage")))
            return@flow
        }
        NativeLibsInstaller.ensureInstalled(context)
        emit(PatchProgress(PatchProgress.Stage.Ready, "Abrindo jogo sem mods…", 1f))
        startGame(env, privateBepInEx = true, noMods = true)
    }.catch { t ->
        emit(PatchProgress(PatchProgress.Stage.Failed, t.message ?: "Erro", 0f, t))
    }.flowOn(Dispatchers.IO)

    private fun startGame(env: GameEnv, privateBepInEx: Boolean, noMods: Boolean = false) {
        GameEnvFactory.reset()
        val intent = GameActivity.newIntent(context, env.packageName, privateBepInEx, noMods)
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
