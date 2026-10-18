package saien.magrathea.runtime

/** Keep the UTF-16 budget without separating a surrogate pair at the cut. */
internal fun String.takeAtCodePointBoundary(maxChars: Int): String {
    require(maxChars >= 0) { "maxChars must not be negative" }
    if (length <= maxChars) return this
    val end = if (maxChars > 0 && this[maxChars - 1].isHighSurrogate() && this[maxChars].isLowSurrogate()) {
        maxChars - 1
    } else {
        maxChars
    }
    return substring(0, end)
}
