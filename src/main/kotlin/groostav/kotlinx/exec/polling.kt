package groostav.kotlinx.exec

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.ConflatedBroadcastChannel
import kotlinx.coroutines.experimental.channels.ReceiveChannel
import kotlinx.coroutines.experimental.channels.produce
import kotlinx.coroutines.experimental.channels.take
import kotlinx.coroutines.experimental.selects.select
import java.io.Closeable
import java.io.Reader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.experimental.CoroutineContext

internal class PollingListenerProvider(val process: Process, val pid: Int, val config: ProcessBuilder): ProcessListenerProvider {

    companion object: ProcessListenerProvider.Factory {
        override fun create(process: Process, pid: Int, config: ProcessBuilder) = PollingListenerProvider(process, pid, config)
    }

    private val standardErrorReader = NamedTracingProcessReader.forStandardError(process, pid, config)
    private val standardOutputReader = NamedTracingProcessReader.forStandardOutput(process, pid, config)

    val PollPeriodWindow = getIntRange("groostav.kotlinx.exec.PollPeriodMillis")?.also {
        require(it.start > 0)
        require(it.endInclusive >= it.start)
    } ?: (2 .. 34) //30fps = 33.3ms period

    private val otherSignals = ConflatedBroadcastChannel<Unit>()
    private @Volatile var manualEOF = false

    override val standardErrorChannel = Supported(
            standardErrorReader.toPolledReceiveChannel(Unconfined, DelayMachine(PollPeriodWindow, otherSignals))

    )
    override val standardOutputChannel = Supported(
            standardOutputReader.toPolledReceiveChannel(Unconfined, DelayMachine(PollPeriodWindow, otherSignals))

    )
    override val exitCodeDeferred = Supported(
            async(Unconfined) {
                val delayMachine = DelayMachine(PollPeriodWindow, otherSignals)
                delayMachine.waitForByPollingPeriodically { process.isAlive }
                val result = process.exitValue()
                manualEOF = true
                delayMachine.signalPollResult()
                result
            }
    )


    private fun Reader.toPolledReceiveChannel(
            context: CoroutineContext,
            delayMachine: DelayMachine
    ): ReceiveChannel<Char> {

        val result = produce(context) {

            val chunkBuffer = CharArray(128)

            reading@ while (isActive) {

                delayMachine.waitForByPollingPeriodically { !ready() && !manualEOF }

                while (ready() || manualEOF) {
                    val readByteCount = read(chunkBuffer)
                    if (readByteCount == EOF_VALUE) {
                        break@reading
                    }

                    for(index in 0 until readByteCount) {
                        send(chunkBuffer[index])
                    }
                }
            }

            trace { "polling of ${this@toPolledReceiveChannel} completed" }
        }
        return object: ReceiveChannel<Char> by result {
            override fun toString() = "pollchan-${this@toPolledReceiveChannel}"
        }
    }
}

private class DelayMachine(
        private val delayWindow: IntRange,
        private val otherSignals: ConflatedBroadcastChannel<Unit>,
        private val delayFactor: Float = 1.5f
) {

    init {
        require(delayWindow.start > 0)
        require(delayWindow.endInclusive >= delayWindow.start)
        require(delayFactor > 1.0f)
    }

    private val backoff = AtomicInteger(0)
    private val otherSignalsSubscription = otherSignals.openSubscription()

    suspend fun waitForByPollingPeriodically(condition: () -> Boolean){
        while(condition()) {
            val backoff = backoff.updateAndGet { updateBackoff(it, delayWindow) }

            select<Unit> {
                onTimeout(backoff.toLong(), TimeUnit.MILLISECONDS) { Unit }
                otherSignalsSubscription.onReceiveOrNull { Unit }
            }

            yield() //if, for whatever reason, we're getting flooded with other signals,
            // this ensures we yield to previously enqueued jobs on our dispatcher
        }
        signalPollResult()
    }

    fun signalPollResult(){
        backoff.set(delayWindow.start)
        otherSignals.offer(Unit)
    }

    private fun updateBackoff(currentPollPeriodMillis: Int, pollPeriodMillis: IntRange): Int {
        return (currentPollPeriodMillis * 1.5).toInt()
                .coerceAtLeast(currentPollPeriodMillis + 1)
                .coerceAtMost(pollPeriodMillis.endInclusive)
    }
}

internal fun getIntRange(key: String): IntRange? = System.getProperty(key)?.let {
    val match = Regex("(-?\\d+)\\s*\\.\\.\\s*(-?\\d+)").matchEntire(it.trim())
            ?: throw UnsupportedOperationException("couldn't parse $it as IntRange (please use format '#..#' eg '1..234')")
    val (start, end) = match.groupValues.apply { require(size == 3) }.takeLast(2).map { it.toInt() }
    start .. end
}