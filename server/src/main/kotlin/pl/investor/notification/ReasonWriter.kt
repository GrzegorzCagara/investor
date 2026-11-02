package pl.investor.notification

import com.anthropic.client.AnthropicClient
import com.anthropic.errors.AnthropicException
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import pl.investor.extraction.AiBudget
import pl.investor.extraction.resultsPool
import pl.investor.report.Company
import pl.investor.scoring.Score
import pl.investor.scoring.ScoringInput

@Component
class ReasonWriter(
    private val claude: AnthropicClient,
    private val budget: AiBudget,
    private val template: ReasonTemplate,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun write(company: Company, input: ScoringInput, score: Score): String =
        generate(company, input, score) ?: template.render(company, input, score)

    private fun generate(company: Company, input: ScoringInput, score: Score): String? {
        val pool = company.market.resultsPool()
        if (!budget.canSpend(pool)) return null
        val message = try {
            claude.messages().create(request(ReasonPrompt.userMessage(company, input, score)))
        } catch (e: AnthropicException) {
            log.warn("Reason generation failed, using the template", e)
            return null
        }
        budget.record(pool, message.usage().inputTokens(), message.usage().outputTokens())
        return message.text()
    }

    private fun Message.text(): String? {
        if (stopReason().orElse(null) != StopReason.END_TURN) return null
        return content().firstNotNullOfOrNull { it.text().orElse(null) }?.text()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun request(userMessage: String): MessageCreateParams =
        MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(MAX_TOKENS)
            .system(ReasonPrompt.SYSTEM)
            .addUserMessage(userMessage)
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            .build()

    private companion object {
        const val MODEL = "claude-haiku-5-5"
        const val MAX_TOKENS = 1000L
    }
}
