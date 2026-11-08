package app.biblioscan

import app.biblioscan.data.Backup
import app.biblioscan.match.Candidate
import app.biblioscan.match.Matching
import app.biblioscan.match.OcrLine
import app.biblioscan.match.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchingTest {
    private val etranger = "ALBERT CAMUS\nL'étranger\nfolio\nPrix Nobel de littérature"

    private fun cand(id: String, title: String, vararg authors: String) = Candidate(id, title, authors.toList())

    @Test fun `normalize strips accents case and punctuation`() =
        assertEquals("l etranger a e", Matching.normalize("L'Étranger — À É"))

    @Test fun `query keeps biggest lines and drops cover noise`() {
        val lines = listOf(
            OcrLine("folio", 30),
            OcrLine("ALBERT CAMUS", 60),
            OcrLine("L'étranger", 120),
            OcrLine("Prix Nobel de littérature", 40),
            OcrLine("Traduit de l'anglais par X", 20),
        )
        assertEquals("l etranger albert camus", Matching.buildQuery(lines))
        assertEquals("l etranger", Matching.biggestLine(lines))
    }

    @Test fun `query is capped near 100 chars on a word boundary`() {
        val q = Matching.buildQuery(listOf(OcrLine("mot ".repeat(60), 10)))
        assertTrue(q, q.length <= 100 && !q.endsWith(" "))
    }

    @Test fun `several editions of the same work still auto-match`() {
        val d = Matching.decide(
            listOf(cand("g:1", "L'Étranger", "Albert Camus"), cand("g:2", "L'étranger", "Albert Camus"), cand("g:3", "La Peste", "Albert Camus")),
            etranger,
        )
        assertEquals(Verdict.MATCHED, d.verdict)
        assertEquals(1.0, d.ranked.first().score, 1e-9)
    }

    @Test fun `two different works scoring alike need review`() {
        val d = Matching.decide(listOf(cand("a", "L'étranger", "Albert Camus"), cand("b", "L'étranger", "Albert Camus Jr")), etranger)
        // second candidate: same title, author tokens 2/3 -> different work, close score
        assertEquals(Verdict.NEEDS_REVIEW, d.verdict)
    }

    @Test fun `title only match without author is review at best`() =
        assertEquals(Verdict.NEEDS_REVIEW, Matching.decide(listOf(cand("a", "L'étranger")), etranger).verdict)

    @Test fun `unrelated or no candidates is not found`() {
        assertEquals(Verdict.NOT_FOUND, Matching.decide(listOf(cand("a", "Dune", "Frank Herbert")), etranger).verdict)
        assertEquals(Verdict.NOT_FOUND, Matching.decide(emptyList(), etranger).verdict)
    }

    @Test fun `zip import only accepts plain uuid cover entries`() {
        val id = "123e4567-e89b-12d3-a456-426614174000"
        assertEquals(id, Backup.coverIdOf("covers/$id.jpg"))
        assertNull(Backup.coverIdOf("covers/../../$id.jpg"))
        assertNull(Backup.coverIdOf("../databases/biblio.db"))
        assertTrue(!Backup.isValidId("../x"))
    }
}
