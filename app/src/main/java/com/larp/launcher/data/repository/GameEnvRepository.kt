package com.larp.launcher.data.repository

import android.content.Context
import android.content.pm.PackageManager
import com.larp.launcher.domain.model.GameEnv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GameEnvRepository @Inject constructor(
    private val context: Context
) {

    suspend fun detect(targetPackage: String = DEFAULT_GAME_PACKAGE): GameEnv = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val installed = runCatching { pm.getPackageInfo(targetPackage, 0) }.isSuccess
        if (!installed) {
            return@withContext GameEnv(
                packageName = targetPackage,
                versionName = "",
                versionCode = 0,
                nativeLibraryDir = "",
                sourceDir = "",
                isInstalled = false
            )
        }
        val info = pm.getPackageInfo(targetPackage, 0)
        val appInfo = pm.getApplicationInfo(targetPackage, 0)
        GameEnv(
            packageName = targetPackage,
            versionName = info.versionName.orEmpty(),
            versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong(),
            nativeLibraryDir = appInfo.nativeLibraryDir,
            sourceDir = appInfo.sourceDir,
            isInstalled = true,
            isExternal = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_EXTERNAL_STORAGE) != 0
        )
    }

    companion object {
        const val DEFAULT_GAME_PACKAGE = "com.regis.game"
    }
}
