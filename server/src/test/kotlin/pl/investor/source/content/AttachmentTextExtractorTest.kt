package pl.investor.source.content

import io.mockk.mockk
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.investor.monitoring.SourceHealthTracker
import pl.investor.source.PoliteHttpClient
import java.time.Clock
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AttachmentTextExtractorTest {

    private val server = MockWebServer()
    private val requested = mutableListOf<RecordedRequest>()
    private val unavailablePaths = mutableSetOf<String>()
    private val http = PoliteHttpClient(Clock.systemUTC(), "me@example.com", mockk<SourceHealthTracker>(relaxed = true))
    private val extractor = AttachmentTextExtractor(http)

    @BeforeEach
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requested += request
                val path = checkNotNull(request.url.encodedPath)
                return when {
                    path.startsWith("/wiadomosc/asbis") -> html("bankier/asbis-article-ps-2026.html")
                    path.startsWith("/wiadomosc/xtb") -> html("bankier/xtb-article-prelim-q2-2026.html")
                    path in unavailablePaths -> MockResponse.Builder().code(404).build()
                    path.startsWith("/static/att/") -> pdf(path.substringAfterLast('/'))
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.close()

    private fun resource(path: String) = checkNotNull(javaClass.getResourceAsStream("/fixtures/$path")) { "missing fixture $path" }.readBytes()

    private fun html(path: String): MockResponse {
        val body = resource(path).toString(Charsets.UTF_8).replace("https://bonnier.pl/static/att/emitent/", server.url("/static/att/").toString().trimEnd('/') + "/emitent/")
        return MockResponse.Builder().body(body).addHeader("Content-Type", "text/html; charset=utf-8").build()
    }

    private fun pdf(fileName: String): MockResponse {
        val fixture = when {
            fileName.endsWith("Zalacznik_do_RB_nr_18_2026_PL.pdf") -> "attachments/xtb-prelim-q2-2026-1.pdf"
            fileName.endsWith("Raport_polroczny_H1_2026.pdf") -> "attachments/asbis-ps-2026-2.pdf"
            fileName.endsWith("Sprawozdanie_finansowe_H1_2026.pdf") -> "attachments/asbis-ps-2026-1.pdf"
            fileName.endsWith("ASBISc_FS_H1_2026.pdf") -> "attachments/asbis-ps-2026-4.pdf"
            fileName.endsWith("EN_report_Q2_andH1_2026.pdf") -> "attachments/asbis-ps-2026-3.pdf"
            else -> return MockResponse.Builder().code(404).build()
        }
        return MockResponse.Builder().body(Buffer().write(resource(fixture))).build()
    }

    private fun articleUrl(path: String) = server.url(path).toString()

    private fun pdfRequests() = requested.filter { it.url.encodedPath.startsWith("/static/att/") }

    @Test
    fun `XTB preliminary results text contains Q2 revenue and net profit`() {
        val text = extractor.relevantText(articleUrl("/wiadomosc/xtb"))

        assertTrue("992 558" in text, "revenue missing")
        assertTrue("492 198" in text, "net profit missing")
    }

    @Test
    fun `ASBIS H1 text contains Q2 revenue`() {
        val text = extractor.relevantText(articleUrl("/wiadomosc/asbis"))

        assertTrue("1 696,7 mln USD" in text || "1 696 664" in text, "Q2 revenue missing")
    }

    @Test
    fun `result is capped at 30000 characters and whitespace is normalized`() {
        val text = extractor.relevantText(articleUrl("/wiadomosc/asbis"))

        assertTrue(text.length <= 30_000)
        assertFalse(Regex("\\s{2,}").containsMatchIn(text))
    }

    @Test
    fun `downloads at most three attachments and sends the article as referer`() {
        extractor.relevantText(articleUrl("/wiadomosc/asbis"))

        assertEquals(3, pdfRequests().size)
        assertTrue(pdfRequests().all { it.headers["Referer"] == articleUrl("/wiadomosc/asbis") })
    }

    @Test
    fun `english attachment is skipped when polish ones exist`() {
        extractor.relevantText(articleUrl("/wiadomosc/asbis"))

        assertTrue(pdfRequests().none { "EN_report" in it.url.encodedPath })
    }

    @Test
    fun `failure of one attachment does not stop the others`() {
        unavailablePaths += requestedPathOf("Raport_polroczny_H1_2026.pdf")

        val text = extractor.relevantText(articleUrl("/wiadomosc/asbis"))

        assertEquals(3, pdfRequests().size)
        assertTrue(text.isNotBlank())
    }

    @Test
    fun `page without pdf attachments yields empty text`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse.Builder().body("<html><body><a href=\"/x.html\">x</a><a href=\"/static/att/emitent/p.PDF.html\">y</a></body></html>").build()
        }

        assertEquals("", extractor.relevantText(articleUrl("/wiadomosc/none")))
    }

    @Test
    fun `keeps first two pages and keyword pages only`() {
        val pdf = resource("attachments/asbis-ps-2026-2.pdf")

        val pages = extractor.relevantPages(pdf)
        val all = org.apache.pdfbox.Loader.loadPDF(pdf).use { org.apache.pdfbox.text.PDFTextStripper().getText(it) }

        assertTrue(pages.length < all.length)
        assertTrue("1 696,7 mln USD" in pages)
    }

    private fun requestedPathOf(suffix: String): String {
        extractor.relevantText(articleUrl("/wiadomosc/asbis"))
        val path = pdfRequests().first { it.url.encodedPath.endsWith(suffix) }.url.encodedPath
        requested.clear()
        return path
    }
}
