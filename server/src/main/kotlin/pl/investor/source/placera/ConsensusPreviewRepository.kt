package pl.investor.source.placera

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import pl.investor.scoring.PastReaction
import pl.investor.support.toInstant
import pl.investor.support.toLocalDate
import pl.investor.support.toUtc
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate

@Repository
class ConsensusPreviewRepository(private val jdbc: JdbcClient) {

    private val mapper = jacksonObjectMapper()

    fun save(preview: ConsensusPreview) {
        jdbc.sql(
            """
            INSERT INTO consensus_preview (url, isin, company_name, report_date, currency,
                revenue_consensus, revenue_prev_year, revenue_analysts, ebit_consensus, ebit_prev_year, ebit_analysts,
                source, past_reactions, published_at, fetched_at)
            VALUES (:url, :isin, :companyName, :reportDate, :currency,
                :revenueConsensus, :revenuePrevYear, :revenueAnalysts, :ebitConsensus, :ebitPrevYear, :ebitAnalysts,
                :source, CAST(:pastReactions AS jsonb), :publishedAt, :fetchedAt)
            ON CONFLICT (url) DO NOTHING
            """
        )
            .param("url", preview.url)
            .param("isin", preview.isin)
            .param("companyName", preview.companyName)
            .param("reportDate", preview.reportDate)
            .param("currency", preview.currency)
            .param("revenueConsensus", preview.revenue?.consensus)
            .param("revenuePrevYear", preview.revenue?.prevYear)
            .param("revenueAnalysts", preview.revenue?.analysts)
            .param("ebitConsensus", preview.ebit?.consensus)
            .param("ebitPrevYear", preview.ebit?.prevYear)
            .param("ebitAnalysts", preview.ebit?.analysts)
            .param("source", preview.source)
            .param("pastReactions", mapper.writeValueAsString(preview.pastReactions))
            .param("publishedAt", preview.publishedAt.toUtc())
            .param("fetchedAt", preview.fetchedAt.toUtc())
            .update()
    }

    fun exists(url: String): Boolean =
        jdbc.sql("SELECT EXISTS (SELECT 1 FROM consensus_preview WHERE url = :url)")
            .param("url", url)
            .query(Boolean::class.java)
            .single()

    fun forReport(isin: String, reportDate: LocalDate, publishedBefore: Instant): ConsensusPreview? =
        jdbc.sql(
            """
            SELECT * FROM consensus_preview
            WHERE isin = :isin AND report_date BETWEEN :from AND :to AND published_at < :before
            ORDER BY abs(report_date - CAST(:reportDate AS DATE)), published_at DESC
            LIMIT 1
            """
        )
            .param("isin", isin)
            .param("from", reportDate.minusDays(REPORT_DATE_TOLERANCE_DAYS))
            .param("to", reportDate.plusDays(REPORT_DATE_TOLERANCE_DAYS))
            .param("reportDate", reportDate)
            .param("before", publishedBefore.toUtc())
            .query { rs, _ -> rs.toPreview() }
            .optional()
            .orElse(null)

    fun latestBefore(isin: String, before: Instant): ConsensusPreview? =
        jdbc.sql("SELECT * FROM consensus_preview WHERE isin = :isin AND published_at < :before ORDER BY published_at DESC LIMIT 1")
            .param("isin", isin)
            .param("before", before.toUtc())
            .query { rs, _ -> rs.toPreview() }
            .optional()
            .orElse(null)

    private fun ResultSet.toPreview() = ConsensusPreview(
        url = getString("url"),
        isin = getString("isin"),
        companyName = getString("company_name"),
        reportDate = checkNotNull(toLocalDate("report_date")),
        currency = getString("currency"),
        revenue = estimate("revenue"),
        ebit = estimate("ebit"),
        source = getString("source"),
        pastReactions = mapper.readValue<List<PastReaction>>(getString("past_reactions")),
        publishedAt = toInstant("published_at"),
        fetchedAt = toInstant("fetched_at"),
    )

    private fun ResultSet.estimate(prefix: String): ConsensusEstimate? =
        getBigDecimal("${prefix}_consensus")?.let { consensus ->
            ConsensusEstimate(consensus, getBigDecimal("${prefix}_prev_year"), getInt("${prefix}_analysts").takeUnless { wasNull() })
        }

    private companion object {
        const val REPORT_DATE_TOLERANCE_DAYS = 1L
    }
}
