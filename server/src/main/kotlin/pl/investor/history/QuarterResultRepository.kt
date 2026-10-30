package pl.investor.history

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import pl.investor.support.toLocalDate
import java.sql.ResultSet
import java.time.LocalDate

@Repository
class QuarterResultRepository(private val jdbc: JdbcClient) {

    fun save(result: QuarterResult) {
        jdbc.sql(
            """
            INSERT INTO quarter_result (company_id, quarter_end, revenue, operating_profit, net_profit, published_on)
            VALUES (:companyId, :quarterEnd, :revenue, :operatingProfit, :netProfit, :publishedOn)
            ON CONFLICT (company_id, quarter_end) DO UPDATE SET
                revenue = EXCLUDED.revenue,
                operating_profit = EXCLUDED.operating_profit,
                net_profit = EXCLUDED.net_profit,
                published_on = COALESCE(
                    LEAST(quarter_result.published_on, EXCLUDED.published_on),
                    EXCLUDED.published_on,
                    quarter_result.published_on
                )
            """
        )
            .param("companyId", result.companyId)
            .param("quarterEnd", result.quarterEnd)
            .param("revenue", result.revenue)
            .param("operatingProfit", result.operatingProfit)
            .param("netProfit", result.netProfit)
            .param("publishedOn", result.publishedOn)
            .update()
    }

    fun publishedOn(companyId: Long, date: LocalDate): QuarterResult? = firstPublishedBetween(companyId, date, date)

    fun firstPublishedBetween(companyId: Long, from: LocalDate, to: LocalDate): QuarterResult? =
        jdbc.sql(
            """
            SELECT * FROM quarter_result
            WHERE company_id = :companyId AND published_on BETWEEN :from AND :to
            ORDER BY published_on, quarter_end DESC
            LIMIT 1
            """
        )
            .param("companyId", companyId)
            .param("from", from)
            .param("to", to)
            .query { rs, _ -> rs.toQuarterResult() }
            .optional()
            .orElse(null)

    fun publishedBefore(companyId: Long, asOfDate: LocalDate, limit: Int): List<QuarterResult> =
        jdbc.sql(
            """
            SELECT * FROM quarter_result
            WHERE company_id = :companyId AND published_on < :asOfDate
            ORDER BY quarter_end DESC
            LIMIT :limit
            """
        )
            .param("companyId", companyId)
            .param("asOfDate", asOfDate)
            .param("limit", limit)
            .query { rs, _ -> rs.toQuarterResult() }
            .list()

    private fun ResultSet.toQuarterResult() = QuarterResult(
        companyId = getLong("company_id"),
        quarterEnd = toLocalDate("quarter_end")!!,
        revenue = getBigDecimal("revenue"),
        operatingProfit = getBigDecimal("operating_profit"),
        netProfit = getBigDecimal("net_profit"),
        publishedOn = toLocalDate("published_on"),
    )
}
