package groostav.kotlinx.exec

import kotlinx.coroutines.experimental.channels.*
import kotlinx.coroutines.experimental.channels.Channel.Factory.UNLIMITED
import java.io.*

internal fun InputStream.toPumpedReceiveChannel(channelName: String, config: ProcessBuilder): ReceiveChannel<Char> {

    //TODO: remove buffers
    val result = produce(capacity = UNLIMITED, context = blockableThread) {
        val reader = BufferedReader(InputStreamReader(this@toPumpedReceiveChannel, config.encoding))

        trace { "SOF on $channelName" }

        while (isActive) {
            val nextCodePoint = reader.read().takeUnless { it == -1 }
            if (nextCodePoint == null) {
                trace { "EOF on $channelName" }
                break
            }
            val nextChar = nextCodePoint.toChar()

            send(nextChar)
        }
    }
    return object: ReceiveChannel<Char> by result {
        override fun toString() = "pump-$channelName"
    }
}


internal fun OutputStream.toSendChannel(config: ProcessBuilder): SendChannel<Char> {
    return actor<Char>(blockableThread) {
        val delimParts = config.delimiters.flatMap { it.toSet() }.toSet()
        val writer = OutputStreamWriter(this@toSendChannel, config.encoding)

        consumeEach { nextChar ->

            try {
                writer.append(nextChar)
                if(nextChar == config.inputFlushMarker) writer.flush()
            }
            catch (ex: FileNotFoundException) {
                //writer was closed, process was terminated.
                //TODO need a test to induce this, verify correctness.
                return@actor
            }
        }
    }
}