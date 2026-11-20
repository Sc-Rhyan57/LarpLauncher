package com.larp.launcher.feature.devkit

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import android.content.ClipData
import android.content.ClipboardManager
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import java.io.File
import android.widget.TextView
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        handler = Handler(Looper.getMainLooper())
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        startForegroundCompat()
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

    private fun label(text: String, color: String, size: Float = 11f, onClick: (() -> Unit)? = null): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor(color))
            textSize = size
            setPadding(dp(8), dp(6), dp(8), dp(6))
            if (onClick != null) setOnClickListener { onClick() }
        }

    private fun render() {
        val root = overlayView as? FrameLayout ?: return
        val params = layoutParams ?: return
        root.removeAllViews()
        if (!expanded) {
            val bubble = TextView(this).apply {
                text = "L"
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setBackgroundColor(Color.parseColor("#CC5B3FD1"))
                setOnTouchListener(DragHandler(windowManager, root, params) { expanded = true; render() })
            }
            root.addView(bubble, FrameLayout.LayoutParams(dp(40), dp(40)))
            runCatching { windowManager.updateViewLayout(root, params) }
            return
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F00D1117"))
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = label("Larp Dev Menu", "#FFFFFF", 12f).apply { typeface = Typeface.DEFAULT_BOLD }
        title.setOnTouchListener(DragHandler(windowManager, root, params, null))
        header.addView(title, LinearLayout.LayoutParams(0, WindowManager.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(label("—", "#FFA0A0A0", 14f) { expanded = false; render() })
        panel.addView(header)
        val names = listOf("Mods", "Logs", "Dev", "Ações")
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        names.forEachIndexed { i, n ->
            tabs.addView(label(n, if (i == tab) "#FF58A6FF" else "#FFA0A0A0") { tab = i; render() })
        }
        panel.addView(tabs)
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
            actions.forEach { (n, f) -> col.addView(label(n, "#FFFFA657", 12f) { f() }) }
            panel.addView(ScrollView(this).apply { addView(col) }, LinearLayout.LayoutParams(dp(300), dp(220)))
            bodyView = null
        } else {
            val body = TextView(this).apply {
                setTextColor(Color.parseColor("#FFD0D0D0"))
                textSize = 10f
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
            bodyView = body
            panel.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(dp(300), dp(220)))
            refreshBody()
        }
        root.addView(panel)
        runCatching { windowManager.updateViewLayout(root, params) }
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
        out += "Loader: etapa=${com.larp.launcher.boot.Boot.stage} ${com.larp.launcher.boot.Boot.message}"
        val dotnet = modCache.dotnetDir()
        out += "libfusion: " + if (File(filesDir, "native/libfusion.so").isFile) "ok" else "Error: ausente"
        out += "dotnet: " + if (dotnet.walkTopDown().any { it.name == "System.Private.CoreLib.dll" }) "ok" else "Error: ausente"
        out += "libcoreclr: " + if (dotnet.walkTopDown().any { it.name == "libcoreclr.so" } || File(filesDir, "native/libcoreclr.so").isFile) "ok" else "Error: não encontrada"
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
        com.larp.launcher.boot.Boot.lines.takeLast(8).forEach { out += "boot: $it" }
        return out
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

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

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
