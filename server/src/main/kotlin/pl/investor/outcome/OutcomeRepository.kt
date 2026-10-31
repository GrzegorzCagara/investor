package pl.investor.outcome

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import pl.investor.report.Market
import pl.investor.support.toInstant
import pl.investor.support.toLocalDate
import pl.investor.support.toUtc
import java.sql.ResultSet
import java.time.Instant

data class PendingOutcome(val reportId: Long, val isin: String, val market: Market, val publishedAt: Instant)

@Repository
class OutcomeRepository(private val jdbc: JdbcClient) {

    fun save(reportId: Long, outcome: Outcome, at: Instant) {
        jdbc.sql(
            """
            INSERT INTO report_outcome (report_id, base_date, reaction_pct, reaction_index_pct, volume_multiple,
                return5_pct, index5_pct, return20_pct, index20_pct, return60_pct, index60_pct, updated_at)
            VALUES (:reportId, :baseDate, :reactionPct, :reactionIndexPct, :volumeMultiple,
                :return5Pct, :index5Pct, :return20Pct, :index20Pct, :return60Pct, :index60Pct, :updatedAt)
            ON CONFLICT (report_id) DO UPDATE SET
                base_date = EXCLUDED.base_date,
                reaction_pct = EXCLUDED.reaction_pct,
                reaction_index_pct = EXCLUDED.reaction_index_pct,
                volume_multiple = EXCLUDED.volume_multiple,
                return5_pct = EXCLUDED.return5_pct,
                index5_pct = EXCLUDED.index5_pct,
                return20_pct = EXCLUDED.return20_pct,
                index20_pct = EXCLUDED.index20_pct,
                return60_pct = EXCLUDED.return60_pct,
                index60_pct = EXCLUDED.index60_pct,
                updated_at = EXCLUDED.updated_at
            """
        )
            .param("reportId", reportId)
            .param("baseDate", outcome.baseDate)
            .param("reactionPct", outcome.reactionPct)
            .param("reactionIndexPct", outcome.reactionIndexPct)
            .param("volumeMultiple", outcome.volumeMultiple)
            .param("return5Pct", outcome.return5Pct)
            .param("index5Pct", outcome.index5Pct)
            .param("return20Pct", outcome.return20Pct)
            .param("index20Pct", outcome.index20Pct)
            .param("return60Pct", outcome.return60Pct)
            .param("index60Pct", outcome.index60Pct)
            .param("updatedAt", at.toUtc())
            .update()
    }

    fun find(reportId: Long): Outcome? =
        jdbc.sql("SELECT * FROM report_outcome WHERE report_id = :reportId")
            .param("reportId", reportId)
            .query { rs, _ -> rs.toOutcome() }
            .optional()
            .orElse(null)

    fun findAll(reportIds: Collection<Long>): Map<Long, Outcome> =
        if (reportIds.isEmpty()) emptyMap()
        else jdbc.sql("SELECT * FROM report_outcome WHERE report_id IN (:reportIds)")
            .param("reportIds", reportIds)
            .query { rs, _ -> rs.getLong("report_id") to rs.toOutcome() }
            .list()
            .toMap()

    fun pending(since: Instant, includeComplete: Boolean = false): List<PendingOutcome> =
        jdbc.sql(
            """
            SELECT r.id, c.isin, r.market, r.published_at
            FROM report r
            JOIN score s ON s.report_id = r.id
            JOIN company c ON c.id = r.company_id
            LEFT JOIN report_outcome o ON o.report_id = r.id
            WHERE r.status IN ('SCORED', 'PUSH_PENDING')
              AND c.isin IS NOT NULL
              AND r.published_at >= :since
              AND (:includeComplete OR o.return60_pct IS NULL OR o.index60_pct IS NULL)
            ORDER BY r.published_at, r.id
            """
        )
            .param("since", since.toUtc())
            .param("includeComplete", includeComplete)
            .query { rs, _ ->
                PendingOutcome(
                    reportId = rs.getLong("id"),
                    isin = rs.getString("isin"),
                    market = Market.valueOf(rs.getString("market")),
                    publishedAt = rs.toInstant("published_at"),
                )
            }
            .list()

    private fun ResultSet.toOutcome() = Outcome(
        baseDate = checkNotNull(toLocalDate("base_date")),
        reactionPct = doubleOrNull("reaction_pct"),
        reactionIndexPct = doubleOrNull("reaction_index_pct"),
        volumeMultiple = doubleOrNull("volume_multiple"),
        return5Pct = doubleOrNull("return5_pct"),
        index5Pct = doubleOrNull("index5_pct"),
        return20Pct = doubleOrNull("return20_pct"),
        index20Pct = doubleOrNull("index20_pct"),
        return60Pct = doubleOrNull("return60_pct"),
        index60Pct = doubleOrNull("index60_pct"),
    )

    private fun ResultSet.doubleOrNull(column: String): Double? = getDouble(column).takeUnless { wasNull() }
}
