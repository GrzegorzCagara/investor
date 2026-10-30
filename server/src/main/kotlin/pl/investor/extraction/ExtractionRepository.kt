package pl.investor.extraction

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue

@Repository
class ExtractionRepository(private val jdbc: JdbcClient) {

    private val mapper = jacksonObjectMapper()

    fun save(reportId: Long, verified: VerifiedExtraction) {
        jdbc.sql(
            """
            INSERT INTO extraction (report_id, payload, uncertain_fields)
            VALUES (:reportId, CAST(:payload AS jsonb), string_to_array(:uncertainFields, ','))
            ON CONFLICT (report_id) DO UPDATE SET
                payload = EXCLUDED.payload,
                uncertain_fields = EXCLUDED.uncertain_fields
            """
        )
            .param("reportId", reportId)
            .param("payload", mapper.writeValueAsString(verified.extraction))
            .param("uncertainFields", verified.uncertainFields.joinToString(","))
            .update()
    }

    fun find(reportId: Long): VerifiedExtraction? =
        jdbc.sql("SELECT payload::text AS payload, array_to_string(uncertain_fields, ',') AS uncertain FROM extraction WHERE report_id = :reportId")
            .param("reportId", reportId)
            .query { rs, _ ->
                VerifiedExtraction(
                    mapper.readValue<Extraction>(rs.getString("payload")),
                    rs.getString("uncertain").split(",").filter { it.isNotEmpty() }.toSet(),
                )
            }
            .optional()
            .orElse(null)

    fun uncertainFields(reportId: Long): List<String> =
        jdbc.sql("SELECT unnest(uncertain_fields) FROM extraction WHERE report_id = :reportId")
            .param("reportId", reportId)
            .query(String::class.java)
            .list()
            .filterNotNull()
}
