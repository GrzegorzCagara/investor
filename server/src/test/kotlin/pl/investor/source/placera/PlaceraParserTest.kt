package pl.investor.source.placera

import pl.investor.scoring.PastReaction
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PlaceraParserTest {

    private fun fixture(path: String) = checkNotNull(javaClass.getResource("/fixtures/se/$path")) { "missing fixture $path" }.readText()

    private fun assertDecimal(expected: String, actual: BigDecimal?) = assertEquals(0, BigDecimal(expected).compareTo(actual), "$expected vs $actual")

    @Test
    fun `listing yields only preview links as absolute urls`() {
        val links = PlaceraParser.previewLinks(fixture("placera-telegram.html"), "https://www.placera.se")

        assertEquals(
            listOf("https://www.placera.se/telegram/coffee-stain-group-coffee-stain-vantas-redovisa-81-miljoner-kronor-i-cash-ebit-modular-20261102"),
            links,
        )
    }

    @Test
    fun `Mips preview has report date, FactSet consensus in thousands SEK, analysts and eight past reactions`() {
        val article = PlaceraParser.article(fixture("placera-mips-q3-2026.html"))

        assertEquals("Mips", article.companyName)
        assertEquals("SE0009216278", article.isin)
        assertEquals(LocalDate.parse("2026-10-21"), article.reportDate)
        assertEquals("SEK", article.currency)
        assertEquals("FactSet", article.source)
        assertEquals(Instant.parse("2026-10-19T11:54:47Z"), article.publishedAt)
        val revenue = checkNotNull(article.revenue)
        assertDecimal("216000", revenue.consensus)
        assertDecimal("135000", revenue.prevYear)
        assertEquals(6, revenue.analysts)
        val ebit = checkNotNull(article.ebit)
        assertDecimal("58000", ebit.consensus)
        assertDecimal("44000", ebit.prevYear)
        assertEquals(6, ebit.analysts)
        assertEquals(
            listOf(27.8, 8.7, -5.6, 11.2, 2.2, -7.7, 3.3, 6.6),
            article.pastReactions.map { it.pct },
        )
        assertEquals(PastReaction(LocalDate.parse("2026-07-16"), 27.8), article.pastReactions.first())
        assertEquals(LocalDate.parse("2024-10-24"), article.pastReactions.last().date)
    }

    @Test
    fun `cash ebit consensus is not taken as EBIT while net sales are`() {
        val article = PlaceraParser.article(fixture("placera-coffee-stain-q2-2026.html"))

        assertEquals("Coffee Stain", article.companyName)
        assertEquals("SE0026599557", article.isin)
        assertEquals(LocalDate.parse("2026-11-05"), article.reportDate)
        assertEquals("Modular Finance", article.source)
        assertDecimal("271000", checkNotNull(article.revenue).consensus)
        assertDecimal("224000", checkNotNull(article.revenue).prevYear)
        assertEquals(10, checkNotNull(article.revenue).analysts)
        assertNull(article.ebit)
        assertEquals(listOf(-2.0, 3.8), article.pastReactions.map { it.pct })
    }

    @Test
    fun `page without a consensus table is unparseable`() {
        assertFailsWith<UnparseablePreviewException> { PlaceraParser.article(fixture("placera-telegram.html")) }
    }
}
