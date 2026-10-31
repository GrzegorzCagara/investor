package pl.investor.source.consensus

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import pl.investor.support.toInstant
import pl.investor.support.toUtc
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.time.Instant

data class PendingConsensus(val reportId: Long, val companyId: Long, val publishedAt: Instant)

@Repository
class ConsensusRepository(private val jdbc: JdbcClient) {

    private val mapper = jacksonObjectMapper()

    fun save(reportId: Long, note: ConsensusNote) {
        jdbc.sql(
            """
            INSERT INTO consensus_note (report_id, url, published_at, rows)
            VALUES (:reportId, :url, :publishedAt, CAST(:rows AS jsonb))
            ON CONFLICT (report_id) DO UPDATE SET
                url = EXCLUDED.url,
                published_at = EXCLUDED.published_at,
                rows = EXCLUDED.rows
            """
        )
            .param("reportId", reportId)
            .param("url", note.url)
            .param("publishedAt", note.publishedAt.toUtc())
            .param("rows", mapper.writeValueAsString(note.rows))
            .update()
    }

    fun find(reportId: Long): ConsensusNote? =
        jdbc.sql("SELECT url, published_at, rows::text AS rows FROM consensus_note WHERE report_id = :reportId")
            .param("reportId", reportId)
            .query { rs, _ ->
                ConsensusNote(
                    url = rs.getString("url"),
                    publishedAt = rs.toInstant("published_at"),
                    rows = mapper.readValue<List<ConsensusRow>>(rs.getString("rows")),
                )
            }
            .optional()
            .orElse(null)

    fun pending(since: Instant): List<PendingConsensus> =
        jdbc.sql(
            """
            SELECT r.id, r.company_id, r.published_at
            FROM report r
            JOIN score s ON s.report_id = r.id
            LEFT JOIN consensus_note n ON n.report_id = r.id
            WHERE r.market = 'GPW'
              AND r.status IN ('SCORED', 'PUSH_PENDING')
              AND r.published_at >= :since
              AND n.report_id IS NULL
              AND (r.kind <> 'CURRENT' OR lower(coalesce(r.title, '')) LIKE '%wynik%')
            ORDER BY r.published_at, r.id
            """
        )
            .param("since", since.toUtc())
            .query { rs, _ -> PendingConsensus(rs.getLong("id"), rs.getLong("company_id"), rs.toInstant("published_at")) }
            .list()
}
