package com.larp.launcher.lua

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

object LarpLuaScriptInstaller {

    private const val TAG = "LarpLua"
    private const val ASSET_DIR = "larp/scripts"
    private const val VERSION_FILE = "scripts_version.txt"
    private const val CURRENT_VERSION = 2

    fun install(context: Context): File {
        val target = File(context.filesDir, "larp/scripts")
        target.mkdirs()
        val vFile = File(target, VERSION_FILE)
        if (vFile.exists() && vFile.readText().trim() == CURRENT_VERSION.toString()) {
            return target
        }
        runCatching {
            val list = context.assets.list(ASSET_DIR) ?: return@runCatching
            for (name in list) {
                if (!name.endsWith(".lua")) continue
                val out = File(target, name)
                context.assets.open("$ASSET_DIR/$name").use { input ->
                    FileOutputStream(out).use { output -> input.copyTo(output) }
                }
            }
            vFile.writeText(CURRENT_VERSION.toString())
            Log.i(TAG, "scripts installed: ${list.size} files")
        }.onFailure { Log.w(TAG, "script install: ${it.message}") }
        return target
    }
}
