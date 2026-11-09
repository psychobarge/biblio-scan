package app.biblioscan.match

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

    /** "title author" to search, or null if the model could read neither. Throws on HTTP/network errors. */
    fun read(jpeg: ByteArray, key: String): String? {
        val body = buildJsonObject {
            put("model", MODEL)
            put("max_tokens", 200)
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
        val text = http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} on api.deepseek.com")
            r.body!!.string()
        }
        return parseAnswer(json.decodeFromString<Response>(text).choices.firstOrNull()?.message?.content.orEmpty())
    }

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
