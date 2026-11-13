package com.musika.player.core.sync

import com.musika.player.core.model.AudioQuality
import com.musika.player.core.model.LoopMode
import com.musika.player.core.model.RandomMode
import com.musika.player.core.model.Track
import com.musika.player.player.DualQueueEngine
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory

class LanAndSupabaseTransportTest {

    private fun makeTrack(
        path: String,
        title: String,
        format: String,
        bitrate: Int,
        durationMs: Long = 210_000L
    ): Track {
        return Track(
            filePath = path,
            relativePath = path.removePrefix("/Music/"),
            fileName = path.substringAfterLast('/'),
            fileExtension = format.lowercase(),
            title = title,
            artist = "Justice",
            album = "Cross",
            discNumber = 1,
            totalDiscs = 1,
            trackNumber = 1,
            durationMs = durationMs,
            audioQuality = AudioQuality(
                format = format,
                bitrateKbps = bitrate,
                sampleRateHz = if (format == "FLAC") 96000 else 44100,
                bitDepth = if (format == "FLAC") 24 else 16,
                isLossless = format == "FLAC"
            ),
            parentFolderPath = path.substringBeforeLast('/')
        )
    }

    @Test
    fun `embedded LanSyncServer enforces PIN pairing, HMAC WebSocket auth, and default-OFF stream guard`() {
        val tempRoot = createTempDirectory("musika-lan-unit-").toFile()
        val audioFile = File(tempRoot, "genesis.flac")
        audioFile.writeBytes("fLaC0123456789AUDIO".toByteArray())

        var allowStreaming = false
        val sharedSecret = "00112233445566778899aabbccddeeff"
        val receivedEnvelopes = mutableListOf<RemoteSyncContract.SyncWireEnvelope>()

        val engine = DualQueueEngine()
        val sampleTrack = makeTrack(audioFile.absolutePath, "Genesis", "FLAC", 1100)
        engine.playFromContext(sampleTrack, listOf(sampleTrack), "Cross")

        val server = LanSyncServer(
            appContext = null,
            deviceId = "android-host-1",
            deviceNameProvider = { "Pixel 9" },
            accountIdProvider = { "acc-42" },
            usernameProvider = { "ale_user" },
            pairSecretProvider = { sharedSecret },
            pinCodeProvider = { "918273" },
            supabaseUrlProvider = { "https://demo.supabase.co" },
            supabaseAnonKeyProvider = { "anon-test-key" },
            rootPathProvider = { tempRoot.absolutePath },
            allowStreamingProvider = { allowStreaming },
            catalogProvider = { listOf(RemoteSyncContract.RemoteLibraryTrackSummary.fromTrack(sampleTrack)) },
            snapshotProvider = { engine.toPlaybackSnapshot() },
            onIncomingEnvelope = { env -> receivedEnvelopes.add(env) }
        )

        val port = server.start(preferredPort = 0, host = "127.0.0.1")
        val client = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).build()
        try {
            // 1. /api/discovery
            client.newCall(Request.Builder().url("http://127.0.0.1:$port/api/discovery").build()).execute().use { resp ->
                assertEquals(200, resp.code)
                val body = resp.body?.string().orEmpty()
                assertTrue(body.contains("android-host-1"))
            }

            // 2. /api/pair/pin blocks wrong PIN & issues QR payload on valid PIN
            val mediaType = "application/json; charset=utf-8".toMediaType()
            client.newCall(
                Request.Builder()
                    .url("http://127.0.0.1:$port/api/pair/pin")
                    .post("""{"pinCode":"000000"}""".toRequestBody(mediaType))
                    .build()
            ).execute().use { resp ->
                assertEquals(403, resp.code)
            }

            client.newCall(
                Request.Builder()
                    .url("http://127.0.0.1:$port/api/pair/pin")
                    .post("""{"pinCode":"918273","deviceId":"pc-2"}""".toRequestBody(mediaType))
                    .build()
            ).execute().use { resp ->
                assertEquals(200, resp.code)
                val parsed = RemoteSyncContract.QrPairPayload.parse(resp.body?.string().orEmpty())
                assertEquals("ale_user", parsed?.username)
                assertEquals(sharedSecret, parsed?.pairSecretHmac)
            }

            // 3. /stream is 403 while allowStreaming == false; allows Range 206 when ON and blocks path traversal
            client.newCall(Request.Builder().url("http://127.0.0.1:$port/stream?path=genesis.flac").build()).execute().use { resp ->
                assertEquals(403, resp.code)
            }

            allowStreaming = true
            client.newCall(Request.Builder().url("http://127.0.0.1:$port/stream?path=../secret.flac").build()).execute().use { resp ->
                assertEquals(403, resp.code)
            }

            client.newCall(
                Request.Builder()
                    .url("http://127.0.0.1:$port/stream?path=genesis.flac")
                    .addHeader("Range", "bytes=0-3")
                    .build()
            ).execute().use { resp ->
                assertEquals(206, resp.code)
                assertEquals("fLaC", resp.body?.string())
            }

            // 4. OnlineControlCoordinator connects cleanly over LAN WebSocket via HMAC handshake
            val peerEngine = DualQueueEngine()
            val coordinator = OnlineControlCoordinator(
                localEngine = peerEngine,
                localDeviceId = "pc-peer-2",
                localDeviceName = "Linux PC"
            )
            val qrLink = RemoteSyncContract.QrPairPayload(
                accountId = "acc-42",
                username = "ale_user",
                pairSecretHmac = sharedSecret,
                lanHost = "127.0.0.1",
                lanPort = port
            ).toQrDeepLink()

            runBlocking {
                val msg = coordinator.pairViaQrOrPinInput(qrLink)
                assertTrue(msg.contains("ale_user"))
            }
            assertEquals(
                RemoteSyncContract.ConnectionTransportMode.LAN_DIRECT,
                coordinator.state.value.transportMode
            )

            // Sending command delivers over verified WS socket
            coordinator.sendRemoteCommand(
                command = RemoteSyncContract.SyncCommand.SkipNext,
                localLibrary = emptyList(),
                targetDeviceId = "android-host-1"
            )
            Thread.sleep(150)
            assertTrue(receivedEnvelopes.any { it.command is RemoteSyncContract.SyncCommand.SkipNext })
            coordinator.stopNetworking()
        } finally {
            server.stop()
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `tryLanFirstThenOnlineFallback fails over to Supabase Online and prevents synced echo when monitor mode is OFF`() {
        val mockServer = MockWebServer()
        mockServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/realtime/v1/websocket") -> {
                        MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                            override fun onMessage(webSocket: WebSocket, text: String) {
                                if (text.contains("phx_join")) {
                                    webSocket.send("""{"topic":"phoenix","event":"phx_reply","payload":{"status":"ok"},"ref":"1"}""")
                                }
                            }
                        })
                    }
                    path.startsWith("/rest/v1/profiles") -> {
                        MockResponse()
                            .setResponseCode(200)
                            .setBody("""[{"user_id":"uid-1","username":"ale_cloud","pair_secret_hmac":"cafebabe"}]""")
                    }
                    path.startsWith("/auth/v1/token") -> {
                        MockResponse()
                            .setResponseCode(200)
                            .setBody("""{"access_token":"jwt-1","refresh_token":"ref-1","user":{"id":"uid-1","email":"ale_cloud@musika-player.org"}}""")
                    }
                    path.startsWith("/rest/v1/devices") -> {
                        MockResponse().setResponseCode(200).setBody("[]")
                    }
                    else -> MockResponse().setResponseCode(200).setBody("{}")
                }
            }
        }
        mockServer.start()

        try {
            var loadedAudioCount = 0
            val engine = DualQueueEngine(RandomMode.OFF, LoopMode.NO_LOOP)
            engine.playbackDriver = object : DualQueueEngine.PlaybackDriver {
                override fun onLoadAndPlayTrack(track: Track, startPositionMs: Long, playWhenReady: Boolean) {
                    if (playWhenReady) loadedAudioCount++
                }
                override fun onPlayStateChanged(isPlaying: Boolean) {}
                override fun onSeekTo(positionMs: Long) {}
            }

            val coordinator = OnlineControlCoordinator(
                localEngine = engine,
                localDeviceId = "phone-local",
                localDeviceName = "Phone Local"
            )
            coordinator.updateSupabaseConfig(mockServer.url("/").toString(), "anon-test")

            runBlocking {
                coordinator.signInUsername("ale_cloud", "pass1234")
                // Connect to dead LAN port 19999 -> must fall back automatically to ONLINE_SUPABASE
                val lanOk = coordinator.tryLanFirstThenOnlineFallback("127.0.0.1", 19999)
                assertFalse(lanOk)
                Thread.sleep(200)
            }

            assertEquals(
                RemoteSyncContract.ConnectionTransportMode.ONLINE_SUPABASE,
                coordinator.state.value.transportMode
            )

            // Simulate remote desktop playing while monitorLocalAudio == false (OFF by default)
            coordinator.setBrowsingDeviceId("desktop-1")
            val flacTrack = makeTrack("/Music/Cross/01.flac", "Genesis", "FLAC", 1100)
            coordinator.updateLocalLibrary(listOf(flacTrack), "/Music")

            runBlocking {
                // Transfer playback to desktop-1 so desktop-1 is active controller
                coordinator.registerOrUpdatePeerDevice(
                    deviceId = "desktop-1",
                    deviceName = "PC",
                    deviceType = "DESKTOP",
                    loadedTrackKeys = listOf(flacTrack.normalizedKey)
                )
                engine.playFromContext(flacTrack, listOf(flacTrack), "Cross")
                val beforeTransferLoads = loadedAudioCount
                val transferRes = coordinator.transferPlaybackToDevice("desktop-1", listOf(flacTrack))
                assertTrue(transferRes.allowed)

                // Now desktop sends PlayNow back under SYNCED_PLAYBACK with listenLocally == false
                val env = RemoteSyncContract.SyncWireEnvelope(
                    type = "command",
                    senderDeviceId = "desktop-1",
                    senderDeviceName = "PC",
                    senderDeviceType = "DESKTOP",
                    command = RemoteSyncContract.SyncCommand.PlayNow(
                        track = RemoteSyncContract.RemoteTrackDescriptor.fromTrack(flacTrack),
                        contextTracks = listOf(RemoteSyncContract.RemoteTrackDescriptor.fromTrack(flacTrack))
                    )
                )
                coordinator.handleIncomingEnvelope(env)
                // Mirror UI updated without starting local speakers (no echo!)
                assertEquals(beforeTransferLoads, loadedAudioCount)
            }
            coordinator.stopNetworking()
            Thread.sleep(100)
        } finally {
            runCatching { mockServer.shutdown() }
        }
    }
}
