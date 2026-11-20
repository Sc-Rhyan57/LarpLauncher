package com.larp.launcher.feature.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.larp.launcher.MainActivity
import com.larp.launcher.R

class FloatingVoiceBubbleService : Service() {

    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private var muted = false
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startBubble()
            ACTION_STOP -> stopBubble()
            ACTION_TOGGLE_MUTE -> {
                muted = !muted
                refreshBubble()
            }
        }
        return START_STICKY
    }

    private fun startBubble() {
        if (bubbleView != null) return
        startForegroundWithNotification()

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 200
        }

        val view = buildBubbleView()
        view.setOnTouchListener(BubbleTouchListener(layoutParams))
        bubbleView = view
        windowManager?.addView(view, layoutParams)
    }

    private fun buildBubbleView(): View {
        val size = dp(64)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundDrawable(GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(colorForState())
            })
            setOnClickListener { sendSelfCommand(ACTION_TOGGLE_MUTE) }
        }
        val label = TextView(this).apply {
            text = if (muted) "OFF" else "ON"
            setTextColor(android.graphics.Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(4))
        }
        container.addView(label, LinearLayout.LayoutParams(size, size).apply { gravity = Gravity.CENTER })
        val params = LinearLayout.LayoutParams(size, size)
        container.layoutParams = params
        return container
    }

    private fun refreshBubble() {
        val view = bubbleView ?: return
        val container = view as? LinearLayout ?: return
        container.setBackgroundDrawable(GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(colorForState())
        })
        val label = container.getChildAt(0) as? TextView
        label?.text = if (muted) "OFF" else "ON"
    }

    private fun colorForState(): Int {
        return if (muted) 0xFFEF4444.toInt() else 0xFF3B6FE0.toInt()
    }

    private fun stopBubble() {
        bubbleView?.let { windowManager?.removeView(it) }
        bubbleView = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun sendSelfCommand(action: String) {
        startService(Intent(this, FloatingVoiceBubbleService::class.java).setAction(action))
    }

    private fun startForegroundWithNotification() {
        createChannel()
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingFlags = if (Build.VERSION.SDK_INT >= 23)
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        else
            android.app.PendingIntent.FLAG_UPDATE_CURRENT
        val openPending = android.app.PendingIntent.getActivity(this, 0, openIntent, pendingFlags)
        val notification: Notification = androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Larp em chamada")
            .setContentText("Toque para voltar — arraste a bolinha, toque para mutar")
            .setOngoing(true)
            .setContentIntent(openPending)
            .setCategory(Notification.CATEGORY_CALL)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Larp voz",
            NotificationManager.IMPORTANCE_HIGH
        ).apply { description = "Chat de voz ativo" }
        nm.createNotificationChannel(channel)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        stopBubble()
        super.onDestroy()
    }

    private inner class BubbleTouchListener(private val params: WindowManager.LayoutParams) : View.OnTouchListener {
        override fun onTouch(v: View, event: MotionEvent): Boolean {
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
                    windowManager?.updateViewLayout(bubbleView, params)
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = Math.abs(event.rawX - initialTouchX)
                    val dy = Math.abs(event.rawY - initialTouchY)
                    if (dx < 10 && dy < 10) {
                        v.performClick()
                    }
                    return true
                }
            }
            return false
        }
    }

    companion object {
        private const val CHANNEL_ID = "larp_voice_bubble"
        private const val NOTIFICATION_ID = 0x1b
        const val ACTION_START = "com.larp.launcher.action.BUBBLE_START"
        const val ACTION_STOP = "com.larp.launcher.action.BUBBLE_STOP"
        const val ACTION_TOGGLE_MUTE = "com.larp.launcher.action.BUBBLE_MUTE"

        fun show(context: Context) {
            val intent = Intent(context, FloatingVoiceBubbleService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun hide(context: Context) {
            context.startService(Intent(context, FloatingVoiceBubbleService::class.java).setAction(ACTION_STOP))
        }

        fun toggleMute(context: Context) {
            context.startService(Intent(context, FloatingVoiceBubbleService::class.java).setAction(ACTION_TOGGLE_MUTE))
        }
    }
}
