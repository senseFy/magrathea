package saien.magrathea.runtime.search

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import saien.magrathea.core.ToolResultContextHints

/**
 * Restorable stub kept when a search result is cleared from the Provider projection: the query
 * and the pages it found, without snippets, so the model can cite or search again.
 */
internal fun searchResultContextHints(query: String, pages: List<SearchPageReference>): ToolResultContextHints {
    val included = mutableListOf<JsonObject>()
    var size = STUB_ENVELOPE_CHARS + query.length
    for (page in pages) {
        val entry = buildJsonObject {
            put("title", page.title)
            put("url", page.url)
        }
        val entrySize = entry.toString().length + 1
        if (size + entrySize > MAX_SEARCH_STUB_CHARS) break
        included += entry
        size += entrySize
    }
    return ToolResultContextHints(
        stub = buildJsonObject {
            put("query", query)
            put("sources", JsonArray(included))
            if (included.size < pages.size) put("omittedSources", pages.size - included.size)
        },
    )
}

internal data class SearchPageReference(val title: String, val url: String)

/**
 * Keeps the leading, highest-ranked items whose serialized size fits [budgetTokens]. The Runtime
 * reduces any result over its budget anyway; admitting whole items first keeps the result
 * well-formed. Sizes are UTF-8 bytes at four per token, the portable estimator's default, which is
 * never smaller than a UTF-16 count. At least one item is always kept.
 */
internal fun <T> List<T>.admittedWithinBudget(
    budgetTokens: Long?,
    envelopeChars: Int,
    serialized: (index: Int, item: T) -> String,
): List<T> {
    if (budgetTokens == null || isEmpty()) return this
    val budgetBytes = budgetTokens * BYTES_PER_TOKEN
    var used = envelopeChars.toLong()
    var count = 0
    for ((index, item) in withIndex()) {
        val cost = serialized(index, item).encodeToByteArray().size + 1L
        if (count > 0 && used + cost > budgetBytes) break
        used += cost
        count++
    }
    return take(count)
}

private const val BYTES_PER_TOKEN = 4L
private const val STUB_ENVELOPE_CHARS = 64
private const val MAX_SEARCH_STUB_CHARS = 1_500
