// Not in upstream (Network.framework limits message sizes itself): a size limit for the
// Java-WebSocket servers (remote control assistant and web, Moblink streamer).
package io.github.mesmerize08.nexusirl.remotecontrol

import org.java_websocket.WebSocketImpl
import org.java_websocket.drafts.Draft
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.enums.Opcode
import org.java_websocket.exceptions.LimitExceededException
import org.java_websocket.framing.Framedata

/**
 * RFC 6455 with messages limited to [maximumMessageSize] bytes; larger ones close the connection
 * with 1009. Java-WebSocket has no limit by default, and its own (Draft_6455's maxFrameSize, as of
 * 1.5.7) checks single frames and the first and last parts of a fragmented message but not the
 * parts in between, so a peer could grow a message without end. This counts every part.
 */
class LimitedDraft(private val maximumMessageSize: Int) : Draft_6455(emptyList(), maximumMessageSize) {
    private var fragmentedSize = 0L

    override fun processFrame(webSocketImpl: WebSocketImpl, frame: Framedata) {
        val opcode = frame.opcode
        val dataFrame = opcode == Opcode.TEXT || opcode == Opcode.BINARY
        if (opcode == Opcode.CONTINUOUS || (dataFrame && !frame.isFin)) {
            if (dataFrame) {
                fragmentedSize = 0
            }
            fragmentedSize += frame.payloadData.remaining()
            if (fragmentedSize > maximumMessageSize) {
                fragmentedSize = 0
                throw LimitExceededException(maximumMessageSize)
            }
            if (frame.isFin) {
                fragmentedSize = 0
            }
        }
        super.processFrame(webSocketImpl, frame)
    }

    /** Each connection gets its own copy (and count). */
    override fun copyInstance(): Draft = LimitedDraft(maximumMessageSize)
}
