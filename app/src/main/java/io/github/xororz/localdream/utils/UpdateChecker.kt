package io.github.xororz.localdream.utils

import io.github.xororz.localdream.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Manual "is there a newer build of this fork?" check, run only when the user
 * taps the button in Settings. No polling, no telemetry.
 *
 * Uses /releases/latest, which skips drafts and pre-releases: test builds go
 * out as pre-releases and must not be offered to everyone.
 *
 * Versions are compared by versionCode, read from the APK asset name
 * (LocalDreamCanvas_armv8a_<versionName>_<versionCode>.apk). Older releases
 * without the number in the name never count as newer.
 */
object UpdateChecker {
    private const val LATEST_URL = "https://api.github.com/repos/denius72/local-dream-canvas/releases/latest"
    private val CODE_IN_NAME = Regex("""_(\d+)\.apk$""")

    sealed class Result {
        data class NewVersion(val name: String, val url: String) : Result()
        object UpToDate : Result()
        object Failed : Result()
    }

    private val client by lazy {
        Http.client.newBuilder()
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    suspend fun check(): Result = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(LATEST_URL)
                .header("Accept", "application/vnd.github+json")
                .build()
            client.newCall(request).execute().use { response ->
                // 404 = no full release yet.
                if (response.code == 404) return@runCatching Result.UpToDate
                if (!response.isSuccessful) return@runCatching Result.Failed
                val release = JSONObject(response.body?.string() ?: return@runCatching Result.Failed)
                val assets = release.optJSONArray("assets")
                var newest = -1L
                for (i in 0 until (assets?.length() ?: 0)) {
                    val name = assets!!.getJSONObject(i).optString("name")
                    val code = CODE_IN_NAME.find(name)?.groupValues?.get(1)?.toLongOrNull() ?: continue
                    if (code > newest) newest = code
                }
                if (newest > BuildConfig.VERSION_CODE) {
                    Result.NewVersion(
                        name = release.optString("name").ifBlank { release.optString("tag_name") },
                        url = release.optString("html_url"),
                    )
                } else {
                    Result.UpToDate
                }
            }
        }.getOrDefault(Result.Failed)
    }
}
