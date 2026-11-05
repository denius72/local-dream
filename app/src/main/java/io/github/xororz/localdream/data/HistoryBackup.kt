package io.github.xororz.localdream.data

import android.content.Context
import android.net.Uri
import io.github.xororz.localdream.data.db.AppDatabase
import io.github.xororz.localdream.data.db.HistoryEntity
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class BackupFormatException(message: String) : IOException(message)

data class BackupStats(
    val totalCount: Int,
    val totalBytes: Long,
    val favoriteCount: Int,
    val favoriteBytes: Long,
    val canvasCount: Int = 0,
    val canvasBytes: Long = 0,
)

data class BackupExportResult(
    val exported: Int,
    val skippedMissingImage: Int,
    val canvasProjects: Int = 0,
)

data class BackupImportResult(
    val imported: Int,
    val duplicates: Int,
    val failed: Int,
    val orphanModelIds: Set<String>,
    val canvasImported: Int = 0,
    val canvasDuplicates: Int = 0,
    val canvasFailed: Int = 0,
    val canvasSettingsRestored: Boolean = false,
)

// Exports generation history (DB rows plus image files) into a single zip
// and merges such zips back in. The zip carries a JSON manifest instead of
// a raw DB copy so backups stay readable across schema versions and import
// merges into the existing history rather than replacing it.
//
// Canvas data (this fork only) rides along in the same zip: project files
// under canvas_projects/ and the canvas tool settings in the manifest's
// "canvas" key. The format and version stay those of upstream Local Dream,
// which ignores unknown keys and zip entries, so a full backup still imports
// there (history only). Exporting with includeCanvas = false writes exactly
// the upstream layout.
object HistoryBackup {
    private const val MANIFEST_NAME = "manifest.json"
    private const val FORMAT = "localdream-history-backup"
    private const val VERSION = 1

    // Exactly the layout saveGeneratedImage produces. Anything else in a
    // manifest (absolute paths, traversal, foreign files) is rejected.
    private val imagePathRegex = Regex("""history/[^/\\]+/\d+\.(png|jpg)""")

    private const val CANVAS_DIR = "canvas_projects"
    private const val CANVAS_EXT = "ldcanvas"
    private const val CANVAS_PREFS = "canvas_prefs"
    private val canvasPathRegex = Regex("""$CANVAS_DIR/[^/\\]+\.$CANVAS_EXT""")

    private fun canvasProjects(context: Context): List<File> =
        File(context.filesDir, CANVAS_DIR).listFiles { f -> f.isFile && f.extension == CANVAS_EXT }
            .orEmpty()
            .sortedBy { it.name }

    suspend fun stats(context: Context): BackupStats = withContext(Dispatchers.IO) {
        val dao = AppDatabase.get(context).historyDao()
        val rows = dao.queryOnce(HistoryFilter().toSqlQuery())
        var totalCount = 0
        var totalBytes = 0L
        var favoriteCount = 0
        var favoriteBytes = 0L
        for (row in rows) {
            val file = File(context.filesDir, row.imagePath)
            if (!file.isFile) continue
            val size = file.length()
            totalCount++
            totalBytes += size
            if (row.favorite) {
                favoriteCount++
                favoriteBytes += size
            }
        }
        val projects = canvasProjects(context)
        BackupStats(
            totalCount,
            totalBytes,
            favoriteCount,
            favoriteBytes,
            canvasCount = projects.size,
            canvasBytes = projects.sumOf { it.length() },
        )
    }

    suspend fun export(
        context: Context,
        uri: Uri,
        favoritesOnly: Boolean,
        includeCanvas: Boolean,
        onProgress: (done: Int, total: Int) -> Unit,
    ): BackupExportResult = withContext(Dispatchers.IO) {
        val dao = AppDatabase.get(context).historyDao()
        val filter = HistoryFilter(
            favorites = if (favoritesOnly) setOf(FavoriteFilter.FAVORITE) else null,
        )
        val rows = dao.queryOnce(filter.toSqlQuery())
        val items = rows.filter { File(context.filesDir, it.imagePath).isFile }
        val projects = if (includeCanvas) canvasProjects(context) else emptyList()
        val total = items.size + projects.size

        val output = context.contentResolver.openOutputStream(uri)
            ?: throw IOException("Cannot open backup destination")
        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            zip.setLevel(Deflater.BEST_SPEED)
            zip.putNextEntry(ZipEntry(MANIFEST_NAME))
            val manifest = buildManifest(items)
            if (includeCanvas) manifest.put("canvas", buildCanvasManifest(context, projects))
            zip.write(manifest.toString().toByteArray())
            zip.closeEntry()

            // The payloads are PNG/JPEG; deflating them again only burns CPU.
            zip.setLevel(Deflater.NO_COMPRESSION)
            items.forEachIndexed { index, row ->
                ensureActive()
                zip.putNextEntry(ZipEntry(row.imagePath))
                File(context.filesDir, row.imagePath).inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                onProgress(index + 1, total)
            }
            projects.forEachIndexed { index, file ->
                ensureActive()
                zip.putNextEntry(ZipEntry("$CANVAS_DIR/${file.name}"))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                onProgress(items.size + index + 1, total)
            }
        }
        BackupExportResult(items.size, rows.size - items.size, projects.size)
    }

    suspend fun import(
        context: Context,
        uri: Uri,
        installedModelIds: Set<String>,
        onProgress: (done: Int, total: Int) -> Unit,
    ): BackupImportResult = withContext(Dispatchers.IO) {
        val dao = AppDatabase.get(context).historyDao()

        // Pass 1: find and parse the manifest. Our own zips put it first, but
        // scan the whole archive so re-zipped backups still import.
        val manifest = openZip(context, uri).use { zip ->
            generateSequence { zip.nextEntry }
                .firstOrNull { it.name == MANIFEST_NAME }
                ?.let { JSONObject(zip.readBytes().toString(Charsets.UTF_8)) }
        } ?: throw BackupFormatException("manifest.json not found")

        if (manifest.optString("format") != FORMAT) {
            throw BackupFormatException("unrecognized backup format")
        }
        if (manifest.optInt("version", Int.MAX_VALUE) > VERSION) {
            throw BackupFormatException("backup was created by a newer app version")
        }

        val itemsJson = manifest.optJSONArray("items") ?: JSONArray()
        val pending = LinkedHashMap<String, HistoryEntity>()
        var duplicates = 0
        var invalid = 0
        for (i in 0 until itemsJson.length()) {
            val entity = itemsJson.optJSONObject(i)?.let { jsonToEntity(it) }
            if (entity == null || !imagePathRegex.matches(entity.imagePath)) {
                invalid++
                continue
            }
            if (dao.countByKey(entity.modelId, entity.timestamp) > 0) {
                duplicates++
                continue
            }
            pending[entity.imagePath] = entity
        }

        val canvas = manifest.optJSONObject("canvas")
        val canvasPending = mutableSetOf<String>()
        var canvasFailed = 0
        canvas?.optJSONArray("projects")?.let { arr ->
            for (i in 0 until arr.length()) {
                val path = arr.optString(i)
                if (canvasPathRegex.matches(path)) canvasPending += path else canvasFailed++
            }
        }

        // Pass 2: stream images to their final location, then insert the row.
        // Each item commits on its own, so a cancelled import keeps what it
        // finished and a re-run resumes via the duplicate check above.
        val total = pending.size + canvasPending.size
        var done = 0
        var imported = 0
        var canvasImported = 0
        var canvasDuplicates = 0
        val orphanModelIds = mutableSetOf<String>()
        if (total > 0) {
            openZip(context, uri).use { zip ->
                generateSequence { zip.nextEntry }.forEach { entry ->
                    ensureActive()
                    if (canvasPending.remove(entry.name)) {
                        when (importCanvasProject(context, zip, entry.name.substringAfter('/'))) {
                            CanvasImport.IMPORTED -> canvasImported++
                            CanvasImport.DUPLICATE -> canvasDuplicates++
                            CanvasImport.FAILED -> canvasFailed++
                        }
                        onProgress(++done, total)
                        return@forEach
                    }
                    val entity = pending.remove(entry.name) ?: return@forEach
                    writeImage(zip, File(context.filesDir, entity.imagePath))
                    dao.insert(entity)
                    imported++
                    if (entity.modelId !in installedModelIds) {
                        orphanModelIds += entity.modelId
                    }
                    onProgress(++done, total)
                }
            }
        }
        val settingsRestored = canvas?.optJSONObject("settings")?.let { restoreCanvasPrefs(context, it) } ?: false
        // Whatever is still pending had no matching entry in the zip.
        BackupImportResult(
            imported,
            duplicates,
            invalid + pending.size,
            orphanModelIds,
            canvasImported = canvasImported,
            canvasDuplicates = canvasDuplicates,
            canvasFailed = canvasFailed + canvasPending.size,
            canvasSettingsRestored = settingsRestored,
        )
    }

    private enum class CanvasImport { IMPORTED, DUPLICATE, FAILED }

    // Same name and same bytes = duplicate. Same name, different content =
    // kept under a new name, so an import never overwrites a project.
    private fun importCanvasProject(context: Context, source: InputStream, fileName: String): CanvasImport {
        val dir = File(context.filesDir, CANVAS_DIR).apply { mkdirs() }
        val tmp = File(dir, "$fileName.part")
        try {
            tmp.outputStream().use { source.copyTo(it) }
            val valid = runCatching { ZipFile(tmp).use { it.getEntry("manifest.json") != null } }.getOrDefault(false)
            if (!valid) return CanvasImport.FAILED
            val base = fileName.removeSuffix(".$CANVAS_EXT")
            var target = File(dir, fileName)
            var n = 2
            while (target.exists()) {
                if (sameContent(target, tmp)) return CanvasImport.DUPLICATE
                target = File(dir, "$base ($n).$CANVAS_EXT")
                n++
            }
            return if (tmp.renameTo(target)) CanvasImport.IMPORTED else CanvasImport.FAILED
        } finally {
            tmp.delete()
        }
    }

    private fun sameContent(a: File, b: File): Boolean {
        if (a.length() != b.length()) return false
        a.inputStream().buffered().use { x ->
            b.inputStream().buffered().use { y ->
                while (true) {
                    val c = x.read()
                    if (c != y.read()) return false
                    if (c == -1) return true
                }
            }
        }
    }

    private fun buildCanvasManifest(context: Context, projects: List<File>): JSONObject {
        val settings = JSONObject()
        context.getSharedPreferences(CANVAS_PREFS, Context.MODE_PRIVATE).all.forEach { (key, value) ->
            val typed = when (value) {
                is Boolean -> "boolean" to value
                is Int -> "int" to value
                is Long -> "long" to value
                is Float -> "float" to value.toDouble()
                is String -> "string" to value
                is Set<*> -> "string_set" to JSONArray(value.map { it.toString() })
                else -> null
            } ?: return@forEach
            settings.put(key, JSONObject().put("type", typed.first).put("value", typed.second))
        }
        return JSONObject().apply {
            put("projects", JSONArray(projects.map { "$CANVAS_DIR/${it.name}" }))
            put("settings", settings)
        }
    }

    private fun restoreCanvasPrefs(context: Context, settings: JSONObject): Boolean {
        if (settings.length() == 0) return false
        val editor = context.getSharedPreferences(CANVAS_PREFS, Context.MODE_PRIVATE).edit()
        settings.keys().forEach { key ->
            val item = settings.optJSONObject(key) ?: return@forEach
            when (item.optString("type")) {
                "boolean" -> editor.putBoolean(key, item.optBoolean("value"))
                "int" -> editor.putInt(key, item.optInt("value"))
                "long" -> editor.putLong(key, item.optLong("value"))
                "float" -> editor.putFloat(key, item.optDouble("value").toFloat())
                "string" -> editor.putString(key, item.optString("value"))
                "string_set" -> item.optJSONArray("value")?.let { arr ->
                    editor.putStringSet(key, (0 until arr.length()).map { arr.optString(it) }.toSet())
                }
            }
        }
        editor.apply()
        return true
    }

    private fun openZip(context: Context, uri: Uri): ZipInputStream {
        val input: InputStream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Cannot open backup file")
        return ZipInputStream(input.buffered())
    }

    private fun writeImage(source: InputStream, dest: File) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, "${dest.name}.part")
        try {
            tmp.outputStream().use { source.copyTo(it) }
            if (!tmp.renameTo(dest)) {
                throw IOException("Failed to move ${tmp.name} into place")
            }
        } finally {
            tmp.delete()
        }
    }

    private fun buildManifest(items: List<HistoryEntity>): JSONObject {
        val array = JSONArray()
        items.forEach { array.put(entityToJson(it)) }
        return JSONObject().apply {
            put("format", FORMAT)
            put("version", VERSION)
            put("exportedAt", System.currentTimeMillis())
            put("items", array)
        }
    }

    private fun entityToJson(e: HistoryEntity): JSONObject = JSONObject().apply {
        put("modelId", e.modelId)
        put("timestamp", e.timestamp)
        put("imagePath", e.imagePath)
        put("width", e.width)
        put("height", e.height)
        put("mode", e.mode)
        e.denoiseStrength?.let { put("denoiseStrength", it.toDouble()) }
        e.upscalerId?.let { put("upscalerId", it) }
        put("steps", e.steps)
        put("cfg", e.cfg.toDouble())
        e.seed?.let { put("seed", it) }
        put("prompt", e.prompt)
        put("negativePrompt", e.negativePrompt)
        e.generationTime?.let { put("generationTime", it) }
        put("scheduler", e.scheduler)
        put("runOnCpu", e.runOnCpu)
        put("useOpenCL", e.useOpenCL)
        put("favorite", e.favorite)
    }

    private fun jsonToEntity(json: JSONObject): HistoryEntity? {
        val modelId = json.optString("modelId")
        val timestamp = json.optLong("timestamp", -1L)
        val imagePath = json.optString("imagePath")
        if (modelId.isEmpty() || timestamp <= 0 || imagePath.isEmpty()) return null
        return HistoryEntity(
            modelId = modelId,
            timestamp = timestamp,
            imagePath = imagePath,
            width = json.optInt("width", 512),
            height = json.optInt("height", 512),
            mode = json.optString("mode", GenerationMode.UNKNOWN.name),
            denoiseStrength = if (json.has("denoiseStrength")) {
                json.optDouble("denoiseStrength").toFloat()
            } else {
                null
            },
            upscalerId = json.optString("upscalerId").ifEmpty { null },
            steps = json.optInt("steps", 20),
            cfg = json.optDouble("cfg", 7.0).toFloat(),
            seed = if (json.has("seed")) json.optLong("seed") else null,
            prompt = json.optString("prompt", ""),
            negativePrompt = json.optString("negativePrompt", ""),
            generationTime = json.optString("generationTime").ifEmpty { null },
            scheduler = json.optString("scheduler", "dpm"),
            runOnCpu = json.optBoolean("runOnCpu", false),
            useOpenCL = json.optBoolean("useOpenCL", false),
            favorite = json.optBoolean("favorite", false),
        )
    }
}
