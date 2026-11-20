package com.larp.launcher.feature.devkit

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

class GameRelaunchReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val target = if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_TARGET, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_TARGET)
        } ?: return
        val pending = goAsync()
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching { context.startActivity(target) }
            pending.finish()
        }, 900)
    }

    companion object {
        const val ACTION = "com.larp.launcher.devkit.RELAUNCH"
        const val EXTRA_TARGET = "target"
    }
}
