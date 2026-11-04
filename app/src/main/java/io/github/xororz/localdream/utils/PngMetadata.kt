package io.github.xororz.localdream.utils

import io.github.xororz.localdream.data.GenerationMode
import io.github.xororz.localdream.ui.screens.GenerationParameters
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.InflaterInputStream

/**
 * Generation parameters in the AUTOMATIC1111 "parameters" text format, the one
 * Civitai, ComfyUI, Forge and most image viewers read:
 *
 * ```
 * <prompt>
 * Negative prompt: <negative>
 * Steps: 20, Sampler: DPM++ 2M, Schedule type: Karras, CFG scale: 7, Seed: 1, Size: 512x512, Model: x, Denoising strength: 0.6
 * ```
 */
data class A1111Params(
    val prompt: String,
    val negativePrompt: String = "",
    val steps: Int? = null,
    val cfg: Float? = null,
    val seed: Long? = null,
    /** Local Dream scheduler id (dpm, euler_a_karras, ...). */
    val scheduler: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val model: String? = null,
    val denoiseStrength: Float? = null,
) {
    fun format(): String = buildString {
        append(prompt.trim())
        append('\n')
        if (negativePrompt.isNotBlank()) {
            append("Negative prompt: ").append(negativePrompt.trim()).append('\n')
        }
        val parts = mutableListOf<String>()
        steps?.let { parts += "Steps: $it" }
        scheduler?.let { id ->
            val base = id.removeSuffix("_karras")
            parts += "Sampler: ${SAMPLER_NAMES[base] ?: base}"
            if (id.endsWith("_karras")) parts += "Schedule type: Karras"
        }
        cfg?.let { parts += "CFG scale: ${trimFloat(it)}" }
        seed?.let { parts += "Seed: $it" }
        if (width != null && height != null) parts += "Size: ${width}x$height"
        model?.let { parts += "Model: ${it.replace(",", " ")}" }
        denoiseStrength?.let { parts += "Denoising strength: ${trimFloat(it)}" }
        parts += "Version: Local Dream Canvas"
        append(parts.joinToString(", "))
    }

    companion object {
        private val SAMPLER_NAMES = mapOf(
            "dpm" to "DPM++ 2M",
            "dpm_sde" to "DPM++ 2M SDE",
            "euler_a" to "Euler a",
            "euler" to "Euler",
            "lcm" to "LCM",
        )

        private fun trimFloat(f: Float): String {
            val s = String.format(java.util.Locale.US, "%.2f", f)
            return s.trimEnd('0').trimEnd('.')
        }

        fun parse(text: String): A1111Params? {
            val lines = text.replace("\r\n", "\n").trimEnd().split('\n')
            if (lines.isEmpty()) return null
            val paramIdx = lines.indexOfLast { it.trimStart().startsWith("Steps:") }
            val body = if (paramIdx >= 0) lines.subList(0, paramIdx) else lines
            val negIdx = body.indexOfFirst { it.startsWith("Negative prompt:") }
            val prompt = (if (negIdx >= 0) body.subList(0, negIdx) else body).joinToString("\n").trim()
            val negative = if (negIdx >= 0) {
                body.subList(negIdx, body.size).joinToString("\n").removePrefix("Negative prompt:").trim()
            } else {
                ""
            }
            val kv = mutableMapOf<String, String>()
            if (paramIdx >= 0) {
                // Values may be quoted and contain commas.
                val regex = Regex("""\s*([\w \-/]+):\s*("(?:\\.|[^"])*"|[^,]*)(?:,|$)""")
                regex.findAll(lines[paramIdx]).forEach { m ->
                    kv[m.groupValues[1].trim()] = m.groupValues[2].trim().trim('"')
                }
            }
            val sampler = kv["Sampler"]?.let { name ->
                SAMPLER_NAMES.entries.firstOrNull { it.value.equals(name, ignoreCase = true) }?.key
                    ?: when {
                        name.contains("SDE", true) -> "dpm_sde"
                        name.startsWith("DPM", true) -> "dpm"
                        name.equals("Euler a", true) -> "euler_a"
                        name.startsWith("Euler", true) -> "euler"
                        name.startsWith("LCM", true) -> "lcm"
                        else -> null
                    }
            }
            val karras = kv["Schedule type"]?.equals("Karras", true) == true ||
                kv["Sampler"]?.contains("Karras", true) == true
            val size = kv["Size"]?.split('x')
            return A1111Params(
                prompt = prompt,
                negativePrompt = negative,
                steps = kv["Steps"]?.toIntOrNull(),
                cfg = kv["CFG scale"]?.toFloatOrNull(),
                seed = kv["Seed"]?.toLongOrNull(),
                scheduler = sampler?.let { if (karras && it != "lcm") it + "_karras" else it },
                width = size?.getOrNull(0)?.trim()?.toIntOrNull(),
                height = size?.getOrNull(1)?.trim()?.toIntOrNull(),
                model = kv["Model"],
                denoiseStrength = kv["Denoising strength"]?.toFloatOrNull(),
            )
        }
    }
}

/** Reads and writes PNG text chunks without re-encoding the image. */
object PngMetadata {
    private val SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    const val KEY_PARAMETERS = "parameters"

    /** Returns [png] with a text chunk [key] = [value] inserted after IHDR. */
    fun withText(png: ByteArray, key: String, value: String): ByteArray {
        if (png.size < 33 || !png.copyOfRange(0, 8).contentEquals(SIGNATURE)) return png
        // IHDR is always first: 8 signature + 4 len + 4 type + 13 data + 4 crc.
        val ihdrEnd = 8 + 4 + 4 + 13 + 4
        val chunk = if (value.all { it.code in 0x20..0x7E || it == '\n' || it == '\t' }) {
            makeChunk("tEXt", key.toByteArray(Charsets.ISO_8859_1) + 0 + value.toByteArray(Charsets.ISO_8859_1))
        } else {
            // iTXt: keyword\0 compression flag(0) method(0) language\0 translated\0 text
            makeChunk(
                "iTXt",
                key.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0, 0, 0, 0, 0) + value.toByteArray(Charsets.UTF_8),
            )
        }
        return png.copyOfRange(0, ihdrEnd) + chunk + png.copyOfRange(ihdrEnd, png.size)
    }

    private operator fun ByteArray.plus(b: Int): ByteArray = this + byteArrayOf(b.toByte())

    private fun makeChunk(type: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val len = data.size
        out.write(byteArrayOf((len ushr 24).toByte(), (len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte()))
        val typeBytes = type.toByteArray(Charsets.ISO_8859_1)
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32().apply {
            update(typeBytes)
            update(data)
        }.value
        out.write(byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte()))
        return out.toByteArray()
    }

    /** Reads all text chunks (tEXt, zTXt, iTXt) of a PNG stream. */
    fun readText(input: InputStream): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val din = DataInputStream(input.buffered())
        val sig = ByteArray(8)
        din.readFully(sig)
        if (!sig.contentEquals(SIGNATURE)) return result
        while (true) {
            val len = try {
                din.readInt()
            } catch (_: java.io.EOFException) {
                break
            }
            val typeBytes = ByteArray(4)
            din.readFully(typeBytes)
            val type = String(typeBytes, Charsets.ISO_8859_1)
            if (type == "IDAT" || type == "IEND") break
            val data = ByteArray(len)
            din.readFully(data)
            din.readInt() // crc
            runCatching {
                when (type) {
                    "tEXt" -> {
                        val z = data.indexOf(0)
                        result[String(data, 0, z, Charsets.ISO_8859_1)] =
                            String(data, z + 1, data.size - z - 1, Charsets.ISO_8859_1)
                    }

                    "zTXt" -> {
                        val z = data.indexOf(0)
                        val text = InflaterInputStream(data.copyOfRange(z + 2, data.size).inputStream()).readBytes()
                        result[String(data, 0, z, Charsets.ISO_8859_1)] = String(text, Charsets.ISO_8859_1)
                    }

                    "iTXt" -> {
                        val z = data.indexOf(0)
                        val key = String(data, 0, z, Charsets.ISO_8859_1)
                        val compressed = data[z + 1].toInt() == 1
                        var p = z + 3
                        while (data[p].toInt() != 0) p++ // language
                        p++
                        while (data[p].toInt() != 0) p++ // translated keyword
                        p++
                        val raw = data.copyOfRange(p, data.size)
                        val text = if (compressed) InflaterInputStream(raw.inputStream()).readBytes() else raw
                        result[key] = String(text, Charsets.UTF_8)
                    }
                }
            }
        }
        return result
    }

    private fun ByteArray.indexOf(b: Int): Int {
        for (i in indices) if (this[i].toInt() == b) return i
        return -1
    }
}

fun GenerationParameters.toA1111(modelName: String?, mode: GenerationMode = this.mode): A1111Params = A1111Params(
    prompt = prompt,
    negativePrompt = negativePrompt,
    steps = steps,
    cfg = cfg,
    seed = seed,
    scheduler = scheduler,
    width = width,
    height = height,
    model = modelName,
    denoiseStrength = if (mode == GenerationMode.IMG2IMG || mode == GenerationMode.INPAINT) denoiseStrength else null,
)
