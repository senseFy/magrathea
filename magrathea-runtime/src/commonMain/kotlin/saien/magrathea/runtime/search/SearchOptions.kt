package saien.magrathea.runtime.search

/** Freshness preference shared by search capabilities. */
enum class SearchFreshness {
    AUTO,
    PAST_DAY,
    PAST_WEEK,
    PAST_MONTH,
    PAST_YEAR,
}

/** Content filtering preference shared by search capabilities. */
enum class SearchSafeSearch {
    OFF,
    MODERATE,
    STRICT,
}

/** Portable locale hint for a host-provided search backend. */
data class SearchLocale(
    val languageTag: String? = null,
    val countryCode: String? = null,
) {
    init {
        languageTag?.let {
            require(SEARCH_LANGUAGE_TAG.matches(it)) {
                "Search languageTag must be a normalized BCP 47 tag"
            }
        }
        countryCode?.let {
            require(SEARCH_COUNTRY_CODE.matches(it)) {
                "Search countryCode must be an uppercase ISO alpha-2 code"
            }
        }
    }
}

/** Consented approximate location hint for a host-provided search backend. */
data class SearchLocation(
    val city: String? = null,
    val region: String? = null,
    val countryCode: String? = null,
    val timezone: String? = null,
) {
    init {
        listOfNotNull(city, region, timezone).forEach {
            require(
                it.isNotBlank() &&
                    it == it.trim() &&
                    it.length <= MAX_SEARCH_LOCATION_VALUE_CHARS &&
                    it.none(Char::isUnsafeSearchControl),
            ) {
                "Search location values must be trimmed and bounded"
            }
        }
        countryCode?.let {
            require(SEARCH_COUNTRY_CODE.matches(it)) {
                "Search countryCode must be an uppercase ISO alpha-2 code"
            }
        }
    }

    override fun toString(): String = "SearchLocation(<redacted>)"
}

private fun Char.isUnsafeSearchControl(): Boolean = code < 0x20 || code == 0x7f

private val SEARCH_LANGUAGE_TAG = Regex("[a-z]{2,3}(?:-[A-Z][a-z]{3})?(?:-[A-Z]{2}|-[0-9]{3})?")
private val SEARCH_COUNTRY_CODE = Regex("[A-Z]{2}")
private const val MAX_SEARCH_LOCATION_VALUE_CHARS = 128
