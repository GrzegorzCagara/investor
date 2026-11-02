package pl.investor.extraction

import pl.investor.extraction.JsonSchemas.enumOf
import pl.investor.extraction.JsonSchemas.nullable
import pl.investor.extraction.JsonSchemas.obj
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
        "orderIntake" to nullable(amount()),
        "orderIntakePrevYear" to nullable(amount()),
    )

    private fun amount(): Map<String, Any> = obj(
        "valueInThousands" to mapOf("type" to "number"),
        "sourceText" to mapOf("type" to "string"),
    )
}
