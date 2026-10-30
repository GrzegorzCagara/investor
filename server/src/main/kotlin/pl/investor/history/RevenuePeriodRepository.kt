package pl.investor.history

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import pl.investor.support.getLongOrNull
import pl.investor.support.toInstant
import pl.investor.support.toLocalDate
import pl.investor.support.toUtc
import java.time.Instant

@Repository
class RevenuePeriodRepository(private val jdbc: JdbcClient) {

    fun save(period: RevenuePeriod) {
        jdbc.sql(
            """
            INSERT INTO revenue_period
                (company_id, kind, period_end, currency, revenue, revenue_prev_year, published_at, report_id)
            VALUES (:companyId, :kind, :periodEnd, :currency, :revenue, :revenuePrevYear, :publishedAt, :reportId)
            ON CONFLICT (company_id, kind, period_end) DO UPDATE SET
                currency = EXCLUDED.currency,
                revenue = EXCLUDED.revenue,
                revenue_prev_year = EXCLUDED.revenue_prev_year,
                published_at = LEAST(revenue_period.published_at, EXCLUDED.published_at),
                report_id = EXCLUDED.report_id
            """
        )
            .param("companyId", period.companyId)
            .param("kind", period.kind.name)
            .param("periodEnd", period.periodEnd)
            .param("currency", period.currency)
            .param("revenue", period.revenue)
            .param("revenuePrevYear", period.revenuePrevYear)
            .param("publishedAt", period.publishedAt.toUtc())
            .param("reportId", period.reportId)
            .update()
    }

    fun publishedBefore(companyId: Long, kind: PeriodKind, asOf: Instant, limit: Int): List<RevenuePeriod> =
        jdbc.sql(
            """
            SELECT * FROM revenue_period
            WHERE company_id = :companyId AND kind = :kind AND published_at < :asOf
            ORDER BY period_end DESC
            LIMIT :limit
            """
        )
            .param("companyId", companyId)
            .param("kind", kind.name)
            .param("asOf", asOf.toUtc())
            .param("limit", limit)
            .query { rs, _ ->
                RevenuePeriod(
                    companyId = rs.getLong("company_id"),
                    kind = PeriodKind.valueOf(rs.getString("kind")),
                    periodEnd = rs.toLocalDate("period_end")!!,
                    currency = rs.getString("currency"),
                    revenue = rs.getBigDecimal("revenue"),
                    revenuePrevYear = rs.getBigDecimal("revenue_prev_year"),
                    publishedAt = rs.toInstant("published_at"),
                    reportId = rs.getLongOrNull("report_id"),
                )
            }
            .list()
}
