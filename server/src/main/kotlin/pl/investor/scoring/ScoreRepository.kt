package pl.investor.scoring

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import pl.investor.support.toUtc
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.time.Instant

@Repository
class ScoreRepository(private val jdbc: JdbcClient) {

    private val mapper = jacksonObjectMapper()

    fun save(reportId: Long, score: Score, reason: String, createdAt: Instant) {
        jdbc.sql(
            """
            INSERT INTO score (report_id, total, components, penalty, warnings, reason, created_at)
            VALUES (:reportId, :total, CAST(:components AS jsonb), :penalty, string_to_array(:warnings, ','), :reason, :createdAt)
            ON CONFLICT (report_id) DO UPDATE SET
                total = EXCLUDED.total,
                components = EXCLUDED.components,
                penalty = EXCLUDED.penalty,
                warnings = EXCLUDED.warnings,
                reason = EXCLUDED.reason,
                created_at = EXCLUDED.created_at
            """
        )
            .param("reportId", reportId)
            .param("total", score.total)
            .param("components", mapper.writeValueAsString(score.components))
            .param("penalty", score.penalty)
            .param("warnings", score.warnings.joinToString(",") { it.name })
            .param("reason", reason)
            .param("createdAt", createdAt.toUtc())
            .update()
    }

    fun find(reportId: Long): StoredScore? =
        jdbc.sql(
            """
            SELECT total, components::text AS components, penalty, array_to_string(warnings, ',') AS warnings, reason
            FROM score WHERE report_id = :reportId
            """
        )
            .param("reportId", reportId)
            .query { rs, _ ->
                StoredScore(
                    Score(
                        total = rs.getDouble("total"),
                        components = mapper.readValue<ScoreComponents>(rs.getString("components")),
                        penalty = rs.getDouble("penalty"),
                        warnings = rs.getString("warnings").split(",").filter { it.isNotEmpty() }.map(Warning::valueOf).toSet(),
                    ),
                    rs.getString("reason"),
                )
            }
            .optional()
            .orElse(null)

    fun totalOf(reportId: Long): Double =
        jdbc.sql("SELECT total FROM score WHERE report_id = :reportId")
            .param("reportId", reportId)
            .query(Double::class.java)
            .single()
}

data class StoredScore(val score: Score, val reason: String)
