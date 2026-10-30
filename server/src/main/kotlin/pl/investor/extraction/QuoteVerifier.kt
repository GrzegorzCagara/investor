package pl.investor.extraction

import org.springframework.stereotype.Component
import pl.investor.source.biznesradar.PolishNumbers
import java.math.BigDecimal

@Component
class QuoteVerifier {

    fun verify(extraction: Extraction, sourceText: String): VerifiedExtraction {
        val text = sourceText.normalized()
        val uncertain = linkedSetOf<String>()
        val periods = extraction.periods.mapIndexed { index, period ->
            period.verified(text) { field -> uncertain += "periods[$index].$field" }
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

    private fun ExtractedPeriod.verified(text: String, onUncertain: (String) -> Unit): ExtractedPeriod {
        fun Amount?.check(field: String): Amount? =
            this?.takeIf { it.isSupportedBy(text) }.also { if (this != null && it == null) onUncertain(field) }
        return copy(
            revenue = revenue.check("revenue"),
            revenuePrevYear = revenuePrevYear.check("revenuePrevYear"),
            operatingProfit = operatingProfit.check("operatingProfit"),
            operatingProfitPrevYear = operatingProfitPrevYear.check("operatingProfitPrevYear"),
            netProfit = netProfit.check("netProfit"),
            netProfitPrevYear = netProfitPrevYear.check("netProfitPrevYear"),
        )
    }

    private fun Outlook.verified(text: String): Outlook =
        if (tone != OutlookTone.NONE && !quote.isQuotedIn(text)) Outlook(OutlookTone.NONE, null) else this

    private fun String?.isQuotedIn(text: String): Boolean =
        this != null && isNotBlank() && normalized() in text

    private fun Amount.isSupportedBy(text: String): Boolean {
        val source = sourceText.normalized()
        if (source.isEmpty()) return false
        val hasUnitDeclaration = UNIT_DECLARATION.containsMatchIn(text)
        return (listOf(source) + listOfNotNull(source.singleNumberToken())).distinct().any { candidate ->
            occurrences(text, candidate).any { start ->
                hasValidBoundaries(text, candidate, start) && matchesValue(candidate, text, start, hasUnitDeclaration)
            }
        }
    }

    private fun String.singleNumberToken(): String? =
        NUMBER_TOKEN.findAll(this).map { it.value }.singleOrNull()?.takeIf { !UNIT_WORD.containsMatchIn(this) || UNIT_WORD.containsMatchIn(it) }

    private fun Amount.matchesValue(source: String, text: String, start: Int, hasUnitDeclaration: Boolean): Boolean {
        if (valueInThousands.signum() > 0 && isPrecededByMinus(text, start)) return false
        PolishNumbers.parseAmountInThousands(source)?.takeIf { it.isWithinTolerance(valueInThousands) }?.let { return true }
        if (UNIT_WORD.containsMatchIn(source) || source.count(Char::isDigit) < MIN_UNITLESS_DIGITS) return false
        if (UNIT_BLOCKER.containsMatchIn(text.substring(start + source.length))) return false
        val parsed = PolishNumbers.parse(source) ?: return false
        val multipliers = if (hasUnitDeclaration) MULTIPLIERS else listOf(BigDecimal.ONE)
        return multipliers.any { (parsed * it).isWithinTolerance(valueInThousands) }
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
        val WHITESPACE = Regex("[\\s\\u00A0\\u202F]+")
        val NUMBER_TOKEN = Regex(
            "\\d+(?: \\d{3})*(?:[,.]\\d+)?(?: (?:tys\\.?|tysięcy|mln\\.?|mld\\.?|thousand|million|billion)(?!\\p{L}))?",
            RegexOption.IGNORE_CASE,
        )
        val UNIT_WORD = Regex("\\b(tys|mln|mld|thousand|million|billion)", RegexOption.IGNORE_CASE)
        val UNIT_DECLARATION = Regex(
            "w tys\\.|w tysiącach|w mln|w milionach|tys\\. (zł|EUR|USD)",
            RegexOption.IGNORE_CASE,
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
    }
}
