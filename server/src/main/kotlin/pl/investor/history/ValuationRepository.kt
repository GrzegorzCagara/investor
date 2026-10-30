package pl.investor.history

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import pl.investor.support.getLongOrNull
import pl.investor.support.toLocalDate
import java.time.LocalDate

@Repository
class ValuationRepository(private val jdbc: JdbcClient) {

    fun save(point: ValuationPoint) {
        jdbc.sql(
            """
            INSERT INTO valuation_point (company_id, quarter_end, price, shares, pe, ps)
            VALUES (:companyId, :quarterEnd, :price, :shares, :pe, :ps)
            ON CONFLICT (company_id, quarter_end) DO UPDATE SET
                price = EXCLUDED.price,
                shares = EXCLUDED.shares,
                pe = EXCLUDED.pe,
                ps = EXCLUDED.ps
            """
        )
            .param("companyId", point.companyId)
            .param("quarterEnd", point.quarterEnd)
            .param("price", point.price)
            .param("shares", point.shares)
            .param("pe", point.pe)
            .param("ps", point.ps)
            .update()
    }

    fun forQuarters(companyId: Long, quarterEnds: Collection<LocalDate>): List<ValuationPoint> =
        if (quarterEnds.isEmpty()) {
            emptyList()
        } else {
            jdbc.sql(
                """
                SELECT * FROM valuation_point
                WHERE company_id = :companyId AND quarter_end IN (:quarterEnds)
                ORDER BY quarter_end DESC
                """
            )
                .param("companyId", companyId)
                .param("quarterEnds", quarterEnds)
                .query { rs, _ ->
                    ValuationPoint(
                        companyId = rs.getLong("company_id"),
                        quarterEnd = rs.toLocalDate("quarter_end")!!,
                        price = rs.getBigDecimal("price"),
                        shares = rs.getLongOrNull("shares"),
                        pe = rs.getBigDecimal("pe"),
                        ps = rs.getBigDecimal("ps"),
                    )
                }
                .list()
        }
}
