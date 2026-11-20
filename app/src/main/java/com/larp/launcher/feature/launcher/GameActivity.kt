package com.larp.launcher.feature.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import android.content.res.Resources
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.larp.launcher.boot.Boot
import com.larp.launcher.diagnostics.Diario
import com.larp.launcher.patcher.GameContext
import com.larp.launcher.patcher.GameEnv
import com.larp.launcher.patcher.ModRuntime
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.runBlocking
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class GameActivity : ComponentActivity() {

    @Inject lateinit var modRuntime: ModRuntime

    private var env: GameEnv? = null
    private var appContext: GameContext? = null
    private var player: Any? = null
    private var playerClass: Class<*>? = null
    private var playerView: View? = null
    private var modsLoaded: Boolean = false
    private var diario: Diario? = null

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        val gamePackage = intent?.getStringExtra(EXTRA_GAME_PACKAGE) ?: DEFAULT_GAME_PACKAGE
        env = GameEnv.init(base, gamePackage)
        if (env != null) appContext = GameContext(base.applicationContext ?: base, env!!)
    }

    override fun getResources(): Resources = env?.resources ?: super.getResources()
    override fun getAssets(): AssetManager = env?.resources?.assets ?: super.getAssets()
    override fun getClassLoader(): ClassLoader = env?.classLoader ?: super.getClassLoader()
    override fun getApplicationInfo(): ApplicationInfo = env?.appInfo ?: super.getApplicationInfo()
    override fun getPackageName(): String = env?.gamePackage ?: super.getPackageName()
    override fun getPackageCodePath(): String = env?.appInfo?.sourceDir ?: super.getPackageCodePath()
    override fun getPackageResourcePath(): String = env?.appInfo?.publicSourceDir ?: super.getPackageResourcePath()
    override fun getApplicationContext(): Context = appContext ?: super.getApplicationContext()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        java.io.File(filesDir, ERROR_FILE).delete()
        val e = env
        if (e == null) {
            fail("Não consegui abrir o jogo instalado.", GameEnv.error())
            return
        }
        try {
            startUnity(e)
        } catch (t: Throwable) {
            fail("O jogo não iniciou.", unwrap(t))
        }
    }

    private fun startUnity(env: GameEnv) {
        val classLoader = env.classLoader
        Thread.currentThread().contextClassLoader = classLoader
        playerClass = classLoader.loadClass("com.unity3d.player.UnityPlayer")
        loadMods(env)
        runCatching { classLoader.loadClass("com.innersloth.spacemafia.EosUnityPlayerActivity") }
        val lifecycleEvents = classLoader.loadClass("com.unity3d.player.IUnityPlayerLifecycleEvents")
        val events = Proxy.newProxyInstance(classLoader, arrayOf(lifecycleEvents), UnityEventsHandler(this))
        val player = playerClass!!.getConstructor(Context::class.java, lifecycleEvents).newInstance(this, events)
        this.player = player
        val view = player as View
        playerView = view
        setContentView(view)
        view.requestFocus()
        if (modsLoaded) showProgress()
        loadEosSdkSafely(classLoader)
        Log.i(TAG, "UnityPlayer criado")
    }

    private fun loadEosSdkSafely(classLoader: ClassLoader) {
        runCatching {
            val candidates = listOf("libEOSSDK.so", "libepicgames-eossdk.so", "libeossdk.so")
            val applicationInfo = env?.appInfo
            val nativeLibDir = applicationInfo?.nativeLibraryDir
            if (nativeLibDir != null) {
                val libDir = File(nativeLibDir)
                if (libDir.isDirectory) {
                    for (candidate in candidates) {
                        val libFile = File(libDir, candidate)
                        if (libFile.isFile) {
                            val libName = candidate.removePrefix("lib").removeSuffix(".so")
                            runCatching { System.loadLibrary(libName) }
                            break
                        }
                    }
                }
            }
        }
        runCatching {
            val eossdkClass = classLoader.loadClass("com.epicgames.mobile.eossdk.EOSSDK")
            val initMethod = eossdkClass.getMethod("init", Activity::class.java)
            initMethod.invoke(null, this as Activity)
        }.onFailure { t ->
            Log.w(TAG, "EOSSDK init falhou (jogo pode rodar sem EOS): ${t.message}")
        }
    }

    private fun loadMods(env: GameEnv) {
        if (intent?.getBooleanExtra(EXTRA_NO_MODS, false) == true) return
        val privateMods = intent?.getBooleanExtra(EXTRA_PRIVATE_MODS, true) ?: true
        try {
            val domainEnv = com.larp.launcher.domain.model.GameEnv(
                packageName = env.gamePackage,
                versionName = "",
                versionCode = 0,
                nativeLibraryDir = env.appInfo.nativeLibraryDir ?: "",
                sourceDir = env.appInfo.sourceDir ?: "",
                isInstalled = true
            )
            val ok = runBlocking { modRuntime.prepare(domainEnv, privateMods) }
            if (ok) {
                Boot.load("fusion")
                modsLoaded = true
                Log.i(TAG, "Hospedeiro de mods carregado")
            } else {
                Log.w(TAG, "Runtime não encontrado no APK; mods desativados nesta sessão")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Mods desativados nesta sessão", unwrap(t))
            diario = Diario.begin(this)
            runBlocking { diario?.finish("mods_desativados: ${unwrap(t).message}") }
        }
    }

    private fun showProgress() {
        val isPt = Locale.getDefault().language == "pt"
        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#FF0D1117"))
            isClickable = true
        }
        val title = TextView(this).apply {
            text = if (isPt) "Preparando mods…" else "Preparing mods…"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
        }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        val status = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#FFA0A0A0"))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(64, 24, 64, 24)
        }
        overlay.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        overlay.addView(bar, LinearLayout.LayoutParams(dp(280), dp(8)).apply { topMargin = dp(18) })
        overlay.addView(status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addContentView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val bootClass = runCatching { playerClass?.classLoader?.loadClass("com.larp.boot.Boot") ?: Boot::class.java }.getOrDefault(Boot::class.java)
        val stageField = runCatching { bootClass.getField("stage") }.getOrNull()
        val messageField = runCatching { bootClass.getField("message") }.getOrNull()
        val handler = Handler(Looper.getMainLooper())
        val start = SystemClock.uptimeMillis()
        val d = Diario.begin(this)
        diario = d

        val tick = object : Runnable {
            var shown = 0
            override fun run() {
                val stage = stageField?.getInt(null) ?: 0
                val msg = messageField?.get(null)?.toString() ?: ""
                val elapsed = SystemClock.uptimeMillis() - start
                runBlocking { d.step("stage=$stage msg=$msg") }
                if (stage < 2 && elapsed <= MAX_WAIT_MS && !isFinishing) {
                    val pct = progressFor(stage, msg)
                    if (pct > shown) shown = pct
                    bar.progress = shown
                    title.text = describe(msg, isPt)
                    if (shown in 10..79) {
                        val mins = elapsed / 60000
                        status.text = if (isPt) "Isso só acontece na primeira vez e quando o jogo atualiza. Pode levar alguns minutos em celulares simples: não saia do app." + (if (mins >= 1) "\n$mins min" else "")
                        else "This only happens the first time and when the game updates. It can take a few minutes on simpler phones: don't leave the app." + (if (mins >= 1) "\n$mins min" else "")
                    }
                    handler.postDelayed(this, 200)
                    return
                }
                runBlocking { d.finish(if (stage >= 2) "ok" else if (isFinishing) "fechado" else "tempo_esgotado") }
                bar.progress = 100
                overlay.animate().alpha(0f).setDuration(350).withEndAction {
                    (overlay.parent as? ViewGroup)?.removeView(overlay)
                }.start()
            }
        }
        handler.post(tick)
    }

    private fun fail(message: String, throwable: Throwable?) {
        val text = if (throwable != null) "$message\n\n${Log.getStackTraceString(throwable)}" else message
        Log.e(TAG, text, throwable)
        val view = TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.BLACK)
            textSize = 12f
            setPadding(48, 48, 48, 48)
            setTextIsSelectable(true)
        }
        setContentView(android.widget.ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(view)
        })
        runBlocking { diario?.finish(message) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private class UnityEventsHandler(val activity: GameActivity) : InvocationHandler {
        override fun invoke(proxy: Any?, method: java.lang.reflect.Method, args: Array<out Any>?): Any? {
            return when (method.name) {
                "onUnityPlayerUnloaded" -> { activity.moveTaskToBack(true); null }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === (args?.getOrNull(0))
                "toString" -> "LarpUnityEvents"
                else -> null
            }
        }
    }

    companion object {
        private const val TAG = "LarpGameActivity"
        private const val ERROR_FILE = "ultimo_erro.txt"
        private const val EXTRA_PRIVATE_MODS = "mods_privados"
        private const val EXTRA_GAME_PACKAGE = "game_package"
        private const val EXTRA_NO_MODS = "sem_mods"
        const val DEFAULT_GAME_PACKAGE = "com.innersloth.spacemafia"
        private const val MAX_WAIT_MS = 1_200_000L

        fun newIntent(context: Context, gamePackage: String, privateMods: Boolean, noMods: Boolean = false): Intent =
            Intent(context, GameActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_GAME_PACKAGE, gamePackage)
                putExtra(EXTRA_PRIVATE_MODS, privateMods)
                putExtra(EXTRA_NO_MODS, noMods)
            }

        fun progressFor(stage: Int, msg: String): Int {
            return when {
                msg.startsWith("Downloading Unity") -> 10
                msg.startsWith("Running Cpp2IL") -> 25
                msg.startsWith("Generating IL2CPP") -> 50
                msg.startsWith("Initialized Chainloader") -> 80
                msg.startsWith("Running Chainloader") -> 85
                stage >= 2 -> 100
                else -> 5
            }
        }

        fun describe(msg: String, isPt: Boolean): String {
            if (msg.isBlank()) return if (isPt) "Inicializando…" else "Initializing…"
            return msg
        }

        fun unwrap(t: Throwable): Throwable {
            var cur: Throwable = t
            while (cur.cause != null && cur.cause !== cur) cur = cur.cause!!
            return cur
        }
    }
}
