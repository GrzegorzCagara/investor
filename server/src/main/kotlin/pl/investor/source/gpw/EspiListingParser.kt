package pl.investor.source.gpw

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import pl.investor.report.Market
import pl.investor.report.ReportKind
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Component
class EspiListingParser {

    private val log = LoggerFactory.getLogger(EspiListingParser::class.java)

    fun parse(html: String, market: Market): ListingPage {
        val items = Jsoup.parseBodyFragment(html).select("li")
        val results = items.map { item -> item to runCatching { item.toEntry(market) } }
        val unparsed = results.filter { it.second.isFailure }.map { (item, failure) ->
            item.text().also { log.warn("Unparseable listing item ({}): {}", failure.exceptionOrNull()?.message, it) }
        }
        return ListingPage(results.mapNotNull { it.second.getOrNull() }, items.size, unparsed)
    }

    private fun Element.toEntry(market: Market): ListingEntry {
        val dateParts = requireNotNull(selectFirst("span.date")) { "no date span" }.text().split("|").map { it.trim() }
        val link = requireNotNull(selectFirst("strong.name a")) { "no company link" }
        val linkText = link.text().trim()
        val (name, isin) = NAME_WITH_ISIN.matchEntire(linkText)?.destructured
            ?.let { (name, isin) -> name.trim() to isin }
            ?: (linkText to null)
        val reportId = requireNotNull(GERU_ID.find(link.attr("href"))?.groupValues?.get(1)) { "no geru_id" }
        return ListingEntry(
            market = market,
            companyName = name,
            isin = isin,
            kind = dateParts.kindPart()?.toReportKind() ?: ReportKind.OTHER,
            number = dateParts.last(),
            sourceRef = reportId,
            title = selectFirst("p")?.text()?.trim()?.takeIf { it.isNotEmpty() },
            publishedAt = LocalDateTime.parse(dateParts.first(), TIMESTAMP).atZone(WARSAW).toInstant(),
            detailUrl = "${market.detailBaseUrl()}$reportId",
        )
    }

    private fun List<String>.kindPart() = if (size >= 4) this[1] else null

    private fun String.toReportKind() = when (this) {
        "Bieżący" -> ReportKind.CURRENT
        "Kwartalny" -> ReportKind.QUARTERLY
        "Półroczny" -> ReportKind.HALF_YEARLY
        "Roczny" -> ReportKind.ANNUAL
        else -> ReportKind.OTHER
    }

    private fun Market.detailBaseUrl() = when (this) {
        Market.GPW -> "https://www.gpw.pl/komunikat?geru_id="
        Market.NC -> "https://newconnect.pl/komunikat?geru_id="
        Market.US -> error("No ESPI listing for $this")
    }

    private companion object {
        val WARSAW: ZoneId = ZoneId.of("Europe/Warsaw")
        val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss")
        val NAME_WITH_ISIN = Regex("""(.*)\(([A-Z]{2}[A-Z0-9]{10})\)""", RegexOption.DOT_MATCHES_ALL)
        val GERU_ID = Regex("""geru_id=(\d+)""")
    }
}
