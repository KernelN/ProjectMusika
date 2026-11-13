package com.musika.player.core.sync

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class SupabaseAuthSession(
    val userId: String,
    val username: String,
    val email: String,
    val accessToken: String,
    val refreshToken: String,
    val pairSecretHmac: String
)

/**
 * Pure OkHttp + Kotlinx Serialization client for Supabase Auth, PostgREST, and Realtime Broadcast (Phoenix WS).
 * Supports custom base URLs for 100% deterministic local MockWebServer integration tests.
 */
class SupabaseSyncClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val msgCounter = AtomicInteger(1)

    @Volatile
    private var baseUrl: String = ""

    @Volatile
    private var anonKey: String = ""

    @Volatile
    var session: SupabaseAuthSession? = null
        private set

    private var realtimeSocket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var currentTopic: String = ""

    fun configure(url: String, key: String) {
        baseUrl = url.trim().trimEnd('/')
        anonKey = key.trim()
    }

    fun getConfig(): Pair<String, String> = baseUrl to anonKey

    fun isConfigured(): Boolean = baseUrl.isNotBlank() && anonKey.isNotBlank()

    fun setSession(newSession: SupabaseAuthSession?) {
        session = newSession
    }

    fun syntheticEmailForUsername(username: String): String {
        val clean = username.trim().lowercase().replace(Regex("[^a-z0-9._-]"), "_")
        return "$clean@musika-player.org"
    }

    @Serializable
    private data class AuthTokenResponse(
        @SerialName("access_token") val accessToken: String = "",
        @SerialName("refresh_token") val refreshToken: String = "",
        val user: AuthUserDto? = null
    )

    @Serializable
    private data class AuthUserDto(
        val id: String = "",
        val email: String = ""
    )

    @Serializable
    private data class ProfileRowDto(
        @SerialName("user_id") val userId: String = "",
        val username: String = "",
        @SerialName("pair_secret_hmac") val pairSecretHmac: String = ""
    )

    @Serializable
    private data class PlaylistRowDto(
        val name: String = "",
        val items: List<RemoteSyncContract.PlaylistSyncTrackItem> = emptyList(),
        @SerialName("client_updated_at") val clientUpdatedAt: Long = 0L
    )

    @Serializable
    private data class DeviceRowDto(
        @SerialName("device_id") val deviceId: String = "",
        @SerialName("device_name") val deviceName: String = "",
        @SerialName("device_type") val deviceType: String = "ANDROID",
        @SerialName("lan_host") val lanHost: String? = null,
        @SerialName("lan_port") val lanPort: Int? = null,
        @SerialName("is_active_playing") val isActivePlaying: Boolean = false
    )

    suspend fun signUpWithUsername(username: String, password: String): SupabaseAuthSession =
        withContext(Dispatchers.IO) {
            require(isConfigured()) { "Supabase URL and Anon Key must be configured first." }
            val cleanUser = username.trim()
            require(cleanUser.length >= 2) { "Username must have at least 2 characters." }
            val email = syntheticEmailForUsername(cleanUser)

            val payload = buildJsonObject {
                put("email", email)
                put("password", password)
                put("data", buildJsonObject { put("username", cleanUser) })
            }
            val respJson = executeRequest(
                method = "POST",
                path = "/auth/v1/signup",
                body = payload.toString(),
                useUserToken = false
            )
            var parsed = json.decodeFromString<AuthTokenResponse>(respJson)
            if (parsed.accessToken.isBlank()) {
                return@withContext signInWithUsername(cleanUser, password)
            }
            val userId = parsed.user?.id.orEmpty()
            val tempSession = SupabaseAuthSession(
                userId = userId,
                username = cleanUser,
                email = email,
                accessToken = parsed.accessToken,
                refreshToken = parsed.refreshToken,
                pairSecretHmac = ""
            )
            session = tempSession

            // Ensure profile row exists / retrieve trigger-created HMAC pair secret
            val profile = fetchProfile(userId)
            val finalSecret = profile?.pairSecretHmac?.takeIf { it.isNotBlank() }
                ?: RemoteSyncContract.computeHmacHex(userId, cleanUser)
            if (profile == null) {
                runCatching {
                    executeRequest(
                        method = "POST",
                        path = "/rest/v1/profiles",
                        body = buildJsonObject {
                            put("user_id", userId)
                            put("username", cleanUser)
                            put("email", email)
                            put("pair_secret_hmac", finalSecret)
                        }.toString(),
                        extraHeaders = mapOf("Prefer" to "resolution=merge-duplicates")
                    )
                }
            }
            tempSession.copy(pairSecretHmac = finalSecret).also { session = it }
        }

    suspend fun signInWithUsername(username: String, password: String): SupabaseAuthSession =
        withContext(Dispatchers.IO) {
            require(isConfigured()) { "Supabase URL and Anon Key must be configured first." }
            val cleanUser = username.trim()
            val rpcEmail = runCatching {
                val rpcResp = executeRequest(
                    method = "POST",
                    path = "/rest/v1/rpc/lookup_email_for_username",
                    body = buildJsonObject { put("p_username", cleanUser) }.toString(),
                    useUserToken = false
                ).trim().trim('"')
                rpcResp.takeIf { it.contains("@") && it != "null" }
            }.getOrNull()

            val email = rpcEmail ?: syntheticEmailForUsername(cleanUser)
            val respJson = executeRequest(
                method = "POST",
                path = "/auth/v1/token?grant_type=password",
                body = buildJsonObject {
                    put("email", email)
                    put("password", password)
                }.toString(),
                useUserToken = false
            )
            val parsed = json.decodeFromString<AuthTokenResponse>(respJson)
            val userId = parsed.user?.id.orEmpty()
            val tempSession = SupabaseAuthSession(
                userId = userId,
                username = cleanUser,
                email = email,
                accessToken = parsed.accessToken,
                refreshToken = parsed.refreshToken,
                pairSecretHmac = ""
            )
            session = tempSession
            val profile = fetchProfile(userId)
            tempSession.copy(
                username = profile?.username?.ifBlank { cleanUser } ?: cleanUser,
                pairSecretHmac = profile?.pairSecretHmac.orEmpty()
            ).also { session = it }
        }

    suspend fun restoreWithRefreshToken(
        refreshToken: String,
        fallbackUsername: String
    ): SupabaseAuthSession? = withContext(Dispatchers.IO) {
        if (!isConfigured() || refreshToken.isBlank()) return@withContext null
        runCatching {
            val respJson = executeRequest(
                method = "POST",
                path = "/auth/v1/token?grant_type=refresh_token",
                body = buildJsonObject { put("refresh_token", refreshToken) }.toString(),
                useUserToken = false
            )
            val parsed = json.decodeFromString<AuthTokenResponse>(respJson)
            val userId = parsed.user?.id.orEmpty()
            val temp = SupabaseAuthSession(
                userId = userId,
                username = fallbackUsername,
                email = parsed.user?.email.orEmpty(),
                accessToken = parsed.accessToken,
                refreshToken = parsed.refreshToken,
                pairSecretHmac = ""
            )
            session = temp
            val profile = fetchProfile(userId)
            temp.copy(
                username = profile?.username?.ifBlank { fallbackUsername } ?: fallbackUsername,
                pairSecretHmac = profile?.pairSecretHmac.orEmpty()
            ).also { session = it }
        }.getOrNull()
    }

    private fun fetchProfile(userId: String): ProfileRowDto? {
        return runCatching {
            val raw = executeRequest(
                method = "GET",
                path = "/rest/v1/profiles?user_id=eq.$userId&select=user_id,username,pair_secret_hmac"
            )
            json.decodeFromString<List<ProfileRowDto>>(raw).firstOrNull()
        }.getOrNull()
    }

    suspend fun upsertDevice(
        deviceId: String,
        deviceName: String,
        lanHost: String?,
        lanPort: Int,
        isActivePlaying: Boolean
    ) = withContext(Dispatchers.IO) {
        val activeSession = session ?: return@withContext
        if (!isConfigured()) return@withContext
        runCatching {
            val body = buildJsonObject {
                put("device_id", deviceId)
                put("user_id", activeSession.userId)
                put("device_name", deviceName)
                put("device_type", "ANDROID")
                if (lanHost != null) put("lan_host", lanHost)
                put("lan_port", lanPort)
                put("is_active_playing", isActivePlaying)
            }
            executeRequest(
                method = "POST",
                path = "/rest/v1/devices?on_conflict=device_id",
                body = body.toString(),
                extraHeaders = mapOf("Prefer" to "resolution=merge-duplicates")
            )
        }
    }

    suspend fun fetchAccountDevices(): List<RemoteSyncContract.ConnectedDeviceNode> =
        withContext(Dispatchers.IO) {
            val activeSession = session ?: return@withContext emptyList()
            if (!isConfigured()) return@withContext emptyList()
            runCatching {
                val raw = executeRequest(
                    method = "GET",
                    path = "/rest/v1/devices?user_id=eq.${activeSession.userId}&select=device_id,device_name,device_type,lan_host,lan_port,is_active_playing"
                )
                json.decodeFromString<List<DeviceRowDto>>(raw).map { row ->
                    RemoteSyncContract.ConnectedDeviceNode(
                        deviceId = row.deviceId,
                        deviceName = row.deviceName,
                        deviceType = row.deviceType.ifBlank { "DESKTOP" },
                        accountId = activeSession.userId,
                        isThisDevice = false,
                        isActivePlayingDevice = row.isActivePlaying,
                        lanHost = row.lanHost,
                        lanPort = row.lanPort
                    )
                }
            }.getOrDefault(emptyList())
        }

    suspend fun updateAccountSettings(
        syncMode: RemoteSyncContract.AccountDeviceSyncMode,
        activeDeviceId: String
    ) = withContext(Dispatchers.IO) {
        val activeSession = session ?: return@withContext
        if (!isConfigured()) return@withContext
        runCatching {
            val body = buildJsonObject {
                put("user_id", activeSession.userId)
                put("sync_mode", syncMode.name)
                put("active_controlling_device_id", activeDeviceId)
            }
            executeRequest(
                method = "POST",
                path = "/rest/v1/account_settings?on_conflict=user_id",
                body = body.toString(),
                extraHeaders = mapOf("Prefer" to "resolution=merge-duplicates")
            )
        }
    }

    suspend fun syncPlaylists(records: List<RemoteSyncContract.PlaylistSyncRecord>) =
        withContext(Dispatchers.IO) {
            val activeSession = session ?: return@withContext
            if (!isConfigured()) return@withContext
            for (rec in records) {
                runCatching {
                    val itemsJson = JsonArray(
                        rec.items.map { item ->
                            buildJsonObject {
                                put("positionIndex", item.positionIndex)
                                put("normalizedKey", item.normalizedKey)
                                put("relativePath", item.relativePath)
                                put("title", item.title)
                                put("artist", item.artist)
                                put("album", item.album)
                                put("durationMs", item.durationMs)
                            }
                        }
                    )
                    val body = buildJsonObject {
                        put("user_id", activeSession.userId)
                        put("name", rec.name)
                        put("items", itemsJson)
                        put("client_updated_at", rec.clientUpdatedAt)
                    }
                    executeRequest(
                        method = "POST",
                        path = "/rest/v1/playlists?on_conflict=user_id,name",
                        body = body.toString(),
                        extraHeaders = mapOf("Prefer" to "resolution=merge-duplicates")
                    )
                }
            }
        }

    suspend fun fetchPlaylists(): List<RemoteSyncContract.PlaylistSyncRecord> =
        withContext(Dispatchers.IO) {
            val activeSession = session ?: return@withContext emptyList()
            if (!isConfigured()) return@withContext emptyList()
            runCatching {
                val raw = executeRequest(
                    method = "GET",
                    path = "/rest/v1/playlists?user_id=eq.${activeSession.userId}&select=name,items,client_updated_at"
                )
                json.decodeFromString<List<PlaylistRowDto>>(raw).map { row ->
                    RemoteSyncContract.PlaylistSyncRecord(
                        name = row.name,
                        clientUpdatedAt = row.clientUpdatedAt,
                        items = row.items
                    )
                }
            }.getOrDefault(emptyList())
        }

    fun connectRealtime(
        accountId: String,
        onConnectedChanged: (Boolean) -> Unit,
        onEnvelope: (RemoteSyncContract.SyncWireEnvelope) -> Unit
    ) {
        if (!isConfigured()) return
        disconnectRealtime()
        val topic = "realtime:musika:${RemoteSyncContract.shortAccountHash(accountId)}"
        currentTopic = topic

        val wsSchemeUrl = baseUrl
            .replace("https://", "wss://")
            .replace("http://", "ws://") +
            "/realtime/v1/websocket?apikey=$anonKey&vsn=1.0.0"

        val req = Request.Builder().url(wsSchemeUrl).build()
        realtimeSocket = httpClient.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val joinMsg = buildJsonObject {
                    put("topic", topic)
                    put("event", "phx_join")
                    put("payload", buildJsonObject {
                        put("config", buildJsonObject {
                            put("broadcast", buildJsonObject {
                                put("self", false)
                                put("ack", false)
                            })
                        })
                    })
                    put("ref", msgCounter.getAndIncrement().toString())
                }
                webSocket.send(joinMsg.toString())
                onConnectedChanged(true)

                heartbeatJob?.cancel()
                heartbeatJob = scope.launch {
                    while (true) {
                        delay(25_000L)
                        val hb = buildJsonObject {
                            put("topic", "phoenix")
                            put("event", "heartbeat")
                            put("payload", buildJsonObject {})
                            put("ref", msgCounter.getAndIncrement().toString())
                        }
                        webSocket.send(hb.toString())
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val obj = json.parseToJsonElement(text).jsonObject
                    val event = obj["event"]?.jsonPrimitive?.content
                    if (event == "broadcast") {
                        val innerPayload = obj["payload"]?.jsonObject?.get("payload") ?: return
                        val env = json.decodeFromJsonElement(
                            RemoteSyncContract.SyncWireEnvelope.serializer(),
                            innerPayload
                        )
                        onEnvelope(env)
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onConnectedChanged(false)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w("MusikaSupabase", "Realtime WS error: ${t.message}")
                onConnectedChanged(false)
            }
        })
    }

    fun sendRealtimeBroadcast(envelope: RemoteSyncContract.SyncWireEnvelope) {
        val sock = realtimeSocket ?: return
        val topic = currentTopic.ifBlank { return }
        val envJson = json.encodeToJsonElement(RemoteSyncContract.SyncWireEnvelope.serializer(), envelope)
        val msg = buildJsonObject {
            put("topic", topic)
            put("event", "broadcast")
            put("payload", buildJsonObject {
                put("type", "broadcast")
                put("event", "musika_sync")
                put("payload", envJson)
            })
            put("ref", msgCounter.getAndIncrement().toString())
        }
        sock.send(msg.toString())
    }

    fun disconnectRealtime() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        realtimeSocket?.close(1000, "disconnect")
        realtimeSocket = null
        currentTopic = ""
    }

    private fun executeRequest(
        method: String,
        path: String,
        body: String? = null,
        useUserToken: Boolean = true,
        extraHeaders: Map<String, String> = emptyMap()
    ): String {
        val builder = Request.Builder()
            .url(baseUrl + path)
            .addHeader("apikey", anonKey)
            .addHeader("Content-Type", "application/json")
        val bearer = if (useUserToken && !session?.accessToken.isNullOrBlank()) {
            session!!.accessToken
        } else {
            anonKey
        }
        builder.addHeader("Authorization", "Bearer $bearer")
        extraHeaders.forEach { (k, v) -> builder.addHeader(k, v) }

        val reqBody = body?.toRequestBody(jsonMedia)
        when (method) {
            "POST" -> builder.post(reqBody ?: "".toRequestBody(jsonMedia))
            "PATCH" -> builder.patch(reqBody ?: "".toRequestBody(jsonMedia))
            else -> builder.get()
        }

        httpClient.newCall(builder.build()).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("Supabase HTTP ${resp.code}: $raw")
            }
            return raw
        }
    }
}
