package com.larp.launcher.patcher

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import android.content.res.Resources

class GameContext(base: Context, private val env: GameEnv) : ContextWrapper(base) {

    override fun getApplicationContext(): Context = this

    override fun getResources(): Resources = env.resources

    override fun getAssets(): AssetManager = env.resources.assets

    override fun getClassLoader(): ClassLoader = env.classLoader

    override fun getApplicationInfo(): ApplicationInfo = env.appInfo

    override fun getPackageName(): String = env.gamePackage

    override fun getPackageCodePath(): String = env.appInfo.sourceDir

    override fun getPackageResourcePath(): String = env.appInfo.publicSourceDir
}
