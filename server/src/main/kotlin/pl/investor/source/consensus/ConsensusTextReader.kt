package pl.investor.source.consensus

import com.anthropic.client.AnthropicClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicException
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import org.springframework.stereotype.Component
import pl.investor.extraction.AiBudget
import pl.investor.extraction.AiBudgetExhaustedException
import pl.investor.extraction.Amount
import pl.investor.extraction.QuoteVerifier
import pl.investor.extraction.resultsPool
import pl.investor.extraction.toAiFailure
import pl.investor.report.Market
import pl.investor.support.foldedTitle
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

enum class ConsensusLabel(val text: String) {
    REVENUE("Przychody"),
    ADJUSTED_EBITDA("EBITDA skorygowana"),
    EBITDA("EBITDA"),
    EBIT("EBIT"),
    NET_PROFIT("Zysk netto"),
}

open class ConsensusReadingFailedException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class MalformedConsensusReadingException(message: String, cause: Throwable) : ConsensusReadingFailedException(message, cause)

data class ModelConsensusRow(val label: ConsensusLabel, val actual: Amount, val consensus: Amount)

data class ModelConsensusRows(val aboutCompany: Boolean, val rows: List<ModelConsensusRow>)

@Component
class ConsensusTextReader(
    private val budget: AiBudget,
    private val client: AnthropicClient,
    private val verifier: QuoteVerifier,
) {

    private val mapper = jacksonObjectMapper()

    fun read(companyName: String, body: String): List<ConsensusRow> {
        val pool = MARKET.resultsPool()
        if (!budget.canSpend(pool)) throw AiBudgetExhaustedException(pool)
        val message = try {
            client.messages().create(request(companyName, body))
        } catch (e: AnthropicException) {
            throw e.toAiFailure { ConsensusReadingFailedException("Anthropic API call failed", it) }
        }
        budget.record(pool, message.usage().inputTokens(), message.usage().outputTokens())
        val reading = message.toModelRows()
        if (!reading.aboutCompany) return emptyList()
        return reading.rows
            .filter { it.actual.isTrustedIn(body) && it.consensus.isTrustedIn(body) && it.consensus.valueInThousands.signum() != 0 }
            .distinctBy { it.label }
            .map { it.toRow() }
    }

    private fun Amount.isTrustedIn(body: String) = verifier.isSupported(this, body, MARKET) && !isUnsignedLoss(body)

    private fun Amount.isUnsignedLoss(body: String): Boolean {
        if (valueInThousands.signum() < 0) return false
        val quoted = sourceText.foldedTitle()
        val text = body.foldedTitle()
        return LOSS in quoted || text.occurrencesOf(quoted).any { start -> LOSS in text.lookBehind(start) }
    }

    private fun String.occurrencesOf(part: String): Sequence<Int> =
        generateSequence(indexOf(part).takeIf { it >= 0 }) { previous -> indexOf(part, previous + 1).takeIf { it >= 0 } }

    private fun String.lookBehind(start: Int): String = substring(maxOf(0, start - LOSS_LOOKBEHIND_CHARS), start)

    private fun ModelConsensusRow.toRow(): ConsensusRow {
        val actualMillions = actual.valueInThousands.inMillions()
        val consensusMillions = consensus.valueInThousands.inMillions()
        return ConsensusRow(
            label = label.text,
            actual = actualMillions,
            consensus = consensusMillions,
            differencePct = (actualMillions - consensusMillions)
                .multiply(HUNDRED)
                .divide(consensusMillions.abs(), MathContext.DECIMAL64)
                .setScale(1, RoundingMode.HALF_UP)
                .toDouble(),
        )
    }

    private fun BigDecimal.inMillions(): BigDecimal = movePointLeft(3).stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }

    private fun request(companyName: String, body: String): MessageCreateParams =
        MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(MAX_TOKENS)
            .system(ConsensusTextPrompt.SYSTEM)
            .addUserMessage(ConsensusTextPrompt.userMessage(companyName, body))
            .outputConfig(
                OutputConfig.builder()
                    .effort(OutputConfig.Effort.LOW)
                    .format(
                        JsonOutputFormat.builder()
                            .schema(JsonOutputFormat.Schema.builder().additionalProperties(ConsensusTextPrompt.SCHEMA.mapValues { (_, value) -> JsonValue.from(value) }).build())
                            .build(),
                    )
                    .build(),
            )
            .build()

    private fun Message.toModelRows(): ModelConsensusRows {
        val reason = stopReason().orElse(null)
        if (reason != StopReason.END_TURN) throw ConsensusReadingFailedException("Unexpected stop reason: $reason")
        val text = content().firstNotNullOfOrNull { it.text().orElse(null) }?.text()
            ?: throw ConsensusReadingFailedException("Response has no text block")
        return try {
            mapper.readValue<ModelConsensusRows>(text)
        } catch (e: Exception) {
            throw MalformedConsensusReadingException("Response is not a valid consensus reading", e)
        }
    }

    private companion object {
        const val MODEL = "claude-haiku-5-5"
        const val MAX_TOKENS = 1000L
        val MARKET = Market.GPW
        val HUNDRED = BigDecimal(100)
        const val LOSS = "strat"
        const val LOSS_LOOKBEHIND_CHARS = 40
    }
}
