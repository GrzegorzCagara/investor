package pl.investor.extraction

import com.anthropic.client.AnthropicClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicException
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicRetryableException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.errors.UnexpectedStatusCodeException
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
            throw when {
                e.isTransient() -> AiUnavailableException(e)
                e.isAuthenticationFailure() -> AiAuthenticationException(e)
                else -> ExtractionFailedException("Anthropic API call failed", e)
            }
        }
        budget.record(pool, message.usage().inputTokens(), message.usage().outputTokens())
        return message.toExtraction()
    }

    private fun AnthropicException.isTransient(): Boolean =
        this is RateLimitException || this is InternalServerException || this is AnthropicIoException ||
            this is AnthropicRetryableException || (this is UnexpectedStatusCodeException && statusCode() >= 500)

    private fun AnthropicException.isAuthenticationFailure(): Boolean =
        this is UnauthorizedException || this is PermissionDeniedException

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
