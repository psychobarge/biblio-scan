package app.biblioscan

import app.biblioscan.match.Candidate
import app.biblioscan.match.Matching
import app.biblioscan.match.NoiseEntry
import app.biblioscan.match.OcrLine
import app.biblioscan.match.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SPEC-4: real cases seen on the Pixel, plus the editable noise list. */
class PolishTest {
    private fun cand(id: String, title: String, author: String, lang: String? = "fr") = Candidate(id, title, listOf(author), language = lang)

    // Real OCR of the "La victoire des aigles" cover (Téméraire 5), letter heights approximated.
    private val novikLines = listOf(
        OcrLine("NAOMI NOVIK", 90), OcrLine("TEMERAIRE", 110), OcrLine("LA VICTOIRE DES AIGLES- 5", 70), OcrLine("HNTASY", 60),
    )
    private val novikOcr = "NAOMI NOVIK\nTEMERAIRE\nLA VICTOIRE DES AIGLES- 5\nHNTASY"

    @Test fun `volume number is dropped from the query`() =
        assertEquals("temeraire naomi novik la victoire des aigles hntasy", Matching.buildQuery(novikLines))

    @Test fun `french edition beats the series name and the english edition`() {
        // Candidates Google really returns for that query (langRestrict=fr leaks English editions).
        val d = Matching.decide(
            listOf(
                cand("g:en1", "Victory of Eagles (The Temeraire Series, Book 5)", "Naomi Novik", "en"),
                cand("g:en2", "Victory of Eagles", "Naomi Novik", "en"),
                cand("g:serie", "Temeraire", "Naomi Novik"),
                cand("g:integrale", "Téméraire - L'intégrale 2", "Naomi Novik"),
                cand("g:fr", "La victoire des aigles", "Naomi Novik"),
            ),
            novikOcr, Matching.buildQuery(novikLines),
        )
        assertEquals(Verdict.MATCHED, d.verdict)
        assertEquals("g:fr", d.ranked.first().sourceId)
    }

    @Test fun `a foreign edition is never picked automatically`() {
        val d = Matching.decide(listOf(cand("g:en", "L'étranger", "Albert Camus", "en")), "ALBERT CAMUS\nL'étranger", "l etranger albert camus")
        assertEquals(0.5, d.ranked.first().score, 1e-9)
        assertTrue(d.verdict != Verdict.MATCHED)
    }

    @Test fun `the volume beats its cycle`() {
        // Real case: "La maison des autres", volume 1 of "La grande patience" (Bernard Clavel).
        val ocr = "A\nBernard\nLa grande patience\nClavel\nLa maison\ndes autres\nRoman\nPAESe\nPETI"
        val d = Matching.decide(
            listOf(
                cand("g:cycle", "La grande patience", "Bernard Clavel"),
                cand("g:tome", "La grande patience 1 : La maison des autres", "Bernard Clavel"),
                cand("g:seul", "La maison des autres", "Bernard Clavel"),
            ),
            ocr, "des autres la maison la grande patience bernard",
        )
        assertEquals("g:tome", d.ranked.first().sourceId)
        assertTrue(d.ranked.first { it.sourceId == "g:cycle" }.score < d.ranked.first { it.sourceId == "g:seul" }.score)
    }

    @Test fun `genre labels are noise by default`() =
        assertEquals("dune", Matching.clean("Science-fiction DUNE"))

    @Test fun `user entries remove a word, a whole line, or a glued word`() {
        val noise = listOf(NoiseEntry("hntasy"), NoiseEntry("goncourt", wholeLine = true), NoiseEntry("bragelonne"))
        assertEquals("la victoire", Matching.clean("La victoire HNTASY", noise))
        assertEquals("", Matching.clean("Lauréat du Goncourt 2020", noise))
        assertEquals("dune", Matching.clean("DUNE editionsbragelonnex", noise)) // 10 letters: matched inside a word
    }

    @Test fun `short entries only match whole words`() {
        val noise = listOf(NoiseEntry("sf"))
        assertEquals("sfumato", Matching.clean("SFUMATO SF", noise))
    }

    @Test fun `user input is normalized and duplicates refused`() {
        val list = Matching.withEntry(emptyList(), "  Éditions  du Seuil ")!!
        assertEquals(listOf(NoiseEntry("editions du seuil")), list)
        assertNull(Matching.withEntry(list, "EDITIONS DU SEUIL"))
        assertNull(Matching.withEntry(list, "   "))
        assertFalse(Matching.withEntry(list, "Folio", wholeLine = true)!!.last().text != "folio")
    }
}
