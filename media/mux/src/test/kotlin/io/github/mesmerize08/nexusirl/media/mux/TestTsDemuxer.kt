package io.github.mesmerize08.nexusirl.media.mux

/**
 * Minimal, independent MPEG-TS demuxer used only to verify writer output against the spec
 * (ISO/IEC 13818-1). It deliberately does not reuse the production parsing code.
 */
class TestTsDemuxer {
    data class Pes(val pid: Int, val streamId: Int, val pts: Long?, val dts: Long?, val payload: ByteArray, val randomAccess: Boolean)

    val pesPackets = ArrayList<Pes>()
    val pcrValues = ArrayList<Pair<Int, Long>>()
    var pmtPid: Int? = null
    var pcrPid: Int? = null
    val streamTypes = LinkedHashMap<Int, Int>()
    val esDescriptors = LinkedHashMap<Int, ByteArray>()
    var patCount = 0
    var pmtCount = 0
    val continuityErrors = ArrayList<String>()

    private val lastContinuity = HashMap<Int, Int>()
    private val pending = HashMap<Int, java.io.ByteArrayOutputStream>()
    private val pendingRandomAccess = HashMap<Int, Boolean>()

    fun feed(data: ByteArray) {
        require(data.size % 188 == 0) { "Output is not whole TS packets: ${data.size} bytes" }
        for (offset in data.indices step 188) {
            packet(data.copyOfRange(offset, offset + 188))
        }
    }

    fun finish() {
        for (pid in pending.keys.toList()) {
            flush(pid)
        }
    }

    private fun u(b: Byte) = b.toInt() and 0xFF

    private fun packet(p: ByteArray) {
        require(u(p[0]) == 0x47) { "Bad sync byte" }
        val pusi = (u(p[1]) and 0x40) != 0
        val pid = ((u(p[1]) and 0x1F) shl 8) or u(p[2])
        val afc = (u(p[3]) ushr 4) and 0x3
        val cc = u(p[3]) and 0xF
        if (afc and 1 != 0 && pid != 0 && pid != pmtPid) {
            lastContinuity[pid]?.let { last ->
                if (cc != (last + 1) and 0xF) {
                    continuityErrors.add("pid $pid: $last -> $cc")
                }
            }
            lastContinuity[pid] = cc
        }
        var index = 4
        var randomAccess = false
        if (afc and 2 != 0) {
            val length = u(p[4])
            if (length > 0) {
                val flags = u(p[5])
                randomAccess = flags and 0x40 != 0
                if (flags and 0x10 != 0) {
                    val base = (u(p[6]).toLong() shl 25) or (u(p[7]).toLong() shl 17) or
                        (u(p[8]).toLong() shl 9) or (u(p[9]).toLong() shl 1) or (u(p[10]).toLong() ushr 7)
                    pcrValues.add(pid to base)
                }
            }
            index += 1 + length
        }
        require(index <= 188) { "Adaptation field overflows packet" }
        val payload = p.copyOfRange(index, 188)
        when {
            pid == 0 -> parseSection(payload, pat = true)
            pid == pmtPid -> parseSection(payload, pat = false)
            else -> {
                if (pusi) {
                    flush(pid)
                    pending[pid] = java.io.ByteArrayOutputStream()
                    pendingRandomAccess[pid] = randomAccess
                }
                pending[pid]?.write(payload)
            }
        }
    }

    private fun parseSection(payload: ByteArray, pat: Boolean) {
        val pointer = u(payload[0])
        val start = 1 + pointer
        val sectionLength = ((u(payload[start + 1]) and 0x0F) shl 8) or u(payload[start + 2])
        val section = payload.copyOfRange(start, start + 3 + sectionLength)
        require(crc32Mpeg2(section) == 0L) { "Bad section CRC" }
        val body = section.copyOfRange(8, section.size - 4)
        if (pat) {
            patCount++
            require(body.size == 4)
            pmtPid = ((u(body[2]) and 0x1F) shl 8) or u(body[3])
        } else {
            pmtCount++
            pcrPid = ((u(body[0]) and 0x1F) shl 8) or u(body[1])
            val programInfoLength = ((u(body[2]) and 0x0F) shl 8) or u(body[3])
            var i = 4 + programInfoLength
            while (i < body.size) {
                val type = u(body[i])
                val pid = ((u(body[i + 1]) and 0x1F) shl 8) or u(body[i + 2])
                val esInfoLength = ((u(body[i + 3]) and 0x0F) shl 8) or u(body[i + 4])
                streamTypes[pid] = type
                esDescriptors[pid] = body.copyOfRange(i + 5, i + 5 + esInfoLength)
                i += 5 + esInfoLength
            }
        }
    }

    private fun timestamp(b: ByteArray, o: Int): Long =
        ((u(b[o]).toLong() and 0x0E) shl 29) or (u(b[o + 1]).toLong() shl 22) or
            ((u(b[o + 2]).toLong() and 0xFE) shl 14) or (u(b[o + 3]).toLong() shl 7) or (u(b[o + 4]).toLong() ushr 1)

    private fun flush(pid: Int) {
        val data = pending.remove(pid)?.toByteArray() ?: return
        require(u(data[0]) == 0 && u(data[1]) == 0 && u(data[2]) == 1) { "Bad PES start code" }
        val streamId = u(data[3])
        val packetLength = (u(data[4]) shl 8) or u(data[5])
        val ptsDts = u(data[7]) ushr 6
        val headerLength = u(data[8])
        val pts = if (ptsDts and 2 != 0) timestamp(data, 9) else null
        val dts = if (ptsDts == 3) timestamp(data, 14) else null
        val payloadStart = 9 + headerLength
        val end = if (packetLength == 0) data.size else 6 + packetLength
        require(end <= data.size) { "PES shorter than its packet length" }
        pesPackets.add(Pes(pid, streamId, pts, dts, data.copyOfRange(payloadStart, end), pendingRandomAccess[pid] == true))
    }

    companion object {
        fun crc32Mpeg2(data: ByteArray): Long {
            var crc = 0xFFFF_FFFFL
            for (byte in data) {
                crc = crc xor ((byte.toLong() and 0xFF) shl 24)
                repeat(8) {
                    crc = if (crc and 0x8000_0000L != 0L) ((crc shl 1) xor 0x04C1_1DB7L) else (crc shl 1)
                    crc = crc and 0xFFFF_FFFFL
                }
            }
            return crc
        }
    }
}
