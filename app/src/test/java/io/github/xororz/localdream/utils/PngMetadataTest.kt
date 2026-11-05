package io.github.xororz.localdream.utils

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

class PngMetadataTest {
    private fun chunk(type: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val t = type.toByteArray(Charsets.ISO_8859_1)
        out.write(byteArrayOf((data.size ushr 24).toByte(), (data.size ushr 16).toByte(), (data.size ushr 8).toByte(), data.size.toByte()))
        out.write(t); out.write(data)
        val crc = CRC32().apply { update(t); update(data) }.value
        out.write(byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte()))
        return out.toByteArray()
    }

    /** Minimal 1x1 RGBA PNG. */
    private fun tinyPng(): ByteArray {
        val ihdr = byteArrayOf(0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0)
        val raw = ByteArrayOutputStream().also { b -> DeflaterOutputStream(b).use { it.write(byteArrayOf(0, 1, 2, 3, 4)) } }.toByteArray()
        return byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10) + chunk("IHDR", ihdr) + chunk("IDAT", raw) + chunk("IEND", ByteArray(0))
    }

    private fun roundTrip(params: A1111Params): A1111Params {
        val png = PngMetadata.withText(tinyPng(), PngMetadata.KEY_PARAMETERS, params.format())
        val text = PngMetadata.readText(png.inputStream())[PngMetadata.KEY_PARAMETERS]!!
        return A1111Params.parse(text)!!
    }

    @Test
    fun asciiRoundTrip() {
        val p = A1111Params(
            prompt = "1girl, (masterpiece:1.2), smile\nsecond line",
            negativePrompt = "lowres, bad hands",
            steps = 20, cfg = 7f, seed = 123L, scheduler = "dpm_karras",
            width = 512, height = 768, model = "Anything V5", denoiseStrength = 0.6f,
        )
        assertEquals(p, roundTrip(p))
    }

    @Test
    fun unicodeRoundTrip() {
        val p = A1111Params(prompt = "café, 猫, ★", negativePrompt = "ruim", steps = 8, cfg = 1.5f, seed = 1L, scheduler = "euler_a")
        assertEquals(p, roundTrip(p))
    }
}
