package pl.investor.source.consensus

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConsensusNoteParserTest {

    private val parser = ConsensusNoteParser()
    private val baseUrl = "https://www.bankier.pl"
    private val noteUrl = "$baseUrl/wiadomosc/Wyniki-Asbisu-w-II-kwartale-2026-roku-vs-konsensus-PAP-tabela-9178558.html"

    private fun fixture(path: String) = checkNotNull(javaClass.getResource("/fixtures/$path")) { "missing fixture $path" }.readText()

    private fun note(published: String, vararg tables: String) =
        """<script type="application/ld+json">{"datePublished": "$published"}</script>""" + tables.joinToString("")

    private fun table(vararg rows: List<String>) =
        "<table>" + rows.joinToString("") { cells -> "<tr>" + cells.joinToString("") { "<td>$it</td>" } + "</tr>" } + "</table>"

    private val header = listOf("2Q2026", "wyniki", "kons.", "różnica", "r/r")

    @Test
    fun `ASBIS note gives the four percentage rows and the publication time`() {
        val note = parser.parseNote(fixture("bankier/asbis-konsensus-q2-2026.html"), noteUrl)

        assertEquals(noteUrl, note.url)
        assertEquals(Instant.parse("2026-08-06T04:46:00Z"), note.publishedAt)
        assertEquals(
            listOf(
                ConsensusRow("Przychody", BigDecimal("1696.7"), BigDecimal("1686.3"), 0.6),
                ConsensusRow("EBITDA", BigDecimal("66.4"), BigDecimal("68.6"), -3.1),
                ConsensusRow("EBIT", BigDecimal("62.6"), BigDecimal("64.8"), -3.3),
                ConsensusRow("zysk netto j.d.", BigDecimal("44.5"), BigDecimal("42.7"), 4.1),
            ),
            note.rows,
        )
    }

    @Test
    fun `typographic minus, leading plus, negative actual and thousands separator are parsed`() {
        val html = note(
            "2026-08-06T06:46:00+0200",
            table(
                header,
                listOf("Przychody", "1 696,7", "1 686,3", "+0,6%", "78,7%"),
                listOf("EBITDA", "66,4", "68,6", "−3,1%", "156,0%"),
                listOf("zysk netto j.d.", "-12,5", "-10,0", "-25,0%", "-"),
                listOf("marża EBITDA", "3,9%", "4,1%", "−0,15", "1,18"),
            ),
        )

        assertEquals(
            listOf(
                ConsensusRow("Przychody", BigDecimal("1696.7"), BigDecimal("1686.3"), 0.6),
                ConsensusRow("EBITDA", BigDecimal("66.4"), BigDecimal("68.6"), -3.1),
                ConsensusRow("zysk netto j.d.", BigDecimal("-12.5"), BigDecimal("-10.0"), -25.0),
            ),
            parser.parseNote(html, noteUrl).rows,
        )
    }

    @Test
    fun `columns are taken from the consensus table header and other tables are ignored`() {
        val html = note(
            "2026-08-06T06:46:00+0200",
            table(listOf("Notowania", "kurs", "zmiana", "obrót"), listOf("WIG", "100,0", "50,0", "1,0%")),
            table(
                listOf("2Q2026", "r/r", "różnica", "kons.", "wyniki"),
                listOf("Przychody", "78,7%", "0,6%", "1686,3", "1696,7"),
            ),
        )

        assertEquals(
            listOf(ConsensusRow("Przychody", BigDecimal("1696.7"), BigDecimal("1686.3"), 0.6)),
            parser.parseNote(html, noteUrl).rows,
        )
    }

    @Test
    fun `page without a consensus table header gives no rows`() {
        val html = note("2026-08-06T06:46:00+0200", table(listOf("Przychody", "1696,7", "1686,3", "0,6%")))

        assertTrue(parser.parseNote(html, noteUrl).rows.isEmpty())
    }

    @Test
    fun `datePublished is accepted with or without seconds, fractions and offset`() {
        val expected = Instant.parse("2026-08-06T04:46:00Z")
        listOf(
            "2026-08-06T06:46:00+0200",
            "2026-08-06T06:46:00+02:00",
            "2026-08-06T06:46+02:00",
            "2026-08-06T06:46:00.000+02:00",
            "2026-08-06T04:46:00Z",
            "2026-08-06T04:46:00.123456Z",
            "2026-08-06T06:46:00",
            "2026-08-06T06:46",
        ).forEach { value ->
            val publishedAt = parser.parseNote(note(value), noteUrl).publishedAt
            assertEquals(expected, publishedAt.truncatedTo(ChronoUnit.MINUTES), value)
        }
    }

    @Test
    fun `note without datePublished cannot be parsed`() {
        assertFailsWith<IllegalStateException> { parser.parseNote(table(header), noteUrl) }
    }

    @Test
    fun `news list yields the ASBIS consensus note link`() {
        val links = parser.noteLinks(fixture("bankier/asbis-wiadomosci.html"), baseUrl)

        assertEquals(listOf(ConsensusLink(noteUrl, Instant.parse("2026-08-06T04:46:00Z"))), links)
    }

    private fun listItem(href: String, date: String, title: String) =
        """<li class="m-listing-article-list__item"><a class="m-listing-article-list__anchor" href="$href"><div class="m-listing-article-list__date-time">$date</div><div class="m-listing-article-list__title">$title</div></a></li>"""

    @Test
    fun `consensus preview without vs is not a note link`() {
        val html = listItem("/wiadomosc/a-1.html", "2026-08-05 08:00", "Konsensus PAP: oczekiwane wyniki Asbisu w II kwartale 2026 roku") +
            listItem("/wiadomosc/b-2.html", "2026-08-06 06:46", "Wyniki Asbisu w II kwartale vs. konsensus PAP (tabela)")

        assertEquals(listOf(ConsensusLink("$baseUrl/wiadomosc/b-2.html", Instant.parse("2026-08-06T04:46:00Z"))), parser.noteLinks(html, baseUrl))
    }

    @Test
    fun `unparseable list date gives a link without listedAt`() {
        val html = listItem("/wiadomosc/b-2.html", "wczoraj 06:46", "Wyniki Asbisu vs. konsensus PAP (tabela)")

        assertEquals(listOf(ConsensusLink("$baseUrl/wiadomosc/b-2.html", null)), parser.noteLinks(html, baseUrl))
    }

    @Test
    fun `page without consensus notes yields no links`() {
        assertTrue(parser.noteLinks(fixture("bankier/asbis-komunikaty.html"), baseUrl).isEmpty())
    }
}
