package groostav.kotlinx.exec

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.*
import kotlinx.coroutines.experimental.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.experimental.selects.SelectClause1
import kotlinx.coroutines.experimental.selects.SelectClause2
import kotlinx.coroutines.experimental.selects.select
import kotlinx.coroutines.experimental.sync.Mutex
import kotlinx.coroutines.experimental.sync.withLock
import java.io.*
import java.nio.charset.Charset
import java.util.*
import java.util.concurrent.CountDownLatch

import java.lang.ProcessBuilder as JProcBuilder
import java.lang.Process as JProcess

import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal val TRACE = true

internal inline fun trace(message: () -> String){
    if(TRACE){
        println(message())
    }
}

internal class RunningProcessImpl(
        _config: ProcessBuilder,
        private val process: JProcess,
        private val processControlWrapper: ProcessFacade
): RunningProcess {

    private val config = _config.copy()

    override val processID: Int = processControlWrapper.pid.value

    private val _standardOutput: SourcedBroadcastChannel<Char> = process.inputStream.toPumpedReceiveChannel("std-out-$processID", config)
    private val _standardError: SourcedBroadcastChannel<Char> = process.errorStream.toPumpedReceiveChannel("std-err-$processID", config)
    private val _standardInput: SendChannel<Char> = process.outputStream.toSendChannel(config.encoding)
    private val inputLineLock = Mutex()

    private val standardOutputLines: BroadcastChannel<String> by lazy { BroadcastChannel<String>(10).apply {
        launch(blockableThread) {
            _standardOutput.openSubscription().lines(config.delimiters).consumeEach { send(it) }
        }
    }}
    private val standardErrorLines: BroadcastChannel<String> by lazy { BroadcastChannel<String>(10).apply {
        launch(blockableThread) {
            _standardError.openSubscription().lines(config.delimiters).consumeEach { send(it) }
        }
    }}

    val errorHistory = async<Queue<String>> {
        val result = LinkedList<String>()
        if (config.linesForExceptionError > 0) {
            standardErrorLines.openSubscription().consumeEach {
                result.addLast(it)
                if (result.size > config.linesForExceptionError) {
                    result.removeFirst()
                }
            }
        }
        result
    }

    private val killLock = Mutex()

    override val standardOutput: ReceiveChannel<Char> by lazy { _standardOutput.openSubscription() }
    override val standardError: ReceiveChannel<Char> by lazy { _standardError.openSubscription() }
    override val standardInput: SendChannel<Char> by lazy { actor<Char> {
        consumeEach {
            inputLineLock.withLock {
                _standardInput.send(it)
            }
        }
        _standardInput.close()
    }}

    private val _exitCode: CompletableDeferred<Int> = CompletableDeferred<Int>().apply {
        processControlWrapper.addCompletionHandle().value { result ->
            launch(blockableThread) {

                trace { "$processID exited with $result, closing streams" }

                _standardOutput.join()
                _standardError.join()

                standardErrorLines.join() //this is the problem
                //the broadcast channel, the way I've implemented it above for _standardOutput and _stdErr,
                // is that the join() method blocks until the producer is done,
                // but it gives no way to know how much back pressure the subscribers are offering
                // it seems like right now, there simply isnt any way for something like
                standardErrorLines.dispatchAllRemainingJobsAndClose()
                //the invokeOnClose() method might do it: https://github.com/Kotlin/kotlinx.coroutines/issues/341
                // remember that broadcast channels will sink any back-pressure put on them and offer no pressure
                // to the channel they pull from. In that way they act like a 'kink' in the reciever-channel flow:
                // if a particular subscriber is slow, they prevent the upstream from knowing about it.
                // this also explains why for example the error line facility *sometimes* looses lines, its simply that
                // no amount of careful join logic can gaurentee that all subscribers of a broadcase channel are finished.
                // but, building a simple multiplexing channel shouldnt be so-hard right?
                standardOutputLines.join()

                trace { "$processID std-err and std-out closed, complete with exitValue=$result" }

                if (result in config.expectedOutputCodes) {
                    complete(result)
                }
                else {
                    val errorLines = errorHistory.await().toList()
                    val exception = makeExitCodeException(config.command, result, config.expectedOutputCodes, errorLines)
                    completeExceptionally(exception)
                }
            }
        }
    }

    override val exitCode: Deferred<Int> = async<Int>(Unconfined) {
        try {
            _exitCode.await()
        }
        catch(ex: CancellationException){
            try {
                kill()
            }
            catch(innerEx: Exception){
                //todo: add suppressed exception is java 9.
            }
            throw ex
        }
        finally {
            trace { "$processID exited" }
        }
    }


    override suspend fun kill(): Unit = withContext<Unit>(blockableThread){

        val gracefulTimeousMillis = config.gracefulTimeousMillis

        killLock.withLock {
            if(_exitCode.isCompleted) return@withContext

            try {

                if (gracefulTimeousMillis > 0) {
                    processControlWrapper.killGracefully(config.includeDescendantsInKill)
                    withTimeoutOrNull(gracefulTimeousMillis, TimeUnit.MILLISECONDS) { _exitCode.join() }

                    if (_exitCode.isCompleted) { return@withContext }
                }

                processControlWrapper.killForcefully(config.includeDescendantsInKill)
                _exitCode.join() //can this fail?
            }
            finally {
                _standardOutput.join()
                _standardError.join()
                _standardInput.close()
            }
        }
    }

    override suspend fun join(): Unit = _exitCode.join()

    private val inputLines by lazy {
        actor<String> {
            consumeEach { nextLine ->
                inputLineLock.withLock {
                    nextLine.forEach { _standardInput.send(it) }
                    System.lineSeparator().forEach { _standardInput.send(it) }
                }
            }
        }
    }

    //SendChannel
    override val isClosedForSend: Boolean get() = inputLines.isClosedForSend
    override val isFull: Boolean get() = inputLines.isFull
    override val onSend: SelectClause2<String, SendChannel<String>> = inputLines.onSend
    override fun offer(element: String): Boolean = inputLines.offer(element)
    override suspend fun send(element: String) = inputLines.send(element)
    override fun close(cause: Throwable?) = inputLines.close(cause)

    private val aggregateChannel by lazy {
        produce<ProcessEvent> {

            val errorLines = standardErrorLines.openSubscription()
            val outputLines = standardOutputLines.openSubscription()

            while (isActive) {
                val next = select<ProcessEvent?> {
                    if (!errorLines.isClosedForReceive) errorLines.onReceiveOrNull { errorMessage ->
                        errorMessage?.let { StandardError(it) }
                    }
                    if (!outputLines.isClosedForReceive) outputLines.onReceiveOrNull { outputMessage ->
                        outputMessage?.let { StandardOutput(it) }
                    }
                    exitCode.onAwait { ExitCode(it) }
                }
                if (next == null) continue
                send(next)
                if (next is ExitCode) return@produce
            }
        }
    }

    //ReceiveChannel
    override val isClosedForReceive: Boolean get() = aggregateChannel.isClosedForReceive
    override val isEmpty: Boolean get() = aggregateChannel.isEmpty
    override val onReceive: SelectClause1<ProcessEvent> get() = aggregateChannel.onReceive
    override val onReceiveOrNull: SelectClause1<ProcessEvent?> get() = aggregateChannel.onReceiveOrNull
    override fun iterator(): ChannelIterator<ProcessEvent> = aggregateChannel.iterator()
    override fun poll(): ProcessEvent? = aggregateChannel.poll()
    override suspend fun receive(): ProcessEvent = aggregateChannel.receive()
    override suspend fun receiveOrNull(): ProcessEvent? = aggregateChannel.receiveOrNull()
    override fun cancel(cause: Throwable?): Boolean = aggregateChannel.cancel(cause)
}


internal val blockableThread: CloseableCoroutineDispatcher = ThreadPoolExecutor(
        0,
        Integer.MAX_VALUE,
        100L,
        TimeUnit.MILLISECONDS,
        SynchronousQueue()
).asCoroutineDispatcher()

// hack to avoid late thread allocation, consider jvm process documentation
//
// >Because some native platforms only provide limited buffer size for standard input and output streams,
// >failure to promptly write the input stream or read the output stream of the subprocess
// >may cause the subprocess to block, or even deadlock."
//
// because we're allocating threads to 'pump' those streams,
// the thread-allocation time might not be 'prompt' enough.
internal fun CoroutineDispatcher.prestart(jobs: Int){

    trace { "prestarting $jobs on $this, possible deadlock..." }

    // this might well premature optimization
    // TODO: microbenchmark? when is this helpful?
    val latch = CountDownLatch(jobs)
    for(jobId in 1 .. jobs){
        launch(this) { latch.countDown() }
    }

    latch.await()

    trace { "prestarted $jobs threads on $this" }
}

private class SourcedBroadcastChannel<T>(
        private val broadcastChannel: BroadcastChannel<T>,
        private val job: Job,
        private val channelName: String
): BroadcastChannel<T> by broadcastChannel {
    suspend fun join() = job.join()

    override fun toString(): String = "SourcedBroadcastChannel[source=$channelName]"

    override fun openSubscription() = object: ReceiveChannel<T> by broadcastChannel.openSubscription() {
        override fun toString() = "Subscription[source=$channelName]"
    }
}
private fun InputStream.toPumpedReceiveChannel(channelName: String, config: ProcessBuilder): SourcedBroadcastChannel<Char> {

    val source = produce(capacity = UNLIMITED, context = blockableThread){
        val reader = BufferedReader(InputStreamReader(this@toPumpedReceiveChannel, config.encoding))

        trace { "SOF on $channelName" }

        while(isActive) {
            val nextCodePoint = reader.read().takeUnless { it == -1 }
            if(nextCodePoint == null){
                trace { "EOF on $channelName" }
                break
            }
            val nextChar = nextCodePoint.toChar()

            send(nextChar)
        }
    }

    val result = BroadcastChannel<Char>(config.charBufferSize)
    val job = launch(blockableThread){
        try {
            source.consumeEach { result.send(it) }
        }
        finally {
            close()
            trace { "closed $channelName" }
        }
    }

    return SourcedBroadcastChannel(result, job, channelName)
}


private fun OutputStream.toSendChannel(encoding: Charset = Charsets.UTF_8): SendChannel<Char> {
    return actor<Char>(blockableThread) {
        val writer = OutputStreamWriter(this@toSendChannel, encoding)

        consumeEach { nextChar ->

            try {
                writer.append(nextChar)
                if(nextChar == '\n') writer.flush()
            }
            catch (ex: FileNotFoundException) {
                //writer was closed, process was terminated.
                //TODO need a test to induce this, verify correctness.
                return@actor
            }
        }
    }
}
internal sealed class Maybe<out T> {
    abstract val value: T
}
internal data class Supported<out T>(override val value: T): Maybe<T>()
internal object Unsupported : Maybe<Nothing>() { override val value: Nothing get() = TODO() }

internal typealias ResultHandler = (Int) -> Unit
internal typealias ResultEventSource = (ResultHandler) -> Unit

internal interface ProcessFacade {

    val pid: Maybe<Int> get() = Unsupported
    fun killGracefully(includeDescendants: Boolean): Maybe<Unit> = Unsupported
    fun killForcefully(includeDescendants: Boolean): Maybe<Unit> = Unsupported
    fun addCompletionHandle(): Maybe<ResultEventSource> = Unsupported
}

internal infix fun ProcessFacade.thenTry(backup: ProcessFacade): ProcessFacade {

    fun flatten(facade: ProcessFacade): List<ProcessFacade> = when(facade){
        is CompositeProcessFacade -> facade.facades
        else -> listOf(facade)
    }

    return CompositeProcessFacade(flatten(this) + flatten(backup))
}

internal class CompositeProcessFacade(val facades: List<ProcessFacade>): ProcessFacade {

    override val pid: Maybe<Int> get() = firstSupported { it.pid }
    override fun killGracefully(includeDescendants: Boolean): Maybe<Unit> = firstSupported { it.killGracefully(includeDescendants) }
    override fun killForcefully(includeDescendants: Boolean): Maybe<Unit> = firstSupported { it.killForcefully(includeDescendants) }
    override fun addCompletionHandle(): Maybe<ResultEventSource> = firstSupported { it.addCompletionHandle() }

    private fun <R> firstSupported(call: (ProcessFacade) -> Maybe<R>): Maybe<R> {
        return facades.asSequence().map(call).firstOrNull { it != Unsupported }
                ?: throw UnsupportedOperationException("none of $facades supports $call")
    }
}

//TODO: dont like dependency on zero-turnaround, but its so well packaged...
//
// on windows: interestingly, they use a combination the cmd tools taskkill and wmic, and a reflection hack + JNA Win-Kernel32 call to manage the process
//   - note that oshi (https://github.com/oshi/oshi, EPL license) has some COM object support... why cant I just load wmi.dll from JNA?
// on linux: they dont support the deletion of children (???), and its pure shell commands (of course, since the shell is so nice)
// what about android or even IOS? *nix == BSD support means... what? is there even a use-case here?
//
// so I think cross-platform support is a grid of:
//                    windows           | osx | linux
// getPID(jvmProc)     jre9->kern32     |
// descendants(pid)    jre9->wmic       |  ?  |
// kill(pid)         taskkill           |  ?  |
// isAlive(pid)        wmic             |  ?  |
// join(pid)          jvm...?           |
//
// and you probably want to target jdk 6, so a third dimension might be jdk-9
//
// also, what can I steal from zero-turnarounds own process API? Its not bad, it uses a builder and it buffers _all_ standard output.
// clearly their consumption model is for invoking things like `ls`, not so much `ansys`.
//
// also, java 9's API is nice, but it doesnt provide kill mechanism
// http://www.baeldung.com/java-9-process-api

internal fun makeCompositImplementation(jvmRunningProcess: JProcess): ProcessFacade {

    //TODO: look at features, reflect on runtime, maybe use a table? whats the most concise way in kotlin to express a feature map?

    return ZeroTurnaroundProcessFacade(jvmRunningProcess) thenTry ThreadBlockingResult(jvmRunningProcess)
}

