package pl.investor.extraction

object JsonSchemas {

    fun obj(vararg properties: Pair<String, Any>): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(*properties),
        "required" to properties.map { it.first },
        "additionalProperties" to false,
    )

    fun nullable(schema: Map<String, Any>): Map<String, Any> =
        mapOf("anyOf" to listOf(schema, mapOf("type" to "null")))

    inline fun <reified E : Enum<E>> enumOf(): Map<String, Any> =
        mapOf("type" to "string", "enum" to enumValues<E>().map { it.name })

    fun ofType(type: String): Map<String, Any> = mapOf("type" to type)
}
