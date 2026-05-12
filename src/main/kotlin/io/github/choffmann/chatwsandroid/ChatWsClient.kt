package io.github.choffmann.chatwsandroid

import io.github.choffmann.chatwsandroid.model.*
import io.github.choffmann.chatwsandroid.net.AppJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration for the websocket client used by [ChatWsClient].
 *
 * @property baseWsUrl Base URL of the chat websocket backend.
 * @property enableLogging When `true`, enables Ktor's verbose logging for easier debugging.
 */
data class ChatWsConfig(
    val baseWsUrl: String = "wss://chat.homebin.dev/api/v1",
    val enableLogging: Boolean = true
)

/**
 * Websocket client for connecting to the ChatWS backend via Ktor.
 * Performs automatic reconnection with exponential backoff and exposes state via Kotlin flows.
 *
 * Call [joinRoom] to connect — the client will automatically reconnect on connection loss.
 * Call [disconnect] to stop the connection (allows re-joining later).
 * Call [close] when the client is no longer needed to release all resources.
 */
class ChatWsClient(
    private val config: ChatWsConfig = ChatWsConfig(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(AppJson) }
        if (config.enableLogging) install(Logging) { level = LogLevel.ALL }
        install(WebSockets)
    }

    private val sessionMutex = Mutex()
    private var session: WebSocketSession? = null
    private var connectJob: Job? = null

    private val _incomingMessages = MutableSharedFlow<Message>(
        replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** Stream of [Message] instances received from the websocket connection. */
    val incomingMessages: Flow<Message> = _incomingMessages.asSharedFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)

    /** Stream that reports the current [ConnectionState] of the client. */
    val connectionState: Flow<ConnectionState> = _connectionState.asStateFlow()

    private val _currentUser = MutableStateFlow<User?>(null)

    /**
     * The current user as assigned by the server on join.
     * Reset on [disconnect] and updated on each (re)connect.
     */
    val currentUser: Flow<User?> = _currentUser.asStateFlow()

    /**
     * Opens the websocket connection and starts automatic reconnection.
     * Cancels any previous connection attempt before starting a new one.
     *
     * @param roomID Identifier of the room to join.
     * @param userName Optional username for creating a new ephemeral user (ignored if userId is provided).
     * @param userId Optional user ID for an existing registered user (takes precedence over userName).
     */
    fun joinRoom(roomID: Int, userName: String? = null, userId: String? = null) {
        connectJob?.cancel()
        connectJob = scope.launch {
            _connectionState.emit(ConnectionState.Connecting)
            var attempt = 0
            while (isActive) {
                try {
                    val wsUrl = buildWsUrl(roomID, userName, userId)
                    val newSession = client.webSocketSession(urlString = wsUrl)
                    sessionMutex.withLock { session = newSession }
                    attempt = 0
                    _connectionState.emit(ConnectionState.Connected)
                    readLoop(newSession)
                    // readLoop returned = connection lost, reconnect
                    sessionMutex.withLock { session = null }
                    ensureActive()
                    _connectionState.emit(ConnectionState.Connecting)
                } catch (e: CancellationException) {
                    sessionMutex.withLock {
                        runCatching { session?.close(CloseReason(CloseReason.Codes.NORMAL, "Client closed")) }
                        session = null
                    }
                    throw e
                } catch (t: Throwable) {
                    sessionMutex.withLock { session = null }
                    ensureActive()
                    attempt++
                    _connectionState.emit(ConnectionState.Disconnected(t))
                    val backoff = (1 shl (attempt - 1)).coerceAtMost(10)
                    delay(backoff.seconds)
                    _connectionState.emit(ConnectionState.Connecting)
                }
            }
        }
    }

    private fun buildWsUrl(roomID: Int, userName: String?, userId: String?): String = buildString {
        append("${config.baseWsUrl}/join/$roomID")
        val params = mutableListOf<String>()
        if (!userName.isNullOrEmpty()) params.add("userName=$userName")
        if (!userId.isNullOrEmpty()) params.add("userId=$userId")
        params.add("userInfo=true")
        append("?${params.joinToString("&")}")
    }

    private suspend fun readLoop(s: WebSocketSession) {
        try {
            for (frame in s.incoming) {
                when (frame) {
                    is Frame.Text -> {
                        val text = frame.readText()
                        runCatching { AppJson.decodeFromString<Message>(text) }
                            .onSuccess { message ->
                                val info = message.additionalInfo
                                val isSelf = info?.get("self")?.jsonPrimitive?.booleanOrNull == true

                                if (isSelf) {
                                    info["joinedUser"]
                                        ?.let { runCatching { AppJson.decodeFromJsonElement<User>(it) }.getOrNull() }
                                        ?.let { _currentUser.emit(it) }
                                } else {
                                    _incomingMessages.tryEmit(message)
                                }
                            }
                    }
                    is Frame.Close -> break
                    else -> Unit
                }
            }
        } catch (_: ClosedReceiveChannelException) {
            // Connection closed by peer
        }
    }

    /**
     * Sends a text message to the room.
     *
     * @param message UTF-8 text payload.
     * @param type Message type — defaults to [MessageType.MESSAGE]. Use any custom string via `MessageType("yourType")`.
     * @param additionalInfo Optional metadata that will be broadcast to all participants.
     * @return `true` if the frame was sent, `false` when no active connection is available or sending failed.
     */
    suspend fun sendMessage(
        message: String,
        type: MessageType = MessageType.MESSAGE,
        additionalInfo: JsonObject? = null
    ): Boolean {
        val s = sessionMutex.withLock { session } ?: return false
        return try {
            val outgoingMessage = OutgoingMessage(type = type, message = message, additionalInfo = additionalInfo)
            s.send(Frame.Text(AppJson.encodeToString(outgoingMessage)))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Sends raw binary data as a WebSocket binary frame.
     * The server detects the MIME type, saves the file, and broadcasts a message
     * with type "image" or "file" containing the download URL.
     *
     * @param data Raw bytes of the file to upload (max 10 MiB).
     * @return `true` if the frame was sent, `false` when no active connection is available or sending failed.
     */
    suspend fun sendBinary(data: ByteArray): Boolean {
        val s = sessionMutex.withLock { session } ?: return false
        return try {
            s.send(Frame.Binary(true, data))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Gracefully stops the connection and auto-reconnect loop.
     * The client can be reused by calling [joinRoom] again.
     */
    suspend fun disconnect() {
        connectJob?.cancelAndJoin()
        connectJob = null
        _connectionState.emit(ConnectionState.Disconnected(null))
        _currentUser.emit(null)
    }

    /**
     * Releases all resources (HTTP client, coroutine scope).
     * The instance is no longer usable after this call.
     */
    fun close() {
        connectJob?.cancel()
        client.close()
        scope.cancel()
    }
}
