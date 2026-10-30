package pl.investor.source

class SourceUnavailableException(val source: Source, cause: Throwable) :
    RuntimeException("Source $source unavailable: ${cause.message}", cause)
