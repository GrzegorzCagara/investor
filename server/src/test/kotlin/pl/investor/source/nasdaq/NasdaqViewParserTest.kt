package pl.investor.source.nasdaq

import org.junit.jupiter.api.Test
import pl.investor.source.content.ReportContentNotFoundException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NasdaqViewParserTest {

    private val pageUrl = "https://view.news.eu.nasdaq.com/view?id=b3e1bac58183058432778b19c49e321a1&lang=sv&src=listed"

    private fun fixture(path: String) = checkNotNull(javaClass.getResource("/fixtures/se/$path")) { "missing fixture $path" }.readText()

    @Test
    fun `Swedish interim report page gives the release text and the PDF attachment`() {
        val content = NasdaqViewParser().parse(fixture("view-opter-q3-2026.html"), pageUrl)

        assertTrue("Nettoomsättningen för tredje kvartalet 2026 uppgick till 27,7 MSEK (24,2)" in content.text)
        assertTrue("Rörelseresultat (EBIT) för tredje kvartalet 2026 uppgick till 10,6 MSEK (8,2)" in content.text)
        assertFalse("Social share" in content.text)
        assertFalse("Bifogade filer" in content.text)
        assertEquals(listOf("https://attachment.news.eu.nasdaq.com/a592fd2ba7dfacddf27f66ea516c09915"), content.attachmentUrls)
        assertTrue(content.selectedData.isEmpty())
    }

    @Test
    fun `page without the release body fails`() {
        assertFailsWith<ReportContentNotFoundException> { NasdaqViewParser().parse("<html><body>maintenance</body></html>", pageUrl) }
    }
}
