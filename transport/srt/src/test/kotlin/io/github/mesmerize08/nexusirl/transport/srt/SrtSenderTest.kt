package io.github.mesmerize08.nexusirl.transport.srt

import io.github.mesmerize08.nexusirl.core.clock.ManualSerialExecutor
import io.github.mesmerize08.nexusirl.media.mux.ByteWriter
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/** Captures sender callbacks. */
private class Recorder : SrtSender.Listener {
    val packets = ArrayList<ByteArray>()
    var connected = 0
    var disconnected = 0

    override fun onConnected() {
        connected++
    }

    override fun onDisconnected() {
        disconnected++
    }

    override fun onOutput(packet: ByteArray) {
        packets.add(packet)
    }
}

/**
 * `connectDisconnect` is a port of MoblinTests/Moblin/Media/HaishinKit/Srt/SrtSenderSuite.swift
 * @97e26d2 with its golden handshake vectors; the rest are new behavioural tests.
 */
class SrtSenderTest {
    private val inductionResponse = (
        "80000000000000000000000000000000000000040000000200000fe6000005dc" +
            "00002000000000012ab1f77c000000000100007f000000000000000000000000"
        ).hexToBytes()

    private val conclusionResponse = (
        "800000000000000000000000000000000000000500000005000000000000" +
            "05dc00002000ffffffff2ab1f77c000000000100007f0000000000000000" +
            "000000000001000300010503000000bf07d007d00005000134333231"
        ).hexToBytes()

    private fun newSender(executor: ManualSerialExecutor, recorder: Recorder, experimental: Boolean = false, latency: Int = 2000) =
        SrtSender("1234", latency, experimental, executor, executor.clock, recorder)

    private fun connect(executor: ManualSerialExecutor, recorder: Recorder, sender: SrtSender) {
        sender.start()
        executor.runPending()
        sender.input(inductionResponse)
        sender.input(conclusionResponse)
        recorder.packets.clear()
    }

    @Test
    fun connectDisconnect() {
        val executor = ManualSerialExecutor()
        val recorder = Recorder()
        val sender = newSender(executor, recorder)
        sender.start()
        executor.runPending()

        val induction = recorder.packets.removeFirst().toHex()
        assertEquals(128, induction.length)
        assertEquals("8000000000000000", induction.substring(0, 16))
        assertEquals("000000000000000400000002", induction.substring(24, 48))
        assertEquals("000005dc0000200000000001", induction.substring(56, 80))
        assertEquals("000000000100007f000000000000000000000000", induction.substring(88, 128))

        sender.input(inductionResponse)
        val conclusion = recorder.packets.removeFirst().toHex()
        assertEquals(176, conclusion.length)
        assertEquals("8000000000000000", conclusion.substring(0, 16))
        assertEquals("000000000000000500000005", conclusion.substring(24, 48))
        assertEquals(
            "000005dc00002000ffffffff2ab1f77c000000000100007f000000000000" +
                "0000000000000001000300010503000000bf07d007d00005000134333231",
            conclusion.substring(56, 176),
        )
        // The sequence number announced in both handshakes is the same initial one.
        assertEquals(induction.substring(48, 56), conclusion.substring(48, 56))

        sender.input(conclusionResponse)
        assertEquals(1, recorder.connected)
        sender.send(executor.clock.nanos + 6_000_000_000)
        assertEquals(1, recorder.disconnected)
    }

    @Test
    fun connectTimesOutAfterFiveSeconds() {
        val executor = ManualSerialExecutor()
        val recorder = Recorder()
        newSender(executor, recorder).start()
        executor.advanceMillis(4_999)
        assertEquals(0, recorder.disconnected)
        executor.advanceMillis(2)
        assertEquals(1, recorder.disconnected)
    }

    @Test
    fun streamIdIsZeroPaddedAndWordReversed() {
        assertEquals("34333231", SrtSender.encodeStreamId("1234").toHex())
        assertEquals("34333231" + "00000035", SrtSender.encodeStreamId("12345").toHex())
        assertEquals("", SrtSender.encodeStreamId("").toHex())
    }

    private fun enqueue(sender: SrtSender, now: Long, count: Int, containsAudio: Boolean = false) {
        repeat(count) {
            val packet = sender.newDataPacket(ByteArray(1316) { 0x47 })
            packet.containsAudio = containsAudio
            sender.enqueue(packet, now)
        }
    }

    @Test
    fun dataPacketsHaveHeaderAndConsecutiveSequenceNumbers() {
        val executor = ManualSerialExecutor()
        val recorder = Recorder()
        val sender = newSender(executor, recorder)
        connect(executor, recorder, sender)
        executor.advanceMillis(10)
        enqueue(sender, executor.clock.nanos, 3)
        sender.send(executor.clock.nanos)
        assertEquals(3, recorder.packets.size)
        val sequenceNumbers = recorder.packets.map { it.getUInt32Be(0) }
        assertEquals(listOf(sequenceNumbers[0], sequenceNumbers[0] + 1, sequenceNumbers[0] + 2), sequenceNumbers)
        for (packet in recorder.packets) {
            assertEquals(16 + 1316, packet.size)
            assertEquals(0xE000_0001L, packet.getUInt32Be(4))
            assertEquals(0x2AB1_F77CL, packet.getUInt32Be(12)) // Peer socket id from the conclusion response.
            assertTrue(isSrtDataPacket(packet))
        }
    }

    @Test
    fun pacingSendsAtMostTenOrATenthPerTwoMilliseconds() {
        val executor = ManualSerialExecutor()
        val recorder = Recorder()
        val sender = newSender(executor, recorder)
        connect(executor, recorder, sender)
        executor.advanceMillis(10)
        enqueue(sender, executor.clock.nanos, 250)
        sender.send(executor.clock.nanos)
        assertEquals(25, recorder.packets.size) // max(250 / 10, min(250, 10))
        sender.send(executor.clock.nanos + 1_000_000) // Within 2 ms: nothing.
        assertEquals(25, recorder.packets.size)
        sender.send(executor.clock.nanos + 3_000_000)
        assertEquals(25 + 22, recorder.packets.size) // 225 queued -> 22
    }

    private fun nakPacket(vararg sequenceNumbers: Long): ByteArray {
        val writer = ByteWriter()
        writer.writeUInt16(0x8000 or SrtPacketType.NAK)
        writer.writeUInt16(0)
        writer.writeUInt32(0)
        writer.writeUInt32(0)
        writer.writeUInt32(0)
        sequenceNumbers.forEach { writer.writeUInt32(it) }
        return writer.toByteArray()
    }

    private fun ackPacket(ackNumber: Long, lastAcknowledged: Long, rttUs: Long): ByteArray {
        val writer = ByteWriter()
        writer.writeUInt16(0x8000 or SrtPacketType.ACK)
        writer.writeUInt16(0)
        writer.writeUInt32(ackNumber)
        writer.writeUInt32(0)
        writer.writeUInt32(0)
        writer.writeUInt32(lastAcknowledged)
        writer.writeUInt32(rttUs)
        return writer.toByteArray()
    }

    @Test
    fun nakRetransmitsWithRetransmissionBitBeforeNewData() {
        val executor = ManualSerialExecutor()
        val recorder = Recorder()
        val sender = newSender(executor, recorder)
        connect(executor, recorder, sender)
        executor.advanceMillis(10)
        enqueue(sender, executor.clock.nanos, 5)
        sender.send(executor.clock.nanos)
        val first = recorder.packets[0].getUInt32Be(0)
        recorder.packets.clear()

        enqueue(sender, executor.clock.nanos, 2)
        executor.advanceMillis(10)
        sender.input(nakPacket(first + 1, 0x8000_0000L or (first + 3), first + 4))
        // Retransmissions (first+1, first+3, first+4) go out before the 2 new packets.
        val sent = recorder.packets.map { it.getUInt32Be(0) to ((it[4].toInt() and 0x4) != 0) }
        assertEquals(
            listOf(first + 1 to true, first + 3 to true, first + 4 to true, first + 5 to false, first + 6 to false),
            sent,
        )
        assertEquals(3, sender.getPerformanceData().pktRetransTotal)
        assertEquals(1, sender.getPerformanceData().pktRecvNakTotal)
    }

    @Test
    fun experimentalModeRetransmitsAudioBeforeVideo() {
        val executor = ManualSerialExecutor()
        val recorder = Recorder()
        val sender = newSender(executor, recorder, experimental = true)
        connect(executor, recorder, sender)
        executor.advanceMillis(10)
        enqueue(sender, executor.clock.nanos, 2, containsAudio = false)
        enqueue(sender, executor.clock.nanos, 2, containsAudio = true)
        sender.send(executor.clock.nanos)
        val first = recorder.packets[0].getUInt32Be(0)
        recorder.packets.clear()
        executor.advanceMillis(10)
        sender.input(nakPacket(first, first + 1, first + 2, first + 3))
        // Experimental mode caps retransmissions at half of each pacing tick: audio goes first.
        assertEquals(listOf(first + 2, first + 3), recorder.packets.map { it.getUInt32Be(0) })
        repeat(3) {
            executor.advanceMillis(3)
            sender.send(executor.clock.nanos)
        }
        // Video (first, first+1) follows on later ticks.
        assertEquals(listOf(first + 2, first + 3, first, first + 1), recorder.packets.map { it.getUInt32Be(0) })
    }

    @Test
    fun ackRemovesPacketsInFlightAndRepliesWithAckAck() {
        val executor = ManualSerialExecutor()
        val recorder = Recorder()
        val sender = newSender(executor, recorder)
        connect(executor, recorder, sender)
        executor.advanceMillis(10)
        enqueue(sender, executor.clock.nanos, 6)
        sender.send(executor.clock.nanos)
        val first = recorder.packets[0].getUInt32Be(0)
        recorder.packets.clear()
        assertEquals(6, sender.getPerformanceData().pktFlightSize)
        executor.advanceMillis(300)
        sender.input(ackPacket(ackNumber = 7, lastAcknowledged = first + 4, rttUs = 45_000))
        assertEquals(2, sender.getPerformanceData().pktFlightSize)
        assertEquals(45.0, sender.getPerformanceData().msRtt)
        val ackAck = recorder.packets.single()
        assertEquals(0x8006, ackAck.getUInt16Be(0))
        assertEquals(7L, ackAck.getUInt32Be(4))
        assertEquals(20, ackAck.size)
        assertTrue(sender.getPerformanceData().mbpsSendRate > 0)
    }

    @Test
    fun keepAliveIsEchoed() {
        val executor = ManualSerialExecutor()
        val recorder = Recorder()
        val sender = newSender(executor, recorder)
        connect(executor, recorder, sender)
        sender.input(createSrtlaPacket(SrtPacketType.KEEP_ALIVE, 16))
        assertEquals(0x8001, recorder.packets.single().getUInt16Be(0))
    }

    @Test
    fun oldPacketsAreDroppedAfterLatencyThresholds() {
        val executor = ManualSerialExecutor()
        val recorder = Recorder()
        val sender = newSender(executor, recorder, latency = 1000)
        connect(executor, recorder, sender)
        executor.advanceMillis(10)
        val start = executor.clock.nanos
        enqueue(sender, start, 5)
        sender.send(start)
        assertEquals(5, sender.getPerformanceData().pktFlightSize)
        // Keep the link alive and step past 1.5 x latency for packets in flight.
        executor.advanceMillis(1_499)
        sender.input(createSrtlaPacket(SrtPacketType.KEEP_ALIVE, 16))
        sender.send(executor.clock.nanos)
        assertEquals(0, sender.getPerformanceData().pktSndDropTotal)
        executor.advanceMillis(5)
        sender.input(createSrtlaPacket(SrtPacketType.KEEP_ALIVE, 16))
        sender.send(executor.clock.nanos)
        // The stats snapshot is refreshed on the next output pass (as upstream).
        executor.advanceMillis(3)
        sender.send(executor.clock.nanos)
        assertEquals(5, sender.getPerformanceData().pktSndDropTotal)
        assertEquals(0, sender.getPerformanceData().pktFlightSize)
    }

    @Test
    fun shutdownDoesNotDisconnectImmediately() {
        val executor = ManualSerialExecutor()
        val recorder = Recorder()
        val sender = newSender(executor, recorder)
        connect(executor, recorder, sender)
        sender.input(createSrtlaPacket(SrtPacketType.SHUTDOWN, 16))
        assertEquals(0, recorder.disconnected)
        assertTrue(sender.isConnected)
    }
}

/** Port of MoblinTests/Moblin/Media/Srtla/SrtSuite.swift @97e26d2. */
class SrtSuiteTest {
    private fun makeNakPacket(vararg sns: Long): ByteArray {
        val writer = ByteWriter()
        writer.writeBytes(ByteArray(16))
        sns.forEach { writer.writeUInt32(it) }
        return writer.toByteArray()
    }

    private fun collect(packet: ByteArray): List<Long> = ArrayList<Long>().also { result -> processSrtNak(packet) { result.add(it) } }

    @Test
    fun processNakSingleAndRange() {
        assertEquals(listOf(7L, 10L, 11L, 12L, 20L), collect(makeNakPacket(7, 0x8000_0000L or 10, 12, 20)))
    }

    @Test
    fun processNakTooBigRangeIsIgnored() {
        assertEquals(listOf(5L), collect(makeNakPacket(0x8000_0000L, 0x7FFF_FFFFL, 5)))
    }

    @Test
    fun processNakReversedRangeIsIgnored() {
        assertEquals(listOf(5L), collect(makeNakPacket(0x8000_0000L or 10, 3, 5)))
    }

    @Test
    fun processNakTruncatedRange() {
        assertEquals(listOf(1L), collect(makeNakPacket(1, 0x8000_0000L or 10)))
    }

    @Test
    fun sequenceNumberAckedWithWrap() {
        assertTrue(isSrtSnAcked(5, 10))
        assertTrue(!isSrtSnAcked(11, 10))
        assertTrue(isSrtSnAcked(0xFFFF_FFF0L, 5)) // Wrapped around.
        assertTrue(!isSrtSnAcked(5, 0xFFFF_FFF0L))
    }

    @Test
    fun srtlaPacketHasControlBitAndType() {
        assertContentEquals(byteArrayOf(0x92.toByte(), 0x00, 0, 0), createSrtlaPacket(SrtlaPacketType.REG1, 4))
        assertContentEquals(byteArrayOf(0x91.toByte(), 0x00), createSrtlaPacket(SrtlaPacketType.ACK, 2))
    }
}
