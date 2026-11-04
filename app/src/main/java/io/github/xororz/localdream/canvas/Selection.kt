package io.github.xororz.localdream.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import java.nio.ByteBuffer

enum class SelectionMode { REPLACE, ADD, SUBTRACT }

/**
 * A selection is an ALPHA_8 mask the size of the document (255 = selected).
 * [outline] is kept when the selection came from shapes, to draw marching ants;
 * wand selections only get the tinted overlay.
 */
class Selection(val mask: Bitmap, val bounds: Rect, val outline: Path?) {
    private var alphaCache: ByteArray? = null

    /** Mask bytes, row-major, width * height. */
    fun alpha(): ByteArray = alphaCache ?: ByteArray(mask.width * mask.height).also {
        mask.copyPixelsToBuffer(ByteBuffer.wrap(it))
        alphaCache = it
    }

    /** Overlay for the unselected area (inverse mask). */
    val outsideOverlay: Bitmap by lazy {
        val out = Bitmap.createBitmap(mask.width, mask.height, Bitmap.Config.ALPHA_8)
        Canvas(out).apply {
            drawColor(Color.BLACK)
            drawBitmap(mask, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
        }
        out
    }

    /** White-on-transparent ARGB mask, as the backend expects for inpainting. */
    fun toInpaintMask(feather: Float = 0f): Bitmap {
        val out = Bitmap.createBitmap(mask.width, mask.height, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.FILTER_BITMAP_FLAG).apply { color = Color.WHITE }
        if (feather > 0f) p.maskFilter = android.graphics.BlurMaskFilter(feather, android.graphics.BlurMaskFilter.Blur.NORMAL)
        Canvas(out).drawBitmap(mask, 0f, 0f, p)
        return out
    }

    fun transformed(matrix: Matrix): Selection? {
        val out = Bitmap.createBitmap(mask.width, mask.height, Bitmap.Config.ALPHA_8)
        Canvas(out).drawBitmap(mask, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        val path = outline?.let { Path(it).apply { transform(matrix) } }
        return fromMask(out, path)
    }

    companion object {
        fun empty(w: Int, h: Int): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)

        fun fromPath(path: Path, w: Int, h: Int): Selection? {
            val mask = empty(w, h)
            Canvas(mask).drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK })
            return fromMask(mask, Path(path))
        }

        fun all(w: Int, h: Int): Selection {
            val mask = empty(w, h)
            mask.eraseColor(Color.BLACK)
            val path = Path().apply { addRect(0f, 0f, w.toFloat(), h.toFloat(), Path.Direction.CW) }
            return Selection(mask, Rect(0, 0, w, h), path)
        }

        /** Builds a selection from a mask; null when nothing is selected. */
        fun fromMask(mask: Bitmap, outline: Path?): Selection? {
            val w = mask.width
            val h = mask.height
            val bytes = ByteArray(w * h)
            mask.copyPixelsToBuffer(ByteBuffer.wrap(bytes))
            var minX = w
            var minY = h
            var maxX = -1
            var maxY = -1
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) {
                    if (bytes[row + x].toInt() != 0) {
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                        if (y < minY) minY = y
                        if (y > maxY) maxY = y
                    }
                }
            }
            if (maxX < 0) return null
            return Selection(mask, Rect(minX, minY, maxX + 1, maxY + 1), outline)
        }

        fun combine(old: Selection?, new: Selection?, mode: SelectionMode): Selection? {
            if (mode == SelectionMode.REPLACE || old == null) {
                return if (mode == SelectionMode.SUBTRACT) null else new
            }
            if (new == null) return old
            val out = old.mask.copy(Bitmap.Config.ALPHA_8, true)
            val p = Paint()
            p.xfermode = PorterDuffXfermode(
                if (mode == SelectionMode.ADD) PorterDuff.Mode.SRC_OVER else PorterDuff.Mode.DST_OUT,
            )
            Canvas(out).drawBitmap(new.mask, 0f, 0f, p)
            val path = if (old.outline != null && new.outline != null) {
                Path().apply {
                    op(old.outline, new.outline, if (mode == SelectionMode.ADD) Path.Op.UNION else Path.Op.DIFFERENCE)
                }
            } else {
                null
            }
            return fromMask(out, path)
        }

        fun invert(old: Selection?, w: Int, h: Int): Selection? {
            if (old == null) return all(w, h)
            val out = empty(w, h)
            Canvas(out).apply {
                drawColor(Color.BLACK)
                drawBitmap(old.mask, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
            }
            val path = old.outline?.let {
                Path().apply {
                    addRect(0f, 0f, w.toFloat(), h.toFloat(), Path.Direction.CW)
                    op(it, Path.Op.DIFFERENCE)
                }
            }
            return fromMask(out, path)
        }

        /**
         * Magic wand: contiguous region around (x, y) whose colour is within
         * [tolerance] (0..255) of the seed colour, on [source].
         */
        fun magicWand(source: Bitmap, x: Int, y: Int, tolerance: Int): Selection? {
            val w = source.width
            val h = source.height
            if (x !in 0 until w || y !in 0 until h) return null
            val px = IntArray(w * h)
            source.getPixels(px, 0, w, 0, 0, w, h)
            val filled = floodRegion(px, w, h, x, y, tolerance)
            val bytes = ByteArray(w * h)
            for (i in bytes.indices) if (filled[i]) bytes[i] = 0xFF.toByte()
            val mask = empty(w, h)
            mask.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
            return fromMask(mask, null)
        }
    }
}

/** Scanline flood fill; returns which pixels belong to the region around (sx, sy). */
fun floodRegion(px: IntArray, w: Int, h: Int, sx: Int, sy: Int, tolerance: Int): BooleanArray {
    val seed = px[sy * w + sx]
    val sa = seed ushr 24
    val sr = (seed shr 16) and 0xFF
    val sg = (seed shr 8) and 0xFF
    val sb = seed and 0xFF
    val tol = tolerance.coerceIn(0, 255)
    fun matches(c: Int): Boolean {
        val a = c ushr 24
        // Fully transparent pixels all count as the same colour.
        if (a == 0 && sa == 0) return true
        return kotlin.math.abs(a - sa) <= tol &&
            kotlin.math.abs(((c shr 16) and 0xFF) - sr) <= tol &&
            kotlin.math.abs(((c shr 8) and 0xFF) - sg) <= tol &&
            kotlin.math.abs((c and 0xFF) - sb) <= tol
    }
    val done = BooleanArray(w * h)
    val stack = IntArray(w * h + 1)
    var sp = 0
    stack[sp++] = sy * w + sx
    while (sp > 0) {
        val idx = stack[--sp]
        val y = idx / w
        var x = idx % w
        if (done[idx]) continue
        while (x > 0 && !done[y * w + x - 1] && matches(px[y * w + x - 1])) x--
        var spanUp = false
        var spanDown = false
        while (x < w) {
            val i = y * w + x
            if (done[i] || !matches(px[i])) break
            done[i] = true
            if (y > 0) {
                val up = i - w
                val ok = !done[up] && matches(px[up])
                if (ok && !spanUp && sp < stack.size) {
                    stack[sp++] = up
                    spanUp = true
                } else if (!ok) {
                    spanUp = false
                }
            }
            if (y < h - 1) {
                val dn = i + w
                val ok = !done[dn] && matches(px[dn])
                if (ok && !spanDown && sp < stack.size) {
                    stack[sp++] = dn
                    spanDown = true
                } else if (!ok) {
                    spanDown = false
                }
            }
            x++
        }
    }
    return done
}
