package app.biblioscan.match

import app.biblioscan.BuildConfig
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Free book APIs, French-language editions only (books may be translations). SPEC §7. */
object BookSearch {
    private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()

    /**
     * Tries each query on Google Books then Open Library, stopping at the first confident match.
     * One source failing (e.g. Google 429 without a key) falls through to the next; throws only if every call failed.
     */
    fun search(queries: List<String>, ocrText: String): Decision {
        val found = mutableListOf<Candidate>()
        var failure: IOException? = null
        var succeeded = false
        for (q in queries.filter { it.isNotBlank() }.distinct()) {
            for (source in listOf(::googleBooks, ::openLibrary)) {
                try {
                    found += source(q)
                    succeeded = true
                } catch (e: IOException) {
                    failure = e
                    continue
                }
                val d = Matching.decide(found, ocrText)
                if (d.verdict == Verdict.MATCHED) return d
            }
        }
        if (!succeeded && failure != null) throw failure // offline or all APIs down: let WorkManager retry
        return Matching.decide(found, ocrText)
    }

    fun googleBooks(q: String): List<Candidate> {
        val url = "https://www.googleapis.com/books/v1/volumes".toHttpUrl().newBuilder()
            .addQueryParameter("q", q)
            .addQueryParameter("langRestrict", "fr")
            .addQueryParameter("printType", "books")
            .addQueryParameter("maxResults", "10")
            .apply { if (BuildConfig.BOOKS_API_KEY.isNotBlank()) addQueryParameter("key", BuildConfig.BOOKS_API_KEY) }
            .build()
        return json.decodeFromString<GResponse>(get(url)).items.map { item ->
            val v = item.volumeInfo
            val ids = v.industryIdentifiers
            Candidate(
                sourceId = "google:${item.id}",
                title = v.title, // subtitle left out: rarely on the cover, it would lower the score
                authors = v.authors,
                publisher = v.publisher,
                year = v.publishedDate?.take(4),
                isbn = (ids.firstOrNull { it.type == "ISBN_13" } ?: ids.firstOrNull { it.type == "ISBN_10" })?.identifier,
                coverUrl = v.imageLinks?.thumbnail?.replace("http://", "https://"),
            )
        }
    }

    fun openLibrary(q: String): List<Candidate> {
        val url = "https://openlibrary.org/search.json".toHttpUrl().newBuilder()
            .addQueryParameter("q", q)
            .addQueryParameter("language", "fre")
            .addQueryParameter("limit", "10")
            .addQueryParameter("fields", "key,title,author_name,publisher,first_publish_year,isbn,cover_i")
            .build()
        return json.decodeFromString<OlResponse>(get(url)).docs.map { d ->
            Candidate(
                sourceId = "openlibrary:${d.key}",
                title = d.title,
                authors = d.author_name,
                publisher = d.publisher.firstOrNull(),
                year = d.first_publish_year?.toString(),
                isbn = d.isbn.firstOrNull { it.length == 13 } ?: d.isbn.firstOrNull(),
                coverUrl = d.cover_i?.let { "https://covers.openlibrary.org/b/id/$it-M.jpg" },
            )
        }
    }

    private fun get(url: HttpUrl): String {
        val req = Request.Builder().url(url).header("User-Agent", "BiblioScan/0.1 (personal Android app)").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} on ${url.host}")
            return r.body!!.string()
        }
    }

    @Serializable private class GResponse(val items: List<GItem> = emptyList())
    @Serializable private class GItem(val id: String, val volumeInfo: GVolume)
    @Serializable private class GVolume(
        val title: String = "",
        val authors: List<String> = emptyList(),
        val publisher: String? = null,
        val publishedDate: String? = null,
        val industryIdentifiers: List<GId> = emptyList(),
        val imageLinks: GImages? = null,
    )
    @Serializable private class GId(val type: String, val identifier: String)
    @Serializable private class GImages(val thumbnail: String? = null)

    @Suppress("PropertyName")
    @Serializable private class OlResponse(val docs: List<OlDoc> = emptyList())
    @Suppress("PropertyName")
    @Serializable private class OlDoc(
        val key: String,
        val title: String = "",
        val author_name: List<String> = emptyList(),
        val publisher: List<String> = emptyList(),
        val first_publish_year: Int? = null,
        val isbn: List<String> = emptyList(),
        val cover_i: Long? = null,
    )
}
