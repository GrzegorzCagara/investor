package pl.investor.source.content

import io.mockk.mockk
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.investor.monitoring.SourceHealthTracker
import pl.investor.source.PoliteHttpClient
import java.io.ByteArrayOutputStream
import java.time.Clock
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NasdaqAttachmentTextTest {

    private val server = MockWebServer()
    private val http = PoliteHttpClient(Clock.systemUTC(), "me@example.com", mockk<SourceHealthTracker>(relaxed = true))
    private val extractor = AttachmentTextExtractor(http)
    private val pdf = checkNotNull(javaClass.getResource("/fixtures/se/opter-kvartalsrapport-q3-2026.pdf")).readBytes()

    @BeforeEach
    fun start() = server.start()

    @AfterEach
    fun stop() = server.close()

    @Test
    fun `Swedish interim report keeps the pages with Swedish result keywords`() {
        server.enqueue(MockResponse.Builder().body(Buffer().write(pdf)).build())

        val reading = extractor.readNasdaqPdfs(listOf(server.url("/a592fd2ba7dfacddf27f66ea516c09915").toString()))
        val text = reading.text

        val all = org.apache.pdfbox.Loader.loadPDF(pdf).use { org.apache.pdfbox.text.PDFTextStripper().getText(it) }
        assertTrue(text.length < all.replace(Regex("\\s+"), " ").length)
        assertTrue("Nettoomsättning 27 696 24 219" in text)
        assertTrue("Rörelseresultat 10 601 8 174" in text)
        assertTrue("Rörelseresultat 10 601 8 174" !in extractor.relevantPages(pdf))
    }

    @Test
    fun `unavailable attachment yields empty text and stays pending`() {
        server.enqueue(MockResponse.Builder().code(404).build())

        assertEquals(AttachmentReading("", nothingReadable = false), extractor.readNasdaqPdfs(listOf(server.url("/missing").toString())))
    }

    @Test
    fun `blank or oversized PDF is unreadable so the body is used at once`() {
        val blank = ByteArrayOutputStream().also { out -> PDDocument().use { it.addPage(PDPage()); it.save(out) } }.toByteArray()
        server.enqueue(MockResponse.Builder().body(Buffer().write(blank)).build())
        server.enqueue(MockResponse.Builder().body(Buffer().write(ByteArray(MAX_ATTACHMENT_BYTES + 1))).build())

        assertEquals(AttachmentReading("", nothingReadable = true), extractor.readNasdaqPdfs(listOf(server.url("/blank").toString())))
        assertEquals(AttachmentReading("", nothingReadable = true), extractor.readNasdaqPdfs(listOf(server.url("/huge").toString())))
    }
}
