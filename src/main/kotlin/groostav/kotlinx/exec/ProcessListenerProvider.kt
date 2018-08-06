package groostav.kotlinx.exec

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.ConflatedBroadcastChannel
import kotlinx.coroutines.experimental.channels.ReceiveChannel
import kotlinx.coroutines.experimental.channels.produce
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.CharBuffer
import kotlin.coroutines.experimental.CoroutineContext

internal interface ProcessListenerProvider {

    val standardErrorChannel: Maybe<ReceiveChannel<Char>> get() = Unsupported
    val standardOutputChannel: Maybe<ReceiveChannel<Char>> get() = Unsupported
    val exitCodeDeferred: Maybe<Deferred<Int>> get() = Unsupported
}

internal class ThreadBlockingListenerProvider(val process: Process, val pid: Int, val config: ProcessBuilder): ProcessListenerProvider {

    override val standardErrorChannel by lazy {
        val standardErrorReader = NamedTracingProcessReader.forStandardError(process, pid, config)
        Supported(standardErrorReader.toPumpedReceiveChannel(BlockableDispatcher))
    }
    override val standardOutputChannel by lazy {
        val standardOutputReader = NamedTracingProcessReader.forStandardOutput(process, pid, config)
        Supported(standardOutputReader.toPumpedReceiveChannel(BlockableDispatcher))
    }
    override val exitCodeDeferred by lazy {
        val result = CompletableDeferred<Int>()
        launch(BlockableDispatcher){
            try { result.complete(process.waitFor()) } catch (ex: Exception) { result.completeExceptionally(ex) }
        }
        Supported(result)
    }
}

/**
 * Returns the input stream as an unbufferred channel by blocking a thread provided by context
 *
 * **this method will put a blocking job** in [context]. Make sure the pool
 * that backs the provided context can procHandle that!
 *
 * the resulting channel is not buffered. This means it is sensitive to back-pressure.
 * downstream receivers should buffer appropriately!!
 */
internal fun Reader.toPumpedReceiveChannel(context: CoroutineContext = BlockableDispatcher): ReceiveChannel<Char> {

    val result = produce(context) {

        while (isActive) {
            val nextCodePoint = read().takeUnless { it == EOF_VALUE }
            if (nextCodePoint == null) {
                break
            }
            val nextChar = nextCodePoint.toChar()

            send(nextChar)
        }
    }
    return object: ReceiveChannel<Char> by result {
        override fun toString() = "pumpchan-${this@toPumpedReceiveChannel}"
    }
}


