package saien.magrathea.core

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Stable, payload-free classification for persisted envelope decode failures. */
enum class StoredEnvelopeDecodeFailure {
    CORRUPT,
    UNSUPPORTED_OLDER_SCHEMA,
    UNSUPPORTED_NEWER_SCHEMA,
    MIGRATION_FAILED,
}

/**
 * A fully decoded current value together with the persistence work discovered while reading it.
 *
 * [rewritePayload] is present only when [sourceSchemaVersion] was migrated. Storage adapters must
 * persist it only after their own index and cross-record identity checks have also succeeded.
 */
class StoredEnvelopeDecodeResult<T> internal constructor(
    val value: T,
    val sourceSchemaVersion: Int,
    val rewritePayload: String?,
) {
    init {
        require(sourceSchemaVersion > 0) { "Source schema version must be positive" }
    }
}

/**
 * Reports why a persisted envelope could not be decoded without exposing its contents.
 *
 * Hosts may use [failure] and the schema versions to offer an upgrade or reset flow. They must
 * never treat an older/newer schema as an individually corrupt record and silently discard it.
 */
class StoredEnvelopeDecodeException internal constructor(
    val failure: StoredEnvelopeDecodeFailure,
    val storedSchemaVersion: Int?,
    val currentSchemaVersion: Int,
) : SerializationException(
    when (failure) {
        StoredEnvelopeDecodeFailure.CORRUPT -> "Stored envelope is corrupt"
        StoredEnvelopeDecodeFailure.UNSUPPORTED_OLDER_SCHEMA ->
            "Stored envelope uses an unsupported older schema"
        StoredEnvelopeDecodeFailure.UNSUPPORTED_NEWER_SCHEMA ->
            "Stored envelope uses an unsupported newer schema"
        StoredEnvelopeDecodeFailure.MIGRATION_FAILED -> "Stored envelope migration failed"
    },
)

/**
 * Frozen behavior for one adjacent schema transition.
 *
 * A shipped implementation must be a dedicated object whose complete source file is frozen by the
 * persistence ledger. Its validation owns every source field consumed, removed, or introduced by
 * the transformation. The complete chain is then decoded and canonically validated only by the
 * current schema adapter, so historical transitions never depend on drifting live serializers.
 */
internal interface AdjacentStorageSchemaMigration {
    /** Validates the frozen source preconditions required by this exact transformation. */
    fun validateSource(document: JsonObject)

    /** Transforms one validated document to the immediately following schema. */
    fun migrate(document: JsonObject): JsonObject
}

internal data class RegisteredStorageSchemaMigration(
    val id: String,
    val fromVersion: Int,
    val migration: AdjacentStorageSchemaMigration,
)

internal data class EvolvedStoredEnvelope(
    val document: JsonObject,
    val sourceSchemaVersion: Int,
    val currentSchemaVersion: Int,
) {
    val migrated: Boolean
        get() = sourceSchemaVersion != currentSchemaVersion
}

/**
 * Core-owned, contiguous migration chain for logical JSON envelopes.
 *
 * [minimumReadableVersion] is the first supported migration baseline. Versions below it are an
 * explicit historical clean break. The repository ledger freezes that baseline so it cannot move
 * forward in a later release. Every version in `[minimumReadableVersion, currentVersion)` must have
 * exactly one adjacent migration, so increasing the current schema without registering the
 * transition fails immediately.
 */
internal class StorageSchemaEvolution(
    val minimumReadableVersion: Int,
    val currentVersion: Int,
    migrations: List<RegisteredStorageSchemaMigration>,
) {
    private val migrationsByVersion: Map<Int, RegisteredStorageSchemaMigration>

    init {
        require(minimumReadableVersion > 0) { "Minimum readable schema must be positive" }
        require(currentVersion >= minimumReadableVersion) {
            "Current schema must not precede the minimum readable schema"
        }
        require(migrations.all { it.fromVersion > 0 }) {
            "Migration source versions must be positive"
        }
        require(migrations.all { it.id.isStableMigrationId() }) {
            "Storage schema migrations must have stable lowercase IDs"
        }
        require(migrations.map { it.id }.distinct().size == migrations.size) {
            "Storage schema migrations must not contain duplicate IDs"
        }
        migrationsByVersion = migrations.associateBy(RegisteredStorageSchemaMigration::fromVersion)
        require(migrationsByVersion.size == migrations.size) {
            "Storage schema migrations must not contain duplicate source versions"
        }
        val requiredVersions = (minimumReadableVersion until currentVersion).toSet()
        require(migrationsByVersion.keys == requiredVersions) {
            "Storage schema migrations must cover every adjacent version from " +
                "$minimumReadableVersion to $currentVersion"
        }
    }

    fun evolve(payload: String, json: Json): EvolvedStoredEnvelope {
        val document = try {
            rejectDuplicateObjectKeys(payload)
            json.parseToJsonElement(payload) as? JsonObject
                ?: throw IllegalArgumentException("Stored envelope must be a JSON object")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw decodeFailure(StoredEnvelopeDecodeFailure.CORRUPT, null)
        }
        return evolve(document)
    }

    fun evolve(document: JsonObject): EvolvedStoredEnvelope {
        val sourceVersion = document[SCHEMA_VERSION_FIELD]
            ?.let { element -> element as? JsonPrimitive }
            ?.takeUnless(JsonPrimitive::isString)
            ?.intOrNull
            ?.takeIf { it > 0 }
            ?: throw decodeFailure(StoredEnvelopeDecodeFailure.CORRUPT, null)

        if (sourceVersion < minimumReadableVersion) {
            throw decodeFailure(
                StoredEnvelopeDecodeFailure.UNSUPPORTED_OLDER_SCHEMA,
                sourceVersion,
            )
        }
        if (sourceVersion > currentVersion) {
            throw decodeFailure(
                StoredEnvelopeDecodeFailure.UNSUPPORTED_NEWER_SCHEMA,
                sourceVersion,
            )
        }

        var evolved = document
        for (version in sourceVersion until currentVersion) {
            val registered = checkNotNull(migrationsByVersion[version]) {
                "Missing registered migration from storage schema $version"
            }
            evolved = try {
                registered.migration.validateSource(evolved)
                val transformed = registered.migration.migrate(evolved)
                JsonObject(
                    transformed.toMutableMap().apply {
                        put(SCHEMA_VERSION_FIELD, JsonPrimitive(version + 1))
                    },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                throw decodeFailure(
                    StoredEnvelopeDecodeFailure.MIGRATION_FAILED,
                    sourceVersion,
                )
            }
        }
        return EvolvedStoredEnvelope(evolved, sourceVersion, currentVersion)
    }

    private fun decodeFailure(
        failure: StoredEnvelopeDecodeFailure,
        storedVersion: Int?,
    ): StoredEnvelopeDecodeException = StoredEnvelopeDecodeException(
        failure = failure,
        storedSchemaVersion = storedVersion,
        currentSchemaVersion = currentVersion,
    )

    private companion object {
        const val SCHEMA_VERSION_FIELD = "schemaVersion"
    }
}

/**
 * Kotlinx serialization materializes JSON objects as maps and therefore retains only one value for
 * a duplicate key. Scan the original wire document first so every object depth has unambiguous
 * semantics before the regular JSON parser or a schema migration sees it.
 */
private fun rejectDuplicateObjectKeys(payload: String) {
    JsonDuplicateKeyScanner(payload).scan()
}

private class JsonDuplicateKeyScanner(
    private val source: String,
) {
    private var offset: Int = 0

    fun scan() {
        skipWhitespace()
        scanValue(nestingDepth = 0)
        skipWhitespace()
        check(offset == source.length) { "Trailing JSON input" }
    }

    private fun scanValue(nestingDepth: Int) {
        skipWhitespace()
        check(offset < source.length) { "Missing JSON value" }
        when (source[offset]) {
            '{' -> {
                check(nestingDepth < MAX_JSON_NESTING_DEPTH) { "JSON nesting limit exceeded" }
                scanObject(nestingDepth + 1)
            }
            '[' -> {
                check(nestingDepth < MAX_JSON_NESTING_DEPTH) { "JSON nesting limit exceeded" }
                scanArray(nestingDepth + 1)
            }
            '"' -> scanString()
            't' -> scanLiteral("true")
            'f' -> scanLiteral("false")
            'n' -> scanLiteral("null")
            '-', in '0'..'9' -> scanNumber()
            else -> error("Invalid JSON value")
        }
    }

    private fun scanObject(nestingDepth: Int) {
        offset++ // {
        skipWhitespace()
        val keys = mutableSetOf<String>()
        if (consume('}')) return
        while (true) {
            check(current() == '"') { "JSON object key must be a string" }
            val key = decodeString(scanString())
            check(keys.add(key)) { "Duplicate JSON object key" }
            skipWhitespace()
            check(consume(':')) { "Missing JSON object colon" }
            scanValue(nestingDepth)
            skipWhitespace()
            when {
                consume('}') -> return
                consume(',') -> {
                    skipWhitespace()
                    check(current() == '"') { "JSON object key must be a string" }
                }
                else -> error("Missing JSON object separator")
            }
        }
    }

    private fun scanArray(nestingDepth: Int) {
        offset++ // [
        skipWhitespace()
        if (consume(']')) return
        while (true) {
            scanValue(nestingDepth)
            skipWhitespace()
            when {
                consume(']') -> return
                consume(',') -> Unit
                else -> error("Missing JSON array separator")
            }
        }
    }

    /** Returns the complete quoted JSON string, including its delimiters. */
    private fun scanString(): String {
        val start = offset
        check(consume('"')) { "JSON string must start with a quote" }
        while (offset < source.length) {
            when (val character = source[offset++]) {
                '"' -> return source.substring(start, offset)
                '\\' -> {
                    check(offset < source.length) { "Incomplete JSON escape" }
                    when (source[offset++]) {
                        '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                        'u' -> repeat(4) {
                            check(offset < source.length && source[offset].isHexDigit()) {
                                "Invalid JSON unicode escape"
                            }
                            offset++
                        }
                        else -> error("Invalid JSON escape")
                    }
                }
                else -> check(character >= ' ') { "Unescaped control character in JSON string" }
            }
        }
        error("Unterminated JSON string")
    }

    private fun decodeString(raw: String): String =
        KEY_DECODER.parseToJsonElement(raw).jsonPrimitive.content

    private fun scanLiteral(literal: String) {
        check(source.regionMatches(offset, literal, 0, literal.length)) { "Invalid JSON literal" }
        offset += literal.length
    }

    private fun scanNumber() {
        if (consume('-')) check(offset < source.length) { "Incomplete JSON number" }
        if (!consume('0')) {
            check(offset < source.length && source[offset] in '1'..'9') { "Invalid JSON number" }
            while (offset < source.length && source[offset] in '0'..'9') offset++
        }
        if (consume('.')) {
            check(offset < source.length && source[offset] in '0'..'9') {
                "Invalid JSON fraction"
            }
            while (offset < source.length && source[offset] in '0'..'9') offset++
        }
        if (offset < source.length && (source[offset] == 'e' || source[offset] == 'E')) {
            offset++
            if (offset < source.length && (source[offset] == '+' || source[offset] == '-')) offset++
            check(offset < source.length && source[offset] in '0'..'9') {
                "Invalid JSON exponent"
            }
            while (offset < source.length && source[offset] in '0'..'9') offset++
        }
    }

    private fun skipWhitespace() {
        while (offset < source.length && source[offset] in JSON_WHITESPACE) offset++
    }

    private fun current(): Char = source.getOrNull(offset) ?: '\u0000'

    private fun consume(expected: Char): Boolean =
        if (current() == expected) {
            offset++
            true
        } else {
            false
        }

    private companion object {
        // A fixed wire limit turns adversarial/corrupt nesting into a stable decode failure before
        // recursive scanning or the regular JSON parser can exhaust a platform call stack.
        const val MAX_JSON_NESTING_DEPTH = 256
        private val JSON_WHITESPACE = charArrayOf(' ', '\t', '\n', '\r')
        private val KEY_DECODER = Json
    }
}

private fun Char.isHexDigit(): Boolean =
    this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private fun String.isStableMigrationId(): Boolean =
    length in 3..128 &&
        first() in 'a'..'z' &&
        last().isAsciiLowercaseOrDigit() &&
        all { it.isAsciiLowercaseOrDigit() || it == '.' || it == '-' }

private fun Char.isAsciiLowercaseOrDigit(): Boolean = this in 'a'..'z' || this in '0'..'9'

/** Strictly decodes and validates the evolved current document with stable failure semantics. */
internal inline fun <T> EvolvedStoredEnvelope.decodeCurrent(block: () -> T): T = try {
    block()
} catch (known: StoredEnvelopeDecodeException) {
    throw known
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    throw currentDecodeFailure()
}

internal class ValidatedCurrentStoredEnvelope<T>(
    val value: T,
    val canonicalPayload: String,
)

/** Creates rewrite metadata only after strict current-schema decoding has completed successfully. */
internal inline fun <T> EvolvedStoredEnvelope.decodeCurrentResult(
    block: () -> ValidatedCurrentStoredEnvelope<T>,
): StoredEnvelopeDecodeResult<T> {
    val validated = decodeCurrent(block)
    return StoredEnvelopeDecodeResult(
        value = validated.value,
        sourceSchemaVersion = sourceSchemaVersion,
        rewritePayload = validated.canonicalPayload.takeIf { migrated },
    )
}

private fun EvolvedStoredEnvelope.currentDecodeFailure() = StoredEnvelopeDecodeException(
    failure = if (migrated) {
        StoredEnvelopeDecodeFailure.MIGRATION_FAILED
    } else {
        StoredEnvelopeDecodeFailure.CORRUPT
    },
    storedSchemaVersion = sourceSchemaVersion,
    currentSchemaVersion = currentSchemaVersion,
)
