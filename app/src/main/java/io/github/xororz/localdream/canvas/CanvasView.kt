package io.github.xororz.localdream.canvas

import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private enum class GestureMode { TOOL, VIEW }

private const val ABORT_WINDOW_MS = 280L
private const val TAP_MAX_MS = 320L
private const val ROTATE_START_DEG = 12f
private const val ROTATE_SNAP_DEG = 6f

@Composable
fun CanvasView(
    editor: CanvasEditorState,
    enabled: Boolean,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val doc = editor.doc
    val paints = remember { CanvasPaints() }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                editor.viewW = size.width.toFloat()
                editor.viewH = size.height.toFloat()
                if (!editor.viewInitialized && size.width > 0) {
                    editor.fitToView(size.width.toFloat(), size.height.toFloat())
                }
            }
            .pointerInput(editor) {
                // S-Pen hover: show the brush outline without touching.
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        val c = e.changes.firstOrNull() ?: continue
                        val isPen = c.type == PointerType.Stylus || c.type == PointerType.Eraser
                        editor.hover = when {
                            !isPen -> null
                            e.type == PointerEventType.Exit -> null
                            else -> c.position
                        }
                    }
                }
            }
            .pointerInput(editor, enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val isPen = down.type == PointerType.Stylus || down.type == PointerType.Eraser
                    val tool = if (down.type == PointerType.Eraser) CanvasTool.ERASER else editor.tool
                    val viewOnly = tool == CanvasTool.HAND || (editor.stylusOnly && !isPen && tool != CanvasTool.MOVE)
                    var mode = if (viewOnly) GestureMode.VIEW else GestureMode.TOOL
                    val start = down.uptimeMillis
                    var maxPointers = 1
                    var travelled = 0f
                    val action: ToolAction? = if (mode == GestureMode.TOOL) {
                        ToolAction.start(editor, tool, down, isPen, onMessage)
                    } else {
                        null
                    }
                    if (mode == GestureMode.TOOL && action == null) mode = GestureMode.VIEW
                    var lastTime = start
                    // Twist of this gesture so far. Rotation waits for a clear
                    // twist, so a plain pinch zoom never tilts the canvas.
                    var twist = 0f
                    var rotating = false
                    var lastCentroid = Offset.Unspecified

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        lastTime = event.changes.maxOf { it.uptimeMillis }
                        if (pressed == 0) {
                            event.changes.forEach { it.consume() }
                            if (mode == GestureMode.TOOL) action?.end(event.changes.firstOrNull { it.id == down.id } ?: event.changes.first())
                            break
                        }
                        maxPointers = max(maxPointers, pressed)

                        // A second finger right after the first: it was a pinch or a
                        // two-finger tap, not a stroke.
                        if (pressed >= 2 && mode == GestureMode.TOOL && action != null && action.handlesMultiTouch.not()) {
                            // A resting palm must not kill a pen stroke.
                            if (!isPen && lastTime - start < ABORT_WINDOW_MS) {
                                action.cancel()
                                mode = GestureMode.VIEW
                            }
                        }

                        when (mode) {
                            GestureMode.VIEW -> {
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                val centroid = event.calculateCentroid(useCurrent = true)
                                travelled += pan.getDistance()
                                if (centroid != Offset.Unspecified) {
                                    val newScale = (editor.scale * zoom).coerceIn(0.05f, 40f)
                                    val k = newScale / editor.scale
                                    editor.offset = centroid - (centroid - editor.offset) * k + pan
                                    editor.scale = newScale
                                    lastCentroid = centroid
                                    if (pressed >= 2) {
                                        val rot = event.calculateRotation()
                                        if (rotating) {
                                            editor.rotateView(rot, centroid)
                                        } else {
                                            twist += rot
                                            if (abs(twist) > ROTATE_START_DEG) {
                                                rotating = true
                                                editor.rotateView(twist, centroid)
                                            }
                                        }
                                    }
                                }
                                event.changes.forEach { it.consume() }
                            }

                            GestureMode.TOOL -> {
                                action?.update(event, down)
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }

                    // Snap to the nearest quarter turn when close to it.
                    if (rotating && lastCentroid != Offset.Unspecified) {
                        val nearest = Math.round(editor.rotation / 90f) * 90f
                        val diff = nearest - editor.rotation
                        if (abs(diff) <= ROTATE_SNAP_DEG) editor.rotateView(diff, lastCentroid)
                    }

                    // Two-finger tap undoes, three-finger tap redoes.
                    if (mode == GestureMode.VIEW && !rotating && maxPointers >= 2 && lastTime - start < TAP_MAX_MS && travelled < 24f) {
                        if (maxPointers == 2) doc.undo() else doc.redo()
                    }
                }
            },
    ) {
        // Read state so the canvas redraws when any of it changes.
        doc.version
        val scale = editor.scale
        val off = editor.offset
        val stroke = editor.stroke
        val transform = editor.transform
        transform?.revision
        editor.draftRevision
        val maskDraft = editor.maskDraft
        val selection = doc.selection
        val inpaintMask = doc.inpaintMask
        val hover = editor.hover

        drawIntoCanvas { cc ->
            val c = cc.nativeCanvas
            c.drawColor(0xFF3A3A3A.toInt())
            c.save()
            c.translate(off.x, off.y)
            c.rotate(editor.rotation)
            c.scale(scale, scale)

            paints.paper.color = doc.paperColor
            c.drawRect(0f, 0f, doc.width.toFloat(), doc.height.toFloat(), paints.paper)

            val filter = scale < 2f
            paints.layer.isFilterBitmap = filter
            for (layer in doc.layers) {
                if (!layer.visible) continue
                val alpha = (layer.opacity * 255).toInt()
                if (stroke != null && stroke.layer === layer && stroke.usesBuffer && stroke.bufferBitmap != null) {
                    paints.saveLayer.alpha = alpha
                    c.saveLayer(0f, 0f, doc.width.toFloat(), doc.height.toFloat(), paints.saveLayer)
                    paints.layer.alpha = 255
                    c.drawBitmap(layer.bitmap, 0f, 0f, paints.layer)
                    paints.stroke.color = stroke.strokeColor
                    paints.stroke.alpha = (stroke.strokeOpacity * 255).toInt()
                    paints.stroke.xfermode = if (stroke.tool == CanvasTool.ERASER) paints.dstOut else null
                    c.drawBitmap(stroke.bufferBitmap, 0f, 0f, paints.stroke)
                    c.restore()
                } else {
                    paints.layer.alpha = alpha
                    c.drawBitmap(layer.bitmap, 0f, 0f, paints.layer)
                }
                if (transform != null && transform.layer === layer) {
                    paints.layer.alpha = alpha
                    paints.layer.isFilterBitmap = true
                    c.drawBitmap(transform.floating, transform.matrix, paints.layer)
                    paints.layer.isFilterBitmap = filter
                }
            }

            // Inpaint mask = what the AI will regenerate: tint it red.
            (maskDraft ?: inpaintMask?.mask)?.let { c.drawBitmap(it, 0f, 0f, paints.maskTint) }

            // Selection: dim the outside, outline the inside.
            if (selection != null) {
                c.drawBitmap(selection.outsideOverlay, 0f, 0f, paints.selectionTint)
                selection.outline?.let { path ->
                    val shown = if (transform != null && transform.selectionBefore === selection) {
                        Path(path).apply { transform(transform.matrix) }
                    } else {
                        path
                    }
                    paints.antsWhite.strokeWidth = 1.5f / scale
                    paints.antsBlack.strokeWidth = 1.5f / scale
                    paints.antsBlack.pathEffect = DashPathEffect(floatArrayOf(6f / scale, 6f / scale), 0f)
                    c.drawPath(shown, paints.antsWhite)
                    c.drawPath(shown, paints.antsBlack)
                }
            }
            editor.selectionDraft?.let { path ->
                paints.antsWhite.strokeWidth = 1.5f / scale
                paints.antsBlack.strokeWidth = 1.5f / scale
                paints.antsBlack.pathEffect = DashPathEffect(floatArrayOf(6f / scale, 6f / scale), 0f)
                c.drawPath(path, paints.antsWhite)
                c.drawPath(path, paints.antsBlack)
            }
            if (transform != null) {
                val r = android.graphics.RectF(0f, 0f, doc.width.toFloat(), doc.height.toFloat())
                transform.selectionBefore?.bounds?.let { r.set(it) }
                val box = Path().apply { addRect(r, Path.Direction.CW); transform(transform.matrix) }
                paints.transformBox.strokeWidth = 2f / scale
                c.drawPath(box, paints.transformBox)
            }
            c.restore()

            // Brush outline under a hovering pen.
            if (hover != null && editor.tool.isBrush && stroke == null) {
                val radius = max(editor.current.size * scale / 2f, 2f)
                c.drawCircle(hover.x, hover.y, radius, paints.cursorWhite)
                c.drawCircle(hover.x, hover.y, radius, paints.cursorBlack)
            }
        }
    }
}

private class CanvasPaints {
    val paper = Paint()
    val layer = Paint(Paint.FILTER_BITMAP_FLAG)
    val saveLayer = Paint()
    val stroke = Paint(Paint.FILTER_BITMAP_FLAG)
    val dstOut = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    val selectionTint = Paint().apply { color = 0x553D6BFF }
    val maskTint = Paint().apply { color = 0x66FF3B30 }
    val antsWhite = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = android.graphics.Color.WHITE
    }
    val antsBlack = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = android.graphics.Color.BLACK
    }
    val transformBox = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF4A90E2.toInt()
    }
    val cursorWhite = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = 0xAAFFFFFF.toInt()
    }
    val cursorBlack = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
        color = 0xFF000000.toInt()
    }
}

/** One gesture of a tool, fed with pointer changes. */
private abstract class ToolAction {
    open val handlesMultiTouch: Boolean = false
    abstract fun update(event: PointerEvent, down: PointerInputChange)
    abstract fun end(change: PointerInputChange)
    abstract fun cancel()

    companion object {
        fun start(
            editor: CanvasEditorState,
            tool: CanvasTool,
            down: PointerInputChange,
            isPen: Boolean,
            onMessage: (String) -> Unit,
        ): ToolAction? {
            val doc = editor.doc
            val layer = doc.activeLayer
            val needsVisibleLayer = (tool.isBrush && tool != CanvasTool.INPAINT) || tool == CanvasTool.FILL || tool == CanvasTool.MOVE
            if (needsVisibleLayer && (layer == null || !layer.visible)) {
                onMessage("The selected layer is hidden")
                return null
            }
            return when (tool) {
                CanvasTool.INPAINT -> MaskBrushAction(editor, down)

                CanvasTool.PEN, CanvasTool.AIRBRUSH, CanvasTool.ERASER, CanvasTool.BLUR, CanvasTool.SMUDGE ->
                    BrushAction(editor, tool, down, isPen)

                CanvasTool.EYEDROPPER -> EyedropperAction(editor, down)

                CanvasTool.FILL -> TapAction(down) { p ->
                    val d = editor.screenToDoc(p)
                    bucketFill(
                        doc, layer!!, d.x.toInt(), d.y.toInt(), editor.color,
                        editor.current.opacity, editor.fillTolerance, editor.fillSampleAll,
                    )
                }

                CanvasTool.SELECT_WAND -> TapAction(down) { p ->
                    val d = editor.screenToDoc(p)
                    val src = layer?.bitmap ?: return@TapAction
                    val sel = Selection.magicWand(src, d.x.toInt(), d.y.toInt(), editor.wandTolerance)
                    doc.changeSelection(Selection.combine(doc.selection, sel, editor.selectionMode))
                    doc.redraw()
                }

                CanvasTool.SELECT_RECT -> RectSelectAction(editor, down)

                CanvasTool.SELECT_LASSO -> LassoAction(editor, down)

                CanvasTool.MOVE -> MoveAction(editor)

                CanvasTool.HAND -> null
            }
        }
    }
}

private class BrushAction(
    private val editor: CanvasEditorState,
    tool: CanvasTool,
    down: PointerInputChange,
    private val isPen: Boolean,
) : ToolAction() {
    private val session = StrokeSession(
        doc = editor.doc,
        layer = editor.doc.activeLayer!!,
        tool = tool,
        settings = editor.settingsFor(tool),
        color = editor.color,
        correction = editor.correction,
        pressureEnabled = isPen && editor.pressureEnabled,
    )

    private var lastPressure = if (isPen) down.pressure else 1f

    init {
        editor.stroke = session
        val d = editor.screenToDoc(down.position)
        session.begin(d.x, d.y, lastPressure)
        refresh()
    }

    private fun refresh() {
        session.syncPreview()
        editor.doc.redraw()
    }

    override fun update(event: PointerEvent, down: PointerInputChange) {
        val changes = event.changes
        val ch = changes.firstOrNull { it.id == down.id } ?: return
        val hist = ch.historical
        val p1 = if (isPen) ch.pressure else 1f
        val p0 = lastPressure
        lastPressure = p1
        hist.forEachIndexed { i, h ->
            val f = (i + 1f) / (hist.size + 1f)
            val d = editor.screenToDoc(h.position)
            session.moveTo(d.x, d.y, p0 + (p1 - p0) * f)
        }
        val d = editor.screenToDoc(ch.position)
        session.moveTo(d.x, d.y, p1)
        refresh()
    }

    override fun end(change: PointerInputChange) {
        val d = editor.screenToDoc(change.position)
        session.endAt(d.x, d.y)
        session.finish()
        session.releaseBefore()
        editor.stroke = null
        if (session.tool == CanvasTool.PEN || session.tool == CanvasTool.AIRBRUSH) editor.pushRecent(editor.color)
    }

    override fun cancel() {
        session.cancel()
        session.releaseBefore()
        editor.stroke = null
    }
}

private class EyedropperAction(private val editor: CanvasEditorState, down: PointerInputChange) : ToolAction() {
    init {
        pick(down.position)
    }

    private fun pick(p: Offset) {
        val d = editor.screenToDoc(p)
        editor.pickColor(editor.doc.sampleColor(d.x.toInt(), d.y.toInt()))
    }

    override fun update(event: PointerEvent, down: PointerInputChange) {
        val changes = event.changes
        changes.firstOrNull { it.id == down.id }?.let { pick(it.position) }
    }

    override fun end(change: PointerInputChange) = Unit
    override fun cancel() = Unit
}

private class TapAction(down: PointerInputChange, private val onTap: (Offset) -> Unit) : ToolAction() {
    private val start = down.position
    private var moved = false

    override fun update(event: PointerEvent, down: PointerInputChange) {
        val changes = event.changes
        val ch = changes.firstOrNull { it.id == down.id } ?: return
        if ((ch.position - start).getDistance() > 24f) moved = true
    }

    override fun end(change: PointerInputChange) {
        if (!moved) onTap(start)
    }

    override fun cancel() {
        moved = true
    }
}

private class RectSelectAction(private val editor: CanvasEditorState, down: PointerInputChange) : ToolAction() {
    private val a = editor.screenToDoc(down.position)
    private var b = a

    override fun update(event: PointerEvent, down: PointerInputChange) {
        val changes = event.changes
        val ch = changes.firstOrNull { it.id == down.id } ?: return
        b = editor.screenToDoc(ch.position)
        editor.selectionDraft = rectPath()
        editor.draftRevision++
    }

    private fun rectPath(): Path {
        val doc = editor.doc
        val l = min(a.x, b.x).coerceIn(0f, doc.width.toFloat())
        val t = min(a.y, b.y).coerceIn(0f, doc.height.toFloat())
        val r = max(a.x, b.x).coerceIn(0f, doc.width.toFloat())
        val bb = max(a.y, b.y).coerceIn(0f, doc.height.toFloat())
        return Path().apply { addRect(l, t, r, bb, Path.Direction.CW) }
    }

    override fun end(change: PointerInputChange) {
        val doc = editor.doc
        editor.selectionDraft = null
        if (abs(a.x - b.x) < 2f || abs(a.y - b.y) < 2f) {
            if (editor.selectionMode == SelectionMode.REPLACE) doc.changeSelection(null)
        } else {
            val sel = Selection.fromPath(rectPath(), doc.width, doc.height)
            doc.changeSelection(Selection.combine(doc.selection, sel, editor.selectionMode))
        }
        doc.redraw()
    }

    override fun cancel() {
        editor.selectionDraft = null
    }
}

private class LassoAction(private val editor: CanvasEditorState, down: PointerInputChange) : ToolAction() {
    private val path = Path()
    private var count = 1

    init {
        val d = editor.screenToDoc(down.position)
        path.moveTo(d.x, d.y)
    }

    override fun update(event: PointerEvent, down: PointerInputChange) {
        val changes = event.changes
        val ch = changes.firstOrNull { it.id == down.id } ?: return
        ch.historical.forEach {
            val d = editor.screenToDoc(it.position)
            path.lineTo(d.x, d.y)
            count++
        }
        val d = editor.screenToDoc(ch.position)
        path.lineTo(d.x, d.y)
        count++
        editor.selectionDraft = path
        editor.draftRevision++
    }

    override fun end(change: PointerInputChange) {
        val doc = editor.doc
        editor.selectionDraft = null
        if (count < 4) {
            if (editor.selectionMode == SelectionMode.REPLACE) doc.changeSelection(null)
        } else {
            path.close()
            val sel = Selection.fromPath(path, doc.width, doc.height)
            doc.changeSelection(Selection.combine(doc.selection, sel, editor.selectionMode))
        }
        doc.redraw()
    }

    override fun cancel() {
        editor.selectionDraft = null
    }
}

/** Drag moves the floating pixels; two fingers scale and rotate them. */
private class MoveAction(private val editor: CanvasEditorState) : ToolAction() {
    override val handlesMultiTouch: Boolean = true

    init {
        if (editor.transform == null) editor.beginTransform()
    }

    override fun update(event: PointerEvent, down: PointerInputChange) {
        val changes = event.changes
        val t = editor.transform ?: return
        val pan = editor.screenDeltaToDoc(event.calculatePan())
        val zoom = event.calculateZoom()
        val rot = event.calculateRotation()
        val centroid = event.calculateCentroid(useCurrent = true)
        t.matrix.postTranslate(pan.x, pan.y)
        if (centroid != Offset.Unspecified && (zoom != 1f || rot != 0f)) {
            val cd = editor.screenToDoc(centroid)
            t.matrix.postScale(zoom, zoom, cd.x, cd.y)
            t.matrix.postRotate(rot, cd.x, cd.y)
        }
        t.revision++
    }

    override fun end(change: PointerInputChange) = Unit
    override fun cancel() = Unit
}

/** Paints (or erases) the inpaint mask ([CanvasDocument.inpaintMask]). */
private class MaskBrushAction(private val editor: CanvasEditorState, down: PointerInputChange) : ToolAction() {
    private val doc = editor.doc
    private val mask: android.graphics.Bitmap =
        doc.inpaintMask?.mask?.copy(android.graphics.Bitmap.Config.ALPHA_8, true)
            ?: Selection.empty(doc.width, doc.height)
    private val canvas = android.graphics.Canvas(mask)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = android.graphics.Color.BLACK
        strokeWidth = editor.settingsFor(CanvasTool.INPAINT).size
        if (editor.maskErase) xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private var last = editor.screenToDoc(down.position)

    init {
        canvas.drawPoint(last.x, last.y, paint)
        editor.maskDraft = mask
        editor.draftRevision++
    }

    override fun update(event: PointerEvent, down: PointerInputChange) {
        val ch = event.changes.firstOrNull { it.id == down.id } ?: return
        (ch.historical.map { it.position } + ch.position).forEach { p ->
            val d = editor.screenToDoc(p)
            canvas.drawLine(last.x, last.y, d.x, d.y, paint)
            last = d
        }
        editor.draftRevision++
    }

    override fun end(change: PointerInputChange) {
        editor.maskDraft = null
        doc.inpaintMask = Selection.fromMask(mask, null)
        doc.redraw()
    }

    override fun cancel() {
        editor.maskDraft = null
        doc.redraw()
    }
}
