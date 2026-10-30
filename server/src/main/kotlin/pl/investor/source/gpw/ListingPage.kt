package pl.investor.source.gpw

data class ListingPage(
    val entries: List<ListingEntry>,
    val rawItemCount: Int,
    val unparsed: List<String>,
)
