package io.github.choffmann.chatwsandroid.model

import kotlinx.serialization.Serializable
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonObject
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.toInstant
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Representation of a chat participant.
 * Optional fields ([firstName], [lastName], [additionalInfo]) are populated
 * when the user was created via the server's REST API (POST /users).
 */
@Serializable
data class User(
    val id: String,
    val name: String,
    val firstName: String? = null,
    val lastName: String? = null,
    val additionalInfo: JsonObject? = null,
)

/**
 * Serializer that encodes a [LocalDateTime] as an ISO-8601 UTC instant string.
 * This matches the payload emitted by the backend.
 */
@OptIn(ExperimentalTime::class)
object UtcLocalDateTimeAsInstantString : KSerializer<LocalDateTime> {
    override val descriptor = PrimitiveSerialDescriptor("UtcLocalDateTime", PrimitiveKind.STRING)


    override fun deserialize(decoder: Decoder): LocalDateTime {
        val s = decoder.decodeString()
        return Instant.parse(s).toLocalDateTime(TimeZone.UTC)
    }


    override fun serialize(encoder: Encoder, value: LocalDateTime) {
        val iso = value.toInstant(TimeZone.UTC).toString()
        encoder.encodeString(iso)
    }
}

/**
 * Flexible message type that supports both well-known types and custom strings.
 * The server accepts any string as type — use the predefined constants or create your own.
 */
@Serializable(with = MessageTypeSerializer::class)
@JvmInline
value class MessageType(val value: String) {
    companion object {
        val SYSTEM = MessageType("system")
        val MESSAGE = MessageType("message")
        val IMAGE = MessageType("image")
        val FILE = MessageType("file")
    }
}

object MessageTypeSerializer : KSerializer<MessageType> {
    override val descriptor = PrimitiveSerialDescriptor("MessageType", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder) = MessageType(decoder.decodeString())
    override fun serialize(encoder: Encoder, value: MessageType) = encoder.encodeString(value.value)
}

/**
 * Domain model describing a chat message received from the server.
 *
 * @property type Message type — can be any string, see [MessageType] constants.
 * @property message Message content (text, or file URL for binary uploads).
 * @property timestamp Timestamp supplied by the backend in UTC.
 * @property user Sender information.
 * @property additionalInfo Optional metadata (e.g., contentType, size, fileName for uploads).
 */
@Serializable
data class Message(
    val id: String,
    val type: MessageType,
    val message: String,
    @Serializable(with = UtcLocalDateTimeAsInstantString::class)
    val timestamp: LocalDateTime,
    val user: User,
    val additionalInfo: JsonObject? = null
)

/**
 * Model for outgoing messages sent to the server.
 *
 * @property type Message type — defaults to [MessageType.MESSAGE] if omitted by the server.
 * @property message The message content.
 * @property additionalInfo Optional metadata that will be broadcast to all participants.
 */
@Serializable
data class OutgoingMessage(
    val type: MessageType,
    val message: String,
    val additionalInfo: JsonObject? = null
)
