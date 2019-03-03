package groostav.kotlinx.exec

import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers.Unconfined
import kotlinx.coroutines.channels.ConflatedBroadcastChannel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.selects.select
import java.io.Reader
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

internal class PollingListenerProvider(val process: Process, val pid: Int, val config: ProcessConfiguration): ProcessListenerProvider {

    companion object: ProcessListenerProvider.Factory {
        override fun create(process: Process, pid: Int, config: ProcessConfiguration) = PollingListenerProvider(process, pid, config)
    }

    private val standardErrorReader = NamedTracingProcessReader.forStandardError(process, pid, config)
    private val standardOutputReader = NamedTracingProcessReader.forStandardOutput(process, pid, config)

    val PollPeriodWindow = getIntRange("kotlinx.exec.PollPeriodMillis")?.also {
        require(it.start > 0)
        require(it.endInclusive >= it.start)
    } ?: (2 .. 34) //30fps = 33.3ms period

    private val otherSignals = ConflatedBroadcastChannel<Unit>()
    private @Volatile var manualEOF = false

    override val standardErrorChannel = run {
        val context = Unconfined + CoroutineName("polling-stderr-$pid")
        Supported(standardErrorReader.toPolledReceiveChannel(context, DelayMachine(PollPeriodWindow, otherSignals)))
    }
    override val standardOutputChannel = run {
        val context = Unconfined + CoroutineName("polling-stdout-$pid")
        Supported(standardOutputReader.toPolledReceiveChannel(context, DelayMachine(PollPeriodWindow, otherSignals)))
    }

    override val exitCodeDeferred = Supported(
            GlobalScope.async(Unconfined + CoroutineName("polling-waitFor-$pid")) {
                val delayMachine = DelayMachine(PollPeriodWindow, otherSignals)
                delayMachine.waitForByPollingPeriodically { ! process.isAlive }
                val result = process.exitValue()
                manualEOF = true
                delayMachine.signalPollResult()
                trace { "polling-waitfor-$pid got result $result" }
                result
            }
    )


    private fun Reader.toPolledReceiveChannel(
            context: CoroutineContext,
            delayMachine: DelayMachine
    ): ReceiveChannel<Char> {

        val name = this.toString()

        val result = GlobalScope.produce(context) {

            val chunkBuffer = CharArray(128)

            reading@ while (isActive) {

                delayMachine.waitForByPollingPeriodically { ready() || manualEOF }

                while (ready() || manualEOF) {
                    val readByteCount = read(chunkBuffer)
                    if (readByteCount == EOF_VALUE) {
                        break@reading
                    }

                    for(index in 0 until readByteCount) {
                        send(chunkBuffer[index])
                    }

                    yield() //manual EOF could be fired before reader can move,
                    // so to avoid flooding we yield here
                }
            }

            trace { "polling of $name completed" }
        }

        return object: ReceiveChannel<Char> by result {
            override fun toString() = "poll-$name"
        }
    }
}

internal class DelayMachine(
        private val delayWindowMillis: IntRange,
        private val otherSignals: ConflatedBroadcastChannel<Unit>,
        private val delayFactor: Float = 1.5f
) {

    init {
        require(delayWindowMillis.start > 0)
        require(delayWindowMillis.endInclusive >= delayWindowMillis.start)
        require(delayFactor > 1.0f)
    }

    private val backoffMillis = AtomicInteger(delayWindowMillis.first)
    private val otherSignalsSubscription = otherSignals.openSubscription()

    suspend fun waitForByPollingPeriodically(condition: () -> Boolean){
        while( ! condition()) {
            val backoff = backoffMillis.updateAndGet { updateBackoff(it, delayWindowMillis) }

            select<Unit> {
                onTimeout(backoff.toLong()) { Unit }
                otherSignalsSubscription.onReceiveOrNull { Unit }
            }

            yield() //if, for whatever reason, we're getting flooded with other signals,
            // this ensures we yield to previously enqueued jobs on our dispatcher
        }
        signalPollResult()
    }

    fun signalPollResult(){
        backoffMillis.set(delayWindowMillis.start)
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