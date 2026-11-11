package app.biblioscan.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
enum class Status { CAPTURED, OCR_DONE, MATCHED, NEEDS_REVIEW, NOT_FOUND, CONFIRMED, ERROR }

/** The photo is always `covers/{id}.jpg` (see [Books.photoFile]); never stored as a path so restores can't break it. */
@Serializable
@Entity
data class Book(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val status: Status,
    val attempts: Int = 0,
    val lastError: String? = null,
    // OCR
    val ocrText: String? = null,
    val ocrLinesJson: String? = null,
    val query: String? = null,
    // Chosen result
    val title: String? = null,
    val authors: String? = null,
    val publisher: String? = null,
    val year: String? = null,
    val isbn: String? = null,
    val sourceId: String? = null,
    val coverUrl: String? = null,
    val score: Double? = null,
    val candidatesJson: String? = null,
    // Room or spot (SPEC-7); null = « Non rangé ». Only meaningful once CONFIRMED.
    val placeId: String? = null,
)
