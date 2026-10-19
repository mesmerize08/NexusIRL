// Port of Moblin/Media/HaishinKit/Mpeg/MpegTsWriter.swift @97e26d2
package io.github.mesmerize08.nexusirl.media.mux.ts

import io.github.mesmerize08.nexusirl.core.clock.MediaTime
import io.github.mesmerize08.nexusirl.media.mux.chunks
import io.github.mesmerize08.nexusirl.media.mux.nal.AvcNalUnit
import io.github.mesmerize08.nexusirl.media.mux.nal.HevcNalUnit
import io.github.mesmerize08.nexusirl.media.mux.nal.NAL_UNIT_START_CODE
import java.io.ByteArrayOutputStream

/** Receives transport stream output. */
fun interface MpegTsWriterListener {
    /**
     * [data] is a whole number of 188-byte TS packets. [containsAudio] marks blocks carrying audio,
     * which the SRT sender retransmits first.
     */
    fun onOutput(data: ByteArray, containsAudio: Boolean)
}

/** Source of NTP time for optional HEVC timecode SEI (upstream uses TrueTime). */
interface TimecodeClock {
    /** NTP-synchronized seconds since the Unix epoch, or null when not yet synchronized. */
    fun ntpNowSeconds(): Double?

    /** The current host clock time, in the same domain as media timestamps. */
    fun hostNow(): MediaTime
}

/**
 * Writes an MPEG-2 transport stream with one program: video on PID 256, audio on PID 257,
 * PCR on the audio PID.
 *
 * Output modes (upstream `newSrt` flag):
 * - [OutputMode.BLOCKS] (Moblin's own SRT sender): variable-size blocks tagged with whether they
 *   contain audio.
 * - [OutputMode.CHUNKED] (libsrt, RIST): everything cut into [payloadSize] chunks.
 *
 * In both modes video PES output is delayed by one frame and each audio block is front-filled with
 * pending video bytes up to [payloadSize], so most datagrams are full.
 *
 * Video input is Annex-B (what Android `MediaCodec` produces); parameter sets are raw NAL units.
 */
class MpegTsWriter(
    private val listener: MpegTsWriterListener,
    private val mode: OutputMode = OutputMode.BLOCKS,
    /** Datagram payload size: `mpegtsPacketsPerPacket * 188`, 1316 by default. */
    var payloadSize: Int = 7 * MpegTsPacket.SIZE,
    private val timecodeClock: TimecodeClock? = null,
) {
    enum class OutputMode { BLOCKS, CHUNKED }

    private var isRunning = false
    private var audioContinuityCounter = 0
    private var videoContinuityCounter = 0
    private var patContinuityCounter = 0
    private var pmtContinuityCounter = 0
    private var latestPeriodicallySendProgramTime: MediaTime = MediaTime.ZERO
    private val videoData = arrayOfNulls<ByteArray>(2)
    private var videoDataOffset = 0
    private var programAssociationTable = newProgramAssociationTable()
    private var programMappingTable = MpegTsProgramMapping()
    private var audioConfig: MpegTsAudioConfig? = null
    private var videoConfig: MpegTsVideoConfig? = null
    private var programClockReferenceTimestamp: MediaTime? = null
    private val timecodeGenerator = if (timecodeClock != null) MpegTsTimecodeGenerator() else null

    fun startRunning() {
        isRunning = true
    }

    fun stopRunning() {
        if (!isRunning) {
            return
        }
        audioContinuityCounter = 0
        videoContinuityCounter = 0
        patContinuityCounter = 0
        pmtContinuityCounter = 0
        programAssociationTable = newProgramAssociationTable()
        programMappingTable = MpegTsProgramMapping()
        audioConfig = null
        videoConfig = null
        videoDataOffset = 0
        videoData[0] = null
        videoData[1] = null
        programClockReferenceTimestamp = null
        timecodeGenerator?.reset()
        isRunning = false
    }

    // Audio

    fun setAudioConfig(config: MpegTsAudioConfig) {
        val data = ElementaryStreamSpecificData(elementaryPacketId = AUDIO_PACKET_ID)
        if (config.isOpus) {
            data.streamType = ElementaryStreamType.MPEG2_PACKETIZED_DATA
            data.appendDescriptor(ElementaryStreamDescriptorTag.REGISTRATION, OPUS_REGISTRATION)
            data.appendDescriptor(ElementaryStreamDescriptorTag.EXTENSION, byteArrayOf(0x80.toByte(), config.channels.toByte()))
        } else {
            data.streamType = ElementaryStreamType.ADTS_AAC
        }
        addStreamSpecificData(data)
        audioContinuityCounter = 0
        audioConfig = config
        writeProgramIfNeeded()
    }

    /** Writes one raw encoded audio frame (AAC without ADTS, or an Opus packet). */
    fun writeAudio(frame: ByteArray, offset: Int, length: Int, presentationTimeStamp: MediaTime) {
        val config = audioConfig
        if (!canWrite() || config == null) {
            return
        }
        val header = if (config.isOpus) {
            OpusHeader.encode(length)
        } else {
            AdtsHeader.encode(config.objectType, config.frequency.index, config.channels, length)
        }
        val data = ByteArray(header.size + length)
        header.copyInto(data)
        frame.copyInto(data, header.size, offset, offset + length)
        val pes = MpegTsPacketizedElementaryStream(AUDIO_STREAM_ID, presentationTimeStamp, null, data)
        val programClockReference = updateProgramClockReference(presentationTimeStamp)
        val packets = pes.arrayOfPackets(AUDIO_PACKET_ID, true, programClockReference)
        periodicallySendProgram(presentationTimeStamp)
        writeAudioData(encode(AUDIO_PACKET_ID, packets))
    }

    // Video

    fun setVideoConfig(config: MpegTsVideoConfig) {
        val data = ElementaryStreamSpecificData(elementaryPacketId = VIDEO_PACKET_ID)
        data.streamType = when (config) {
            is MpegTsVideoConfig.Avc -> ElementaryStreamType.H264
            is MpegTsVideoConfig.Hevc -> ElementaryStreamType.H265
        }
        videoContinuityCounter = 0
        addStreamSpecificData(data)
        videoConfig = config
        writeProgramIfNeeded()
    }

    /**
     * Writes one Annex-B access unit. [decodeTimeStamp] is null when frame reordering (B-frames) is
     * off, which yields PTS-only PES headers like upstream.
     */
    fun writeVideo(
        accessUnit: ByteArray,
        presentationTimeStamp: MediaTime,
        decodeTimeStamp: MediaTime?,
        isSync: Boolean,
    ) {
        val config = videoConfig
        if (!canWrite() || config == null) {
            return
        }
        val timecode = makeTimecode(presentationTimeStamp, decodeTimeStamp)
        val data = when (config) {
            is MpegTsVideoConfig.Avc -> packH264(isSync, config, accessUnit)
            is MpegTsVideoConfig.Hevc -> packH265(isSync, config, timecode, accessUnit)
        }
        val pes = MpegTsPacketizedElementaryStream(VIDEO_STREAM_ID, presentationTimeStamp, decodeTimeStamp, data)
        val packets = pes.arrayOfPackets(VIDEO_PACKET_ID, isSync, null)
        writeVideoData(encode(VIDEO_PACKET_ID, packets))
    }

    private fun packH264(isSync: Boolean, config: MpegTsVideoConfig.Avc, accessUnit: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(accessUnit.size + 64)
        if (isSync) {
            output.write(AvcNalUnit.AUD_10_WITH_START_CODE)
            config.sequenceParameterSet?.let {
                output.write(NAL_UNIT_START_CODE)
                output.write(it)
            }
            config.pictureParameterSet?.let {
                output.write(NAL_UNIT_START_CODE)
                output.write(it)
            }
        } else {
            output.write(AvcNalUnit.AUD_30_WITH_START_CODE)
        }
        // Upstream has AVC picture timing SEI disabled (`if let timecode, false`).
        output.write(accessUnit)
        return output.toByteArray()
    }

    private fun packH265(
        isSync: Boolean,
        config: MpegTsVideoConfig.Hevc,
        timecode: MpegTsTimecode?,
        accessUnit: ByteArray,
    ): ByteArray {
        val output = ByteArrayOutputStream(accessUnit.size + 128)
        if (isSync) {
            for (parameterSet in listOf(config.videoParameterSet, config.sequenceParameterSet, config.pictureParameterSet)) {
                parameterSet?.let {
                    output.write(NAL_UNIT_START_CODE)
                    output.write(it)
                }
            }
        }
        if (timecode != null) {
            output.write(NAL_UNIT_START_CODE)
            output.write(HevcNalUnit.PrefixSei(timecode.toSeiTimecode()).encode())
        }
        output.write(accessUnit)
        return output.toByteArray()
    }

    private fun makeTimecode(presentationTimeStamp: MediaTime, decodeTimeStamp: MediaTime?): MpegTsTimecode? {
        val generator = timecodeGenerator ?: return null
        val clock = timecodeClock ?: return null
        if (!generator.hasReference()) {
            val now = clock.ntpNowSeconds() ?: return null
            generator.setReference(now, clock.hostNow().seconds)
        }
        return generator.makeTimecode(presentationTimeStamp, decodeTimeStamp)
    }

    // Program tables

    private fun canWrite(): Boolean = audioConfig != null && videoConfig != null

    private fun addStreamSpecificData(data: ElementaryStreamSpecificData) {
        val datas = programMappingTable.elementaryStreamSpecificDatas
        val index = datas.indexOfFirst { it.elementaryPacketId == data.elementaryPacketId }
        if (index >= 0) {
            datas[index] = data
        } else {
            datas.add(data)
        }
    }

    private fun writeProgramIfNeeded() {
        if (canWrite()) {
            writeProgram()
        }
    }

    private fun writeProgram() {
        programMappingTable.programClockReferencePacketId = AUDIO_PACKET_ID
        val patPacket = programAssociationTable.packet(PROGRAM_ASSOCIATION_TABLE_PACKET_ID)
        val pmtPacket = programMappingTable.packet(PROGRAM_MAPPING_TABLE_PACKET_ID)
        patPacket.continuityCounter = patContinuityCounter
        patContinuityCounter = (patContinuityCounter + 1) and 0x0F
        pmtPacket.continuityCounter = pmtContinuityCounter
        pmtContinuityCounter = (pmtContinuityCounter + 1) and 0x0F
        output(patPacket.encode() + pmtPacket.encode(), containsAudio = false)
    }

    private fun periodicallySendProgram(now: MediaTime) {
        if (now - latestPeriodicallySendProgramTime <= SEGMENT_DURATION) {
            return
        }
        writeProgram()
        latestPeriodicallySendProgramTime = now
    }

    private fun updateProgramClockReference(timestamp: MediaTime): Long? {
        if (timestamp.seconds - (programClockReferenceTimestamp?.seconds ?: 0.0) >= 0.1) {
            programClockReferenceTimestamp = timestamp
            return (maxOf(timestamp.seconds, 0.0) * TsTimestamp.RESOLUTION).toLong()
        }
        return null
    }

    // Packet encoding and interleaving

    private fun encode(packetId: Int, packets: List<MpegTsPacket>): ByteArray {
        val buffer = ByteArray(packets.size * MpegTsPacket.SIZE)
        var offset = 0
        for (packet in packets) {
            packet.continuityCounter = nextContinuityCounter(packetId)
            offset += packet.encodeInto(buffer, offset)
        }
        return buffer
    }

    private fun nextContinuityCounter(packetId: Int): Int = when (packetId) {
        AUDIO_PACKET_ID -> audioContinuityCounter.also { audioContinuityCounter = (it + 1) and 0x0F }
        VIDEO_PACKET_ID -> videoContinuityCounter.also { videoContinuityCounter = (it + 1) and 0x0F }
        else -> 0
    }

    private fun appendVideoData(data: ByteArray?) {
        videoData[0] = videoData[1]
        videoData[1] = data
        videoDataOffset = 0
    }

    private fun writeVideoData(data: ByteArray) {
        videoData[0]?.let { pending ->
            output(pending.copyOfRange(videoDataOffset, pending.size), containsAudio = false)
        }
        appendVideoData(data)
    }

    private fun writeAudioData(data: ByteArray) {
        val pending = videoData[0]
        if (pending == null) {
            output(data, containsAudio = true)
            return
        }
        for (chunk in data.chunks(payloadSize)) {
            var packet = chunk
            val videoSize = payloadSize - chunk.size
            if (videoSize > 0) {
                val endOffset = minOf(videoDataOffset + videoSize, pending.size)
                if (videoDataOffset != endOffset) {
                    packet = pending.copyOfRange(videoDataOffset, endOffset) + chunk
                    videoDataOffset = endOffset
                }
            }
            output(packet, containsAudio = true)
        }
        if (videoDataOffset == pending.size) {
            appendVideoData(null)
        }
    }

    private fun output(data: ByteArray, containsAudio: Boolean) {
        when (mode) {
            OutputMode.BLOCKS -> listener.onOutput(data, containsAudio)
            OutputMode.CHUNKED -> for (chunk in data.chunks(payloadSize)) {
                listener.onOutput(chunk, containsAudio)
            }
        }
    }

    companion object {
        const val PROGRAM_ASSOCIATION_TABLE_PACKET_ID = 0
        const val PROGRAM_MAPPING_TABLE_PACKET_ID = 4095
        const val AUDIO_PACKET_ID = 257
        const val VIDEO_PACKET_ID = 256
        private const val AUDIO_STREAM_ID = 192
        private const val VIDEO_STREAM_ID = 224
        private val SEGMENT_DURATION = MediaTime(2, 1)

        private fun newProgramAssociationTable() = MpegTsProgramAssociation().apply {
            programs[1] = PROGRAM_MAPPING_TABLE_PACKET_ID
        }
    }
}
