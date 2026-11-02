package pl.investor.news

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
import pl.investor.extraction.newsPool
import pl.investor.extraction.Amount
import pl.investor.extraction.QuoteVerifier
import pl.investor.extraction.toAiFailure
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

@Component
class NewsAssessor(
    private val budget: AiBudget,
    private val client: AnthropicClient,
    private val verifier: QuoteVerifier,
) {

    private val mapper = jacksonObjectMapper()

    fun assess(input: NewsInput): NewsAssessment {
        val pool = input.market.newsPool()
        if (!budget.canSpend(pool)) throw AiBudgetExhaustedException(pool)
        val message = try {
            client.messages().create(request(input))
        } catch (e: AnthropicException) {
            throw e.toAiFailure { NewsAssessmentFailedException("Anthropic API call failed", it) }
        }
        budget.record(pool, message.usage().inputTokens(), message.usage().outputTokens())
        return message.toModelAssessment().guarded(input)
    }

    private fun request(input: NewsInput): MessageCreateParams =
        MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(MAX_TOKENS)
            .system(NewsPrompt.SYSTEM)
            .addUserMessage(NewsPrompt.userMessage(input))
            .outputConfig(
                OutputConfig.builder()
                    .effort(OutputConfig.Effort.LOW)
                    .format(
                        JsonOutputFormat.builder()
                            .schema(JsonOutputFormat.Schema.builder().additionalProperties(NewsPrompt.SCHEMA.mapValues { (_, value) -> JsonValue.from(value) }).build())
                            .build(),
                    )
                    .build(),
            )
            .build()

    private fun Message.toModelAssessment(): ModelNewsAssessment {
        val reason = stopReason().orElse(null)
        if (reason != StopReason.END_TURN) throw NewsAssessmentFailedException("Unexpected stop reason: $reason")
        val text = content().firstNotNullOfOrNull { it.text().orElse(null) }?.text()
            ?: throw NewsAssessmentFailedException("Response has no text block")
        return try {
            mapper.readValue<ModelNewsAssessment>(text)
        } catch (e: Exception) {
            throw NewsAssessmentFailedException("Response is not a valid news assessment", e)
        }
    }

    private fun ModelNewsAssessment.guarded(input: NewsInput): NewsAssessment {
        val value = contractValue
            ?.takeIf { category == NewsCategory.CONTRACT }
            ?.takeIf { verifier.isSupported(Amount(it.valueInThousands, it.sourceText), input.announcement, input.market) }
        val currency = value?.currency
        val clamped = impact.coerceIn(0.0, 100.0)
        return NewsAssessment(
            category = category,
            direction = direction,
            impact = if (category == NewsCategory.CONTRACT && value == null) clamped.coerceAtMost(UNVERIFIED_CONTRACT_MAX_IMPACT) else clamped,
            reason = reason.trim(),
            contractValue = value?.valueInThousands,
            contractCurrency = currency,
            revenueSharePct = value?.takeIf { currency?.name == input.currency }?.let { revenueSharePct(it.valueInThousands, input.trailingRevenue) },
        )
    }

    private fun revenueSharePct(value: BigDecimal, revenue: BigDecimal?): Double? =
        revenue?.takeIf { it.signum() > 0 }
            ?.let { value.multiply(HUNDRED).divide(it, MathContext.DECIMAL64).setScale(1, RoundingMode.HALF_UP).toDouble() }

    private companion object {
        const val MODEL = "claude-haiku-5-5"
        const val MAX_TOKENS = 2000L
        const val UNVERIFIED_CONTRACT_MAX_IMPACT = 60.0
        val HUNDRED = BigDecimal(100)
    }
}
