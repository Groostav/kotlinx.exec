package groostav.kotlinx.exec

import kotlinx.coroutines.experimental.channels.ConflatedBroadcastChannel
import kotlinx.coroutines.experimental.channels.firstOrNull
import kotlinx.coroutines.experimental.channels.take
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.withTimeoutOrNull
import kotlinx.coroutines.experimental.yield
import java.util.concurrent.atomic.AtomicInteger


//TODO: casually running tests shows that many tests take 2x or 3x the time with this reader strategy.
// why?
internal class DelayMachine(
        val delayWindow: IntRange,
        val otherSignals: ConflatedBroadcastChannel<Unit>,
        val delayFactor: Float = 1.5f
) {

    init {
        require(delayWindow.start > 0)
        require(delayWindow.endInclusive >= delayWindow.start)
        require(delayFactor > 1.0f)
    }

    val backoff = AtomicInteger(0)
    val signals = ConflatedBroadcastChannel<Unit>()//TODO: replace with BroadcastChannel(0)

    suspend fun waitForByPollingPeriodically(condition: () -> Boolean){
        while(condition()) {
            val backoff = backoff.updateAndGet { updateBackoff(it, delayWindow) }

            withTimeoutOrNull(backoff){
                signals.openSubscription().take(2)
            }

            yield()
        }
        signalPollResult()
    }

    private fun updateBackoff(currentPollPeriodMillis: Int, pollPeriodMillis: IntRange): Int {
        return (currentPollPeriodMillis * 1.5).toInt()
                .coerceAtLeast(currentPollPeriodMillis + 1)
                .coerceAtMost(pollPeriodMillis.endInclusive)
    }

    private fun signalPollResult(){
        backoff.set(delayWindow.start)
        signals.offer(Unit)
    }
}

internal fun getIntRange(key: String): IntRange? = System.getProperty(key)?.let {
    val match = Regex("(-?\\d+)\\s*\\.\\.\\s*(-?\\d+)").matchEntire(it.trim())
            ?: throw UnsupportedOperationException("couldn't parse $it as IntRange (please use format '#..#' eg '1..234')")
    val (start, end) = match.groupValues.apply { require(size == 3) }.takeLast(2).map { it.toInt() }
    start .. end
}