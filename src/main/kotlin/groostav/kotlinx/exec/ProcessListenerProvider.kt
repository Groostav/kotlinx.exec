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

internal class PollingListenerProvider(val process: Process, val pid: Int, val config: ProcessBuilder): ProcessListenerProvider {

    private val standardErrorReader = NamedTracingProcessReader.forStandardError(process, pid, config)
    private val standardOutputReader = NamedTracingProcessReader.forStandardOutput(process, pid, config)

    val PollPeriodWindow = getIntRange("groostav.kotlinx.exec.PollPeriodMillis")?.also {
        require(it.start > 0)
        require(it.endInclusive >= it.start)
    } ?: (5 .. 100)

    val otherSignals = ConflatedBroadcastChannel<Unit>()
    @Volatile var manualEOF = false

    override val standardErrorChannel =
        Supported(standardErrorReader.toPolledReceiveChannel(CommonPool, DelayMachine(PollPeriodWindow, otherSignals)))
    override val standardOutputChannel =
        Supported(standardOutputReader.toPolledReceiveChannel(CommonPool, DelayMachine(PollPeriodWindow, otherSignals)))
    override val exitCodeDeferred = run {
        val delayMachine = DelayMachine(PollPeriodWindow, otherSignals)
        Supported(async(CommonPool) {

            try {
                delayMachine.waitForByPollingPeriodically { process.isAlive }
                process.waitFor()
            }
            finally {
                manualEOF = true
            }
        })
    }


    internal fun Reader.toPolledReceiveChannel(
            context: CoroutineContext,
            delayMachine: DelayMachine
    ): ReceiveChannel<Char> {

        val result = produce(context) {

            reading@ while (isActive) {

                delayMachine.waitForByPollingPeriodically { ! ready() && ! manualEOF }

                while(ready() || manualEOF){
                    val nextCodePoint = read().takeUnless { it == EOF_VALUE }
                    if (nextCodePoint == null) {
                        break@reading
                    }
                    val nextChar = nextCodePoint.toChar()

                    send(nextChar)
                }
            }
        }
        return object: ReceiveChannel<Char> by result {
            override fun toString() = "pollchan-${this@toPolledReceiveChannel}"
        }
    }
}


private const val EOF_VALUE: Int = -1

internal class NamedTracingProcessReader private constructor(
        src: InputStream,
        val name: String,
        val config: ProcessBuilder
): Reader() {

    //TODO: there doesnt seem to be any way to control buffering here.
    val src = InputStreamReader(src, config.encoding)

    init {
        trace { "SOF on $this" }
    }

    override fun skip(n: Long): Long = src.skip(n)
    override fun ready(): Boolean = src.ready()
    override fun reset() = src.reset()
    override fun close() = src.close()
    override fun markSupported(): Boolean = src.markSupported()
    override fun mark(readAheadLimit: Int) = src.mark(readAheadLimit)
    override fun read(target: CharBuffer?): Int = src.read(target)

    override fun read(): Int =
            src.read().also { if(it == EOF_VALUE){ trace { "EOF on $this" } } }
    override fun read(cbuf: CharArray?): Int =
            src.read(cbuf).also { if(it == EOF_VALUE){ trace { "EOF on $this" } } }
    override fun read(cbuf: CharArray?, off: Int, len: Int): Int =
            src.read(cbuf, off, len).also { if(it == EOF_VALUE){ trace { "EOF on $this" } } }

    override fun toString() = name

    companion object {

        fun forStandardError(process: Process, pid: Int, config: ProcessBuilder) =
                NamedTracingProcessReader(process.errorStream, "stderr-$pid", config)

        fun forStandardOutput(process: Process, pid: Int, config: ProcessBuilder) =
                NamedTracingProcessReader(process.inputStream, "stdout-$pid", config)
    }
}
