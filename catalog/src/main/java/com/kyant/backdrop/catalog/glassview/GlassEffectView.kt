package com.kyant.backdrop.catalog.glassview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Glass-morphism custom view — no external library required.
 *
 * Renders a frosted-glass card by:
 *   1. Capturing the parent's content (excluding itself) into a bitmap
 *   2. Applying StackBlur to the cropped region
 *   3. Drawing the blurred bitmap + semi-transparent tint + top highlight + bright border
 *
 * The blurred bitmap is cached until [invalidateBlur] is called.
 *
 * Usage:
 *   val glass = GlassEffectView(context).apply {
 *       blurRadius  = 20
 *       cornerRadius = 32f
 *       overlayColor = Color.WHITE
 *       overlayAlpha = 0.25f
 *   }
 */
class GlassEffectView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** Blur strength 1–25. Larger values are slower but produce smoother glass. */
    var blurRadius: Int = 20
        set(value) {
            field = value.coerceIn(1, 25)
            clearBlurCache()
        }

    /** Corner radius in pixels. */
    var cornerRadius: Float = 48f
        set(value) {
            field = value
            rebuildClipPath()
            invalidate()
        }

    /** Base color mixed into the tint layer. */
    var overlayColor: Int = Color.WHITE
        set(value) {
            field = value
            invalidate()
        }

    /** Opacity of the tint layer (0 = fully transparent, 1 = fully opaque). */
    var overlayAlpha: Float = 0.25f
        set(value) {
            field = value
            invalidate()
        }

    /**
     * Saturation multiplier applied to the blurred backdrop before compositing.
     * 1.0 = unchanged, 1.5 = vivid, 0.0 = greyscale.
     * Higher values let background colors bleed through the glass more vibrantly.
     */
    var saturationBoost: Float = 1.4f
        set(value) {
            field = value
            invalidate()
        }

    // ── Internals ────────────────────────────────────────────────────────────

    private var cachedBlur: Bitmap? = null
    private var isCapturing = false

    private val clipPath = Path()
    private val boundsRectF = RectF()

    private val blurPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }

    init {
        // ViewGroups skip onDraw unless this flag is cleared.
        setWillNotDraw(false)
    }

    // ── Layout ───────────────────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        boundsRectF.set(0f, 0f, w.toFloat(), h.toFloat())
        rebuildClipPath()
        clearBlurCache()
    }

    private fun rebuildClipPath() {
        clipPath.reset()
        clipPath.addRoundRect(boundsRectF, cornerRadius, cornerRadius, Path.Direction.CW)
    }

    // ── Drawing ──────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.clipPath(clipPath)

        // 1. Blurred background — optionally boost saturation so colors feel vivid through the glass
        val blurred = captureAndBlurBackground()
        if (blurred != null) {
            blurPaint.colorFilter = if (saturationBoost != 1f) {
                ColorMatrixColorFilter(ColorMatrix().also { it.setSaturation(saturationBoost) })
            } else null
            canvas.drawBitmap(blurred, 0f, 0f, blurPaint)
        }

        // 2. Colour tint overlay
        overlayPaint.color = colorWithAlpha(overlayColor, overlayAlpha)
        canvas.drawRect(boundsRectF, overlayPaint)

        // 3. Top-to-centre highlight (simulates light from above)
        highlightPaint.shader = LinearGradient(
            0f, 0f, 0f, height * 0.45f,
            intArrayOf(Color.argb(160, 255, 255, 255), Color.argb(0, 255, 255, 255)),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(clipPath, highlightPaint)

        // 4. Bright top-left → bottom-right border gradient
        val inset = strokePaint.strokeWidth / 2f
        strokePaint.shader = LinearGradient(
            0f, 0f, width.toFloat(), height.toFloat(),
            intArrayOf(Color.argb(200, 255, 255, 255), Color.argb(40, 255, 255, 255)),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        val cr = (cornerRadius - inset).coerceAtLeast(0f)
        canvas.drawRoundRect(RectF(inset, inset, width - inset, height - inset), cr, cr, strokePaint)

        canvas.restore()
        super.onDraw(canvas)
    }

    // ── Blur capture ─────────────────────────────────────────────────────────

    private fun captureAndBlurBackground(): Bitmap? {
        cachedBlur?.let { return it }
        if (isCapturing) return null

        val parent = parent as? ViewGroup ?: return null
        val pw = parent.width; val ph = parent.height
        if (pw <= 0 || ph <= 0 || width <= 0 || height <= 0) return null

        // Render the parent (minus ourselves) into a software bitmap.
        val full = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
        isCapturing = true
        val prevVisibility = visibility
        visibility = View.INVISIBLE
        parent.draw(Canvas(full))
        visibility = prevVisibility
        isCapturing = false

        // Determine our offset inside the parent.
        val myXY = IntArray(2); getLocationOnScreen(myXY)
        val parentXY = IntArray(2); parent.getLocationOnScreen(parentXY)
        val left = (myXY[0] - parentXY[0]).coerceIn(0, pw)
        val top = (myXY[1] - parentXY[1]).coerceIn(0, ph)
        val cw = width.coerceAtMost(pw - left)
        val ch = height.coerceAtMost(ph - top)
        if (cw <= 0 || ch <= 0) { full.recycle(); return null }

        val crop = Bitmap.createBitmap(full, left, top, cw, ch)
        full.recycle()

        // Scale down → blur (two passes for smoothness) → scale back up.
        val scale = 4
        val sw = max(1, cw / scale); val sh = max(1, ch / scale)
        val small = Bitmap.createScaledBitmap(crop, sw, sh, true).copy(Bitmap.Config.ARGB_8888, true)
        crop.recycle()

        val r = max(2, blurRadius / scale)
        stackBlur(small, r)
        stackBlur(small, r)   // second pass → smoother edges

        val result = Bitmap.createScaledBitmap(small, cw, ch, true)
        small.recycle()

        cachedBlur = result
        return result
    }

    /** Force the blurred background to be recaptured on the next draw. */
    fun invalidateBlur() = clearBlurCache()

    private fun clearBlurCache() {
        cachedBlur?.recycle()
        cachedBlur = null
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        clearBlurCache()
    }

    // ── StackBlur ─────────────────────────────────────────────────────────────
    // Pure-Kotlin in-place blur. No library, no RenderScript, works API 23+.
    // Based on Mario Klingemann's StackBlur algorithm.

    private fun stackBlur(bm: Bitmap, radius: Int) {
        val r = radius.coerceAtLeast(1)
        val w = bm.width; val h = bm.height
        val pix = IntArray(w * h)
        bm.getPixels(pix, 0, w, 0, 0, w, h)

        val div = r + r + 1
        val rArr = IntArray(w * h); val gArr = IntArray(w * h); val bArr = IntArray(w * h)
        val vmin = IntArray(max(w, h))
        val half = (div + 1) shr 1
        val divSum = half * half
        val dv = IntArray(256 * divSum) { it / divSum }
        val stk = Array(div) { IntArray(3) }
        val r1 = r + 1

        var yw = 0; var yi = 0
        for (y in 0 until h) {
            var rsum = 0; var gsum = 0; var bsum = 0
            var routsum = 0; var goutsum = 0; var boutsum = 0
            var rinsum = 0; var ginsum = 0; var binsum = 0
            for (i in -r..r) {
                val p = pix[yi + min(w - 1, max(0, i))]
                val s = stk[i + r]
                s[0] = (p shr 16) and 0xff; s[1] = (p shr 8) and 0xff; s[2] = p and 0xff
                val rbs = r1 - abs(i)
                rsum += s[0] * rbs; gsum += s[1] * rbs; bsum += s[2] * rbs
                if (i > 0) { rinsum += s[0]; ginsum += s[1]; binsum += s[2] }
                else { routsum += s[0]; goutsum += s[1]; boutsum += s[2] }
            }
            var sp = r
            for (x in 0 until w) {
                rArr[yi] = dv[rsum]; gArr[yi] = dv[gsum]; bArr[yi] = dv[bsum]
                rsum -= routsum; gsum -= goutsum; bsum -= boutsum
                val ss = (sp - r + div) % div; val s2 = stk[ss]
                routsum -= s2[0]; goutsum -= s2[1]; boutsum -= s2[2]
                if (y == 0) vmin[x] = min(x + r + 1, w - 1)
                val p2 = pix[yw + vmin[x]]
                s2[0] = (p2 shr 16) and 0xff; s2[1] = (p2 shr 8) and 0xff; s2[2] = p2 and 0xff
                rinsum += s2[0]; ginsum += s2[1]; binsum += s2[2]
                rsum += rinsum; gsum += ginsum; bsum += binsum
                sp = (sp + 1) % div; val s3 = stk[sp]
                routsum += s3[0]; goutsum += s3[1]; boutsum += s3[2]
                rinsum -= s3[0]; ginsum -= s3[1]; binsum -= s3[2]
                yi++
            }
            yw += w
        }

        for (x in 0 until w) {
            var rsum = 0; var gsum = 0; var bsum = 0
            var routsum = 0; var goutsum = 0; var boutsum = 0
            var rinsum = 0; var ginsum = 0; var binsum = 0
            var yp = -r * w
            for (i in -r..r) {
                val yi2 = max(0, yp) + x; val s = stk[i + r]
                s[0] = rArr[yi2]; s[1] = gArr[yi2]; s[2] = bArr[yi2]
                val rbs = r1 - abs(i)
                rsum += s[0] * rbs; gsum += s[1] * rbs; bsum += s[2] * rbs
                if (i > 0) { rinsum += s[0]; ginsum += s[1]; binsum += s[2] }
                else { routsum += s[0]; goutsum += s[1]; boutsum += s[2] }
                if (i < h - 1) yp += w
            }
            yi = x; var sp = r
            for (y in 0 until h) {
                pix[yi] = 0xff000000.toInt() or (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum]
                rsum -= routsum; gsum -= goutsum; bsum -= boutsum
                val ss = (sp - r + div) % div; val s2 = stk[ss]
                routsum -= s2[0]; goutsum -= s2[1]; boutsum -= s2[2]
                if (x == 0) vmin[y] = min(y + r1, h - 1) * w
                s2[0] = rArr[x + vmin[y]]; s2[1] = gArr[x + vmin[y]]; s2[2] = bArr[x + vmin[y]]
                rinsum += s2[0]; ginsum += s2[1]; binsum += s2[2]
                rsum += rinsum; gsum += ginsum; bsum += binsum
                sp = (sp + 1) % div; val s3 = stk[sp]
                routsum += s3[0]; goutsum += s3[1]; boutsum += s3[2]
                rinsum -= s3[0]; ginsum -= s3[1]; binsum -= s3[2]
                yi += w
            }
        }

        bm.setPixels(pix, 0, w, 0, 0, w, h)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun colorWithAlpha(color: Int, alpha: Float) = Color.argb(
        (alpha * 255).toInt().coerceIn(0, 255),
        Color.red(color), Color.green(color), Color.blue(color)
    )
}
