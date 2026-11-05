package io.github.xororz.localdream.canvas.ui

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.material3.Checkbox
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.xororz.localdream.R
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
import androidx.compose.runtime.LaunchedEffect
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
    /** Loads only the prompt and negative prompt; the other settings stay. */
    val applyPrompt: (prompt: String, negativePrompt: String) -> Unit,
    val promptEditor: @Composable () -> Unit,
    val openAdvanced: () -> Unit,
    /** img2img / inpaint strength, 0..1; read in composition. */
    val denoise: () -> Float,
    val setDenoise: (Float) -> Unit,
    val batchCount: () -> Int,
    val setBatchCount: (Int) -> Unit,
    /** True when a seed is set, which makes the backend run a single image. */
    val seedFixed: () -> Boolean,
    val saveToGallery: suspend (Bitmap) -> Boolean,
)

/** Process-lifetime flag: "Don't show again" lasts until the app restarts. */
private object GenerateHintSession {
    var dismissed = false
}

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
    // A resized document starts out unsaved even though it is a fresh object.
    val startDirty = remember { booleanArrayOf(false) }
    var savedCounter by remember(doc) {
        mutableIntStateOf(if (startDirty[0]) -1 else doc.dirtyCounter).also { startDirty[0] = false }
    }
    val isDirty = doc.dirtyCounter != savedCounter

    var showColorPanel by remember { mutableStateOf(true) }
    var showLayers by remember { mutableStateOf(false) }
    var showBrushSettings by remember { mutableStateOf(false) }
    var showColorPicker by remember { mutableStateOf(false) }
    var showSelectionMenu by remember { mutableStateOf(false) }
    var showProjectMenu by remember { mutableStateOf(false) }
    var showGenSettings by remember { mutableStateOf(false) }
    var showHistoryPicker by remember { mutableStateOf(false) }
    var showOpen by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var nameDialog by remember { mutableStateOf<String?>(null) } // "save" | "saveQuit"
    var confirmExit by remember { mutableStateOf(false) }
    var confirmNew by remember { mutableStateOf(false) }
    var renameLayer by remember { mutableStateOf<CanvasLayer?>(null) }
    var magicSource by remember { mutableStateOf<Bitmap?>(null) }
    var noRoomResult by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showGenerateHint by remember { mutableStateOf(false) }
    var askAspect by remember { mutableStateOf(false) }
    var generateAfterResize by remember { mutableStateOf(false) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    val resources = LocalResources.current
    fun str(id: Int, vararg args: Any): String = resources.getString(id, *args)

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
                toast(str(R.string.canvas_project_saved, clean))
                then()
            }.onFailure { toast(str(R.string.canvas_save_failed, it.message ?: "")) }
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

    fun resizeDoc(fill: Boolean) {
        editor.commitTransform()
        startDirty[0] = true
        doc = doc.resizedTo(host.width, host.height, fill)
    }

    fun runMagic(confirmed: Boolean = false, aspectChecked: Boolean = false) {
        if (!confirmed && !GenerateHintSession.dismissed) {
            showGenerateHint = true
            return
        }
        if (!aspectChecked && !sameAspect(doc.width, doc.height, host.width, host.height)) {
            askAspect = true
            return
        }
        editor.commitTransform()
        if (host.isBusy()) {
            toast(str(R.string.canvas_generation_running))
            return
        }
        val flat = doc.compositeVisible(includePaper = true)
        val blank = doc.isBlank()
        if (!blank && !host.canImg2img) toast(str(R.string.canvas_img2img_disabled_toast))
        val useImage = !blank && host.canImg2img
        val sel = doc.inpaintMask
        val mask = if (useImage && sel != null) {
            if (host.canInpaint) sel.toInpaintMask(editor.maskFeather) else null.also { toast(str(R.string.canvas_mask_ignored)) }
        } else {
            null
        }
        if (editor.acceptMode == AcceptMode.NEW_LAYER && !doc.canAddLayer()) {
            toast(str(R.string.canvas_no_free_layers_toast, doc.maxLayers))
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
                toast(str(R.string.canvas_image_open_failed))
                return@launch
            }
            if (doc.addLayer("Image", fitIntoDoc(doc, bmp)) == null) toast(str(R.string.canvas_layer_limit, doc.maxLayers))
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
                toast(str(R.string.canvas_no_saved_prompt))
            } else {
                host.applyPrompt(parsed.prompt, parsed.negativePrompt)
                toast(str(R.string.canvas_prompt_loaded))
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
                        contentDescription = stringResource(R.string.canvas_color_panel),
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
                BarButton(CanvasIcons.Undo, stringResource(R.string.canvas_undo), enabled = doc.canUndo) { doc.undo() }
                BarButton(CanvasIcons.Redo, stringResource(R.string.canvas_redo), enabled = doc.canRedo) { doc.redo() }
                BarButton(Icons.Default.Colorize, stringResource(R.string.canvas_tool_eyedropper), selected = editor.tool == CanvasTool.EYEDROPPER) {
                    editor.selectTool(CanvasTool.EYEDROPPER)
                }
                BarButton(Icons.Default.Edit, stringResource(R.string.canvas_tool_pen), selected = editor.tool == CanvasTool.PEN) { editor.selectTool(CanvasTool.PEN) }
                BarButton(CanvasIcons.Eraser, stringResource(R.string.canvas_tool_eraser), selected = editor.tool == CanvasTool.ERASER) {
                    editor.selectTool(CanvasTool.ERASER)
                }
                // The magic button, labelled with what it will run.
                val modeLabel = remember(doc.version, doc.inpaintMask, host.canImg2img) {
                    when {
                        !host.canImg2img || doc.isBlank() -> "TXT2IMG"
                        doc.inpaintMask != null && host.canInpaint -> "INPAINT"
                        else -> "IMG2IMG"
                    }
                }
                Box(
                    Modifier
                        .size(width = 76.dp, height = 46.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MagicBlue)
                        .clickable { runMagic() },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.AutoFixHigh, stringResource(R.string.canvas_generate), tint = Color.White, modifier = Modifier.size(24.dp))
                        Text(
                            modeLabel,
                            color = if (modeLabel == "INPAINT") Color(0xFFFFCDD2) else Color.White,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 10.sp,
                        )
                    }
                }
                BarButton(Icons.Default.Settings, stringResource(R.string.canvas_generation_settings)) { showGenSettings = true }
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
                BarButton(Icons.Default.Menu, stringResource(R.string.canvas_menu)) { showProjectMenu = true }
                BarButton(CanvasIcons.SelectionMenu, stringResource(R.string.canvas_selection), selected = doc.selection != null) { showSelectionMenu = true }
                BarButton(Icons.Default.OpenWith, stringResource(R.string.canvas_transform), selected = editor.tool == CanvasTool.MOVE) { editor.selectTool(CanvasTool.MOVE) }
                Box(Modifier.width(1.dp).height(32.dp).background(Color.White.copy(alpha = 0.15f)))
                BarButton(Icons.Default.Brush, stringResource(R.string.canvas_brush)) { showBrushSettings = true }
                BarButton(Icons.Default.Palette, stringResource(R.string.canvas_color), selected = showColorPanel) { showColorPicker = true }
                BarButton(Icons.Default.Layers, stringResource(R.string.canvas_layers), selected = showLayers) { showLayers = !showLayers }
            }
        }
    }

    // ----- Dialogs -----

    if (showGenerateHint) {
        GenerateHintDialog(
            onCancel = { showGenerateHint = false },
            onGenerate = { dontShowAgain ->
                showGenerateHint = false
                if (dontShowAgain) GenerateHintSession.dismissed = true
                runMagic(confirmed = true)
            },
        )
    }

    // Generate once the resized document is in place (the editor follows doc).
    LaunchedEffect(doc) {
        if (generateAfterResize) {
            generateAfterResize = false
            runMagic(confirmed = true, aspectChecked = true)
        }
    }

    if (askAspect) {
        AspectMismatchDialog(
            docW = doc.width,
            docH = doc.height,
            modelW = host.width,
            modelH = host.height,
            onResize = { fill ->
                askAspect = false
                generateAfterResize = true
                resizeDoc(fill)
            },
            onStretch = {
                askAspect = false
                runMagic(confirmed = true, aspectChecked = true)
            },
            onDismiss = { askAspect = false },
        )
    }

    if (showBrushSettings) BrushSettingsDialog(editor) { showBrushSettings = false }
    if (showColorPicker) ColorPickerDialog(editor) { showColorPicker = false }
    if (showSelectionMenu) {
        SelectionMenuDialog(editor, onAction = { action ->
            showSelectionMenu = false
            when (action) {
                "all" -> doc.changeSelection(Selection.all(doc.width, doc.height))
                "none" -> doc.changeSelection(null)
                "invert" -> doc.changeSelection(Selection.invert(doc.selection, doc.width, doc.height))
                "to_mask" -> doc.selection?.let {
                    doc.inpaintMask = it
                    toast(str(R.string.canvas_selection_to_mask_done))
                }
                "cut" -> editor.cutSelection()
                "copy" -> if (editor.copySelection()) toast(str(R.string.canvas_copied))
                "paste" -> when (editor.paste()) {
                    CanvasEditorState.PasteResult.NO_ROOM -> toast(str(R.string.canvas_layer_limit, doc.maxLayers))
                    CanvasEditorState.PasteResult.EMPTY -> toast(str(R.string.canvas_nothing_copied))
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
            title = { Text(projectName ?: stringResource(R.string.canvas_untitled_project)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(pluralStringResource(R.plurals.canvas_doc_info, doc.layers.size, doc.width, doc.height, doc.layers.size) + if (isDirty) stringResource(R.string.canvas_unsaved_suffix) else "", style = MaterialTheme.typography.bodySmall)
                    MenuRow(stringResource(R.string.canvas_new_canvas)) {
                        showProjectMenu = false
                        if (isDirty) confirmNew = true else doc = newDocument(host.width, host.height, null).also { projectName = null }
                    }
                    MenuRow(stringResource(R.string.canvas_open_project_menu)) {
                        showProjectMenu = false
                        showOpen = true
                    }
                    MenuRow(stringResource(R.string.canvas_save)) {
                        showProjectMenu = false
                        save()
                    }
                    MenuRow(stringResource(R.string.canvas_save_as)) {
                        showProjectMenu = false
                        nameDialog = "save"
                    }
                    MenuRow(stringResource(R.string.canvas_save_and_quit)) {
                        showProjectMenu = false
                        save(onClose)
                    }
                    HorizontalDivider()
                    MenuRow(stringResource(R.string.canvas_export_image)) {
                        showProjectMenu = false
                        showExport = true
                    }
                    MenuRow(stringResource(R.string.canvas_import_layer)) {
                        showProjectMenu = false
                        importImageLauncher.launch("image/*")
                    }
                    if (doc.width != host.width || doc.height != host.height) {
                        MenuRow(stringResource(R.string.canvas_resize_fit, host.width, host.height)) {
                            showProjectMenu = false
                            resizeDoc(fill = false)
                        }
                        MenuRow(stringResource(R.string.canvas_resize_fill, host.width, host.height)) {
                            showProjectMenu = false
                            resizeDoc(fill = true)
                        }
                    }
                    HorizontalDivider()
                    MenuRow(stringResource(R.string.canvas_leave)) {
                        showProjectMenu = false
                        requestExit()
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showProjectMenu = false }) { Text(stringResource(R.string.canvas_close)) } },
        )
    }

    if (showExport) {
        var transparent by remember { mutableStateOf(editor.exportTransparent) }
        AlertDialog(
            onDismissRequest = { showExport = false },
            title = { Text(stringResource(R.string.canvas_export_title)) },
            text = {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { transparent = !transparent },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = transparent, onCheckedChange = { transparent = it })
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.canvas_export_transparent))
                        Text(stringResource(R.string.canvas_export_transparent_hint), style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showExport = false
                    editor.exportTransparent = transparent
                    editor.savePrefs()
                    editor.commitTransform()
                    if (transparent && doc.layers.none { it.visible }) toast(str(R.string.canvas_export_all_hidden))
                    // Only the export can be transparent: generation and
                    // thumbnails keep the paper under the layers.
                    val flat = doc.compositeVisible(includePaper = !transparent)
                    scope.launch { if (host.saveToGallery(flat)) toast(str(R.string.canvas_image_saved)) }
                }) { Text(stringResource(R.string.canvas_export)) }
            },
            dismissButton = { TextButton(onClick = { showExport = false }) { Text(stringResource(R.string.canvas_cancel)) } },
        )
    }

    nameDialog?.let { purpose ->
        ProjectNameDialog(
            title = if (purpose == "saveQuit") stringResource(R.string.canvas_save_and_quit) else stringResource(R.string.canvas_save_project),
            initial = projectName ?: stringResource(R.string.canvas_default_project_name, java.text.SimpleDateFormat("dd-MM HH'h'mm", LocalConfiguration.current.locales[0]).format(java.util.Date())),
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
                            toast(str(R.string.canvas_project_size_differs, p.doc.width, p.doc.height, host.width, host.height))
                        }
                    }.onFailure { toast(str(R.string.canvas_open_failed, it.message ?: "")) }
                }
            },
            onDismiss = { showOpen = false },
        )
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text(stringResource(R.string.canvas_leave_title)) },
            text = { Text(stringResource(R.string.canvas_leave_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmExit = false
                    save(onClose)
                }) { Text(stringResource(R.string.canvas_save_and_quit)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmExit = false }) { Text(stringResource(R.string.canvas_cancel)) }
                    TextButton(onClick = {
                        confirmExit = false
                        onClose()
                    }) { Text(stringResource(R.string.canvas_discard)) }
                }
            },
        )
    }

    if (confirmNew) {
        AlertDialog(
            onDismissRequest = { confirmNew = false },
            title = { Text(stringResource(R.string.canvas_new_title)) },
            text = { Text(stringResource(R.string.canvas_new_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmNew = false
                    doc = newDocument(host.width, host.height, null)
                    projectName = null
                }) { Text(stringResource(R.string.canvas_discard_and_create)) }
            },
            dismissButton = { TextButton(onClick = { confirmNew = false }) { Text(stringResource(R.string.canvas_cancel)) } },
        )
    }

    renameLayer?.let { layer ->
        ProjectNameDialog(
            title = stringResource(R.string.canvas_rename_layer),
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
                    Text(stringResource(R.string.canvas_generation))
                    Text(host.modelName, style = MaterialTheme.typography.bodySmall)
                }
            },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    host.promptEditor()
                    if (host.canImg2img) {
                        val d = host.denoise()
                        LabeledSlider(stringResource(R.string.canvas_denoise), String.format(java.util.Locale.US, "%.2f", d), d, 0f..1f) {
                            host.setDenoise((it * 100).roundToInt() / 100f)
                        }
                        Text(
                            stringResource(R.string.canvas_denoise_hint),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    val b = host.batchCount()
                    LabeledSlider(stringResource(R.string.canvas_batch_count), "$b", b.toFloat(), 1f..10f) { host.setBatchCount(it.roundToInt()) }
                    Text(
                        if (host.seedFixed()) {
                            stringResource(R.string.canvas_batch_seed_set)
                        } else {
                            stringResource(R.string.canvas_batch_hint)
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = {
                        showGenSettings = false
                        host.openAdvanced()
                    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.canvas_advanced_settings)) }
                    OutlinedButton(onClick = { showHistoryPicker = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.canvas_load_prompt_history))
                    }
                    OutlinedButton(onClick = { loadPromptLauncher.launch("image/png") }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.canvas_load_prompt_png))
                    }
                    Text(
                        when {
                            !host.canImg2img -> stringResource(R.string.canvas_mode_txt2img_only)
                            doc.inpaintMask != null && host.canInpaint -> stringResource(R.string.canvas_mode_inpaint)
                            else -> stringResource(R.string.canvas_mode_img2img)
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showGenSettings = false }) { Text(stringResource(R.string.canvas_ok)) } },
        )
    }

    if (showHistoryPicker) {
        HistoryPromptPicker(
            onPick = { item ->
                showHistoryPicker = false
                host.applyPrompt(item.params.prompt, item.params.negativePrompt)
                toast(str(R.string.canvas_prompt_loaded))
            },
            onDismiss = { showHistoryPicker = false },
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
            onAccept = { bmp -> applyResult(bmp, editor.acceptMode) },
            onReject = {
                host.resetAi()
                magicSource = null
            },
            onCancel = {
                host.cancel()
                // Stopping a batch keeps the images already made.
                if (host.aiState() !is CanvasAiState.Done) magicSource = null
            },
        )
    }

    noRoomResult?.let { bmp ->
        AlertDialog(
            onDismissRequest = { noRoomResult = null },
            title = { Text(stringResource(R.string.canvas_no_free_layers)) },
            text = {
                Text(pluralStringResource(R.plurals.canvas_layer_limit_text, doc.maxLayers, doc.maxLayers))
            },
            confirmButton = {
                TextButton(onClick = {
                    noRoomResult = null
                    applyResult(bmp, AcceptMode.FLATTEN)
                }) { Text(stringResource(R.string.canvas_flatten_and_apply)) }
            },
            dismissButton = { TextButton(onClick = { noRoomResult = null }) { Text(stringResource(R.string.canvas_back)) } },
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
    listOf(CanvasTool.INPAINT, CanvasTool.SELECT_RECT, CanvasTool.SELECT_LASSO, CanvasTool.SELECT_WAND),
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
            tool == CanvasTool.INPAINT -> {
                Chip(stringResource(R.string.canvas_mask_paint), selected = !editor.maskErase) { editor.maskErase = false }
                Chip(stringResource(R.string.canvas_mask_erase), selected = editor.maskErase) { editor.maskErase = true }
                val s = editor.settingsFor(tool)
                Text("${s.size.roundToInt()}px", color = BarIcon, fontSize = 12.sp, modifier = Modifier.width(40.dp))
                Slider(
                    value = kotlin.math.sqrt(s.size),
                    onValueChange = { editor.updateSettings(tool, s.copy(size = (it * it).coerceIn(1f, 500f))) },
                    valueRange = 1f..kotlin.math.sqrt(500f),
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color(0xFFFF3B30)),
                )
                Chip(stringResource(R.string.canvas_clear), selected = false) {
                    doc.inpaintMask = null
                    doc.redraw()
                }
            }

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
                        Text(stringResource(R.string.canvas_correction_label), color = BarIcon, fontSize = 10.sp, fontWeight = FontWeight.Bold)
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
                            SelectionMode.REPLACE -> stringResource(R.string.canvas_sel_new)
                            SelectionMode.ADD -> stringResource(R.string.canvas_sel_add)
                            SelectionMode.SUBTRACT -> stringResource(R.string.canvas_sel_subtract)
                        },
                        selected = editor.selectionMode == m,
                    ) { editor.selectionMode = m }
                }
                Spacer(Modifier.weight(1f))
                Chip(stringResource(R.string.canvas_deselect), selected = false) {
                    doc.changeSelection(null)
                    doc.redraw()
                }
            }

            tool == CanvasTool.FILL -> {
                Text(stringResource(R.string.canvas_tolerance, editor.fillTolerance), color = BarIcon, fontSize = 12.sp, modifier = Modifier.width(96.dp))
                Slider(
                    value = editor.fillTolerance.toFloat(),
                    onValueChange = { editor.fillTolerance = it.roundToInt() },
                    onValueChangeFinished = { editor.savePrefs() },
                    valueRange = 0f..128f,
                    modifier = Modifier.weight(1f),
                )
                Chip(stringResource(R.string.canvas_sample_all), selected = editor.fillSampleAll) {
                    editor.fillSampleAll = !editor.fillSampleAll
                    editor.savePrefs()
                }
            }

            tool == CanvasTool.MOVE -> {
                Text(
                    stringResource(R.string.canvas_move_hint),
                    color = BarIcon,
                    fontSize = 12.sp,
                    lineHeight = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { editor.cancelTransform() }) { Icon(Icons.Default.Close, stringResource(R.string.canvas_cancel), tint = Color(0xFFFF8A80)) }
                IconButton(onClick = { editor.commitTransform() }) { Icon(Icons.Default.Check, stringResource(R.string.canvas_apply), tint = Color(0xFF9CCC65)) }
            }

            tool == CanvasTool.HAND -> {
                val rot = editor.rotation.roundToInt().let { if (it > 180) it - 360 else it }
                Text(if (rot != 0) stringResource(R.string.canvas_zoom_rotation, (editor.scale * 100).roundToInt(), rot) else stringResource(R.string.canvas_zoom, (editor.scale * 100).roundToInt()), color = BarIcon, fontSize = 12.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = { editor.refit() }) {
                    Icon(Icons.Default.FitScreen, stringResource(R.string.canvas_fit), tint = BarIcon)
                }
            }

            tool == CanvasTool.EYEDROPPER -> {
                Text(stringResource(R.string.canvas_eyedropper_hint), color = BarIcon, fontSize = 12.sp, lineHeight = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
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
