package pl.investor.source.content

class ReportContentNotFoundException(val url: String) : RuntimeException("Report body not found at $url")
