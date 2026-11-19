package com.larp.launcher.patcher

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.system.Os
import android.util.Log
import dalvik.system.PathClassLoader
import java.io.File
import java.io.IOException

class GameEnv private constructor(
    context: Context,
    val gamePackage: String,
    val appInfo: ApplicationInfo,
    val classLoader: ClassLoader,
    val gameContext: Context,
    val resources: Resources,
    val nativeLibsDir: File
) {

    fun interface Listener {
        fun onProgress(stage: String, message: String, progress: Float)
    }

    companion object {
        private const val TAG = "LarpLauncher"
        private var instance: GameEnv? = null
        private var initError: Throwable? = null

        fun init(context: Context, gamePackage: String): GameEnv? {
            if (instance == null && initError == null) {
                synchronized(GameEnv::class.java) {
                    if (instance == null && initError == null) {
                        runCatching { build(context, gamePackage) }
                            .onSuccess { instance = it }
                            .onFailure {
                                initError = it
                                Log.e(TAG, "Falha ao preparar o jogo", it)
                            }
                    }
                }
            }
            return instance
        }

        fun get(): GameEnv? = instance
        fun error(): Throwable? = initError
        fun reset() {
            synchronized(GameEnv::class.java) {
                instance = null
                initError = null
            }
        }

        private fun build(context: Context, gamePackage: String): GameEnv {
            val pm = context.packageManager
            val gameAppInfo = pm.getApplicationInfo(gamePackage, PackageManager.GET_SHARED_LIBRARY_FILES)
            val launcherAppInfo = context.applicationInfo
            val gameContext = context.createPackageContext(gamePackage, Context.CONTEXT_IGNORE_SECURITY)

            val classpath = StringBuilder(gameAppInfo.sourceDir)
            gameAppInfo.splitSourceDirs?.forEach { classpath.append(File.pathSeparator).append(it) }
            val bootDex = extractBootDex(context)
            if (bootDex != null) classpath.append(File.pathSeparator).append(bootDex.absolutePath)

            val nativeLibsDir = NativeLibsInstaller.ensureInstalled(context)
            val libSearchPath = buildString {
                append(nativeLibsDir.absolutePath).append(File.pathSeparator)
                append(launcherAppInfo.nativeLibraryDir).append(File.pathSeparator)
                append(gameAppInfo.nativeLibraryDir)
            }
            val parent = ClassLoader.getSystemClassLoader().parent
            val classLoader = PathClassLoader(classpath.toString(), libSearchPath, parent)

            val cloned = ApplicationInfo(gameAppInfo).apply {
                dataDir = launcherAppInfo.dataDir
                if (android.os.Build.VERSION.SDK_INT >= 24) {
                    deviceProtectedDataDir = launcherAppInfo.deviceProtectedDataDir
                    credentialProtectedDataDir = launcherAppInfo.credentialProtectedDataDir
                }
                uid = launcherAppInfo.uid
                processName = launcherAppInfo.processName
                nativeLibraryDir = nativeLibsDir.absolutePath
            }

            Log.i(TAG, "Jogo: ${gameAppInfo.sourceDir} libs=${gameAppInfo.nativeLibraryDir} nativelib=$nativeLibsDir")
            return GameEnv(
                context = context,
                gamePackage = gamePackage,
                appInfo = cloned,
                classLoader = classLoader,
                gameContext = gameContext,
                resources = gameContext.resources,
                nativeLibsDir = nativeLibsDir
            )
        }

        private fun extractBootDex(context: Context): File? {
            val target = File(context.codeCacheDir, "larp-boot.dex")
            return runCatching {
                context.assets.open("larp-boot.dex").use { input ->
                    target.delete()
                    target.outputStream().use { output -> input.copyTo(output, 8192) }
                    target.setReadOnly()
                }
                target
            }.onFailure { Log.w(TAG, "larp-boot.dex não encontrado em assets; usando Boot.kt embutido", it) }
                .getOrNull()
        }
    }
}

object GameEnvFactory {
    fun create(context: Context, gamePackage: String): GameEnv? = GameEnv.init(context, gamePackage)
    fun reset() = GameEnv.reset()
}
