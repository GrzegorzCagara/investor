package pl.investor.app

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Formatting {

    private val warsaw: ZoneId = ZoneId.of("Europe/Warsaw")
    private val hourMinute = DateTimeFormatter.ofPattern("HH:mm").withZone(warsaw)
    private val dayMonth = DateTimeFormatter.ofPattern("dd.MM").withZone(warsaw)
    private val polish = Locale.forLanguageTag("pl-PL")

    const val TODAY = "Dziś"
    const val YESTERDAY = "Wczoraj"

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
    }

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

    private fun Instant.daysBefore(now: Instant): Long =
        now.atZone(warsaw).toLocalDate().toEpochDay() - atZone(warsaw).toLocalDate().toEpochDay()
}
