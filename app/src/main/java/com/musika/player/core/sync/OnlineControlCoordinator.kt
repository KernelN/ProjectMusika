package com.musika.player.core.sync

import android.content.Context
import android.net.Uri
import android.os.Build
import com.musika.player.core.model.AudioQuality
import com.musika.player.core.model.Track
import com.musika.player.data.repository.SettingsPreferences
import com.musika.player.player.DualQueueEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Coordinates Stage 2 Web/App Duality over **LAN-First -> Supabase Online Failover**:
 * 1. Runs an embedded Ktor HTTP + WebSocket LAN server (`LanSyncServer`) + NsdManager mDNS (`_musika._tcp.`)
 * 2. Probes LAN WebSocket with HMAC-SHA256 verification in <1.5s; falls back cleanly to Supabase Realtime when off-LAN,
 *    and re-probes LAN gently every 15s to upgrade back whenever devices share Wi-Fi.
 * 3. Supports `SYNCED_PLAYBACK` (with explicit default-OFF local speaker monitor guard to avoid echo) and
 *    `INDEPENDENT_CONCURRENT` multi-device control, plus strict metadata-verified `"Move Music Here"` hand-off.
 */
class OnlineControlCoordinator(
    private val localEngine: DualQueueEngine,
    val localDeviceId: String = "android-phone-1",
    val localDeviceName: String = runCatching { Build.MODEL?.takeIf { it.isNotBlank() } }.getOrNull() ?: "Android Phone",
    private val settings: SettingsPreferences? = null,
    private val context: Context? = null,
    val supabaseClient: SupabaseSyncClient = SupabaseSyncClient()
) {
    data class OnlineControlState(
        val accountId: String = "musika-account-default",
        val username: String = "MusikaUser",
        val syncMode: RemoteSyncContract.AccountDeviceSyncMode =
            RemoteSyncContract.AccountDeviceSyncMode.SYNCED_PLAYBACK,
        val transportMode: RemoteSyncContract.ConnectionTransportMode =
            RemoteSyncContract.ConnectionTransportMode.OFFLINE_LOCAL,
        val activePinCode: String = generateSixDigitPin(),
        val pairSecretHmac: String = "",
        val lanHost: String? = null,
        val lanPort: Int = LanSyncServer.DEFAULT_PORT,
        val connectedLanPeer: String? = null,
        val supabaseOnlineConnected: Boolean = false,
        val supabaseConfigured: Boolean = false,
        val activeControllingDeviceId: String = "android-phone-1",
        val browsingDeviceId: String = "android-phone-1",
        val listenLocallyWhenSyncedTargetIsRemote: Boolean = false,
        val allowLanStreamingForMissingTracks: Boolean = false,
        val connectedDevices: List<RemoteSyncContract.ConnectedDeviceNode> = emptyList(),
        val lastTransferResult: RemoteSyncContract.PlaybackTransferResult? = null,
        val statusMessage: String? = null
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lanHttpClient = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var localCatalogTracks: List<Track> = emptyList()

    @Volatile
    private var localRootPath: String = settings?.musicalRootPath?.value ?: "/Music"

    @Volatile
    private var localCatalogSummaries: List<RemoteSyncContract.RemoteLibraryTrackSummary> = emptyList()

    private var lanServer: LanSyncServer? = null
    private var lanDiscovery: LanDiscoveryManager? = null
    private var outgoingLanSocket: WebSocket? = null
    private var lanProbeJob: Job? = null
    private var mDnsCollectJob: Job? = null
    private var remotePlaylistCallback: (suspend (List<RemoteSyncContract.PlaylistSyncRecord>) -> Unit)? = null

    private val _state = MutableStateFlow(createInitialState())
    val state: StateFlow<OnlineControlState> = _state.asStateFlow()

    init {
        if (settings != null) {
            supabaseClient.configure(settings.supabaseUrl.value, settings.supabaseAnonKey.value)
            val cachedUid = settings.cachedAuthUserId.value
            val cachedUser = settings.cachedAuthUsername.value
            if (cachedUid.isNotBlank() && settings.cachedAccessToken.value.isNotBlank()) {
                supabaseClient.setSession(
                    SupabaseAuthSession(
                        userId = cachedUid,
                        username = cachedUser.ifBlank { "MusikaUser" },
                        email = "",
                        accessToken = settings.cachedAccessToken.value,
                        refreshToken = settings.cachedRefreshToken.value,
                        pairSecretHmac = settings.cachedPairSecretHmac.value
                    )
                )
            }
            _state.value = _state.value.copy(
                supabaseConfigured = supabaseClient.isConfigured()
            )
        }
    }

    private fun createInitialState(): OnlineControlState {
        val devId = settings?.localDeviceId ?: localDeviceId
        val devName = settings?.defaultDeviceName ?: localDeviceName
        val initialSecret = settings?.cachedPairSecretHmac?.value ?: generateHexSecret()
        val initialAccount = settings?.cachedAuthUserId?.value?.ifBlank { "musika-account-default" }
            ?: "musika-account-default"
        val initialUsername = settings?.cachedAuthUsername?.value?.ifBlank { "MusikaUser" } ?: "MusikaUser"

        return OnlineControlState(
            accountId = initialAccount,
            username = initialUsername,
            pairSecretHmac = initialSecret,
            lanHost = LanSyncServer.getLocalIpv4Address(),
            activeControllingDeviceId = devId,
            browsingDeviceId = devId,
            listenLocallyWhenSyncedTargetIsRemote =
                settings?.listenLocallyWhenSyncedTargetIsRemote?.value ?: false,
            allowLanStreamingForMissingTracks =
                settings?.allowLanStreamingForMissingTracks?.value ?: false,
            connectedDevices = buildList {
                add(
                    RemoteSyncContract.ConnectedDeviceNode(
                        deviceId = devId,
                        deviceName = "$devName (This Phone)",
                        deviceType = "ANDROID",
                        accountId = initialAccount,
                        isThisDevice = true,
                        isActivePlayingDevice = true,
                        lanHost = LanSyncServer.getLocalIpv4Address(),
                        lanPort = LanSyncServer.DEFAULT_PORT,
                        lastSnapshot = localEngine.toPlaybackSnapshot()
                    )
                )
                if (context == null && settings == null) {
                    add(
                        RemoteSyncContract.ConnectedDeviceNode(
                            deviceId = "desktop-winamp-workstation",
                            deviceName = "Workstation Winamp/Musika Web",
                            deviceType = "DESKTOP",
                            accountId = initialAccount,
                            isThisDevice = false,
                            isActivePlayingDevice = false
                        )
                    )
                }
            }
        )
    }

    fun effectiveLocalDeviceId(): String = settings?.localDeviceId ?: localDeviceId

    fun setOnRemotePlaylistsReceived(
        callback: suspend (List<RemoteSyncContract.PlaylistSyncRecord>) -> Unit
    ) {
        remotePlaylistCallback = callback
    }

    fun startNetworking(preferredPort: Int = LanSyncServer.DEFAULT_PORT) {
        val devId = effectiveLocalDeviceId()
        if (lanServer == null) {
            lanServer = LanSyncServer(
                appContext = context,
                deviceId = devId,
                deviceNameProvider = { localDeviceName },
                accountIdProvider = { _state.value.accountId },
                usernameProvider = { _state.value.username },
                pairSecretProvider = { _state.value.pairSecretHmac },
                pinCodeProvider = { _state.value.activePinCode },
                supabaseUrlProvider = { supabaseClient.getConfig().first },
                supabaseAnonKeyProvider = { supabaseClient.getConfig().second },
                rootPathProvider = { localRootPath },
                allowStreamingProvider = { _state.value.allowLanStreamingForMissingTracks },
                catalogProvider = { localCatalogSummaries },
                snapshotProvider = { localEngine.toPlaybackSnapshot(lanHostPortString()) },
                onIncomingEnvelope = { env -> scope.launch { handleIncomingEnvelope(env) } }
            )
        }
        val boundPort = lanServer!!.start(preferredPort)
        val lanIp = LanSyncServer.getLocalIpv4Address()

        if (context != null && lanDiscovery == null) {
            lanDiscovery = LanDiscoveryManager(context, devId).also { mgr ->
                mgr.start(
                    port = boundPort,
                    deviceName = localDeviceName,
                    accountHash = RemoteSyncContract.shortAccountHash(_state.value.accountId)
                )
            }
            mDnsCollectJob?.cancel()
            mDnsCollectJob = scope.launch {
                lanDiscovery!!.discoveredPeers.collect { peers ->
                    mergeDiscoveredLanPeers(peers)
                    if (_state.value.transportMode != RemoteSyncContract.ConnectionTransportMode.LAN_DIRECT &&
                        peers.isNotEmpty()
                    ) {
                        val first = peers.first()
                        tryLanFirstThenOnlineFallback(first.host, first.port)
                    }
                }
            }
        }

        _state.value = _state.value.copy(
            lanHost = lanIp,
            lanPort = boundPort,
            supabaseConfigured = supabaseClient.isConfigured()
        )

        scope.launch {
            if (settings != null &&
                settings.cachedRefreshToken.value.isNotBlank() &&
                supabaseClient.isConfigured()
            ) {
                supabaseClient.restoreWithRefreshToken(
                    settings.cachedRefreshToken.value,
                    _state.value.username
                )?.let { refreshed ->
                    settings.saveAuthSession(
                        refreshed.userId,
                        refreshed.username,
                        refreshed.accessToken,
                        refreshed.refreshToken,
                        refreshed.pairSecretHmac.takeIf { it.isNotBlank() }
                    )
                    _state.value = _state.value.copy(
                        accountId = refreshed.userId,
                        username = refreshed.username,
                        pairSecretHmac = refreshed.pairSecretHmac.ifBlank { _state.value.pairSecretHmac }
                    )
                }
            }
            if (supabaseClient.session != null) {
                supabaseClient.upsertDevice(
                    deviceId = devId,
                    deviceName = localDeviceName,
                    lanHost = lanIp,
                    lanPort = boundPort,
                    isActivePlaying = _state.value.activeControllingDeviceId == devId
                )
                refreshSupabaseAccountDevices()
                activateSupabaseOnlineFallback()
            }
        }

        lanProbeJob?.cancel()
        lanProbeJob = scope.launch {
            while (true) {
                delay(15_000L)
                if (_state.value.transportMode != RemoteSyncContract.ConnectionTransportMode.LAN_DIRECT) {
                    val peer = findBestLanCandidate()
                    if (peer != null) {
                        tryLanFirstThenOnlineFallback(peer.first, peer.second)
                    }
                }
            }
        }
    }

    fun stopNetworking() {
        lanProbeJob?.cancel()
        mDnsCollectJob?.cancel()
        outgoingLanSocket?.close(1000, "stop")
        outgoingLanSocket = null
        lanDiscovery?.stop()
        lanDiscovery = null
        lanServer?.stop()
        lanServer = null
        supabaseClient.disconnectRealtime()
    }

    private fun lanHostPortString(): String? {
        val h = _state.value.lanHost ?: return null
        return "$h:${_state.value.lanPort}"
    }

    private fun findBestLanCandidate(): Pair<String, Int>? {
        val mDnsPeer = lanDiscovery?.discoveredPeers?.value?.firstOrNull()
        if (mDnsPeer != null) return mDnsPeer.host to mDnsPeer.port
        val knownPeer = _state.value.connectedDevices.firstOrNull {
            !it.isThisDevice && !it.lanHost.isNullOrBlank() && it.lanPort != null
        }
        return knownPeer?.lanHost?.let { it to (knownPeer.lanPort ?: LanSyncServer.DEFAULT_PORT) }
    }

    /**
     * Probes direct WebSocket over LAN (1500ms timeout) and verifies HMAC-SHA256 handshake.
     * Automatically fails over to Supabase Realtime Broadcast if unreachable.
     */
    suspend fun tryLanFirstThenOnlineFallback(host: String, port: Int): Boolean {
        val devId = effectiveLocalDeviceId()
        val deferred = CompletableDeferred<Boolean>()
        val request = Request.Builder().url("ws://$host:$port/ws").build()

        val ws = lanHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val hello = RemoteSyncContract.SyncWireEnvelope(
                    type = "hello",
                    senderDeviceId = devId,
                    senderDeviceName = localDeviceName,
                    senderDeviceType = "ANDROID",
                    accountHash = RemoteSyncContract.shortAccountHash(_state.value.accountId),
                    lanHost = _state.value.lanHost,
                    lanPort = _state.value.lanPort
                )
                webSocket.send(RemoteSyncContract.serializeEnvelope(hello))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val env = runCatching { RemoteSyncContract.deserializeEnvelope(text) }.getOrNull()
                    ?: return
                when (env.type) {
                    "auth_challenge" -> {
                        val nonce = env.nonce.orEmpty()
                        val proof = RemoteSyncContract.computeHmacHex(
                            _state.value.pairSecretHmac,
                            "$nonce|$devId"
                        )
                        val auth = RemoteSyncContract.SyncWireEnvelope(
                            type = "auth_proof",
                            senderDeviceId = devId,
                            senderDeviceName = localDeviceName,
                            senderDeviceType = "ANDROID",
                            nonce = nonce,
                            hmacProof = proof
                        )
                        webSocket.send(RemoteSyncContract.serializeEnvelope(auth))
                        outgoingLanSocket = webSocket
                        _state.value = _state.value.copy(
                            transportMode = RemoteSyncContract.ConnectionTransportMode.LAN_DIRECT,
                            connectedLanPeer = "$host:$port",
                            statusMessage = "Connected via Direct LAN ($host:$port)"
                        )
                        pushLocalCatalogEnvelope(webSocket)
                        if (!deferred.isCompleted) deferred.complete(true)
                    }

                    else -> {
                        scope.launch { handleIncomingEnvelope(env) }
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (outgoingLanSocket == webSocket) {
                    outgoingLanSocket = null
                    scope.launch { activateSupabaseOnlineFallback() }
                }
                if (!deferred.isCompleted) deferred.complete(false)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (outgoingLanSocket == webSocket) {
                    outgoingLanSocket = null
                }
                if (!deferred.isCompleted) deferred.complete(false)
            }
        })

        val ok = withTimeoutOrNull(1800L) { deferred.await() } == true
        if (!ok) {
            runCatching { ws.cancel() }
            activateSupabaseOnlineFallback()
        }
        return ok
    }

    private fun activateSupabaseOnlineFallback() {
        if (!supabaseClient.isConfigured() || supabaseClient.session == null) {
            _state.value = _state.value.copy(
                transportMode = RemoteSyncContract.ConnectionTransportMode.OFFLINE_LOCAL,
                supabaseOnlineConnected = false
            )
            return
        }
        supabaseClient.connectRealtime(
            accountId = _state.value.accountId,
            onConnectedChanged = { online ->
                if (_state.value.transportMode != RemoteSyncContract.ConnectionTransportMode.LAN_DIRECT) {
                    _state.value = _state.value.copy(
                        supabaseOnlineConnected = online,
                        transportMode = if (online) {
                            RemoteSyncContract.ConnectionTransportMode.ONLINE_SUPABASE
                        } else {
                            RemoteSyncContract.ConnectionTransportMode.OFFLINE_LOCAL
                        },
                        statusMessage = if (online) {
                            "Online Sync Active via Supabase (LAN unreachable)"
                        } else {
                            _state.value.statusMessage
                        }
                    )
                } else {
                    _state.value = _state.value.copy(supabaseOnlineConnected = online)
                }
                if (online) {
                    pushLocalCatalogEnvelope()
                }
            },
            onEnvelope = { env ->
                scope.launch { handleIncomingEnvelope(env) }
            }
        )
    }

    suspend fun signUpUsername(username: String, password: String): SupabaseAuthSession {
        val sess = supabaseClient.signUpWithUsername(username, password)
        applyAuthenticatedSession(sess)
        return sess
    }

    suspend fun signInUsername(username: String, password: String): SupabaseAuthSession {
        val sess = supabaseClient.signInWithUsername(username, password)
        applyAuthenticatedSession(sess)
        return sess
    }

    private suspend fun applyAuthenticatedSession(sess: SupabaseAuthSession) {
        val effectiveSecret = sess.pairSecretHmac.ifBlank { _state.value.pairSecretHmac }
        settings?.saveAuthSession(
            userId = sess.userId,
            username = sess.username,
            accessToken = sess.accessToken,
            refreshToken = sess.refreshToken,
            pairSecretHmac = effectiveSecret
        )
        _state.value = _state.value.copy(
            accountId = sess.userId,
            username = sess.username,
            pairSecretHmac = effectiveSecret,
            supabaseConfigured = supabaseClient.isConfigured(),
            statusMessage = "Signed in as ${sess.username}"
        )
        val devId = effectiveLocalDeviceId()
        supabaseClient.upsertDevice(
            deviceId = devId,
            deviceName = localDeviceName,
            lanHost = _state.value.lanHost,
            lanPort = _state.value.lanPort,
            isActivePlaying = _state.value.activeControllingDeviceId == devId
        )
        refreshSupabaseAccountDevices()
        activateSupabaseOnlineFallback()
    }

    fun signOutAccount() {
        supabaseClient.setSession(null)
        supabaseClient.disconnectRealtime()
        settings?.clearAuthSession()
        _state.value = _state.value.copy(
            accountId = "musika-account-default",
            username = "MusikaUser",
            supabaseOnlineConnected = false,
            transportMode = if (outgoingLanSocket != null) {
                RemoteSyncContract.ConnectionTransportMode.LAN_DIRECT
            } else {
                RemoteSyncContract.ConnectionTransportMode.OFFLINE_LOCAL
            },
            statusMessage = "Signed out of online account"
        )
    }

    suspend fun pairViaQrOrPinInput(
        rawInput: String,
        manualHost: String? = null,
        manualPort: Int = LanSyncServer.DEFAULT_PORT
    ): String {
        val trimmed = rawInput.trim()
        val qr = RemoteSyncContract.QrPairPayload.parse(trimmed)
        if (qr != null) {
            if (qr.supabaseUrl.isNotBlank() && qr.supabaseAnonKey.isNotBlank()) {
                supabaseClient.configure(qr.supabaseUrl, qr.supabaseAnonKey)
                settings?.setSupabaseConfig(qr.supabaseUrl, qr.supabaseAnonKey)
            }
            if (!qr.refreshToken.isNullOrBlank() && supabaseClient.isConfigured()) {
                supabaseClient.restoreWithRefreshToken(qr.refreshToken, qr.username)
            }
            settings?.saveAuthSession(
                userId = qr.accountId,
                username = qr.username,
                accessToken = supabaseClient.session?.accessToken.orEmpty(),
                refreshToken = qr.refreshToken ?: supabaseClient.session?.refreshToken.orEmpty(),
                pairSecretHmac = qr.pairSecretHmac
            )
            _state.value = _state.value.copy(
                accountId = qr.accountId,
                username = qr.username,
                pairSecretHmac = qr.pairSecretHmac,
                supabaseConfigured = supabaseClient.isConfigured(),
                statusMessage = "Paired via QR with account '${qr.username}'"
            )
            val targetHost = qr.lanHost ?: manualHost?.trim()?.takeIf { it.isNotBlank() }
            if (targetHost != null) {
                tryLanFirstThenOnlineFallback(targetHost, qr.lanPort)
            } else {
                activateSupabaseOnlineFallback()
            }
            return "Paired with '${qr.username}'"
        }

        if (trimmed.matches(Regex("^\\d{6}$"))) {
            val candidate = manualHost?.trim()?.takeIf { it.isNotBlank() }
                ?.let { it to manualPort }
                ?: findBestLanCandidate()
                ?: throw IllegalStateException("6-digit PIN pairing requires a discovered LAN peer or Peer IP.")

            val respJson = withContext(Dispatchers.IO) {
                val body = """{"pinCode":"$trimmed","deviceId":"${effectiveLocalDeviceId()}","deviceName":"$localDeviceName"}"""
                    .toRequestBody("application/json; charset=utf-8".toMediaType())
                val req = Request.Builder()
                    .url("http://${candidate.first}:${candidate.second}/api/pair/pin")
                    .post(body)
                    .build()
                lanHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw IllegalStateException("Invalid or expired 6-digit PIN (HTTP ${resp.code}).")
                    }
                    resp.body?.string().orEmpty()
                }
            }
            val data = json.decodeFromString<RemoteSyncContract.QrPairPayload>(respJson)
            if (data.supabaseUrl.isNotBlank() && data.supabaseAnonKey.isNotBlank()) {
                supabaseClient.configure(data.supabaseUrl, data.supabaseAnonKey)
                settings?.setSupabaseConfig(data.supabaseUrl, data.supabaseAnonKey)
            }
            settings?.saveAuthSession(
                userId = data.accountId,
                username = data.username,
                accessToken = "",
                refreshToken = data.refreshToken.orEmpty(),
                pairSecretHmac = data.pairSecretHmac
            )
            _state.value = _state.value.copy(
                accountId = data.accountId,
                username = data.username,
                pairSecretHmac = data.pairSecretHmac,
                supabaseConfigured = supabaseClient.isConfigured(),
                statusMessage = "PIN verified on LAN (${candidate.first}:${candidate.second})"
            )
            tryLanFirstThenOnlineFallback(candidate.first, candidate.second)
            return "Paired via PIN with '${data.username}' over LAN"
        }

        throw IllegalArgumentException("Enter a valid Musika QR link or 6-digit LAN PIN.")
    }

    fun generateQrPayload(): RemoteSyncContract.QrPairPayload {
        val cfg = supabaseClient.getConfig()
        return RemoteSyncContract.QrPairPayload(
            version = RemoteSyncContract.PROTOCOL_VERSION,
            accountId = _state.value.accountId,
            username = _state.value.username,
            pairSecretHmac = _state.value.pairSecretHmac,
            pinCode = _state.value.activePinCode,
            lanHost = LanSyncServer.getLocalIpv4Address() ?: _state.value.lanHost,
            lanPort = lanServer?.actualPort ?: _state.value.lanPort,
            supabaseUrl = cfg.first,
            supabaseAnonKey = cfg.second,
            refreshToken = supabaseClient.session?.refreshToken?.takeIf { it.isNotBlank() },
            issuedAtMs = System.currentTimeMillis()
        )
    }

    fun updateSupabaseConfig(url: String, anonKey: String) {
        supabaseClient.configure(url, anonKey)
        settings?.setSupabaseConfig(url, anonKey)
        _state.value = _state.value.copy(
            supabaseConfigured = supabaseClient.isConfigured(),
            statusMessage = "Supabase credentials updated"
        )
        if (_state.value.transportMode != RemoteSyncContract.ConnectionTransportMode.LAN_DIRECT &&
            supabaseClient.session != null
        ) {
            scope.launch { activateSupabaseOnlineFallback() }
        }
    }

    fun setAllowLanStreamingForMissingTracks(enabled: Boolean) {
        settings?.setAllowLanStreamingForMissingTracks(enabled)
        _state.value = _state.value.copy(allowLanStreamingForMissingTracks = enabled)
    }

    fun setListenLocallyWhenSyncedTargetIsRemote(enabled: Boolean) {
        settings?.setListenLocallyWhenSyncedTargetIsRemote(enabled)
        _state.value = _state.value.copy(listenLocallyWhenSyncedTargetIsRemote = enabled)
    }

    fun setBrowsingDeviceId(deviceId: String) {
        _state.value = _state.value.copy(browsingDeviceId = deviceId)
    }

    /**
     * Resolves tracks for whatever device library the user is currently browsing in the UI
     * (this phone's local library OR a connected peer's indexed catalog).
     */
    fun getActiveBrowsingTracks(localTracks: List<Track>): List<Track> {
        val devId = effectiveLocalDeviceId()
        val browsingId = _state.value.browsingDeviceId
        if (browsingId == devId || browsingId.isBlank()) return localTracks
        val peer = _state.value.connectedDevices.firstOrNull { it.deviceId == browsingId }
            ?: return emptyList()
        val peerHost = if (!peer.lanHost.isNullOrBlank() && peer.lanPort != null) {
            "${peer.lanHost}:${peer.lanPort}"
        } else {
            null
        }
        return peer.catalogTracks.map { summary ->
            val streamUri = if (peerHost != null && _state.value.allowLanStreamingForMissingTracks) {
                "http://$peerHost/stream?path=${Uri.encode(summary.relativePath)}"
            } else {
                null
            }
            Track(
                filePath = streamUri ?: summary.relativePath,
                relativePath = summary.relativePath,
                fileName = summary.relativePath.substringAfterLast('/'),
                fileExtension = summary.audioQuality?.format?.lowercase() ?: "audio",
                title = summary.title,
                artist = summary.artist,
                album = summary.album,
                discNumber = summary.discNumber,
                trackNumber = summary.trackNumber,
                durationMs = summary.durationMs,
                audioQuality = summary.audioQuality ?: AudioQuality("REMOTE", 0),
                parentFolderPath = "/" + summary.relativePath.substringBeforeLast('/', "")
            )
        }
    }

    fun updateLocalLibrary(tracks: List<Track>, rootPath: String) {
        localCatalogTracks = tracks
        localRootPath = rootPath
        localCatalogSummaries = tracks.map { RemoteSyncContract.RemoteLibraryTrackSummary.fromTrack(it) }
        updateLocalCatalog(tracks)
        pushLocalCatalogEnvelope()
    }

    private suspend fun refreshSupabaseAccountDevices() {
        val remoteRows = supabaseClient.fetchAccountDevices()
        val devId = effectiveLocalDeviceId()
        val merged = _state.value.connectedDevices.toMutableList()
        for (remote in remoteRows) {
            if (remote.deviceId == devId) continue
            val idx = merged.indexOfFirst { it.deviceId == remote.deviceId }
            if (idx >= 0) {
                merged[idx] = merged[idx].copy(
                    deviceName = remote.deviceName,
                    lanHost = remote.lanHost ?: merged[idx].lanHost,
                    lanPort = remote.lanPort ?: merged[idx].lanPort
                )
            } else {
                merged.add(remote)
            }
        }
        _state.value = _state.value.copy(connectedDevices = merged)
    }

    private fun mergeDiscoveredLanPeers(peers: List<DiscoveredLanPeer>) {
        val devId = effectiveLocalDeviceId()
        val updated = _state.value.connectedDevices.toMutableList()
        for (peer in peers) {
            if (peer.deviceId == devId) continue
            val idx = updated.indexOfFirst { it.deviceId == peer.deviceId }
            val node = if (idx >= 0) {
                updated[idx].copy(
                    deviceName = peer.deviceName,
                    lanHost = peer.host,
                    lanPort = peer.port
                )
            } else {
                RemoteSyncContract.ConnectedDeviceNode(
                    deviceId = peer.deviceId,
                    deviceName = peer.deviceName,
                    deviceType = peer.deviceType.ifBlank { "DESKTOP" },
                    accountId = _state.value.accountId,
                    isThisDevice = false,
                    isActivePlayingDevice = false,
                    lanHost = peer.host,
                    lanPort = peer.port
                )
            }
            if (idx >= 0) updated[idx] = node else updated.add(node)
        }
        _state.value = _state.value.copy(connectedDevices = updated)
    }

    private fun pushLocalCatalogEnvelope(specificSocket: WebSocket? = null) {
        val devId = effectiveLocalDeviceId()
        val env = RemoteSyncContract.SyncWireEnvelope(
            type = "catalog",
            senderDeviceId = devId,
            senderDeviceName = localDeviceName,
            senderDeviceType = "ANDROID",
            accountHash = RemoteSyncContract.shortAccountHash(_state.value.accountId),
            catalog = localCatalogSummaries,
            snapshot = localEngine.toPlaybackSnapshot(lanHostPortString()),
            lanHost = _state.value.lanHost,
            lanPort = _state.value.lanPort
        )
        if (specificSocket != null) {
            specificSocket.send(RemoteSyncContract.serializeEnvelope(env))
        } else {
            sendEnvelopeOverActiveTransport(env)
        }
    }

    private fun sendEnvelopeOverActiveTransport(envelope: RemoteSyncContract.SyncWireEnvelope) {
        val serialized = RemoteSyncContract.serializeEnvelope(envelope)
        lanServer?.broadcast(envelope)
        outgoingLanSocket?.send(serialized)
        if (outgoingLanSocket == null && _state.value.supabaseOnlineConnected) {
            supabaseClient.sendRealtimeBroadcast(envelope)
        }
    }

    suspend fun handleIncomingEnvelope(envelope: RemoteSyncContract.SyncWireEnvelope) {
        val devId = effectiveLocalDeviceId()
        if (envelope.senderDeviceId == devId) return

        // Register/update sender device node automatically
        val existingIdx = _state.value.connectedDevices.indexOfFirst { it.deviceId == envelope.senderDeviceId }
        val senderType = envelope.senderDeviceType.ifBlank { "DESKTOP" }

        val currentList = _state.value.connectedDevices.toMutableList()
        val existing = if (existingIdx >= 0) currentList[existingIdx] else {
            RemoteSyncContract.ConnectedDeviceNode(
                deviceId = envelope.senderDeviceId,
                deviceName = envelope.senderDeviceName.ifBlank { envelope.senderDeviceId },
                deviceType = senderType,
                accountId = _state.value.accountId,
                isThisDevice = false,
                isActivePlayingDevice = false
            )
        }
        val catalogTracks = envelope.catalog ?: existing.catalogTracks
        val loadedKeys = envelope.catalog?.map { it.normalizedKey }?.toSet() ?: existing.loadedTrackKeys
        val loadedPaths = envelope.catalog?.map { it.relativePath }?.toSet() ?: existing.loadedRelativePaths
        val refreshedNode = existing.copy(
            deviceName = envelope.senderDeviceName.ifBlank { existing.deviceName },
            lanHost = envelope.lanHost ?: existing.lanHost,
            lanPort = envelope.lanPort ?: existing.lanPort,
            lastSnapshot = envelope.snapshot ?: existing.lastSnapshot,
            catalogTracks = catalogTracks,
            loadedTrackKeys = loadedKeys,
            loadedRelativePaths = loadedPaths
        )
        if (existingIdx >= 0) currentList[existingIdx] = refreshedNode else currentList.add(refreshedNode)
        _state.value = _state.value.copy(connectedDevices = currentList)

        if (envelope.type == "playlists" && !envelope.playlists.isNullOrEmpty()) {
            remotePlaylistCallback?.invoke(envelope.playlists)
            return
        }

        if (envelope.type == "sync_mode" && envelope.syncMode != null) {
            _state.value = _state.value.copy(
                syncMode = envelope.syncMode,
                statusMessage = "Sync mode updated by ${existing.deviceName}"
            )
            return
        }

        val allowStream = _state.value.allowLanStreamingForMissingTracks
        val cmd = envelope.command
        if (envelope.type == "command" && cmd != null) {
            when (cmd) {
                is RemoteSyncContract.SyncCommand.TransferPlayback -> {
                    if (cmd.targetDeviceId == devId) {
                        localEngine.applySyncCommand(cmd, localCatalogTracks, allowStream, playAudio = true)
                        _state.value = _state.value.copy(
                            activeControllingDeviceId = devId,
                            connectedDevices = _state.value.connectedDevices.map {
                                it.copy(isActivePlayingDevice = it.deviceId == devId)
                            },
                            statusMessage = "Playback moved here from ${existing.deviceName}"
                        )
                    } else if (cmd.pauseSourceDevice && _state.value.activeControllingDeviceId == devId) {
                        val playLocal = _state.value.listenLocallyWhenSyncedTargetIsRemote
                        localEngine.setPlaying(playLocal)
                        _state.value = _state.value.copy(
                            activeControllingDeviceId = cmd.targetDeviceId,
                            connectedDevices = _state.value.connectedDevices.map {
                                it.copy(isActivePlayingDevice = it.deviceId == cmd.targetDeviceId)
                            }
                        )
                    }
                }

                is RemoteSyncContract.SyncCommand.SetAccountSyncMode -> {
                    _state.value = _state.value.copy(syncMode = cmd.mode)
                }

                else -> {
                    val targetedAtUs = envelope.targetDeviceId == devId ||
                        (_state.value.syncMode == RemoteSyncContract.AccountDeviceSyncMode.SYNCED_PLAYBACK &&
                            envelope.targetDeviceId.isNullOrBlank())
                    if (targetedAtUs) {
                        val playLocal = envelope.targetDeviceId == devId ||
                            _state.value.activeControllingDeviceId == devId ||
                            _state.value.listenLocallyWhenSyncedTargetIsRemote
                        localEngine.applySyncCommand(cmd, localCatalogTracks, allowStream, playAudio = playLocal)
                    }
                }
            }
        }
    }

    fun setAccountSyncMode(mode: RemoteSyncContract.AccountDeviceSyncMode) {
        _state.value = _state.value.copy(
            syncMode = mode,
            statusMessage = "Account sync mode set to ${mode.displayName}"
        )
        sendEnvelopeOverActiveTransport(
            RemoteSyncContract.SyncWireEnvelope(
                type = "sync_mode",
                senderDeviceId = effectiveLocalDeviceId(),
                senderDeviceName = localDeviceName,
                senderDeviceType = "ANDROID",
                syncMode = mode
            )
        )
        scope.launch {
            supabaseClient.updateAccountSettings(mode, _state.value.activeControllingDeviceId)
        }
    }

    fun registerOrUpdatePeerDevice(
        deviceId: String,
        deviceName: String,
        deviceType: String = "DESKTOP",
        loadedTrackKeys: List<String> = emptyList(),
        loadedRelativePaths: List<String> = emptyList(),
        lastSnapshot: RemoteSyncContract.PlaybackSnapshot? = null
    ) {
        val current = _state.value
        val existingIdx = current.connectedDevices.indexOfFirst { it.deviceId == deviceId }
        val node = RemoteSyncContract.ConnectedDeviceNode(
            deviceId = deviceId,
            deviceName = deviceName,
            deviceType = deviceType,
            accountId = current.accountId,
            isThisDevice = deviceId == effectiveLocalDeviceId(),
            isActivePlayingDevice = deviceId == current.activeControllingDeviceId,
            lastSnapshot = lastSnapshot,
            loadedTrackKeys = loadedTrackKeys.toSet(),
            loadedRelativePaths = loadedRelativePaths.toSet()
        )
        val updatedList = current.connectedDevices.toMutableList()
        if (existingIdx >= 0) updatedList[existingIdx] = node else updatedList.add(node)
        _state.value = current.copy(connectedDevices = updatedList)
    }

    fun mirrorLocalCatalogToPeerDevice(targetDeviceId: String, localTracks: List<Track>) {
        val current = _state.value
        val summaries = localTracks.map { RemoteSyncContract.RemoteLibraryTrackSummary.fromTrack(it) }
        val updated = current.connectedDevices.map { dev ->
            if (dev.deviceId == targetDeviceId) {
                dev.copy(
                    loadedTrackKeys = localTracks.map { it.normalizedKey }.toSet(),
                    loadedRelativePaths = localTracks.map { it.relativePath }.toSet(),
                    catalogTracks = summaries
                )
            } else {
                dev
            }
        }
        _state.value = current.copy(connectedDevices = updated)
    }

    fun sendRemoteCommand(
        command: RemoteSyncContract.SyncCommand,
        localLibrary: List<Track>,
        targetDeviceId: String = _state.value.activeControllingDeviceId
    ) {
        val current = _state.value
        val devId = effectiveLocalDeviceId()
        val allowStream = current.allowLanStreamingForMissingTracks
        val effectiveLib = localLibrary.ifEmpty { localCatalogTracks }

        if (targetDeviceId == devId ||
            current.syncMode == RemoteSyncContract.AccountDeviceSyncMode.SYNCED_PLAYBACK
        ) {
            val playLocal = targetDeviceId == devId ||
                current.activeControllingDeviceId == devId ||
                current.listenLocallyWhenSyncedTargetIsRemote
            localEngine.applySyncCommand(command, effectiveLib, allowStream, playAudio = playLocal)
        }

        val updatedDevices = current.connectedDevices.map { node ->
            if (node.deviceId == targetDeviceId ||
                (current.syncMode == RemoteSyncContract.AccountDeviceSyncMode.SYNCED_PLAYBACK && !node.isThisDevice)
            ) {
                val baseSnap = node.lastSnapshot ?: localEngine.toPlaybackSnapshot(lanHostPortString())
                val nextSnap = applyCommandToSnapshotPreview(baseSnap, command)
                node.copy(lastSnapshot = nextSnap)
            } else {
                node
            }
        }

        _state.value = current.copy(
            connectedDevices = updatedDevices,
            statusMessage = "Sent ${command::class.simpleName} to $targetDeviceId"
        )

        sendEnvelopeOverActiveTransport(
            RemoteSyncContract.SyncWireEnvelope(
                type = "command",
                senderDeviceId = devId,
                senderDeviceName = localDeviceName,
                senderDeviceType = "ANDROID",
                targetDeviceId = if (current.syncMode == RemoteSyncContract.AccountDeviceSyncMode.SYNCED_PLAYBACK) null else targetDeviceId,
                command = command
            )
        )
    }

    fun transferPlaybackToDevice(
        targetDeviceId: String,
        localLibrary: List<Track> = emptyList()
    ): RemoteSyncContract.PlaybackTransferResult {
        val current = _state.value
        val devId = effectiveLocalDeviceId()
        val effectiveLib = localLibrary.ifEmpty { localCatalogTracks }
        val allowStream = current.allowLanStreamingForMissingTracks
        val sourceDeviceId = current.activeControllingDeviceId
        val sourceNode = current.connectedDevices.firstOrNull { it.deviceId == sourceDeviceId }
        val targetNode = current.connectedDevices.firstOrNull { it.deviceId == targetDeviceId }
            ?: return RemoteSyncContract.PlaybackTransferResult(
                allowed = false,
                sourceDeviceId = sourceDeviceId,
                targetDeviceId = targetDeviceId,
                transferredTrack = null,
                reason = "Target device '$targetDeviceId' is not connected."
            )

        val hostStr = lanHostPortString()
        val sourceSnapshot = if (sourceDeviceId == devId) {
            localEngine.toPlaybackSnapshot(hostStr)
        } else {
            sourceNode?.lastSnapshot ?: localEngine.toPlaybackSnapshot(hostStr)
        }

        val validation = RemoteSyncContract.validateAndPreparePlaybackTransfer(
            sourceDeviceId = sourceDeviceId,
            sourceSnapshot = sourceSnapshot,
            targetDevice = targetNode,
            allowLanStreamingFallback = allowStream
        )

        if (!validation.allowed) {
            _state.value = current.copy(
                lastTransferResult = validation,
                statusMessage = validation.reason
            )
            return validation
        }

        val (filteredSnap, _) = RemoteSyncContract.filterSnapshotForTargetDevice(
            snapshot = sourceSnapshot,
            targetDevice = targetNode,
            allowLanStreamingFallback = allowStream
        )

        val transferCmd = RemoteSyncContract.SyncCommand.TransferPlayback(
            sourceDeviceId = sourceDeviceId,
            targetDeviceId = targetDeviceId,
            snapshot = filteredSnap.copy(isPlaying = true),
            pauseSourceDevice = sourceDeviceId != targetDeviceId
        )

        if (sourceDeviceId == devId && targetDeviceId != devId) {
            localEngine.setPlaying(current.listenLocallyWhenSyncedTargetIsRemote)
        } else if (targetDeviceId == devId) {
            localEngine.applySyncCommand(transferCmd, effectiveLib, allowStream, playAudio = true)
        }

        val updatedDevices = current.connectedDevices.map { node ->
            node.copy(
                isActivePlayingDevice = node.deviceId == targetDeviceId,
                lastSnapshot = if (node.deviceId == targetDeviceId) {
                    filteredSnap.copy(isPlaying = true)
                } else if (node.deviceId == sourceDeviceId && sourceDeviceId != targetDeviceId) {
                    sourceSnapshot.copy(isPlaying = false)
                } else {
                    node.lastSnapshot
                }
            )
        }

        _state.value = current.copy(
            activeControllingDeviceId = targetDeviceId,
            connectedDevices = updatedDevices,
            lastTransferResult = validation,
            statusMessage = validation.reason
        )

        sendEnvelopeOverActiveTransport(
            RemoteSyncContract.SyncWireEnvelope(
                type = "command",
                senderDeviceId = devId,
                senderDeviceName = localDeviceName,
                senderDeviceType = "ANDROID",
                targetDeviceId = targetDeviceId,
                command = transferCmd
            )
        )
        scope.launch {
            supabaseClient.updateAccountSettings(current.syncMode, targetDeviceId)
        }
        return validation
    }

    fun updateLocalCatalog(tracks: List<Track>) {
        localCatalogTracks = tracks
        val devId = effectiveLocalDeviceId()
        val summaries = tracks.map { RemoteSyncContract.RemoteLibraryTrackSummary.fromTrack(it) }
        localCatalogSummaries = summaries
        val updatedDevices = _state.value.connectedDevices.map { node ->
            if (node.deviceId == devId) {
                node.copy(
                    loadedTrackKeys = tracks.map { it.normalizedKey }.toSet(),
                    loadedRelativePaths = tracks.map { it.relativePath }.toSet(),
                    catalogTracks = summaries,
                    lastSnapshot = localEngine.toPlaybackSnapshot(lanHostPortString())
                )
            } else {
                node
            }
        }
        _state.value = _state.value.copy(connectedDevices = updatedDevices)
    }

    fun broadcastPlaylists(records: List<RemoteSyncContract.PlaylistSyncRecord>) {
        sendEnvelopeOverActiveTransport(
            RemoteSyncContract.SyncWireEnvelope(
                type = "playlists",
                senderDeviceId = effectiveLocalDeviceId(),
                senderDeviceName = localDeviceName,
                senderDeviceType = "ANDROID",
                playlists = records
            )
        )
        scope.launch { supabaseClient.syncPlaylists(records) }
    }

    private fun applyCommandToSnapshotPreview(
        snap: RemoteSyncContract.PlaybackSnapshot,
        command: RemoteSyncContract.SyncCommand
    ): RemoteSyncContract.PlaybackSnapshot {
        return when (command) {
            is RemoteSyncContract.SyncCommand.Play -> snap.copy(isPlaying = true)
            is RemoteSyncContract.SyncCommand.Pause -> snap.copy(isPlaying = false)
            is RemoteSyncContract.SyncCommand.TogglePlayPause -> snap.copy(isPlaying = !snap.isPlaying)
            is RemoteSyncContract.SyncCommand.SeekTo -> snap.copy(positionMs = command.positionMs)
            is RemoteSyncContract.SyncCommand.SetRandom -> snap.copy(randomMode = command.mode)
            is RemoteSyncContract.SyncCommand.SetLoop -> snap.copy(loopMode = command.mode)
            is RemoteSyncContract.SyncCommand.PlayNow -> snap.copy(
                currentTrack = command.track,
                isPlaying = true,
                positionMs = 0L,
                standardQueue = command.contextTracks
            )
            else -> snap
        }
    }

    companion object {
        fun generateSixDigitPin(): String {
            return (100000 + SecureRandom().nextInt(900000)).toString()
        }

        private fun generateHexSecret(): String {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
