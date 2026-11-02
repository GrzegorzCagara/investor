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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AttachmentTextExtractorTest {

    private val server = MockWebServer()
    private val requested = mutableListOf<RecordedRequest>()
    private val unavailablePaths = mutableSetOf<String>()
    private val served = linkedMapOf<String, ByteArray>()
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
                    path.startsWith("/wiadomosc/mostalplc") -> html("bankier/mostalplc-article-saq-3-2026.html")
                    path.startsWith("/wiadomosc/served") -> servedArticle()
                    path in unavailablePaths -> MockResponse.Builder().code(404).build()
                    path.startsWith("/static/att/served/") -> served.getValue(path.substringAfterLast('/')).let { MockResponse.Builder().body(Buffer().write(it)).build() }
                    path.startsWith("/static/att/") -> attachment(path.substringAfterLast('/'))
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

    private fun attachment(fileName: String): MockResponse {
        val fixture = when {
            fileName.endsWith("Zalacznik_do_RB_nr_18_2026_PL.pdf") -> "attachments/xtb-prelim-q2-2026-1.pdf"
            fileName.endsWith("Raport_polroczny_H1_2026.pdf") -> "attachments/asbis-ps-2026-2.pdf"
            fileName.endsWith("Sprawozdanie_finansowe_H1_2026.pdf") -> "attachments/asbis-ps-2026-1.pdf"
            fileName.endsWith("ASBISc_FS_H1_2026.pdf") -> "attachments/asbis-ps-2026-4.pdf"
            fileName.endsWith("EN_report_Q2_andH1_2026.pdf") -> "attachments/asbis-ps-2026-3.pdf"
            fileName.endsWith("Informacja_dodatkowa-2026-09-30-1-pl.xhtml") -> "attachments/mostalplc-saq-3-2026-Informacja_dodatkowa.xhtml"
            fileName.endsWith("Pozostale_informacje-2026-09-30-1-pl.xhtml") -> "attachments/mostalplc-saq-3-2026-Pozostale_informacje.xhtml"
            else -> return MockResponse.Builder().code(404).build()
        }
        return MockResponse.Builder().body(Buffer().write(resource(fixture))).build()
    }

    private fun servedArticle(): MockResponse {
        val links = served.keys.joinToString("") { "<a href=\"/static/att/served/$it\">$it</a>" }
        return MockResponse.Builder().body("<html><body>$links</body></html>").addHeader("Content-Type", "text/html; charset=utf-8").build()
    }

    private fun articleUrl(path: String) = server.url(path).toString()

    private fun attachmentRequests() = requested.filter { it.url.encodedPath.startsWith("/static/att/") }

    @Test
    fun `XTB preliminary results text contains Q2 revenue and net profit`() {
        val text = extractor.read(articleUrl("/wiadomosc/xtb")).text

        assertTrue("992 558" in text, "revenue missing")
        assertTrue("492 198" in text, "net profit missing")
    }

    @Test
    fun `ASBIS H1 text contains Q2 revenue`() {
        val text = extractor.read(articleUrl("/wiadomosc/asbis")).text

        assertTrue("1 696,7 mln USD" in text || "1 696 664" in text, "Q2 revenue missing")
    }

    @Test
    fun `Mostostal Plock Q3 xhtml attachments contain the quarter revenue`() {
        val text = extractor.read(articleUrl("/wiadomosc/mostalplc")).text

        assertTrue("73 704 27 463 101 167" in text, "Q3 revenue missing")
        assertEquals(2, attachmentRequests().size)
    }

    @Test
    fun `signature files next to xhtml attachments are not downloaded`() {
        extractor.read(articleUrl("/wiadomosc/mostalplc"))

        assertTrue(attachmentRequests().none { it.url.encodedPath.endsWith(".xades") })
    }

    @Test
    fun `xhtml keeps first two pages and keyword pages only`() {
        val xhtml = resource("attachments/mostalplc-saq-3-2026-Informacja_dodatkowa.xhtml")

        val pages = extractor.relevantXhtmlPages(xhtml)
        val all = org.jsoup.Jsoup.parse(xhtml.inputStream(), null, "").select("div.pf").joinToString(" ") { it.text() }

        assertTrue(pages.length < all.length)
        assertFalse(pages.contains("Base CSS for pdf2htmlEX"))
    }

    @Test
    fun `result is capped at 30000 characters and whitespace is normalized`() {
        val text = extractor.read(articleUrl("/wiadomosc/asbis")).text

        assertTrue(text.length <= 30_000)
        assertFalse(Regex("\\s{2,}").containsMatchIn(text))
    }

    @Test
    fun `downloads at most three attachments and sends the article as referer`() {
        extractor.read(articleUrl("/wiadomosc/asbis"))

        assertEquals(3, attachmentRequests().size)
        assertTrue(attachmentRequests().all { it.headers["Referer"] == articleUrl("/wiadomosc/asbis") })
    }

    @Test
    fun `english attachment is skipped when polish ones exist`() {
        extractor.read(articleUrl("/wiadomosc/asbis"))

        assertTrue(attachmentRequests().none { "EN_report" in it.url.encodedPath })
    }

    @Test
    fun `failure of one attachment does not stop the others`() {
        unavailablePaths += requestedPathOf("Raport_polroczny_H1_2026.pdf")

        val text = extractor.read(articleUrl("/wiadomosc/asbis")).text

        assertEquals(3, attachmentRequests().size)
        assertTrue(text.isNotBlank())
    }

    @Test
    fun `page without pdf attachments yields empty text`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse.Builder().body("<html><body><a href=\"/x.html\">x</a><a href=\"/static/att/emitent/p.PDF.html\">y</a></body></html>").build()
        }

        val reading = extractor.read(articleUrl("/wiadomosc/none"))

        assertEquals("", reading.text)
        assertTrue(reading.nothingReadable)
    }

    @Test
    fun `keeps first two pages and keyword pages only`() {
        val pdf = resource("attachments/asbis-ps-2026-2.pdf")

        val pages = extractor.relevantPages(pdf)
        val all = org.apache.pdfbox.Loader.loadPDF(pdf).use { org.apache.pdfbox.text.PDFTextStripper().getText(it) }

        assertTrue(pages.length < all.length)
        assertTrue("1 696,7 mln USD" in pages)
    }

    @Test
    fun `real Eurohold RAR4 attachment is unpacked and its pdf text is read`() {
        served["20261102_150924_0730903372_85__16_02-11-2026.rar,20261102_150924"] = resource("attachments/eurohold-notification-148b-2026-11-02.rar")

        val reading = extractor.read(articleUrl("/wiadomosc/served"))

        assertTrue("Notification under Art. 148 b of the POSA" in reading.text)
        assertTrue("SLS Holding" in reading.text && "4.1382%" in reading.text)
        assertFalse(reading.nothingReadable)
    }

    @Test
    fun `zip attachment is unpacked and its pdf and html entries are read`() {
        served["report.zip"] = zip(
            "Zalacznik.pdf" to resource("attachments/xtb-prelim-q2-2026-1.pdf"),
            "nota.html" to "<html><body><p>Przychody grupy wzrosly</p></body></html>".toByteArray(),
            "podpis.xml" to "<sig/>".toByteArray(),
        )

        val reading = extractor.read(articleUrl("/wiadomosc/served"))

        assertTrue("992 558" in reading.text, "revenue missing")
        assertTrue("Przychody grupy wzrosly" in reading.text)
        assertFalse("<sig/>" in reading.text)
    }

    @Test
    fun `archive without readable entries yields nothing readable`() {
        served["dane.zip"] = zip("BGFSC_DATTAD_ESAPS.xml" to "<x/>".toByteArray())

        val reading = extractor.read(articleUrl("/wiadomosc/served"))

        assertEquals("", reading.text)
        assertTrue(reading.nothingReadable)
    }

    @Test
    fun `real Sopharma RAR5 attachment is unpacked and its pdf text is read`() {
        served["20261102_153125_1157969850_SFA_EGM_30102026.rar"] = resource("attachments/sopharma-egm-minutes-2026-11-02.rar")

        val reading = extractor.read(articleUrl("/wiadomosc/served"))

        assertTrue("Event identification code: SFAEGM30102026" in reading.text)
        assertTrue("PROTOCOL to verify the quorum" in reading.text)
        assertFalse(reading.nothingReadable)
    }

    @Test
    fun `broken RAR5 archive is reported as nothing readable`() {
        served["report.rar"] = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x01, 0x00) + ByteArray(64)

        val reading = extractor.read(articleUrl("/wiadomosc/served"))

        assertEquals("", reading.text)
        assertTrue(reading.nothingReadable)
    }

    @Test
    fun `article with unsupported attachments only yields nothing readable without downloading them`() {
        served["umowa.doc"] = "doc".toByteArray()

        val reading = extractor.read(articleUrl("/wiadomosc/served"))

        assertTrue(reading.nothingReadable)
        assertTrue(attachmentRequests().isEmpty())
    }

    @Test
    fun `pdf that cannot be read is still worth waiting for`() {
        served["raport.pdf"] = "not a pdf".toByteArray()

        val reading = extractor.read(articleUrl("/wiadomosc/served"))

        assertEquals("", reading.text)
        assertFalse(reading.nothingReadable)
    }

    @Test
    fun `zip bomb is rejected without unpacking it fully`() {
        served["bomb.zip"] = zip("bomb.pdf" to ByteArray(64 * 1024 * 1024))

        val reading = extractor.read(articleUrl("/wiadomosc/served"))

        assertEquals("", reading.text)
        assertTrue(reading.nothingReadable)
    }

    @Test
    fun `unpacker enforces entry count and total size limits`() {
        val limits = ArchiveLimits(maxEntries = 2, maxDocumentBytes = 1_000, maxTotalBytes = 1_500)
        val unpacker = ArchiveUnpacker(limits)

        assertFailsWith<ArchiveLimitExceededException> {
            unpacker.unpacked(zip("a.pdf" to ByteArray(1), "b.pdf" to ByteArray(1), "c.pdf" to ByteArray(1)), AttachmentFormat.ZIP)
        }
        assertFailsWith<ArchiveLimitExceededException> {
            unpacker.unpacked(zip("a.pdf" to ByteArray(1_001)), AttachmentFormat.ZIP)
        }
        assertFailsWith<ArchiveLimitExceededException> {
            unpacker.unpacked(zip("a.pdf" to ByteArray(900), "b.pdf" to ByteArray(900)), AttachmentFormat.ZIP)
        }
        assertEquals(2, unpacker.unpacked(zip("a.pdf" to ByteArray(700), "b.pdf" to ByteArray(700)), AttachmentFormat.ZIP).size)
    }

    @Test
    fun `archive formats are recognised in GPW and Bankier attachment urls`() {
        assertEquals(AttachmentFormat.RAR, AttachmentFormat.of("https://www.gpw.pl/attachment/zal01_report.rar,20261102_150924_0000999,20261102"))
        assertEquals(AttachmentFormat.RAR, AttachmentFormat.of("https://bonnier.pl/static/att/emitent/2026-11/20261102_150924_0730903372_85__16_02-11-2026.rar"))
        assertEquals(AttachmentFormat.ZIP, AttachmentFormat.of("https://bonnier.pl/static/att/emitent/2026-11/raport.ZIP"))
        assertEquals(null, AttachmentFormat.of("https://bonnier.pl/static/att/emitent/2026-11/raport.rar.html"))
    }

    @Test
    fun `real Sopharma Q3 RAR5 report brings revenue and net profit into the attachment text`() {
        served["20261030_101159_1129467468_Individual_financial_report_according_to_IFRS_as_at_30_September_2026.rar"] =
            resource("attachments/sopharma-ifrs-q3-2026.rar")

        val reading = extractor.read(articleUrl("/wiadomosc/served"))

        assertTrue("132,211" in reading.text, "revenue missing")
        assertTrue("28,410" in reading.text, "net profit missing")
        assertTrue(reading.text.length <= 30_000)
    }

    @Test
    fun `zip entry named in IBM852 is read`() {
        served["raport.zip"] = zip("Sprawozdanie_finansowe_ŁÓDŹ_żółć.pdf" to resource("attachments/xtb-prelim-q2-2026-1.pdf"), charset = charset("IBM852"))

        val reading = extractor.read(articleUrl("/wiadomosc/served"))

        assertTrue("992 558" in reading.text)
    }

    @Test
    fun `pdf attachments keep their selection and text when an ESEF zip sits next to them`() {
        served["Raport_polroczny_H1_2026.pdf"] = resource("attachments/asbis-ps-2026-2.pdf")
        served["Sprawozdanie_finansowe_H1_2026.pdf"] = resource("attachments/asbis-ps-2026-1.pdf")
        val withoutZip = extractor.read(articleUrl("/wiadomosc/served")).text
        requested.clear()
        served["asbis-2026-06-30-pl.zip"] = zip("reports/asbis-2026-06-30-pl.xhtml" to "<html><body>Przychody ESEF 1</body></html>".toByteArray())

        val withZip = extractor.read(articleUrl("/wiadomosc/served")).text

        assertEquals(withoutZip, withZip)
        assertEquals(2, attachmentRequests().size)
        assertTrue(attachmentRequests().none { it.url.encodedPath.endsWith(".zip") })
    }

    @Test
    fun `unreadable pdf next to an archive without readable entries is still worth waiting for`() {
        served["dane.zip"] = zip("BGFSC_DATTAD_ESAPS.xml" to "<x/>".toByteArray())
        served["raport.pdf"] = "not a pdf".toByteArray()

        val reading = extractor.read(articleUrl("/wiadomosc/served"))

        assertEquals("", reading.text)
        assertFalse(reading.nothingReadable)
    }

    @Test
    fun `encrypted zip entry is skipped and the entries before it are kept`() {
        val archive = zip("a.html" to "<p>Przychody A</p>".toByteArray(), "b.html" to "<p>Przychody B</p>".toByteArray())
        val secondHeader = archive.indexOfLocalHeader(after = 0)
        archive[secondHeader + 6] = (archive[secondHeader + 6].toInt() or 1).toByte()

        val names = ArchiveUnpacker().unpacked(archive, AttachmentFormat.ZIP).map { it.name }

        assertEquals(listOf("a.html"), names)
    }

    @Test
    fun `archive above the size limit is rejected`() {
        val unpacker = ArchiveUnpacker(ArchiveLimits(maxArchiveBytes = 100))

        assertFailsWith<ArchiveLimitExceededException> {
            unpacker.unpacked(zip("a.pdf" to kotlin.random.Random(1).nextBytes(500)), AttachmentFormat.ZIP)
        }
    }

    @Test
    fun `unpacker stops after the maximum number of documents`() {
        val unpacker = ArchiveUnpacker(ArchiveLimits(maxDocuments = 2))
        val read = mutableListOf<String>()

        unpacker.readDocuments(zip("a.pdf" to ByteArray(1), "b.pdf" to ByteArray(1), "c.pdf" to ByteArray(1)), AttachmentFormat.ZIP) { read += it.name }

        assertEquals(listOf("a.pdf", "b.pdf"), read)
    }

    private fun ArchiveUnpacker.unpacked(archive: ByteArray, format: AttachmentFormat) = readDocuments(archive, format) { it }

    private fun ByteArray.indexOfLocalHeader(after: Int): Int =
        (after + 1 until size - 3).first { this[it] == 0x50.toByte() && this[it + 1] == 0x4b.toByte() && this[it + 2] == 0x03.toByte() && this[it + 3] == 0x04.toByte() }

    private fun zip(vararg entries: Pair<String, ByteArray>, charset: java.nio.charset.Charset = Charsets.UTF_8): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out, charset).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun requestedPathOf(suffix: String): String {
        extractor.read(articleUrl("/wiadomosc/asbis"))
        val path = attachmentRequests().first { it.url.encodedPath.endsWith(suffix) }.url.encodedPath
        requested.clear()
        return path
    }
}
