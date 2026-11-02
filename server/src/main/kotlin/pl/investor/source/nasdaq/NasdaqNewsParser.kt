package pl.investor.source.nasdaq

import pl.investor.support.STOCKHOLM
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object NasdaqNewsParser {

    private val mapper = JsonMapper.builder().build()
    private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun parse(json: String): List<NasdaqNewsItem> =
        mapper.readTree(json).path("results").path("item").elements().map { it.toItem() }

    private fun JsonNode.toItem() = NasdaqNewsItem(
        disclosureId = requiredText("disclosureId"),
        headline = path("headline").asString("").trim(),
        language = path("language").asString(""),
        category = path("cnsCategory").asString(""),
        messageUrl = requiredText("messageUrl"),
        publishedAt = LocalDateTime.parse(requiredText("published"), TIMESTAMP).atZone(STOCKHOLM).toInstant(),
        market = path("market").asString(""),
        company = requiredText("company").trim(),
    )

    private fun JsonNode.elements(): List<JsonNode> = iterator().asSequence().toList()

    private fun JsonNode.requiredText(field: String): String =
        path(field).asString("").takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("Nasdaq news item without $field")
}
