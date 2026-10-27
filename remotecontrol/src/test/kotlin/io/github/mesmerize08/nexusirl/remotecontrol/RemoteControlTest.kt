// Golden JSON follows upstream MoblinTests/Moblin/RemoteControl/RemoteControlSuite.swift @97e26d2
// and Swift's synthesized Codable rules.
@file:Suppress("ktlint:standard:max-line-length")

package io.github.mesmerize08.nexusirl.remotecontrol

import io.github.mesmerize08.nexusirl.core.codable.SwiftJson
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.Base64
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteControlTest {
    private fun toStreamer(json: String) = RemoteControlMessageToStreamer.fromJson(json)

    @Test
    fun passwordHash() {
        // base64(SHA256(base64(SHA256("passwordsalt")) + "challenge")), computed independently.
        assertEquals("zTM5ki6L2vVvBQiTG9ckH1Lh64AbnCf6XZ226UmnkIA=", remoteControlHashPassword("challenge", "salt", "password"))
    }

    @Test
    fun encryption() {
        val encryption = RemoteControlEncryption("secret")
        val sealed = encryption.encrypt("token".toByteArray())
        assertEquals(12 + 5 + 16, sealed.size)
        assertContentEquals("token".toByteArray(), encryption.decrypt(sealed))
        assertNull(RemoteControlEncryption("other").decrypt(sealed))
        assertNull(encryption.decrypt(ByteArray(10)))
    }

    @Test
    fun messagesFromAssistant() {
        val hello = toStreamer("""{"hello":{"apiVersion":"0.1","authentication":{"challenge":"c","salt":"s"}}}""")
        assertEquals(RemoteControlMessageToStreamer.Hello("0.1", RemoteControlAuthentication("c", "s")), hello)
        assertEquals(
            RemoteControlMessageToStreamer.Identified(RemoteControlResult.WRONG_PASSWORD),
            toStreamer("""{"identified":{"result":{"wrongPassword":{}}}}"""),
        )
        assertEquals(RemoteControlMessageToStreamer.Pong, toStreamer("""{"pong":{}}"""))
        val id = UUID.fromString("0B3B5C3E-6D5A-4D47-9D7B-6B7C1D8F2A11")
        val requests = mapOf(
            """{"getStatus":{}}""" to RemoteControlRequest.GetStatus,
            """{"setZoom":{"x":2.5}}""" to RemoteControlRequest.SetZoom(2.5f),
            """{"setScene":{"id":"0B3B5C3E-6D5A-4D47-9D7B-6B7C1D8F2A11"}}""" to RemoteControlRequest.SetScene(id),
            """{"setFilter":{"filter":{"grayScale":{}},"on":true}}""" to RemoteControlRequest.SetFilter(RemoteControlFilter.GRAY_SCALE, true),
            """{"startStatus":{"interval":1,"filter":{"topRight":false}}}""" to RemoteControlRequest.StartStatus(1, RemoteControlStartStatusFilter(false)),
            """{"sendMessage":{"text":"Hello"}}""" to RemoteControlRequest.SendMessage("Hello"),
            """{"setMic":{"id":"15:Built-in"}}""" to RemoteControlRequest.SetMic("15:Built-in"),
            """{"setBitratePreset":{"id":"0B3B5C3E-6D5A-4D47-9D7B-6B7C1D8F2A11"}}""" to RemoteControlRequest.SetBitratePreset(id),
            """{"setGimbalTracking":{"on":true}}""" to RemoteControlRequest.Unknown,
            """{"music":{"command":{"play":{}}}}""" to RemoteControlRequest.Unknown,
        )
        for ((json, request) in requests) {
            assertEquals(RemoteControlMessageToStreamer.Request(7, request), toStreamer("""{"request":{"id":7,"data":$json}}"""), json)
        }
    }

    @Test
    fun forwardedChatAndEvents() {
        // Shapes as sent by upstream tests/utils/moblin.py (the harness chat suite).
        val chat = toStreamer(
            """{"request":{"id":3,"data":{"chatMessages":{"history":false,"messages":[""" +
                """{"id":1761800000123,"platform":{"twitch":{}},"displayName":"Viewer","user":"viewer","userBadges":[],""" +
                """"segments":[{"id":0,"text":"Hello "},{"id":1,"text":"there "}],"timestamp":"12:34","isAction":false,""" +
                """"isModerator":true,"isSubscriber":false,"isOwner":false,""" +
                """"highlight":{"kind":{"newFollower":{}},"barColor":{"red":255,"green":0,"blue":128},"image":"heart"}},""" +
                """{"id":1761800000124,"platform":{"someNewPlatform":{}},"segments":[]}]}}}}""",
        )
        val messages = ((chat as RemoteControlMessageToStreamer.Request).data as RemoteControlRequest.ChatMessages).messages
        assertEquals(2, messages.size)
        val first = messages[0]
        assertEquals(1761800000123, first.id)
        assertEquals(RemoteControlPlatform.TWITCH, first.platform)
        assertEquals(listOf("Hello ", "there "), first.segments.map { it.text })
        assertTrue(first.isModerator)
        assertEquals(RemoteControlChatHighlightKind.NEW_FOLLOWER, first.highlight?.kind)
        assertEquals(RemoteControlRgbColor(255, 0, 128), first.highlight?.barColor)
        assertEquals(RemoteControlPlatform.TWITCH, messages[1].platform)
        assertEquals(
            RemoteControlMessageToStreamer.Request(4, RemoteControlRequest.TwitchEventSubNotification("{\"metadata\":{}}")),
            toStreamer("""{"request":{"id":4,"data":{"twitchEventSubNotification":{"message":"{\"metadata\":{}}"}}}}"""),
        )
    }

    @Test
    fun messagesToAssistant() {
        assertEquals(
            """{"identify":{"streamerId":"S","authentication":"h"}}""",
            RemoteControlMessageToAssistant.Identify("S", "h").toJson(),
        )
        assertEquals("""{"ping":{}}""", RemoteControlMessageToAssistant.Ping.toJson())
        assertEquals(
            """{"response":{"id":3,"result":{"unknownRequest":{}}}}""",
            RemoteControlMessageToAssistant.Response(3, RemoteControlResult.UNKNOWN_REQUEST).toJson(),
        )
        assertEquals("""{"preview":{"preview":"AQID"}}""", RemoteControlMessageToAssistant.Preview(byteArrayOf(1, 2, 3)).toJson())
        val status = RemoteControlMessageToAssistant.Event(
            RemoteControlEvent.Status(
                general = RemoteControlStatusGeneral(batteryLevel = 80, flame = RemoteControlStatusGeneralFlame.YELLOW, isLive = true),
                topRight = RemoteControlStatusTopRight(
                    audioInfo = RemoteControlAudioInfo(RemoteControlAudioLevel.Value(-12.5f), 1),
                    bitrate = RemoteControlStatusItem("5.0 Mbps"),
                ),
            ),
        )
        assertEquals(
            """{"event":{"data":{"status":{"general":{"batteryLevel":80,"flame":"Yellow","isLive":true},"topRight":{"audioInfo":{"audioLevel":{"value":{"_0":-12.5}},"numberOfAudioChannels":1},"bitrate":{"message":"5.0 Mbps","ok":true}}}}}}""",
            status.toJson(),
        )
        // Upstream encodes [RemoteControlFilter: Bool] as a flat array.
        val state = RemoteControlMessageToAssistant.Event(
            RemoteControlEvent.State(RemoteControlAssistantStreamerState(muted = true, filters = mapOf(RemoteControlFilter.SEPIA to true))),
        )
        assertEquals("""{"event":{"data":{"state":{"data":{"muted":true,"filters":[{"sepia":{}},true]}}}}}""", state.toJson())
        val settings = RemoteControlMessageToAssistant.Response(
            1,
            RemoteControlResult.OK,
            RemoteControlResponse.GetSettings(
                RemoteControlSettings(
                    streams = listOf(RemoteControlSettingsStream(UUID.fromString("0B3B5C3E-6D5A-4D47-9D7B-6B7C1D8F2A11"), "IRL")),
                    scenes = emptyList(),
                ),
            ),
        )
        assertEquals(
            """{"response":{"id":1,"result":{"ok":{}},"data":{"getSettings":{"data":{"streams":[{"id":"0B3B5C3E-6D5A-4D47-9D7B-6B7C1D8F2A11","name":"IRL"}],"scenes":[],"bitratePresets":[],"mics":[],"srt":{"connectionPrioritiesEnabled":false,"connectionPriorities":[]}}}}}}""",
            settings.toJson(),
        )
        // Round trip through the same serializer the assistant side would use.
        assertEquals(status, SwiftJson.decodeFromString(RemoteControlMessageToAssistant.Serializer, status.toJson()))
    }

    /** Protocol additions upstream made after 97e26d2. */
    @Test
    fun newerUpstreamMessages() {
        val topRight = RemoteControlStatusTopRight(
            bitrateInfo = RemoteControlStatusTopRightBitrateInfo(5_000_000, 12_300_000),
            bondingInfo = listOf(RemoteControlStatusTopRightBondingConnection("Cellular", 375_000, 48)),
        )
        assertEquals(
            """{"bitrateInfo":{"bitsPerSecond":5000000,"totalBytes":12300000},"bondingInfo":[{"name":"Cellular","bytesPerSecond":375000,"rtt":48}]}""",
            SwiftJson.encodeToString(RemoteControlStatusTopRight.serializer(), topRight),
        )
        // An iOS streamer proves itself to Moblin Cloud with App Attest; assistants ignore it.
        assertEquals(
            RemoteControlMessageToAssistant.Identify("S", "h"),
            SwiftJson.decodeFromString(
                RemoteControlMessageToAssistant.Serializer,
                """{"identify":{"streamerId":"S","authentication":"h","appAttest":{"keyId":"k","attestation":"a","attestationChallenge":"c","assertion":"x"}}}""",
            ),
        )
        // Events NexusIRL does not show (here the new top right icon) are skipped, not fatal.
        assertTrue(
            runCatching {
                SwiftJson.decodeFromString(
                    RemoteControlMessageToAssistant.Serializer,
                    """{"event":{"data":{"icon":{"id":"AppIconKing"}}}}""",
                )
            }.isFailure,
        )
    }

    /** A fake assistant: the streamer connects to it, like to an iOS assistant on port 2345. */
    @Test
    fun handshakeAndRequests() {
        val fromStreamer = LinkedBlockingQueue<String>()
        val assistant = LinkedBlockingQueue<WebSocket>()
        val server = MockWebServer()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    assistant += webSocket
                    webSocket.send("""{"hello":{"apiVersion":"0.1","authentication":{"challenge":"c","salt":"s"}}}""")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    fromStreamer += text
                }
            }),
        )
        server.start()
        val events = LinkedBlockingQueue<String>()
        val streamer = RemoteControlStreamer(
            server.url("/").toString().replace("http", "ws"),
            "password",
            "streamer-1",
            object : RemoteControlStreamerDelegate {
                override fun connected() {
                    events += "connected"
                }

                override fun getStatus() = RemoteControlResponse.GetStatus(
                    topLeft = RemoteControlStatusTopLeft(),
                    topRight = RemoteControlStatusTopRight(),
                )

                override fun getSettings() = RemoteControlSettings(emptyList(), emptyList())

                override fun setTorch(on: Boolean) {
                    events += "torch $on"
                }
            },
        )
        try {
            streamer.start()
            val identify = fromStreamer.poll(5, TimeUnit.SECONDS)
            assertEquals(
                """{"identify":{"streamerId":"streamer-1","authentication":"${remoteControlHashPassword("c", "s", "password")}"}}""",
                identify,
            )
            val socket = assertNotNull(assistant.poll(5, TimeUnit.SECONDS))
            socket.send("""{"identified":{"result":{"ok":{}}}}""")
            assertEquals("connected", events.poll(5, TimeUnit.SECONDS))
            socket.send("""{"request":{"id":1,"data":{"setTorch":{"on":true}}}}""")
            assertEquals("torch true", events.poll(5, TimeUnit.SECONDS))
            assertEquals("""{"response":{"id":1,"result":{"ok":{}}}}""", fromStreamer.poll(5, TimeUnit.SECONDS))
            socket.send("""{"request":{"id":2,"data":{"getStatus":{}}}}""")
            assertEquals(
                """{"response":{"id":2,"result":{"ok":{}},"data":{"getStatus":{"topLeft":{},"topRight":{}}}}}""",
                fromStreamer.poll(5, TimeUnit.SECONDS),
            )
            socket.send("""{"request":{"id":3,"data":{"saveGimbalPreset":{}}}}""")
            assertEquals("""{"response":{"id":3,"result":{"unknownRequest":{}}}}""", fromStreamer.poll(5, TimeUnit.SECONDS))
            // Settings import is refused unless the user accepts it on the device.
            socket.send(
                """{"request":{"id":4,"data":{"importSettings":{"data":"${Base64.getEncoder().encodeToString("{}".toByteArray())}"}}}}""",
            )
            assertEquals("""{"response":{"id":4,"result":{"error":{}}}}""", fromStreamer.poll(5, TimeUnit.SECONDS))
            streamer.twitchStart(null, "123", "token")
            val twitch = SwiftJson.decodeFromString(RemoteControlMessageToAssistant.Serializer, fromStreamer.poll(5, TimeUnit.SECONDS)!!)
            val token = (twitch as RemoteControlMessageToAssistant.TwitchStart).accessToken
            assertContentEquals("token".toByteArray(), RemoteControlEncryption("password").decrypt(Base64.getDecoder().decode(token)))
            assertTrue(streamer.isConnected)
        } finally {
            streamer.stop()
            server.shutdown()
        }
    }
}
