package pl.investor.history

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import pl.investor.report.Company
import pl.investor.report.CompanyRepository
import pl.investor.report.Market
import pl.investor.support.PostgresTestBase
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CompanyForecastRepositoryTest : PostgresTestBase() {

    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var companies: CompanyRepository
    @Autowired lateinit var forecasts: CompanyForecastRepository

    private var imsId = 0L

    @BeforeEach
    fun setUp() {
        jdbc.sql("TRUNCATE company RESTART IDENTITY CASCADE").update()
        imsId = companies.save(Company(null, "IMS", "PLINTMS00019", Market.GPW, "IMS", "IMS", true)).id!!
    }

    private fun forecast(yearEnd: String, revenue: Int, publishedAt: String) =
        CompanyForecast(imsId, LocalDate.parse(yearEnd), "PLN", BigDecimal(revenue), null, null, Instant.parse(publishedAt), null)

    @Test
    fun `latest forecast of the year containing the period and published before the report is used`() {
        forecasts.save(forecast("2026-12-31", 75000, "2026-03-20T07:00:00Z"))
        forecasts.save(forecast("2026-12-31", 80000, "2026-09-15T11:53:00Z"))
        forecasts.save(forecast("2026-12-31", 85000, "2026-11-20T07:00:00Z"))
        forecasts.save(forecast("2027-12-31", 95000, "2026-10-01T07:00:00Z"))

        val used = forecasts.latestForYearOf(imsId, LocalDate.parse("2026-09-30"), Instant.parse("2026-11-17T06:57:32Z"))

        assertEquals(BigDecimal("80000"), used?.revenue?.stripTrailingZeros()?.setScale(0))
        assertEquals(LocalDate.parse("2026-12-31"), used?.periodEnd)
    }

    @Test
    fun `no forecast before the report gives nothing`() {
        forecasts.save(forecast("2026-12-31", 85000, "2026-11-20T07:00:00Z"))

        assertNull(forecasts.latestForYearOf(imsId, LocalDate.parse("2026-09-30"), Instant.parse("2026-11-17T06:57:32Z")))
    }
}
