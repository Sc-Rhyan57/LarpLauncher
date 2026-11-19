package com.larp.launcher.patcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.larp.launcher.data.local.cache.ModCacheManager
import com.larp.launcher.data.repository.GameEnvRepository
import com.larp.launcher.domain.model.GameEnv
import com.larp.launcher.domain.model.PatchProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GameLauncher @Inject constructor(
    private val context: Context,
    private val gameEnvRepo: GameEnvRepository,
    private val modRuntime: ModRuntime,
    private val modSync: ModSync,
    private val cache: ModCacheManager
) {

    fun launchWithMods(modIds: List<String>, privateBepInEx: Boolean = true): Flow<PatchProgress> = flow {
        emit(PatchProgress(PatchProgress.Stage.Downloading, "Iniciando sincronização…", 0f))
        val env = gameEnvRepo.detect()
        if (!env.isInstalled) {
            emit(PatchProgress(PatchProgress.Stage.Failed, "Jogo base não instalado", 0f, IllegalStateException("Jogo não instalado")))
            return@flow
        }
        try {
            modSync.run(modIds) { name, downloading, progress ->
                val stage = if (downloading) PatchProgress.Stage.Downloading else PatchProgress.Stage.Decrypting
                val msg = if (downloading) "Baixando $name…" else "Descompactando $name…"
                emit(PatchProgress(stage, msg, progress))
            }
            emit(PatchProgress(PatchProgress.Stage.Preparing, "Preparando runtime .NET…", 0f))
            val ok = modRuntime.prepare(env, privateBepInEx)
            if (!ok) {
                emit(PatchProgress(PatchProgress.Stage.Failed, "Runtime não encontrado no APK", 0f, IllegalStateException("Runtime ausente")))
                return@flow
            }
            emit(PatchProgress(PatchProgress.Stage.Ready, "Abrindo jogo…", 1f))
            startGame(env)
        } catch (t: Throwable) {
            emit(PatchProgress(PatchProgress.Stage.Failed, t.message ?: "Erro", 0f, t))
        }
    }.flowOn(Dispatchers.IO)

    fun launchLocalMods(dllPaths: List<String>, privateBepInEx: Boolean = true): Flow<PatchProgress> = flow {
        val env = gameEnvRepo.detect()
        if (!env.isInstalled) {
            emit(PatchProgress(PatchProgress.Stage.Failed, "Jogo base não instalado", 0f, IllegalStateException("Jogo não instalado")))
            return@flow
        }
        try {
            emit(PatchProgress(PatchProgress.Stage.Installing, "Copiando DLLs locais…", 0f))
            val plugins = cache.bepinexPluginsDir().apply { mkdirs() }
            dllPaths.forEach { src ->
                val file = java.io.File(src)
                if (file.isFile) file.copyTo(java.io.File(plugins, file.name), overwrite = true)
            }
            emit(PatchProgress(PatchProgress.Stage.Preparing, "Preparando runtime .NET…", 0f))
            modRuntime.prepare(env, privateBepInEx)
            emit(PatchProgress(PatchProgress.Stage.Ready, "Abrindo jogo…", 1f))
            startGame(env)
        } catch (t: Throwable) {
            emit(PatchProgress(PatchProgress.Stage.Failed, t.message ?: "Erro", 0f, t))
        }
    }.flowOn(Dispatchers.IO)

    private fun startGame(env: GameEnv) {
        val intent = context.packageManager.getLaunchIntentForPackage(env.packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            context.startActivity(intent)
        } else {
            val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${env.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(market) }
        }
    }
}
