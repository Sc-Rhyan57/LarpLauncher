package com.larp.launcher.feature.devkit

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.content.ClipData
import android.content.ClipboardManager
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import java.io.File
import androidx.core.app.NotificationCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.larp.launcher.MainActivity
import com.larp.launcher.domain.model.DevKitSnapshot
import com.larp.launcher.domain.model.DevKitVariable
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject

private val Context.devkitPrefs by preferencesDataStore(name = "larp_devkit")

@AndroidEntryPoint
class DevKitOverlayService : Service() {

    @Inject lateinit var devKitStore: DevKitStore
    @Inject lateinit var devKitBridge: DevKitBridge
    @Inject lateinit var catalogExtractor: DevKitCatalogExtractor
    @Inject lateinit var json: Json
    @Inject lateinit var modCache: com.larp.launcher.data.local.cache.ModCacheManager
    @Inject lateinit var console: com.larp.launcher.core.console.Console

    private lateinit var windowManager: WindowManager
    private lateinit var handler: Handler
    private lateinit var scope: CoroutineScope
    private var refreshJob: Job? = null
    private var overlayView: View? = null
    private var variables: List<DevKitVariable> = emptyList()
    private var latestSnapshot: DevKitSnapshot? = null

    private var choreographer: Choreographer? = null
    private var frameCount = 0
    private var lastFps = 0
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            frameCount++
            choreographer?.postFrameCallback(this)
        }
    }
    private val fpsRunnable = object : Runnable {
        override fun run() {
            lastFps = frameCount
            frameCount = 0
            updateFpsPill(lastFps)
            handler.postDelayed(this, 1000)
        }
    }

    private val cBgDark = 0xFF181213.toInt()
    private val cSurface = 0xFF1F1719.toInt()
    private val cSurfaceHigh = 0xFF2D2526.toInt()
    private val cOnSurface = 0xFFEDE0E0.toInt()
    private val cOnSurfaceVar = 0xFFD7C0C1.toInt()
    private val cPrimary = 0xFF7E5260.toInt()
    private val cPrimaryContainer = 0xFFFFD9DD.toInt()
    private val cPrimaryDark = 0xFFF4B3BB.toInt()
    private val cOutline = 0xFF9F8C8C.toInt()
    private val cBroadcastInfo = 0xFF3B6FE0.toInt()
    private val cBroadcastCritical = 0xFFB3261E.toInt()
    private val cStatusGreen = 0xFF4CAF50.toInt()
    private val cStatusYellow = 0xFFFFC107.toInt()
    private val cStatusRed = 0xFFEF4444.toInt()

    private val bubbleSizeDp = 56
    private val panelWidthDp = 300
    private val panelHeightDp = 390
    private val panelRadiusDp = 24
    private val contentClipRadiusDp = 12
    private val panelStrokeDp = 2

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        handler = Handler(Looper.getMainLooper())
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        startForegroundCompat()
        choreographer = Choreographer.getInstance()
        choreographer?.postFrameCallback(frameCallback)
        handler.postDelayed(fpsRunnable, 1000)
        loadVariablesAndShowOverlay()
    }

    private fun startForegroundCompat() {
        val notification = buildNotification("Dev Kit ativo")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH) {
            scope.launch { refreshOnce() }
        } else if (intent?.action == ACTION_EXTRACT) {
            catalogExtractor.stop()
            catalogExtractor.start(scope, true)
        } else if (intent?.action == ACTION_STOP) {
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        refreshJob?.cancel()
        choreographer?.removeFrameCallback(frameCallback)
        handler.removeCallbacks(fpsRunnable)
        catalogExtractor.stop()
        runCatching { overlayView?.let { windowManager.removeView(it) } }
        overlayView = null
        scope.cancel()
        super.onDestroy()
    }

    private fun loadVariablesAndShowOverlay() {
        scope.launch {
            variables = devKitStore.loadVariables()
            handler.post { showOverlay() }
            if (isDevKitEnabled(this@DevKitOverlayService)) catalogExtractor.start(scope)
            startRefreshLoop()
        }
    }

    private fun startRefreshLoop() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            while (true) {
                refreshOnce()
                kotlinx.coroutines.delay(REFRESH_INTERVAL_MS)
            }
        }
    }

    private suspend fun refreshOnce() {
        if (variables.isEmpty()) {
            variables = devKitStore.loadVariables()
        }
        val snapshot = devKitBridge.snapshot(variables, UUID.randomUUID().toString())
        latestSnapshot = snapshot
        devKitStore.saveSnapshot(snapshot)
        handler.post { updateOverlay(snapshot) }
    }

    private var expanded = false
    private var tab = 0
    private var bodyView: TextView? = null
    private var fpsPillView: TextView? = null
    private var statusDotView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private fun showOverlay() {
        if (overlayView != null) return
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW não concedido")
            stopSelf()
            return
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 16
        params.y = 120
        layoutParams = params
        val root = FrameLayout(this)
        overlayView = root
        runCatching { windowManager.addView(root, params) }
            .onFailure { Log.e(TAG, "Falha ao adicionar overlay: ${it.message}") }
        render()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun roundRectDrawable(color: Int, cornerDp: Int, strokeDp: Int = 0, strokeColor: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(cornerDp).toFloat()
            setColor(color)
            if (strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
        }

    private fun panelGradientDrawable(cornerDp: Int, strokeDp: Int, strokeColor: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(cornerDp).toFloat()
            colors = intArrayOf(cBgDark, cSurface)
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            setStroke(dp(strokeDp), strokeColor)
        }

    private fun ovalDrawable(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

    private fun pill(bgColor: Int, text: String, textColor: Int = Color.WHITE, cornerDp: Int = 12, sizeSp: Float = 11f): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(textColor)
            textSize = sizeSp
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = roundRectDrawable(bgColor, cornerDp)
        }

    private fun statusDotColor(): Int {
        val stage = com.regis.boot.Boot.stage
        return when {
            stage >= 2 -> cStatusGreen
            stage == 1 -> cStatusYellow
            else -> cStatusRed
        }
    }

    private fun addSpace(parent: LinearLayout, heightDp: Int) {
        parent.addView(View(this), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp)))
    }

    private fun updateFpsPill(fps: Int) {
        fpsPillView?.text = if (fps <= 0) "0" else fps.toString()
    }

    private fun updateStatusDot() {
        statusDotView?.background = ovalDrawable(statusDotColor())
    }

    private fun render() {
        val root = overlayView as? FrameLayout ?: return
        val params = layoutParams ?: return
        root.removeAllViews()
        fpsPillView = null
        statusDotView = null
        bodyView = null

        if (!expanded) {
            renderBubble(root, params)
            runCatching { windowManager.updateViewLayout(root, params) }
            return
        }

        renderPanel(root, params)
        runCatching { windowManager.updateViewLayout(root, params) }
    }

    private fun renderBubble(root: FrameLayout, params: WindowManager.LayoutParams) {
        val bubble = BubbleView().apply {
            contentDescription = "Abrir workspace. Bolha flutuante. Arraste para reposicionar."
            setOnTouchListener(DragHandler(windowManager, root, params) { expanded = true; render() })
        }
        root.addView(bubble, FrameLayout.LayoutParams(dp(bubbleSizeDp), dp(bubbleSizeDp)))
    }

    private fun renderPanel(root: FrameLayout, params: WindowManager.LayoutParams) {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panelGradientDrawable(panelRadiusDp, panelStrokeDp, cPrimaryDark)
            elevation = dp(10).toFloat()
            setPadding(dp(14), dp(14), dp(14), dp(14))
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setOnTouchListener(DragHandler(windowManager, root, params, null))
        }
        val statusDot = View(this).apply { background = ovalDrawable(statusDotColor()) }
        statusDotView = statusDot
        header.addView(statusDot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(8) })
        val title = TextView(this).apply {
            text = "Larp"
            setTextColor(cOnSurface)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
        }
        header.addView(title, LinearLayout.LayoutParams(0, WindowManager.LayoutParams.WRAP_CONTENT, 1f))

        val closeBtn = CloseButtonView().apply {
            setOnClickListener { expanded = false; render() }
            contentDescription = "Fechar"
        }
        header.addView(closeBtn, LinearLayout.LayoutParams(dp(28), dp(28)))
        panel.addView(header)

        addSpace(panel, 10)

        val statsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val fpsTv = pill(cBroadcastInfo, "0")
        fpsPillView = fpsTv
        statsRow.addView(fpsTv, LinearLayout.LayoutParams(0, WindowManager.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(4) })
        val pingTv = pill(cPrimary, "--")
        statsRow.addView(pingTv, LinearLayout.LayoutParams(0, WindowManager.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(4) })
        val exitBtn = pill(cBroadcastCritical, "SAIR")
        exitBtn.setOnClickListener { quitGame() }
        statsRow.addView(exitBtn, LinearLayout.LayoutParams(0, WindowManager.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(statsRow)

        addSpace(panel, 10)

        val names = listOf("Mods", "Logs", "Dev", "Ações")
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        names.forEachIndexed { i, n ->
            val tabView = TextView(this).apply {
                text = n
                setTextColor(if (i == tab) Color.WHITE else cOnSurfaceVar)
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(6), dp(10), dp(6))
                background = roundRectDrawable(
                    if (i == tab) cPrimary else Color.TRANSPARENT,
                    cornerDp = 14
                )
                setOnClickListener { tab = i; render() }
            }
            val lp = LinearLayout.LayoutParams(0, WindowManager.LayoutParams.WRAP_CONTENT, 1f)
            if (i < names.size - 1) lp.rightMargin = dp(4)
            tabs.addView(tabView, lp)
        }
        panel.addView(tabs)

        addSpace(panel, 10)

        if (tab == 3) {
            val actions = listOf<Pair<String, () -> Unit>>(
                "Reiniciar jogo (recarrega mods)" to { restartGame(null) },
                "Reiniciar sem mods" to { restartGame(true) },
                "Sair do jogo" to { quitGame() },
                "Copiar logs" to { copyLogs() },
                "Extrair catálogo (Dev Kit)" to { catalogExtractor.stop(); catalogExtractor.start(scope, true) },
                "Abrir no app" to { openInApp() },
                "Fechar este menu" to { stopSelf() }
            )
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            actions.forEach { (n, f) ->
                val item = TextView(this).apply {
                    text = n
                    setTextColor(cOnSurface)
                    textSize = 11f
                    setPadding(dp(4), dp(6), dp(4), dp(6))
                    setOnClickListener { f() }
                }
                col.addView(item)
            }
            val scroll = ScrollView(this).apply {
                addView(col)
                background = roundRectDrawable(cSurface, cornerDp = contentClipRadiusDp)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                clipToOutline = true
                outlineProvider = ViewOutlineProvider.BACKGROUND
            }
            panel.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            bodyView = null
        } else {
            val body = TextView(this).apply {
                setTextColor(cOnSurface)
                textSize = 10f
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
            bodyView = body
            val scroll = ScrollView(this).apply {
                addView(body)
                background = roundRectDrawable(cSurface, cornerDp = contentClipRadiusDp)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                clipToOutline = true
                outlineProvider = ViewOutlineProvider.BACKGROUND
            }
            panel.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            refreshBody()
        }

        addSpace(panel, 6)

        val divider = View(this).apply { setBackgroundColor(cPrimaryDark) }
        panel.addView(divider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
        val footer = TextView(this).apply {
            text = "Larp Dev Menu v0.2"
            setTextColor(cOnSurfaceVar)
            textSize = 10f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        }
        panel.addView(footer)

        root.addView(panel, FrameLayout.LayoutParams(dp(panelWidthDp), dp(panelHeightDp)))
    }

    private inner class BubbleView : View(this@DevKitOverlayService) {
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }

        private val heartPath = Path()
        private val bgDrawable: Drawable

        init {

            bgDrawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(bubbleSizeDp / 2).toFloat()
                colors = intArrayOf(cPrimary, cPrimaryContainer)
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                setStroke(dp(2), cPrimaryDark)
            }
            elevation = dp(8).toFloat()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            bgDrawable.setBounds(0, 0, w, h)
            val cx = w / 2f
            val cy = h / 2f
            val r = Math.min(w, h) * 0.18f
            heartPath.reset()
            heartPath.moveTo(cx, cy + r * 1.4f)
            heartPath.cubicTo(cx - r * 2.4f, cy + r * 0.2f, cx - r * 1.4f, cy - r * 1.4f, cx, cy - r * 0.4f)
            heartPath.cubicTo(cx + r * 1.4f, cy - r * 1.4f, cx + r * 2.4f, cy + r * 0.2f, cx, cy + r * 1.4f)
            heartPath.close()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            bgDrawable.draw(canvas)
            canvas.drawPath(heartPath, iconPaint)
        }
    }

    private inner class CloseButtonView : View(this@DevKitOverlayService) {
        private val xPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = cOnSurface
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
            strokeCap = Paint.Cap.ROUND
        }
        private val bgDrawable: Drawable

        init {
            bgDrawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(cSurfaceHigh)
                setStroke(dp(1), cOutline)
            }
            elevation = dp(4).toFloat()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            bgDrawable.setBounds(0, 0, w, h)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            bgDrawable.draw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val r = Math.min(width, height) * 0.25f
            canvas.drawLine(cx - r, cy - r, cx + r, cy + r, xPaint)
            canvas.drawLine(cx + r, cy - r, cx - r, cy + r, xPaint)
        }
    }

    private fun colorize(lines: List<String>): CharSequence {
        val sb = SpannableStringBuilder()
        lines.forEach { line ->
            val start = sb.length
            sb.append(line).append('\n')
            val color = when {
                line.contains("Error", true) || line.contains("Fatal", true) || line.contains("Exception") || line.contains("Failed", true) || line.contains("Falha", true) || line.contains("✖") -> "#FFFF7B72"
                line.contains("Warn", true) || line.contains("Aviso", true) -> "#FFE3B341"
                else -> null
            }
            if (color != null) sb.setSpan(ForegroundColorSpan(Color.parseColor(color)), start, sb.length, 0)
        }
        return sb
    }

    private fun bepinexLog(): File? {
        val dir = modCache.bepinexDir()
        return listOf(File(dir, "LogOutput.log"), File(dir, "logs/LogOutput.log"), File(dir, "BepInEx/LogOutput.log"))
            .firstOrNull { it.isFile }
    }

    private fun tailLog(max: Int): List<String> =
        runCatching { bepinexLog()?.readLines()?.takeLast(max) }.getOrNull().orEmpty()

    private fun modsLines(): List<String> {
        val out = ArrayList<String>()
        out += "Loader: etapa=${com.regis.boot.Boot.stage} ${com.regis.boot.Boot.message}"
        val dotnet = modCache.dotnetDir()
        out += "dotnet: " + if (File(dotnet, "System.Private.CoreLib.dll").isFile) "ok" else "Error: ausente"
        out += "libcoreclr: " + describeCoreclr(dotnet)
        out += "BepInEx core: " + if (File(modCache.bepinexDir(), "core/BepInEx.Core.dll").isFile) "ok" else "Error: ausente"
        val plugins = modCache.bepinexPluginsDir().walkTopDown().filter { it.isFile && it.extension.equals("dll", true) }.toList()
        out += "Plugins em disco: ${plugins.size}"
        plugins.forEach { out += "  • ${it.name} (${it.length() / 1024} KB)" }
        val log = tailLog(400)
        val loaded = log.filter { it.contains("Loading [") }
        out += "Carregados pelo BepInEx: ${loaded.size}"
        loaded.takeLast(15).forEach { out += "  ✓ " + it.substringAfter("Loading ") }
        val errors = log.filter { it.contains("[Error") || it.contains("[Fatal") || it.contains("Exception") }
        out += "Erros: ${errors.size}"
        errors.takeLast(12).forEach { out += "  ✖ $it" }
        if (log.isEmpty()) out += "Aviso: LogOutput.log ainda não existe"
        com.regis.boot.Boot.lines().takeLast(14).forEach { out += "boot: $it" }
        runCatching {
            val dir = modCache.bepinexDir()
            dir.listFiles { f -> f.isFile && f.name.startsWith("preloader") && f.name.endsWith(".log") }
                ?.maxByOrNull { it.lastModified() }
                ?.readLines()?.takeLast(12)?.forEach { out += "pre: $it" }
            com.regis.boot.Boot.previousLog(dir).takeLast(30).forEach { out += "ant: $it" }
        }
        if (android.os.Build.VERSION.SDK_INT >= 30) runCatching {
            val am = getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.getHistoricalProcessExitReasons(packageName, 0, 3).forEach {
                out += "saida: ${it.processName} reason=${it.reason} status=${it.status} ${it.description ?: ""}"
            }
        }
        return out
    }

    private fun describeCoreclr(dotnet: File): String {

        if (File(dotnet, "libcoreclr.so").isFile) return "ok (dotnet)"

        if (File(applicationInfo.nativeLibraryDir, "libcoreclr.so").isFile) return "ok (nativelib)"

        val classLoader = (javaClass.classLoader as? dalvik.system.BaseDexClassLoader)
        val path = classLoader?.findLibrary("coreclr")
        if (path != null) return "ok ($path)"
        return "Error: não encontrada"
    }

    private fun logsLines(): List<String> {
        val log = tailLog(80)
        if (log.isNotEmpty()) return log
        return console.entries.value.takeLast(60).map { "${console.formatTimestamp(it)} ${it.level.name.first()} ${it.tag}: ${it.message}" }
    }

    private fun devLines(): List<String> {
        val snap = latestSnapshot
        val out = ArrayList<String>()
        out += "Catálogo: ${devKitStore.catalogStatus()}"
        if (snap == null || snap.values.isEmpty()) {
            out += "Nenhuma variável ativa."
        } else {
            snap.values.take(12).forEach {
                out += if (it.error != null) "${it.name}: Error ${it.error}" else "${it.name} [${it.address}] = ${it.rawHex}"
            }
        }
        return out
    }

    private fun refreshBody() {
        val body = bodyView ?: return
        val lines = when (tab) {
            0 -> modsLines()
            1 -> logsLines()
            else -> devLines()
        }
        body.text = colorize(lines)
    }

    private fun copyLogs() {
        val text = (modsLines() + logsLines()).joinToString("\n")
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("larp-logs", text))
        Toast.makeText(this, "Logs copiados", Toast.LENGTH_SHORT).show()
    }

    private fun quitGame() {
        com.larp.launcher.feature.launcher.GameActivity.finishGame()
        handler.postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 400)
    }

    private fun restartGame(noMods: Boolean?) {
        val target = com.larp.launcher.feature.launcher.GameActivity.relaunchIntent(noMods)
        if (target == null) {
            Toast.makeText(this, "Jogo não encontrado", Toast.LENGTH_SHORT).show()
            return
        }
        val broadcast = Intent(GameRelaunchReceiver.ACTION).apply {
            setClassName(packageName, GameRelaunchReceiver::class.java.name)
            putExtra(GameRelaunchReceiver.EXTRA_TARGET, target)
        }
        sendBroadcast(broadcast)
        handler.postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 300)
    }

    private fun updateOverlay(snapshot: DevKitSnapshot) {
        if (expanded && tab != 3) refreshBody()
        updateStatusDot()
    }

    private fun openInApp() {
        val snapshotId = latestSnapshot?.id ?: UUID.randomUUID().toString()
        val uri = android.net.Uri.parse("com.larp.launcher://devkit?snapshot=$snapshotId")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            setPackage(packageName)
        }
        runCatching { startActivity(intent) }
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Dev Kit", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Overlay do Dev Kit ativo"
            }
            nm.createNotificationChannel(channel)
        }
        val openIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(MainActivity.EXTRA_DEVKIT, true)
        }
        val openPi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = Intent(this, DevKitOverlayService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPi = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Larp Dev Kit")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Parar", stopPi)
            .setOngoing(true)
            .build()
    }

    private inner class DragHandler(
        private val windowManager: WindowManager,
        private val view: View,
        private val params: WindowManager.LayoutParams,
        private val onTap: (() -> Unit)?
    ) : View.OnTouchListener {
        private var initialX = 0
        private var initialY = 0
        private var initialTouchX = 0f
        private var initialTouchY = 0f
        private var moved = false

        override fun onTouch(v: View?, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    moved = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 8 || Math.abs(dy) > 8) moved = true
                    params.x = initialX + dx
                    params.y = initialY + dy
                    runCatching { windowManager.updateViewLayout(view, params) }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) onTap?.invoke()
                    return true
                }
            }
            return false
        }
    }

    companion object {
        private const val TAG = "LarpDevKitOverlay"
        private const val CHANNEL_ID = "larp_devkit"
        private const val NOTIF_ID = 0xD0
        private const val REFRESH_INTERVAL_MS = 1000L
        private const val ACTION_REFRESH = "com.larp.launcher.devkit.REFRESH"
        private const val ACTION_EXTRACT = "com.larp.launcher.devkit.EXTRACT"
        private const val ACTION_STOP = "com.larp.launcher.devkit.STOP"
        private val KEY_ENABLED = booleanPreferencesKey("devkit_enabled")

        suspend fun setEnabled(context: Context, enabled: Boolean) {
            context.devkitPrefs.edit { it[KEY_ENABLED] = enabled }
        }

        fun isDevKitEnabled(context: Context): Boolean = runBlocking {
            context.devkitPrefs.data.first()[KEY_ENABLED] ?: false
        }
    }
}
