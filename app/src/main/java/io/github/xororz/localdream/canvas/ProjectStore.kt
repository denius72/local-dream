package io.github.xororz.localdream.canvas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

/** Generation settings stored with a project so it can be resumed on any model. */
data class CanvasGenParams(
    val prompt: String,
    val negativePrompt: String,
    val steps: Int,
    val cfg: Float,
    val seed: String,
    val denoiseStrength: Float,
    val scheduler: String,
    val modelId: String?,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("prompt", prompt)
        put("negative_prompt", negativePrompt)
        put("steps", steps)
        put("cfg", cfg.toDouble())
        put("seed", seed)
        put("denoise_strength", denoiseStrength.toDouble())
        put("scheduler", scheduler)
        put("model_id", modelId)
    }

    companion object {
        fun fromJson(j: JSONObject): CanvasGenParams = CanvasGenParams(
            prompt = j.optString("prompt"),
            negativePrompt = j.optString("negative_prompt"),
            steps = j.optInt("steps", 20),
            cfg = j.optDouble("cfg", 7.0).toFloat(),
            seed = j.optString("seed"),
            denoiseStrength = j.optDouble("denoise_strength", 0.6).toFloat(),
            scheduler = j.optString("scheduler", "dpm"),
            modelId = j.optString("model_id").takeIf { it.isNotBlank() && it != "null" },
        )
    }
}

data class ProjectInfo(val name: String, val file: File, val modified: Long, val width: Int, val height: Int)

data class LoadedProject(
    val name: String,
    val doc: CanvasDocument,
    val params: CanvasGenParams?,
)

/**
 * Canvas projects live in their own folder, apart from the generation
 * history: `files/canvas_projects/<name>.ldcanvas`. A project is a zip with a
 * manifest, one PNG per layer and a thumbnail.
 */
object ProjectStore {
    private const val EXT = "ldcanvas"
    private const val FORMAT_VERSION = 1

    fun dir(context: Context): File = File(context.filesDir, "canvas_projects").apply { mkdirs() }

    fun list(context: Context): List<ProjectInfo> = dir(context)
        .listFiles { f -> f.extension == EXT }
        .orEmpty()
        .mapNotNull { f ->
            runCatching {
                ZipFile(f).use { zip ->
                    val manifest = JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().readText())
                    ProjectInfo(f.nameWithoutExtension, f, f.lastModified(), manifest.getInt("width"), manifest.getInt("height"))
                }
            }.getOrNull()
        }
        .sortedByDescending { it.modified }

    fun thumbnail(info: ProjectInfo): Bitmap? = runCatching {
        ZipFile(info.file).use { zip ->
            zip.getEntry("thumb.png")?.let { e -> zip.getInputStream(e).use { BitmapFactory.decodeStream(it) } }
        }
    }.getOrNull()

    fun sanitize(name: String): String = name.trim().replace(Regex("""[\\/:*?"<>|]"""), "_").take(60).ifBlank { "Project" }

    fun exists(context: Context, name: String): Boolean = File(dir(context), "${sanitize(name)}.$EXT").exists()

    fun save(context: Context, name: String, doc: CanvasDocument, activeLayerIndex: Int, params: CanvasGenParams?) {
        val target = File(dir(context), "${sanitize(name)}.$EXT")
        val tmp = File(target.path + ".tmp")
        ZipOutputStream(FileOutputStream(tmp).buffered()).use { zip ->
            val layersJson = JSONArray()
            doc.layers.forEachIndexed { i, layer ->
                val entry = "layer_$i.png"
                zip.putNextEntry(ZipEntry(entry))
                layer.bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip)
                zip.closeEntry()
                layersJson.put(
                    JSONObject().apply {
                        put("name", layer.name)
                        put("visible", layer.visible)
                        put("opacity", layer.opacity.toDouble())
                        put("file", entry)
                    },
                )
            }
            val manifest = JSONObject().apply {
                put("format", FORMAT_VERSION)
                put("width", doc.width)
                put("height", doc.height)
                put("paper", doc.paperColor)
                put("active", activeLayerIndex)
                put("layers", layersJson)
                params?.let { put("params", it.toJson()) }
            }
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toString(2).toByteArray())
            zip.closeEntry()

            val flat = doc.compositeVisible(includePaper = true)
            val k = 256f / maxOf(flat.width, flat.height)
            val thumb = flat.scale((flat.width * k).toInt().coerceAtLeast(1), (flat.height * k).toInt().coerceAtLeast(1))
            zip.putNextEntry(ZipEntry("thumb.png"))
            thumb.compress(Bitmap.CompressFormat.PNG, 100, zip)
            zip.closeEntry()
            flat.recycle()
        }
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    fun load(info: ProjectInfo): LoadedProject = ZipFile(info.file).use { zip ->
        val manifest = JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().readText())
        val doc = CanvasDocument(manifest.getInt("width"), manifest.getInt("height"))
        val layersJson = manifest.getJSONArray("layers")
        val opts = BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPremultiplied = true
        }
        val layers = (0 until layersJson.length()).map { i ->
            val j = layersJson.getJSONObject(i)
            val bmp = zip.getInputStream(zip.getEntry(j.getString("file"))).use { BitmapFactory.decodeStream(it, null, opts) }
                ?: doc.newBitmap()
            val fitted = if (bmp.width != doc.width || bmp.height != doc.height) {
                bmp.scale(doc.width, doc.height).copy(Bitmap.Config.ARGB_8888, true)
            } else {
                bmp
            }
            doc.newLayerObject(j.optString("name", "Layer ${i + 1}"), fitted, j.optBoolean("visible", true), j.optDouble("opacity", 1.0).toFloat())
        }
        doc.replaceAll(layers, manifest.optInt("active", layers.lastIndex), manifest.optInt("paper", android.graphics.Color.WHITE))
        val params = manifest.optJSONObject("params")?.let { CanvasGenParams.fromJson(it) }
        LoadedProject(info.name, doc, params)
    }

    fun delete(info: ProjectInfo) {
        info.file.delete()
    }
}
