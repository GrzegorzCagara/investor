package pl.investor.context

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import pl.investor.source.consensus.BankierHeadline
import pl.investor.support.toInstant
import pl.investor.support.toUtc
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.time.Instant

@Repository
class ReportContextRepository(private val jdbc: JdbcClient) {

    private val mapper = jacksonObjectMapper()

    fun save(reportId: Long, context: ReportContext) {
        jdbc.sql(
            """
            INSERT INTO report_context (report_id, points, headlines, created_at)
            VALUES (:reportId, CAST(:points AS jsonb), CAST(:headlines AS jsonb), :createdAt)
            ON CONFLICT (report_id) DO UPDATE SET
                points = EXCLUDED.points,
                headlines = EXCLUDED.headlines,
                created_at = EXCLUDED.created_at
            """
        )
            .param("reportId", reportId)
            .param("points", mapper.writeValueAsString(context.points))
            .param("headlines", mapper.writeValueAsString(context.headlines))
            .param("createdAt", context.createdAt.toUtc())
            .update()
    }

    fun find(reportId: Long): ReportContext? =
        jdbc.sql("SELECT points::text AS points, headlines::text AS headlines, created_at FROM report_context WHERE report_id = :reportId")
            .param("reportId", reportId)
            .query { rs, _ ->
                ReportContext(
                    points = mapper.readValue<List<ContextPoint>>(rs.getString("points")),
                    headlines = mapper.readValue<List<BankierHeadline>>(rs.getString("headlines")),
                    createdAt = rs.toInstant("created_at"),
                )
            }
            .optional()
            .orElse(null)

    fun exists(reportId: Long): Boolean =
        jdbc.sql("SELECT count(*) FROM report_context WHERE report_id = :reportId")
            .param("reportId", reportId)
            .query(Int::class.java)
            .single() > 0

    fun due(changedSince: Instant, minScore: Double, limit: Int): List<Long> =
        jdbc.sql(
            """
            SELECT r.id
            FROM report r
            JOIN score s ON s.report_id = r.id
            LEFT JOIN report_context c ON c.report_id = r.id
            WHERE r.market IN ('GPW', 'NC')
              AND r.event_type = 'RESULTS'
              AND r.status IN ('SCORED', 'PUSH_PENDING')
              AND r.status_changed_at >= :changedSince
              AND s.total >= :minScore
              AND c.report_id IS NULL
            ORDER BY r.status_changed_at, r.id
            LIMIT :limit
            """
        )
            .param("changedSince", changedSince.toUtc())
            .param("minScore", minScore)
            .param("limit", limit)
            .query { rs, _ -> rs.getLong("id") }
            .list()
}
