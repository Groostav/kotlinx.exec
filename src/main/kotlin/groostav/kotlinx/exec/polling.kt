package groostav.kotlinx.exec

import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.channels.ConflatedBroadcastChannel
import kotlinx.coroutines.experimental.channels.ReceiveChannel
import kotlinx.coroutines.experimental.channels.produce
import java.io.Reader
import kotlin.coroutines.experimental.CoroutineContext

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
            } finally {
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

                delayMachine.waitForByPollingPeriodically { !ready() && !manualEOF }

                while (ready() || manualEOF) {
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