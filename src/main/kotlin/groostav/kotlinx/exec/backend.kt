package groostav.kotlinx.exec

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.CancellationException
import kotlinx.coroutines.experimental.channels.*
import kotlinx.coroutines.experimental.selects.SelectClause1
import kotlinx.coroutines.experimental.selects.SelectClause2
import kotlinx.coroutines.experimental.selects.select
import kotlinx.coroutines.experimental.sync.Mutex
import kotlinx.coroutines.experimental.sync.withLock
import java.util.*
import java.util.concurrent.*

import java.lang.ProcessBuilder as JProcBuilder
import java.lang.Process as JProcess

internal val TRACE = true

internal inline fun trace(message: () -> String){
    if(TRACE){
        println(message())
    }
}

internal class RunningProcessImpl(
        _config: ProcessBuilder,
        private val process: JProcess,
        private val processControlWrapper: ProcessControlFacade
): RunningProcess {

    private val config = _config.copy()

    override val processID: Int = processControlWrapper.pid.value

    // region output

    private val _standardOutput: SimpleInlineMulticaster<Char> = SimpleInlineMulticaster(
            process.inputStream.toPumpedReceiveChannel("stdout-$processID", config)
    )
    private val _standardOutputLines: SimpleInlineMulticaster<String> by lazy {
        SimpleInlineMulticaster(_standardOutput.openSubscription().lines(config.delimiters))
    }

    private val _standardError: SimpleInlineMulticaster<Char> = SimpleInlineMulticaster(
            process.errorStream.toPumpedReceiveChannel("stderr-$processID", config)
    )
    private val _standardErrorLines: SimpleInlineMulticaster<String> by lazy {
        SimpleInlineMulticaster(_standardError.openSubscription().lines(config.delimiters))
    }

    private val errorHistory = async<Queue<String>>(Unconfined) {
        val result = LinkedList<String>()
        if (config.linesForExceptionError > 0) {
            _standardErrorLines.openSubscription().consumeEach {
                result.addLast(it)
                if (result.size > config.linesForExceptionError) {
                    result.removeFirst()
                }
            }
        }
        result
    }

    override val standardOutput: ReceiveChannel<Char> by lazy {
        _standardOutput.openSubscription().backPressureFreeMostRecent(config.charBufferSize)
    }
    override val standardError: ReceiveChannel<Char> by lazy {
        _standardError.openSubscription().backPressureFreeMostRecent(config.charBufferSize)
    }

    // endregion

    // region input

    private val _standardInput: SendChannel<Char> = process.outputStream.toSendChannel(config)
    private val inputLineLock = Mutex()

    override val standardInput: SendChannel<Char> by lazy { actor<Char> {
        consumeEach {
            inputLineLock.withLock {
                _standardInput.send(it)
            }
        }
        _standardInput.close()
    }}

    // endregion input

    //region join, kill

    private val killLock = Mutex()

    private val _exitCode: CompletableDeferred<Int> = CompletableDeferred<Int>().apply {
        processControlWrapper.completionEvent.value { result ->
            launch(blockableThread) {

                trace { "$processID exited with $result, closing streams" }

                _standardOutput.join()
                _standardError.join()

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

                    withTimeoutOrNull(gracefulTimeousMillis, TimeUnit.MILLISECONDS) {
                        processControlWrapper.tryKillGracefullyAsync(config.includeDescendantsInKill)
                        _exitCode.join()
                    }

                    if (_exitCode.isCompleted) { return@withContext }
                }

                processControlWrapper.killForcefullyAsync(config.includeDescendantsInKill)
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

    //endregion

    //region SendChannel

    private val inputLines by lazy {
        actor<String> {
            val newlineString = System.lineSeparator()
            consumeEach { nextLine ->
                inputLineLock.withLock {
                    nextLine.forEach { _standardInput.send(it) }
                    newlineString.forEach { _standardInput.send(it) }
                    val x = 4;
                }
            }
        }
    }

    override val isClosedForSend: Boolean get() = inputLines.isClosedForSend
    override val isFull: Boolean get() = inputLines.isFull
    override val onSend: SelectClause2<String, SendChannel<String>> = inputLines.onSend
    override fun offer(element: String): Boolean = inputLines.offer(element)
    override suspend fun send(element: String) = inputLines.send(element)
    override fun close(cause: Throwable?) = inputLines.close(cause)

    //endregion

    //region ReceiveChannel

    private val aggregateChannel: ReceiveChannel<ProcessEvent> = run {

        val errorLines = _standardErrorLines.openSubscription()
        val outputLines = _standardOutputLines.openSubscription()

        val actual = produce<ProcessEvent> {

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

        val namedAggregate = object: ReceiveChannel<ProcessEvent> by actual {
            override fun toString() = "aggregate[out=$outputLines,err=$errorLines]"
        }

        return@run namedAggregate.backPressureFreeMostRecent(config.lineBufferSize + 1)
        // +1 for exitCode. If the configuration has statically known math
        // (eg 54 lines for `ls` of a directory with 54 items).
    }

    override val isClosedForReceive: Boolean get() = aggregateChannel.isClosedForReceive
    override val isEmpty: Boolean get() = aggregateChannel.isEmpty
    override val onReceive: SelectClause1<ProcessEvent> get() = aggregateChannel.onReceive
    override val onReceiveOrNull: SelectClause1<ProcessEvent?> get() = aggregateChannel.onReceiveOrNull
    override fun iterator(): ChannelIterator<ProcessEvent> = aggregateChannel.iterator()
    override fun poll(): ProcessEvent? = aggregateChannel.poll()
    override suspend fun receive(): ProcessEvent = aggregateChannel.receive()
    override suspend fun receiveOrNull(): ProcessEvent? = aggregateChannel.receiveOrNull()
    override fun cancel(cause: Throwable?): Boolean = aggregateChannel.cancel(cause)

    //endregion
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
// so we'll use a hack to make sure 2 threads exist such that when we dispatch jobs to this pool,
// the jobs will be subitted to a pool with 2 idle threads.
//
// TODO: how can this be tested? Can we find a place where not prestarting results in data being lost?
// what about a microbenchmark?
internal fun CoroutineDispatcher.prestart(jobs: Int){

    trace { "prestarting $jobs on $this, possible deadlock..." }

    val latch = CountDownLatch(jobs)
    for(jobId in 1 .. jobs){
        launch(this) { latch.countDown() }
    }

    latch.await()

    trace { "prestarted $jobs threads on $this" }
}

internal sealed class Maybe<out T> {
    abstract val value: T
}
internal data class Supported<out T>(override val value: T): Maybe<T>()
internal object Unsupported : Maybe<Nothing>() { override val value: Nothing get() = TODO() }

internal typealias ResultHandler = (Int) -> Unit
internal typealias ResultEventSource = (ResultHandler) -> Unit
