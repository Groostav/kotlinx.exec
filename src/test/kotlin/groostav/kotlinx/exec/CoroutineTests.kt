package groostav.kotlinx.exec

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.*
import kotlinx.coroutines.experimental.selects.select
import org.junit.Ignore
import org.junit.Test
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import kotlin.coroutines.experimental.CoroutineContext
import kotlin.coroutines.experimental.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull


class CoroutineTests {

    @Ignore("unbelievable, select {} does simply abandon you!")
    @Test fun `using an empty select clause doenst just abandon you`() = runBlocking {

        val producer = produce<String> {
            select<String> {
                val x = 4;
            }

            val y = 4;
        }

        val result = producer.receiveOrNull()

        val z = 4;
    }

    @Ignore("see https://youtrack.jetbrains.com/issue/KT-24209")
    @Test fun `when using select in producer to merge channels should operate normally`() = runBlocking<Unit> {

        val sourceOne = produce { send(1); send(2); send(3) }

        //inlining this var causes the test to pass
        var s1: ReceiveChannel<Any>? = sourceOne

        val merged = produce<Any>{
            while(isActive){ //removing this while causes the test to pass
                val next = select<Any> {
                    s1?.onReceive { it }
                }
            }
        }

        merged.receiveOrNull()
        //does not reach here.
    }

    @Ignore("https://youtrack.jetbrains.net/issue/KT-25716")
    @Test fun `one two`(){
//        class Message(val outputQuestionLine: String, val response: CompletableDeferred<String> = CompletableDeferred())
//
//        val localDecoder = listOf<Message>()
    }


    internal class DelayMachine(
            val otherSignals: ConflatedBroadcastChannel<Unit>,
            val delayWindow: IntRange = 5 .. 100,
            val delayFactor: Float = 1.5f
    ) {
        val backoff = AtomicInteger(0)

        suspend fun waitForByPollingPeriodically(condition: () -> Boolean){
            while(condition()) {
                val backoff = backoff.updateAndGet { updateBackoff(it, delayWindow) }

                withTimeoutOrNull(backoff){
                    otherSignals.openSubscription().take(2)
                }
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
            otherSignals.offer(Unit)
        }
    }

    @Test fun `when opening subscription after member already published should suspend`() = runBlocking {

        val channel = ConflatedBroadcastChannel<Unit>()
        channel.send(Unit)

        val r = withTimeoutOrNull(200) {
            channel.openSubscription().receive()
        }

        assertNotNull(r)
    }

    @Test fun `mssing around with eventloop`() = runBlocking {
        val loop = EventLoop(Thread.currentThread())

        val result = async(loop){
            val x = 4;
            x
        }

        (loop as EventLoop).processNextEvent()

        val r = result.await()

        assertEquals(4, r)
    }
}
