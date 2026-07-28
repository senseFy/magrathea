package saien.magrathea.provider.gemini

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import saien.magrathea.core.AttachmentPart
import saien.magrathea.core.dataUrlPayload
import saien.magrathea.core.normalizedMimeType
import saien.magrathea.provider.api.ProviderProtocolException
import saien.magrathea.provider.api.ReferenceProviderInputCapabilities

internal object GeminiAttachmentCodec {
    fun encode(attachment: AttachmentPart): JsonObject {
        val dataUrl = attachment.dataUrlPayload()
        if (attachment.uri.startsWith("data:", ignoreCase = true) && dataUrl == null) {
            throw ProviderProtocolException("Gemini attachment data URL must contain base64 data")
        }
        if (dataUrl == null && attachment.uri.isBlank()) {
            throw ProviderProtocolException("Gemini attachment URI must not be blank")
        }

        val declaredMime = attachment.normalizedMimeType()
        val declaredWireMime = declaredMime.takeIf(String::isNotBlank)?.toGeminiWireMimeType()
        val dataWireMime = dataUrl?.mediaType?.toGeminiWireMimeType()
        if (declaredWireMime == null && dataWireMime == null) {
            throw ProviderProtocolException("Gemini attachment requires a supported MIME type")
        }
        if (declaredWireMime != null && dataWireMime != null && declaredWireMime != dataWireMime) {
            throw ProviderProtocolException("Gemini attachment MIME type does not match its data URL")
        }
        val wireMime = declaredWireMime ?: requireNotNull(dataWireMime)
        val contentType = when {
            wireMime.startsWith("image/") -> "image"
            wireMime.startsWith("audio/") -> "audio"
            wireMime.startsWith("video/") -> "video"
            wireMime == "application/pdf" || wireMime == "text/csv" -> "document"
            else -> throw ProviderProtocolException("Gemini attachment MIME type is not supported")
        }

        return buildJsonObject {
            put("type", contentType)
            put("mime_type", wireMime)
            if (dataUrl != null) put("data", dataUrl.data) else put("uri", attachment.uri)
        }
    }
}

private fun String.toGeminiWireMimeType(): String {
    if (!ReferenceProviderInputCapabilities.geminiInteractions.supportsAttachment(this)) {
        throw ProviderProtocolException("Gemini attachment MIME type is not supported")
    }
    return when (this) {
        "application/csv" -> "text/csv"
        "audio/mp4" -> "audio/m4a"
        "video/quicktime" -> "video/mov"
        else -> this
    }
}
