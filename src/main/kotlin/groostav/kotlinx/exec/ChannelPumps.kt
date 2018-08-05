package groostav.kotlinx.exec

import kotlinx.coroutines.experimental.Unconfined
import kotlinx.coroutines.experimental.channels.*
import kotlinx.coroutines.experimental.launch
import java.io.*
import kotlin.coroutines.experimental.CoroutineContext

/**
 * Returns the input stream as an unbufferred channel by blocking a thread provided by context
 *
 * **this method will put a blocking job** in [context]. Make sure the pool
 * that backs the provided context can procHandle that!
 *
 * the resulting channel is not buffered. This means it is sensitive to back-pressure.
 * downstream receivers should buffer appropriately!!
 */
internal fun NewMessageChunkEventSource.toReceiveChannel(
        channelName: String,
        config: ProcessBuilder,
        context: CoroutineContext = blockableThread
): ReceiveChannel<Char> {

    val result = produce(context) {

        this@toReceiveChannel.invoke { messageChunk ->
            launch(Unconfined){
                messageChunk.forEach { nextChar ->
                    send(nextChar)
                }
            }
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

        writer.close()
    }
}