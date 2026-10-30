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

class PointInTimeQueriesTest : PostgresTestBase() {

    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var companies: CompanyRepository
    @Autowired lateinit var quarterResults: QuarterResultRepository
    @Autowired lateinit var revenuePeriods: RevenuePeriodRepository
    @Autowired lateinit var valuations: ValuationRepository
    @Autowired lateinit var quotes: QuoteRepository

    private var asbisId = 0L

    @BeforeEach
    fun setUp() {
        jdbc.sql("TRUNCATE company, daily_quote RESTART IDENTITY CASCADE").update()
        asbisId = companies.save(
            Company(null, "ASBIS", "CY0101562015", Market.GPW, "ASB", "ASBISC", true)
        ).id!!
    }

    @Test
    fun `quarter results ignore rows published on or after the as-of date`() {
        quarterResults.save(quarterResult(LocalDate.parse("2026-03-31"), LocalDate.parse("2026-05-07")))
        quarterResults.save(quarterResult(LocalDate.parse("2026-06-30"), LocalDate.parse("2026-08-06")))

        val rows = quarterResults.publishedBefore(asbisId, LocalDate.parse("2026-08-06"), 8)

        assertEquals(listOf(LocalDate.parse("2026-03-31")), rows.map { it.quarterEnd })
    }

    @Test
    fun `first quarter published in a window is the earliest publication, ties go to the latest quarter`() {
        quarterResults.save(quarterResult(LocalDate.parse("2025-12-31"), LocalDate.parse("2026-08-07")))
        quarterResults.save(quarterResult(LocalDate.parse("2026-06-30"), LocalDate.parse("2026-08-07")))
        quarterResults.save(quarterResult(LocalDate.parse("2026-03-31"), LocalDate.parse("2026-08-09")))

        val first = quarterResults.firstPublishedBetween(asbisId, LocalDate.parse("2026-08-06"), LocalDate.parse("2026-08-08"))

        assertEquals(LocalDate.parse("2026-06-30"), first?.quarterEnd)
        assertEquals(null, quarterResults.firstPublishedBetween(asbisId, LocalDate.parse("2026-08-10"), LocalDate.parse("2026-08-12")))
    }

    @Test
    fun `quarter results are returned newest first and limited`() {
        quarterResults.save(quarterResult(LocalDate.parse("2025-12-31"), LocalDate.parse("2026-03-05")))
        quarterResults.save(quarterResult(LocalDate.parse("2026-03-31"), LocalDate.parse("2026-05-07")))

        val rows = quarterResults.publishedBefore(asbisId, LocalDate.parse("2026-09-01"), 1)

        assertEquals(listOf(LocalDate.parse("2026-03-31")), rows.map { it.quarterEnd })
    }

    @Test
    fun `revenue periods published after as-of are invisible`() {
        revenuePeriods.save(revenuePeriod(LocalDate.parse("2026-07-31"), Instant.parse("2026-08-20T06:00:00Z")))
        revenuePeriods.save(revenuePeriod(LocalDate.parse("2026-08-31"), Instant.parse("2026-09-21T06:09:00Z")))

        val rows = revenuePeriods.publishedBefore(
            asbisId, PeriodKind.MONTH, Instant.parse("2026-09-21T06:08:00Z"), 12
        )

        assertEquals(listOf(LocalDate.parse("2026-07-31")), rows.map { it.periodEnd })
    }

    @Test
    fun `re-saving a revenue period keeps the earliest published at and updates values`() {
        val original = Instant.parse("2026-09-21T06:09:00Z")
        val period = revenuePeriod(LocalDate.parse("2026-08-31"), original)
            .copy(revenuePrevYear = BigDecimal("90000"), reportId = null)
        revenuePeriods.save(period)
        revenuePeriods.save(
            period.copy(revenue = BigDecimal("120000"), publishedAt = Instant.parse("2026-10-01T10:00:00Z"))
        )

        val row = revenuePeriods.publishedBefore(asbisId, PeriodKind.MONTH, Instant.parse("2026-12-01T00:00:00Z"), 1)
            .single()

        assertEquals(original, row.publishedAt)
        assertEquals(0, BigDecimal("120000").compareTo(row.revenue))
        assertEquals(0, BigDecimal("90000").compareTo(row.revenuePrevYear))
        assertEquals("USD", row.currency)
    }

    @Test
    fun `re-saving a quarter result keeps the earliest published on and updates values`() {
        val quarterEnd = LocalDate.parse("2026-03-31")
        quarterResults.save(quarterResult(quarterEnd, LocalDate.parse("2026-05-07")))
        quarterResults.save(
            quarterResult(quarterEnd, LocalDate.parse("2026-06-01")).copy(revenue = BigDecimal("2000"))
        )

        val row = quarterResults.publishedBefore(asbisId, LocalDate.parse("2026-12-01"), 1).single()

        assertEquals(LocalDate.parse("2026-05-07"), row.publishedOn)
        assertEquals(0, BigDecimal("2000").compareTo(row.revenue))
    }

    @Test
    fun `re-saving a quarter result fills a null published on`() {
        val quarterEnd = LocalDate.parse("2026-03-31")
        jdbc.sql("INSERT INTO quarter_result (company_id, quarter_end, published_on) VALUES (:id, :q, NULL)")
            .param("id", asbisId).param("q", quarterEnd).update()
        quarterResults.save(quarterResult(quarterEnd, LocalDate.parse("2026-05-07")))

        val row = quarterResults.publishedBefore(asbisId, LocalDate.parse("2026-12-01"), 1).single()

        assertEquals(LocalDate.parse("2026-05-07"), row.publishedOn)
    }

    @Test
    fun `re-saving a quarter result with null published on keeps the existing date`() {
        val quarterEnd = LocalDate.parse("2026-03-31")
        quarterResults.save(quarterResult(quarterEnd, LocalDate.parse("2026-05-07")))
        quarterResults.save(quarterResult(quarterEnd, LocalDate.parse("2026-05-07")).copy(publishedOn = null))

        val row = quarterResults.publishedBefore(asbisId, LocalDate.parse("2026-12-01"), 1).single()

        assertEquals(LocalDate.parse("2026-05-07"), row.publishedOn)
    }

    @Test
    fun `revenue period published exactly at as-of is not returned`() {
        val asOf = Instant.parse("2026-09-21T06:09:00Z")
        revenuePeriods.save(revenuePeriod(LocalDate.parse("2026-08-31"), asOf))

        val rows = revenuePeriods.publishedBefore(asbisId, PeriodKind.MONTH, asOf, 12)

        assertEquals(emptyList(), rows)
    }

    @Test
    fun `revenue periods are filtered by kind and sorted newest first`() {
        revenuePeriods.save(revenuePeriod(LocalDate.parse("2026-06-30"), Instant.parse("2026-07-20T06:00:00Z")))
        revenuePeriods.save(revenuePeriod(LocalDate.parse("2026-07-31"), Instant.parse("2026-08-20T06:00:00Z")))
        revenuePeriods.save(
            revenuePeriod(LocalDate.parse("2026-06-30"), Instant.parse("2026-08-06T06:00:00Z"), PeriodKind.HALF_YEAR)
        )

        val rows = revenuePeriods.publishedBefore(
            asbisId, PeriodKind.MONTH, Instant.parse("2026-12-01T00:00:00Z"), 12
        )

        assertEquals(listOf(LocalDate.parse("2026-07-31"), LocalDate.parse("2026-06-30")), rows.map { it.periodEnd })
    }

    @Test
    fun `valuations are returned only for the requested quarters`() {
        listOf("2026-03-31", "2026-06-30", "2025-12-31").forEach {
            valuations.save(ValuationPoint(asbisId, LocalDate.parse(it), BigDecimal("50.00"), 5000000, null, null))
        }

        val rows = valuations.forQuarters(
            asbisId, listOf(LocalDate.parse("2026-03-31"), LocalDate.parse("2025-12-31"))
        )

        assertEquals(listOf(LocalDate.parse("2026-03-31"), LocalDate.parse("2025-12-31")), rows.map { it.quarterEnd })
    }

    @Test
    fun `closes up to returns the last N sessions ending on the date, newest first`() {
        listOf("2026-04-08", "2026-04-09", "2026-04-10").forEach {
            quotes.save(DailyQuote("WIG", LocalDate.parse(it), BigDecimal("100.5"), 1000, null))
        }

        val rows = quotes.closesUpTo("WIG", LocalDate.parse("2026-04-10"), 2)

        assertEquals(listOf(LocalDate.parse("2026-04-10"), LocalDate.parse("2026-04-09")), rows.map { it.date })
    }

    @Test
    fun `closes up to ignores sessions after the date`() {
        listOf("2026-04-08", "2026-04-09", "2026-04-10").forEach {
            quotes.save(DailyQuote("WIG", LocalDate.parse(it), BigDecimal("100.5"), 1000, null))
        }

        val rows = quotes.closesUpTo("WIG", LocalDate.parse("2026-04-09"), 5)

        assertEquals(listOf(LocalDate.parse("2026-04-09"), LocalDate.parse("2026-04-08")), rows.map { it.date })
    }

    private fun quarterResult(quarterEnd: LocalDate, publishedOn: LocalDate) = QuarterResult(
        asbisId, quarterEnd, BigDecimal("1000"), BigDecimal("50"), BigDecimal("30"), publishedOn
    )

    private fun revenuePeriod(
        periodEnd: LocalDate,
        publishedAt: Instant,
        kind: PeriodKind = PeriodKind.MONTH,
    ) = RevenuePeriod(asbisId, kind, periodEnd, "USD", BigDecimal("100000"), null, publishedAt, null)
}
