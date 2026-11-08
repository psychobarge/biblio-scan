package app.biblioscan

import app.biblioscan.data.Book
import app.biblioscan.data.Status
import app.biblioscan.ui.Sort
import app.biblioscan.ui.arrange
import org.junit.Assert.assertEquals
import org.junit.Test

class ArrangeTest {
    private fun book(id: String, at: Long, title: String?, authors: String?) =
        Book(id = id, createdAt = at, status = Status.CONFIRMED, title = title, authors = authors)

    private val books = listOf(
        book("a", 1, "Zola et moi", "Émile Zola"),
        book("b", 3, "L'Étranger", "Albert Camus; Autre"),
        book("c", 2, null, null),
        book("d", 4, "éloge de l'ombre", "Tanizaki"),
    )

    private fun ids(sort: Sort, q: String = "") = arrange(books, q, sort).map { it.id }

    @Test fun `added sorts newest first`() = assertEquals(listOf("d", "b", "c", "a"), ids(Sort.ADDED))

    @Test fun `title ignores accents and case, missing last`() = assertEquals(listOf("d", "b", "a", "c"), ids(Sort.TITLE))

    @Test fun `author uses first author, missing last`() = assertEquals(listOf("b", "a", "d", "c"), ids(Sort.AUTHOR))

    @Test fun `search matches title or author without accents`() {
        assertEquals(listOf("a"), ids(Sort.ADDED, "emile"))
        assertEquals(listOf("b"), ids(Sort.ADDED, "ETRANGER"))
    }
}
