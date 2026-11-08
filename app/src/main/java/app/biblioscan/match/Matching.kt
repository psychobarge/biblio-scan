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
)

enum class Verdict { MATCHED, NEEDS_REVIEW, NOT_FOUND }

data class Decision(val verdict: Verdict, val ranked: List<Candidate>)

/** Pure text logic (no Android): OCR lines -> query, candidates -> verdict. Thresholds: SPEC §8, to calibrate on real books. */
object Matching {
    const val AUTO = 0.8
    const val GAP = 0.15
    const val REVIEW = 0.4

    // Cover noise. shortcut: hand-written French list, extend it from real misses.
    private val dropLineWords = setOf("prix", "traduit", "traduction", "preface", "postface")
    private val noisePhrases = listOf("livre de poche", "j ai lu")
    private val noiseWords = setOf("editions", "edition", "roman", "prix", "collection", "poche", "folio", "points", "pocket", "inedit", "nouvelle")
    // Publisher/collection marks often printed along the edges; the OCR glues them into one token
    // ("garnierflammarionh", "texteintegralgarnier…"), so they are matched inside words.
    private val noiseInside = listOf("flammarion", "garnier", "integral", "gallimard")

    fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    fun tokens(s: String): Set<String> = normalize(s).split(' ').filter { it.length >= 2 }.toSet()

    fun clean(line: String): String {
        var n = " ${normalize(line)} "
        if (n.split(' ').any { it in dropLineWords }) return "" // "Prix Goncourt", "Traduit de l'anglais par…"
        noisePhrases.forEach { n = n.replace(" $it ", " ") }
        return n.split(' ')
            .filter { w -> w.isNotEmpty() && w !in noiseWords && noiseInside.none { w.contains(it) } }
            .joinToString(" ")
    }

    private fun cleanedBySize(lines: List<OcrLine>) =
        lines.map { it.copy(text = clean(it.text)) }.filter { it.text.length >= 2 }.sortedByDescending { it.height }

    /** Biggest text first: title and author are almost always the largest print on a cover. */
    fun buildQuery(lines: List<OcrLine>): String {
        val q = cleanedBySize(lines).take(4).joinToString(" ") { it.text }
        return if (q.length <= 100) q else q.take(100).substringBeforeLast(' ')
    }

    fun biggestLine(lines: List<OcrLine>): String? = cleanedBySize(lines).firstOrNull()?.text

    fun score(c: Candidate, ocr: Set<String>): Double {
        fun part(words: Set<String>) = if (words.isEmpty()) 0.0 else words.count { it in ocr }.toDouble() / words.size
        val t = part(tokens(c.title))
        val a = c.authors.maxOfOrNull { part(tokens(it)) } ?: 0.0
        return 0.6 * t + 0.4 * a
    }

    private fun workKey(c: Candidate) = normalize(c.title) + "|" + normalize(c.authors.firstOrNull().orEmpty())

    fun decide(candidates: List<Candidate>, ocrText: String): Decision {
        val ocr = tokens(ocrText)
        val ranked = candidates.distinctBy { it.sourceId }
            .map { it.copy(score = score(it, ocr)) }
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
