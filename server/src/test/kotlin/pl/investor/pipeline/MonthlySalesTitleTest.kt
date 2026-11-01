package pl.investor.pipeline

import org.junit.jupiter.api.Test
import pl.investor.report.Market
import pl.investor.report.ReportKind
import pl.investor.source.gpw.ListingEntry
import java.time.Instant
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MonthlySalesTitleTest {

    private fun current(title: String?, kind: ReportKind = ReportKind.CURRENT) = ListingEntry(
        market = Market.GPW,
        companyName = "SPOLKA",
        isin = null,
        kind = kind,
        number = "1/2026",
        sourceRef = "1",
        title = title,
        publishedAt = Instant.parse("2026-10-20T15:00:00Z"),
        detailUrl = "https://example.invalid/1",
    )

    @Test
    fun `titles with the word monthly still match`() {
        assertTrue(current("Informacja o szacunkowych miesięcznych skonsolidowanych przychodach za wrzesień 2026 r.").isMonthlySales())
        assertTrue(current("Informacja poufna - raport miesięczny o przychodach ze sprzedaży za lipiec 2026 roku").isMonthlySales())
        assertTrue(current("Informacja o miesięcznych przychodach ze sprzedaży").isMonthlySales())
    }

    @Test
    fun `revenue titles naming a single month match`() {
        assertTrue(current("Szacunkowe przychody ze sprzedaży Spółki oraz koszty akwizycji nowych graczy w lipcu 2026 r.").isMonthlySales())
        assertTrue(current("Przychody ze sprzedaży za październik 2026 r.").isMonthlySales())
        assertTrue(current("PRZYCHODY ZE SPRZEDAŻY ZA MIESIĄC WRZESIEŃ 2026").isMonthlySales())
        assertTrue(current("Sprzedaż we wrześniu 2026 r.").isMonthlySales())
    }

    @Test
    fun `quarter, half-year and asset sale titles do not match`() {
        assertFalse(current("Szacunkowe przychody ze sprzedaży za III kwartał 2026 r.").isMonthlySales())
        assertFalse(current("Szacunkowa wysokość skonsolidowanych przychodów ze sprzedaży za okres 01.01-30.06.2026 r.").isMonthlySales())
        assertFalse(current("Sprzedaż akcji przez członka zarządu w lipcu 2026 r.").isMonthlySales())
        assertFalse(current("Zawarcie umowy sprzedaży nieruchomości w maju 2026 r.").isMonthlySales())
        assertFalse(current("Raport miesięczny za wrzesień 2026 r.").isMonthlySales())
        assertFalse(current("Zawarcie umowy z dnia 5 lipca 2026 r. na dostawę").isMonthlySales())
    }

    @Test
    fun `quarterly reports and missing titles do not match`() {
        assertFalse(current("Przychody ze sprzedaży za październik 2026 r.", ReportKind.QUARTERLY).isMonthlySales())
        assertFalse(current(null).isMonthlySales())
    }
}
