package app.biblioscan

import app.biblioscan.data.Books
import org.junit.Assert.assertEquals
import org.junit.Test

class ImportSizeTest {
    @Test fun `portrait gallery photo is capped on its long side`() = assertEquals(1440 to 1920, Books.targetSize(3000, 4000))

    @Test fun `landscape photo keeps its ratio`() = assertEquals(1920 to 1080, Books.targetSize(4032, 2268))

    @Test fun `small or exact images are never upscaled`() {
        assertEquals(800 to 600, Books.targetSize(800, 600))
        assertEquals(1280 to 1920, Books.targetSize(1280, 1920))
    }

    @Test fun `extreme panorama never collapses to zero`() = assertEquals(1920 to 1, Books.targetSize(100000, 10))
}
