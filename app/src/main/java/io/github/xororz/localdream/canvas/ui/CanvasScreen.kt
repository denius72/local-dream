package io.github.xororz.localdream.canvas.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.scale
import io.github.xororz.localdream.canvas.AcceptMode
import io.github.xororz.localdream.canvas.CanvasDocument
import io.github.xororz.localdream.canvas.CanvasEditorState
import io.github.xororz.localdream.canvas.CanvasGenParams
import io.github.xororz.localdream.canvas.CanvasLayer
import io.github.xororz.localdream.canvas.CanvasTool
import io.github.xororz.localdream.canvas.CanvasView
import io.github.xororz.localdream.canvas.ProjectStore
import io.github.xororz.localdream.canvas.Selection
import io.github.xororz.localdream.canvas.SelectionMode
import io.github.xororz.localdream.utils.A1111Params
import io.github.xororz.localdream.utils.PngMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * What the canvas needs from the generation screen. Keeps the canvas free of
 * service / backend details.
 */
class CanvasHost(
    val modelName: String,
    val width: Int,
    val height: Int,
    val canImg2img: Boolean,
    val canInpaint: Boolean,
    val isBusy: () -> Boolean,
    /** Current AI state; must read snapshot state so the overlay recomposes. */
    val aiState: () -> CanvasAiState,
    /** Starts a run. [image] null = txt2img. Returns false if it could not start. */
    val generate: (image: Bitmap?, mask: Bitmap?) -> Boolean,
    val cancel: () -> Unit,
    val resetAi: () -> Unit,
    val currentParams: () -> CanvasGenParams,
    val applyParams: (CanvasGenParams) -> Unit,
    val applyA1111: (A1111Params) -> Unit,
    val promptEditor: @Composable () -> Unit,
    val openAdvanced: () -> Unit,
    val saveToGallery: suspend (Bitmap) -> Boolean,
)

private fun newDocument(w: Int, h: Int, base: Bitmap?): CanvasDocument = CanvasDocument(w, h).apply {
    if (base != null) {
        val fitted = if (base.width != w || base.height != h) base.scale(w, h) else base
        addLayerInitial("Base", fitted.copy(Bitmap.Config.ARGB_8888, true))
    }
    addLayerInitial("Layer 1")
}

/** Scales [src] to fit inside the document, centred, as a new layer bitmap. */
private fun fitIntoDoc(doc: CanvasDocument, src: Bitmap): Bitmap {
    val out = doc.newBitmap()
    val k = minOf(doc.width.toFloat() / src.width, doc.height.toFloat() / src.height)
    val w = src.width * k
    val h = src.height * k
    val l = (doc.width - w) / 2f
    val t = (doc.height - h) / 2f
    Canvas(out).drawBitmap(src, null, RectF(l, t, l + w, t + h), Paint(Paint.FILTER_BITMAP_FLAG))
    return out
}

@Composable
fun CanvasScreen(host: CanvasHost, initialImage: Bitmap?, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var doc by remember { mutableStateOf(newDocument(host.width, host.height, initialImage)) }
    val editor = remember(doc) { CanvasEditorState(context, doc) }
    var projectName by remember { mutableStateOf<String?>(null) }
    var savedCounter by remember(doc) { mutableIntStateOf(doc.dirtyCounter) }
    val isDirty = doc.dirtyCounter != savedCounter

    var showColorPanel by remember { mutableStateOf(true) }
    var showLayers by remember { mutableStateOf(false) }
    var showBrushSettings by remember { mutableStateOf(false) }
    var showColorPicker by remember { mutableStateOf(false) }
    var showSelectionMenu by remember { mutableStateOf(false) }
    var showProjectMenu by remember { mutableStateOf(false) }
    var showGenSettings by remember { mutableStateOf(false) }
    var showOpen by remember { mutableStateOf(false) }
    var nameDialog by remember { mutableStateOf<String?>(null) } // "save" | "saveQuit"
    var confirmExit by remember { mutableStateOf(false) }
    var confirmNew by remember { mutableStateOf(false) }
    var renameLayer by remember { mutableStateOf<CanvasLayer?>(null) }
    var magicSource by remember { mutableStateOf<Bitmap?>(null) }
    var noRoomResult by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    fun saveProject(name: String, then: () -> Unit = {}) {
        val clean = ProjectStore.sanitize(name)
        editor.commitTransform()
        busy = true
        scope.launch {
            val ok = runCatching {
                withContext(Dispatchers.IO) {
                    ProjectStore.save(context, clean, doc, doc.activeIndex, host.currentParams())
                }
            }
            busy = false
            ok.onSuccess {
                projectName = clean
                savedCounter = doc.dirtyCounter
                toast("Project \"$clean\" saved")
                then()
            }.onFailure { toast("Failed to save: ${it.message}") }
        }
    }

    fun save(then: () -> Unit = {}) {
        val name = projectName
        if (name == null) nameDialog = if (then === onClose) "saveQuit" else "save" else saveProject(name, then)
    }

    fun requestExit() {
        editor.cancelTransform()
        if (isDirty) confirmExit = true else onClose()
    }

    fun runMagic() {
        editor.commitTransform()
        if (host.isBusy()) {
            toast("A generation is already running")
            return
        }
        val flat = doc.compositeVisible(includePaper = true)
        val blank = doc.isBlank()
        if (!blank && !host.canImg2img) toast("img2img is disabled for this model: generating from the prompt only")
        val useImage = !blank && host.canImg2img
        val sel = doc.selection
        val mask = if (useImage && sel != null) {
            if (host.canInpaint) sel.toInpaintMask(editor.maskFeather) else null.also { toast("This model does not use masks; the selection was ignored") }
        } else {
            null
        }
        if (editor.acceptMode == AcceptMode.NEW_LAYER && !doc.canAddLayer()) {
            toast("No free layers (${doc.maxLayers}): you will need to flatten on accept")
        }
        if (host.generate(if (useImage) flat else null, mask)) magicSource = flat
    }

    fun applyResult(result: Bitmap, mode: AcceptMode) {
        val bmp = if (result.width != doc.width || result.height != doc.height) {
            result.scale(doc.width, doc.height)
        } else {
            result
        }.copy(Bitmap.Config.ARGB_8888, true)
        when (mode) {
            AcceptMode.NEW_LAYER -> {
                val n = doc.layers.count { it.name.startsWith("AI ") } + 1
                if (!doc.addResultLayer(bmp, "AI $n")) {
                    noRoomResult = bmp
                    return
                }
            }

            AcceptMode.FLATTEN -> doc.flattenAll("Layer 1", bmp)
        }
        host.resetAi()
        magicSource = null
    }

    val importImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } }.getOrNull()
            }
            if (bmp == null) {
                toast("Could not open the image")
                return@launch
            }
            if (doc.addLayer("Image", fitIntoDoc(doc, bmp)) == null) toast("Layer limit (${doc.maxLayers}) reached")
        }
    }

    val loadPromptLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { PngMetadata.readText(it) }
                }.getOrNull()?.get(PngMetadata.KEY_PARAMETERS)
            }
            val parsed = text?.let { A1111Params.parse(it) }
            if (parsed == null) {
                toast("This image has no saved prompt")
            } else {
                host.applyA1111(parsed)
                toast("Prompt and parameters loaded")
            }
        }
    }

    BackHandler(enabled = magicSource == null) {
        when {
            editor.transform != null -> editor.cancelTransform()
            showLayers -> showLayers = false
            else -> requestExit()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(BarBgDark),
    ) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            TopToolBar(editor, onReselect = { showBrushSettings = true })
            OptionsBar(editor, onOpenBrush = { showBrushSettings = true }, onMessage = ::toast)
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clipToBounds(),
            ) {
                CanvasView(editor = editor, enabled = magicSource == null, onMessage = ::toast)
                androidx.compose.animation.AnimatedVisibility(
                    visible = showColorPanel,
                    enter = slideInHorizontally { -it },
                    exit = slideOutHorizontally { -it },
                    modifier = Modifier.align(Alignment.CenterStart).padding(top = 40.dp),
                ) {
                    ColorSidePanel(editor, onColorClick = { showColorPicker = true }, onSizeClick = { showBrushSettings = true })
                }
                IconButton(
                    onClick = { showColorPanel = !showColorPanel },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .size(36.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(alpha = 0.85f)),
                ) {
                    Icon(
                        if (showColorPanel) Icons.AutoMirrored.Filled.KeyboardArrowLeft else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Color panel",
                        tint = Color.DarkGray,
                    )
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = showLayers,
                    enter = slideInHorizontally { it },
                    exit = slideOutHorizontally { it },
                    modifier = Modifier.align(Alignment.CenterEnd),
                ) {
                    LayersPanel(doc, onMessage = ::toast, onRename = { renameLayer = it }, onImportImage = { importImageLauncher.launch("image/*") })
                }
                if (busy) {
                    androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                }
            }
            HorizontalDivider(color = Color.Black.copy(alpha = 0.3f))
            // Row 1: undo / redo / quick tools / MAGIC / gear.
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(BarBg)
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                BarButton(CanvasIcons.Undo, "Undo", enabled = doc.canUndo) { doc.undo() }
                BarButton(CanvasIcons.Redo, "Redo", enabled = doc.canRedo) { doc.redo() }
                BarButton(Icons.Default.Colorize, "Eyedropper", selected = editor.tool == CanvasTool.EYEDROPPER) {
                    editor.selectTool(CanvasTool.EYEDROPPER)
                }
                BarButton(Icons.Default.Edit, "Pen", selected = editor.tool == CanvasTool.PEN) { editor.selectTool(CanvasTool.PEN) }
                BarButton(CanvasIcons.Eraser, "Eraser", selected = editor.tool == CanvasTool.ERASER) {
                    editor.selectTool(CanvasTool.ERASER)
                }
                // The magic button.
                Box(
                    Modifier
                        .size(width = 76.dp, height = 46.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MagicBlue)
                        .clickable { runMagic() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.AutoFixHigh, "Generate", tint = Color.White, modifier = Modifier.size(30.dp))
                }
                BarButton(Icons.Default.Settings, "Generation settings") { showGenSettings = true }
            }
            // Row 2: menus and panels.
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(BarBgDark)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                BarButton(Icons.Default.Menu, "Menu") { showProjectMenu = true }
                BarButton(CanvasIcons.SelectionMenu, "Selection", selected = doc.selection != null) { showSelectionMenu = true }
                BarButton(Icons.Default.OpenWith, "Transform", selected = editor.tool == CanvasTool.MOVE) { editor.selectTool(CanvasTool.MOVE) }
                Box(Modifier.width(1.dp).height(32.dp).background(Color.White.copy(alpha = 0.15f)))
                BarButton(Icons.Default.Brush, "Brush") { showBrushSettings = true }
                BarButton(Icons.Default.Palette, "Color", selected = showColorPanel) { showColorPicker = true }
                BarButton(Icons.Default.Layers, "Layers", selected = showLayers) { showLayers = !showLayers }
            }
        }
    }

    // ----- Dialogs -----

    if (showBrushSettings) BrushSettingsDialog(editor) { showBrushSettings = false }
    if (showColorPicker) ColorPickerDialog(editor) { showColorPicker = false }
    if (showSelectionMenu) {
        SelectionMenuDialog(editor, onAction = { action ->
            showSelectionMenu = false
            when (action) {
                "all" -> doc.selection = Selection.all(doc.width, doc.height)
                "none" -> doc.selection = null
                "invert" -> doc.selection = Selection.invert(doc.selection, doc.width, doc.height)
                "cut" -> editor.cutSelection()
                "copy" -> if (editor.copySelection()) toast("Copied")
                "paste" -> when (editor.paste()) {
                    CanvasEditorState.PasteResult.NO_ROOM -> toast("Layer limit (${doc.maxLayers}) reached")
                    CanvasEditorState.PasteResult.EMPTY -> toast("Nothing copied")
                    else -> Unit
                }
                "delete" -> doc.clearActive()
                "transform" -> editor.selectTool(CanvasTool.MOVE)
            }
            doc.redraw()
        }, onDismiss = { showSelectionMenu = false })
    }

    if (showProjectMenu) {
        AlertDialog(
            onDismissRequest = { showProjectMenu = false },
            title = { Text(projectName ?: "Untitled project") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("${doc.width}×${doc.height} · ${doc.layers.size} layers" + if (isDirty) " · unsaved" else "", style = MaterialTheme.typography.bodySmall)
                    MenuRow("New canvas") {
                        showProjectMenu = false
                        if (isDirty) confirmNew = true else doc = newDocument(host.width, host.height, null).also { projectName = null }
                    }
                    MenuRow("Open project…") {
                        showProjectMenu = false
                        showOpen = true
                    }
                    MenuRow("Save") {
                        showProjectMenu = false
                        save()
                    }
                    MenuRow("Save as…") {
                        showProjectMenu = false
                        nameDialog = "save"
                    }
                    MenuRow("Save and quit") {
                        showProjectMenu = false
                        save(onClose)
                    }
                    HorizontalDivider()
                    MenuRow("Export image to gallery") {
                        showProjectMenu = false
                        editor.commitTransform()
                        val flat = doc.compositeVisible(includePaper = true)
                        scope.launch { if (host.saveToGallery(flat)) toast("Image saved to gallery (with prompt)") }
                    }
                    MenuRow("Import image as layer") {
                        showProjectMenu = false
                        importImageLauncher.launch("image/*")
                    }
                    HorizontalDivider()
                    MenuRow("Leave canvas") {
                        showProjectMenu = false
                        requestExit()
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showProjectMenu = false }) { Text("Close") } },
        )
    }

    nameDialog?.let { purpose ->
        ProjectNameDialog(
            title = if (purpose == "saveQuit") "Save and quit" else "Save project",
            initial = projectName ?: "Drawing ${java.text.SimpleDateFormat("dd-MM HH'h'mm", java.util.Locale.getDefault()).format(java.util.Date())}",
            onConfirm = { name ->
                nameDialog = null
                saveProject(name, if (purpose == "saveQuit") onClose else ({}))
            },
            onDismiss = { nameDialog = null },
        )
    }

    if (showOpen) {
        OpenProjectDialog(
            onOpen = { info ->
                showOpen = false
                busy = true
                scope.launch {
                    val loaded = runCatching { withContext(Dispatchers.IO) { ProjectStore.load(info) } }
                    busy = false
                    loaded.onSuccess { p ->
                        doc = p.doc
                        projectName = p.name
                        p.params?.let { host.applyParams(it) }
                        if (p.doc.width != host.width || p.doc.height != host.height) {
                            toast("Project is ${p.doc.width}×${p.doc.height}; the model uses ${host.width}×${host.height}: the image will be resized when generating")
                        }
                    }.onFailure { toast("Failed to open: ${it.message}") }
                }
            },
            onDismiss = { showOpen = false },
        )
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("Leave the canvas?") },
            text = { Text("There are unsaved changes.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmExit = false
                    save(onClose)
                }) { Text("Save and quit") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmExit = false }) { Text("Cancel") }
                    TextButton(onClick = {
                        confirmExit = false
                        onClose()
                    }) { Text("Discard") }
                }
            },
        )
    }

    if (confirmNew) {
        AlertDialog(
            onDismissRequest = { confirmNew = false },
            title = { Text("New canvas?") },
            text = { Text("Unsaved changes will be lost.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmNew = false
                    doc = newDocument(host.width, host.height, null)
                    projectName = null
                }) { Text("Discard and create") }
            },
            dismissButton = { TextButton(onClick = { confirmNew = false }) { Text("Cancel") } },
        )
    }

    renameLayer?.let { layer ->
        ProjectNameDialog(
            title = "Rename layer",
            initial = layer.name,
            onConfirm = {
                layer.name = it.trim()
                doc.touch()
                renameLayer = null
            },
            onDismiss = { renameLayer = null },
        )
    }

    if (showGenSettings) {
        AlertDialog(
            onDismissRequest = { showGenSettings = false },
            title = {
                Column {
                    Text("Generation")
                    Text(host.modelName, style = MaterialTheme.typography.bodySmall)
                }
            },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    host.promptEditor()
                    OutlinedButton(onClick = {
                        showGenSettings = false
                        host.openAdvanced()
                    }, modifier = Modifier.fillMaxWidth()) { Text("Steps, CFG, seed, denoise, sampler…") }
                    OutlinedButton(onClick = { loadPromptLauncher.launch("image/png") }, modifier = Modifier.fillMaxWidth()) {
                        Text("Load prompt from a PNG image")
                    }
                    Text(
                        when {
                            !host.canImg2img -> "img2img is disabled: the canvas generates from the prompt only."
                            doc.selection != null && host.canInpaint -> "A selection is active: only the selected area will be regenerated (inpaint)."
                            else -> "The whole drawing is sent as img2img. Empty canvas = txt2img. Make a selection to regenerate only that area."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showGenSettings = false }) { Text("OK") } },
        )
    }

    magicSource?.let { source ->
        val state = host.aiState()
        MagicOverlay(
            source = source,
            state = state,
            acceptMode = editor.acceptMode,
            onAcceptModeChange = {
                editor.acceptMode = it
                editor.savePrefs()
            },
            onAccept = { (state as? CanvasAiState.Done)?.let { applyResult(it.result, editor.acceptMode) } },
            onReject = {
                host.resetAi()
                magicSource = null
            },
            onCancel = {
                host.cancel()
                magicSource = null
            },
        )
    }

    noRoomResult?.let { bmp ->
        AlertDialog(
            onDismissRequest = { noRoomResult = null },
            title = { Text("No free layers") },
            text = {
                Text(
                    "The limit is ${doc.maxLayers} layers for this canvas size. " +
                        "Merge or delete layers, or flatten everything and apply the result.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    noRoomResult = null
                    applyResult(bmp, AcceptMode.FLATTEN)
                }) { Text("Flatten and apply") }
            },
            dismissButton = { TextButton(onClick = { noRoomResult = null }) { Text("Back") } },
        )
    }
}

@Composable
private fun BarButton(
    icon: ImageVector,
    desc: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) BarSelected else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, desc, tint = if (enabled) BarIcon else BarIcon.copy(alpha = 0.35f), modifier = Modifier.size(26.dp))
    }
}

private val TOOLBAR_GROUPS = listOf(
    listOf(CanvasTool.PEN, CanvasTool.AIRBRUSH, CanvasTool.ERASER, CanvasTool.BLUR, CanvasTool.SMUDGE),
    listOf(CanvasTool.FILL, CanvasTool.EYEDROPPER),
    listOf(CanvasTool.SELECT_RECT, CanvasTool.SELECT_LASSO, CanvasTool.SELECT_WAND),
    listOf(CanvasTool.MOVE, CanvasTool.HAND),
)

@Composable
private fun TopToolBar(editor: CanvasEditorState, onReselect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(BarBg)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TOOLBAR_GROUPS.forEachIndexed { gi, group ->
            if (gi > 0) {
                Box(
                    Modifier
                        .padding(horizontal = 6.dp)
                        .width(1.dp)
                        .height(30.dp)
                        .background(Color.White.copy(alpha = 0.15f)),
                )
            }
            group.forEach { tool ->
                val selected = editor.tool == tool
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (selected) BarSelected else Color.Transparent)
                        .clickable {
                            if (selected && tool.isBrush) onReselect() else editor.selectTool(tool)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(CanvasIcons.forTool(tool), CanvasIcons.label(tool), tint = BarIcon, modifier = Modifier.size(28.dp))
                }
            }
        }
    }
}

@Composable
private fun OptionsBar(editor: CanvasEditorState, onOpenBrush: () -> Unit, onMessage: (String) -> Unit) {
    val doc = editor.doc
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(BarBgDark)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tool = editor.tool
        when {
            tool.isBrush -> {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(BarBg)
                        .clickable(onClick = onOpenBrush)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("CORRECTION", color = BarIcon, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text("${editor.correction}", color = BarIcon, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.width(10.dp))
                val s = editor.settingsFor(tool)
                Text("${s.size.roundToInt()}px", color = BarIcon, fontSize = 12.sp, modifier = Modifier.width(44.dp))
                // Square-root scale: fine control for small brushes.
                Slider(
                    value = kotlin.math.sqrt(s.size),
                    onValueChange = { editor.updateSettings(tool, s.copy(size = (it * it).coerceIn(1f, 500f))) },
                    valueRange = 1f..kotlin.math.sqrt(500f),
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = MagicBlue),
                )
            }

            tool == CanvasTool.SELECT_RECT || tool == CanvasTool.SELECT_LASSO || tool == CanvasTool.SELECT_WAND -> {
                SelectionMode.entries.forEach { m ->
                    Chip(
                        when (m) {
                            SelectionMode.REPLACE -> "New"
                            SelectionMode.ADD -> "Add"
                            SelectionMode.SUBTRACT -> "Subtract"
                        },
                        selected = editor.selectionMode == m,
                    ) { editor.selectionMode = m }
                }
                Spacer(Modifier.weight(1f))
                Chip("Deselect", selected = false) {
                    doc.selection = null
                    doc.redraw()
                }
            }

            tool == CanvasTool.FILL -> {
                Text("Tolerance ${editor.fillTolerance}", color = BarIcon, fontSize = 12.sp, modifier = Modifier.width(96.dp))
                Slider(
                    value = editor.fillTolerance.toFloat(),
                    onValueChange = { editor.fillTolerance = it.roundToInt() },
                    onValueChangeFinished = { editor.savePrefs() },
                    valueRange = 0f..128f,
                    modifier = Modifier.weight(1f),
                )
                Chip("All", selected = editor.fillSampleAll) {
                    editor.fillSampleAll = !editor.fillSampleAll
                    editor.savePrefs()
                }
            }

            tool == CanvasTool.MOVE -> {
                Text(
                    "Drag to move · 2 fingers: scale/rotate",
                    color = BarIcon,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { editor.cancelTransform() }) { Icon(Icons.Default.Close, "Cancel", tint = Color(0xFFFF8A80)) }
                IconButton(onClick = { editor.commitTransform() }) { Icon(Icons.Default.Check, "Apply", tint = Color(0xFF9CCC65)) }
            }

            tool == CanvasTool.HAND -> {
                Text("Zoom ${(editor.scale * 100).roundToInt()}%", color = BarIcon, fontSize = 12.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = { editor.refit() }) {
                    Icon(Icons.Default.FitScreen, "Fit", tint = BarIcon)
                }
            }

            tool == CanvasTool.EYEDROPPER -> {
                Text("Tap the canvas to pick a color", color = BarIcon, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Box(Modifier.size(28.dp).clip(CircleShape).background(Color(editor.color)))
            }

            else -> Unit
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) Color.White else BarIcon,
        fontSize = 12.sp,
        modifier = Modifier
            .padding(end = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) MagicBlue else BarBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
