package app.biblioscan.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import app.biblioscan.match.Matching
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** A room (parentId null) or a spot in a room. Two levels only (SPEC-7 §2.1). */
@Serializable
@Entity
data class Place(
    @PrimaryKey val id: String,
    val parentId: String? = null,
    val name: String,
)

@Dao
abstract class PlaceDao {
    @Query("SELECT * FROM Place")
    abstract fun all(): Flow<List<Place>>

    @Query("SELECT * FROM Place")
    abstract suspend fun snapshot(): List<Place>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insert(places: List<Place>)

    @Query("UPDATE Place SET name = :name WHERE id = :id")
    abstract suspend fun rename(id: String, name: String)

    @Query("UPDATE Book SET placeId = :to WHERE placeId IN (:from)")
    protected abstract suspend fun rehome(from: List<String>, to: String?)

    @Query("SELECT id FROM Place WHERE parentId = :roomId")
    protected abstract suspend fun spotIds(roomId: String): List<String>

    @Query("DELETE FROM Place WHERE id IN (:ids)")
    protected abstract suspend fun deleteIds(ids: List<String>)

    /** Never deletes a book: a spot's books go to its room, a room's books (and its spots') become « Non rangé ». */
    @Transaction
    open suspend fun delete(place: Place) {
        val ids = if (place.parentId != null) listOf(place.id) else spotIds(place.id) + place.id
        rehome(ids, place.parentId)
        deleteIds(ids)
    }
}

/** Pure helpers on places; scopes are [ALL], [NONE] or a place id. */
object Places {
    const val ALL = "all"
    const val NONE = "none"
    const val MAX_NAME = 40

    fun placeIdOf(scope: String): String? = scope.takeIf { it != ALL && it != NONE }

    private val chunks = Regex("\\d+|\\D+")

    /** Natural order, accents and case ignored: « Étagère 2 » before « étagère 10 ». */
    val byName: Comparator<String> = Comparator { a, b ->
        val x = chunks.findAll(Matching.normalize(a)).map { it.value }.toList()
        val y = chunks.findAll(Matching.normalize(b)).map { it.value }.toList()
        for (i in 0 until minOf(x.size, y.size)) {
            val (p, q) = x[i] to y[i]
            val c = if (p[0].isDigit() && q[0].isDigit()) {
                val (pt, qt) = p.trimStart('0') to q.trimStart('0')
                compareValues(pt.length, qt.length).takeIf { it != 0 } ?: pt.compareTo(qt)
            } else p.compareTo(q)
            if (c != 0) return@Comparator c
        }
        x.size - y.size
    }

    /** Rooms in order, each with its spots in order. */
    fun tree(places: List<Place>): List<Pair<Place, List<Place>>> {
        val sorted = places.sortedWith(compareBy(byName) { it.name })
        return sorted.filter { it.parentId == null }.map { room -> room to sorted.filter { it.parentId == room.id } }
    }

    /** « Salon › Étagère noire 1 », « Salon », or null when not filed (or the place is gone). */
    fun path(placeId: String?, byId: Map<String, Place>): String? {
        val p = placeId?.let(byId::get) ?: return null
        val room = p.parentId?.let(byId::get) ?: return p.name
        return "${room.name} › ${p.name}"
    }

    fun inScope(placeId: String?, scope: String, byId: Map<String, Place>): Boolean {
        val p = placeId?.let(byId::get)
        return when (scope) {
            ALL -> true
            NONE -> p == null
            else -> p != null && (p.id == scope || p.parentId == scope)
        }
    }

    /** Validated books per scope: [ALL], [NONE], every place id (a room counts its spots' books). */
    fun counts(books: List<Book>, places: List<Place>): Map<String, Int> {
        val byId = places.associateBy { it.id }
        val m = HashMap<String, Int>()
        for (b in books) {
            if (b.status != Status.CONFIRMED) continue
            m.merge(ALL, 1, Int::plus)
            val p = b.placeId?.let(byId::get)
            if (p == null) m.merge(NONE, 1, Int::plus)
            else listOfNotNull(p.id, p.parentId).forEach { m.merge(it, 1, Int::plus) }
        }
        return m
    }

    /** The place with the same name at the same level, if any. */
    fun twin(name: String, parentId: String?, places: List<Place>, selfId: String? = null): Place? {
        val n = Matching.normalize(name)
        return places.find { it.parentId == parentId && it.id != selfId && Matching.normalize(it.name) == n }
    }

    /** null = valid; "" = blank (button off, no message); else the message to show under the field. */
    fun nameError(name: String, parentId: String?, places: List<Place>, selfId: String? = null): String? {
        val n = name.trim()
        return when {
            n.isEmpty() -> ""
            n.length > MAX_NAME -> "$MAX_NAME caractères maximum"
            twin(n, parentId, places, selfId) != null ->
                "Existe déjà" + (parentId?.let { id -> places.find { it.id == id } }?.let { " dans ${it.name}" } ?: "")
            else -> null
        }
    }

    /**
     * Import (trust boundary, SPEC-7 §7): imported id → id on this phone (existing ids map to themselves),
     * and the places to insert. Same name at the same level = the existing place; invalid ones are dropped.
     */
    fun merge(existing: List<Place>, imported: List<Place>): Pair<Map<String, String>, List<Place>> {
        val map = existing.associate { it.id to it.id }.toMutableMap()
        val all = existing.toMutableList()
        val insert = mutableListOf<Place>()
        fun add(p: Place, parentId: String?) {
            if (p.id in map || !Backup.isValidId(p.id)) return
            val name = p.name.trim()
            val twin = twin(name, parentId, all)
            if (twin != null) map[p.id] = twin.id
            else if (nameError(name, parentId, all) == null) {
                val q = Place(p.id, parentId, name)
                all += q; insert += q; map[p.id] = p.id
            }
        }
        imported.filter { it.parentId == null }.forEach { add(it, null) }
        imported.filter { it.parentId != null }.forEach { p ->
            val parent = map[p.parentId]?.let { id -> all.find { it.id == id } }
            if (parent != null && parent.parentId == null) add(p, parent.id) // a spot's parent must be a room
        }
        return map to insert
    }
}
