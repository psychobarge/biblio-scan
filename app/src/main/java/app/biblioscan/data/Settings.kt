package app.biblioscan.data

import android.content.Context
import app.biblioscan.match.Matching
import app.biblioscan.match.NoiseEntry
import app.biblioscan.match.json
import kotlinx.serialization.encodeToString

/** User settings (SPEC-4 §4). Absent or unreadable = defaults, so a bad value can never block processing. */
object Settings {
    private const val NOISE = "noise"
    // API keys live only on the device (SPEC-5): never in the APK, the repo or the ZIP backup.
    const val BOOKS_KEY = "books_key"
    const val DEEPSEEK_KEY = "deepseek_key"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun noise(ctx: Context): List<NoiseEntry> =
        prefs(ctx).getString(NOISE, null)
            ?.let { runCatching { json.decodeFromString<List<NoiseEntry>>(it) }.getOrNull() }
            ?: Matching.DEFAULT_NOISE

    fun noiseIsDefault(ctx: Context) = !prefs(ctx).contains(NOISE)

    fun noiseJson(ctx: Context): String = json.encodeToString(noise(ctx))

    // commit(): the list must be on disk before a worker reads it.
    fun setNoise(ctx: Context, list: List<NoiseEntry>) {
        prefs(ctx).edit().putString(NOISE, json.encodeToString(list)).commit()
    }

    fun key(ctx: Context, name: String): String = prefs(ctx).getString(name, null).orEmpty()

    fun setKey(ctx: Context, name: String, value: String) {
        prefs(ctx).edit().putString(name, value.trim()).commit()
    }

    fun resetNoise(ctx: Context) {
        prefs(ctx).edit().remove(NOISE).commit()
    }
}
