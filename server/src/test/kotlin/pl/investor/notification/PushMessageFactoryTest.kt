package pl.investor.notification

import pl.investor.extraction.AiPool
import pl.investor.report.Company
import pl.investor.report.Market
import pl.investor.scoring.Score
import pl.investor.scoring.ScoreComponents
import pl.investor.source.Source
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class PushMessageFactoryTest {

    private val factory = PushMessageFactory()
    private val asbis = Company(1, "ASBIS ENTERPRISES PLC", "CY1000031710", Market.GPW, "ASB", "ASBISC", true, shortName = "ASBIS")

    private fun score(total: Double) = Score(total, ScoreComponents(0.0, 0.0, 0.0, 0.0, 0.0, 0.0), 0.0, emptySet())

    @Test
    fun `push title format`() {
        assertEquals("ASBIS (GPW) · WYNIKI · ocena 83", factory.forResults(asbis, Market.GPW, score(82.5), "…", 1).title)
    }

    @Test
    fun `title falls back to the full name when there is no short name`() {
        val company = asbis.copy(shortName = null, name = "XTB")

        assertEquals("XTB (NC) · WYNIKI · ocena 71", factory.forResults(company, Market.NC, score(70.9), "…", 1).title)
    }

    @Test
    fun `results message carries the alert id`() {
        val message = factory.forResults(asbis, Market.GPW, score(80.0), "Zdanie.", 42)

        assertEquals(mapOf("alertId" to "42"), message.data)
        assertEquals(42L, message.reportId)
        assertEquals(PushKind.RESULTS, message.kind)
    }

    @Test
    fun `push body is the first sentence cut to 180 chars`() {
        val reason = "Przychody +91% r/r; 7. mocny raport z rzędu; C/Z 20,1. Drugie zdanie."
        assertEquals("Przychody +91% r/r; 7. mocny raport z rzędu; C/Z 20,1.", factory.forResults(asbis, Market.GPW, score(80.0), reason, 1).body)

        val long = "a".repeat(300) + ". Koniec."
        assertEquals(180, factory.forResults(asbis, Market.GPW, score(80.0), long, 1).body.length)
    }

    @Test
    fun `source outage message shows the Warsaw time of the last success`() {
        val message = factory.forSourceOutage(Source.GPW_LISTING, Instant.parse("2026-09-21T15:05:00Z"))

        assertEquals("Źródło GPW_LISTING nie działa", message.title)
        assertEquals("Brak danych od 17:05", message.body)
        assertEquals(PushKind.SOURCE_OUTAGE, message.kind)
    }

    @Test
    fun `AI budget message names the pool`() {
        assertEquals("Do końca miesiąca raporty GPW/NC są oceniane bez AI", factory.forAiBudgetExhausted(AiPool.GPW).body)
        assertEquals("Do końca miesiąca raporty USA są oceniane bez AI", factory.forAiBudgetExhausted(AiPool.US).body)
        assertEquals("Limit AI wyczerpany", factory.forAiBudgetExhausted(AiPool.GPW).title)
    }
}
