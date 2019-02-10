package groostav.kotlinx.exec

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import java.io.Reader
import java.lang.Long.getLong
import java.lang.Runnable
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext


internal class ThreadBlockingListenerProvider(val process: Process, val pid: Int, val config: ProcessConfiguration): ProcessListenerProvider {

    companion object: ProcessListenerProvider.Factory {
        override fun create(process: Process, pid: Int, config: ProcessConfiguration) =
                ThreadBlockingListenerProvider(process, pid, config)

        fun prestart(){
            // 1 thread for each { stderr.read(), stdout.read(), proc.waitFor() }
            BlockableDispatcher.prestart(3)
        }
    }

    override val standardErrorChannel = run {
        val standardErrorReader = NamedTracingProcessReader.forStandardError(process, pid, config)
        val context = BlockableDispatcher + CoroutineName("blocking-stderr-$pid")
        Supported(standardErrorReader.toPumpedReceiveChannel(context))
    }
    override val standardOutputChannel = run {
        val standardOutputReader = NamedTracingProcessReader.forStandardOutput(process, pid, config)
        val context = BlockableDispatcher + CoroutineName("blocking-stdout-$pid")
        Supported(standardOutputReader.toPumpedReceiveChannel(context))
    }
    override val exitCodeDeferred = run {
        val context = BlockableDispatcher + CoroutineName("blocking-waitFor-$pid")
        Supported(GlobalScope.async(context) {
            process.waitFor().also { trace { "blocking-waitFor-$pid got result $it" } }
        })
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

        val name = this.toString()

        val result = GlobalScope.produce(context + BlockableDispatcher) {

            while (isActive) {
                //TODO: An abandoned hanging process causes this to leak a thread.
                // given that you've demanded thread-blocking instead of polling,
                // I think that a solution has to invole making `read()` interruptable.
                // and then binding that to cancellation mechanics.
                // then we need to make sure this job is cancelled.
                val nextCodePoint = read().takeUnless { it == EOF_VALUE }
                val nextChar = nextCodePoint?.toChar() ?: break

                send(nextChar)
            }

            trace { "blocking-pump of $name completed" }
        }
        return object: ReceiveChannel<Char> by result {
            override fun toString() = "pump-$name"
        }
    }

}

private object BlockableThreadFactory: ThreadFactory {

    // use a smaller stack-size because we could have a lot of these threads and
    // we know that our dispatcher is limited by the max depth of a coroutine dispatcher (loop & tail recursion)
    // and java.lang.process IO code.
    private val StackSize = getLong("kotlinx.exec.blockable.stackSize") ?: 256 * 1024L
            //TODO: coerceAtMost(getJvmArg("-Xss"))?

    private val group: ThreadGroup
    private val threadNumber = AtomicInteger(1)
    private val namePrefix: String

    init {
        group = System.getSecurityManager()?.threadGroup ?: Thread.currentThread().threadGroup
        namePrefix = "exec-thread-"
    }

    override fun newThread(r: Runnable) = Thread(
            group, r,
            namePrefix + threadNumber.getAndIncrement(),
            StackSize
    ).apply {
        // users of kotlinx.coroutines will be calling into kotlinx.exec
        // on their own executors, which will keep the JVM alive
        // if the caller abandons his hung RunningProcess instance,
        // I do not believe it reasonable to keep the jvm alive
        // until the sub-process exits.
        // Instead the jvm should shut-down and let the process run its course..
        // of course, if the user `awaits()` on the instance then his dispatcher will keep the JVM alive.
        isDaemon = true
        // add priority to ensure that output values are quickly flushed to terminal buffers.
        priority = Thread.NORM_PRIORITY + Thread.MAX_PRIORITY / 2
    }

}

//see java.util.concurrent.Executors.newCachedThreadPool()
internal object BlockableDispatcher: CoroutineContext by ThreadPoolExecutor(
        0,
        Integer.MAX_VALUE,
        //shorten the keep-alive
        5L, TimeUnit.SECONDS,
        SynchronousQueue(),
        BlockableThreadFactory
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
                    latch.countDown()
                }
            }

            latch.await()

            trace { "prestarted $jobs threads on $this" }
        }

    }
}
