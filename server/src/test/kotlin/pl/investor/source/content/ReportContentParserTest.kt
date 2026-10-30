package pl.investor.source.content

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReportContentParserTest {

    private val parser = ReportContentParser()

    private fun parse(path: String, baseUrl: String = ReportContentParser.DEFAULT_BASE_URL) =
        parser.parse(
            checkNotNull(javaClass.getResource("/fixtures/$path")) { "missing fixture $path" }.readText(),
            baseUrl,
        )

    @Test
    fun `ASBIS RB 23 text contains both revenue figures`() {
        val content = parse("reports/asbis-rb-23-2026.html")

        assertTrue("604" in content.text && "316" in content.text)
    }

    @Test
    fun `ASBIS July 2026 sales text contains both revenue figures`() {
        val content = parse("reports/asbis-rb-sales-2026-08.html")

        assertTrue("531" in content.text && "281" in content.text)
    }

    @Test
    fun `page without report body throws`() {
        val error = assertFailsWith<ReportContentNotFoundException> {
            parser.parse("<html><body><p>Access denied</p></body></html>", pageUrl = "https://www.gpw.pl/komunikat?geru_id=1")
        }

        assertEquals("https://www.gpw.pl/komunikat?geru_id=1", error.url)
    }

    @Test
    fun `selected data rows keep original cell strings`() {
        val html = """
            <div class="report-data">
              <h4>Nazwa arkusza: <a name="a">WYBRANE DANE FINANSOWE</a></h4>
              <table><tr><td><table>
                <tr class="nTekst"><td> </td><td rowspan="2">WYBRANE DANE FINANSOWE</td><td>w tys.</td><td>PLN</td><td> </td></tr>
                <tr class="nTekst"><td> </td><td>półrocze /</td><td>2026</td><td>2025</td></tr>
                <tr class="nTekst"><td> </td><td>Przychody netto ze sprzedaży</td><td>1 052,3</td><td>-8,5</td><td> </td></tr>
                <tr class="nTekst"><td> </td><td>Zysk netto</td><td>29&nbsp;800</td><td>5 000</td></tr>
              </table></td></tr></table>
            </div>
        """.trimIndent()

        val rows = parser.parse(html).selectedData

        assertEquals(
            listOf(
                SelectedDataRow("Przychody netto ze sprzedaży", listOf("1 052,3", "-8,5")),
                SelectedDataRow("Zysk netto", listOf("29 800", "5 000")),
            ),
            rows,
        )
        assertTrue(rows.first().label.contains("Przychody"))
    }

    @Test
    fun `ASBIS half-yearly has empty selected data`() {
        val content = parse("reports/asbis-ps-2026.html")

        assertTrue(content.selectedData.isEmpty())
        assertEquals(4, content.attachmentUrls.size)
        assertTrue(content.attachmentUrls.all { it.startsWith("https://www.gpw.pl/attachment/zal0") })
    }

    @Test
    fun `page chrome is stripped`() {
        val text = parse("reports/asbis-rb-23-2026.html").text

        assertFalse("Regulamin" in text)
        assertFalse("Polityka prywatności" in text)
        assertFalse("POLITYKA PRYWATNOŚCI" in text)
        assertFalse(" " in text)
        assertFalse("  " in text)
    }

    @Test
    fun `NC quarterly report has only attachments`() {
        val content = parse("nc/report-present24-q2-2026-08-14.html", "https://newconnect.pl/")

        assertTrue(content.text.length < 250, content.text)
        assertTrue(content.selectedData.isEmpty())
        assertTrue("https://newconnect.pl/ebi/files/gpwdata/4899_20260814_233500_main.xhtml" in content.attachmentUrls)
        assertEquals(3, content.attachmentUrls.size)
    }

    @Test
    fun `NC current report carries full text`() {
        val content = parse("nc/report-beein-current-sales-2026-10-29.html", "https://newconnect.pl/")

        assertTrue(content.text.contains("przychod", ignoreCase = true))
        assertTrue(content.selectedData.isEmpty())
    }

    @Test
    fun `Polish characters stay intact`() {
        val text = parse("reports/crj-prelim-q1-2026.html").text

        assertTrue("przychody ze sprzedaży: 40,0 mln zł" in text, text.take(1500))
    }
}
