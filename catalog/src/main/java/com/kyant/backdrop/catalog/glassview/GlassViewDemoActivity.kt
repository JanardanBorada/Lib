package com.kyant.backdrop.catalog.glassview

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

class GlassViewDemoActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(buildLayout())
    }

    private fun buildLayout(): View {
        val root = FrameLayout(this)

        // Vivid neon-blob background — dark base + overlapping glowing color circles.
        root.addView(NeonBlobBackground(this), MATCH_PARENT, MATCH_PARENT)

        val scroll = ScrollView(this)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        ViewCompat.setOnApplyWindowInsetsListener(column) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(dp(20), bars.top + dp(16), dp(20), bars.bottom + dp(32))
            insets
        }

        // Header
        column.addView(textView("Glass Effect", 30f, Color.WHITE, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
        }, lp(MATCH_PARENT, WRAP_CONTENT, bottom = dp(4)))

        column.addView(textView("Color · Blur · Vibrancy", 13f, Color.argb(160, 255, 255, 255)).apply {
            gravity = Gravity.CENTER
        }, lp(MATCH_PARENT, WRAP_CONTENT, bottom = dp(28)))

        // Cards — overlay alpha is kept very low so background colors bleed through vividly.
        addCard(
            parent = column,
            title = "Clear Glass",
            body = "Nearly invisible tint: the blurred background colors show through at full saturation.",
            tint = Color.WHITE, alpha = 0.06f,
            blur = 20, cr = dp(28).toFloat(), sat = 1.6f
        )
        addCard(
            parent = column,
            title = "Rose Vibrancy",
            body = "Warm pink tint at low alpha — the neon reds and pinks behind bleed through richly.",
            tint = Color.parseColor("#FF2D55"), alpha = 0.10f,
            blur = 18, cr = dp(32).toFloat(), sat = 1.5f
        )
        addCard(
            parent = column,
            title = "Indigo Frost",
            body = "Cool indigo overlay that amplifies the blues and purples in the background.",
            tint = Color.parseColor("#5856D6"), alpha = 0.12f,
            blur = 22, cr = dp(20).toFloat(), sat = 1.4f
        )
        addCard(
            parent = column,
            title = "Teal Mist",
            body = "Subtle teal tint — the green and cyan blobs underneath stay vivid through the glass.",
            tint = Color.parseColor("#32ADE6"), alpha = 0.08f,
            blur = 16, cr = dp(36).toFloat(), sat = 1.5f
        )
        addCard(
            parent = column,
            title = "Dark Smoked Glass",
            body = "Dark overlay at very low alpha — sleek and minimal while still showing background color.",
            tint = Color.BLACK, alpha = 0.20f,
            blur = 24, cr = dp(16).toFloat(), sat = 1.3f
        )

        scroll.addView(column, MATCH_PARENT, WRAP_CONTENT)
        root.addView(scroll, MATCH_PARENT, MATCH_PARENT)
        return root
    }

    // ── Card builder ──────────────────────────────────────────────────────────

    private fun addCard(
        parent: LinearLayout,
        title: String,
        body: String,
        tint: Int,
        alpha: Float,
        blur: Int,
        cr: Float,
        sat: Float = 1.4f
    ) {
        val card = GlassEffectView(this).apply {
            overlayColor = tint
            overlayAlpha = alpha
            blurRadius = blur
            cornerRadius = cr
            saturationBoost = sat
            val p = dp(20)
            setPadding(p, p, p, p)
        }

        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        inner.addView(
            textView(title, 17f, Color.WHITE, Typeface.BOLD),
            lp(MATCH_PARENT, WRAP_CONTENT, bottom = dp(6))
        )
        inner.addView(
            textView(body, 14f, Color.argb(210, 255, 255, 255)).apply {
                setLineSpacing(0f, 1.5f)
            },
            lp(MATCH_PARENT, WRAP_CONTENT)
        )

        card.addView(inner, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        parent.addView(card, lp(MATCH_PARENT, WRAP_CONTENT, top = dp(10), bottom = dp(10)))
    }

    // ── Neon blob background ──────────────────────────────────────────────────
    //
    // Dark base (#0D0D1A) + six large radial gradients (glowing blobs) layered
    // additively via Porter-Duff ADD.  Each blob fades from its vivid color
    // to transparent so the dark base shows between blobs.

    private inner class NeonBlobBackground(ctx: Context) : View(ctx) {

        // (cx_frac, cy_frac, argb) — positions are fractions of width/height
        private val blobs = listOf(
            Triple(0.10f, 0.08f, Color.parseColor("#FF2D55")),  // red    – top-left
            Triple(0.88f, 0.14f, Color.parseColor("#FF9500")),  // orange – top-right
            Triple(0.50f, 0.30f, Color.parseColor("#AF52DE")),  // purple – upper-centre
            Triple(0.08f, 0.62f, Color.parseColor("#007AFF")),  // blue   – mid-left
            Triple(0.90f, 0.58f, Color.parseColor("#FF375F")),  // pink   – mid-right
            Triple(0.28f, 0.88f, Color.parseColor("#34C759")),  // green  – bottom-left
            Triple(0.72f, 0.92f, Color.parseColor("#30D158")),  // mint   – bottom-right
            Triple(0.50f, 0.70f, Color.parseColor("#64D2FF")),  // cyan   – lower-centre
        )

        private val paints = blobs.map {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                xfermode = android.graphics.PorterDuffXfermode(
                    android.graphics.PorterDuff.Mode.ADD
                )
            }
        }

        override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
            val radius = maxOf(w, h) * 0.72f
            blobs.forEachIndexed { i, (rx, ry, color) ->
                // Fade from half-alpha vivid color → fully transparent
                val vivid = Color.argb(180, Color.red(color), Color.green(color), Color.blue(color))
                val clear = Color.argb(0, Color.red(color), Color.green(color), Color.blue(color))
                paints[i].shader = RadialGradient(
                    w * rx, h * ry, radius,
                    intArrayOf(vivid, clear),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
        }

        override fun onDraw(canvas: Canvas) {
            // Dark base — needed so ADD blending has a dark canvas to add onto
            canvas.drawColor(Color.parseColor("#0D0D1A"))
            val w = width.toFloat(); val h = height.toFloat()
            for (p in paints) canvas.drawRect(0f, 0f, w, h, p)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun textView(text: String, sp: Float, color: Int, style: Int = Typeface.NORMAL) =
        TextView(this).apply {
            this.text = text
            textSize = sp
            setTextColor(color)
            setTypeface(typeface, style)
        }

    private fun lp(
        w: Int = WRAP_CONTENT,
        h: Int = WRAP_CONTENT,
        top: Int = 0,
        bottom: Int = 0
    ) = LinearLayout.LayoutParams(w, h).also { it.setMargins(0, top, 0, bottom) }
}
