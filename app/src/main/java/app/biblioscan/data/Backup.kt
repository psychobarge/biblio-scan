package app.biblioscan.data

import android.content.Context
import android.net.Uri
import app.biblioscan.app
import app.biblioscan.match.NoiseEntry
import app.biblioscan.match.json
import app.biblioscan.work.Processing
import kotlinx.serialization.encodeToString
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** ZIP = library.json + covers/{id}.jpg. Written outside the app (SAF), so it survives an uninstall. SPEC §10. */
object Backup {
    private const val LIBRARY = "library.json"
    private const val SETTINGS = "settings.json" // the noise list, only when customised
    private const val PLACES = "places.json" // rooms and spots (SPEC-7 §7)

    // Trust boundary: an imported ZIP may be anything. Only plain "covers/<uuid>.jpg" entries are accepted.
    private const val UUID = "[0-9a-fA-F-]{36}"
    private val coverEntry = Regex("covers/($UUID)\\.jpg")
    fun isValidId(id: String) = Regex(UUID).matches(id) // ids become file names

    fun coverIdOf(entryName: String): String? = coverEntry.matchEntire(entryName)?.groupValues?.get(1)

    suspend fun exportZip(ctx: Context, uri: Uri): Int {
        val books = ctx.app.db.books().snapshot()
        val places = ctx.app.db.places().snapshot()
        val out = ctx.contentResolver.openOutputStream(uri) ?: error("impossible d'écrire le fichier")
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(LIBRARY))
            zip.write(json.encodeToString(books).toByteArray())
            zip.closeEntry()
            if (places.isNotEmpty()) {
                zip.putNextEntry(ZipEntry(PLACES))
                zip.write(json.encodeToString(places).toByteArray())
                zip.closeEntry()
            }
            if (!Settings.noiseIsDefault(ctx)) {
                zip.putNextEntry(ZipEntry(SETTINGS))
                zip.write(Settings.noiseJson(ctx).toByteArray())
                zip.closeEntry()
            }
            for (b in books) {
                val photo = Books.photoFile(ctx, b.id).takeIf { it.exists() } ?: continue
                zip.putNextEntry(ZipEntry("covers/${b.id}.jpg"))
                photo.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return books.size
    }

    /** Adds missing books and photos; never overwrites an existing book. */
    suspend fun importZip(ctx: Context, uri: Uri): Int {
        var books = emptyList<Book>()
        var places = emptyList<Place>()
        val input = ctx.contentResolver.openInputStream(uri) ?: error("impossible de lire le fichier")
        ZipInputStream(input).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                if (entry.name == LIBRARY) {
                    books = json.decodeFromString<List<Book>>(zip.readBytes().decodeToString()).filter { isValidId(it.id) }
                } else if (entry.name == PLACES) {
                    places = runCatching { json.decodeFromString<List<Place>>(zip.readBytes().decodeToString()) }.getOrDefault(emptyList())
                } else if (entry.name == SETTINGS) {
                    // Never overwrite a list the user already customised on this phone.
                    val noise = runCatching { json.decodeFromString<List<NoiseEntry>>(zip.readBytes().decodeToString()) }.getOrNull()
                    if (noise != null && Settings.noiseIsDefault(ctx)) Settings.setNoise(ctx, noise)
                } else {
                    val id = coverIdOf(entry.name) ?: return@forEach
                    val dest = Books.photoFile(ctx, id)
                    if (!dest.exists()) {
                        // Via .tmp: a half-written photo must never look like a real capture to reconcile().
                        val tmp = java.io.File(dest.path + ".tmp")
                        tmp.outputStream().use { zip.copyTo(it) }
                        check(tmp.renameTo(dest)) { "photo $id non enregistrée" }
                    }
                }
            }
        }
        // Photos are on disk before rows exist, same invariant as a capture.
        val (ids, newPlaces) = Places.merge(ctx.app.db.places().snapshot(), places)
        ctx.app.db.places().insert(newPlaces)
        val dao = ctx.app.db.books()
        val before = dao.ids().size
        dao.insert(books.map { it.copy(placeId = it.placeId?.let(ids::get)) }) // unknown place → « Non rangé »
        Processing.enqueueOcr(ctx) // imported books that were mid-processing resume
        return dao.ids().size - before
    }

    suspend fun exportCsv(ctx: Context, uri: Uri): Int {
        val books = ctx.app.db.books().snapshot()
        val byId = ctx.app.db.places().snapshot().associateBy { it.id }
        fun cell(s: String?) = "\"" + s.orEmpty().replace("\"", "\"\"") + "\""
        val csv = buildString {
            appendLine("titre,auteurs,editeur,annee,isbn,statut,piece,emplacement")
            books.forEach { b ->
                val p = b.placeId?.let(byId::get)
                val room = p?.parentId?.let(byId::get) ?: p
                val spot = p?.takeIf { it.parentId != null }
                appendLine(listOf(b.title, b.authors, b.publisher, b.year, b.isbn, b.status.name, room?.name, spot?.name).joinToString(",") { cell(it) })
            }
        }
        val out = ctx.contentResolver.openOutputStream(uri) ?: error("impossible d'écrire le fichier")
        // BOM so Excel opens the accents correctly.
        out.use { it.write(("﻿" + csv).toByteArray()) }
        return books.size
    }
}
