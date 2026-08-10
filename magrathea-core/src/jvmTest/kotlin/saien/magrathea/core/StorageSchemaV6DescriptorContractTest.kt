package saien.magrathea.core

import java.security.MessageDigest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalSerializationApi::class)
class StorageSchemaV6DescriptorContractTest {
    @Test
    fun livePayloadSerializersMatchFrozenV6DescriptorFingerprint() {
        val schema = buildString {
            append("storage-schema-v6\n")
            append("session\n")
            appendDescriptor(AgentSessionSnapshot.serializer().descriptor, mutableSetOf())
            append("checkpoint\n")
            appendDescriptor(AgentCheckpoint.serializer().descriptor, mutableSetOf())
        }
        val expected = requireNotNull(
            javaClass.getResource("/v6/core/storage-schema-descriptor.sha256"),
        ).readText().trim()

        assertEquals(expected, schema.sha256())
    }

    private fun StringBuilder.appendDescriptor(
        descriptor: SerialDescriptor,
        activeDescriptors: MutableSet<String>,
    ) {
        val identity = buildString {
            append(descriptor.serialName)
            append('|')
            append(descriptor.kind)
            append('|')
            append(descriptor.isNullable)
        }
        append("descriptor|")
        appendToken(descriptor.serialName)
        append('|')
        appendToken(descriptor.kind.toString())
        append("|nullable=")
        append(descriptor.isNullable)
        append("|inline=")
        append(descriptor.isInline)
        append("|elements=")
        append(descriptor.elementsCount)
        if (!activeDescriptors.add(identity)) {
            append("|recursive\n")
            return
        }
        append('\n')
        try {
            repeat(descriptor.elementsCount) { index ->
                append("element|")
                append(index)
                append('|')
                appendToken(descriptor.getElementName(index))
                append("|optional=")
                append(descriptor.isElementOptional(index))
                append('\n')
                appendDescriptor(descriptor.getElementDescriptor(index), activeDescriptors)
            }
        } finally {
            activeDescriptors.remove(identity)
        }
        append("end|")
        appendToken(descriptor.serialName)
        append('\n')
    }

    private fun StringBuilder.appendToken(value: String) {
        append(value.length)
        append(':')
        append(value)
    }

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
