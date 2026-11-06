package pl.investor.context

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
import pl.investor.extraction.resultsPool
import pl.investor.extraction.toAiFailure
import pl.investor.report.Market
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue

@Component
class ContextPointsWriter(
    private val budget: AiBudget,
    private val client: AnthropicClient,
) {

    private val mapper = jacksonObjectMapper()

    fun write(input: ContextInput): List<ContextPoint> {
        val pool = MARKET.resultsPool()
        if (!budget.canSpend(pool)) throw AiBudgetExhaustedException(pool)
        val message = try {
            client.messages().create(request(input))
        } catch (e: AnthropicException) {
            throw e.toAiFailure { ContextWritingFailedException("Anthropic API call failed", it) }
        }
        budget.record(pool, message.usage().inputTokens(), message.usage().outputTokens())
        return message.toModelPoints().points
            .map { it.copy(text = it.text.trim().capped()) }
            .filter { it.text.isNotBlank() }
            .distinctBy { it.text }
            .take(ContextPrompt.MAX_POINTS)
    }

    private fun String.capped(): String =
        if (length <= ContextPrompt.MAX_TEXT_CHARS) this else take(ContextPrompt.MAX_TEXT_CHARS - 1).trimEnd() + ELLIPSIS

    private fun request(input: ContextInput): MessageCreateParams =
        MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(MAX_TOKENS)
            .system(ContextPrompt.SYSTEM)
            .addUserMessage(ContextPrompt.userMessage(input))
            .outputConfig(
                OutputConfig.builder()
                    .effort(OutputConfig.Effort.LOW)
                    .format(
                        JsonOutputFormat.builder()
                            .schema(JsonOutputFormat.Schema.builder().additionalProperties(ContextPrompt.SCHEMA.mapValues { (_, value) -> JsonValue.from(value) }).build())
                            .build(),
                    )
                    .build(),
            )
            .build()

    private fun Message.toModelPoints(): ModelContextPoints {
        val reason = stopReason().orElse(null)
        if (reason != StopReason.END_TURN) throw ContextWritingFailedException("Unexpected stop reason: $reason")
        val text = content().firstNotNullOfOrNull { it.text().orElse(null) }?.text()
            ?: throw ContextWritingFailedException("Response has no text block")
        return try {
            mapper.readValue<ModelContextPoints>(text)
        } catch (e: Exception) {
            throw ContextWritingFailedException("Response is not a valid context", e)
        }
    }

    private companion object {
        const val MODEL = "claude-haiku-5-5"
        const val MAX_TOKENS = 1000L
        const val ELLIPSIS = "…"
        val MARKET = Market.GPW
    }
}
