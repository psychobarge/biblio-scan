package app.biblioscan.data

import android.content.Context
import app.biblioscan.match.Matching
import app.biblioscan.match.NoiseEntry
import app.biblioscan.match.json
import kotlinx.serialization.encodeToString

/** User settings (SPEC-4 §4). Absent or unreadable = defaults, so a bad value can never block processing. */
object Settings {
    private const val NOISE = "noise"
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

    fun resetNoise(ctx: Context) {
        prefs(ctx).edit().remove(NOISE).commit()
    }
}
