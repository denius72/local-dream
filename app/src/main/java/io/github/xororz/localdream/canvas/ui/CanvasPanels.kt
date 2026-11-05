package io.github.xororz.localdream.canvas.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.ControlPointDuplicate
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LayersClear
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xororz.localdream.canvas.CanvasDocument
import io.github.xororz.localdream.canvas.CanvasEditorState
import io.github.xororz.localdream.canvas.CanvasLayer
import kotlin.math.roundToInt

internal val PanelBg = Color(0xFF8A8A8A)
internal val BarBg = Color(0xFF4A4B4D)
internal val BarBgDark = Color(0xFF3C3D3F)
internal val BarSelected = Color(0xFF6A6B6D)
internal val BarIcon = Color(0xFFE8E8E8)
internal val MagicBlue = Color(0xFF4A8FC9)

internal fun hsv(color: Int): FloatArray = FloatArray(3).also { android.graphics.Color.colorToHSV(color, it) }

internal fun hsvToColor(h: Float, s: Float, v: Float): Int = android.graphics.Color.HSVToColor(floatArrayOf(h, s, v))

/**
 * Left colour panel, like MediBang: saturation and value bars on the left,
 * hue bar on the right, then brush size, opacity and the current colour.
 */
@Composable
fun ColorSidePanel(editor: CanvasEditorState, onColorClick: () -> Unit, onSizeClick: () -> Unit, modifier: Modifier = Modifier) {
    val color = editor.color
    // Hue survives grey colours (s = 0), so keep our own copy.
    var hsvState by remember { mutableStateOf(hsv(color)) }
    if (hsvToColor(hsvState[0], hsvState[1], hsvState[2]) != (color or (0xFF shl 24))) {
        val fresh = hsv(color)
        if (fresh[1] < 0.001f) fresh[0] = hsvState[0]
        hsvState = fresh
    }
    val (h, s, v) = Triple(hsvState[0], hsvState[1], hsvState[2])

    fun set(nh: Float, ns: Float, nv: Float) {
        hsvState = floatArrayOf(nh, ns, nv)
        editor.pickColor(hsvToColor(nh, ns, nv))
    }

    Column(
        modifier = modifier
            .width(72.dp)
            .fillMaxHeight()
            .blockTouchesBehind()
            .background(PanelBg)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(modifier = Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // Saturation: top = full colour... MediBang puts white at the top.
                GradientBar(
                    colors = listOf(Color(hsvToColor(h, 0f, v)), Color(hsvToColor(h, 1f, v))),
                    position = s,
                    onChange = { set(h, it, v) },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                GradientBar(
                    colors = listOf(Color.Black, Color(hsvToColor(h, s, 1f))),
                    position = v,
                    onChange = { set(h, s, it) },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
            GradientBar(
                colors = (0..6).map { Color(hsvToColor(360f - it * 60f, 1f, 1f)) },
                position = 1f - h / 360f,
                onChange = { set((1f - it) * 360f % 360f, s, v) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
                reversed = false,
            )
        }
        Spacer(Modifier.height(8.dp))
        val brush = editor.current
        // Centre of the brush preview dot, in root coordinates: both drags
        // measure from it.
        var dotCenter by remember { mutableStateOf(Offset.Zero) }
        DragValue(
            text = "${brush.size.roundToInt()} px",
            center = { dotCenter },
            onDrag = { dy ->
                val t = editor.tool.takeIf { it.isBrush } ?: io.github.xororz.localdream.canvas.CanvasTool.PEN
                val cur = editor.settingsFor(t)
                val factor = 1f - dy / 300f
                editor.updateSettings(t, cur.copy(size = (cur.size * factor).coerceIn(1f, 500f)))
            },
            onClick = onSizeClick,
        )
        Box(
            Modifier
                .size(28.dp)
                .onGloballyPositioned { c ->
                    dotCenter = c.positionInRoot() + Offset(c.size.width / 2f, c.size.height / 2f)
                },
            contentAlignment = Alignment.Center,
        ) {
            val d = (brush.size / 4f).coerceIn(2f, 26f)
            Box(
                Modifier
                    .size(d.dp)
                    .clip(CircleShape)
                    .background(Color(color).copy(alpha = brush.opacity)),
            )
        }
        DragValue(
            text = "${(brush.opacity * 100).roundToInt()} %",
            center = { dotCenter },
            onDrag = { dy ->
                val t = editor.tool.takeIf { it.isBrush } ?: io.github.xororz.localdream.canvas.CanvasTool.PEN
                val cur = editor.settingsFor(t)
                editor.updateSettings(t, cur.copy(opacity = (cur.opacity - dy / 400f).coerceIn(0.01f, 1f)))
            },
            onClick = onSizeClick,
        )
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(Color(color))
                .border(2.dp, Color.White.copy(alpha = 0.6f), CircleShape)
                .clickable(onClick = onColorClick),
        )
    }
}

@Composable
private fun DragValue(text: String, center: () -> Offset, onDrag: (Float) -> Unit, onClick: () -> Unit) {
    val drag by rememberUpdatedState(onDrag)
    val click by rememberUpdatedState(onClick)
    val centerRoot by rememberUpdatedState(center)
    var myPos by remember { mutableStateOf(Offset.Zero) }
    Text(
        text,
        color = Color.White,
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { myPos = it.positionInRoot() }
            .pointerInput(Unit) {
                // Like MediBang: moving away from the centre of the brush
                // preview dot raises the value in any direction, moving toward
                // it lowers it, and going past the centre keeps lowering it. While the
                // finger is on the outward side the axis follows it (pure
                // distance); past the centre the axis stays put, so the
                // projection turns negative instead of growing again.
                // onDrag takes a dy-style delta (negative = increase).
                var origin = Offset.Zero
                var axis = Offset.Zero
                var lastS = 0f
                detectDragGestures(
                    onDragStart = { p ->
                        origin = centerRoot() - myPos
                        val v0 = p - origin
                        val l0 = v0.getDistance()
                        axis = if (l0 > 0f) v0 / l0 else Offset.Zero
                        lastS = l0
                    },
                ) { change, _ ->
                    change.consume()
                    val v = change.position - origin
                    val len = v.getDistance()
                    if (axis == Offset.Zero && len > 0f) axis = v / len
                    var s = v.x * axis.x + v.y * axis.y
                    if (s > 0f && len > 0f) {
                        axis = v / len
                        s = len
                    }
                    drag(-(s - lastS))
                    lastS = s
                }
            }
            .pointerInput(Unit) { detectTapGestures { click() } }
            .padding(vertical = 10.dp),
    )
}

/**
 * Makes the whole area a touch target, so touches on gaps and padding never
 * fall through to the canvas behind the panel: a hit sibling on top keeps the
 * ones below from receiving the gesture. It only listens and never consumes,
 * since tap and drag detectors in the children cancel themselves when they see
 * their events consumed by someone else.
 */
internal fun Modifier.blockTouchesBehind(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent(PointerEventPass.Final)
    }
}

/** Vertical gradient bar with a draggable marker; position 0 = bottom, 1 = top. */
@Composable
internal fun GradientBar(
    colors: List<Color>,
    position: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    reversed: Boolean = false,
) {
    val latest by rememberUpdatedState(onChange)
    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(2.dp))
            .pointerInput(Unit) {
                detectTapGestures { p -> latest((1f - p.y / size.height).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    latest((1f - change.position.y / size.height).coerceIn(0f, 1f))
                }
            },
    ) {
        val cols = if (reversed) colors else colors.reversed()
        drawRect(Brush.verticalGradient(cols))
        val y = (1f - position) * size.height
        drawRect(Color.White, topLeft = Offset(-2f, y - 3f), size = Size(size.width + 4f, 6f))
        drawRect(Color.Black.copy(alpha = 0.6f), topLeft = Offset(-2f, y - 3f), size = Size(size.width + 4f, 6f), style = Stroke(1f))
    }
}

/** Right-hand layer list and layer actions. */
@Composable
fun LayersPanel(
    doc: CanvasDocument,
    onMessage: (String) -> Unit,
    onRename: (CanvasLayer) -> Unit,
    onImportImage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(236.dp)
            .fillMaxHeight()
            .blockTouchesBehind()
            .background(BarBgDark.copy(alpha = 0.97f)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(4.dp),
        ) {
            PanelIcon(Icons.Default.Add, "New layer") {
                if (doc.addLayer() == null) onMessage("Layer limit (${doc.maxLayers}) reached")
            }
            PanelIcon(Icons.Default.AddPhotoAlternate, "Import image as layer") {
                if (doc.canAddLayer()) onImportImage() else onMessage("Layer limit (${doc.maxLayers}) reached")
            }
            PanelIcon(Icons.Default.ControlPointDuplicate, "Duplicate") {
                if (!doc.duplicateActive()) onMessage("Layer limit (${doc.maxLayers}) reached")
            }
            PanelIcon(Icons.Default.CallMerge, "Merge down") {
                if (!doc.mergeDown()) onMessage("No layer below")
            }
            PanelIcon(Icons.Default.ArrowUpward, "Move up") { doc.moveActive(1) }
            PanelIcon(Icons.Default.ArrowDownward, "Move down") { doc.moveActive(-1) }
            PanelIcon(Icons.Default.LayersClear, "Clear") { doc.clearActive() }
            PanelIcon(Icons.Default.Delete, "Delete") {
                if (!doc.deleteActive()) onMessage("At least one layer is required")
            }
        }
        doc.activeLayer?.let { layer ->
            Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Opacity ${(layer.opacity * 100).roundToInt()}%", color = BarIcon, fontSize = 12.sp, modifier = Modifier.width(96.dp))
                Slider(
                    value = layer.opacity,
                    onValueChange = {
                        layer.opacity = it
                        doc.touch()
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Text(
            "${doc.layers.size}/${doc.maxLayers} layers",
            color = BarIcon.copy(alpha = 0.6f),
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
        // Top layer first, like every paint app.
        val ordered = doc.layers.indices.reversed().toList()
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(ordered, key = { _, i -> doc.layers[i].id }) { _, index ->
                val layer = doc.layers[index]
                LayerRow(
                    doc = doc,
                    layer = layer,
                    selected = index == doc.activeIndex,
                    onClick = { doc.selectLayer(index) },
                    onLongClick = { onRename(layer) },
                )
            }
        }
    }
}

@Composable
private fun PanelIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(icon, contentDescription = desc, tint = BarIcon, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun LayerRow(doc: CanvasDocument, layer: CanvasLayer, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) Color(0xFF3F6E9C) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = {
                layer.visible = !layer.visible
                doc.touch()
            },
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                if (layer.visible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                contentDescription = "Visibility",
                tint = BarIcon,
                modifier = Modifier.size(20.dp),
            )
        }
        val aspect = doc.width.toFloat() / doc.height
        val thumbW = if (aspect >= 1f) 52f else 52f * aspect
        val thumbH = if (aspect >= 1f) 52f / aspect else 52f
        Canvas(
            Modifier
                .padding(horizontal = 6.dp)
                .size(thumbW.dp, thumbH.dp)
                .border(1.dp, Color.Black.copy(alpha = 0.4f)),
        ) {
            doc.version
            // Checkerboard so transparency reads as transparency.
            val cell = 6.dp.toPx()
            var yy = 0f
            var row = 0
            while (yy < size.height) {
                var xx = 0f
                var col = 0
                while (xx < size.width) {
                    drawRect(
                        if ((row + col) % 2 == 0) Color(0xFFDDDDDD) else Color.White,
                        topLeft = Offset(xx, yy),
                        size = Size(cell, cell),
                    )
                    xx += cell
                    col++
                }
                yy += cell
                row++
            }
            drawIntoCanvas {
                val c = it.nativeCanvas
                c.save()
                c.scale(size.width / doc.width, size.height / doc.height)
                c.drawBitmap(layer.bitmap, 0f, 0f, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                c.restore()
            }
        }
        Column(Modifier.weight(1f)) {
            Text(layer.name, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${(layer.opacity * 100).roundToInt()}%", color = BarIcon.copy(alpha = 0.7f), fontSize = 11.sp)
        }
    }
}
