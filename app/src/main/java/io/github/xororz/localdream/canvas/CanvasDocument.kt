package io.github.xororz.localdream.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.graphics.createBitmap

/**
 * One raster layer. The bitmap is mutated in place by the tools; anything that
 * needs to redraw reads [CanvasDocument.version].
 */
class CanvasLayer(
    val id: Long,
    name: String,
    var bitmap: Bitmap,
    visible: Boolean = true,
    opacity: Float = 1f,
) {
    var name by mutableStateOf(name)
    var visible by mutableStateOf(visible)
    var opacity by mutableFloatStateOf(opacity)
}

/** An undoable change. Pixel edits keep only the touched rectangle. */
sealed class UndoOp {
    abstract val bytes: Long

    /** Selection before/after this op, when the op also changed the selection. */
    var selectionChange: Pair<Selection?, Selection?>? = null

    /** A selection-only change (select, add, subtract, invert, deselect). */
    class SelectionOnly : UndoOp() {
        // The masks are ALPHA_8 bitmaps the size of the document.
        override val bytes: Long get() = 64 + (selectionChange?.let { (a, b) ->
            (a?.mask?.byteCount ?: 0).toLong() + (b?.mask?.byteCount ?: 0)
        } ?: 0L)
    }

    class Pixels(val layer: CanvasLayer, val rect: Rect, var before: Bitmap, var after: Bitmap? = null) : UndoOp() {
        override val bytes: Long get() = rect.width().toLong() * rect.height() * 4 * 2
    }

    class Structure(
        val beforeLayers: List<CanvasLayer>,
        val beforeActive: Int,
        var afterLayers: List<CanvasLayer>,
        var afterActive: Int,
        /** Layers whose bitmap was swapped (merge/flatten): layer -> (before, after). */
        val swapped: Map<CanvasLayer, Pair<Bitmap, Bitmap>> = emptyMap(),
    ) : UndoOp() {
        override val bytes: Long get() = swapped.values.sumOf { it.first.byteCount.toLong() } + 64
    }
}

/**
 * Layered raster document. Layer 0 is the bottom one. Under all layers sits an
 * opaque paper colour, so a flattened export never has holes.
 */
class CanvasDocument(val width: Int, val height: Int) {
    val layers = mutableStateListOf<CanvasLayer>()
    var activeIndex by mutableIntStateOf(0)
    var paperColor by mutableIntStateOf(Color.WHITE)

    /** Bumped on every pixel change so composables redraw. */
    var version by mutableIntStateOf(0)
        private set

    /** Bumped whenever the document changes in a way worth saving. */
    var dirtyCounter by mutableIntStateOf(0)
        private set

    var selection by mutableStateOf<Selection?>(null)

    /** Area the AI regenerates (inpaint). Separate from [selection], which is for editing. */
    var inpaintMask by mutableStateOf<Selection?>(null)

    private val undoStack = ArrayDeque<UndoOp>()
    private val redoStack = ArrayDeque<UndoOp>()
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    private var nextId = 1L

    val layerBytes: Long get() = width.toLong() * height * 4

    /** How many layers fit comfortably in this process' heap. */
    val maxLayers: Int = run {
        val heap = Runtime.getRuntime().maxMemory()
        ((heap * 0.35) / layerBytes).toInt().coerceIn(4, 32)
    }

    private val undoBudget: Long = (Runtime.getRuntime().maxMemory() * 0.25).toLong()

    val activeLayer: CanvasLayer? get() = layers.getOrNull(activeIndex)

    fun touch() {
        version++
        dirtyCounter++
    }

    fun redraw() {
        version++
    }

    fun newBitmap(): Bitmap = createBitmap(width, height).apply { eraseColor(Color.TRANSPARENT) }

    fun canAddLayer(): Boolean = layers.size < maxLayers

    // ----- Layer structure --------------------------------------------------

    private inline fun structural(block: () -> Unit) {
        val before = layers.toList()
        val beforeActive = activeIndex
        block()
        pushUndo(UndoOp.Structure(before, beforeActive, layers.toList(), activeIndex))
        touch()
    }

    fun addLayerInitial(name: String, bitmap: Bitmap? = null): CanvasLayer {
        val layer = CanvasLayer(nextId++, name, bitmap ?: newBitmap())
        layers.add(layer)
        activeIndex = layers.lastIndex
        return layer
    }

    /** Adds a layer above the active one. Returns null when the layer limit is hit. */
    fun addLayer(name: String = defaultLayerName(), bitmap: Bitmap? = null, atTop: Boolean = false): CanvasLayer? {
        if (!canAddLayer()) return null
        val layer = CanvasLayer(nextId++, name, bitmap ?: newBitmap())
        structural {
            val index = if (atTop || layers.isEmpty()) layers.size else activeIndex + 1
            layers.add(index, layer)
            activeIndex = index
        }
        return layer
    }

    fun defaultLayerName(): String {
        var n = layers.size + 1
        while (layers.any { it.name == "Layer $n" }) n++
        return "Layer $n"
    }

    fun duplicateActive(): Boolean {
        val src = activeLayer ?: return false
        if (!canAddLayer()) return false
        val copy = CanvasLayer(
            nextId++,
            src.name + " copy",
            src.bitmap.copy(Bitmap.Config.ARGB_8888, true),
            src.visible,
            src.opacity,
        )
        structural {
            layers.add(activeIndex + 1, copy)
            activeIndex += 1
        }
        return true
    }

    fun deleteActive(): Boolean {
        if (layers.size <= 1) return false
        structural {
            layers.removeAt(activeIndex)
            activeIndex = activeIndex.coerceAtMost(layers.lastIndex)
        }
        return true
    }

    fun moveActive(delta: Int): Boolean {
        val target = activeIndex + delta
        if (target !in layers.indices) return false
        structural {
            val layer = layers.removeAt(activeIndex)
            layers.add(target, layer)
            activeIndex = target
        }
        return true
    }

    fun selectLayer(index: Int) {
        if (index in layers.indices) activeIndex = index
    }

    /** Merges the active layer into the one below it. */
    fun mergeDown(): Boolean {
        if (activeIndex <= 0) return false
        val top = layers[activeIndex]
        val below = layers[activeIndex - 1]
        val merged = newBitmap()
        Canvas(merged).apply {
            val p = Paint(Paint.FILTER_BITMAP_FLAG)
            if (below.visible) {
                p.alpha = (below.opacity * 255).toInt()
                drawBitmap(below.bitmap, 0f, 0f, p)
            }
            if (top.visible) {
                p.alpha = (top.opacity * 255).toInt()
                drawBitmap(top.bitmap, 0f, 0f, p)
            }
        }
        val mergedLayer = CanvasLayer(nextId++, below.name, merged, true, 1f)
        structural {
            val i = activeIndex - 1
            layers.removeAt(activeIndex)
            layers[i] = mergedLayer
            activeIndex = i
        }
        return true
    }

    /** Replaces every layer with a single flattened one. */
    fun flattenAll(name: String = "Layer 1", content: Bitmap? = null) {
        val flat = content ?: compositeVisible(includePaper = false)
        val layer = CanvasLayer(nextId++, name, flat)
        structural {
            layers.clear()
            layers.add(layer)
            activeIndex = 0
        }
    }

    /**
     * Adds [bitmap] as a new top layer and hides every other layer.
     * Returns false when there is no room for another layer.
     */
    fun addResultLayer(bitmap: Bitmap, name: String): Boolean {
        if (!canAddLayer()) return false
        val layer = CanvasLayer(nextId++, name, bitmap)
        val hiddenBefore = layers.filter { it.visible }
        structural {
            layers.add(layer)
            activeIndex = layers.lastIndex
            hiddenBefore.forEach { it.visible = false }
        }
        lastStructureOp()?.let { rememberHidden(it, hiddenBefore) }
        return true
    }

    // ----- Pixel ops --------------------------------------------------------

    /** Records a pixel edit of [layer] in [rect]; [before] holds the old pixels of that rect. */
    fun recordPixels(layer: CanvasLayer, rect: Rect, before: Bitmap) {
        pushUndo(UndoOp.Pixels(layer, Rect(rect), before))
        touch()
    }

    /** Runs [block] on the active layer and records the whole layer for undo. */
    inline fun editActiveLayer(block: (CanvasLayer) -> Unit) {
        val layer = activeLayer ?: return
        val before = layer.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        block(layer)
        recordPixels(layer, Rect(0, 0, width, height), before)
    }

    fun clearActive() {
        val sel = selection
        editActiveLayer { layer ->
            val c = Canvas(layer.bitmap)
            if (sel == null) {
                layer.bitmap.eraseColor(Color.TRANSPARENT)
            } else {
                c.drawBitmap(sel.mask, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
            }
        }
    }

    // ----- Composite --------------------------------------------------------

    fun compositeVisible(includePaper: Boolean, out: Bitmap? = null): Bitmap {
        val result = out ?: newBitmap()
        val c = Canvas(result)
        if (includePaper) c.drawColor(paperColor) else result.eraseColor(Color.TRANSPARENT)
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        layers.forEach { layer ->
            if (!layer.visible) return@forEach
            p.alpha = (layer.opacity * 255).toInt()
            c.drawBitmap(layer.bitmap, 0f, 0f, p)
        }
        return result
    }

    /** True when no visible layer has any visible pixel. */
    fun isBlank(): Boolean = layers.none { layer ->
        if (!layer.visible || layer.opacity <= 0f) return@none false
        val px = IntArray(width)
        for (y in 0 until height step 2) {
            layer.bitmap.getPixels(px, 0, width, 0, y, width, 1)
            for (v in px) if ((v ushr 24) > 8) return@none true
        }
        false
    }

    /** Composited colour at a document pixel, over the paper. */
    fun sampleColor(x: Int, y: Int): Int {
        if (x !in 0 until width || y !in 0 until height) return paperColor
        var r = Color.red(paperColor) / 255f
        var g = Color.green(paperColor) / 255f
        var b = Color.blue(paperColor) / 255f
        layers.forEach { layer ->
            if (!layer.visible) return@forEach
            val c = layer.bitmap.getPixel(x, y)
            val a = (c ushr 24) / 255f * layer.opacity
            r = r * (1 - a) + Color.red(c) / 255f * a
            g = g * (1 - a) + Color.green(c) / 255f * a
            b = b * (1 - a) + Color.blue(c) / 255f * a
        }
        return Color.rgb((r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt())
    }

    // ----- Selection --------------------------------------------------------

    /** Sets the selection as an undoable step. */
    fun changeSelection(new: Selection?) {
        val old = selection
        if (old === new) return
        selection = new
        pushUndo(UndoOp.SelectionOnly().apply { selectionChange = old to new })
        touch()
    }

    /**
     * Sets the selection as part of the op just recorded (e.g. a move that
     * carries its selection along), so one undo reverts both.
     */
    fun changeSelectionWithLastOp(new: Selection?) {
        val old = selection
        if (old === new) return
        selection = new
        val last = undoStack.lastOrNull()
        if (last == null) {
            pushUndo(UndoOp.SelectionOnly().apply { selectionChange = old to new })
        } else {
            last.selectionChange = (last.selectionChange?.first ?: old) to new
        }
    }

    // ----- Undo -------------------------------------------------------------

    private fun pushUndo(op: UndoOp) {
        undoStack.addLast(op)
        redoStack.clear()
        var total = undoStack.sumOf { it.bytes }
        while (undoStack.size > 1 && (total > undoBudget || undoStack.size > 60)) {
            total -= undoStack.removeFirst().bytes
        }
        refreshUndoFlags()
    }

    fun clearHistory() {
        undoStack.clear()
        redoStack.clear()
        refreshUndoFlags()
    }

    private fun refreshUndoFlags() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }

    fun undo() {
        val op = undoStack.removeLastOrNull() ?: return
        when (op) {
            is UndoOp.Pixels -> {
                val r = op.rect
                if (op.after == null) {
                    op.after = Bitmap.createBitmap(op.layer.bitmap, r.left, r.top, r.width(), r.height())
                }
                Canvas(op.layer.bitmap).drawBitmap(op.before, null, r, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) })
            }

            is UndoOp.Structure -> {
                layers.clear()
                layers.addAll(op.beforeLayers)
                activeIndex = op.beforeActive.coerceIn(0, (layers.size - 1).coerceAtLeast(0))
                op.swapped.forEach { (layer, pair) -> layer.bitmap = pair.first }
                restoreVisibility(op, undo = true)
            }

            is UndoOp.SelectionOnly -> Unit
        }
        op.selectionChange?.let { selection = it.first }
        redoStack.addLast(op)
        refreshUndoFlags()
        touch()
    }

    fun redo() {
        val op = redoStack.removeLastOrNull() ?: return
        when (op) {
            is UndoOp.Pixels -> {
                val after = op.after ?: return
                Canvas(op.layer.bitmap).drawBitmap(after, null, op.rect, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) })
            }

            is UndoOp.Structure -> {
                layers.clear()
                layers.addAll(op.afterLayers)
                activeIndex = op.afterActive.coerceIn(0, (layers.size - 1).coerceAtLeast(0))
                op.swapped.forEach { (layer, pair) -> layer.bitmap = pair.second }
                restoreVisibility(op, undo = false)
            }

            is UndoOp.SelectionOnly -> Unit
        }
        op.selectionChange?.let { selection = it.second }
        undoStack.addLast(op)
        refreshUndoFlags()
        touch()
    }

    // Accepting a result hides the older layers; undoing it shows them again.
    private val hiddenByResult = mutableMapOf<UndoOp.Structure, List<CanvasLayer>>()

    fun rememberHidden(op: UndoOp.Structure, hidden: List<CanvasLayer>) {
        hiddenByResult[op] = hidden
    }

    private fun restoreVisibility(op: UndoOp.Structure, undo: Boolean) {
        hiddenByResult[op]?.forEach { it.visible = undo }
    }

    internal fun lastStructureOp(): UndoOp.Structure? = undoStack.lastOrNull() as? UndoOp.Structure

    /** Replaces the whole document content (used by project load). */
    fun replaceAll(newLayers: List<CanvasLayer>, active: Int, paper: Int) {
        layers.clear()
        layers.addAll(newLayers)
        nextId = (newLayers.maxOfOrNull { it.id } ?: 0L) + 1
        activeIndex = active.coerceIn(0, (layers.size - 1).coerceAtLeast(0))
        paperColor = paper
        selection = null
        inpaintMask = null
        clearHistory()
        version++
    }

    fun newLayerObject(name: String, bitmap: Bitmap, visible: Boolean, opacity: Float): CanvasLayer = CanvasLayer(nextId++, name, bitmap, visible, opacity)
}
