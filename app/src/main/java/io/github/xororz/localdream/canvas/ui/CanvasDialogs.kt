package io.github.xororz.localdream.canvas.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.xororz.localdream.canvas.AcceptMode
import io.github.xororz.localdream.canvas.BrushSettings
import io.github.xororz.localdream.canvas.CanvasEditorState
import io.github.xororz.localdream.canvas.CanvasTool
import io.github.xororz.localdream.canvas.ProjectInfo
import io.github.xororz.localdream.canvas.ProjectStore
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlin.math.roundToInt

// ----- Brush & canvas settings -------------------------------------------------

@Composable
fun BrushSettingsDialog(editor: CanvasEditorState, onDismiss: () -> Unit) {
    val tool = editor.tool.takeIf { it.isBrush } ?: CanvasTool.PEN
    val s = editor.settingsFor(tool)
    fun upd(n: BrushSettings) = editor.updateSettings(tool, n)
    AlertDialog(
        onDismissRequest = {
            editor.savePrefs()
            onDismiss()
        },
        title = { Text("${CanvasIcons.label(tool)} settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                LabeledSlider("Size", "${s.size.roundToInt()} px", s.size, 1f..500f) { upd(s.copy(size = it)) }
                LabeledSlider("Opacity", "${(s.opacity * 100).roundToInt()}%", s.opacity, 0.01f..1f) { upd(s.copy(opacity = it)) }
                LabeledSlider("Hardness", "${(s.hardness * 100).roundToInt()}%", s.hardness, 0f..1f) { upd(s.copy(hardness = it)) }
                if (tool == CanvasTool.BLUR || tool == CanvasTool.SMUDGE || tool == CanvasTool.AIRBRUSH) {
                    val label = if (tool == CanvasTool.AIRBRUSH) "Flow" else "Strength"
                    LabeledSlider(label, "${(s.strength * 100).roundToInt()}%", s.strength, 0.01f..1f) { upd(s.copy(strength = it)) }
                }
                LabeledSlider("Spacing", "${(s.spacing * 100).roundToInt()}%", s.spacing, 0.02f..1f) { upd(s.copy(spacing = it)) }
                if (tool != CanvasTool.BLUR && tool != CanvasTool.SMUDGE) {
                    SwitchRow("Pressure → size", s.pressureSize) { upd(s.copy(pressureSize = it)) }
                    if (s.pressureSize) {
                        LabeledSlider("Minimum size", "${(s.minSizeRatio * 100).roundToInt()}%", s.minSizeRatio, 0f..1f) {
                            upd(s.copy(minSizeRatio = it))
                        }
                    }
                    SwitchRow("Pressure → opacity", s.pressureOpacity) { upd(s.copy(pressureOpacity = it)) }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Canvas", style = MaterialTheme.typography.titleSmall)
                LabeledSlider("Correction (stabilizer)", "${editor.correction}", editor.correction.toFloat(), 0f..20f) {
                    editor.correction = it.roundToInt()
                }
                SwitchRow("Use S Pen pressure", editor.pressureEnabled) { editor.pressureEnabled = it }
                SwitchRow("Only the S Pen draws (fingers move the view)", editor.stylusOnly) { editor.stylusOnly = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                editor.savePrefs()
                onDismiss()
            }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = { upd(BrushSettings.defaultFor(tool)) }) { Text("Default") }
        },
    )
}

@Composable
internal fun LabeledSlider(label: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
        Slider(value = v.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range)
    }
}

@Composable
internal fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ----- Colour picker -------------------------------------------------------------

@Composable
fun ColorPickerDialog(editor: CanvasEditorState, onDismiss: () -> Unit) {
    var h by remember { mutableStateOf(hsv(editor.color)[0]) }
    var s by remember { mutableStateOf(hsv(editor.color)[1]) }
    var v by remember { mutableStateOf(hsv(editor.color)[2]) }
    var hex by remember { mutableStateOf(String.format("%06X", editor.color and 0xFFFFFF)) }
    val current = hsvToColor(h, s, v)
    LaunchedEffect(current) { hex = String.format("%06X", current and 0xFFFFFF) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Color") },
        text = {
            Column {
                Row(Modifier.fillMaxWidth().height(220.dp)) {
                    SvSquare(h, s, v, { ns, nv -> s = ns; v = nv }, Modifier.weight(1f).fillMaxSize())
                    Spacer(Modifier.width(10.dp))
                    GradientBar(
                        colors = (0..6).map { Color(hsvToColor(360f - it * 60f, 1f, 1f)) },
                        position = 1f - h / 360f,
                        onChange = { h = (1f - it) * 360f % 360f },
                        modifier = Modifier.width(28.dp).fillMaxSize(),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(Color(current)))
                    Spacer(Modifier.width(10.dp))
                    OutlinedTextField(
                        value = hex,
                        onValueChange = { t ->
                            hex = t.uppercase().filter { it in "0123456789ABCDEF" }.take(6)
                            if (hex.length == 6) {
                                val c = hsv(0xFF000000.toInt() or hex.toInt(16))
                                h = c[0]
                                s = c[1]
                                v = c[2]
                            }
                        },
                        prefix = { Text("#") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (editor.recentColors.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("Recent", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        editor.recentColors.forEach { c ->
                            Box(
                                Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(Color(c))
                                    .border(1.dp, Color.Gray, CircleShape)
                                    .clickable {
                                        val x = hsv(c)
                                        h = x[0]
                                        s = x[1]
                                        v = x[2]
                                    },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                editor.pickColor(current)
                onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SvSquare(h: Float, s: Float, v: Float, onChange: (Float, Float) -> Unit, modifier: Modifier) {
    val latest by rememberUpdatedState(onChange)
    Canvas(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .pointerInput(Unit) {
                detectTapGestures { p ->
                    latest((p.x / size.width).coerceIn(0f, 1f), (1f - p.y / size.height).coerceIn(0f, 1f))
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { c, _ ->
                    c.consume()
                    latest((c.position.x / size.width).coerceIn(0f, 1f), (1f - c.position.y / size.height).coerceIn(0f, 1f))
                }
            },
    ) {
        drawRect(Brush.horizontalGradient(listOf(Color.White, Color(hsvToColor(h, 1f, 1f)))))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        val c = Offset(s * size.width, (1f - v) * size.height)
        drawCircle(Color.White, 9f, c, style = Stroke(3f))
        drawCircle(Color.Black, 11f, c, style = Stroke(1f))
    }
}

// ----- Selection menu ------------------------------------------------------------

@Composable
fun SelectionMenuDialog(
    editor: CanvasEditorState,
    onAction: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val hasSel = editor.doc.selection != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Selection") },
        text = {
            Column {
                Text("Selection tool mode", style = MaterialTheme.typography.labelMedium)
                val modes = io.github.xororz.localdream.canvas.SelectionMode.entries
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    modes.forEachIndexed { i, m ->
                        SegmentedButton(
                            selected = editor.selectionMode == m,
                            onClick = { editor.selectionMode = m },
                            shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                        ) {
                            Text(
                                when (m) {
                                    io.github.xororz.localdream.canvas.SelectionMode.REPLACE -> "New"
                                    io.github.xororz.localdream.canvas.SelectionMode.ADD -> "Add"
                                    io.github.xororz.localdream.canvas.SelectionMode.SUBTRACT -> "Subtract"
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                MenuRow("Select all") { onAction("all") }
                MenuRow("Deselect", enabled = hasSel) { onAction("none") }
                MenuRow("Invert selection") { onAction("invert") }
                HorizontalDivider()
                MenuRow("Cut") { onAction("cut") }
                MenuRow("Copy") { onAction("copy") }
                MenuRow("Paste (new layer)", enabled = editor.clipboard != null) { onAction("paste") }
                MenuRow("Clear contents") { onAction("delete") }
                MenuRow("Move / transform") { onAction("transform") }
                HorizontalDivider()
                LabeledSlider("Magic wand tolerance", "${editor.wandTolerance}", editor.wandTolerance.toFloat(), 0f..128f) {
                    editor.wandTolerance = it.roundToInt()
                }
                LabeledSlider("Fill tolerance", "${editor.fillTolerance}", editor.fillTolerance.toFloat(), 0f..128f) {
                    editor.fillTolerance = it.roundToInt()
                }
                SwitchRow("Fill samples all layers", editor.fillSampleAll) { editor.fillSampleAll = it }
                LabeledSlider("Mask edge feather (AI)", "${editor.maskFeather.roundToInt()} px", editor.maskFeather, 0f..48f) {
                    editor.maskFeather = it
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                editor.savePrefs()
                onDismiss()
            }) { Text("Close") }
        },
    )
}

@Composable
internal fun MenuRow(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
    )
}

// ----- Projects ----------------------------------------------------------------------

@Composable
fun ProjectNameDialog(title: String, initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") })
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun OpenProjectDialog(onOpen: (ProjectInfo) -> Unit, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var refresh by remember { mutableStateOf(0) }
    val projects by produceState<List<ProjectInfo>?>(null, refresh) {
        value = kotlinx.coroutines.withContext(Dispatchers.IO) { ProjectStore.list(context) }
    }
    var toDelete by remember { mutableStateOf<ProjectInfo?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open project") },
        text = {
            val list = projects
            when {
                list == null -> LinearProgressIndicator(Modifier.fillMaxWidth())

                list.isEmpty() -> Text("No saved projects yet.")

                else -> LazyColumn(Modifier.height(420.dp)) {
                    items(list, key = { it.file.path }) { info ->
                        val thumb by produceState<Bitmap?>(null, info.file.path, info.modified) {
                            value = kotlinx.coroutines.withContext(Dispatchers.IO) { ProjectStore.thumbnail(info) }
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onOpen(info) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(64.dp).background(Color.LightGray), contentAlignment = Alignment.Center) {
                                thumb?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                            }
                            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                Text(info.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "${info.width}×${info.height} · " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(info.modified)),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            IconButton(onClick = { toDelete = info }) { Icon(Icons.Default.Delete, "Delete") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
    toDelete?.let { info ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete \"${info.name}\"?") },
            text = { Text("The project will be deleted. Images already in the history are not affected.") },
            confirmButton = {
                TextButton(onClick = {
                    ProjectStore.delete(info)
                    toDelete = null
                    refresh++
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } },
        )
    }
}

// ----- AI result overlay -------------------------------------------------------------

sealed class CanvasAiState {
    object Idle : CanvasAiState()
    data class Running(val progress: Float, val preview: Bitmap?) : CanvasAiState()
    data class Done(val result: Bitmap) : CanvasAiState()
    data class Error(val message: String) : CanvasAiState()
}

/**
 * Full-screen overlay while the AI works: the drawing floats on top, an arrow
 * points to the generated image below, then ✓ applies it and ✗ discards it.
 */
@Composable
fun MagicOverlay(
    source: Bitmap,
    state: CanvasAiState,
    acceptMode: AcceptMode,
    onAcceptModeChange: (AcceptMode) -> Unit,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onCancel: () -> Unit,
) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        val infinite = rememberInfiniteTransition(label = "magic")
        val bob by infinite.animateFloat(
            initialValue = -6f,
            targetValue = 6f,
            animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "bob",
        )
        val spin by infinite.animateFloat(0f, 360f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "spin")
        val pulse by infinite.animateFloat(0.85f, 1.1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")
        val aspect = source.width.toFloat() / source.height

        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xE6101418)),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // The drawing, floating.
                Text("Your drawing", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .weight(0.8f)
                        .aspectRatio(aspect, matchHeightConstraintsFirst = true)
                        .offset(y = bob.dp)
                        .shadow(16.dp, RoundedCornerShape(8.dp))
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White),
                ) {
                    Image(source.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
                Box(Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.ArrowDownward,
                        contentDescription = null,
                        tint = MagicBlue,
                        modifier = Modifier.size(40.dp).offset(y = (bob / 2).dp),
                    )
                }
                // The result (or the work in progress).
                Box(
                    Modifier
                        .weight(1.2f)
                        .aspectRatio(aspect, matchHeightConstraintsFirst = true)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF1E252D))
                        .border(2.dp, MagicBlue.copy(alpha = 0.6f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    when (state) {
                        is CanvasAiState.Done -> Image(
                            state.result.asImageBitmap(),
                            null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )

                        is CanvasAiState.Running -> {
                            state.preview?.let {
                                Image(it.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.AutoFixHigh,
                                    null,
                                    tint = Color.White,
                                    modifier = Modifier.size(56.dp).rotate(spin).scale(pulse),
                                )
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    if (state.progress > 0f) "${(state.progress * 100).roundToInt()}%" else "Preparing…",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            LinearProgressIndicator(
                                progress = { state.progress },
                                modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter),
                                color = MagicBlue,
                            )
                        }

                        is CanvasAiState.Error -> Text(state.message, color = Color(0xFFFF8A80), modifier = Modifier.padding(16.dp))

                        CanvasAiState.Idle -> Icon(
                            Icons.Default.AutoFixHigh,
                            null,
                            tint = Color.White,
                            modifier = Modifier.size(56.dp).rotate(spin).scale(pulse),
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                when (state) {
                    is CanvasAiState.Done -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("On accept:", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                            Spacer(Modifier.width(8.dp))
                            SingleChoiceSegmentedButtonRow {
                                SegmentedButton(
                                    selected = acceptMode == AcceptMode.NEW_LAYER,
                                    onClick = { onAcceptModeChange(AcceptMode.NEW_LAYER) },
                                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                                ) { Text("New layer", fontSize = 12.sp) }
                                SegmentedButton(
                                    selected = acceptMode == AcceptMode.FLATTEN,
                                    onClick = { onAcceptModeChange(AcceptMode.FLATTEN) },
                                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                                ) { Text("Flatten", fontSize = 12.sp) }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        AnimatedVisibility(visible = true, enter = fadeIn() + scaleIn()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                                FilledIconButton(
                                    onClick = onReject,
                                    modifier = Modifier.size(68.dp),
                                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFD9534F)),
                                ) { Icon(Icons.Default.Close, "Reject", modifier = Modifier.size(36.dp), tint = Color.White) }
                                FilledIconButton(
                                    onClick = onAccept,
                                    modifier = Modifier.size(68.dp),
                                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF3FA34D)),
                                ) { Icon(Icons.Default.Check, "Accept", modifier = Modifier.size(36.dp), tint = Color.White) }
                            }
                        }
                    }

                    is CanvasAiState.Error -> Button(onClick = onReject) { Text("Back to canvas") }

                    else -> Button(
                        onClick = onCancel,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF555B63)),
                    ) { Text("Cancel generation") }
                }
            }
        }
    }
}
