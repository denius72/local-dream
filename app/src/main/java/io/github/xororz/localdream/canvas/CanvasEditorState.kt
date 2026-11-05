package io.github.xororz.localdream.canvas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.core.content.edit
import org.json.JSONObject

/** What happens to the layers when a generated image is accepted. */
enum class AcceptMode { NEW_LAYER, FLATTEN }

/** Floating pixels being moved/scaled/rotated. */
class TransformSession(
    val layer: CanvasLayer,
    val floating: Bitmap,
    /** Layer pixels before the transform started (undo / cancel). */
    val before: Bitmap,
    val selectionBefore: Selection?,
) {
    val matrix = Matrix()
    var revision by mutableStateOf(0)
}

/** Clipboard for copy / cut / paste: pixels plus where they came from. */
class CanvasClipboard(val bitmap: Bitmap, val left: Int, val top: Int)

/**
 * UI-side editor state: current tool, colour, per-tool settings, view
 * transform. Settings persist in SharedPreferences.
 */
class CanvasEditorState(context: Context, val doc: CanvasDocument) {
    val appContext: Context = context.applicationContext
    private val prefs = context.getSharedPreferences("canvas_prefs", Context.MODE_PRIVATE)

    var tool by mutableStateOf(CanvasTool.valueOf(prefs.getString("tool", CanvasTool.PEN.name) ?: CanvasTool.PEN.name))
    var color by mutableStateOf(prefs.getInt("color", android.graphics.Color.BLACK))
    val recentColors = mutableStateListOf<Int>().apply {
        prefs.getString("recent_colors", null)?.split(',')?.mapNotNull { it.toIntOrNull() }?.let { addAll(it) }
    }
    val settings = mutableStateMapOf<CanvasTool, BrushSettings>()
    var correction by mutableStateOf(prefs.getInt("correction", 3))
    var stylusOnly by mutableStateOf(prefs.getBoolean("stylus_only", false))
    var pressureEnabled by mutableStateOf(prefs.getBoolean("pressure", true))
    var acceptMode by mutableStateOf(AcceptMode.valueOf(prefs.getString("accept_mode", AcceptMode.NEW_LAYER.name) ?: AcceptMode.NEW_LAYER.name))
    var selectionMode by mutableStateOf(SelectionMode.REPLACE)

    /** Inpaint mask brush erases instead of painting. */
    var maskErase by mutableStateOf(false)

    /** Mask being painted right now (ALPHA_8), shown instead of the selection. */
    var maskDraft by mutableStateOf<Bitmap?>(null)
    var wandTolerance by mutableStateOf(prefs.getInt("wand_tolerance", 32))
    var fillTolerance by mutableStateOf(prefs.getInt("fill_tolerance", 24))
    var fillSampleAll by mutableStateOf(prefs.getBoolean("fill_sample_all", true))
    var maskFeather by mutableStateOf(prefs.getFloat("mask_feather", 8f))
    var exportTransparent by mutableStateOf(prefs.getBoolean(CanvasOptions.KEY_EXPORT_TRANSPARENT, false))

    // View transform: screen = rotate(doc * scale, rotation) + offset.
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
    /** View rotation in degrees, clockwise on screen. */
    var rotation by mutableFloatStateOf(0f)
    var viewInitialized = false
    var viewW = 0f
    var viewH = 0f

    fun refit() {
        if (viewW > 0f) fitToView(viewW, viewH)
    }

    /** Live stroke (for preview). */
    var stroke by mutableStateOf<StrokeSession?>(null)
    var transform by mutableStateOf<TransformSession?>(null)
    var clipboard: CanvasClipboard? = null

    /** Shape being dragged by a selection tool, in doc coordinates. */
    var selectionDraft by mutableStateOf<android.graphics.Path?>(null)
    var draftRevision by mutableStateOf(0)

    // Eyedropper options (Settings → Local Dream Canvas Options), read when
    // the canvas opens.
    val longPressPick = prefs.getBoolean(CanvasOptions.KEY_EYEDROPPER_LONG_PRESS, CanvasOptions.DEFAULT_LONG_PRESS)
    val loupeEnabled = prefs.getBoolean(CanvasOptions.KEY_EYEDROPPER_LOUPE, CanvasOptions.DEFAULT_LOUPE)
    val loupeZoom = prefs.getInt(CanvasOptions.KEY_LOUPE_ZOOM, CanvasOptions.DEFAULT_LOUPE_ZOOM).toFloat()

    /** Tool to return to once the eyedropper has picked a colour. */
    var toolBeforeEyedropper: CanvasTool = CanvasTool.PEN
        private set

    /** Where the eyedropper is sampling right now (screen coordinates), for the magnifier. */
    var picker by mutableStateOf<Offset?>(null)

    /** S-Pen hover position, in screen coordinates. */
    var hover by mutableStateOf<Offset?>(null)

    init {
        CanvasTool.entries.filter { it.isBrush }.forEach { t ->
            settings[t] = loadSettings(t)
        }
    }

    fun settingsFor(t: CanvasTool): BrushSettings = settings[t] ?: BrushSettings.defaultFor(t)

    val current: BrushSettings get() = settingsFor(if (tool.isBrush) tool else CanvasTool.PEN)

    fun updateSettings(t: CanvasTool, s: BrushSettings) {
        settings[t] = s
        prefs.edit {
            putString(
                "brush_${t.name}",
                JSONObject().apply {
                    put("size", s.size.toDouble())
                    put("opacity", s.opacity.toDouble())
                    put("hardness", s.hardness.toDouble())
                    put("spacing", s.spacing.toDouble())
                    put("strength", s.strength.toDouble())
                    put("pressureSize", s.pressureSize)
                    put("pressureOpacity", s.pressureOpacity)
                    put("minSize", s.minSizeRatio.toDouble())
                }.toString(),
            )
        }
    }

    private fun loadSettings(t: CanvasTool): BrushSettings {
        val d = BrushSettings.defaultFor(t)
        val raw = prefs.getString("brush_${t.name}", null) ?: return d
        return runCatching {
            val j = JSONObject(raw)
            BrushSettings(
                size = j.optDouble("size", d.size.toDouble()).toFloat(),
                opacity = j.optDouble("opacity", d.opacity.toDouble()).toFloat(),
                hardness = j.optDouble("hardness", d.hardness.toDouble()).toFloat(),
                spacing = j.optDouble("spacing", d.spacing.toDouble()).toFloat(),
                strength = j.optDouble("strength", d.strength.toDouble()).toFloat(),
                pressureSize = j.optBoolean("pressureSize", d.pressureSize),
                pressureOpacity = j.optBoolean("pressureOpacity", d.pressureOpacity),
                minSizeRatio = j.optDouble("minSize", d.minSizeRatio.toDouble()).toFloat(),
            )
        }.getOrDefault(d)
    }

    fun selectTool(t: CanvasTool) {
        if (transform != null && t != CanvasTool.MOVE) commitTransform()
        if (t == CanvasTool.EYEDROPPER && tool != CanvasTool.EYEDROPPER) {
            // Hand and transform make no sense to come back to after a pick.
            toolBeforeEyedropper = when (tool) {
                CanvasTool.HAND, CanvasTool.MOVE -> lastDrawTool()
                else -> tool
            }
        }
        tool = t
        if (t != CanvasTool.HAND && t != CanvasTool.EYEDROPPER && t != CanvasTool.MOVE) prefs.edit { putString("tool", t.name) }
        rememberDrawTool()
        if (t == CanvasTool.MOVE && transform == null) beginTransform()
    }

    fun pickColor(c: Int) {
        color = c or (0xFF shl 24)
        prefs.edit { putInt("color", color) }
    }

    /** Remembers the colour of a finished stroke in the recent palette. */
    fun pushRecent(c: Int) {
        recentColors.remove(c)
        recentColors.add(0, c)
        while (recentColors.size > 12) recentColors.removeAt(recentColors.lastIndex)
        prefs.edit { putString("recent_colors", recentColors.joinToString(",")) }
    }

    fun savePrefs() {
        prefs.edit {
            putInt("correction", correction)
            putBoolean("stylus_only", stylusOnly)
            putBoolean("pressure", pressureEnabled)
            putString("accept_mode", acceptMode.name)
            putInt("wand_tolerance", wandTolerance)
            putInt("fill_tolerance", fillTolerance)
            putBoolean("fill_sample_all", fillSampleAll)
            putFloat("mask_feather", maskFeather)
            putBoolean(CanvasOptions.KEY_EXPORT_TRANSPARENT, exportTransparent)
        }
    }

    fun screenToDoc(p: Offset): Offset = screenDeltaToDoc(p - offset)

    /** Converts a screen-space vector (a pan, say) to document space. */
    fun screenDeltaToDoc(v: Offset): Offset {
        if (rotation == 0f) return v / scale
        val r = Math.toRadians(-rotation.toDouble())
        val c = kotlin.math.cos(r).toFloat()
        val s = kotlin.math.sin(r).toFloat()
        return Offset((v.x * c - v.y * s) / scale, (v.x * s + v.y * c) / scale)
    }

    /** Rotates the view by [degrees] around the screen point [pivot]. */
    fun rotateView(degrees: Float, pivot: Offset) {
        if (degrees == 0f) return
        val r = Math.toRadians(degrees.toDouble())
        val c = kotlin.math.cos(r).toFloat()
        val s = kotlin.math.sin(r).toFloat()
        val v = offset - pivot
        offset = pivot + Offset(v.x * c - v.y * s, v.x * s + v.y * c)
        rotation = ((rotation + degrees) % 360f + 360f) % 360f
    }

    fun fitToView(viewW: Float, viewH: Float) {
        val margin = 0.94f
        val s = minOf(viewW / doc.width, viewH / doc.height) * margin
        scale = s
        rotation = 0f
        offset = Offset((viewW - doc.width * s) / 2f, (viewH - doc.height * s) / 2f)
        viewInitialized = true
    }

    // ----- Clipboard ------------------------------------------------------

    /** Copies the selected pixels of the active layer. */
    fun copySelection(): Boolean {
        val layer = doc.activeLayer ?: return false
        val sel = doc.selection
        val r = sel?.bounds ?: Rect(0, 0, doc.width, doc.height)
        val out = Bitmap.createBitmap(layer.bitmap, r.left, r.top, r.width(), r.height()).copy(Bitmap.Config.ARGB_8888, true)
        sel?.keepInside(out, r.left, r.top)
        clipboard = CanvasClipboard(out, r.left, r.top)
        return true
    }

    fun cutSelection(): Boolean {
        if (!copySelection()) return false
        doc.clearActive()
        return true
    }

    /** Pastes as a new layer at the copied position and starts moving it. */
    fun paste(): PasteResult {
        val clip = clipboard ?: return PasteResult.EMPTY
        val bmp = doc.newBitmap()
        Canvas(bmp).drawBitmap(clip.bitmap, clip.left.toFloat(), clip.top.toFloat(), null)
        doc.addLayer("Pasted", bmp) ?: return PasteResult.NO_ROOM
        doc.changeSelectionWithLastOp(null)
        selectTool(CanvasTool.MOVE)
        return PasteResult.OK
    }

    enum class PasteResult { OK, EMPTY, NO_ROOM }

    // ----- Transform ------------------------------------------------------

    fun beginTransform() {
        val layer = doc.activeLayer ?: return
        val before = layer.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val sel = doc.selection
        val floating = doc.newBitmap()
        val fc = Canvas(floating)
        fc.drawBitmap(layer.bitmap, 0f, 0f, null)
        if (sel != null) {
            sel.keepInside(floating)
            Canvas(layer.bitmap).drawBitmap(sel.mask, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
        } else {
            layer.bitmap.eraseColor(android.graphics.Color.TRANSPARENT)
        }
        transform = TransformSession(layer, floating, before, sel)
        doc.redraw()
    }

    fun commitTransform() {
        val t = transform ?: return
        val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        Canvas(t.layer.bitmap).drawBitmap(t.floating, t.matrix, p)
        val moved = !t.matrix.isIdentity
        if (moved) {
            doc.recordPixels(t.layer, Rect(0, 0, doc.width, doc.height), t.before)
            t.selectionBefore?.let { doc.changeSelectionWithLastOp(it.transformed(t.matrix)) }
        } else {
            doc.redraw()
        }
        t.floating.recycle()
        transform = null
        if (tool == CanvasTool.MOVE) tool = lastDrawTool()
    }

    fun cancelTransform() {
        val t = transform ?: return
        Canvas(t.layer.bitmap).drawBitmap(t.before, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) })
        t.floating.recycle()
        transform = null
        doc.redraw()
        if (tool == CanvasTool.MOVE) tool = lastDrawTool()
    }

    private fun lastDrawTool(): CanvasTool = runCatching {
        CanvasTool.valueOf(prefs.getString("last_draw_tool", CanvasTool.PEN.name)!!)
    }.getOrDefault(CanvasTool.PEN)

    fun rememberDrawTool() {
        if (tool.isBrush) prefs.edit { putString("last_draw_tool", tool.name) }
    }
}
