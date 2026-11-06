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

    fun save(reportId: Long, note: ConsensusNote, savedAt: Instant) {
        jdbc.sql(
            """
            INSERT INTO consensus_note (report_id, url, published_at, rows, saved_at)
            VALUES (:reportId, :url, :publishedAt, CAST(:rows AS jsonb), :savedAt)
            ON CONFLICT (report_id) DO UPDATE SET
                url = EXCLUDED.url,
                published_at = EXCLUDED.published_at,
                rows = EXCLUDED.rows,
                saved_at = EXCLUDED.saved_at
            """
        )
            .param("reportId", reportId)
            .param("savedAt", savedAt.toUtc())
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

    fun exists(reportId: Long): Boolean =
        jdbc.sql("SELECT count(*) FROM consensus_note WHERE report_id = :reportId")
            .param("reportId", reportId)
            .query(Int::class.java)
            .single() > 0

    fun savedAfterScoring(since: Instant): List<Long> =
        jdbc.sql(
            """
            SELECT r.id
            FROM report r
            JOIN score s ON s.report_id = r.id
            JOIN consensus_note n ON n.report_id = r.id
            WHERE r.market = 'GPW'
              AND r.event_type = 'RESULTS'
              AND r.status IN ('SCORED', 'PUSH_PENDING')
              AND r.published_at >= :since
              AND n.saved_at > s.created_at
            ORDER BY r.published_at, r.id
            """
        )
            .param("since", since.toUtc())
            .query { rs, _ -> rs.getLong("id") }
            .list()

    fun pending(since: Instant): List<PendingConsensus> =
        jdbc.sql(
            """
            SELECT r.id, r.company_id, r.published_at
            FROM report r
            JOIN score s ON s.report_id = r.id
            LEFT JOIN consensus_note n ON n.report_id = r.id
            WHERE r.market = 'GPW'
              AND r.event_type = 'RESULTS'
              AND r.status IN ('SCORED', 'PUSH_PENDING', 'AWAITING_CONSENSUS')
              AND r.published_at >= :since
              AND n.report_id IS NULL
              AND NOT EXISTS (
                  SELECT 1
                  FROM extraction e, jsonb_array_elements(e.payload -> 'periods') p
                  WHERE e.report_id = r.id AND p ->> 'kind' = 'MONTH' AND jsonb_typeof(p -> 'revenue') = 'object'
              )
            ORDER BY r.published_at, r.id
            """
        )
            .param("since", since.toUtc())
            .query { rs, _ -> PendingConsensus(rs.getLong("id"), rs.getLong("company_id"), rs.toInstant("published_at")) }
            .list()
}
