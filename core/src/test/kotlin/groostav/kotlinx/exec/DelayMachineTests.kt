package groostav.kotlinx.exec

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ConflatedBroadcastChannel
import org.junit.Test
import java.util.*
import java.util.concurrent.Executors
import kotlin.test.assertEquals

class DelayMachineTests{

    //do tests in one other thread to reduce accidental parallism, which is likely to make these tests harder to pass.
    val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    @Test fun `when using three subscribers should properly leverage things`() = runBlocking<Unit>(dispatcher) {
        val broadcastChannel = ConflatedBroadcastChannel<Unit>()

        val one = DelayMachine(2 .. 10, broadcastChannel)
        val two = DelayMachine(2 .. 10, broadcastChannel)
        val three = DelayMachine(2 .. 10, broadcastChannel)

        val state = mutableListOf(10, 9, 8)

        launch(dispatcher) {
            val random = Random()
            while(state.any { it > 0 }){
                val next = Math.abs(random.nextInt()) % 3
                state[next] = (state[next] - 1).coerceAtLeast(0)
                delay((random.nextInt() % 20).toLong())
            }
        }

        val oneJob = async(dispatcher) {
            one.waitForByPollingPeriodically { state[0] == 0 }
            state[0]
        }
        val twoJob = async(dispatcher) {
            two.waitForByPollingPeriodically { state[1] == 0 }
            state[1]
        }
        val threeJob = async(dispatcher) {
            three.waitForByPollingPeriodically { state[2] == 0 }
            state[2]
        }

        val oneResult = oneJob.await()
        val twoResult = twoJob.await()
        val threeResult = threeJob.await()

        assertEquals(0, oneResult)
        assertEquals(0, twoResult)
        assertEquals(0, threeResult)
    }

    @Test fun `when using really long timeout should be interruptable with signal`() = runBlocking(dispatcher) {
        val broadcastChannel = ConflatedBroadcastChannel<Unit>()

        val machine = DelayMachine(999_999_999 .. 1_000_000_000, broadcastChannel)
        var polledResult: Boolean = false

        val pollingJob = launch(dispatcher) { machine.waitForByPollingPeriodically { polledResult } }
        delay(10)
        require( ! pollingJob.isCompleted)

        polledResult = true
        delay(10)
        require ( ! pollingJob.isCompleted)

        broadcastChannel.send(Unit)
        delay(10)
        require(pollingJob.isCompleted)
    }

    @Test fun `when using really long timeout should be manually interruptable`() = runBlocking(dispatcher) {
        val broadcastChannel = ConflatedBroadcastChannel<Unit>()

        val machine = DelayMachine(999_999_999 .. 1_000_000_000, broadcastChannel)
        var polledResult: Boolean = false

        val pollingJob = launch(dispatcher) { machine.waitForByPollingPeriodically { polledResult } }
        delay(10)
        require( ! pollingJob.isCompleted)

        polledResult = true
        machine.signalPollResult()

        delay(10)
        require(pollingJob.isCompleted)
    }
}