package app.biblioscan.work

import android.content.Context
import android.net.Uri
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
import app.biblioscan.data.Status
import app.biblioscan.match.BookSearch
import app.biblioscan.match.Matching
import app.biblioscan.match.OcrLine
import app.biblioscan.match.Verdict
import app.biblioscan.match.json
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
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
            val lines = text.textBlocks.flatMap { it.lines }.map { OcrLine(it.text, it.boundingBox?.height() ?: 0) }
            book.copy(
                status = Status.OCR_DONE,
                ocrText = text.text,
                ocrLinesJson = json.encodeToString(lines),
                query = book.query ?: Matching.buildQuery(lines), // keep a query the user typed before OCR ran
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
        val retry = Processing.forEach(applicationContext.app.db.books(), Status.OCR_DONE) { book ->
            val lines = book.ocrLinesJson?.let { json.decodeFromString<List<OcrLine>>(it) }.orEmpty()
            // Score against the query too: a title typed by hand must count even when the OCR misread it.
            val reference = listOfNotNull(book.ocrText, book.query).joinToString("\n")
            val d = BookSearch.search(listOfNotNull(book.query, Matching.biggestLine(lines)), reference)
            val best = d.ranked.firstOrNull()?.takeIf { d.verdict == Verdict.MATCHED }
            book.copy(
                status = Status.valueOf(d.verdict.name),
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
}
