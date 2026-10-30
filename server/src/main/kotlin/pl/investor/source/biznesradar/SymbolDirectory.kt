package pl.investor.source.biznesradar

import tools.jackson.databind.json.JsonMapper
import pl.investor.report.Company
import java.text.Normalizer

class SymbolNotFoundException(company: Company) :
    RuntimeException("No BiznesRadar symbol for ${company.name} (ticker ${company.ticker})")

class SymbolDirectory private constructor(private val entries: List<Entry>) {

    data class Entry(val ticker: String, val mediumName: String, val fullName: String, val symbol: String)

    fun symbolFor(company: Company): String =
        (byTicker(company) ?: byName(company))?.symbol ?: throw SymbolNotFoundException(company)

    fun mediumNameFor(companyName: String): String? {
        val name = normalize(companyName).takeIf { it.isNotEmpty() } ?: return null
        return (entries.firstOrNull { it.mediumName == name } ?: entries.firstOrNull { it.fullName == name })?.mediumName
    }

    private fun byTicker(company: Company) =
        company.ticker?.let { ticker -> entries.firstOrNull { it.ticker.equals(ticker, ignoreCase = true) } }

    private fun byName(company: Company): Entry? {
        val name = normalize(company.name).takeIf { it.isNotEmpty() } ?: return null
        return entries.firstOrNull { it.mediumName == name } ?: entries.firstOrNull { it.fullName == name }
    }

    companion object {
        private val PREFIX = Regex("^\\s*var\\s+symbols\\s*=\\s*")
        private val LEGAL_WORDS = setOf("S", "A", "SA", "SPOLKA", "AKCYJNA", "PLC", "ENTERPRISES", "SE", "NV", "LTD")
        private val NON_ALPHANUMERIC = Regex("[^A-Z0-9]+")
        private val DIACRITICS = Regex("\\p{M}+")
        private val mapper = JsonMapper.builder().build()

        fun parse(script: String): SymbolDirectory {
            val json = script.replace(PREFIX, "").trimEnd().removeSuffix(";")
            val entries = mapper.readTree(json).filter { it.path("source").asString() == "BDM" && it.path("currency").asString("") == "PLN" }.map { node ->
                Entry(
                    ticker = node.path("shortName").asString(),
                    mediumName = normalize(node.path("mediumName").asString("")),
                    fullName = normalize(node.path("fullName").asString("")),
                    symbol = node.path("ut").asString(),
                )
            }
            return SymbolDirectory(entries)
        }

        fun normalize(name: String): String =
            Normalizer.normalize(name.uppercase().replace('Ł', 'L'), Normalizer.Form.NFD)
                .replace(DIACRITICS, "")
                .split(NON_ALPHANUMERIC)
                .filter { it.isNotEmpty() && it !in LEGAL_WORDS }
                .joinToString("")
    }
}
