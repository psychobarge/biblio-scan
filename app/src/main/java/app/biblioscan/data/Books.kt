package app.biblioscan.data

import android.content.Context
import app.biblioscan.app
import app.biblioscan.work.Processing
import java.io.File

/** Book lifecycle operations shared by the UI, the workers and the backup code. */
object Books {
    fun coversDir(ctx: Context) = File(ctx.filesDir, "covers").apply { mkdirs() }
    fun photoFile(ctx: Context, id: String) = File(coversDir(ctx), "$id.jpg")

    /** Called once the JPEG is on disk: the photo exists before its row, so a crash in between is healed by [reconcile]. */
    suspend fun onPhotoSaved(ctx: Context, id: String) {
        ctx.app.db.books().insert(listOf(Book(id = id, createdAt = System.currentTimeMillis(), status = Status.CAPTURED)))
        Processing.enqueueOcr(ctx)
    }

    /** Startup safety net: every photo gets a row, and every unfinished row gets processed. */
    suspend fun reconcile(ctx: Context) {
        val dao = ctx.app.db.books()
        val known = dao.ids().toSet()
        val orphans = coversDir(ctx).listFiles().orEmpty()
            .filter { it.extension == "jpg" && it.length() > 0 && it.nameWithoutExtension !in known }
            .map { Book(id = it.nameWithoutExtension, createdAt = it.lastModified(), status = Status.CAPTURED) }
        dao.insert(orphans)
        Processing.enqueueOcr(ctx) // also enqueues lookup when OCR is done
    }

    /** Re-run processing from the right step (after ERROR, or with a user-corrected query). */
    suspend fun retry(ctx: Context, book: Book, query: String? = book.query) {
        val status = if (book.ocrText == null) Status.CAPTURED else Status.OCR_DONE
        ctx.app.db.books().update(book.copy(status = status, query = query, attempts = 0, lastError = null))
        Processing.enqueueOcr(ctx)
    }

    suspend fun delete(ctx: Context, id: String) {
        // Photo first: a leftover photo without a row would be resurrected by reconcile().
        photoFile(ctx, id).delete()
        ctx.app.db.books().delete(id)
    }
}
