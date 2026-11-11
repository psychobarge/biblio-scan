package app.biblioscan.match

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Reads title and author off the cover photo with DeepSeek Flash (SPEC-5): ML Kit OCR is poor on book covers. */
object CoverReader {
    private val http = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()
    private const val URL = "https://api.deepseek.com/chat/completions"
    private const val MODEL = "deepseek-flash"
    private const val PROMPT =
        "Photo d'un livre (couverture, dos ou page de titre). Donne uniquement son titre et son auteur, " +
            "tels qu'imprimés, en JSON : {\"title\": \"...\", \"author\": \"...\"}. " +
            "Ignore l'éditeur, la collection, le genre, les prix littéraires, le traducteur, le préfacier, les citations. " +
            "Mets null pour ce que tu ne lis pas avec certitude : n'invente rien."

    /** For logs (SPEC-6 §1): last 4 characters and length only, enough to match the key in the DeepSeek console. */
    fun maskKey(key: String): String = if (key.isBlank()) "absent" else "present (sk-…${key.takeLast(4)}, ${key.length} chars)"

    /** "title author" to search, or null if the model could read neither. Throws on HTTP/network errors. [tag] = book id for logs. */
    fun read(jpeg: ByteArray, key: String, tag: String = ""): String? {
        val body = buildJsonObject {
            put("model", MODEL)
            // Thinking is on by default and its reasoning ate the whole token budget: empty content (seen on the Pixel 6).
            putJsonObject("thinking") { put("type", "disabled") }
            put("max_tokens", 1000)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject { put("type", "text"); put("text", PROMPT) }
                        addJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") { put("url", "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg)) }
                        }
                    }
                }
            }
        }.toString()
        val req = Request.Builder().url(URL)
            .header("Authorization", "Bearer $key")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        Log.i(TAG, "deepseek $tag: POST model=$MODEL image=${jpeg.size / 1024} KB")
        val start = System.nanoTime()
        val text = http.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            val took = "in %.1f s".format((System.nanoTime() - start) / 1e9)
            if (!r.isSuccessful) {
                // The body is where DeepSeek says why (unknown model, image not supported, quota…).
                Log.w(TAG, "deepseek $tag: HTTP ${r.code} $took, body=\"${body.take(500)}\"")
                throw IOException("HTTP ${r.code} on api.deepseek.com: ${body.take(500)}")
            }
            Log.i(TAG, "deepseek $tag: HTTP ${r.code} $took")
            body
        }
        val content = json.decodeFromString<Response>(text).choices.firstOrNull()?.message?.content.orEmpty()
        val parsed = parseAnswer(content)
        Log.i(TAG, "deepseek $tag: content=\"${content.take(300)}\" parsed=${parsed?.let { "\"$it\"" }}")
        // Empty answer: finish_reason, usage and any reasoning text in the raw body say why.
        if (content.isBlank()) Log.w(TAG, "deepseek $tag: empty content, raw body=\"${text.take(1500)}\"")
        return parsed
    }

    private const val TAG = "BiblioScan"

    /** Lenient: the model may wrap its JSON in prose or a code fence. */
    fun parseAnswer(content: String): String? {
        val inner = content.substringAfter('{', "").substringBeforeLast('}', "")
        val a = runCatching { json.decodeFromString<Answer>("{$inner}") }.getOrNull() ?: return null
        return listOfNotNull(a.title, a.author).filter { it.isNotBlank() }.joinToString(" ").ifBlank { null }
    }

    @Serializable private class Answer(val title: String? = null, val author: String? = null)
    @Serializable private class Response(val choices: List<Choice> = emptyList())
    @Serializable private class Choice(val message: Message)
    @Serializable private class Message(val content: String? = null)
}
