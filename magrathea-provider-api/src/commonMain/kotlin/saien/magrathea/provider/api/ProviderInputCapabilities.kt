package saien.magrathea.provider.api

/**
 * Stable attachment envelope understood by one Provider wire adapter.
 *
 * This describes what the adapter can encode, not what every model behind that adapter accepts.
 * Model-specific capabilities remain a concern of model discovery and product policy.
 */
data class ProviderInputCapabilities(
    val attachmentMimeTypes: Set<String> = emptySet(),
    val attachmentMimeTypePrefixes: Set<String> = emptySet(),
) {
    init {
        require(attachmentMimeTypes.all { it.isCanonicalMimeType() }) {
            "Provider attachment MIME types must be canonical"
        }
        require(attachmentMimeTypePrefixes.all { it.isCanonicalMimeTypePrefix() }) {
            "Provider attachment MIME prefixes must be canonical"
        }
    }

    fun supportsAttachment(mimeType: String): Boolean {
        val normalized = mimeType.normalizedMimeType()
        if (!normalized.isCanonicalMimeType()) return false
        return normalized in attachmentMimeTypes ||
            attachmentMimeTypePrefixes.any(normalized::startsWith)
    }
}

/** Attachment envelopes accepted and normalized by the reference adapters shipped by Magrathea. */
object ReferenceProviderInputCapabilities {
    val geminiInteractions = ProviderInputCapabilities(
        attachmentMimeTypes = setOf(
            "application/csv",
            "application/pdf",
            "audio/aac",
            "audio/aiff",
            "audio/alaw",
            "audio/flac",
            "audio/l16",
            "audio/m4a",
            "audio/mp3",
            "audio/mp4",
            "audio/mpeg",
            "audio/mulaw",
            "audio/ogg",
            "audio/opus",
            "audio/wav",
            "image/bmp",
            "image/gif",
            "image/heic",
            "image/heif",
            "image/jpeg",
            "image/png",
            "image/tiff",
            "image/webp",
            "text/csv",
            "video/3gpp",
            "video/avi",
            "video/mov",
            "video/mp4",
            "video/mpeg",
            "video/mpg",
            "video/quicktime",
            "video/webm",
            "video/wmv",
            "video/x-flv",
        ),
    )

    val openAiResponses = ProviderInputCapabilities(
        attachmentMimeTypes = setOf(
            "application/csv",
            "application/json",
            "application/msword",
            "application/pdf",
            "application/rtf",
            "application/vnd.ms-excel",
            "application/vnd.ms-powerpoint",
            "application/vnd.oasis.opendocument.presentation",
            "application/vnd.oasis.opendocument.spreadsheet",
            "application/vnd.oasis.opendocument.text",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "image/gif",
            "image/jpeg",
            "image/png",
            "image/webp",
        ),
        attachmentMimeTypePrefixes = setOf("text/"),
    )

    /** Portable attachment subset of OpenAI-compatible Chat Completions services. */
    val openAiChatCompletions = ProviderInputCapabilities(
        attachmentMimeTypes = setOf(
            "image/gif",
            "image/jpeg",
            "image/png",
            "image/webp",
        ),
    )

    val anthropicMessages = ProviderInputCapabilities(
        attachmentMimeTypes = setOf(
            "application/pdf",
            "image/gif",
            "image/jpeg",
            "image/png",
            "image/webp",
        ),
    )
}

private fun String.normalizedMimeType(): String = substringBefore(';').trim().lowercase()

private fun String.isCanonicalMimeType(): Boolean =
    isNotEmpty() &&
        this == normalizedMimeType() &&
        count { it == '/' } == 1 &&
        substringBefore('/').isNotEmpty() &&
        substringAfter('/').isNotEmpty() &&
        none(Char::isWhitespace)

private fun String.isCanonicalMimeTypePrefix(): Boolean =
    isNotEmpty() &&
        this == lowercase() &&
        endsWith('/') &&
        dropLast(1).isNotEmpty() &&
        dropLast(1).none(Char::isWhitespace) &&
        dropLast(1).none { it == '/' }
