package pl.investor.source.quotes

import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import pl.investor.history.DailyQuote
import pl.investor.source.quotes.QuoteFixtures.ASBIS_ISIN
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GpwArchiveParserTest {

    private val parser = GpwArchiveParser()

    private fun parse(path: String, date: LocalDate): List<DailyQuote> = parser.parse(QuoteFixtures.bytes(path), date)

    private fun assertDecimal(expected: String, actual: BigDecimal?) =
        assertEquals(0, BigDecimal(expected).compareTo(actual), "expected $expected but was $actual")

    @Test
    fun `ASBIS closes on 18 and 21 September 2026`() {
        val first = parse("quotes/gpw-2026-09-18.xls", LocalDate.of(2026, 9, 18)).single { it.instrument == ASBIS_ISIN }
        val second = parse("quotes/gpw-2026-09-21.xls", LocalDate.of(2026, 9, 21)).single { it.instrument == ASBIS_ISIN }

        assertDecimal("164.20", first.close)
        assertDecimal("175.00", second.close)
        assertEquals(LocalDate.of(2026, 9, 21), second.date)
    }

    @Test
    fun `volume and turnover are parsed`() {
        val asbis = parse("quotes/gpw-2026-09-21.xls", LocalDate.of(2026, 9, 21)).single { it.instrument == ASBIS_ISIN }

        assertTrue(asbis.volume in 612_810L..625_190L, "volume ${asbis.volume}")
        assertDecimal("108340210", asbis.turnover)
    }

    @Test
    fun `every row has an ISIN as instrument`() {
        val quotes = parse("quotes/gpw-2026-09-21.xls", LocalDate.of(2026, 9, 21))

        assertTrue(quotes.size > 300)
        assertTrue(quotes.all { it.instrument.length == 12 })
    }

    @Test
    fun `an empty header cell does not shift the columns`() {
        val book = HSSFWorkbook()
        val sheet = book.createSheet()
        listOf("Data", "Nazwa", null, "ISIN", "Kurs zamknięcia", "Wolumen", "Obrót").let { header ->
            val row = sheet.createRow(0)
            header.forEachIndexed { i, text -> text?.let { row.createCell(i).setCellValue(it) } }
        }
        sheet.createRow(1).apply {
            createCell(0).setCellValue("2026-09-21")
            createCell(1).setCellValue("ABC")
            createCell(3).setCellValue("PLABC0000012")
            createCell(4).setCellValue(12.5)
            createCell(5).setCellValue(100.0)
            createCell(6).setCellValue(2.0)
        }
        val bytes = ByteArrayOutputStream().also { book.write(it) }.toByteArray()

        val quote = parser.parse(bytes, LocalDate.of(2026, 9, 21)).single()

        assertEquals("PLABC0000012", quote.instrument)
        assertDecimal("12.5", quote.close)
        assertEquals(100L, quote.volume)
        assertDecimal("2000", quote.turnover)
    }

    @Test
    fun `index file is not mistaken for stocks by name lookup`() {
        val bytes = QuoteFixtures.bytes("index/gpw-indices-2026-09-21.xls")

        val wig = parser.parseIndex(bytes, LocalDate.of(2026, 9, 21), "WIG")

        assertDecimal("158381.53", wig?.close)
        assertEquals("WIG", wig?.instrument)
    }
}
