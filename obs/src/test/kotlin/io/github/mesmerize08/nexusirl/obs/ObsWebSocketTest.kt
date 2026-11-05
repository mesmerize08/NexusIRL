// Wire format per the obs-websocket 5.x protocol (docs/generated/protocol.md) and upstream
// Moblin/Obs/ObsWebSocket.swift @97e26d2.
@file:Suppress("ktlint:standard:max-line-length")

package io.github.mesmerize08.nexusirl.obs

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ObsWebSocketTest {
    private class Events : ObsWebSocketListener {
        val queue = LinkedBlockingQueue<String>()

        override fun connected() {
            queue += "connected"
        }

        override fun disconnected() {
            queue += "disconnected"
        }

        override fun connectionFailed(message: String) {
            queue += "failed $message"
        }

        override fun sceneChanged(sceneName: String) {
            queue += "scene $sceneName"
        }

        override fun inputMuteStateChanged(inputName: String, muted: Boolean) {
            queue += "mute $inputName $muted"
        }

        override fun streamStatusChanged(active: Boolean, state: ObsOutputState?) {
            queue += "stream $active $state"
        }

        override fun recordStatusChanged(active: Boolean, state: ObsOutputState?) {
            queue += "record $active $state"
        }

        override fun audioVolume(volumes: List<ObsAudioInputVolume>) {
            queue += "volume " + volumes.joinToString { "${it.name}=${it.volumes}" }
        }

        fun next(): String? = queue.poll(5, TimeUnit.SECONDS)
    }

    private fun <T> await(call: ((Result<T>) -> Unit) -> Unit): Result<T> {
        val future = CompletableFuture<Result<T>>()
        call { future.complete(it) }
        return future.get(5, TimeUnit.SECONDS)
    }

    private fun connect(fake: FakeObs, password: String = "", events: Events = Events()): Pair<ObsWebSocket, Events> {
        val obs = ObsWebSocket(fake.url, password, events)
        obs.start()
        assertEquals("connected", events.next())
        return obs to events
    }

    @Test
    fun authenticationLikeObsWebSocket() {
        // base64(SHA256(base64(SHA256("passwordsalt")) + "challenge")), computed independently.
        assertEquals("zTM5ki6L2vVvBQiTG9ckH1Lh64AbnCf6XZ226UmnkIA=", obsAuthentication("password", "salt", "challenge"))
    }

    @Test
    fun identifiesWithAuthentication() {
        val hello = """{"op":0,"d":{"obsWebSocketVersion":"5.5.2","rpcVersion":1,"authentication":{"challenge":"challenge","salt":"salt"}}}"""
        FakeObs(hello = hello, accept = {
            it["authentication"]?.jsonPrimitive?.content == obsAuthentication("password", "salt", "challenge")
        })
            .use { fake ->
                val (obs, _) = connect(fake, "password")
                try {
                    val identify = fake.next(1)
                    assertEquals(JsonPrimitive(1), identify["rpcVersion"])
                    // Default subscriptions (all but the high-volume ones), as upstream.
                    assertNull(identify["eventSubscriptions"])
                    assertTrue(obs.isConnected)
                } finally {
                    obs.stop()
                }
            }
    }

    @Test
    fun identifiesWithoutAuthenticationWhenNotRequired() {
        FakeObs().use { fake ->
            val (obs, _) = connect(fake)
            try {
                assertEquals("""{"rpcVersion":1}""", fake.next(1).toString())
            } finally {
                obs.stop()
            }
        }
    }

    @Test
    fun wrongPasswordIsReported() {
        val hello = """{"op":0,"d":{"rpcVersion":1,"authentication":{"challenge":"c","salt":"s"}}}"""
        FakeObs(hello = hello, accept = { false }).use { fake ->
            val events = Events()
            val obs = ObsWebSocket(fake.url, "wrong", events)
            try {
                obs.start()
                fake.next(1)
                assertEquals("failed Authentication failed.", events.next())
                assertEquals("Authentication failed.", obs.connectionErrorMessage)
                assertFalse(obs.isConnected)
            } finally {
                obs.stop()
            }
        }
    }

    @Test
    fun anUnreachableServerIsReported() {
        // Nothing listens on this port (as with a firewall dropping or refusing the connection):
        // the app must say so instead of showing "Connecting" for ever.
        val port = java.net.ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress()).use { it.localPort }
        val events = Events()
        val obs = ObsWebSocket("ws://127.0.0.1:$port", "", events)
        try {
            obs.start()
            val event = events.next() ?: "nothing within 5 s (still \"Connecting\")"
            assertTrue(event.startsWith("failed ") && event.length > "failed ".length, event)
            assertFalse(obs.isConnected)
        } finally {
            obs.stop()
        }
    }

    @Test
    fun connectionErrorIsNotStaleAndClearsOnceIdentified() {
        lateinit var fake: FakeObs
        var identifies = 0
        fake = FakeObs(accept = {
            identifies += 1
            when (identifies) {
                1 -> false
                // Dropped without a close frame: no reason given.
                2 -> {
                    fake.socket!!.cancel()
                    true
                }
                else -> true
            }
        })
        fake.use {
            val events = Events()
            val obs = ObsWebSocket(fake.url, "", events)
            try {
                obs.start()
                assertEquals("failed Authentication failed.", events.next())
                assertEquals("failed Disconnected", events.next())
                assertEquals("connected", events.next())
                assertEquals("", obs.connectionErrorMessage)
            } finally {
                obs.stop()
            }
        }
    }

    @Test
    fun stoppedSocketNeverBecomesConnected() {
        // Stops while OBS's Identified is on its way, again and again.
        FakeObs(connections = 300).use { fake ->
            repeat(300) { attempt ->
                val events = Events()
                val obs = ObsWebSocket(fake.url, "", events)
                obs.start()
                fake.next(1)
                val spinUntil = System.nanoTime() + (attempt % 30) * 20_000L
                while (System.nanoTime() < spinUntil) {
                    Thread.onSpinWait()
                }
                obs.stop()
                Thread.sleep(5)
                assertFalse(obs.isConnected, "attempt $attempt")
                assertEquals("Not connected to server", await { obs.getSceneList(it) }.exceptionOrNull()?.message)
            }
        }
    }

    @Test
    fun requestsFailWhenNotConnected() {
        val obs = ObsWebSocket("ws://127.0.0.1:1", "", Events())
        assertEquals("Not connected to server", await { obs.getSceneList(it) }.exceptionOrNull()?.message)
    }

    @Test
    fun sceneListIsReversedLikeObsShowsIt() {
        FakeObs(respond = { type, _ ->
            assertEquals("GetSceneList", type)
            """"requestStatus":{"result":true,"code":100},"responseData":{"currentProgramSceneName":"Main",""" +
                """"scenes":[{"sceneIndex":0,"sceneName":"BRB"},{"sceneIndex":1,"sceneName":"Main"}]}"""
        }).use { fake ->
            val (obs, _) = connect(fake)
            try {
                val list = await { obs.getSceneList(it) }.getOrThrow()
                assertEquals(ObsSceneList("Main", listOf("Main", "BRB")), list)
                val request = fake.next(6)
                assertEquals("GetSceneList", request["requestType"]?.jsonPrimitive?.content)
                // Request ids are strings in obs-websocket 5.
                assertTrue(request.getValue("requestId").jsonPrimitive.isString)
                assertNull(request["requestData"])
            } finally {
                obs.stop()
            }
        }
    }

    @Test
    fun requestDataAndErrors() {
        FakeObs(respond = { type, _ ->
            when (type) {
                "StartStream" -> """"requestStatus":{"result":false,"code":500}"""
                "SetCurrentProgramScene" -> """"requestStatus":{"result":false,"code":600,"comment":"No source was found by the name of `X`."}"""
                "StopRecord" -> """"requestStatus":{"result":false,"code":999}"""
                else -> """"requestStatus":{"result":true,"code":100}"""
            }
        }).use { fake ->
            val (obs, _) = connect(fake)
            try {
                assertEquals("Already streaming", await { obs.startStream(it) }.exceptionOrNull()?.message)
                fake.next(6)
                assertEquals(
                    "Operation failed with resourceNotFound (No source was found by the name of `X`.)",
                    await { obs.setCurrentProgramScene("X", it) }.exceptionOrNull()?.message,
                )
                assertEquals("""{"sceneName":"X"}""", fake.next(6)["requestData"].toString())
                assertEquals("Operation failed with unknown", await { obs.stopRecord(it) }.exceptionOrNull()?.message)
                fake.next(6)
                assertTrue(await { obs.setInputMute("Mic", true, it) }.isSuccess)
                assertEquals("""{"inputName":"Mic","inputMuted":true}""", fake.next(6)["requestData"].toString())
                assertTrue(await { obs.setInputAudioSyncOffset("Moblin", -200, it) }.isSuccess)
                assertEquals("""{"inputName":"Moblin","inputAudioSyncOffset":-200}""", fake.next(6)["requestData"].toString())
                assertTrue(await { obs.restartInput("Moblin", it) }.isSuccess)
                assertEquals("""{"inputName":"Moblin","inputSettings":{}}""", fake.next(6)["requestData"].toString())
                assertTrue(await { obs.setMediaSourceSettings("Cam", "srt://1.2.3.4:4000", it) }.isSuccess)
                assertEquals(
                    """{"inputName":"Cam","inputSettings":{"input":"srt://1.2.3.4:4000"}}""",
                    fake.next(6)["requestData"].toString(),
                )
            } finally {
                obs.stop()
            }
        }
    }

    @Test
    fun statusQueries() {
        FakeObs(respond = { type, data ->
            val response = when (type) {
                "GetStreamStatus" -> """{"outputActive":true,"outputReconnecting":false,"outputTimecode":"00:00:01.000"}"""
                "GetRecordStatus" -> """{"outputActive":false,"outputPaused":false}"""
                "GetInputAudioSyncOffset" -> """{"inputAudioSyncOffset":${if (data?.get(
                        "inputName",
                    )?.jsonPrimitive?.content == "Moblin"
                ) {
                    150
                } else {
                    0
                }}}"""
                "GetInputList" -> """{"inputs":[{"inputName":"Mic/Aux","inputKind":"wasapi_input_capture"},{"inputName":"Moblin"}]}"""
                "GetSpecialInputs" -> """{"desktop1":"Desktop Audio","desktop2":null,"mic1":"Mic/Aux","mic2":null,"mic3":null,"mic4":null}"""
                "GetSceneItemList" ->
                    """{"sceneItems":[{"sourceName":"Moblin","inputKind":"ffmpeg_source","sceneItemEnabled":true},""" +
                        """{"sourceName":"Group","inputKind":null,"sceneItemEnabled":false}]}"""
                else -> "{}"
            }
            """"requestStatus":{"result":true,"code":100},"responseData":$response"""
        }).use { fake ->
            val (obs, _) = connect(fake)
            try {
                assertEquals(true, await { obs.getStreamStatus(it) }.getOrThrow())
                assertEquals(false, await { obs.getRecordStatus(it) }.getOrThrow())
                assertEquals(150, await { obs.getInputAudioSyncOffset("Moblin", it) }.getOrThrow())
                assertEquals(listOf("Mic/Aux", "Moblin"), await { obs.getInputList(it) }.getOrThrow())
                assertEquals(listOf("Mic/Aux"), await { obs.getSpecialInputs(it) }.getOrThrow().mics)
                assertEquals(
                    listOf(ObsSceneItem("Moblin", "ffmpeg_source", true), ObsSceneItem("Group", null, false)),
                    await { obs.getSceneItemList("Main", it) }.getOrThrow(),
                )
            } finally {
                obs.stop()
            }
        }
    }

    @Test
    fun screenshotIsDecoded() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)
        val data = "data:image/jpg;base64," + Base64.getEncoder().encodeToString(jpeg)
        FakeObs(respond = { _, _ -> """"requestStatus":{"result":true,"code":100},"responseData":{"imageData":"$data"}""" }).use { fake ->
            val (obs, _) = connect(fake)
            try {
                assertContentEquals(jpeg, await { obs.getSourceScreenshot("Main", it) }.getOrThrow())
                assertEquals(
                    """{"sourceName":"Main","imageFormat":"jpg","imageWidth":640,"imageCompressionQuality":30}""",
                    fake.next(6)["requestData"].toString(),
                )
            } finally {
                obs.stop()
            }
        }
    }

    @Test
    fun batchRequests() {
        FakeObs(respond = { type, data ->
            when (data?.get("inputName")?.jsonPrimitive?.content) {
                "Missing" -> """"requestStatus":{"result":false,"code":600}"""
                else -> if (type == "GetInputMute") {
                    """"requestStatus":{"result":true,"code":100},"responseData":{"inputMuted":true}"""
                } else {
                    """"requestStatus":{"result":true,"code":100},"responseData":{"inputKind":"ffmpeg_source",""" +
                        """"inputSettings":{"input":"srt://0.0.0.0:4000","is_local_file":false}}"""
                }
            }
        }).use { fake ->
            val (obs, _) = connect(fake)
            try {
                assertEquals(listOf(true, null), await { obs.getInputMuteBatch(listOf("Mic", "Missing"), it) }.getOrThrow())
                val batch = fake.next(8)
                assertTrue(batch.getValue("requestId").jsonPrimitive.isString)
                assertEquals(
                    listOf(ObsMediaSourceSettings("srt://0.0.0.0:4000", false), null),
                    await { obs.getMediaSourcesSettingsBatch(listOf("Cam", "Missing"), it) }.getOrThrow(),
                )
            } finally {
                obs.stop()
            }
        }
    }

    @Test
    fun events() {
        FakeObs().use { fake ->
            val (obs, events) = connect(fake)
            try {
                fake.sendEvent("CurrentProgramSceneChanged", """{"sceneName":"BRB","sceneUuid":"x"}""")
                assertEquals("scene BRB", events.next())
                fake.sendEvent("StreamStateChanged", """{"outputActive":true,"outputState":"OBS_WEBSOCKET_OUTPUT_STARTED"}""")
                assertEquals("stream true STARTED", events.next())
                fake.sendEvent("StreamStateChanged", """{"outputActive":false,"outputState":"OBS_WEBSOCKET_OUTPUT_RECONNECTING"}""")
                assertEquals("stream false STOPPED", events.next())
                fake.sendEvent(
                    "RecordStateChanged",
                    """{"outputActive":true,"outputState":"OBS_WEBSOCKET_OUTPUT_PAUSED","outputPath":null}""",
                )
                assertEquals("record true STARTED", events.next())
                fake.sendEvent("InputMuteStateChanged", """{"inputName":"Mic","inputMuted":true}""")
                assertEquals("mute Mic true", events.next())
                fake.sendEvent("SceneCreated", """{"sceneName":"New"}""")
                fake.sendEvent("CurrentProgramSceneChanged", """{"sceneName":"Main"}""")
                assertEquals("scene Main", events.next())
            } finally {
                obs.stop()
            }
        }
    }

    @Test
    fun audioVolumeMetersAfterReidentify() {
        FakeObs().use { fake ->
            val (obs, events) = connect(fake)
            try {
                obs.startAudioVolume()
                // All (0x7FF) plus InputVolumeMeters (0x10000).
                assertEquals("""{"eventSubscriptions":67583}""", fake.next(3).toString())
                fake.sendEvent(
                    "InputVolumeMeters",
                    """{"inputs":[{"inputName":"Moblin","inputLevelsMul":[[0.1,0.1,0.2],[0.0,0.0,0.0]]}]}""",
                )
                assertEquals("volume Moblin=[-20.0, -Infinity]", events.next())
                obs.stopAudioVolume()
                assertEquals("""{"eventSubscriptions":2047}""", fake.next(3).toString())
            } finally {
                obs.stop()
            }
        }
    }

    @Test
    fun pendingRequestsFailAndReconnectsAfterDisconnect() {
        FakeObs(respond = { _, _ -> null }).use { fake ->
            val (obs, events) = connect(fake)
            try {
                val pending = CompletableFuture<Result<Unit>>()
                obs.startStream { pending.complete(it) }
                fake.next(6)
                fake.awaitSocket().close(1001, "OBS is closing")
                assertEquals("disconnected", events.next())
                assertEquals("Disconnected", pending.get(5, TimeUnit.SECONDS).exceptionOrNull()?.message)
                assertFalse(obs.isConnected)
                // Reconnects with backoff and identifies again.
                assertEquals("connected", events.next())
                fake.next(1)
            } finally {
                obs.stop()
            }
        }
    }
}
