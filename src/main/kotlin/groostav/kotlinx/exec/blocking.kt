package groostav.kotlinx.exec

import kotlinx.coroutines.experimental.CompletableDeferred
import kotlinx.coroutines.experimental.asCoroutineDispatcher
import kotlinx.coroutines.experimental.channels.ReceiveChannel
import kotlinx.coroutines.experimental.channels.produce
import kotlinx.coroutines.experimental.launch
import java.io.Reader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.experimental.CoroutineContext


internal class ThreadBlockingListenerProvider(val process: Process, val pid: Int, val config: ProcessBuilder): ProcessListenerProvider {

    companion object: ProcessListenerProvider.Factory {
        override fun create(process: Process, pid: Int, config: ProcessBuilder) =
                ThreadBlockingListenerProvider(process, pid, config)
    }

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
        // the jobs will be subitted to a pool with 2 idle threads.
        //
        // TODO: how can this be tested? Can we find a place where not prestarting results in data being lost?
        // what about a microbenchmark?
        internal fun prestart(jobs: Int){

            trace { "prestarting $jobs on $this, possible deadlock..." }

            val latch = CountDownLatch(jobs)
            for(jobId in 1 .. jobs){
                launch(this) { latch.countDown() }
            }

            latch.await()

            trace { "prestarted $jobs threads on $this" }
        }
    }

}
