package pl.investor.source.gpw

import io.mockk.mockk
import org.junit.jupiter.api.Test
import pl.investor.report.Market
import pl.investor.source.PoliteHttpClient
import java.time.LocalDate
import kotlin.test.assertEquals

class EspiListingClientTest {

    private val client = EspiListingClient(mockk<PoliteHttpClient>(), EspiListingParser())
    private val date = LocalDate.of(2026, 9, 21)

    @Test
    fun `gpw form requests ESPI with every periodic type`() {
        val form = client.form(Market.GPW, date, 50)

        assertEquals("21-09-2026", form.single { it.first == "date" }.second)
        assertEquals("50", form.single { it.first == "offset" }.second)
        assertEquals("50", form.single { it.first == "limit" }.second)
        assertEquals("komunikaty", form.single { it.first == "page" }.second)
        assertEquals(listOf("RB", "Q", "P", "O", "R"), form.filter { it.first == "typeRaports[]" }.map { it.second })
        assertEquals(listOf("ESPI"), form.filter { it.first == "categoryRaports[]" }.map { it.second })
    }

    @Test
    fun `nc form requests ESPI and EBI from the NC page`() {
        val form = client.form(Market.NC, date, 0)

        assertEquals("spolki-komunikaty-spolek", form.single { it.first == "page" }.second)
        assertEquals(listOf("ESPI", "EBI"), form.filter { it.first == "categoryRaports[]" }.map { it.second })
    }
}
