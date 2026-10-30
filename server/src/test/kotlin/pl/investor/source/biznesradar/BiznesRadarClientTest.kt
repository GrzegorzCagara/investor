package pl.investor.source.biznesradar

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import pl.investor.report.Company
import pl.investor.report.Market
import pl.investor.source.PoliteHttpClient
import pl.investor.source.Source
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BiznesRadarClientTest {

    private val http = mockk<PoliteHttpClient>()
    private val client = BiznesRadarClient(http, QuarterlyResultsParser(), ValuationParser())

    private fun fixture(name: String) =
        javaClass.getResource("/fixtures/biznesradar/$name")!!.readText()

    private fun company(name: String, ticker: String?) = Company(
        id = null,
        name = name,
        isin = null,
        market = Market.GPW,
        ticker = ticker,
        biznesradarSymbol = null,
        publishesMonthlySales = false,
    )

    private fun stubSymbols() {
        every { http.get(Source.BIZNESRADAR, "https://www.biznesradar.pl/service-data-js/8") } returns fixture("symbols-raw.js")
    }

    @Test
    fun `ticker resolves to the biznesradar symbol`() {
        stubSymbols()

        assertEquals("ASBISC", client.symbolFor(company("ASBISC ENTERPRISES PLC", "ASB")))
        assertEquals("XTB", client.symbolFor(company("XTB SPÓŁKA AKCYJNA", "XTB")))
        assertEquals("CRJ", client.symbolFor(company("CREEPY JAR SPÓŁKA AKCYJNA", "CRJ")))
    }

    @Test
    fun `name without ticker resolves ignoring legal suffixes and diacritics`() {
        stubSymbols()

        assertEquals("ASBISC", client.symbolFor(company("ASBISC ENTERPRISES PLC", null)))
        assertEquals("CRJ", client.symbolFor(company("Creepy Jar S.A.", null)))
        assertEquals("CRJ", client.symbolFor(company("CREEPY JAR SPÓŁKA AKCYJNA", null)))
    }

    @Test
    fun `name match picks the stock and not a bond of the same issuer`() {
        stubSymbols()

        assertEquals("BEST", client.symbolFor(company("BEST SA", null)))
        assertEquals("KRUK", client.symbolFor(company("KRUK SA", null)))
    }

    @Test
    fun `unknown company throws`() {
        stubSymbols()

        assertFailsWith<SymbolNotFoundException> { client.symbolFor(company("NIE ISTNIEJE SA", "ZZZ")) }
    }

    @Test
    fun `symbol list is downloaded once`() {
        stubSymbols()

        client.symbolFor(company("A", "ASB"))
        client.symbolFor(company("B", "XTB"))

        verify(exactly = 1) { http.get(Source.BIZNESRADAR, any()) }
    }

    @Test
    fun `quarterly and valuation fetch the symbol pages`() {
        every { http.get(Source.BIZNESRADAR, "https://www.biznesradar.pl/raporty-finansowe-rachunek-zyskow-i-strat/XTB,Q") } returns fixture("XTB-pl-q.html")
        every { http.get(Source.BIZNESRADAR, "https://www.biznesradar.pl/wskazniki-wartosci-rynkowej/XTB") } returns fixture("XTB-valuation.html")

        assertEquals(true, client.quarterly("XTB").isNotEmpty())
        assertEquals(true, client.valuation("XTB").isNotEmpty())
    }
}
