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
                cashFlow = extraction.cashFlow?.takeIf { it.isSupportedBy(text, units) },
            ),
            uncertain,
        )
    }

    fun isSupported(amount: Amount, sourceText: String, market: Market): Boolean =
        amount.isSupportedBy(sourceText.normalized(), market.units(), Period.CURRENT)

    private fun Market.units(): Units = when (this) {
        Market.SE -> SWEDISH
        Market.DE -> GERMAN
        Market.GPW, Market.NC, Market.US -> POLISH
    }

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

    private fun CashFlow.isSupportedBy(text: String, units: Units): Boolean =
        operatingCashFlow.isSupportedStatementFigure(text, units) && netProfit.isSupportedStatementFigure(text, units)

    private fun Amount.isSupportedStatementFigure(text: String, units: Units): Boolean = when {
        !units.negativesInBrackets -> isSupportedBy(text, units, Period.CURRENT)
        valueInThousands.signum() > 0 -> isSupportedBy(text, units, Period.CURRENT) && !isQuotedOnlyInBrackets(text)
        else -> isSupportedBy(text, units, Period.CURRENT) || isSupportedAsBracketedNegative(text, units)
    }

    private fun Amount.isQuotedOnlyInBrackets(text: String): Boolean {
        val source = sourceText.normalized()
        val number = BRACKETED_NUMBER.find(source)?.groupValues?.get(1)
            ?: PLAIN_NUMBER.findAll(source).map { it.value }.singleOrNull()
            ?: return false
        val standalone = occurrences(text, number).filter { start -> text.getOrNull(start - 1)?.isDigit() != true }.toList()
        return standalone.isNotEmpty() && standalone.all { start -> text.getOrNull(start - 1) == '(' && text.getOrNull(start + number.length) == ')' }
    }

    private fun Amount.isSupportedAsBracketedNegative(text: String, units: Units): Boolean {
        val source = sourceText.normalized()
        val bracketed = BRACKETED_NUMBER.find(source)
        if (valueInThousands.signum() >= 0 || bracketed == null || source !in text) return false
        return Amount(valueInThousands.negate(), bracketed.groupValues[1]).isSupportedBy(text, units, Period.CURRENT)
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
        val readings = units.readings(quoted)
        if (units.labelsPriorYearInBrackets && readings.none { units.comparative(it) != null } && !isQuotedForPeriod(quoted, text, start, units, period)) return false
        return readings.any { matchesReading(it, quoted, text, start, hasUnitDeclaration, units, period) }
    }

    private fun isQuotedForPeriod(quoted: String, text: String, start: Int, units: Units, period: Period): Boolean {
        val valueStart = start + (units.numberToken.findAll(quoted).lastOrNull()?.range?.first ?: 0)
        val insidePriorYearBracket = text.substring(0, valueStart).isInsidePriorYearBracket()
        val tableValue = !units.unitWord.containsMatchIn(quoted) && !ENGLISH_UNIT_SUFFIX.containsMatchIn(quoted)
        return if (period == Period.PREVIOUS_YEAR) insidePriorYearBracket || tableValue else !insidePriorYearBracket
    }

    private fun String.isInsidePriorYearBracket(): Boolean {
        val open = lastIndexOf('(')
        return open >= 0 && indexOf(')', open) < 0 && PRIOR_YEAR_BRACKET_LABEL.containsMatchIn(substring(open))
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
        val labelsPriorYearInBrackets: Boolean = false,
        val negativesInBrackets: Boolean = false,
    ) {
        fun comparative(source: String): Comparative? {
            val match = comparativeForm?.matchEntire(source) ?: return null
            val unit = match.groupValues.getOrNull(3)?.takeIf { it.isNotEmpty() }
                ?: unitWord.findAll(match.groupValues[1]).lastOrNull()?.value
                ?: return null
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
        const val GERMAN_UNITS = "mio|mrd|millionen|milliarden|teur|keur|meur|tsd"
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
            negativesInBrackets = true,
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
        val GERMAN = Units(
            unitWord = Regex("\\b($POLISH_UNITS|$GERMAN_UNITS)|T€", RegexOption.IGNORE_CASE),
            numberToken = Regex(
                "-?\\d+(?:[ .]\\d{3})*(?:[,.]\\d+)?(?: ?(?:mio\\.?|mrd\\.?|millionen|milliarden|million|billion|thousand|teur|t€|tsd\\.?)(?!\\p{L}))?(?: ?(?:euro|eur|€)(?!\\p{L}))?",
                RegexOption.IGNORE_CASE,
            ),
            declaration = Regex(
                "(?<!\\p{L})(teur|keur|meur|t€|tsd\\. ?(eur|€)|mio\\. ?(eur|€)|(eur|€) ?(thousand|million|mio\\.?)|(thousand|million|mio\\.?) (eur|€|euro))(?!\\p{L})",
                RegexOption.IGNORE_CASE,
            ),
            comparativeForm = Regex("^(.*\\d.*?)\\s*\\(\\s*(-?\\d[\\d ]*(?:,\\d+)?)\\s*(mln|mld|tys)?\\s*\\)$"),
            readings = ::germanReadings,
            labelsPriorYearInBrackets = true,
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
        val BRACKETED_NUMBER = Regex("\\((\\d(?:[\\d ,.]*\\d)?)\\)")
        val PLAIN_NUMBER = Regex("\\d(?:[\\d ,.]*\\d)?")
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

        val COMPARATIVE_LABEL = Regex("\\(\\s*[^():]*:\\s*")
        val COMPARATIVE_REMARK = Regex(",\\s+[^()]*\\)")
        val GERMAN_THOUSANDS_UNIT = Regex("(?<!\\p{L})(teur|keur|tsd\\.?|tausend|thousand)(?!\\p{L})|T€", RegexOption.IGNORE_CASE)
        val GERMAN_CURRENCY = Regex("€|(?<!\\p{L})(eur|euro)(?!\\p{L})", RegexOption.IGNORE_CASE)
        val GERMAN_MILLIONS_UNIT = Regex("(?<!\\p{L})(meur|mio\\.?|millionen|million|mn)(?!\\p{L})", RegexOption.IGNORE_CASE)
        val GERMAN_BILLIONS_UNIT = Regex("(?<!\\p{L})(mrd\\.?|milliarden|billion|bn)(?!\\p{L})", RegexOption.IGNORE_CASE)
        val MINUS_BEFORE_NUMBER = Regex("-\\s+(?=\\d)")
        val UNIT_BEFORE_NUMBER = Regex("^(mln|mld|tys) (-?\\d[\\d.,]*)")
        val GERMAN_THOUSANDS_DOT = Regex("(?<=\\d)\\.(?=\\d{3}(?!\\d))")
        val GERMAN_UNIT_SPELLING = Regex("(?<!\\p{L})(mio|mrd|millionen|milliarden|teur|keur|meur|tsd|tausend)(?!\\p{L})|T€", RegexOption.IGNORE_CASE)
        val ENGLISH_UNIT_SPELLING = Regex("(?<!\\p{L})(million|billion|thousand|mn|bn)(?!\\p{L})", RegexOption.IGNORE_CASE)
        val ENGLISH_UNIT_SUFFIX = Regex("(?<=\\d)\\s?m(?![\\p{L}\\d])")
        val PRIOR_YEAR_BRACKET_LABEL = Regex(":|vorjahr|vj\\.|prior[- ]year|previous year|last year", RegexOption.IGNORE_CASE)

        fun germanReadings(source: String): List<String> {
            val amount = source
                .replace(COMPARATIVE_LABEL, "(")
                .replace(COMPARATIVE_REMARK, ")")
                .replace(GERMAN_THOUSANDS_UNIT, " tys ")
                .replace(GERMAN_CURRENCY, " ")
                .replace(GERMAN_MILLIONS_UNIT, " mln ")
                .replace(MILLIONS_SUFFIX, " mln ")
                .replace(GERMAN_BILLIONS_UNIT, " mld ")
                .replace(MINUS_BEFORE_NUMBER, "-")
                .replace(WHITESPACE, " ")
                .trim()
                .replace(UNIT_BEFORE_NUMBER, "$2 $1")
            val germanFormat = amount.replace(GERMAN_THOUSANDS_DOT, "")
            val englishFormat = amount.replace(THOUSANDS_COMMA, "").replace(ENGLISH_DECIMAL, ",")
            return when {
                GERMAN_UNIT_SPELLING.containsMatchIn(source) -> listOf(germanFormat)
                ENGLISH_UNIT_SPELLING.containsMatchIn(source) || ENGLISH_UNIT_SUFFIX.containsMatchIn(source) -> listOf(englishFormat)
                else -> listOf(germanFormat, englishFormat).distinct()
            }
        }
    }
}
