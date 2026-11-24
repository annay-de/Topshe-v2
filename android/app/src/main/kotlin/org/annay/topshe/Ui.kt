package org.annay.topshe

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/** Colours and type for the dark, liquid-metal look. */
object Look {
    const val BG = 0xFF09090B.toInt()            // near-black base
    const val TEXT = 0xFFF4F4F5.toInt()          // main text
    const val MUTED = 0xFFA1A1AA.toInt()         // secondary text
    const val FAINT = 0xFF71717A.toInt()         // log text
    const val GLASS = 0xA60B0B0F.toInt()         // card fill: dark glass (65%), keeps text readable
    const val EDGE = 0x24FFFFFF                  // card border
    const val ON = 0xFF4ADE80.toInt()            // listening
    const val PAUSED = 0xFFFBBF24.toInt()        // paused
    const val OFF = 0xFF71717A.toInt()           // off / not ready
    val semibold: Typeface = Typeface.create(Typeface.DEFAULT, 600, false)
    val black: Typeface = Typeface.create(Typeface.DEFAULT, 900, false)
}

/**
 * Animated liquid-metal background (AGSL, Android 13+). Ripples where you touch and drifts as you scroll.
 * Drawn at 1/[SCALE] resolution into a hardware layer and scaled up (smooth metal needs no detail),
 * so it costs little; it only animates while the screen is visible. Older Android: a still gradient.
 */
@SuppressLint("ViewConstructor")
class MetalBackground(ctx: Context) : View(ctx) {
    companion object {
        const val SCALE = 3f
        private const val SRC = """
uniform float2 iResolution;
uniform float iTime;
uniform float3 iTouch;   // x, y (pixels), strength 0..1
uniform float iScroll;

float hash(float2 p) {
    p = fract(p * float2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}
float noise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float a = hash(i);
    float b = hash(i + float2(1.0, 0.0));
    float c = hash(i + float2(0.0, 1.0));
    float d = hash(i + float2(1.0, 1.0));
    float2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}
float fbm(float2 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 4; i++) {
        v += a * noise(p);
        p = p * 2.03 + float2(1.7, 9.2);
        a *= 0.5;
    }
    return v;
}
float height(float2 p, float t) {
    float2 q = float2(fbm(p + float2(0.0, t * 0.06)), fbm(p + float2(5.2, 1.3) - float2(t * 0.05, 0.0)));
    return fbm(p + 2.2 * q + float2(t * 0.03, -t * 0.02));
}
half4 main(float2 fragCoord) {
    float2 uv = fragCoord / iResolution.y;
    float t = iTime;
    float2 p = uv * 1.05 + float2(0.0, iScroll * 0.0004);
    float2 tp = iTouch.xy / iResolution.y;
    float dist = distance(uv, tp);
    float2 dir = (uv - tp) / max(dist, 0.0001);
    p += dir * iTouch.z * 0.09 * exp(-dist * 3.5) * sin(dist * 22.0 - t * 5.0);
    float e = 0.02;
    float h = height(p, t);
    float hx = height(p + float2(e, 0.0), t);
    float hy = height(p + float2(0.0, e), t);
    float3 n = normalize(float3((h - hx) / e, (h - hy) / e, 2.4));
    float3 l = normalize(float3(-0.45, 0.65, 0.65));
    float diff = clamp(dot(n, l), 0.0, 1.0);
    float3 r = reflect(-l, n);
    float spec = 0.45 * pow(clamp(r.z, 0.0, 1.0), 14.0);
    float fres = pow(1.0 - n.z, 2.0);
    float3 base = mix(float3(0.03, 0.03, 0.037), float3(0.20, 0.21, 0.24), smoothstep(0.30, 0.80, h));
    float3 col = base * (0.35 + 0.65 * diff) + spec * float3(0.80, 0.84, 0.92) + fres * float3(0.10, 0.12, 0.16);
    col += 0.035 * float3(sin(h * 11.0 + t * 0.3), sin(h * 11.0 + 2.1 + t * 0.3), sin(h * 11.0 + 4.2 + t * 0.3));
    float2 c = fragCoord / iResolution - 0.5;
    float vig = 1.0 - smoothstep(0.15, 1.05, length(c * float2(1.0, 1.25)));
    col *= 0.38 + 0.62 * vig;
    return half4(half3(col), 1.0);
}
"""
    }

    private val paint = Paint()
    private var shader: Any? = null            // RuntimeShader (kept as Any so older Android never loads it)
    private val start = SystemClock.uptimeMillis()
    private var touchX = 0f
    private var touchY = 0f
    private var touchAmt = 0f
    private var lastTouch = 0L
    private var scroll = 0f
    private var running = false

    init {
        pivotX = 0f; pivotY = 0f
        scaleX = SCALE; scaleY = SCALE
        setLayerType(LAYER_TYPE_HARDWARE, null)   // render small, then scale the texture up
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                shader = RuntimeShader(SRC)
            } catch (e: Throwable) {
                Log.w("Alfred", "metal shader unavailable: ${e.message}")
            }
        }
        setBackgroundColor(Look.BG)
    }

    /** Called with the full-screen touch position (the view itself is 1/SCALE size). */
    fun touch(x: Float, y: Float) {
        touchX = x / SCALE; touchY = y / SCALE; touchAmt = 1f; lastTouch = SystemClock.uptimeMillis()
    }

    fun scrolled(y: Int) { scroll = y / SCALE }

    fun setRunning(on: Boolean) {
        running = on
        if (on) postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (Build.VERSION.SDK_INT >= 33 && shader != null) {
            val s = shader as RuntimeShader
            val t = (SystemClock.uptimeMillis() - start) / 1000f
            val since = (SystemClock.uptimeMillis() - lastTouch) / 1000f
            val amt = touchAmt * Math.max(0f, 1f - since / 2.5f)
            s.setFloatUniform("iResolution", w, h)
            s.setFloatUniform("iTime", t)
            s.setFloatUniform("iTouch", touchX, touchY, amt)
            s.setFloatUniform("iScroll", scroll)
            paint.shader = s
            canvas.drawRect(0f, 0f, w, h, paint)
            if (running) postInvalidateOnAnimation()
        } else {
            if (paint.shader == null) paint.shader = LinearGradient(0f, 0f, w * 0.4f, h,
                intArrayOf(0xFF1C1D22.toInt(), Look.BG, 0xFF16171C.toInt()), null, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, paint)
        }
    }
}

/** Small builders so the screen code stays readable. */
class Kit(private val ctx: Context) {
    fun dp(v: Number) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    fun rounded(fill: Int, radius: Int, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radius).toFloat()
        if (stroke > 0) setStroke(dp(stroke), strokeColor)
    }

    /** Big metallic ALFRED wordmark. */
    fun wordmark(text: String) = object : TextView(ctx) {
        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            super.onSizeChanged(w, h, ow, oh)
            paint.shader = LinearGradient(0f, 0f, w * 0.25f, h.toFloat(),
                intArrayOf(0xFFFFFFFF.toInt(), 0xFF9CA3AF.toInt(), 0xFFE5E7EB.toInt(), 0xFF6B7280.toInt(), 0xFFD1D5DB.toInt()),
                floatArrayOf(0f, 0.35f, 0.55f, 0.8f, 1f), Shader.TileMode.CLAMP)
        }
    }.apply {
        this.text = text; textSize = 64f; typeface = Look.black; letterSpacing = 0.08f
        setTextColor(Color.WHITE); includeFontPadding = false
    }

    fun text(t: String, size: Float = 14f, color: Int = Look.MUTED, face: Typeface = Typeface.DEFAULT) = TextView(ctx).apply {
        text = t; textSize = size; setTextColor(color); typeface = face; setLineSpacing(0f, 1.15f)
    }

    /** A glass card with an optional step number and title. */
    fun card(title: String?, step: String? = null): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Look.GLASS, 22, 1, Look.EDGE)
        setPadding(dp(18), dp(16), dp(18), dp(18))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) }
        if (title != null) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            if (step != null) row.addView(TextView(ctx).apply {
                text = step; textSize = 12f; typeface = Look.semibold; setTextColor(Look.TEXT); gravity = Gravity.CENTER
                background = rounded(0x1FFFFFFF, 12, 1, Look.EDGE)
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(10) }
            })
            row.addView(text(title, 17f, Look.TEXT, Look.semibold))
            addView(row)
            addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(1, dp(6)) })
        }
    }

    private fun pill(t: String, primary: Boolean, onClick: (View) -> Unit) = Button(ctx).apply {
        text = t; isAllCaps = false; textSize = 15f; typeface = Look.semibold; stateListAnimator = null
        setTextColor(if (primary) Look.BG else Look.TEXT)
        val shape = if (primary) rounded(0xFFF4F4F5.toInt(), 26) else rounded(0x0AFFFFFF, 26, 1, 0x40FFFFFF)
        background = RippleDrawable(ColorStateList.valueOf(if (primary) 0x33000000 else 0x33FFFFFF), shape, rounded(Color.WHITE, 26))
        minHeight = dp(50); minimumHeight = dp(50)
        setPadding(dp(18), dp(10), dp(18), dp(10))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
        setOnClickListener { onClick(it) }
    }

    fun primary(t: String, onClick: () -> Unit) = pill(t, true) { onClick() }
    fun button(t: String, onClick: () -> Unit) = pill(t, false) { onClick() }
    fun buttonV(t: String, onClick: (View) -> Unit) = pill(t, false, onClick)

    fun input(hintText: String, value: String, secret: Boolean = false) = EditText(ctx).apply {
        hint = hintText; setText(value); textSize = 15f
        setTextColor(Look.TEXT); setHintTextColor(0x80FFFFFF.toInt())
        background = rounded(0x14FFFFFF, 14, 1, Look.EDGE)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
        if (secret) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
    }

    fun slider(label: String, value: Float, lo: Float, hi: Float, save: (Float) -> Unit): View {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, 0) }
        val t = text("$label: %.2f".format(value), 14f, Look.TEXT)
        val bar = SeekBar(ctx).apply {
            max = 100; progress = (((value - lo) / (hi - lo)) * 100).toInt().coerceIn(0, 100)
            progressTintList = ColorStateList.valueOf(Look.TEXT)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = ColorStateList.valueOf(0x40FFFFFF)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = lo + (hi - lo) * p / 100f; t.text = "$label: %.2f".format(v); if (fromUser) save(v)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        box.addView(t); box.addView(bar)
        return box
    }

    /** Thin progress bar; [showProgress] drives it from a status line like "Brain: 120 / 2614 MB". */
    fun bar(): android.widget.ProgressBar =
        android.widget.ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(Look.TEXT)
            progressBackgroundTintList = ColorStateList.valueOf(0x33FFFFFF)
            indeterminateTintList = ColorStateList.valueOf(Look.TEXT)
            layoutParams = LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(8) }
            visibility = View.GONE
        }

    fun showProgress(bar: android.widget.ProgressBar, msg: String) {
        val m = Regex("(\\d+) / (\\d+) MB").find(msg)
        when {
            m != null -> {
                val done = m.groupValues[1].toLong()
                val total = m.groupValues[2].toLong().coerceAtLeast(1)
                bar.isIndeterminate = false
                bar.progress = (done * 100 / total).toInt().coerceIn(0, 100)
                bar.visibility = View.VISIBLE
            }
            Regex("(?i)queued|starting|checking|retrying|paused|continue|syncing").containsMatchIn(msg) -> {
                bar.isIndeterminate = true
                bar.visibility = View.VISIBLE
            }
            else -> bar.visibility = View.GONE
        }
    }

    /** Small rounded shortcut button. */
    fun chip(t: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = t; textSize = 13f; setTextColor(Look.TEXT); typeface = Look.semibold
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), rounded(0xB3141418.toInt(), 18, 1, Look.EDGE), rounded(Color.WHITE, 18))
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    /** Round icon button (send, mic). */
    fun round(label: String, primary: Boolean, onClick: () -> Unit) = TextView(ctx).apply {
        text = label; textSize = 18f; gravity = Gravity.CENTER; typeface = Look.semibold
        setTextColor(if (primary) Look.BG else Look.TEXT)
        val shape = GradientDrawable().apply { this.shape = GradientDrawable.OVAL; setColor(if (primary) 0xFFF4F4F5.toInt() else 0xB3141418.toInt()); if (!primary) setStroke(dp(1), Look.EDGE) }
        val mask = GradientDrawable().apply { this.shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
        background = RippleDrawable(ColorStateList.valueOf(if (primary) 0x33000000 else 0x33FFFFFF), shape, mask)
        layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply { leftMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    /** Round mic button with a drawn microphone (no emoji). */
    fun mic(onClick: () -> Unit): View {
        val icon = object : View(ctx) {
            private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = Look.TEXT; style = android.graphics.Paint.Style.STROKE; strokeWidth = dp(2).toFloat()
                strokeCap = android.graphics.Paint.Cap.ROUND
            }
            override fun onDraw(c: android.graphics.Canvas) {
                val cx = width / 2f; val cy = height / 2f; val u = width / 46f
                p.style = android.graphics.Paint.Style.FILL
                c.drawRoundRect(cx - 5 * u, cy - 12 * u, cx + 5 * u, cy + 3 * u, 5 * u, 5 * u, p)   // capsule
                p.style = android.graphics.Paint.Style.STROKE
                c.drawArc(cx - 9 * u, cy - 7 * u, cx + 9 * u, cy + 7 * u, 0f, 180f, false, p)       // cradle
                c.drawLine(cx, cy + 7 * u, cx, cy + 12 * u, p)                                      // stem
                c.drawLine(cx - 5 * u, cy + 12 * u, cx + 5 * u, cy + 12 * u, p)                     // base
            }
        }
        val shape = GradientDrawable().apply { this.shape = GradientDrawable.OVAL; setColor(0xB3141418.toInt()); setStroke(dp(1), Look.EDGE) }
        val mask = GradientDrawable().apply { this.shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
        icon.background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape, mask)
        icon.layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply { leftMargin = dp(8) }
        icon.contentDescription = "Talk"
        icon.setOnClickListener { onClick() }
        return icon
    }

    /** A chat bubble: yours on the right (light), Alfred's on the left (dark glass). */
    fun bubble(fromYou: Boolean, text: String, voice: Boolean): View {
        val maxW = (ctx.resources.displayMetrics.widthPixels * 0.78).toInt()
        val t = TextView(ctx).apply {
            this.text = text
            textSize = 15f; setLineSpacing(0f, 1.15f); maxWidth = maxW
            setTextColor(if (fromYou) Look.BG else Look.TEXT)
            setTextIsSelectable(true)
            background = if (fromYou) rounded(0xF2F4F4F5.toInt(), 18) else rounded(0xD9141418.toInt(), 18, 1, Look.EDGE)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        return LinearLayout(ctx).apply {
            gravity = if (fromYou) Gravity.END else Gravity.START
            setPadding(0, dp(4), 0, dp(4))
            addView(t)
        }
    }

    /** Bottom tab bar. */
    fun tabBar(names: List<String>, onPick: (Int) -> Unit): Pair<LinearLayout, List<TextView>> {
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(0xE60E0E12.toInt(), 26, 1, Look.EDGE)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        val tabs = names.mapIndexed { i, n ->
            TextView(ctx).apply {
                text = n; textSize = 14f; typeface = Look.semibold; gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, dp(10))
                setOnClickListener { onPick(i) }
                bar.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
            }
        }
        return bar to tabs
    }

    fun selectTab(tabs: List<TextView>, i: Int) = tabs.forEachIndexed { j, t ->
        t.setTextColor(if (i == j) Look.BG else Look.MUTED)
        t.background = if (i == j) rounded(0xFFF4F4F5.toInt(), 20) else null
    }

    /** Coloured dot + text, for the status line. */
    fun dot(color: Int) = View(ctx).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(10) }
    }
}
