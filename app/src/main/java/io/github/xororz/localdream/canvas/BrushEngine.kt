package io.github.xororz.localdream.canvas

import android.graphics.Bitmap
import android.graphics.Rect
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class CanvasTool(val isBrush: Boolean = false) {
    PEN(true),
    AIRBRUSH(true),
    ERASER(true),
    BLUR(true),
    SMUDGE(true),
    FILL,
    EYEDROPPER,
    SELECT_RECT,
    SELECT_LASSO,
    SELECT_WAND,
    MOVE,
    HAND,
}

/** Per-tool adjustable settings. */
data class BrushSettings(
    val size: Float = 8f,
    val opacity: Float = 1f,
    val hardness: Float = 1f,
    /** Dab spacing as a fraction of the diameter. */
    val spacing: Float = 0.08f,
    /** Blur/smudge strength, airbrush flow. */
    val strength: Float = 0.5f,
    val pressureSize: Boolean = true,
    val pressureOpacity: Boolean = false,
    /** Smallest size at zero pressure, as a fraction of [size]. */
    val minSizeRatio: Float = 0.15f,
) {
    companion object {
        fun defaultFor(tool: CanvasTool): BrushSettings = when (tool) {
            CanvasTool.PEN -> BrushSettings(size = 8f, hardness = 1f, spacing = 0.08f)
            CanvasTool.AIRBRUSH -> BrushSettings(size = 80f, hardness = 0f, spacing = 0.06f, strength = 0.15f, pressureSize = false, pressureOpacity = true)
            CanvasTool.ERASER -> BrushSettings(size = 30f, hardness = 0.9f, spacing = 0.08f, pressureSize = false)
            CanvasTool.BLUR -> BrushSettings(size = 60f, hardness = 0.3f, spacing = 0.2f, strength = 0.6f, pressureSize = false)
            CanvasTool.SMUDGE -> BrushSettings(size = 40f, hardness = 0.4f, spacing = 0.1f, strength = 0.7f, pressureSize = false)
            else -> BrushSettings()
        }
    }
}

private fun smoothstep(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

/**
 * Lookup of dab alpha against squared normalised distance (0..1), so per pixel
 * work is a multiply and an array read.
 */
private class TipLut(hardness: Float, radiusPx: Float) {
    val table = FloatArray(LUT_SIZE + 1)

    init {
        // A hard tip still gets ~1px of antialiasing at its edge.
        val aa = (1f / max(radiusPx, 0.5f)).coerceIn(0.002f, 1f)
        val h = (hardness.coerceIn(0f, 1f) * (1f - aa))
        for (i in 0..LUT_SIZE) {
            val d = sqrt(i / LUT_SIZE.toFloat())
            table[i] = if (d <= h) 1f else 1f - smoothstep((d - h) / (1f - h).coerceAtLeast(1e-4f))
        }
    }

    fun at(d2: Float): Float = if (d2 >= 1f) 0f else table[(d2 * LUT_SIZE).toInt()]

    companion object {
        const val LUT_SIZE = 1024
    }
}

/**
 * One stroke in progress. PEN / AIRBRUSH / ERASER paint into an alpha buffer
 * that is composited onto the layer on [finish], so overlapping dabs inside a
 * stroke never stack past the stroke opacity. BLUR / SMUDGE edit the layer
 * directly.
 */
class StrokeSession(
    private val doc: CanvasDocument,
    val layer: CanvasLayer,
    val tool: CanvasTool,
    private val settings: BrushSettings,
    private val color: Int,
    /** Stabiliser strength, 0 = off. */
    private val correction: Int,
    private val pressureEnabled: Boolean,
) {
    private val w = doc.width
    private val h = doc.height
    private val selectionAlpha: ByteArray? = doc.selection?.alpha()

    /** Full copy of the layer when the stroke began: undo and cancel. */
    private val before: Bitmap = layer.bitmap.copy(Bitmap.Config.ARGB_8888, false)

    val usesBuffer = tool == CanvasTool.PEN || tool == CanvasTool.AIRBRUSH || tool == CanvasTool.ERASER

    /** Stroke alpha buffer (0..255), and its preview bitmap. */
    val buffer: ByteArray? = if (usesBuffer) ByteArray(w * h) else null
    val bufferBitmap: Bitmap? = if (usesBuffer) Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8) else null
    val strokeColor: Int get() = color
    val strokeOpacity: Float get() = settings.opacity

    private val dirty = Rect()
    private var hasDirty = false

    private var lastX = 0f
    private var lastY = 0f
    private var lastP = 1f
    private var smoothX = 0f
    private var smoothY = 0f
    private var started = false
    private var distanceCarry = 0f
    var dabCount = 0
        private set

    // Smudge pick-up buffer, premultiplied RGBA floats, diameter x diameter.
    private var smudgeBuf: FloatArray? = null
    private var smudgeDiameter = 0

    private val lutCache = HashMap<Int, TipLut>()

    private fun lut(radius: Float): TipLut {
        val key = (radius * 2).roundToInt().coerceAtLeast(1)
        return lutCache.getOrPut(key) { TipLut(settings.hardness, radius) }
    }

    fun begin(x: Float, y: Float, pressure: Float) {
        smoothX = x
        smoothY = y
        lastX = x
        lastY = y
        lastP = effectivePressure(pressure)
        started = true
        dab(x, y, lastP)
    }

    private fun effectivePressure(p: Float): Float = if (pressureEnabled) p.coerceIn(0.02f, 1f) else 1f

    /** Feeds a new input point; the stabiliser trails it. */
    fun moveTo(x: Float, y: Float, pressure: Float) {
        if (!started) {
            begin(x, y, pressure)
            return
        }
        val k = correction.coerceIn(0, 30)
        if (k == 0) {
            segmentTo(x, y, effectivePressure(pressure))
        } else {
            val f = 1f / (1f + k * 0.45f)
            smoothX += (x - smoothX) * f
            smoothY += (y - smoothY) * f
            segmentTo(smoothX, smoothY, effectivePressure(pressure))
        }
    }

    /** Catches the stabiliser up with the final pen position. */
    fun endAt(x: Float, y: Float) {
        if (correction > 0 && started) segmentTo(x, y, lastP)
    }

    private fun sizeFor(p: Float): Float {
        val s = settings.size
        return if (settings.pressureSize && pressureEnabled) {
            s * (settings.minSizeRatio + (1f - settings.minSizeRatio) * p)
        } else {
            s
        }
    }

    private fun segmentTo(x: Float, y: Float, p: Float) {
        val dx = x - lastX
        val dy = y - lastY
        val dist = hypot(dx, dy)
        if (dist < 1e-3f) return
        var t = distanceCarry
        while (true) {
            val f = t / dist
            if (f > 1f) break
            val pp = lastP + (p - lastP) * f
            val step = max(sizeFor(pp) * settings.spacing, 0.5f)
            if (t > 0f || dabCount == 0) dab(lastX + dx * f, lastY + dy * f, pp)
            t += step
        }
        distanceCarry = t - dist
        lastX = x
        lastY = y
        lastP = p
    }

    private fun dab(cx: Float, cy: Float, p: Float) {
        dabCount++
        val radius = max(sizeFor(p) / 2f, 0.5f)
        when (tool) {
            CanvasTool.PEN, CanvasTool.ERASER -> dabMax(cx, cy, radius, if (settings.pressureOpacity && pressureEnabled) p else 1f)
            CanvasTool.AIRBRUSH -> dabFlow(cx, cy, radius, settings.strength * (if (settings.pressureOpacity && pressureEnabled) p else 1f))
            CanvasTool.BLUR -> dabBlur(cx, cy, radius, settings.strength * (if (pressureEnabled) p else 1f))
            CanvasTool.SMUDGE -> dabSmudge(cx, cy, radius, settings.strength * (if (pressureEnabled) (0.5f + 0.5f * p) else 1f))
            else -> Unit
        }
    }

    private fun bounds(cx: Float, cy: Float, r: Float): Rect? {
        val l = floor(cx - r).toInt().coerceAtLeast(0)
        val t = floor(cy - r).toInt().coerceAtLeast(0)
        val rr = ceil(cx + r).toInt().coerceAtMost(w)
        val b = ceil(cy + r).toInt().coerceAtMost(h)
        if (rr <= l || b <= t) return null
        return Rect(l, t, rr, b)
    }

    private fun markDirty(r: Rect) {
        if (!hasDirty) {
            dirty.set(r)
            hasDirty = true
        } else {
            dirty.union(r)
        }
    }

    private fun selAt(i: Int): Float = selectionAlpha?.let { (it[i].toInt() and 0xFF) / 255f } ?: 1f

    private fun dabMax(cx: Float, cy: Float, r: Float, amount: Float) {
        val buf = buffer ?: return
        val box = bounds(cx, cy, r) ?: return
        val lut = lut(r)
        val inv = 1f / (r * r)
        for (y in box.top until box.bottom) {
            val fy = y + 0.5f - cy
            val row = y * w
            for (x in box.left until box.right) {
                val fx = x + 0.5f - cx
                val a = lut.at((fx * fx + fy * fy) * inv) * amount * selAt(row + x)
                if (a <= 0f) continue
                val v = (a * 255f).toInt()
                val i = row + x
                if (v > (buf[i].toInt() and 0xFF)) buf[i] = v.toByte()
            }
        }
        markDirty(box)
    }

    private fun dabFlow(cx: Float, cy: Float, r: Float, flow: Float) {
        val buf = buffer ?: return
        val box = bounds(cx, cy, r) ?: return
        val lut = lut(r)
        val inv = 1f / (r * r)
        for (y in box.top until box.bottom) {
            val fy = y + 0.5f - cy
            val row = y * w
            for (x in box.left until box.right) {
                val fx = x + 0.5f - cx
                val a = lut.at((fx * fx + fy * fy) * inv) * flow * selAt(row + x)
                if (a <= 0f) continue
                val i = row + x
                val d = (buf[i].toInt() and 0xFF) / 255f
                buf[i] = ((d + a * (1f - d)) * 255f).toInt().coerceAtMost(255).toByte()
            }
        }
        markDirty(box)
    }

    private fun dabBlur(cx: Float, cy: Float, r: Float, strength: Float) {
        val box = bounds(cx, cy, r) ?: return
        val blurR = max(1, (r * 0.35f).roundToInt())
        // Read a margin around the dab so the blur has real neighbours.
        val l = (box.left - blurR).coerceAtLeast(0)
        val t = (box.top - blurR).coerceAtLeast(0)
        val rr = (box.right + blurR).coerceAtMost(w)
        val b = (box.bottom + blurR).coerceAtMost(h)
        val rw = rr - l
        val rh = b - t
        val src = IntArray(rw * rh)
        layer.bitmap.getPixels(src, 0, rw, l, t, rw, rh)
        val pm = toPremul(src)
        val blurred = boxBlur(boxBlur(pm, rw, rh, blurR), rw, rh, blurR)
        val lut = lut(r)
        val inv = 1f / (r * r)
        for (y in box.top until box.bottom) {
            val fy = y + 0.5f - cy
            for (x in box.left until box.right) {
                val fx = x + 0.5f - cx
                val a = lut.at((fx * fx + fy * fy) * inv) * strength * selAt(y * w + x)
                if (a <= 0f) continue
                val li = ((y - t) * rw + (x - l)) * 4
                for (c in 0 until 4) pm[li + c] += (blurred[li + c] - pm[li + c]) * a
            }
        }
        fromPremul(pm, src)
        layer.bitmap.setPixels(src, 0, rw, l, t, rw, rh)
        markDirty(Rect(l, t, rr, b))
    }

    private fun dabSmudge(cx: Float, cy: Float, r: Float, strength: Float) {
        val d = max(2, ceil(r * 2).toInt())
        val ox = (cx - d / 2f).roundToInt()
        val oy = (cy - d / 2f).roundToInt()
        // Region of the patch that lies on the canvas.
        val l = ox.coerceAtLeast(0)
        val t = oy.coerceAtLeast(0)
        val rr = (ox + d).coerceAtMost(w)
        val b = (oy + d).coerceAtMost(h)
        if (rr <= l || b <= t) return
        val rw = rr - l
        val rh = b - t
        val src = IntArray(rw * rh)
        layer.bitmap.getPixels(src, 0, rw, l, t, rw, rh)
        val pm = toPremul(src)
        var buf = smudgeBuf
        if (buf == null || smudgeDiameter != d) {
            // First dab (or size changed): pick up the paint under the tip.
            val fresh = FloatArray(d * d * 4)
            for (y in t until b) for (x in l until rr) {
                val pi = ((y - oy) * d + (x - ox)) * 4
                val li = ((y - t) * rw + (x - l)) * 4
                for (c in 0 until 4) fresh[pi + c] = pm[li + c]
            }
            buf = fresh
            smudgeBuf = fresh
            smudgeDiameter = d
            return
        }
        val lut = lut(r)
        val inv = 1f / (r * r)
        val center = d / 2f
        for (y in t until b) {
            val py = y - oy
            val fy = py + 0.5f - center
            for (x in l until rr) {
                val px = x - ox
                val fx = px + 0.5f - center
                val tip = lut.at((fx * fx + fy * fy) * inv)
                if (tip <= 0f) continue
                val a = tip * strength * selAt(y * w + x)
                val pi = (py * d + px) * 4
                val li = ((y - t) * rw + (x - l)) * 4
                for (c in 0 until 4) {
                    val canvasV = pm[li + c]
                    val carried = buf[pi + c]
                    pm[li + c] = canvasV + (carried - canvasV) * a
                    // The tip keeps most of its paint and picks up some of the canvas.
                    buf[pi + c] = carried + (canvasV - carried) * (1f - strength) * tip
                }
            }
        }
        fromPremul(pm, src)
        layer.bitmap.setPixels(src, 0, rw, l, t, rw, rh)
        markDirty(Rect(l, t, rr, b))
    }

    /** Copies the alpha buffer into its preview bitmap. */
    fun syncPreview() {
        val buf = buffer ?: return
        bufferBitmap?.copyPixelsFromBuffer(ByteBuffer.wrap(buf))
    }

    /** Composites the stroke onto the layer and records undo. */
    fun finish() {
        if (!hasDirty) {
            bufferBitmap?.recycle()
            return
        }
        val r = Rect(dirty)
        if (usesBuffer) commitBuffer(r)
        val beforeRect = Bitmap.createBitmap(before, r.left, r.top, r.width(), r.height())
        doc.recordPixels(layer, r, beforeRect)
        bufferBitmap?.recycle()
    }

    /** Throws the stroke away and restores the layer. */
    fun cancel() {
        if (!usesBuffer && hasDirty) {
            val r = Rect(dirty)
            val px = IntArray(r.width() * r.height())
            before.getPixels(px, 0, r.width(), r.left, r.top, r.width(), r.height())
            layer.bitmap.setPixels(px, 0, r.width(), r.left, r.top, r.width(), r.height())
        }
        bufferBitmap?.recycle()
        doc.redraw()
    }

    private fun commitBuffer(r: Rect) {
        val buf = buffer ?: return
        val rw = r.width()
        val rh = r.height()
        val px = IntArray(rw * rh)
        layer.bitmap.getPixels(px, 0, rw, r.left, r.top, rw, rh)
        val op = settings.opacity
        val cr = (color shr 16) and 0xFF
        val cg = (color shr 8) and 0xFF
        val cb = color and 0xFF
        val ca = ((color ushr 24) / 255f)
        val erase = tool == CanvasTool.ERASER
        for (y in 0 until rh) {
            val brow = (y + r.top) * w + r.left
            for (x in 0 until rw) {
                val s = (buf[brow + x].toInt() and 0xFF) / 255f * op
                if (s <= 0f) continue
                val i = y * rw + x
                val d = px[i]
                val da = (d ushr 24) / 255f
                if (erase) {
                    val na = da * (1f - s)
                    px[i] = ((na * 255f).roundToInt() shl 24) or (d and 0xFFFFFF)
                } else {
                    val sa = s * ca
                    val oa = sa + da * (1f - sa)
                    if (oa <= 0f) continue
                    val dr = (d shr 16) and 0xFF
                    val dg = (d shr 8) and 0xFF
                    val db = d and 0xFF
                    val k = da * (1f - sa)
                    val or = ((cr * sa + dr * k) / oa).roundToInt().coerceIn(0, 255)
                    val og = ((cg * sa + dg * k) / oa).roundToInt().coerceIn(0, 255)
                    val ob = ((cb * sa + db * k) / oa).roundToInt().coerceIn(0, 255)
                    px[i] = ((oa * 255f).roundToInt().coerceIn(0, 255) shl 24) or (or shl 16) or (og shl 8) or ob
                }
            }
        }
        layer.bitmap.setPixels(px, 0, rw, r.left, r.top, rw, rh)
    }

    fun releaseBefore() {
        before.recycle()
    }
}

// ----- pixel helpers --------------------------------------------------------

/** Unpremultiplied ARGB ints to premultiplied RGBA floats (0..255). */
internal fun toPremul(src: IntArray): FloatArray {
    val out = FloatArray(src.size * 4)
    for (i in src.indices) {
        val c = src[i]
        val a = (c ushr 24) / 255f
        out[i * 4] = ((c shr 16) and 0xFF) * a
        out[i * 4 + 1] = ((c shr 8) and 0xFF) * a
        out[i * 4 + 2] = (c and 0xFF) * a
        out[i * 4 + 3] = (c ushr 24).toFloat()
    }
    return out
}

internal fun fromPremul(pm: FloatArray, out: IntArray) {
    for (i in out.indices) {
        val a = pm[i * 4 + 3].coerceIn(0f, 255f)
        if (a < 0.5f) {
            out[i] = 0
            continue
        }
        val k = 255f / a
        val r = (pm[i * 4] * k).roundToInt().coerceIn(0, 255)
        val g = (pm[i * 4 + 1] * k).roundToInt().coerceIn(0, 255)
        val b = (pm[i * 4 + 2] * k).roundToInt().coerceIn(0, 255)
        out[i] = (a.roundToInt() shl 24) or (r shl 16) or (g shl 8) or b
    }
}

/** Separable box blur on premultiplied RGBA floats. */
internal fun boxBlur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
    val tmp = FloatArray(src.size)
    val out = FloatArray(src.size)
    // Horizontal
    for (y in 0 until h) {
        for (c in 0 until 4) {
            var sum = 0f
            var count = 0
            for (x in -r..r) {
                val xx = x.coerceIn(0, w - 1)
                sum += src[(y * w + xx) * 4 + c]
                count++
            }
            for (x in 0 until w) {
                tmp[(y * w + x) * 4 + c] = sum / count
                val outX = (x - r).coerceIn(0, w - 1)
                val inX = (x + r + 1).coerceIn(0, w - 1)
                sum += src[(y * w + inX) * 4 + c] - src[(y * w + outX) * 4 + c]
            }
        }
    }
    // Vertical
    for (x in 0 until w) {
        for (c in 0 until 4) {
            var sum = 0f
            var count = 0
            for (y in -r..r) {
                val yy = y.coerceIn(0, h - 1)
                sum += tmp[(yy * w + x) * 4 + c]
                count++
            }
            for (y in 0 until h) {
                out[(y * w + x) * 4 + c] = sum / count
                val outY = (y - r).coerceIn(0, h - 1)
                val inY = (y + r + 1).coerceIn(0, h - 1)
                sum += tmp[(inY * w + x) * 4 + c] - tmp[(outY * w + x) * 4 + c]
            }
        }
    }
    return out
}

/** Bucket fill on [layer], limited to the selection when there is one. */
fun bucketFill(doc: CanvasDocument, layer: CanvasLayer, x: Int, y: Int, color: Int, opacity: Float, tolerance: Int, sampleAll: Boolean) {
    val w = doc.width
    val h = doc.height
    if (x !in 0 until w || y !in 0 until h) return
    val sampleSrc = if (sampleAll) doc.compositeVisible(includePaper = false) else layer.bitmap
    val sample = IntArray(w * h)
    sampleSrc.getPixels(sample, 0, w, 0, 0, w, h)
    if (sampleSrc !== layer.bitmap) sampleSrc.recycle()
    val region = floodRegion(sample, w, h, x, y, tolerance)
    val sel = doc.selection?.alpha()
    doc.editActiveLayer { l ->
        val px = IntArray(w * h)
        l.bitmap.getPixels(px, 0, w, 0, 0, w, h)
        val cr = (color shr 16) and 0xFF
        val cg = (color shr 8) and 0xFF
        val cb = color and 0xFF
        // Grow the region by one pixel so antialiased line edges are covered.
        val grown = region.copyOf()
        for (yy in 0 until h) for (xx in 0 until w) {
            val i = yy * w + xx
            if (region[i]) continue
            if ((xx > 0 && region[i - 1]) || (xx < w - 1 && region[i + 1]) ||
                (yy > 0 && region[i - w]) || (yy < h - 1 && region[i + w])
            ) {
                grown[i] = true
            }
        }
        for (i in px.indices) {
            if (!grown[i]) continue
            val s = opacity * (sel?.let { (it[i].toInt() and 0xFF) / 255f } ?: 1f)
            if (s <= 0f) continue
            val d = px[i]
            val da = (d ushr 24) / 255f
            val oa = s + da * (1f - s)
            val k = da * (1f - s)
            val r = ((cr * s + ((d shr 16) and 0xFF) * k) / oa).roundToInt().coerceIn(0, 255)
            val g = ((cg * s + ((d shr 8) and 0xFF) * k) / oa).roundToInt().coerceIn(0, 255)
            val b = ((cb * s + (d and 0xFF) * k) / oa).roundToInt().coerceIn(0, 255)
            px[i] = ((oa * 255f).roundToInt().coerceIn(0, 255) shl 24) or (r shl 16) or (g shl 8) or b
        }
        l.bitmap.setPixels(px, 0, w, 0, 0, w, h)
    }
}

internal fun minOf3(a: Int, b: Int, c: Int) = min(a, min(b, c))
