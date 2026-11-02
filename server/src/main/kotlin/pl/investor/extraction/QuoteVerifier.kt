package pl.investor.extraction

import org.springframework.stereotype.Component
import pl.investor.report.Market
import pl.investor.source.biznesradar.PolishNumbers
import java.math.BigDecimal

@Component
class QuoteVerifier {

    fun verify(extraction: Extraction, sourceText: String, market: Market = Market.GPW): VerifiedExtraction {
        val text = sourceText.normalized()
        val units = market.units()
        val uncertain = linkedSetOf<String>()
        val periods = extraction.periods.mapIndexed { index, period ->
            period.verified(text, units) { field -> uncertain += "periods[$index].$field" }
        }
        val oneOffSupported = extraction.oneOffQuarter && extraction.oneOffQuote.isQuotedIn(text)
        return VerifiedExtraction(
            extraction.copy(
                periods = periods,
                outlook = extraction.outlook.verified(text),
                oneOffQuarter = oneOffSupported,
                oneOffQuote = extraction.oneOffQuote.takeIf { oneOffSupported },
            ),
            uncertain,
        )
    }

    fun isSupported(amount: Amount, sourceText: String, market: Market): Boolean =
        amount.isSupportedBy(sourceText.normalized(), market.units(), Period.CURRENT)

    private fun Market.units(): Units = if (this == Market.SE) SWEDISH else POLISH

    private fun ExtractedPeriod.verified(text: String, units: Units, onUncertain: (String) -> Unit): ExtractedPeriod {
        fun Amount?.check(field: String): Amount? {
            val period = if (field.endsWith(PREV_YEAR_SUFFIX)) Period.PREVIOUS_YEAR else Period.CURRENT
            return this?.takeIf { it.isSupportedBy(text, units, period) }.also { if (this != null && it == null) onUncertain(field) }
        }
        return copy(
            revenue = revenue.check("revenue"),
            revenuePrevYear = revenuePrevYear.check("revenuePrevYear"),
            operatingProfit = operatingProfit.check("operatingProfit"),
            operatingProfitPrevYear = operatingProfitPrevYear.check("operatingProfitPrevYear"),
            netProfit = netProfit.check("netProfit"),
            netProfitPrevYear = netProfitPrevYear.check("netProfitPrevYear"),
            orderIntake = orderIntake.check("orderIntake"),
            orderIntakePrevYear = orderIntakePrevYear.check("orderIntakePrevYear"),
        )
    }

    private fun Outlook.verified(text: String): Outlook =
        if (tone != OutlookTone.NONE && !quote.isQuotedIn(text)) Outlook(OutlookTone.NONE, null) else this

    private fun String?.isQuotedIn(text: String): Boolean =
        this != null && isNotBlank() && normalized() in text

    private fun Amount.isSupportedBy(text: String, units: Units, period: Period): Boolean {
        val source = sourceText.normalized()
        if (source.isEmpty()) return false
        val hasUnitDeclaration = units.declaration.containsMatchIn(text)
        return (listOf(source) + listOfNotNull(source.singleNumberToken(units))).distinct().any { candidate ->
            occurrences(text, candidate).any { start ->
                hasValidBoundaries(text, candidate, start) && matchesValue(candidate, text, start, hasUnitDeclaration, units, period)
            }
        }
    }

    private fun String.singleNumberToken(units: Units): String? =
        units.numberToken.findAll(this).map { it.value }.singleOrNull()
            ?.takeIf { !units.unitWord.containsMatchIn(this) || units.unitWord.containsMatchIn(it) }

    private fun Amount.matchesValue(quoted: String, text: String, start: Int, hasUnitDeclaration: Boolean, units: Units, period: Period): Boolean {
        if (valueInThousands.signum() > 0 && isPrecededByMinus(text, start)) return false
        return units.readings(quoted).any { matchesReading(it, quoted, text, start, hasUnitDeclaration, units, period) }
    }

    private fun Amount.matchesReading(source: String, quoted: String, text: String, start: Int, hasUnitDeclaration: Boolean, units: Units, period: Period): Boolean {
        units.comparative(source)?.let { return matchesComparative(it, period) }
        PolishNumbers.parseAmountInThousands(source)?.takeIf { it.isWithinTolerance(valueInThousands) }?.let { return true }
        if (units.unitWord.containsMatchIn(source) || source.count(Char::isDigit) < MIN_UNITLESS_DIGITS) return false
        if (UNIT_BLOCKER.containsMatchIn(text.substring(start + quoted.length))) return false
        val parsed = PolishNumbers.parse(source) ?: return false
        val multipliers = if (hasUnitDeclaration) MULTIPLIERS else listOf(BigDecimal.ONE)
        return multipliers.any { (parsed * it).isWithinTolerance(valueInThousands) }
    }

    private fun Amount.matchesComparative(comparative: Comparative, period: Period): Boolean {
        val amount = when (period) {
            Period.CURRENT -> comparative.current
            Period.PREVIOUS_YEAR -> "${comparative.previousYear} ${comparative.unit}"
        }
        return PolishNumbers.parseAmountInThousands(amount)?.isWithinTolerance(valueInThousands) == true
    }

    private enum class Period { CURRENT, PREVIOUS_YEAR }

    private data class Comparative(val current: String, val previousYear: String, val unit: String)

    private class Units(
        val unitWord: Regex,
        val numberToken: Regex,
        val declaration: Regex,
        private val comparativeForm: Regex?,
        val readings: (String) -> List<String> = { listOf(it) },
    ) {
        fun comparative(source: String): Comparative? {
            val match = comparativeForm?.matchEntire(source) ?: return null
            val unit = unitWord.findAll(match.groupValues[1]).lastOrNull()?.value ?: return null
            return Comparative(match.groupValues[1], match.groupValues[2], unit)
        }
    }

    private fun occurrences(text: String, source: String): Sequence<Int> =
        generateSequence(text.indexOf(source).takeIf { it >= 0 }) { previous ->
            text.indexOf(source, previous + 1).takeIf { it >= 0 }
        }

    private fun hasValidBoundaries(text: String, source: String, start: Int): Boolean {
        val before = text.substring(0, start)
        val after = text.substring(start + source.length)
        val groupedNumber = source.leadingMultiGroupNumber()
        val startsWithGroupedNumber = groupedNumber != null && groupedNumber.range.first == source.indexOfFirst(Char::isDigit)
        val endsWithGroupedNumber = groupedNumber != null && groupedNumber.range.last == source.indexOfLast(Char::isDigit)
        val rejectedBefore = if (startsWithGroupedNumber) {
            SHORT_GROUP_BEFORE.containsMatchIn(before)
        } else {
            source.firstDigitGroupHasThreeDigits() && THOUSANDS_GROUP_BEFORE.containsMatchIn(before)
        }
        return !CONTINUES_NUMBER_BEFORE.containsMatchIn(before) &&
            !rejectedBefore &&
            !CONTINUES_NUMBER_AFTER.containsMatchIn(after) &&
            !(source.last().isDigit() && !endsWithGroupedNumber && THOUSANDS_GROUP_AFTER.containsMatchIn(after))
    }

    private fun isPrecededByMinus(text: String, start: Int): Boolean =
        MINUS_BEFORE.containsMatchIn(text.substring(0, start))

    private fun String.leadingMultiGroupNumber(): MatchResult? =
        MULTI_GROUP_NUMBER.find(this)?.takeUnless { it.value.startsWith("0") }

    private fun String.firstDigitGroupHasThreeDigits(): Boolean =
        FIRST_DIGIT_GROUP.find(this)?.value?.length == 3

    private fun BigDecimal.isWithinTolerance(expected: BigDecimal): Boolean =
        (this - expected).abs() <= expected.abs() * TOLERANCE

    private fun String.normalized() = replace('−', '-').replace(WHITESPACE, " ").trim()

    private companion object {
        const val PREV_YEAR_SUFFIX = "PrevYear"
        const val POLISH_UNITS = "tys|mln|mld|thousand|million|billion"
        const val SWEDISH_UNITS = "mdsek|mdkr|msek|mnkr|mkr|ksek|tsek|tkr|miljoner|miljarder|tusen"
        const val POLISH_DECLARATION = "w tys\\.|w tysiącach|w mln|w milionach|tys\\. (zł|EUR|USD)"
        val WHITESPACE = Regex("[\\s\\u00A0\\u202F]+")
        val POLISH = Units(
            unitWord = Regex("\\b($POLISH_UNITS)", RegexOption.IGNORE_CASE),
            numberToken = Regex(
                "\\d+(?: \\d{3})*(?:[,.]\\d+)?(?: (?:tys\\.?|tysięcy|mln\\.?|mld\\.?|thousand|million|billion)(?!\\p{L}))?",
                RegexOption.IGNORE_CASE,
            ),
            declaration = Regex(POLISH_DECLARATION, RegexOption.IGNORE_CASE),
            comparativeForm = null,
        )
        val SWEDISH = Units(
            unitWord = Regex("\\b($POLISH_UNITS|$SWEDISH_UNITS)", RegexOption.IGNORE_CASE),
            numberToken = Regex(
                "\\d+(?: \\d{3})*(?:[,.]\\d+)?(?: (?:tys\\.?|tysięcy|mln\\.?|mld\\.?|thousand|million|billion|$SWEDISH_UNITS)(?!\\p{L}))?",
                RegexOption.IGNORE_CASE,
            ),
            declaration = Regex("$POLISH_DECLARATION|(?<!\\p{L})($SWEDISH_UNITS|meur|teur|keur)(?!\\p{L})|belopp i", RegexOption.IGNORE_CASE),
            comparativeForm = Regex("^(.*\\d.*?)\\s*\\(\\s*(-?\\d[\\d ]*(?:,\\d{3})*(?:[,.]\\d+)?)\\s*\\)$"),
            readings = ::englishSwedishReadings,
        )
        val UNIT_BLOCKER = Regex(
            "^\\s*(%|r\\.|(stycz|lut|marc|kwiet|maj|czerw|lip|sierp|wrze|paźdz|listop|grud)\\p{L}*)",
            RegexOption.IGNORE_CASE,
        )
        val CONTINUES_NUMBER_BEFORE = Regex("(\\d|\\d[,.])$")
        val THOUSANDS_GROUP_BEFORE = Regex("\\d $")
        val CONTINUES_NUMBER_AFTER = Regex("^(\\d|[,.]\\d)")
        val THOUSANDS_GROUP_AFTER = Regex("^ \\d{3}(?!\\d)")
        val MINUS_BEFORE = Regex("-\\s?$")
        val MULTI_GROUP_NUMBER = Regex("(?<!\\d)\\d{1,3}(?:[\\s\\u00A0\\u202F]\\d{3})+(?!\\d)")
        val SHORT_GROUP_BEFORE = Regex("(?<!\\d)\\d{1,2} $")
        val FIRST_DIGIT_GROUP = Regex("\\d+")
        val TOLERANCE = BigDecimal("0.005")
        val MULTIPLIERS = listOf(BigDecimal("0.001"), BigDecimal.ONE, BigDecimal(1000), BigDecimal(1_000_000))
        const val MIN_UNITLESS_DIGITS = 3
        val ENGLISH_DECIMAL = Regex("(?<=\\d)\\.(?=\\d)")
        val MILLIONS_SUFFIX = Regex("(?<=\\d)\\s?m(?![\\p{L}\\d])")
        val BILLIONS_SUFFIX = Regex("(?<=\\d)\\s?bn(?![\\p{L}\\d])")
        val UNIT_AFTER_COMPARATIVE = Regex(
            "^(.*\\d)\\s*(\\(\\s*-?\\d[\\d ]*(?:,\\d+)?\\s*\\))\\s*(million|billion|thousand|msek|mkr|mdsek|mdkr|ksek|tkr)$",
            RegexOption.IGNORE_CASE,
        )

        val THOUSANDS_COMMA = Regex("(?<=\\d),(?=\\d{3}(?!\\d))")

        fun englishSwedishReadings(source: String): List<String> =
            listOfNotNull(source, source.takeIf { THOUSANDS_COMMA.containsMatchIn(it) }?.replace(THOUSANDS_COMMA, ""))
                .map(::englishSwedishAmount)
                .distinct()

        private fun englishSwedishAmount(source: String): String =
            source
                .let { if (',' in it) it else it.replace(ENGLISH_DECIMAL, ",") }
                .replace(MILLIONS_SUFFIX, " msek")
                .replace(BILLIONS_SUFFIX, " mdsek")
                .replace(UNIT_AFTER_COMPARATIVE, "$1 $3 $2")
    }
}
