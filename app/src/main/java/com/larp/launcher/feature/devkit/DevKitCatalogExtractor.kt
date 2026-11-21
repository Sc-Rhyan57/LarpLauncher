package com.larp.launcher.feature.devkit

import android.content.Context
import com.larp.launcher.feature.launcher.GameActivity
import com.larp.launcher.patcher.GameEnv
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DevKitCatalogExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: DevKitStore
) {

    @Volatile private var job: Job? = null

    fun start(scope: CoroutineScope, force: Boolean = false) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) { run(force) }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun currentStamp(): String {
        val packageName = GameEnv.get()?.gamePackage ?: GameActivity.DEFAULT_GAME_PACKAGE
        val lastUpdate = runCatching { context.packageManager.getPackageInfo(packageName, 0).lastUpdateTime }.getOrDefault(0L)
        return "$packageName:$lastUpdate"
    }

    private suspend fun run(force: Boolean) {
        val stamp = currentStamp()
        if (!force && store.catalogFile().isFile && store.catalogStamp() == stamp) {
            store.saveCatalogStatus("${store.catalogCount()} entradas extraídas")
            return
        }
        store.saveCatalogStatus("Aguardando o jogo iniciar…")
        delay(INITIAL_DELAY_MS)
        val started = System.currentTimeMillis()
        var forceDump = false
        while (currentCoroutineContext().isActive) {
            val elapsed = System.currentTimeMillis() - started
            if (elapsed > GIVE_UP_MS) {
                store.saveCatalogStatus("Não foi possível extrair: o IL2CPP do jogo não ficou pronto")
                return
            }
            if (elapsed > FORCE_AFTER_MS) forceDump = true
            val result = DevKitNative.dump(store.catalogFile().absolutePath, forceDump)
            when {
                result >= 0 -> {
                    store.saveCatalogMeta(stamp, result)
                    store.saveCatalogStatus("$result entradas extraídas")
                    return
                }
                result == DevKitNative.NOT_LOADED -> {
                    store.saveCatalogStatus("Falha: biblioteca nativa do Dev Kit não carregou")
                    return
                }
                result == -2 -> {
                    store.saveCatalogStatus("Falha: o libil2cpp.so deste jogo não exporta as funções il2cpp_*")
                    return
                }
                result == -7 -> {
                    store.saveCatalogStatus("Extração abortada (falha nativa evitada). Tentando de novo…")
                }
                result == -5 -> {
                    store.saveCatalogStatus("Falha ao gravar o catálogo")
                    return
                }
                else -> store.saveCatalogStatus("Aguardando o jogo iniciar… (${elapsed / 1000}s)")
            }
            delay(POLL_MS)
        }
    }

    companion object {
        private const val POLL_MS = 2000L
        private const val INITIAL_DELAY_MS = 5_000L
        private const val FORCE_AFTER_MS = 60_000L
        private const val GIVE_UP_MS = 300_000L
    }
}
