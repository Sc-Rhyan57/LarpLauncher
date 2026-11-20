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
import com.larp.launcher.feature.devkit.DevKitOverlayService
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
    private var devKitStarted: Boolean = false
    private var resumed: Boolean = false
    @Volatile private var elapsedSkip: Boolean = false

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
        setTheme(android.R.style.Theme_Material_NoActionBar)
        super.onCreate(savedInstanceState)
        current = java.lang.ref.WeakReference(this)
        java.io.File(filesDir, ERROR_FILE).delete()
        val e = env
        if (e == null) {
            fail("Não consegui abrir o jogo instalado.", GameEnv.error())
            return
        }
        if (intent?.getBooleanExtra(EXTRA_NO_MODS, false) == true || intent?.getBooleanExtra(EXTRA_FRIDA, false) == true) {
            runStart(e)
        } else {
            prepareModsAsync(e)
        }
    }

    private fun runStart(e: GameEnv) {
        try {
            if (intent?.getBooleanExtra(EXTRA_FRIDA, false) == true) loadFridaGadget(e)
            startUnity(e)
        } catch (t: Throwable) {
            fail("O jogo não iniciou.", unwrap(t))
        }
    }

    private fun prepareModsAsync(e: GameEnv) {
        val isPt = Locale.getDefault().language == "pt"
        val title = TextView(this).apply {
            text = if (isPt) "Carregando mods…" else "Loading mods…"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
        }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        val status = TextView(this).apply {
            setTextColor(Color.parseColor("#FFA0A0A0"))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(64, 24, 64, 24)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(bar, LinearLayout.LayoutParams(dp(280), dp(8)).apply { topMargin = dp(18) })
            addView(status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        setContentView(root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val privateMods = intent?.getBooleanExtra(EXTRA_PRIVATE_MODS, true) ?: true
        Thread {
            var error: Throwable? = null
            var ok = false
            try {
                val domainEnv = com.larp.launcher.domain.model.GameEnv(
                    packageName = e.gamePackage,
                    versionName = "",
                    versionCode = 0,
                    nativeLibraryDir = e.gameNativeLibDir.absolutePath,
                    sourceDir = e.appInfo.sourceDir ?: "",
                    isInstalled = true
                )
                ok = runBlocking {
                    modRuntime.prepare(domainEnv, privateMods) { msg, fraction ->
                        runOnUiThread {
                            status.text = msg
                            bar.progress = (fraction * 100).toInt()
                        }
                    }
                }
                if (ok) {
                    runOnUiThread { status.text = if (isPt) "Iniciando BepInEx…" else "Starting BepInEx…" }
                    Boot.load("fusion")
                    if (Boot.stage < 1) throw IllegalStateException(Boot.message)
                    modsLoaded = true
                }
            } catch (t: Throwable) {
                error = unwrap(t)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when {
                    error != null -> fail("Os mods não puderam ser carregados.", error)
                    !ok -> fail("Runtime de mods indisponível. Abra Ajustes → Runtime e baixe dotnet-arm64.zip e BepInEx-arm64.zip.", null)
                    else -> runStart(e)
                }
            }
        }.start()
    }

    private fun callPlayer(name: String, types: Array<Class<*>> = emptyArray(), vararg args: Any?) {
        val p = player ?: return
        runCatching { playerClass?.getMethod(name, *types)?.invoke(p, *args) }
            .onFailure { Log.w(TAG, "UnityPlayer.$name falhou: ${unwrap(it).message}") }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        callPlayer("resume")
    }

    override fun onPause() {
        resumed = false
        super.onPause()
        callPlayer("pause")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        callPlayer("windowFocusChanged", arrayOf(Boolean::class.javaPrimitiveType!!), hasFocus)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        callPlayer("configurationChanged", arrayOf(android.content.res.Configuration::class.java), newConfig)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        callPlayer("lowMemory")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        callPlayer("newIntent", arrayOf(Intent::class.java), intent)
    }

    override fun onDestroy() {
        if (current?.get() === this) current = null
        callPlayer("destroy")
        if (devKitStarted) {
            runCatching { baseContext.stopService(launcherServiceIntent(DevKitOverlayService::class.java)) }
            devKitStarted = false
        }
        super.onDestroy()
    }

    private fun startUnity(env: GameEnv) {
        val classLoader = env.classLoader
        Thread.currentThread().contextClassLoader = classLoader
        playerClass = classLoader.loadClass("com.unity3d.player.UnityPlayer")
        runCatching { Class.forName("com.innersloth.spacemafia.EosUnityPlayerActivity", true, classLoader) }
        val lifecycleEvents = classLoader.loadClass("com.unity3d.player.IUnityPlayerLifecycleEvents")
        val events = Proxy.newProxyInstance(classLoader, arrayOf(lifecycleEvents), UnityEventsHandler(this))
        val player = playerClass!!.getConstructor(Context::class.java, lifecycleEvents).newInstance(this, events)
        this.player = player
        val view = player as View
        playerView = view
        setContentView(view)
        view.requestFocus()
        if (resumed) {
            callPlayer("resume")
            callPlayer("windowFocusChanged", arrayOf(Boolean::class.javaPrimitiveType!!), hasWindowFocus())
        }
        if (modsLoaded) showProgress()
        loadEosSdkSafely(classLoader)
        maybeStartDevKitOverlay()
        maybeStartVoiceBubble()
        Log.i(TAG, "UnityPlayer criado")
    }

    private fun loadFridaGadget(env: GameEnv) {
        val gadget = File(env.nativeLibsDir, "libfrida-gadget.so")
        val script = File(env.nativeLibsDir, "libfrida-gadget.script.so")
        if (!gadget.isFile) throw IllegalStateException("libfrida-gadget.so ausente em ${env.nativeLibsDir}")
        if (!script.isFile) throw IllegalStateException("agent.js não instalado (libfrida-gadget.script.so ausente)")
        System.load(gadget.absolutePath)
        Log.i(TAG, "Frida gadget carregado: ${gadget.absolutePath}")
    }

    private fun launcherServiceIntent(service: Class<*>): Intent =
        Intent().setComponent(android.content.ComponentName(baseContext.packageName, service.name))

    private fun maybeStartDevKitOverlay() {
        if (devKitStarted) return
        val enabled = DevKitOverlayService.isDevKitEnabled(this)
        if (!enabled && !modsLoaded) return
        if (!android.provider.Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Dev Kit ativo mas SYSTEM_ALERT_WINDOW não concedido")
            return
        }
        runCatching {
            val intent = launcherServiceIntent(DevKitOverlayService::class.java)
            val component = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                baseContext.startForegroundService(intent)
            } else {
                baseContext.startService(intent)
            }
            if (component == null) {
                Log.w(TAG, "Dev Kit overlay: serviço não encontrado")
                return
            }
            devKitStarted = true
            Log.i(TAG, "Dev Kit overlay iniciado")
        }.onFailure { Log.w(TAG, "Falha ao iniciar Dev Kit overlay: ${it.message}") }
    }

    private fun maybeStartVoiceBubble() {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            Log.i(TAG, "Voice bubble pulado: SYSTEM_ALERT_WINDOW não concedido")
            return
        }
        val prefs = getSharedPreferences("larp_voice", android.content.Context.MODE_PRIVATE)
        val connected = prefs.getBoolean("connected", false)
        if (!connected) {
            Log.i(TAG, "Voice bubble pulado: usuário não está em chamada")
            return
        }
        runCatching {
            com.larp.launcher.feature.voice.FloatingVoiceBubbleService.show(baseContext)
            Log.i(TAG, "Voice bubble iniciado sobre o jogo")
        }.onFailure { Log.w(TAG, "Falha ao iniciar voice bubble: ${it.message}") }
    }

    private fun loadEosSdkSafely(classLoader: ClassLoader) {
        val from = playerClass
        if (from != null) {
            for (libName in listOf("EOSSDK", "epicgames-eossdk", "eossdk")) {
                val found = (classLoader as? dalvik.system.BaseDexClassLoader)?.findLibrary(libName) != null
                if (found && loadInGameLoader(libName, from)) break
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

    private fun loadInGameLoader(libName: String, from: Class<*>): Boolean {
        val runtime = Runtime.getRuntime()
        val attempts = listOf<() -> Unit>(
            {
                runtime.javaClass.getDeclaredMethod("loadLibrary0", Class::class.java, String::class.java)
                    .apply { isAccessible = true }.invoke(runtime, from, libName)
            },
            {
                runtime.javaClass.getDeclaredMethod("loadLibrary0", ClassLoader::class.java, Class::class.java, String::class.java)
                    .apply { isAccessible = true }.invoke(runtime, from.classLoader, from, libName)
            },
            {
                runtime.javaClass.getDeclaredMethod("loadLibrary0", ClassLoader::class.java, String::class.java)
                    .apply { isAccessible = true }.invoke(runtime, from.classLoader, libName)
            }
        )
        for (attempt in attempts) {
            if (runCatching(attempt).isSuccess) return true
        }
        return runCatching { System.loadLibrary(libName) }.isSuccess
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

        val handler = Handler(Looper.getMainLooper())
        val start = SystemClock.uptimeMillis()
        val d = Diario.begin(this)
        diario = d

        val tick = object : Runnable {
            var shown = 0
            override fun run() {
                if (elapsedSkip) {
                    runBlocking { d.finish("ignorado") }
                    (overlay.parent as? ViewGroup)?.removeView(overlay)
                    return
                }
                val stage = Boot.stage
                val msg = Boot.message
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
                    if (elapsed > SKIP_AFTER_MS) {
                        status.text = if (isPt) "Toque na tela para fechar este aviso e entrar no jogo." else "Tap the screen to dismiss this and enter the game."
                        overlay.setOnClickListener { elapsedSkip = true }
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
        private const val EXTRA_FRIDA = "frida"
        const val DEFAULT_GAME_PACKAGE = "com.innersloth.spacemafia"
        private const val MAX_WAIT_MS = 1_200_000L
        private const val SKIP_AFTER_MS = 20_000L

        @Volatile var current: java.lang.ref.WeakReference<GameActivity>? = null

        fun relaunchIntent(noMods: Boolean?): Intent? {
            val activity = current?.get() ?: return null
            val base = Intent(activity.intent)
            if (noMods != null) base.putExtra(EXTRA_NO_MODS, noMods)
            if (noMods == true) base.putExtra(EXTRA_FRIDA, false)
            base.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            base.component = android.content.ComponentName(activity.baseContext.packageName, GameActivity::class.java.name)
            return base
        }

        fun finishGame() {
            current?.get()?.let { runCatching { it.finishAndRemoveTask() } }
        }

        fun newIntent(context: Context, gamePackage: String, privateMods: Boolean, noMods: Boolean = false, frida: Boolean = false): Intent =
            Intent(context, GameActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_GAME_PACKAGE, gamePackage)
                putExtra(EXTRA_PRIVATE_MODS, privateMods)
                putExtra(EXTRA_NO_MODS, noMods)
                putExtra(EXTRA_FRIDA, frida)
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
