package io.github.xororz.localdream.data

import android.content.Context
import androidx.core.content.edit

/**
 * Port of the local native backend. Upstream Local Dream hard-codes 8081;
 * here it is a setting so this app can run next to the original one, whose
 * backend may already hold 8081.
 */
object BackendPort {
    const val DEFAULT = 8081
    private const val PREFS = "app_prefs"
    private const val KEY = "backend_port"

    @Volatile
    var port: Int = DEFAULT
        private set

    val host: String get() = "localhost:$port"

    fun init(context: Context) {
        port = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, DEFAULT)
    }

    fun isValid(value: Int): Boolean = value in 1024..65535

    fun set(context: Context, value: Int) {
        if (!isValid(value)) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putInt(KEY, value) }
        port = value
    }
}
