package pl.investor.source.quotes

internal object QuoteFixtures {

    const val ASBIS_ISIN = "CY1000031710"

    val HTML_PAGE = "<!DOCTYPE html><html><body>Brak notowań</body></html>".toByteArray()

    fun bytes(path: String): ByteArray =
        checkNotNull(QuoteFixtures::class.java.getResourceAsStream("/fixtures/$path")) { "missing fixture $path" }.use { it.readBytes() }
}
