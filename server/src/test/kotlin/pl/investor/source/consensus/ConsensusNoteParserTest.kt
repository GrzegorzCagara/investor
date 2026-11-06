package pl.investor.source.consensus

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
        val note = parser.parseArticle(fixture("bankier/asbis-konsensus-q2-2026.html"))

        assertEquals(Instant.parse("2026-08-06T04:46:00Z"), note.publishedAt)
        assertEquals(
            listOf(
                ConsensusRow("Przychody", BigDecimal("1696.7"), BigDecimal("1686.3"), 0.6),
                ConsensusRow("EBITDA", BigDecimal("66.4"), BigDecimal("68.6"), -3.1),
                ConsensusRow("EBIT", BigDecimal("62.6"), BigDecimal("64.8"), -3.3),
                ConsensusRow("zysk netto j.d.", BigDecimal("44.5"), BigDecimal("42.7"), 4.1),
            ),
            note.tableRows,
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
            parser.parseArticle(html).tableRows,
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
            parser.parseArticle(html).tableRows,
        )
    }

    @Test
    fun `page without a consensus table header gives no rows`() {
        val html = note("2026-08-06T06:46:00+0200", table(listOf("Przychody", "1696,7", "1686,3", "0,6%")))

        assertTrue(parser.parseArticle(html).tableRows.isEmpty())
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
            val publishedAt = parser.parseArticle(note(value)).publishedAt
            assertEquals(expected, publishedAt.truncatedTo(ChronoUnit.MINUTES), value)
        }
    }

    @Test
    fun `note without datePublished cannot be parsed`() {
        assertFailsWith<IllegalStateException> { parser.parseArticle(table(header)) }
    }

    @Test
    fun `news list yields the ASBIS consensus note link among the other articles`() {
        val links = parser.articleLinks(fixture("bankier/asbis-wiadomosci.html"), baseUrl)

        assertTrue(ConsensusLink(noteUrl, Instant.parse("2026-08-06T04:46:00Z")) in links)
        assertTrue(links.size > 1)
        assertEquals(links.size, links.map { it.url }.toSet().size)
    }

    @Test
    fun `news list yields the Unimot note whatever its title says`() {
        val links = parser.articleLinks(fixture("bankier/unimot-wiadomosci.html"), baseUrl)

        assertEquals(
            ConsensusLink(
                "$baseUrl/wiadomosc/Skorygowana-EBITDA-Unimotu-w-III-kw-wyniosla-133-mln-zl-wobec-108-8-mln-zl-konsensusu-9224554.html",
                Instant.parse("2026-11-05T19:15:00Z"),
            ),
            links.first(),
        )
    }

    @Test
    fun `Unimot text note has no table and its JSON-LD article body mentions the consensus`() {
        val article = parser.parseArticle(fixture("bankier/unimot-konsensus-prelim-q3-2026.html"))

        assertEquals(Instant.parse("2026-11-05T18:58:00Z"), article.publishedAt)
        assertTrue(article.tableRows.isEmpty())
        assertEquals("Unimot zaskoczył wynikami. Przychody w III kwartale sięgnęły aż 6 mld zł", article.headline)
        val body = checkNotNull(article.body)
        assertTrue("Analitycy ankietowani przez PAP Biznes spodziewali się 108,8 mln zł skorygowanej EBITDA" in body)
        assertTrue("Konsensus PAP Biznes przewidywał 4,776 mld zł przychodów" in body)
        assertTrue(parser.mentionsConsensus(body))
    }

    @Test
    fun `Orlen note keeps its PAP table rows`() {
        val article = parser.parseArticle(fixture("bankier/orlen-konsensus-q3-2026.html"))

        assertEquals(Instant.parse("2026-11-05T16:16:00Z"), article.publishedAt)
        assertEquals(ConsensusRow("Przychody", BigDecimal("80140"), BigDecimal("85025"), -5.7), article.tableRows.first())
        assertTrue(article.tableRows.any { it.label.startsWith("EBITDA LIFO") && it.actual == BigDecimal("14779") && it.differencePct == -3.5 })
    }

    @Test
    fun `Orlen tax article is not about the consensus`() {
        val article = parser.parseArticle(fixture("bankier/orlen-article-podatek-2026-11-05.html"))

        assertTrue(article.tableRows.isEmpty())
        assertFalse(parser.mentionsConsensus(checkNotNull(article.body)))
    }

    @Test
    fun `consensus mention ignores case and inflection`() {
        assertTrue(parser.mentionsConsensus("wobec 108,8 mln zł KONSENSUSU"))
        assertFalse(parser.mentionsConsensus("Analitycy oczekiwali więcej"))
    }

    private fun listItem(href: String, date: String, title: String) =
        """<li class="m-listing-article-list__item"><a class="m-listing-article-list__anchor" href="$href"><div class="m-listing-article-list__date-time">$date</div><div class="m-listing-article-list__title">$title</div></a></li>"""

    @Test
    fun `every listed article is a candidate whatever its title`() {
        val html = listItem("/wiadomosc/a-1.html", "2026-08-05 08:00", "Konsensus PAP: oczekiwane wyniki Asbisu w II kwartale 2026 roku") +
            listItem("/wiadomosc/b-2.html", "2026-08-06 06:46", "Asbis zaskoczył wynikami")

        assertEquals(
            listOf(
                ConsensusLink("$baseUrl/wiadomosc/a-1.html", Instant.parse("2026-08-05T06:00:00Z")),
                ConsensusLink("$baseUrl/wiadomosc/b-2.html", Instant.parse("2026-08-06T04:46:00Z")),
            ),
            parser.articleLinks(html, baseUrl),
        )
    }

    @Test
    fun `unparseable list date gives a link without listedAt`() {
        val html = listItem("/wiadomosc/b-2.html", "wczoraj 06:46", "Wyniki Asbisu vs. konsensus PAP (tabela)")

        assertEquals(listOf(ConsensusLink("$baseUrl/wiadomosc/b-2.html", null)), parser.articleLinks(html, baseUrl))
    }

    @Test
    fun `page without consensus notes yields no links`() {
        assertTrue(parser.articleLinks(fixture("bankier/asbis-komunikaty.html"), baseUrl).isEmpty())
    }

    @Test
    fun `headlines carry the title with its label as a prefix, the absolute url and the listing time`() {
        val headlines = parser.headlines(fixture("bankier/unimot-wiadomosci.html"), baseUrl)

        assertEquals(19, headlines.size)
        assertEquals(
            BankierHeadline(
                "Unimot zaskoczył wynikami. Przychody w III kwartale sięgnęły aż 6 mld zł",
                "$baseUrl/wiadomosc/Skorygowana-EBITDA-Unimotu-w-III-kw-wyniosla-133-mln-zl-wobec-108-8-mln-zl-konsensusu-9224554.html",
                Instant.parse("2026-11-05T19:15:00Z"),
            ),
            headlines.first(),
        )
        assertEquals(
            BankierHeadline(
                "[OPINIA] Ceny gazu najwyższe od 2022 roku. Analitycy ostrzegają przed zimą",
                headlines[1].url,
                Instant.parse("2026-11-02T09:25:00Z"),
            ),
            headlines[1],
        )
    }

    @Test
    fun `listed article without a parseable time or title gives no headline`() {
        val html = listItem("/wiadomosc/b-2.html", "wczoraj 06:46", "Wyniki Asbisu vs. konsensus PAP (tabela)") +
            listItem("/wiadomosc/b-3.html", "2026-08-06 06:46", " ")

        assertTrue(parser.headlines(html, baseUrl).isEmpty())
    }
}
