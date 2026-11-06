package pl.investor.app

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Formatting {

    private val warsaw: ZoneId = ZoneId.of("Europe/Warsaw")
    private val hourMinute = DateTimeFormatter.ofPattern("HH:mm").withZone(warsaw)
    private val dayMonth = DateTimeFormatter.ofPattern("dd.MM").withZone(warsaw)
    private val dayMonthYear = DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(warsaw)
    private val sessionDateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    private val shortDateFormat = DateTimeFormatter.ofPattern("dd.MM.yy")
    private val polish = Locale.forLanguageTag("pl-PL")

    const val TODAY = "Dziś"
    const val YESTERDAY = "Wczoraj"
    const val UNKNOWN = "—"
    private const val MINUS = "−"

    fun time(publishedAt: Instant, now: Instant): String {
        val clock = hourMinute.format(publishedAt)
        return when (publishedAt.daysBefore(now)) {
            0L -> clock
            1L -> "wczoraj $clock"
            else -> "${dayMonth.format(publishedAt)} $clock"
        }
    }

    fun sectionTitle(publishedAt: Instant, now: Instant): String = when (publishedAt.daysBefore(now)) {
        0L -> TODAY
        1L -> YESTERDAY
        else -> dayMonth.format(publishedAt)
    }

    fun marketLabel(m: Market): String = when (m) {
        Market.GPW -> "GPW"
        Market.NC -> "NC"
        Market.US -> "USA"
        Market.SE -> "SE"
        Market.DE -> "DE"
        Market.NL -> "NL"
    }

    fun marketName(m: Market): String = when (m) {
        Market.GPW -> "GPW"
        Market.NC -> "NC"
        Market.US -> "USA"
        Market.SE -> "Szwecja"
        Market.DE -> "Niemcy"
        Market.NL -> "Holandia"
    }

    fun detailTitle(detail: AlertDetail): String =
        "${detail.companyName} (${listOfNotNull(marketLabel(detail.market), detail.listing).joinToString(" · ")})"

    fun reportKindLabel(kind: ReportKind): String = when (kind) {
        ReportKind.CURRENT -> "raport bieżący"
        ReportKind.QUARTERLY -> "raport kwartalny"
        ReportKind.HALF_YEARLY -> "raport półroczny"
        ReportKind.ANNUAL -> "raport roczny"
        ReportKind.OTHER -> "raport"
    }

    fun detailHeadline(detail: AlertDetail, now: Instant): String =
        "${detail.reportNumber} · ${marketLabel(detail.market)} · ${reportKindLabel(detail.reportKind)}, ${time(detail.publishedAt, now)}"

    fun points(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else String.format(polish, "%.1f", value)

    fun amount(value: Double): String = String.format(polish, "%,.1f", value)

    fun percent(value: Double?): String = signedPercent(value, 1)

    fun wholePercent(value: Double?): String = signedPercent(value, 0)

    fun sign(value: Double): Int = value.rounded(1).signum()

    private fun signedPercent(value: Double?, scale: Int): String {
        if (value == null) return UNKNOWN
        val rounded = value.rounded(scale)
        val sign = when (rounded.signum()) {
            1 -> "+"
            -1 -> MINUS
            else -> ""
        }
        return "$sign${rounded.abs().polish()}%"
    }

    fun ytdLine(pct: Double): String = "Kurs od początku roku: ${wholePercent(pct)}"

    fun ordersLine(pct: Double): String = "Zamówienia r/r: ${wholePercent(pct)}"

    fun reactionDate(isoDate: String): String =
        runCatching { LocalDate.parse(isoDate).format(shortDateFormat) }.getOrDefault(isoDate)

    fun multiple(value: Double?): String = value?.let { "${it.rounded(1).polish()}×" } ?: UNKNOWN

    fun usd(value: Double): String = "${value.rounded(2).polish()} USD"

    fun aiUsage(spent: Double, limit: Double?): String =
        limit?.let { "${spent.rounded(2).polish()} z ${usd(it)}" } ?: usd(spent)

    fun headlineDate(at: Instant): String = dayMonth.format(at)

    fun contextSign(direction: ContextDirection): String = when (direction) {
        ContextDirection.PLUS -> "+"
        ContextDirection.MINUS -> MINUS
    }

    fun date(at: Instant, now: Instant): String =
        if (at.atZone(warsaw).year == now.atZone(warsaw).year) dayMonth.format(at) else dayMonthYear.format(at)

    fun sessionDate(isoDate: String?): String =
        isoDate?.let { runCatching { LocalDate.parse(it).format(sessionDateFormat) }.getOrNull() } ?: "brak"

    fun relative(at: Instant?, now: Instant): String {
        if (at == null) return "nigdy"
        val elapsed = Duration.between(at, now).coerceAtLeast(Duration.ZERO)
        return when {
            elapsed.toMinutes() < 1 -> "przed chwilą"
            elapsed.toHours() < 1 -> "${elapsed.toMinutes()} min temu"
            elapsed.toDays() < 1 -> "${elapsed.toHours()} godz. temu"
            elapsed.toDays() == 1L -> "1 dzień temu"
            else -> "${elapsed.toDays()} dni temu"
        }
    }

    fun eventTypeLabel(type: EventType): String = when (type) {
        EventType.RESULTS -> "Wyniki"
        EventType.NEWS -> "Wydarzenia"
    }

    fun alertTypeLabel(type: EventType): String = when (type) {
        EventType.RESULTS -> "Wyniki"
        EventType.NEWS -> "Wydarzenie"
    }

    fun alertLine(alert: AlertItem, now: Instant): String =
        "${time(alert.publishedAt, now)} · ${alertTypeLabel(alert.eventType)} · ${alert.summary}"

    fun contractValueLine(news: NewsView): String? = news.contractValue?.let { value ->
        news.revenueSharePct?.let { "$value (${share(it)} rocznych przychodów)" } ?: value
    }

    private fun share(pct: Double): String =
        pct.rounded(1).takeIf { it.signum() > 0 }?.let { "${it.polish()}%" } ?: "<0,1%"

    fun reactionLabel(reaction: Reaction): String = when (reaction) {
        Reaction.NEGATIVE -> "Spadek w dniu raportu"
        Reaction.FLAT -> "Płasko"
        Reaction.POSITIVE -> "Wzrost"
    }

    fun sourceLabel(source: String): String = when (source) {
        "GPW_LISTING" -> "Komunikaty GPW"
        "NC_LISTING" -> "Komunikaty NewConnect"
        "BANKIER" -> "Bankier – komunikaty"
        "BANKIER_NEWS" -> "Bankier – konsensus PAP"
        "BIZNESRADAR" -> "BiznesRadar"
        "GPW_ARCHIVE" -> "Notowania GPW"
        "INDEX_DAILY" -> "Notowania indeksów"
        "NC_DAILY" -> "Notowania NewConnect"
        "CLAUDE" -> "AI (Claude)"
        "NASDAQ_NORDIC" -> "Nasdaq (komunikaty)"
        "AVANZA" -> "Avanza"
        "YAHOO" -> "Yahoo (indeks)"
        "PLACERA" -> "Placera (konsensus)"
        "XTB" -> "XTB (lista akcji)"
        "EQS" -> "EQS News"
        "EURONEXT" -> "Euronext Amsterdam"
        else -> source
    }

    fun aiPoolLabel(pool: String): String = when (pool) {
        "GPW" -> "GPW/NC"
        "US" -> "USA"
        "NEWS" -> "Wydarzenia"
        "SE" -> "Szwecja – wyniki"
        "SE_NEWS" -> "Szwecja – wydarzenia"
        "DE" -> "Niemcy – wyniki"
        "DE_NEWS" -> "Niemcy – wydarzenia"
        "NL" -> "Holandia – wyniki"
        "NL_NEWS" -> "Holandia – wydarzenia"
        else -> pool
    }

    fun reactionLine(reaction: ReactionView): String {
        val parts = buildList {
            add("kurs ${percent(reaction.stockPct)}")
            reaction.indexPct?.let { add("${reaction.indexName ?: "indeks"} ${percent(it)}") }
            reaction.volumeMultiple?.let { add("wolumen ${multiple(it)} średniej") }
        }
        return "Reakcja rynku: ${parts.joinToString(", ")}"
    }

    fun consensusLines(rows: List<ConsensusRowView>): List<String> {
        val (sourced, pap) = rows.partition { it.currency != null }
        val papLine = pap.takeIf { it.isNotEmpty() }
            ?.let { "Konsensus PAP: ${it.joinToString(", ") { row -> "${row.label} ${percent(row.differencePct)}" }}" }
        return listOfNotNull(papLine) + sourced.map { it.sourcedConsensusLine() }
    }

    private fun ConsensusRowView.sourcedConsensusLine(): String =
        "$label: ${amount(actual)} wobec ${amount(consensus)} mln $currency (${percent(differencePct)})"

    fun horizonExcess(stats: HorizonStats?): String = percent(stats?.avgExcessPct)

    fun horizonCount(stats: HorizonStats?): String {
        if (stats?.avgExcessPct == null) return ""
        return "(${stats.count})"
    }

    fun outcomeLine(item: OutcomeItem): String =
        "Reakcja ${percent(item.reactionPct)} · ponad indeks: 5 s. ${percent(item.excess5Pct)}, " +
            "20 s. ${percent(item.excess20Pct)}, 60 s. ${percent(item.excess60Pct)}"

    fun historyLine(item: OutcomeItem, now: Instant): String =
        "${date(item.publishedAt, now)} · ${alertTypeLabel(item.eventType)} · po 20 sesjach ${percent(item.excess20Pct)}"

    private fun Double.rounded(scale: Int): BigDecimal = BigDecimal.valueOf(this).setScale(scale, RoundingMode.HALF_UP)

    private fun BigDecimal.polish(): String = toPlainString().replace('.', ',')

    private fun Instant.daysBefore(now: Instant): Long =
        now.atZone(warsaw).toLocalDate().toEpochDay() - atZone(warsaw).toLocalDate().toEpochDay()
}
