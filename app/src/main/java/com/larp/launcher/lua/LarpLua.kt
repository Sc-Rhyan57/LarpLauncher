package com.larp.launcher.lua

import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

object LarpLua {

    private const val TAG = "LarpLua"
    private const val DIR_NAME = "larp"
    private const val SCRIPTS_DIR = "scripts"
    private const val WORKSPACE_DIR = "Larplaucher/Workspace"

    @Volatile private var initialized = false
    @Volatile private var luauInitialized = false
    private val ticking = AtomicBoolean(false)
    private val tickHandler = Handler(Looper.getMainLooper())
    private var lastTickMs = 0L
    private var screenW = 1080
    private var screenH = 1920
    private var scale = 1f

    fun rootDir(context: Context): File {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun scriptsDir(context: Context): File {
        val dir = File(rootDir(context), SCRIPTS_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun workspaceDir(context: Context): File {
        val external = Environment.getExternalStorageDirectory()
        val dir = File(external, WORKSPACE_DIR)
        if (!dir.exists()) {
            val ok = dir.mkdirs()
            if (!ok) {
                val fallback = File(context.getExternalFilesDir(null), WORKSPACE_DIR)
                if (!fallback.exists()) fallback.mkdirs()
                return fallback
            }
        }
        return dir
    }

    @Synchronized
    fun init(context: Context, width: Int = 1080, height: Int = 1920, sc: Float = 1f): Boolean {
        if (initialized) return true
        if (!LarpLuaNative.ensureLoaded()) return false
        screenW = width
        screenH = height
        scale = sc
        LarpLuaScriptInstaller.install(context)
        val root = rootDir(context).absolutePath
        val scripts = scriptsDir(context).absolutePath
        val workspace = workspaceDir(context).absolutePath
        val rc = LarpLuaNative.nativeInit(root, scripts, workspace, screenW, screenH, scale)
        if (rc != 0) {
            Log.e(TAG, "nativeInit rc=$rc")
            return false
        }
        initialized = true
        Log.i(TAG, "init ok root=$root scripts=$scripts workspace=$workspace ${width}x${height}")
        autoLoad(context)
        try {
            val rc2 = LarpLuaNative.nativeLuauInit(scripts, workspace, screenW, screenH, scale)
            if (rc2 == 0) {
                luauInitialized = true
                Log.i(TAG, "luau init ok")
                autoLoadLuau(context)
            } else {
                Log.w(TAG, "luau init rc=$rc2 (continuing without Luau)")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "luau unavailable: ${t.message}")
        }
        return true
    }

    fun autoLoad(context: Context) {
        val dir = scriptsDir(context)
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".lua") && !f.name.endsWith(".luau") }
            ?.sortedBy { it.name } ?: return
        for (f in files) {
            val rc = LarpLuaNative.nativeLoadFile(f.absolutePath)
            Log.i(TAG, "autoLoad ${f.name} rc=$rc")
        }
    }

    fun autoLoadLuau(context: Context) {
        val dir = scriptsDir(context)
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".luau") }
            ?.sortedBy { it.name } ?: return
        for (f in files) {
            try {
                val rc = LarpLuaNative.nativeLuauLoadFile(f.absolutePath)
                Log.i(TAG, "autoLoad luau ${f.name} rc=$rc")
            } catch (t: Throwable) {
                Log.w(TAG, "luau load ${f.name}: ${t.message}")
            }
        }
    }

    fun runScript(code: String, name: String? = null): Boolean {
        if (!initialized) return false
        val rc = LarpLuaNative.nativeRun(code, name)
        return rc == 0
    }

    fun runLuauScript(code: String, name: String? = null): Boolean {
        if (!luauInitialized) return false
        return try {
            LarpLuaNative.nativeLuauRun(code, name) == 0
        } catch (t: Throwable) {
            Log.w(TAG, "luau run: ${t.message}")
            false
        }
    }

    fun loadFile(path: String): Boolean {
        if (!initialized) return false
        return LarpLuaNative.nativeLoadFile(path) == 0
    }

    fun loadLuauFile(path: String): Boolean {
        if (!luauInitialized) return false
        return try {
            LarpLuaNative.nativeLuauLoadFile(path) == 0
        } catch (t: Throwable) {
            Log.w(TAG, "luau load: ${t.message}")
            false
        }
    }

    fun getScreenSize(): IntArray {
        if (!initialized) return intArrayOf(screenW, screenH)
        return try {
            LarpLuaNative.nativeGetScreen()
        } catch (t: Throwable) {
            intArrayOf(screenW, screenH)
        }
    }

    fun getWorkspacePath(): String {
        if (!initialized) return ""
        return try {
            LarpLuaNative.nativeGetWorkspace()
        } catch (t: Throwable) {
            ""
        }
    }

    fun startTicking(w: Int = screenW, h: Int = screenH) {
        if (!initialized) return
        if (!ticking.compareAndSet(false, true)) return
        screenW = w
        screenH = h
        lastTickMs = System.currentTimeMillis()
        tickHandler.post(tickRunnable)
    }

    fun stopTicking() {
        ticking.set(false)
        tickHandler.removeCallbacks(tickRunnable)
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!ticking.get()) return
            if (initialized) {
                val now = System.currentTimeMillis()
                val dt = if (lastTickMs > 0) (now - lastTickMs) / 1000f else 0f
                lastTickMs = now
                try {
                    LarpLuaNative.nativeTick(dt, screenW, screenH)
                    if (luauInitialized) {
                        try { LarpLuaNative.nativeLuauTick(dt, screenW, screenH) }
                        catch (t: Throwable) { Log.w(TAG, "luau tick: ${t.message}") }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "tick: ${t.message}")
                }
            }
            tickHandler.postDelayed(this, 16L)
        }
    }

    @Synchronized
    fun shutdown() {
        stopTicking()
        if (initialized) {
            try { LarpLuaNative.nativeShutdown() } catch (t: Throwable) { Log.w(TAG, "shutdown: ${t.message}") }
            initialized = false
        }
        if (luauInitialized) {
            try { LarpLuaNative.nativeLuauShutdown() } catch (t: Throwable) { Log.w(TAG, "luau shutdown: ${t.message}") }
            luauInitialized = false
        }
    }

    fun isReady(): Boolean = initialized
    fun isLuauReady(): Boolean = luauInitialized
}
