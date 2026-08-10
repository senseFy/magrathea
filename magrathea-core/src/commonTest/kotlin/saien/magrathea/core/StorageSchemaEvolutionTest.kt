package saien.magrathea.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class StorageSchemaEvolutionTest {
    @Test
    fun appliesEveryAdjacentMigrationInOrderAndOwnsVersionAdvancement() {
        val evolution = StorageSchemaEvolution(
            minimumReadableVersion = 6,
            currentVersion = 8,
            migrations = listOf(
                migration(6, "six-to-seven"),
                migration(7, "seven-to-eight"),
            ),
        )

        val evolved = evolution.evolve(
            """{"schemaVersion":6,"steps":[]}""",
            Json,
        )

        assertEquals(6, evolved.sourceSchemaVersion)
        assertEquals(8, evolved.currentSchemaVersion)
        assertEquals(8, evolved.document.getValue("schemaVersion").jsonPrimitive.content.toInt())
        assertEquals(
            listOf("six-to-seven", "seven-to-eight"),
            evolved.document.getValue("steps").jsonArray.map { it.jsonPrimitive.content },
        )

        val result = evolved.decodeCurrentResult {
            ValidatedCurrentStoredEnvelope("decoded", "canonical-decoded")
        }
        assertEquals("decoded", result.value)
        assertEquals(6, result.sourceSchemaVersion)
        assertEquals("canonical-decoded", result.rewritePayload)
    }

    @Test
    fun currentDecodeResultDoesNotRequestAnUnnecessaryRewrite() {
        val evolved = StorageSchemaEvolution(
            minimumReadableVersion = 6,
            currentVersion = 6,
            migrations = emptyList(),
        ).evolve("""{"schemaVersion":6}""", Json)

        val result = evolved.decodeCurrentResult {
            ValidatedCurrentStoredEnvelope("decoded", "canonical-decoded")
        }

        assertEquals("decoded", result.value)
        assertEquals(6, result.sourceSchemaVersion)
        assertNull(result.rewritePayload)
    }

    @Test
    fun rejectsMigrationRegistriesWithGapsDuplicatesOrUnexpectedSteps() {
        assertFailsWith<IllegalArgumentException> {
            StorageSchemaEvolution(
                minimumReadableVersion = 6,
                currentVersion = 8,
                migrations = listOf(migration(6, "only-one-step")),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            StorageSchemaEvolution(
                minimumReadableVersion = 6,
                currentVersion = 7,
                migrations = listOf(
                    migration(6, "first"),
                    migration(6, "duplicate"),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            StorageSchemaEvolution(
                minimumReadableVersion = 6,
                currentVersion = 7,
                migrations = listOf(migration(7, "wrong-source")),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            StorageSchemaEvolution(
                minimumReadableVersion = 6,
                currentVersion = 8,
                migrations = listOf(
                    migration(6, "first-id", id = "duplicate-id"),
                    migration(7, "second-id", id = "duplicate-id"),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            StorageSchemaEvolution(
                minimumReadableVersion = 6,
                currentVersion = 7,
                migrations = listOf(migration(6, "invalid-id", id = "INVALID_ID")),
            )
        }
    }

    @Test
    fun migrationFailureIsClassifiedWithoutMutatingTheInputDocument() {
        val input = Json.parseToJsonElement("""{"schemaVersion":6,"value":"original"}""")
            as JsonObject
        val evolution = StorageSchemaEvolution(
            minimumReadableVersion = 6,
            currentVersion = 7,
            migrations = listOf(
                RegisteredStorageSchemaMigration(
                    id = "test-failure",
                    fromVersion = 6,
                    migration = adjacentMigration(
                        migrate = { error("synthetic migration failure") },
                    ),
                ),
            ),
        )

        val failure = assertFailsWith<StoredEnvelopeDecodeException> {
            evolution.evolve(input)
        }

        assertEquals(StoredEnvelopeDecodeFailure.MIGRATION_FAILED, failure.failure)
        assertEquals(6, failure.storedSchemaVersion)
        assertEquals(6, input.getValue("schemaVersion").jsonPrimitive.content.toInt())
        assertEquals("original", input.getValue("value").jsonPrimitive.content)
        assertNull(failure.cause)
    }

    @Test
    fun rejectsInvalidSourceBeforeRunningARepairingMigration() {
        var migrationRan = false
        val evolution = StorageSchemaEvolution(
            minimumReadableVersion = 6,
            currentVersion = 7,
            migrations = listOf(
                RegisteredStorageSchemaMigration(
                    id = "validate-before-transform",
                    fromVersion = 6,
                    migration = adjacentMigration(
                        validateSource = { document ->
                            require(document["required"] == JsonPrimitive("valid"))
                        },
                        migrate = { document ->
                            migrationRan = true
                            JsonObject(
                                document.toMutableMap().apply {
                                    put("required", JsonPrimitive("valid"))
                                },
                            )
                        },
                    ),
                ),
            ),
        )

        val failure = assertFailsWith<StoredEnvelopeDecodeException> {
            evolution.evolve("""{"schemaVersion":6}""", Json)
        }

        assertEquals(StoredEnvelopeDecodeFailure.MIGRATION_FAILED, failure.failure)
        assertEquals(6, failure.storedSchemaVersion)
        assertEquals(false, migrationRan)
        assertNull(failure.cause)
    }

    @Test
    fun rejectsDuplicateKeysAtRootAndNestedObjectDepthsBeforeVersionDispatch() {
        val evolution = StorageSchemaEvolution(
            minimumReadableVersion = 6,
            currentVersion = 6,
            migrations = emptyList(),
        )
        val ambiguousPayloads = listOf(
            """{"schemaVersion":5,"schemaVersion":6}""",
            """{"schemaVersion":6,"payload":{"value":1,"value":2}}""",
            """{"schemaVersion":6,"payload":{"name":1,"\u006eame":2}}""",
        )

        ambiguousPayloads.forEach { payload ->
            val failure = assertFailsWith<StoredEnvelopeDecodeException> {
                evolution.evolve(payload, Json)
            }
            assertEquals(StoredEnvelopeDecodeFailure.CORRUPT, failure.failure)
            assertNull(failure.storedSchemaVersion)
            assertNull(failure.cause)
        }
    }

    @Test
    fun excessiveObjectAndArrayNestingIsClassifiedAsPayloadFreeCorruption() {
        val evolution = StorageSchemaEvolution(
            minimumReadableVersion = 6,
            currentVersion = 6,
            migrations = emptyList(),
        )
        val nestedArrays =
            """{"schemaVersion":6,"payload":""" + "[".repeat(300) + "0" + "]".repeat(300) + "}"
        val nestedObjects =
            """{"schemaVersion":6,"payload":""" + """{"value":""".repeat(300) + "0" + "}".repeat(301)

        listOf(nestedArrays, nestedObjects).forEach { payload ->
            val failure = assertFailsWith<StoredEnvelopeDecodeException> {
                evolution.evolve(payload, Json)
            }

            assertEquals(StoredEnvelopeDecodeFailure.CORRUPT, failure.failure)
            assertNull(failure.storedSchemaVersion)
            assertNull(failure.cause)
        }
    }

    @Test
    fun invalidStrictDecodeAfterMigrationIsMigrationFailure() {
        val evolved = StorageSchemaEvolution(
            minimumReadableVersion = 6,
            currentVersion = 7,
            migrations = listOf(migration(6, "six-to-seven")),
        ).evolve("""{"schemaVersion":6,"steps":[]}""", Json)

        val failure = assertFailsWith<StoredEnvelopeDecodeException> {
            evolved.decodeCurrent<Unit> {
                error("synthetic invalid current DTO")
            }
        }

        assertEquals(StoredEnvelopeDecodeFailure.MIGRATION_FAILED, failure.failure)
        assertEquals(6, failure.storedSchemaVersion)
        assertNull(failure.cause)
    }

    private fun migration(
        fromVersion: Int,
        label: String,
        id: String = "test-$label",
    ) = RegisteredStorageSchemaMigration(
        id = id,
        fromVersion = fromVersion,
        migration = adjacentMigration(
            validateSource = { document ->
                require(
                    document.getValue("schemaVersion").jsonPrimitive.content.toInt() == fromVersion,
                )
            },
            migrate = { document ->
                val existing = document["steps"]?.jsonArray?.toList().orEmpty()
                JsonObject(
                    document.toMutableMap().apply {
                        put("steps", JsonArray(existing + JsonPrimitive(label)))
                        // A migration cannot choose or skip the next version; the engine overwrites it.
                        put("schemaVersion", JsonPrimitive(999))
                    },
                )
            },
        ),
    )

    private fun adjacentMigration(
        validateSource: (JsonObject) -> Unit = {},
        migrate: (JsonObject) -> JsonObject,
    ): AdjacentStorageSchemaMigration = object : AdjacentStorageSchemaMigration {
        override fun validateSource(document: JsonObject) = validateSource.invoke(document)

        override fun migrate(document: JsonObject): JsonObject = migrate.invoke(document)
    }
}
