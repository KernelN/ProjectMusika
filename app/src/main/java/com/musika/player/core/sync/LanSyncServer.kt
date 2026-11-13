package com.musika.player.core.sync

import android.content.Context
import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.defaultForFilePath
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.time.Duration.Companion.seconds

/**
 * Embedded LAN HTTP + WebSocket Server running on Android (Ktor CIO on port 8765):
 * - Serves `assets/web/` static PWA bundle over `http://<phone-ip>:8765/` (mixed-content free over LAN!)
 * - Exposes `/api/discovery`, `/api/library`, `/api/pair/pin` (6-digit offline PIN), and `/stream?path=...`
 *   (strictly gated behind Settings `allowLanStreamingForMissingTracks` = true + canonical traversal checks)
 * - Hosts RFC 6455 WebSocket `/ws` with HMAC-SHA256 challenge-response verification.
 */
class LanSyncServer(
    private val appContext: Context?,
    private val deviceId: String,
    private val deviceNameProvider: () -> String,
    private val accountIdProvider: () -> String,
    private val usernameProvider: () -> String,
    private val pairSecretProvider: () -> String,
    private val pinCodeProvider: () -> String,
    private val supabaseUrlProvider: () -> String,
    private val supabaseAnonKeyProvider: () -> String,
    private val rootPathProvider: () -> String,
    private val allowStreamingProvider: () -> Boolean,
    private val catalogProvider: () -> List<RemoteSyncContract.RemoteLibraryTrackSummary>,
    private val snapshotProvider: () -> RemoteSyncContract.PlaybackSnapshot,
    private val onIncomingEnvelope: (RemoteSyncContract.SyncWireEnvelope) -> Unit
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var engine: ApplicationEngine? = null
    private val authenticatedPeers = CopyOnWriteArraySet<DefaultWebSocketServerSession>()

    @Volatile
    var actualPort: Int = DEFAULT_PORT
        private set

    @Serializable
    private data class DiscoveryResponse(
        val version: String,
        val deviceId: String,
        val deviceName: String,
        val deviceType: String,
        val accountHash: String,
        val port: Int
    )

    @Serializable
    private data class LibraryResponse(
        val rootPath: String,
        val tracks: List<RemoteSyncContract.RemoteLibraryTrackSummary>
    )

    @Serializable
    private data class PinPairRequest(
        val pinCode: String = "",
        val deviceId: String = "",
        val deviceName: String = ""
    )

    @Serializable
    private data class ErrorResponse(val error: String)

    fun start(preferredPort: Int = DEFAULT_PORT, host: String = "0.0.0.0"): Int {
        if (engine != null) return actualPort
        val portsToTry = if (preferredPort == 0) listOf(0) else listOf(preferredPort, 0)
        for (portCandidate in portsToTry) {
            try {
                val created = embeddedServer(CIO, port = portCandidate, host = host) {
                    install(AutoHeadResponse)
                    install(PartialContent)
                    install(WebSockets) {
                        pingPeriodMillis = 15_000L
                        timeoutMillis = 30_000L
                        maxFrameSize = Long.MAX_VALUE
                        masking = false
                    }

                    routing {
                        get("/api/discovery") {
                            val resp = DiscoveryResponse(
                                version = RemoteSyncContract.PROTOCOL_VERSION,
                                deviceId = deviceId,
                                deviceName = deviceNameProvider(),
                                deviceType = "ANDROID",
                                accountHash = RemoteSyncContract.shortAccountHash(accountIdProvider()),
                                port = actualPort
                            )
                            call.respondText(
                                json.encodeToString(resp),
                                ContentType.Application.Json,
                                HttpStatusCode.OK
                            )
                        }

                        get("/api/library") {
                            val resp = LibraryResponse(
                                rootPath = rootPathProvider(),
                                tracks = catalogProvider()
                            )
                            call.respondText(
                                json.encodeToString(resp),
                                ContentType.Application.Json,
                                HttpStatusCode.OK
                            )
                        }

                        post("/api/pair/pin") {
                            val rawBody = call.receiveText()
                            val req = runCatching { json.decodeFromString<PinPairRequest>(rawBody) }
                                .getOrNull()
                            val expectedPin = pinCodeProvider().trim()
                            if (req == null || expectedPin.isBlank() || req.pinCode.trim() != expectedPin) {
                                call.respondText(
                                    json.encodeToString(ErrorResponse("Invalid or expired pairing PIN.")),
                                    ContentType.Application.Json,
                                    HttpStatusCode.Forbidden
                                )
                                return@post
                            }
                            val payload = RemoteSyncContract.QrPairPayload(
                                version = RemoteSyncContract.PROTOCOL_VERSION,
                                accountId = accountIdProvider(),
                                username = usernameProvider(),
                                pairSecretHmac = pairSecretProvider(),
                                pinCode = expectedPin,
                                lanHost = getLocalIpv4Address(),
                                lanPort = actualPort,
                                supabaseUrl = supabaseUrlProvider(),
                                supabaseAnonKey = supabaseAnonKeyProvider(),
                                issuedAtMs = System.currentTimeMillis()
                            )
                            call.respondText(
                                json.encodeToString(payload),
                                ContentType.Application.Json,
                                HttpStatusCode.OK
                            )
                        }

                        get("/stream") {
                            if (!allowStreamingProvider()) {
                                call.respondText(
                                    json.encodeToString(
                                        ErrorResponse("LAN audio streaming for missing tracks is disabled in Settings.")
                                    ),
                                    ContentType.Application.Json,
                                    HttpStatusCode.Forbidden
                                )
                                return@get
                            }
                            val rel = call.request.queryParameters["path"].orEmpty().trim()
                            val rootStr = rootPathProvider()
                            if (rel.isBlank() || rootStr.isBlank()) {
                                call.respondText(
                                    json.encodeToString(ErrorResponse("Missing track path parameter.")),
                                    ContentType.Application.Json,
                                    HttpStatusCode.BadRequest
                                )
                                return@get
                            }
                            val rootDir = File(rootStr).canonicalFile
                            val candidate = File(rootDir, rel.replace('\\', '/').trimStart('/')).canonicalFile
                            val rootPrefix = rootDir.path.trimEnd(File.separatorChar) + File.separatorChar
                            if (!candidate.path.startsWith(rootPrefix)) {
                                call.respondText(
                                    json.encodeToString(ErrorResponse("Path traversal denied.")),
                                    ContentType.Application.Json,
                                    HttpStatusCode.Forbidden
                                )
                                return@get
                            }
                            val ext = candidate.extension.lowercase()
                            if (ext !in ALLOWED_AUDIO_EXTS) {
                                call.respondText(
                                    json.encodeToString(ErrorResponse("Unsupported audio file extension.")),
                                    ContentType.Application.Json,
                                    HttpStatusCode.Forbidden
                                )
                                return@get
                            }
                            if (!candidate.exists() || !candidate.isFile) {
                                call.respondText(
                                    json.encodeToString(ErrorResponse("Audio file not found.")),
                                    ContentType.Application.Json,
                                    HttpStatusCode.NotFound
                                )
                                return@get
                            }
                            call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
                            call.respondFile(candidate)
                        }

                        webSocket("/ws") {
                            handleWebSocketSession()
                        }

                        // Serve bundled Musika Web PWA over LAN HTTP portal (`assets/web/`)
                        get("/{path...}") {
                            val assetManager = appContext?.assets
                            if (assetManager == null) {
                                call.respondText("Musika LAN Sync Server active", ContentType.Text.Plain)
                                return@get
                            }
                            val segments = call.parameters.getAll("path").orEmpty()
                            val subPath = if (segments.isEmpty()) "index.html" else segments.joinToString("/")
                            if (subPath.contains("..")) {
                                call.respondText("Forbidden", ContentType.Text.Plain, HttpStatusCode.Forbidden)
                                return@get
                            }
                            val bytes: ByteArray? = runCatching {
                                assetManager.open("web/$subPath").use { input -> input.readBytes() }
                            }.getOrElse {
                                runCatching {
                                    assetManager.open("web/index.html").use { input -> input.readBytes() }
                                }.getOrNull()
                            }
                            if (bytes == null) {
                                call.respondText("Not Found", ContentType.Text.Plain, HttpStatusCode.NotFound)
                                return@get
                            }
                            val filePathForType = if (subPath.contains('.')) subPath else "index.html"
                            val contentType = ContentType.defaultForFilePath(filePathForType)
                            call.respondBytes(bytes, contentType, HttpStatusCode.OK)
                        }
                    }
                }
                created.start(wait = false)
                val boundConnectors = runCatching {
                    kotlinx.coroutines.runBlocking { created.resolvedConnectors() }
                }.getOrNull()
                actualPort = boundConnectors?.firstOrNull()?.port ?: if (portCandidate > 0) portCandidate else DEFAULT_PORT
                engine = created
                return actualPort
            } catch (t: Throwable) {
                Log.w("MusikaLanServer", "Bind failed on port $portCandidate: ${t.message}")
            }
        }
        return actualPort
    }

    private suspend fun DefaultWebSocketServerSession.handleWebSocketSession() {
        var authenticated = false
        var currentNonce = generateNonceHex()
        var peerId = ""

        try {
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val envelope = runCatching {
                    RemoteSyncContract.deserializeEnvelope(frame.readText())
                }.getOrNull() ?: continue

                when (envelope.type) {
                    "hello" -> {
                        peerId = envelope.senderDeviceId
                        currentNonce = generateNonceHex()
                        val challenge = RemoteSyncContract.SyncWireEnvelope(
                            type = "auth_challenge",
                            senderDeviceId = deviceId,
                            senderDeviceName = deviceNameProvider(),
                            senderDeviceType = "ANDROID",
                            accountHash = RemoteSyncContract.shortAccountHash(accountIdProvider()),
                            nonce = currentNonce,
                            lanHost = getLocalIpv4Address(),
                            lanPort = actualPort
                        )
                        send(Frame.Text(RemoteSyncContract.serializeEnvelope(challenge)))
                    }

                    "auth_proof" -> {
                        val effectivePeerId = envelope.senderDeviceId.ifBlank { peerId }
                        val valid = RemoteSyncContract.verifyHmacHex(
                            secret = pairSecretProvider(),
                            message = "$currentNonce|$effectivePeerId",
                            candidateHex = envelope.hmacProof.orEmpty()
                        )
                        if (!valid) {
                            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "HMAC verification failed"))
                            return
                        }
                        authenticated = true
                        authenticatedPeers.add(this)
                        val initialCatalog = RemoteSyncContract.SyncWireEnvelope(
                            type = "catalog",
                            senderDeviceId = deviceId,
                            senderDeviceName = deviceNameProvider(),
                            senderDeviceType = "ANDROID",
                            catalog = catalogProvider(),
                            snapshot = snapshotProvider(),
                            lanHost = getLocalIpv4Address(),
                            lanPort = actualPort
                        )
                        send(Frame.Text(RemoteSyncContract.serializeEnvelope(initialCatalog)))
                    }

                    else -> {
                        if (!authenticated) {
                            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthenticated"))
                            return
                        }
                        onIncomingEnvelope(envelope)
                        val text = RemoteSyncContract.serializeEnvelope(envelope)
                        for (peer in authenticatedPeers) {
                            if (peer != this) {
                                runCatching { peer.send(Frame.Text(text)) }
                            }
                        }
                    }
                }
            }
        } finally {
            authenticatedPeers.remove(this)
        }
    }

    fun broadcast(envelope: RemoteSyncContract.SyncWireEnvelope) {
        if (authenticatedPeers.isEmpty()) return
        val encoded = RemoteSyncContract.serializeEnvelope(envelope)
        scope.launch {
            for (peer in authenticatedPeers) {
                runCatching { peer.send(Frame.Text(encoded)) }
            }
        }
    }

    fun stop() {
        authenticatedPeers.clear()
        runCatching { engine?.stop(250, 500) }
        engine = null
    }

    private fun generateNonceHex(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val DEFAULT_PORT = 8765
        private val ALLOWED_AUDIO_EXTS = setOf("flac", "mp3", "opus", "ogg", "m4a", "aac", "wav", "alac", "ape", "wma")

        fun getLocalIpv4Address(): String? {
            return runCatching {
                NetworkInterface.getNetworkInterfaces()?.toList()
                    ?.asSequence()
                    ?.filter { it.isUp && !it.isLoopback }
                    ?.flatMap { it.inetAddresses.toList().asSequence() }
                    ?.filterIsInstance<Inet4Address>()
                    ?.firstOrNull { !it.isLoopbackAddress }
                    ?.hostAddress
            }.getOrNull()
        }
    }
}
