package app.biblioscan.work

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.biblioscan.app
import app.biblioscan.data.Book
import app.biblioscan.data.BookDao
import app.biblioscan.data.Books
import app.biblioscan.data.Settings
import app.biblioscan.data.Status
import app.biblioscan.match.BookSearch
import app.biblioscan.match.CoverReader
import app.biblioscan.match.Matching
import app.biblioscan.match.NoiseEntry
import app.biblioscan.match.OcrLine
import app.biblioscan.match.Verdict
import app.biblioscan.match.json
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlin.math.hypot
import kotlinx.serialization.encodeToString

/**
 * The DB status is the source of truth; workers just process every row in their input status,
 * so they are idempotent and safe to re-run (SPEC §5).
 */
object Processing {
    private const val MAX_ATTEMPTS = 5

    // Separate unique chains: OCR must never wait behind a lookup that is waiting for the network.
    fun enqueueOcr(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            "ocr", ExistingWorkPolicy.APPEND_OR_REPLACE, OneTimeWorkRequestBuilder<OcrWorker>().build()
        )
    }

    fun enqueueLookup(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<LookupWorker>()
            .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("lookup", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    /** Runs [step] on every book in [status]. Returns true if some book failed and deserves a retry. */
    suspend fun forEach(dao: BookDao, status: Status, step: suspend (Book) -> Book): Boolean {
        var retry = false
        for (book in dao.byStatus(status)) {
            try {
                dao.update(step(book))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val attempts = book.attempts + 1
                val giveUp = attempts >= MAX_ATTEMPTS
                dao.update(book.copy(attempts = attempts, lastError = e.toString(), status = if (giveUp) Status.ERROR else status))
                retry = retry || !giveUp
            }
        }
        return retry
    }
}

class OcrWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val retry = Processing.forEach(ctx.app.db.books(), Status.CAPTURED) { book ->
            // fromFilePath applies the JPEG's EXIF rotation.
            val image = InputImage.fromFilePath(ctx, Uri.fromFile(Books.photoFile(ctx, book.id)))
            val text = Tasks.await(recognizer.process(image))
            // Text size = letter height measured along the line's own orientation (corner 0 = top-left,
            // 3 = bottom-left in reading direction). An axis-aligned box would make a long vertical or
            // tilted line (collection name along the edge) look like huge text.
            val lines = text.textBlocks.flatMap { it.lines }.map { line ->
                val c = line.cornerPoints
                val size = if (c != null && c.size == 4) hypot((c[3].x - c[0].x).toDouble(), (c[3].y - c[0].y).toDouble()).toInt()
                else line.boundingBox?.let { minOf(it.width(), it.height()) } ?: 0
                OcrLine(line.text, size)
            }
            book.copy(
                status = Status.OCR_DONE,
                ocrText = text.text,
                ocrLinesJson = json.encodeToString(lines),
                attempts = 0,
                lastError = null,
            )
        }
        Processing.enqueueLookup(ctx)
        return if (retry) Result.retry() else Result.success()
    }
}

class LookupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        // Read once per pass: edits apply to the next books.
        val noise = Settings.noise(ctx)
        val booksKey = Settings.key(ctx, Settings.BOOKS_KEY)
        val deepseekKey = Settings.key(ctx, Settings.DEEPSEEK_KEY)
        val dao = ctx.app.db.books()
        Log.i(TAG, "lookup pass: ${dao.byStatus(Status.OCR_DONE).size} book(s), deepseek key=${CoverReader.maskKey(deepseekKey)}, " +
            "books key=${if (booksKey.isBlank()) "absent" else "present"}")
        val retry = Processing.forEach(dao, Status.OCR_DONE) { book ->
            val tag = book.id.take(8)
            val lines = book.ocrLinesJson?.let { json.decodeFromString<List<OcrLine>>(it) }.orEmpty()
            val query = chooseQuery(book, lines, noise, deepseekKey, tag)
            // Score against the query too: a title typed by hand (or read by the LLM) must count even when the OCR misread it.
            val reference = listOfNotNull(book.ocrText, query).joinToString("\n")
            val d = BookSearch.search(listOfNotNull(query, Matching.biggestLine(lines, noise)), reference, booksKey)
            val top = d.ranked.firstOrNull()
            Log.i(TAG, "lookup $tag: verdict=${d.verdict} best=${top?.title?.let { "\"$it\"" }} score=${top?.score?.let { "%.2f".format(it) }}")
            val best = top?.takeIf { d.verdict == Verdict.MATCHED }
            book.copy(
                status = Status.valueOf(d.verdict.name),
                query = query,
                candidatesJson = json.encodeToString(d.ranked.take(5)),
                score = d.ranked.firstOrNull()?.score,
                title = best?.title ?: book.title,
                authors = best?.authors?.joinToString("; ") ?: book.authors,
                publisher = best?.publisher ?: book.publisher,
                year = best?.year ?: book.year,
                isbn = best?.isbn ?: book.isbn,
                sourceId = best?.sourceId ?: book.sourceId,
                coverUrl = best?.coverUrl ?: book.coverUrl,
                attempts = 0,
                lastError = null,
            )
        }
        return if (retry) Result.retry() else Result.success()
    }

    /**
     * A query already on the book (typed, or kept from a previous lookup) wins. Otherwise the LLM reads the cover
     * and the OCR is the fallback (SPEC-5): any LLM failure (no key, offline, bad key, quota) must not block the book.
     * Every path is logged (SPEC-6 §1).
     */
    private fun chooseQuery(book: Book, lines: List<OcrLine>, noise: List<NoiseEntry>, key: String, tag: String): String {
        book.query?.let {
            Log.i(TAG, "lookup $tag: query source=kept query=\"$it\"")
            return it
        }
        val why = if (key.isBlank()) "no key" else try {
            CoverReader.read(Books.photoFile(applicationContext, book.id).readBytes(), key, tag)?.let {
                Log.i(TAG, "lookup $tag: query source=deepseek query=\"$it\"")
                return it
            }
            "no title read"
        } catch (e: Exception) {
            Log.w(TAG, "deepseek $tag: failed: $e")
            "failed"
        }
        return Matching.buildQuery(lines, noise).also { Log.i(TAG, "lookup $tag: query source=ocr (deepseek: $why) query=\"$it\"") }
    }

    private companion object { const val TAG = "BiblioScan" }
}
