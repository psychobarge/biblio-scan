package app.biblioscan

import app.biblioscan.match.CoverReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoverReaderTest {
    @Test fun plainJson() =
        assertEquals("La Curée Émile Zola", CoverReader.parseAnswer("""{"title": "La Curée", "author": "Émile Zola"}"""))

    @Test fun fencedJsonWithProse() =
        assertEquals("La Curée", CoverReader.parseAnswer("Voici :\n```json\n{\"title\": \"La Curée\", \"author\": null}\n```"))

    @Test fun nothingRead() {
        assertNull(CoverReader.parseAnswer("""{"title": null, "author": null}"""))
        assertNull(CoverReader.parseAnswer("Je ne peux pas lire cette image."))
        assertNull(CoverReader.parseAnswer(""))
    }
}
