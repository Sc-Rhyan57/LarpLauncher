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
import android.widget.LinearLayout
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
    @Inject lateinit var json: Json

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
        } else if (intent?.action == ACTION_STOP) {
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        refreshJob?.cancel()
        runCatching { overlayView?.let { windowManager.removeView(it) } }
        overlayView = null
        scope.cancel()
        super.onDestroy()
    }

    private fun loadVariablesAndShowOverlay() {
        scope.launch {
            variables = devKitStore.loadVariables()
            handler.post { showOverlay() }
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 16
        params.y = 120

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#EE0D1117"))
            setPadding(12, 12, 12, 12)
        }
        val title = TextView(this).apply {
            text = "Dev Kit"
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 4)
        }
        val body = TextView(this).apply {
            text = "Carregando…"
            setTextColor(Color.parseColor("#FFA0A0A0"))
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, 4)
            setTextIsSelectable(true)
        }
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val openBtn = TextView(this).apply {
            text = "Abrir no app"
            setTextColor(Color.parseColor("#FF58A6FF"))
            textSize = 10f
            setPadding(8, 4, 8, 4)
            setOnClickListener { openInApp() }
        }
        val refreshBtn = TextView(this).apply {
            text = "Atualizar"
            setTextColor(Color.parseColor("#FF7EE787"))
            textSize = 10f
            setPadding(8, 4, 8, 4)
            setOnClickListener {
                scope.launch { refreshOnce() }
            }
        }
        buttonRow.addView(refreshBtn)
        buttonRow.addView(openBtn)

        container.addView(title)
        container.addView(body)
        container.addView(buttonRow)

        params.width = dp(220)
        params.height = WindowManager.LayoutParams.WRAP_CONTENT

        container.setOnTouchListener(DragHandler(windowManager, container, params))

        runCatching {
            windowManager.addView(container, params)
            overlayView = container
        }.onFailure { Log.e(TAG, "Falha ao adicionar overlay: ${it.message}") }
    }

    private fun updateOverlay(snapshot: DevKitSnapshot) {
        val view = overlayView as? LinearLayout ?: return
        val body = view.getChildAt(1) as? TextView ?: return
        if (snapshot.values.isEmpty()) {
            body.text = "Nenhuma variável.\nAdicione em Dev Kit no app."
            return
        }
        val sb = StringBuilder()
        snapshot.values.take(8).forEach { v ->
            val line = if (v.error != null) {
                "${v.name}: ${v.error}"
            } else {
                "${v.name} [${v.address}]\n  = ${v.rawHex}"
            }
            sb.append(line).append('\n')
        }
        body.text = sb.toString().trimEnd()
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
        private val params: WindowManager.LayoutParams
    ) : View.OnTouchListener {
        private var initialX = 0
        private var initialY = 0
        private var initialTouchX = 0f
        private var initialTouchY = 0f

        override fun onTouch(v: View?, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    runCatching { windowManager.updateViewLayout(view, params) }
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
