package saien.magrathea.provider.api

/**
 * Content-free facts about a protocol failure, suitable for a completed attempt's trace.
 *
 * Adapters must supply stable, code-defined reason/field identifiers and allowlisted event
 * types. Never derive these identifiers from exception messages, payload values, or headers.
 * Unknown wire event types must be represented by a fixed identifier such as `unknown`.
 * [eventIndex] is the one-based SSE frame ordinal, when available.
 */
data class ProviderProtocolDiagnostic(
    val reason: String,
    val eventType: String? = null,
    val eventIndex: Long? = null,
    val field: String? = null,
) {
    init {
        require(reason.isDiagnosticIdentifier())
        require(eventType == null || eventType.isDiagnosticIdentifier())
        require(field == null || field.isDiagnosticIdentifier())
        require(eventIndex == null || eventIndex >= 1)
    }
}

private fun String.isDiagnosticIdentifier(): Boolean = length in 1..128 && all {
    it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '.' || it == '_' || it == '-'
}
