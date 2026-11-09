package app.biblioscan.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log
import app.biblioscan.app
import app.biblioscan.work.Processing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Book lifecycle operations shared by the UI, the workers and the backup code. */
object Books {
    fun coversDir(ctx: Context) = File(ctx.filesDir, "covers").apply { mkdirs() }
    fun photoFile(ctx: Context, id: String) = File(coversDir(ctx), "$id.jpg")

    /** Called once the JPEG is on disk: the photo exists before its row, so a crash in between is healed by [reconcile]. */
    suspend fun onPhotoSaved(ctx: Context, id: String) {
        ctx.app.db.books().insert(listOf(Book(id = id, createdAt = System.currentTimeMillis(), status = Status.CAPTURED)))
        Processing.enqueueOcr(ctx)
    }

    const val MAX_SIDE = 1920

    /** Size to decode an imported photo to: long side capped at [max], ratio kept, never upscaled. */
    fun targetSize(w: Int, h: Int, max: Int = MAX_SIDE): Pair<Int, Int> {
        val long = maxOf(w, h)
        if (long <= max) return w to h
        val s = max.toDouble() / long
        return maxOf(1, Math.round(w * s).toInt()) to maxOf(1, Math.round(h * s).toInt())
    }

    data class ImportResult(val ok: Int, val failed: Int)

    /**
     * Copies gallery photos into the app (SPEC-3 §4): decoded with EXIF orientation, downscaled,
     * written via .tmp then renamed, row inserted only once the photo is on disk.
     * Picker URIs only live for this session, so this runs to completion in the app scope.
     */
    suspend fun importPhotos(ctx: Context, uris: List<Uri>): ImportResult = withContext(Dispatchers.IO) {
        val dao = ctx.app.db.books()
        val start = System.currentTimeMillis()
        var ok = 0
        var failed = 0
        uris.forEachIndexed { i, uri ->
            val id = UUID.randomUUID().toString()
            val dest = photoFile(ctx, id)
            val tmp = File(dest.path + ".tmp")
            try {
                val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
                    val (w, h) = targetSize(info.size.width, info.size.height)
                    d.setTargetSize(w, h)
                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // compress() needs a CPU-side bitmap
                }
                tmp.outputStream().use { check(bmp.compress(Bitmap.CompressFormat.JPEG, 90, it)) { "encodage JPEG" } }
                bmp.recycle()
                check(tmp.renameTo(dest)) { "renommage" }
                // +i keeps the selection order in the "date added" sort.
                dao.insert(listOf(Book(id = id, createdAt = start + i, status = Status.CAPTURED)))
                ok++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("BiblioScan", "import failed for $uri", e)
                tmp.delete()
                failed++
            }
            if ((i + 1) % 10 == 0) Processing.enqueueOcr(ctx) // start OCR without waiting for the whole batch
        }
        Processing.enqueueOcr(ctx)
        ImportResult(ok, failed)
    }

    /** Startup safety net: every photo gets a row, and every unfinished row gets processed. */
    suspend fun reconcile(ctx: Context) {
        val dao = ctx.app.db.books()
        val known = dao.ids().toSet()
        coversDir(ctx).listFiles { f -> f.extension == "tmp" }.orEmpty().forEach { it.delete() } // interrupted writes
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

    /**
     * After the noise list changed (SPEC-4 §4.4): clear the query of every book the user has not
     * validated (to review, not found, or auto-matched) so the lookup rebuilds it (no new OCR) and
     * search again. Confirmed books are never touched.
     */
    suspend fun reapplyNoise(ctx: Context): Int {
        val dao = ctx.app.db.books()
        val books = listOf(Status.NEEDS_REVIEW, Status.NOT_FOUND, Status.MATCHED)
            .flatMap { dao.byStatus(it) }
            .filter { it.ocrLinesJson != null }
        books.forEach { b ->
            // Null query: the lookup rebuilds it (LLM, else OCR with the new noise list), SPEC-5.
            var next = b.copy(status = Status.OCR_DONE, query = null, attempts = 0, lastError = null)
            // An automatic match filled these fields, not the user: drop them so a wrong match doesn't linger.
            if (b.status == Status.MATCHED) next = next.copy(
                title = null, authors = null, publisher = null, year = null, isbn = null, sourceId = null, coverUrl = null, score = null,
            )
            dao.update(next)
        }
        if (books.isNotEmpty()) Processing.enqueueLookup(ctx)
        return books.size
    }

    suspend fun delete(ctx: Context, id: String) {
        // Photo first: a leftover photo without a row would be resurrected by reconcile().
        photoFile(ctx, id).delete()
        ctx.app.db.books().delete(id)
    }
}
