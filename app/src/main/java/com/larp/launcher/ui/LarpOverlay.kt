package com.larp.launcher.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

object LarpOverlay {

    private const val TAG = "LarpUi"
    private const val SLIDER_STEPS = 1000

    private val ui = Handler(Looper.getMainLooper())
    private val layers = HashMap<String, Layer>()
    private val pending = ArrayList<Runnable>()
    private val frames = ConcurrentHashMap<String, Frame>()
    private var activity: Activity? = null

    private class Frame(val layer: String, val node: String, val data: IntArray, val texts: String)

    private class Node(val id: String, val layer: Layer, val type: String, val view: View) {
        var x = 0f
        var y = 0f
        var w = -2f
        var h = -2f
        var weight = 0f
        var margin = floatArrayOf(0f, 0f, 0f, 0f)
        var gravity = -1
        var bg = 0
        var hasBg = false
        var radius = 0f
        var stroke = 0f
        var strokeColor = Color.TRANSPARENT
        var muted = false
        var min = 0f
        var max = 100f
        var drag = false
        var touch = false
        var click = false
        var dragDx = 0f
        var dragDy = 0f
        var downX = 0f
        var downY = 0f
        var moved = false
    }

    private class Layer(val id: String, val root: FrameLayout) {
        val nodes = HashMap<String, Node>()
        var system = false
        var params: WindowManager.LayoutParams? = null
        var touchable = true
    }

    fun attach(a: Activity) {
        activity = a
        ui.post {
            val queued = ArrayList(pending)
            pending.clear()
            queued.forEach { run(it) }
        }
    }

    fun detach() {
        ui.post {
            layers.values.toList().forEach { destroyLayer(it) }
            layers.clear()
            activity = null
        }
    }

    fun send(json: String) {
        post(Runnable {
            try {
                exec(JSONObject(json))
            } catch (t: Throwable) {
                Log.w(TAG, "comando falhou: ${t.message} :: $json")
            }
        })
    }

    fun draw(layer: String, node: String, data: IntArray, texts: String) {
        val key = "$layer/$node"
        val previous = frames.put(key, Frame(layer, node, data, texts))
        if (previous != null) return
        ui.post {
            val frame = frames.remove(key) ?: return@post
            val view = layers[frame.layer]?.nodes?.get(frame.node)?.view as? LarpCanvas
            view?.submit(frame.data, frame.texts)
        }
    }

    private fun run(r: Runnable) {
        try {
            r.run()
        } catch (t: Throwable) {
            Log.w(TAG, "falha: ${t.message}", t)
        }
    }

    private fun post(r: Runnable) {
        ui.post {
            if (activity == null) pending.add(r) else run(r)
        }
    }

    private fun emit(layer: String, node: String, event: String, value: String) {
        LarpNative.emit(layer, node, event, value)
    }

    private fun exec(o: JSONObject) {
        val p = o.optJSONObject("p") ?: JSONObject()
        when (o.getString("op")) {
            "layer" -> createLayer(o.getString("layer"), p)
            "add" -> addNode(o.getString("layer"), o.getString("id"), o.getString("type"), o.optString("parent", ""), p)
            "set" -> {
                val n = layers[o.getString("layer")]?.nodes?.get(o.getString("id")) ?: return
                applyProps(n, p)
            }
            "remove" -> removeNode(o.getString("layer"), o.getString("id"))
            "clear" -> clearNode(o.getString("layer"), o.optString("id", ""))
            "destroy" -> layers.remove(o.getString("layer"))?.let { destroyLayer(it) }
            "call" -> callNode(o.getString("layer"), o.getString("id"), o.getString("method"), p)
            "toast" -> activity?.let { Toast.makeText(it.baseContext, o.optString("text"), Toast.LENGTH_SHORT).show() }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createLayer(id: String, p: JSONObject) {
        val a = activity ?: return
        layers.remove(id)?.let { destroyLayer(it) }
        val root = FrameLayout(a)
        val layer = Layer(id, root)
        layer.touchable = p.optBoolean("touch", true)
        val wantsSystem = p.optString("mode", "activity") == "system"
        if (wantsSystem && Settings.canDrawOverlays(a)) {
            val wm = a.baseContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            if (!layer.touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            val lp = WindowManager.LayoutParams(
                dimension(p, "w", WindowManager.LayoutParams.WRAP_CONTENT),
                dimension(p, "h", WindowManager.LayoutParams.WRAP_CONTENT),
                type, flags, PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP or Gravity.START
            lp.x = px(p.optDouble("x", 0.0).toFloat())
            lp.y = px(p.optDouble("y", 0.0).toFloat())
            layer.system = true
            layer.params = lp
            wm.addView(root, lp)
        } else {
            root.isClickable = false
            root.isFocusable = false
            a.addContentView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            if (wantsSystem) emit(id, "", "layer_fallback", "activity")
        }
        layers[id] = layer
        if (p.has("visible")) root.visibility = if (p.optBoolean("visible")) View.VISIBLE else View.GONE
        if (p.has("alpha")) root.alpha = p.optDouble("alpha", 1.0).toFloat()
    }

    private fun destroyLayer(layer: Layer) {
        runCatching {
            if (layer.system) {
                val wm = layer.root.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.removeViewImmediate(layer.root)
            } else {
                (layer.root.parent as? ViewGroup)?.removeView(layer.root)
            }
        }
        layer.nodes.clear()
    }

    private fun dimension(p: JSONObject, key: String, def: Int): Int {
        if (!p.has(key)) return def
        val v = p.opt(key)
        if (v is String) return when (v) {
            "match" -> ViewGroup.LayoutParams.MATCH_PARENT
            "wrap" -> ViewGroup.LayoutParams.WRAP_CONTENT
            else -> def
        }
        return px((v as Number).toFloat())
    }

    private fun density(): Float = activity?.resources?.displayMetrics?.density ?: 1f

    private fun px(dp: Float): Int = (dp * density()).toInt()

    private fun create(a: Activity, type: String): View = when (type) {
        "frame" -> FrameLayout(a)
        "column" -> LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        "row" -> LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        "scroll" -> ScrollView(a)
        "hscroll" -> HorizontalScrollView(a)
        "text" -> TextView(a).apply { setTextColor(Color.WHITE) }
        "button" -> Button(a).apply { isAllCaps = false; setTextColor(Color.WHITE) }
        "toggle" -> Switch(a).apply { setTextColor(Color.WHITE) }
        "checkbox" -> CheckBox(a).apply { setTextColor(Color.WHITE) }
        "slider" -> SeekBar(a).apply { max = SLIDER_STEPS }
        "input" -> EditText(a).apply { setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); setSingleLine(true) }
        "progress" -> ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply { max = SLIDER_STEPS }
        "image" -> ImageView(a)
        "canvas" -> LarpCanvas(a)
        "spinner" -> Spinner(a)
        else -> View(a)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addNode(layerId: String, id: String, type: String, parentId: String, p: JSONObject) {
        val a = activity ?: return
        val layer = layers[layerId] ?: return
        layer.nodes[id]?.let { removeNode(layerId, id) }
        val view = create(a, type)
        val node = Node(id, layer, type, view)
        val parent: ViewGroup = if (parentId.isEmpty()) layer.root else (layer.nodes[parentId]?.view as? ViewGroup ?: layer.root)
        layer.nodes[id] = node
        wire(node)
        applyProps(node, p, false)
        parent.addView(view, layoutFor(node, parent))
        if (type == "canvas") {
            node.touch = p.optBoolean("touch", false)
        }
    }

    private fun wire(n: Node) {
        val v = n.view
        val layerId = n.layer.id
        when (v) {
            is CheckBox -> v.setOnCheckedChangeListener { _, c -> if (!n.muted) emit(layerId, n.id, "change", if (c) "1" else "0") }
            is Switch -> v.setOnCheckedChangeListener { _, c -> if (!n.muted) emit(layerId, n.id, "change", if (c) "1" else "0") }
            is SeekBar -> v.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (n.muted || !fromUser) return
                    emit(layerId, n.id, "change", (n.min + (n.max - n.min) * progress / SLIDER_STEPS).toString())
                }
                override fun onStartTrackingTouch(s: SeekBar?) = emit(layerId, n.id, "slide_start", "")
                override fun onStopTrackingTouch(s: SeekBar?) = emit(layerId, n.id, "slide_end", "")
            })
            is EditText -> v.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (!n.muted) emit(layerId, n.id, "text", s?.toString() ?: "")
                }
            })
            is Spinner -> v.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    if (!n.muted) emit(layerId, n.id, "select", pos.toString())
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        if (n.type == "button") v.setOnClickListener { emit(layerId, n.id, "click", "") }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun refreshTouch(n: Node) {
        val v = n.view
        if (n.click && n.type != "button") {
            v.setOnClickListener { emit(n.layer.id, n.id, "click", "") }
        }
        if (!n.drag && !n.touch) {
            if (n.type != "slider" && n.type != "input") v.setOnTouchListener(null)
            return
        }
        v.setOnTouchListener { view, ev ->
            if (n.touch) emit(n.layer.id, n.id, "touch", "${ev.actionMasked},${ev.rawX},${ev.rawY},${ev.x},${ev.y}")
            if (n.drag) {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        n.dragDx = ev.rawX - n.x * density()
                        n.dragDy = ev.rawY - n.y * density()
                        n.downX = ev.rawX
                        n.downY = ev.rawY
                        n.moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!n.moved && Math.hypot((ev.rawX - n.downX).toDouble(), (ev.rawY - n.downY).toDouble()) > 12 * density()) n.moved = true
                        if (n.moved) {
                            n.x = (ev.rawX - n.dragDx) / density()
                            n.y = (ev.rawY - n.dragDy) / density()
                            relayout(n)
                            emit(n.layer.id, n.id, "drag", "${n.x},${n.y}")
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!n.moved && n.click) emit(n.layer.id, n.id, "click", "")
                    }
                }
                true
            } else {
                n.touch && n.type != "button"
            }
        }
    }

    private fun layoutFor(n: Node, parent: ViewGroup): ViewGroup.LayoutParams {
        val w = if (n.w <= -1f) n.w.toInt() else px(n.w)
        val h = if (n.h <= -1f) n.h.toInt() else px(n.h)
        return if (parent is LinearLayout) {
            LinearLayout.LayoutParams(w, h, n.weight).also {
                it.setMargins(px(n.margin[0]), px(n.margin[1]), px(n.margin[2]), px(n.margin[3]))
                if (n.gravity >= 0) it.gravity = n.gravity
            }
        } else {
            FrameLayout.LayoutParams(w, h).also {
                it.leftMargin = px(n.x + n.margin[0])
                it.topMargin = px(n.y + n.margin[1])
                it.rightMargin = px(n.margin[2])
                it.bottomMargin = px(n.margin[3])
                if (n.gravity >= 0) it.gravity = n.gravity
            }
        }
    }

    private fun relayout(n: Node) {
        val parent = n.view.parent as? ViewGroup ?: return
        n.view.layoutParams = layoutFor(n, parent)
    }

    private fun color(v: Any?): Int = when (v) {
        is Number -> v.toInt()
        is String -> runCatching { Color.parseColor(v) }.getOrDefault(Color.WHITE)
        else -> Color.WHITE
    }

    private fun restyle(n: Node) {
        if (!n.hasBg) return
        val d = GradientDrawable()
        d.setColor(n.bg)
        d.cornerRadius = n.radius * density()
        if (n.stroke > 0f) d.setStroke(px(n.stroke), n.strokeColor)
        n.view.background = d
    }

    private fun applyProps(n: Node, p: JSONObject, relayoutAfter: Boolean = true) {
        val v = n.view
        n.muted = true
        var layoutDirty = false
        var styleDirty = false
        val keys = p.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val value = p.get(k)
            when (k) {
                "x" -> { n.x = (value as Number).toFloat(); layoutDirty = true }
                "y" -> { n.y = (value as Number).toFloat(); layoutDirty = true }
                "w" -> { n.w = sizeOf(value); layoutDirty = true }
                "h" -> { n.h = sizeOf(value); layoutDirty = true }
                "weight" -> { n.weight = (value as Number).toFloat(); layoutDirty = true }
                "margin" -> {
                    val m = (value as Number).toFloat()
                    n.margin = floatArrayOf(m, m, m, m)
                    layoutDirty = true
                }
                "marginLTRB" -> {
                    val a = value as JSONArray
                    n.margin = FloatArray(4) { a.optDouble(it, 0.0).toFloat() }
                    layoutDirty = true
                }
                "gravity" -> { n.gravity = gravityOf(value.toString()); layoutDirty = true; if (v is TextView) v.gravity = n.gravity }
                "padding" -> {
                    val d = px((value as Number).toFloat())
                    v.setPadding(d, d, d, d)
                }
                "paddingLTRB" -> {
                    val a = value as JSONArray
                    v.setPadding(px(a.optDouble(0, 0.0).toFloat()), px(a.optDouble(1, 0.0).toFloat()), px(a.optDouble(2, 0.0).toFloat()), px(a.optDouble(3, 0.0).toFloat()))
                }
                "bg" -> { n.bg = color(value); n.hasBg = true; styleDirty = true }
                "radius" -> { n.radius = (value as Number).toFloat(); styleDirty = true }
                "stroke" -> { n.stroke = (value as Number).toFloat(); styleDirty = true }
                "strokeColor" -> { n.strokeColor = color(value); styleDirty = true }
                "alpha" -> v.alpha = (value as Number).toFloat()
                "visible" -> v.visibility = if (value == true) View.VISIBLE else if (value == "invisible") View.INVISIBLE else View.GONE
                "rotation" -> v.rotation = (value as Number).toFloat()
                "scale" -> { v.scaleX = (value as Number).toFloat(); v.scaleY = v.scaleX }
                "z" -> v.elevation = px((value as Number).toFloat()).toFloat()
                "enabled" -> v.isEnabled = value == true
                "orientation" -> (v as? LinearLayout)?.orientation = if (value == "h") LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                "drag" -> { n.drag = value == true; refreshTouch(n) }
                "touch" -> { n.touch = value == true; refreshTouch(n) }
                "click" -> { n.click = value == true; refreshTouch(n) }
                "text" -> {
                    if (v is EditText) {
                        if (v.text.toString() != value.toString()) v.setText(value.toString())
                    } else (v as? TextView)?.text = value.toString()
                }
                "hint" -> (v as? EditText)?.hint = value.toString()
                "number" -> (v as? EditText)?.inputType = if (value == true) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED else InputType.TYPE_CLASS_TEXT
                "color" -> (v as? TextView)?.setTextColor(color(value))
                "size" -> (v as? TextView)?.textSize = (value as Number).toFloat()
                "bold" -> (v as? TextView)?.setTypeface(null, if (value == true) Typeface.BOLD else Typeface.NORMAL)
                "checked" -> {
                    (v as? android.widget.CompoundButton)?.isChecked = value == true
                }
                "min" -> n.min = (value as Number).toFloat()
                "max" -> n.max = (value as Number).toFloat()
                "value" -> {
                    val f = (value as Number).toFloat()
                    val steps = if (n.max > n.min) ((f - n.min) / (n.max - n.min) * SLIDER_STEPS).toInt().coerceIn(0, SLIDER_STEPS) else 0
                    when (v) {
                        is SeekBar -> v.progress = steps
                        is ProgressBar -> v.progress = steps
                    }
                }
                "tint" -> (v as? ProgressBar)?.progressTintList = android.content.res.ColorStateList.valueOf(color(value))
                "items" -> if (v is Spinner) {
                    val a = value as JSONArray
                    val list = List(a.length()) { a.optString(it) }
                    v.adapter = ArrayAdapter(v.context, android.R.layout.simple_spinner_dropdown_item, list)
                }
                "selected" -> (v as? Spinner)?.setSelection((value as Number).toInt())
                "image" -> if (v is ImageView) {
                    val bytes = Base64.decode(value.toString(), Base64.DEFAULT)
                    v.setImageBitmap(android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                }
                "scaleType" -> (v as? ImageView)?.scaleType = runCatching { ImageView.ScaleType.valueOf(value.toString().uppercase()) }.getOrDefault(ImageView.ScaleType.FIT_CENTER)
                "mode", "layer" -> {}
                "layerX", "layerY", "layerW", "layerH" -> updateSystemLayer(n.layer, k, (value as Number).toFloat())
            }
        }
        if (styleDirty) restyle(n)
        if (layoutDirty && relayoutAfter) relayout(n)
        n.muted = false
    }

    private fun updateSystemLayer(layer: Layer, key: String, value: Float) {
        val lp = layer.params ?: return
        when (key) {
            "layerX" -> lp.x = px(value)
            "layerY" -> lp.y = px(value)
            "layerW" -> lp.width = px(value)
            "layerH" -> lp.height = px(value)
        }
        runCatching {
            val wm = layer.root.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.updateViewLayout(layer.root, lp)
        }
    }

    private fun sizeOf(v: Any): Float = when (v) {
        is Number -> v.toFloat()
        "match" -> -1f
        else -> -2f
    }

    private fun gravityOf(s: String): Int {
        var g = 0
        for (part in s.split('|')) {
            g = g or when (part.trim()) {
                "center" -> Gravity.CENTER
                "left" -> Gravity.START
                "right" -> Gravity.END
                "top" -> Gravity.TOP
                "bottom" -> Gravity.BOTTOM
                "hcenter" -> Gravity.CENTER_HORIZONTAL
                "vcenter" -> Gravity.CENTER_VERTICAL
                else -> 0
            }
        }
        return g
    }

    private fun removeNode(layerId: String, id: String) {
        val layer = layers[layerId] ?: return
        val node = layer.nodes.remove(id) ?: return
        forget(layer, node.view)
        (node.view.parent as? ViewGroup)?.removeView(node.view)
    }

    private fun forget(layer: Layer, view: View) {
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) forget(layer, view.getChildAt(i))
        }
        val it = layer.nodes.entries.iterator()
        while (it.hasNext()) if (it.next().value.view === view) it.remove()
    }

    private fun clearNode(layerId: String, id: String) {
        val layer = layers[layerId] ?: return
        val group = if (id.isEmpty()) layer.root else layer.nodes[id]?.view as? ViewGroup ?: return
        for (i in 0 until group.childCount) forget(layer, group.getChildAt(i))
        group.removeAllViews()
    }

    private fun callNode(layerId: String, id: String, method: String, p: JSONObject) {
        val layer = layers[layerId] ?: return
        val view = if (id.isEmpty()) layer.root else layer.nodes[id]?.view ?: return
        when (method) {
            "front" -> view.bringToFront()
            "focus" -> view.requestFocus()
            "hideKeyboard" -> {
                val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(view.windowToken, 0)
            }
            "scrollTo" -> (view as? ScrollView)?.smoothScrollTo(0, px(p.optDouble("y", 0.0).toFloat()))
            "invalidate" -> view.invalidate()
            "animate" -> {
                val a = view.animate().setDuration(p.optLong("duration", 250))
                if (p.has("alpha")) a.alpha(p.getDouble("alpha").toFloat())
                if (p.has("x")) a.translationX(px(p.getDouble("x").toFloat()).toFloat())
                if (p.has("y")) a.translationY(px(p.getDouble("y").toFloat()).toFloat())
                if (p.has("scale")) a.scaleX(p.getDouble("scale").toFloat()).scaleY(p.getDouble("scale").toFloat())
                if (p.has("rotation")) a.rotation(p.getDouble("rotation").toFloat())
                a.start()
            }
        }
    }
}
