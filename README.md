# Chat WS Android

Lightweight Kotlin library that connects Android clients to the [Chat websocket backend](https://github.com/choffmann/chat-room). It provides a simple `ChatWsClient` for websocket communication, along with serializable models that match the server payloads.

## Gradle Setup

```kotlin
// build.gradle.kts of the consuming module
repositories {
    mavenCentral()
}

dependencies {
    implementation("io.github.choffmann:chat-ws-android:1.0.0")
}
```

Snapshots (if published) are available from Sonatype:

```kotlin
maven {
    name = "Central Portal Snapshots"
    url = uri("https://central.sonatype.com/repository/maven-snapshots/")

    // Only search this repository for the specific dependency
    content {
        includeModule("io.github.choffmann", "chat-ws-android")
    }
}
```

## Quick Start

```kotlin
class ChatViewModel(
    private val client: ChatWsClient = ChatWsClient()
) : ViewModel() {
    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    val currentUser: StateFlow<User?> = client.currentUser
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    init {
        client.incomingMessages
            .onEach { incoming -> _messages.update { it + incoming } }
            .launchIn(viewModelScope)
    }

    fun connect(roomId: Int, userName: String) {
        client.joinRoom(roomId, userName)
    }

    fun send(text: String) = viewModelScope.launch {
        client.sendMessage(text)
    }

    fun sendFile(data: ByteArray) = viewModelScope.launch {
        client.sendBinary(data)
    }

    override fun onCleared() {
        client.close()
        super.onCleared()
    }
}
```

### Joining Rooms: userName vs userId

The library supports three ways to join a room:

1. **With `userName` only**: Creates a new ephemeral user with the specified name

   ```kotlin
   client.joinRoom(roomId = 1, userName = "Alice")
   ```

   - Server creates a new user with a fresh UUID
   - User exists only for this session

2. **With `userId` only**: Uses an existing registered user from the server

   ```kotlin
   client.joinRoom(roomId = 1, userId = "existing-uuid-here")
   ```

   - Server looks up the user in its registry
   - Uses stored name and properties
   - Returns 404 if user not found

3. **Anonymous**: Let the server assign a random name

   ```kotlin
   client.joinRoom(roomId = 1)
   ```

   - Server picks a random humorous name (e.g., "Kotlin Kevin", "Gradle Gero")

**Important:** If you provide both `userName` and `userId`, only `userId` is used and `userName` is ignored. The `userId` parameter always takes precedence.

### Configuration

Pass a custom `ChatWsConfig` if you need to point to a self-hosted backend:

```kotlin
val client = ChatWsClient(
    config = ChatWsConfig(
        baseWsUrl = "wss://my-chat.example.com",
        enableLogging = false
    )
)
```

### Getting Current User Information

After successfully joining a room, you can access the current user's information (including server-assigned names for anonymous joins):

```kotlin
client.currentUser.collectLatest { user ->
    if (user != null) {
        println("Connected as: ${user.name} (ID: ${user.id})")
    } else {
        println("Not connected")
    }
}
```

This is especially useful when joining anonymously, as the server assigns a random name that you'll want to display in your UI.

**Note:** The library automatically sets `userInfo=true` when joining a room. The server's self-join message is intercepted to populate `currentUser` and is not forwarded to `incomingMessages`.

### Connection State

Subscribe to `client.connectionState` to reflect websocket status in your UI:

```kotlin
client.connectionState.collectLatest { state ->
    when (state) {
        is ConnectionState.Idle -> // Not connected
        is ConnectionState.Connecting -> // Connection in progress or reconnecting
        is ConnectionState.Connected -> // Ready to send messages
        is ConnectionState.Disconnected -> // Connection lost, check state.cause
    }
}
```

The client automatically reconnects with exponential backoff (up to 10 seconds) when the connection drops. During reconnection the state cycles through `Disconnected` → `Connecting` → `Connected`.

## Message Types

The server accepts any string as message type. The library provides predefined constants and supports custom types:

```kotlin
// Predefined types
MessageType.MESSAGE  // "message" (default)
MessageType.IMAGE    // "image"
MessageType.FILE     // "file"
MessageType.SYSTEM   // "system" (server-only)

// Custom types
MessageType("poll")
MessageType("reaction")
MessageType("ticket")
```

## Sending Messages

### Text Messages

```kotlin
// Simple text message (type defaults to MessageType.MESSAGE)
client.sendMessage("Hello World")

// Custom message type
client.sendMessage(
    message = "What's for lunch?",
    type = MessageType("poll"),
    additionalInfo = buildJsonObject {
        put("options", buildJsonArray {
            add("Pizza")
            add("Sushi")
        })
    }
)
```

### Binary Uploads (Images & Files)

Files are sent as raw WebSocket binary frames. The server auto-detects the MIME type, saves the file, and broadcasts a message with the download URL to all participants.

```kotlin
// Send an image or file (max 10 MiB)
client.sendBinary(imageBytes)
```

The server responds with a broadcast message like:

```json
{
  "id": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "type": "image",
  "message": "https://chat.example.com/uploads/1/a1b2c3d4.png",
  "timestamp": "2024-04-09T12:35:10.123456789Z",
  "user": { "id": "...", "name": "Alice" },
  "additionalInfo": {
    "contentType": "image/png",
    "size": 204800,
    "fileName": "a1b2c3d4.png"
  }
}
```

## Receiving Messages

```kotlin
client.incomingMessages
    .onEach { message ->
        when (message.type) {
            MessageType.MESSAGE -> {
                println("${message.user.name}: ${message.message}")
            }
            MessageType.IMAGE -> {
                // message.message contains the download URL
                val url = message.message
                val contentType = message.additionalInfo?.get("contentType")
                    ?.jsonPrimitive?.contentOrNull
            }
            MessageType.FILE -> {
                val url = message.message
                val fileName = message.additionalInfo?.get("fileName")
                    ?.jsonPrimitive?.contentOrNull
            }
            MessageType.SYSTEM -> {
                println("System: ${message.message}")
            }
            else -> {
                // Custom message type
                println("Custom [${message.type.value}]: ${message.message}")
            }
        }
    }
    .launchIn(viewModelScope)
```

Every incoming `Message` includes an `id` (UUID) that can be used to reference messages for editing or deletion via the server's REST API.

## Lifecycle

| Method | Purpose |
|---|---|
| `joinRoom(...)` | Connect (cancels any previous connection). Auto-reconnects on drop. |
| `disconnect()` | Stop connection and auto-reconnect. Client can be reused via `joinRoom`. |
| `close()` | Release all resources (HTTP client, coroutine scope). Instance is no longer usable. |

Typical usage in a ViewModel:

```kotlin
override fun onCleared() {
    client.close()
    super.onCleared()
}
```

## Development

- Build and lint: `./gradlew clean lint`
- Check publishing locally: `./gradlew publishToMavenLocal`

### Required Environment Variables / gradle.properties

```
mavenCentralUsername=<sonatype-username>
mavenCentralPassword=<sonatype-password>

signing.keyId=<last 7 digits from GPG public key>
signing.password=<pgp-passphrase>
signing.secretKeyRingFile=<path to GPG keyring file>
```

Alternatively export them as environment variables (`ORG_GRADLE_PROJECT_mavenCentralUsername`, `ORG_GRADLE_PROJECT_mavenCentralPassword`, `ORG_GRADLE_PROJECT_signingInMemoryKey`, `ORG_GRADLE_PROJECT_signingInMemoryKeyId`, `ORG_GRADLE_PROJECT_signingInMemoryKeyPassword`) for CI.

## Releasing

1. Bump the version in `build.gradle.kts`.
2. Commit and tag the release (`git tag v1.0.0 && git push --tags`).
3. (Optional) Run `./gradlew publishToMavenCentral` locally.
4. Use GitHub release or manual workflow dispatch to trigger `.github/workflows/publish.yml`.
5. In Sonatype Central, close and release the staged repository so it syncs to Maven Central.
