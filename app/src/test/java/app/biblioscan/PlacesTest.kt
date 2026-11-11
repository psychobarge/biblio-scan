package app.biblioscan

import app.biblioscan.data.Book
import app.biblioscan.data.Place
import app.biblioscan.data.Places
import app.biblioscan.data.Status
import app.biblioscan.ui.Sort
import app.biblioscan.ui.arrange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlacesTest {
    private fun uuid(n: Int) = "00000000-0000-0000-0000-%012d".format(n)
    private val salon = Place(uuid(1), null, "Salon")
    private val noire = Place(uuid(2), salon.id, "Étagère noire 1")
    private val chambre = Place(uuid(3), null, "Chambre")
    private val places = listOf(salon, noire, chambre)

    private fun book(id: String, place: String?, status: Status = Status.CONFIRMED, title: String = "Livre $id") =
        Book(id = id, createdAt = id.hashCode().toLong(), status = status, title = title, placeId = place)

    private val books = listOf(
        book("salon", salon.id),
        book("noire", noire.id),
        book("chambre", chambre.id),
        book("none", null),
        book("gone", "deleted-place"),
        book("pending", noire.id, Status.MATCHED),
    )

    private fun ids(scope: String, q: String = "") = arrange(books, q, Sort.TITLE, scope, places).map { it.id }.toSet()

    @Test fun `scope shows validated books only, a room includes its spots`() {
        assertEquals(setOf("salon", "noire", "chambre", "none", "gone"), ids(Places.ALL))
        assertEquals(setOf("salon", "noire"), ids(salon.id))
        assertEquals(setOf("noire"), ids(noire.id))
        assertEquals(setOf("none", "gone"), ids(Places.NONE)) // a place that no longer exists = « Non rangé »
    }

    @Test fun `search is global, validated or not`() {
        assertEquals(books.map { it.id }.toSet(), ids(chambre.id, "livre"))
        assertEquals(setOf("pending"), ids(Places.NONE, "pending"))
    }

    @Test fun `natural order ignores accents and case`() {
        val names = listOf("étagère 10", "Étagère 2", "Bureau", "etagere 1")
        assertEquals(listOf("Bureau", "etagere 1", "Étagère 2", "étagère 10"), names.sortedWith(Places.byName))
    }

    @Test fun `path and counts`() {
        val byId = places.associateBy { it.id }
        assertEquals("Salon › Étagère noire 1", Places.path(noire.id, byId))
        assertEquals("Salon", Places.path(salon.id, byId))
        assertNull(Places.path("deleted-place", byId))
        val c = Places.counts(books, places)
        assertEquals(5, c[Places.ALL]) // the MATCHED book is not in the library yet
        assertEquals(2, c[salon.id])
        assertEquals(2, c[Places.NONE])
    }

    @Test fun `name check`() {
        assertEquals("", Places.nameError("  ", null, places))
        assertEquals("40 caractères maximum", Places.nameError("x".repeat(41), null, places))
        assertEquals("Existe déjà", Places.nameError("SALON", null, places))
        assertEquals("Existe déjà dans Salon", Places.nameError("etagere noire 1", salon.id, places))
        assertNull(Places.nameError("Étagère noire 1", chambre.id, places)) // same spot name in another room
        assertNull(Places.nameError("Salon", null, places, selfId = salon.id)) // renaming to itself
    }

    @Test fun `import merges by name and drops invalid places`() {
        val otherSalon = Place(uuid(10), null, " salon ")
        val spotInOther = Place(uuid(11), otherSalon.id, "Étagère noire 1") // same as existing → merged
        val newSpot = Place(uuid(12), otherSalon.id, "Vitrine")
        val orphan = Place(uuid(13), uuid(99), "Nulle part")
        val deep = Place(uuid(14), newSpot.id, "Trop profond") // parent is a spot
        val badId = Place("../evil", null, "Cave")
        val (map, insert) = Places.merge(places, listOf(otherSalon, spotInOther, newSpot, orphan, deep, badId))
        assertEquals(salon.id, map[otherSalon.id])
        assertEquals(noire.id, map[spotInOther.id])
        assertEquals(listOf(Place(newSpot.id, salon.id, "Vitrine")), insert)
        assertTrue(listOf(orphan, deep, badId).none { it.id in map })
        assertEquals(chambre.id, map[chambre.id]) // existing ids stay valid for imported books
    }
}
