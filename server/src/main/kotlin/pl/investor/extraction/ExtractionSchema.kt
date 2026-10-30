package pl.investor.extraction

import pl.investor.history.PeriodKind

object ExtractionSchema {

    val SCHEMA: Map<String, Any> = obj(
        "category" to enumOf<ReportCategory>(),
        "periods" to mapOf("type" to "array", "items" to period()),
        "outlook" to obj(
            "tone" to enumOf<OutlookTone>(),
            "quote" to nullable(mapOf("type" to "string")),
        ),
        "oneOffQuarter" to mapOf("type" to "boolean"),
        "oneOffQuote" to nullable(mapOf("type" to "string")),
        "cyclicalIndustry" to mapOf("type" to "boolean"),
    )

    private fun period(): Map<String, Any> = obj(
        "kind" to enumOf<PeriodKind>(),
        "periodEnd" to mapOf("type" to "string"),
        "currency" to mapOf("type" to "string"),
        "revenue" to nullable(amount()),
        "revenuePrevYear" to nullable(amount()),
        "operatingProfit" to nullable(amount()),
        "operatingProfitPrevYear" to nullable(amount()),
        "netProfit" to nullable(amount()),
        "netProfitPrevYear" to nullable(amount()),
    )

    private fun amount(): Map<String, Any> = obj(
        "valueInThousands" to mapOf("type" to "number"),
        "sourceText" to mapOf("type" to "string"),
    )

    private fun obj(vararg properties: Pair<String, Any>): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(*properties),
        "required" to properties.map { it.first },
        "additionalProperties" to false,
    )

    private fun nullable(schema: Map<String, Any>): Map<String, Any> =
        mapOf("anyOf" to listOf(schema, mapOf("type" to "null")))

    private inline fun <reified E : Enum<E>> enumOf(): Map<String, Any> =
        mapOf("type" to "string", "enum" to enumValues<E>().map { it.name })
}
