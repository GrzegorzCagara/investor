package pl.investor.extraction

import com.anthropic.client.AnthropicClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicException
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import org.springframework.stereotype.Component
import pl.investor.source.content.ReportContent
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue

@Component
class ClaudeExtractor(private val budget: AiBudget, private val client: AnthropicClient) {

    private val mapper = jacksonObjectMapper()

    fun extract(content: ReportContent, companyName: String, pool: AiPool): Extraction {
        if (!budget.canSpend(pool)) throw AiBudgetExhaustedException(pool)
        val message = try {
            client.messages().create(request(content, companyName))
        } catch (e: AnthropicException) {
            throw e.toAiFailure { ExtractionFailedException("Anthropic API call failed", it) }
        }
        budget.record(pool, message.usage().inputTokens(), message.usage().outputTokens())
        return message.toExtraction()
    }

    private fun request(content: ReportContent, companyName: String): MessageCreateParams =
        MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(MAX_TOKENS)
            .system(ExtractionPrompt.SYSTEM)
            .addUserMessage(ExtractionPrompt.userMessage(content, companyName))
            .outputConfig(
                OutputConfig.builder()
                    .effort(OutputConfig.Effort.LOW)
                    .format(
                        JsonOutputFormat.builder()
                            .schema(JsonOutputFormat.Schema.builder().additionalProperties(schemaProperties()).build())
                            .build(),
                    )
                    .build(),
            )
            .build()

    private fun schemaProperties(): Map<String, JsonValue> =
        ExtractionSchema.SCHEMA.mapValues { (_, value) -> JsonValue.from(value) }

    private fun Message.toExtraction(): Extraction {
        val reason = stopReason().orElse(null)
        if (reason != StopReason.END_TURN) throw ExtractionFailedException("Unexpected stop reason: $reason")
        val text = content().firstNotNullOfOrNull { it.text().orElse(null) }?.text()
            ?: throw ExtractionFailedException("Response has no text block")
        return try {
            mapper.readValue<Extraction>(text)
        } catch (e: Exception) {
            throw ExtractionFailedException("Response is not a valid extraction", e)
        }
    }

    private companion object {
        const val MODEL = "claude-haiku-5-5"
        const val MAX_TOKENS = 8000L
    }
}
