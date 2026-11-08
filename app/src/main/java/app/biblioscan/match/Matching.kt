package app.biblioscan.match

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.text.Normalizer

val json = Json { ignoreUnknownKeys = true }

@Serializable
data class OcrLine(val text: String, val height: Int)

@Serializable
data class Candidate(
    val sourceId: String,
    val title: String,
    val authors: List<String> = emptyList(),
    val publisher: String? = null,
    val year: String? = null,
    val isbn: String? = null,
    val coverUrl: String? = null,
    val score: Double = 0.0,
    /** ISO code from the API ("fr", "en"…); null = unknown (Open Library). */
    val language: String? = null,
)

/** A word or phrase ignored on covers (SPEC-4 §4). [wholeLine] = drop the entire line that contains it. */
@Serializable
data class NoiseEntry(val text: String, val wholeLine: Boolean = false)

enum class Verdict { MATCHED, NEEDS_REVIEW, NOT_FOUND }

data class Decision(val verdict: Verdict, val ranked: List<Candidate>)

/** Pure text logic (no Android): OCR lines -> query, candidates -> verdict. Thresholds: SPEC §8, to calibrate on real books. */
object Matching {
    const val AUTO = 0.8
    const val GAP = 0.15
    const val REVIEW = 0.4
    const val FOREIGN_PENALTY = 0.5
    /** Single words this long are also removed from inside OCR-glued words ("garnierflammarionh"). */
    const val INSIDE_MIN = 7

    /** Default cover noise; the user edits a copy in Settings. */
    val DEFAULT_NOISE: List<NoiseEntry> =
        listOf("prix", "traduit", "traduction", "preface", "postface").map { NoiseEntry(it, wholeLine = true) } +
            listOf(
                "editions", "edition", "roman", "collection", "poche", "folio", "points", "pocket", "inedit", "nouvelle",
                "livre de poche", "j ai lu",
                "flammarion", "garnier", "integral", "gallimard",
                "fantasy", "science fiction", "sf", "thriller", "policier", "polar", "bd", "manga", "jeunesse",
            ).map { NoiseEntry(it) }

    fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    fun tokens(s: String): Set<String> = normalize(s).split(' ').filter { it.length >= 2 }.toSet()

    /** Adds a user entry, normalized like OCR text. Null = blank or already in the list. */
    fun withEntry(list: List<NoiseEntry>, raw: String, wholeLine: Boolean = false): List<NoiseEntry>? {
        val text = normalize(raw)
        if (text.isEmpty() || list.any { it.text == text }) return null
        return list + NoiseEntry(text, wholeLine)
    }

    private fun insideWord(e: NoiseEntry) = ' ' !in e.text && e.text.length >= INSIDE_MIN

    private fun phrase(e: NoiseEntry) = Regex("(?<= )${Regex.escape(e.text)}(?= )")

    fun clean(line: String, noise: List<NoiseEntry> = DEFAULT_NOISE): String {
        var n = " ${normalize(line)} "
        val words = n.split(' ').filter { it.isNotEmpty() }
        fun found(e: NoiseEntry) = phrase(e).containsMatchIn(n) || (insideWord(e) && words.any { it.contains(e.text) })
        if (noise.any { it.wholeLine && found(it) }) return "" // "Prix Goncourt", "Traduit de l'anglais par…"
        noise.filter { !it.wholeLine }.forEach { n = phrase(it).replace(n, "") }
        val inside = noise.filter { !it.wholeLine && insideWord(it) }.map { it.text }
        return n.split(' ')
            // Lone numbers are volume numbers or prices: they make Google miss the right edition.
            .filter { w -> w.isNotEmpty() && !w.all(Char::isDigit) && inside.none { w.contains(it) } }
            .joinToString(" ")
    }

    private fun cleanedBySize(lines: List<OcrLine>, noise: List<NoiseEntry>) =
        lines.map { it.copy(text = clean(it.text, noise)) }.filter { it.text.length >= 2 }.sortedByDescending { it.height }

    /** Biggest text first: title and author are almost always the largest print on a cover. */
    fun buildQuery(lines: List<OcrLine>, noise: List<NoiseEntry> = DEFAULT_NOISE): String {
        val q = cleanedBySize(lines, noise).take(4).joinToString(" ") { it.text }
        return if (q.length <= 100) q else q.take(100).substringBeforeLast(' ')
    }

    fun biggestLine(lines: List<OcrLine>, noise: List<NoiseEntry> = DEFAULT_NOISE): String? =
        cleanedBySize(lines, noise).firstOrNull()?.text

    /**
     * Title is scored both ways (SPEC-4 §2.2): p = candidate title words found on the cover,
     * r = query words explained by the candidate's title + authors. Their harmonic mean stops a
     * short title (series or cycle name) from scoring 1.0 just because its one word is on the cover.
     */
    fun score(c: Candidate, ocr: Set<String>, query: Set<String>): Double {
        fun part(words: Set<String>) = if (words.isEmpty()) 0.0 else words.count { it in ocr }.toDouble() / words.size
        val title = tokens(c.title)
        val explained = title + c.authors.flatMap { tokens(it) }
        val p = part(title)
        val r = if (query.isEmpty()) p else query.count { it in explained }.toDouble() / query.size
        val t = if (p + r == 0.0) 0.0 else 2 * p * r / (p + r)
        val a = c.authors.maxOfOrNull { part(tokens(it)) } ?: 0.0
        val s = 0.6 * t + 0.4 * a
        // Google's langRestrict leaks foreign editions: keep them listed, never auto-picked.
        return if (c.language != null && !c.language.startsWith("fr")) s * FOREIGN_PENALTY else s
    }

    private fun workKey(c: Candidate) = normalize(c.title) + "|" + normalize(c.authors.firstOrNull().orEmpty())

    /** [ocrText] = what is on the cover; [query] = what was searched (cleaned or typed by the user). */
    fun decide(candidates: List<Candidate>, ocrText: String, query: String): Decision {
        val ocr = tokens(ocrText)
        val q = tokens(query)
        val ranked = candidates.distinctBy { it.sourceId }
            .map { it.copy(score = score(it, ocr, q)) }
            .sortedByDescending { it.score }
        val best = ranked.firstOrNull() ?: return Decision(Verdict.NOT_FOUND, ranked)
        // Other editions of the same work are not ambiguity: compare against the best *different* work.
        val second = ranked.firstOrNull { workKey(it) != workKey(best) }?.score ?: 0.0
        val verdict = when {
            best.score >= AUTO && best.score - second >= GAP -> Verdict.MATCHED
            best.score >= REVIEW -> Verdict.NEEDS_REVIEW
            else -> Verdict.NOT_FOUND
        }
        return Decision(verdict, ranked)
    }
}
