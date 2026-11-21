package com.larp.launcher

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class LarpApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var console: com.larp.launcher.core.console.Console

    override fun onCreate() {
        super.onCreate()
        com.larp.launcher.diagnostics.CrashReporter.installJavaHandler(this)

        val proc = if (android.os.Build.VERSION.SDK_INT >= 28) Application.getProcessName() else packageName
        if (!proc.endsWith(":game")) {
            com.larp.launcher.diagnostics.CrashReporter.watch(this, console)
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
}
