package groostav.kotlinx.exec

import kotlinx.coroutines.experimental.channels.ReceiveChannel
import kotlinx.coroutines.experimental.channels.produce
import kotlinx.coroutines.experimental.runBlocking
import kotlinx.coroutines.experimental.selects.select
import org.junit.Ignore
import org.junit.Test
import kotlin.coroutines.experimental.EmptyCoroutineContext


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
}
