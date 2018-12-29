package groostav.kotlinx.exec

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import java.io.Reader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext


internal class ThreadBlockingListenerProvider(val process: Process, val pid: Int, val config: ProcessBuilder): ProcessListenerProvider {

    companion object: ProcessListenerProvider.Factory {
        override fun create(process: Process, pid: Int, config: ProcessBuilder) =
                ThreadBlockingListenerProvider(process, pid, config)
    }

    override val standardErrorChannel = run {
        val standardErrorReader = NamedTracingProcessReader.forStandardError(process, pid, config)
        val context = BlockableDispatcher + CoroutineName("blocking-process.stderr")
        Supported(standardErrorReader.toPumpedReceiveChannel(context))
    }
    override val standardOutputChannel = run {
        val standardOutputReader = NamedTracingProcessReader.forStandardOutput(process, pid, config)
        val context = BlockableDispatcher + CoroutineName("blocking-process.stdout")
        Supported(standardOutputReader.toPumpedReceiveChannel(context))
    }
    override val exitCodeDeferred = run {
        val context = BlockableDispatcher + CoroutineName("blocking-process.WaitFor")
        Supported(GlobalScope.async(context) { process.waitFor() })
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
    private fun Reader.toPumpedReceiveChannel(context: CoroutineContext = BlockableDispatcher): ReceiveChannel<Char> {

        val result = GlobalScope.produce(context + BlockableDispatcher) {

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
            override fun toString() = "pump-${this@toPumpedReceiveChannel}"
        }
    }

    object BlockableDispatcher: CoroutineContext by ThreadPoolExecutor(
            0,
            Integer.MAX_VALUE,
            100L,
            TimeUnit.MILLISECONDS,
            SynchronousQueue()
    ).asCoroutineDispatcher(){

        // hack to avoid late thread allocation, consider jvm process documentation
        //
        // >Because some native platforms only provide limited buffer size for standard input and output streams,
        // >failure to promptly write the input stream or read the output stream of the subprocess
        // >may cause the subprocess to block, or even deadlock."
        //
        // because we're allocating threads to 'pump' those streams,
        // the thread-allocation time might not be 'prompt' enough.
        // so we'll use a hack to make sure 2 threads exist such that when we dispatch jobs to this pool,
        // the jobs will be submitted to a pool with 2 idle threads.
        //
        // TODO: how can this be tested? Can we find a place where not prestarting results in data being lost?
        // what about a microbenchmark?
        internal fun prestart(jobs: Int){

            trace { "prestarting $jobs on $this, possible deadlock..." }

            runBlocking {
                val latch = CountDownLatch(jobs)
                for(jobId in 1 .. jobs){
                    val name = CoroutineName("Prestarting-job$jobId-${this@BlockableDispatcher}")
                    launch(name + BlockableDispatcher) {
                        latch.countDown() }
                }

                latch.await()

                trace { "prestarted $jobs threads on $this" }
            }

        }
    }

}