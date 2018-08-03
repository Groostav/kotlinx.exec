package groostav.kotlinx.exec

import kotlinx.coroutines.experimental.channels.*
import kotlinx.coroutines.experimental.channels.Channel.Factory.UNLIMITED
import java.io.*
import kotlin.coroutines.experimental.CoroutineContext

/**
 * Returns the input stream as an unbufferred channel by blocking a thread provided by context
 *
 * **this method will put a blocking job** in [context]. Make sure the pool
 * that backs the provided context can handle that!
 *
 * the resulting channel is not buffered. This means it is sensitive to back-pressure.
 * downstream receivers should buffer appropriately!!
 */
internal fun InputStream.toPumpedReceiveChannel(
        channelName: String,
        config: ProcessBuilder,
        context: CoroutineContext = blockableThread
): ReceiveChannel<Char> {

    val result = produce(context) {

        // note: we ignore the "it is a good idea to buffer" here,
        // this code expects downstream users to buffer appropriately.
        val reader = InputStreamReader(this@toPumpedReceiveChannel, config.encoding)

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