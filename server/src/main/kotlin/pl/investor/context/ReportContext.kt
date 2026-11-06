package pl.investor.context

import pl.investor.extraction.ExtractedPeriod
import pl.investor.news.EarlierReport
import pl.investor.scoring.PastReaction
import pl.investor.scoring.ScoreComponents
import pl.investor.scoring.Warning
import pl.investor.source.consensus.BankierHeadline
import pl.investor.source.consensus.ConsensusRow
import java.time.Instant

enum class ContextDirection { PLUS, MINUS }

data class ContextPoint(val direction: ContextDirection, val text: String)

data class ModelContextPoints(val points: List<ContextPoint>)

data class ReportContext(val points: List<ContextPoint>, val headlines: List<BankierHeadline>, val createdAt: Instant)

enum class ContextOutcome { WRITTEN, NOTHING_TO_ADD, NOT_FOUND, NOT_ELIGIBLE, SOURCE_UNAVAILABLE, FAILED }

data class ContextInput(
    val companyName: String,
    val reportTitle: String?,
    val publishedAt: Instant,
    val period: ExtractedPeriod?,
    val consensus: List<ConsensusRow>,
    val components: ScoreComponents,
    val warnings: Set<Warning>,
    val ytdChangePct: Double?,
    val pastReactions: List<PastReaction>,
    val headlines: List<BankierHeadline>,
    val earlierReports: List<EarlierReport>,
)

class ContextWritingFailedException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
