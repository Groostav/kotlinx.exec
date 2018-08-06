package groostav.kotlinx.exec

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.ConflatedBroadcastChannel
import org.junit.Test
import java.util.*
import java.util.concurrent.Executors

class DelayMachineTests{

    @Test fun `when using three subscribers should properly leverage things`() = runBlocking<Unit> {
        val broadcastChannel = ConflatedBroadcastChannel<Unit>()

        // put everythong on one thread to proove that the system doesnt need
        // 'accidental' parallelism.
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

        val one = CoroutineTests.DelayMachine(broadcastChannel)
        val two = CoroutineTests.DelayMachine(broadcastChannel)
        val three = CoroutineTests.DelayMachine(broadcastChannel)

        val state = mutableListOf(10, 9, 8)

        launch(dispatcher) {
            val random = Random()
            while(state.any { it > 0 }){
                val next = Math.abs(random.nextInt()) % 3
                state[next] = (state[next] - 1).coerceAtLeast(0)
                delay(random.nextInt() % 20)
            }
        }

        val oneResult = async(dispatcher) {
            one.waitForByPollingPeriodically { state[0] == 0 }
            1
        }
        val twoResult = async(dispatcher) {
            two.waitForByPollingPeriodically { state[1] == 0 }
            2
        }
        val threeResult = async(dispatcher) {
            three.waitForByPollingPeriodically { state[2] == 0 }
            3
        }

        oneResult.await()
        twoResult.await()
        threeResult.await()

        val x = 4;
    }

    @Test fun todo(): Unit = TODO("what kind of coverage can we get here, and what kind of test-hard points can we use?")
}