package com.larp.launcher.lua

object LarpLuaNative {

    @Volatile private var loaded = false

    external fun nativeInit(root: String, scripts: String, workspace: String, w: Int, h: Int, scale: Float): Int
    external fun nativeRun(code: String, name: String?): Int
    external fun nativeLoadFile(path: String): Int
    external fun nativeTick(dt: Float, w: Int, h: Int)
    external fun nativeShutdown()

    external fun nativeLuauInit(scripts: String, workspace: String, w: Int, h: Int, scale: Float): Int
    external fun nativeLuauRun(code: String, name: String?): Int
    external fun nativeLuauLoadFile(path: String): Int
    external fun nativeLuauTick(dt: Float, w: Int, h: Int)
    external fun nativeLuauShutdown()

    external fun nativeGetScreen(): IntArray
    external fun nativeGetWorkspace(): String

    @Synchronized
    fun ensureLoaded(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("larplua")
            try { System.loadLibrary("larpluau") } catch (t: Throwable) {
                android.util.Log.w("LarpLua", "liblarpluau falhou: ${t.message}")
            }
            loaded = true
            true
        } catch (t: Throwable) {
            android.util.Log.w("LarpLua", "load liblarplua falhou: ${t.message}")
            false
        }
    }
}
