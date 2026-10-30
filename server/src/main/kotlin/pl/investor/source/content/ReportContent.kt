package pl.investor.source.content

data class SelectedDataRow(val label: String, val values: List<String>)

data class ReportContent(
    val text: String,
    val selectedData: List<SelectedDataRow>,
    val attachmentUrls: List<String>,
    val attachmentText: String = "",
    val attachmentsPending: Boolean = false,
)
