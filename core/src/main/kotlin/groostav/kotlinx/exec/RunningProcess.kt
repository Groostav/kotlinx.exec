package groostav.kotlinx.exec

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.selects.SelectClause1
import kotlinx.coroutines.selects.SelectClause2
import kotlinx.coroutines.selects.SelectInstance
import java.lang.IllegalStateException
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.suspendCoroutine

@Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE") //renames a couple of the param names for SendChannel & ReceiveChannel
/**
 * A concurrent proxy to an external operating system process.
 *
 * This class converts the otherwise difficult to manage java process primatives
 * into concurrently accessible values.
 *
 * It has two main modes:
 *
 * 1. two high-level line-by-line channels: one SendChannel (for input)
 *    and one RecieveChannel (for output)
 * 2. a set of lower level channels for character-by-character access to
 *    std-in, std-err, std-out, and the process exit code as a
 *    [SendChannel], two [ReceiveChannel]s, and a [Deferred] respectively.
 *
 * This interface was designed for use where some level of parallelism is required,
 * for example: when output messages must be read and processed as they are
 * emitted by the child process. If you have no such requirement for parallelism
 * it will likely be easier to use the [exec] or [execVoid] builders, which
 * provide sequential access to the same values, suspending until the sub-process
 * terminates.
 *
 * The receive channel implemented by this object is known as the aggregate channel,
 * and represents a multiplexed set of outputs from this process, including:
 * - standard error messages
 * - standard output messages
 * - the processes exit value
 * emitted as [ProcessEvent] instances.
 *
 * [execVoid] and [execAsync] are the most concise process-builder factories.
 */
interface RunningProcess: SendChannel<String>, ReceiveChannel<ProcessEvent>, Deferred<Int> {

    val standardOutput: ReceiveChannel<Char>
    val standardError: ReceiveChannel<Char>
    val standardInput: SendChannel<Char>

    /**
     * The exit code of the process, or [InvalidExitValueException] if configured
     *
     * If the process exits normally, and the process exit code is one of
     * [ProcessBuilder.expectedOutputCodes], then this value will be completed
     * with the exit code provided by the child process. If the process exits
     * with a code that is _not_ in the `expectedOutputCodes`, it will throw
     * an [InvalidExitValueException].
     *
     * Cancellation of this deferred will kill the backing process.
     */
    override suspend fun await(): Int

    /**
     * Ths process ID associated with this process.
     *
     * If the process has not yet been started, this method will throw [IllegalStateException].
     */
    val processID: Int

    /**
     * kills the process
     *
     * This method attempts to kill the process gracefully with an interrupt signal (SIG_INT)
     * in accordance with the settings in [ProcessBuilder.gracefulTimeoutMillis], then, if that fails,
     * uses a more aggressive kill signal (SIG_KILL) to end the process, suspending until the process
     * is terminated.
     *
     * Once this method returns, all outputs will be closed.
     */
    suspend fun kill(): Unit


    override val isClosedForReceive: Boolean
    override val isEmpty: Boolean
    override val onReceive: SelectClause1<ProcessEvent>
    override val onReceiveOrNull: SelectClause1<ProcessEvent?>
    override fun iterator(): ChannelIterator<ProcessEvent>
    override fun poll(): ProcessEvent?
    override suspend fun receive(): ProcessEvent
    override suspend fun receiveOrNull(): ProcessEvent?
    /**
     * kills the running process and drops any further not-yet-processed output
     * from standard-output, standard-error, or the aggregate channel
     *
     * Note, if you intended to mute the output channel without killing the process,
     * consider setting [ProcessBuilder.standardOutputBufferCharCount],
     * [ProcessBuilder.standardErrorBufferCharCount] and [ProcessBuilder.aggregateOutputBufferLineCount]
     * to zero.
     */
    override fun cancel(): Unit { cancel(null) }
    override fun cancel0(): Boolean = cancel(null)
    override fun cancel(cause: Throwable?): Boolean

    override val isClosedForSend: Boolean
    override val isFull: Boolean
    override val onSend: SelectClause2<String, SendChannel<String>>
    override fun offer(messageLine: String): Boolean
    override suspend fun send(messageLine: String)
    /**
     * sends the end-of-input signal to the input stream,
     * and closes the input channel.
     *
     * This does not directly terminate the process
     *
     * The character based input channel will also be closed.
     */
    override fun close(cause: Throwable?): Boolean
}

sealed class ProcessEvent {
    abstract val formattedMessage: String
}
data class StandardOutputMessage(val line: String): ProcessEvent() {
    override val formattedMessage get() = line
}
data class StandardErrorMessage(val line: String): ProcessEvent() {
    override val formattedMessage get() = "ERROR: $line"
}
data class ExitCode(val code: Int): ProcessEvent() {
    override val formattedMessage: String get() = "Process finished with exit code $code"
}

//
//internal class RunningProcessFactory {
//
//    val _standardOutputLines = SimpleInlineMulticaster<String>("stdout-lines")
//    val _standardErrorLines = SimpleInlineMulticaster<String>("stderr-lines")
//
//    internal fun create(
//            config: ProcessBuilder,
//            process: Process,
//            processID: Int,
//            processControl: ProcessControlFacade,
//            processListenerProvider: ProcessListenerProvider
//    ): RunningProcessImpl {
//
//        val _standardOutputSource = SimpleInlineMulticaster<Char>("stdout$processID")
//        val _standardErrorSource = SimpleInlineMulticaster<Char>("stderr$processID")
//
//        val result = RunningProcessImpl(
//                config,
//                processID,
//                process,
//                processControl,
//                processListenerProvider,
//                _standardOutputSource,
//                _standardOutputLines,
//                _standardErrorSource,
//                _standardErrorLines
//        )
//
//        //TODO: ok so this is kind've illegal, the above constructor starts a number of coroutines.
//        // it seems like its trying to use launch(Unconfined) as a way to get them into a "ready" state synchronously.
//        // this is prone to failure. We need better state management.
//        // ok, so it turns out parentScope.launch(Unconfined) does **not** give you the above assumed behaviour. fuu.
//        _standardOutputLines.syndicateAsync(_standardOutputSource.openSubscription().lines(config.delimiters))
//        _standardErrorLines.syndicateAsync(_standardErrorSource.openSubscription().lines(config.delimiters))
//        _standardOutputSource.syndicateAsync(processListenerProvider.standardOutputChannel.value)
//        _standardErrorSource.syndicateAsync(processListenerProvider.standardErrorChannel.value)
//
//        return result
//    }
//}
//
//internal class RunningProcessImpl(
//        _config: ProcessBuilder,
//        override val processID: Int,
//        private val process: Process,
//        private val processControlWrapper: ProcessControlFacade,
//        private val processListenerProvider: ProcessListenerProvider,
//        _standardOutputSource: SimpleInlineMulticaster<Char>,
//        _standardOutputLines: SimpleInlineMulticaster<String>,
//        _standardErrorSource: SimpleInlineMulticaster<Char>,
//        _standardErrorLines: SimpleInlineMulticaster<String>
//): RunningProcess {
//
//    private val config = _config.copy()
//    //private var state: Any = fail;
//    // we should formalize the state of this object into fields on the state machine.
//    // this will make it clearer from the debugger and easier to document.
//
//    // region output
//
//    private val _standardOutput: ReceiveChannel<Char>? = run {
//        if(config.standardOutputBufferCharCount == 0) null
//        else _standardOutputSource.openSubscription().tail(config.standardOutputBufferCharCount)
//    }
//    override val standardOutput: ReceiveChannel<Char> get() = _standardOutput ?: throw IllegalStateException(
//            "no buffer specified for standard-output"
//    )
//
//    private val _standardError: ReceiveChannel<Char>? = run {
//        if(config.standardErrorBufferCharCount == 0) null
//        else _standardErrorSource.openSubscription().tail(config.standardErrorBufferCharCount)
//    }
//    override val standardError: ReceiveChannel<Char> get() = _standardError ?: throw IllegalStateException(
//            "no buffer specified for standard-error"
//    )
//
//    // endregion
//
//    // region input
//
//    private val _standardInput: SendChannel<Char> by lazy { process.outputStream.toSendChannel(config) }
//    private val inputLineLock = Mutex()
//
//    override val standardInput: SendChannel<Char> by lazy { GlobalScope.actor<Char> {
//        consumeEach {
//            inputLineLock.withLock {
//                _standardInput.send(it)
//            }
//        }
//        _standardInput.close()
//    }}
//
//    // endregion input
//
//    //region join, kill
//
//    private val killed = AtomicBoolean(false)
//
//    private val _exitCode = GlobalScope.async(CoroutineName("process(PID=$processID)._exitcode")) {
//
//        val result = processListenerProvider.exitCodeDeferred.value.await()
//
//        trace { "$processID exited with $result, closing streams" }
//
//        _standardOutputSource.join()
//        _standardErrorSource.join()
//
//        result
//    }
//
//    private val errorHistory = GlobalScope.async<Queue<String>>(Unconfined + CoroutineName("process(PID=$processID).errorHistory")) {
//        val result = LinkedList<String>()
//        if (config.linesForExceptionError > 0) {
//            _standardErrorLines.openSubscription().consumeEach {
//                result.addLast(it)
//                if (result.size > config.linesForExceptionError) {
//                    result.removeFirst()
//                }
//            }
//        }
//        result
//    }
//
//    //user-facing control root.
//    override val exitCode: Deferred<Int> = GlobalScope.async<Int>(Unconfined + CoroutineName("process(PID=$processID).exitCode")) {
//        return@async try {
//            val result = _exitCode.await()
//
//            when {
//                killed.get() -> throw CancellationException()
//                config.expectedOutputCodes.let { it != null && result in it } -> result
//                else -> {
//                    val errorLines = errorHistory.await().toList()
//                    val exception = makeExitCodeException(config, result, errorLines)
//                    throw exception
//                }
//            }
//        }
//        catch (ex: CancellationException) {
//            killOnceWithoutSync()
//            _exitCode.join()
//            throw ex
//        }
//        finally {
//            shutdownZipper.waitFor(ShutdownItem.ExitCodeJoin)
//            trace { "exitCode pid=$processID in finally block, killed=$killed" }
//        }
//    }
//
//
//    override suspend fun join(): Unit {
//        exitCode.join()
//        shutdownZipper.waitFor(ShutdownItem.ProcessJoin)
//        trace { "process joined" }
//    }
//
//    override suspend fun kill(): Unit {
//        killOnceWithoutSync()
//        exitCode.join()
//    }
//
//    // must be reentrant, this method is called in `finally{}` logic
//    // TODO: probably in shutdown we dont want to bother with graceful shutdown,
//    // or we could make that configurable.
//    private suspend fun killOnceWithoutSync() {
//
//        val gracefulTimeoutMillis = config.gracefulTimeoutMillis
//
//        if( ! killed.getAndSet(true)) {
//
//            if (_exitCode.isCompleted) return
//
//            trace { "killing $processID" }
//
//            if (gracefulTimeoutMillis > 0) {
//
//                withTimeoutOrNull(gracefulTimeoutMillis) {
//                    processControlWrapper.tryKillGracefullyAsync(config.includeDescendantsInKill)
//
//                    _exitCode.join()
//                }
//
//                if (_exitCode.isCompleted) {
//                    return
//                }
//            }
//
//            processControlWrapper.killForcefullyAsync(config.includeDescendantsInKill)
//        }
//    }
//
//
//    //endregion
//
//    //region SendChannel
//
//    private val inputLines by lazy {
//        GlobalScope.actor<String>(Unconfined) {
//            val newlineString = System.lineSeparator()
//            consumeEach { nextLine ->
//                inputLineLock.withLock {
//                    nextLine.forEach { _standardInput.send(it) }
//                    newlineString.forEach { _standardInput.send(it) }
//                }
//            }
//        }
//    }
//
//    override val isClosedForSend: Boolean get() = inputLines.isClosedForSend
//    override val isFull: Boolean get() = inputLines.isFull
//    override val onSend: SelectClause2<String, SendChannel<String>> = inputLines.onSend
//    override fun offer(element: String): Boolean = inputLines.offer(element)
//    override suspend fun send(element: String) = inputLines.send(element)
//    override fun close(cause: Throwable?): Boolean {
//        _standardInput.close(cause)
//        return inputLines.close(cause)
//    }
//    override fun invokeOnClose(handler: (cause: Throwable?) -> Unit) = inputLines.invokeOnClose(handler)
//
//    //endregion
//
//    enum class ShutdownItem { ExitCodeJoin, AggregateChannel, ProcessJoin } //order matters
//    val shutdownZipper = ShutdownZipper(ShutdownItem.values().asList())
//
//    //region ReceiveChannel
//
//    private val aggregateChannel: ReceiveChannel<ProcessEvent> = when(config.aggregateOutputBufferLineCount){
//        0 -> {
//            val name = "aggregate[NoBufferedOutput, delay=$_exitCode]"
//            val actual = GlobalScope.produce<ProcessEvent>(Unconfined + CoroutineName("Process(PID=$processID).$name"), capacity = 1){
//                val code = _exitCode.await()
//                send(ExitCode(code))
//
//                shutdownZipper.waitFor(ShutdownItem.AggregateChannel)
//            }
//            object: ReceiveChannel<ProcessEvent> by actual{
//                override fun toString() = name
//            }
//        }
//        else -> {
//
//            val errorLines = _standardErrorLines.openSubscription()
//            val outputLines = _standardOutputLines.openSubscription()
//
//            val name = "aggregate[out=$outputLines,err=$errorLines]"
//
//            val actual = GlobalScope.produce<ProcessEvent>(Unconfined + CoroutineName("Process(PID=$processID).$name")) {
//                try {
//                    var stderrWasNull = false
//                    var stdoutWasNull = false
//
//                    loop@ while (isActive) {
//
//                        val next = select<ProcessEvent?> {
//                            if (!stderrWasNull) errorLines.onReceiveOrNull { errorMessage ->
//                                if(errorMessage == null){ stderrWasNull = true }
//                                errorMessage?.let { StandardErrorMessage(it) }
//                            }
//                            if (!stdoutWasNull) outputLines.onReceiveOrNull { outputMessage ->
//                                if(outputMessage == null){ stdoutWasNull = true }
//                                outputMessage?.let { StandardOutputMessage(it) }
//                            }
//                            _exitCode.onAwait { ExitCode(it) }
//                        }
//
//                        when (next) {
//                            null -> { }
//                            is ExitCode -> { send(next); break@loop }
//                            else -> send(next)
//                        } as Any
//                    }
//                }
//                finally {
//                    shutdownZipper.waitFor(ShutdownItem.AggregateChannel)
//                    trace { "aggregate channel done" }
//                }
//            }
//
//            val namedAggregate = object: ReceiveChannel<ProcessEvent> by actual {
//                override fun toString(): String = name
//            }
//
//            namedAggregate.tail(config.aggregateOutputBufferLineCount + 1)
//            // +1 for exitCode. If the configuration has statically known math
//            // (eg 54 lines for `ls` of a directory with 54 items).
//        }
//    }
//
//    override val isClosedForReceive: Boolean get() = aggregateChannel.isClosedForReceive
//    override val isEmpty: Boolean get() = aggregateChannel.isEmpty
//    override val onReceive: SelectClause1<ProcessEvent> get() = aggregateChannel.onReceive
//    override val onReceiveOrNull: SelectClause1<ProcessEvent?> get() = aggregateChannel.onReceiveOrNull
//
//    override fun iterator(): ChannelIterator<ProcessEvent> = aggregateChannel.iterator()
//    override fun poll(): ProcessEvent? = aggregateChannel.poll()
//    override suspend fun receive(): ProcessEvent = aggregateChannel.receive()
//    override suspend fun receiveOrNull(): ProcessEvent? = aggregateChannel.receiveOrNull()
//    override fun cancel() { cancel(null); Unit }
//    override fun cancel(cause: Throwable?): Boolean {
//        GlobalScope.launch(Unconfined + CoroutineName("process(PID=$processID).cancel-kill")) { killOnceWithoutSync() }
//        return aggregateChannel.cancel(cause)
//    }
//
//    //endregion
//}

typealias FlushCommand = Unit

class ProcessChannels(
        val name: String,
        val stdin: Channel<Char> = Channel(Channel.RENDEZVOUS),
        val stdout: SimpleInlineMulticaster<Char> = SimpleInlineMulticaster("$name-stdout"),
        val stderr: SimpleInlineMulticaster<Char> = SimpleInlineMulticaster("$name-stderr"),
        val flush: Channel<FlushCommand> = Channel(Channel.RENDEZVOUS)
)

@InternalCoroutinesApi internal class ExecCoroutine(
        private val config: ProcessBuilder,
        parentContext: CoroutineContext,
        override val standardInput: SendChannel<Char>,
        override val standardOutput: ReceiveChannel<Char>,
        override val standardError: ReceiveChannel<Char>,
        private val aggregateChannel: Channel<ProcessEvent>,
        inputLines: SendChannel<String>,
        private val pidGen: ProcessIDGenerator
):
        AbstractCoroutine<Int>(parentContext + makeName(config), true),
        RunningProcess,
        SelectClause1<Int>,
        ReceiveChannel<ProcessEvent> by aggregateChannel,
        SendChannel<String> by inputLines
{
    internal var process: Process? = null

    override val processID: Int get() = process?.let { pidGen.findPID(it) } ?: throw IllegalStateException()

    override suspend fun kill() {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun cancel(): Unit {
        cancel(null)
    }

    override fun onCancellation(cause: Throwable?) {
        when(cause){
            null -> {} //completed normally
            is CancellationException -> {  } // cancelled --killOnceWithoutSync?
            else -> {  } // failed --killOnceWithoutSync?
        }
    }

    override fun cancel0(): Boolean = cancel(null)

    override fun cancel(cause: Throwable?): Boolean {
        val wasCancelled: Boolean = true
        if (wasCancelled) super<AbstractCoroutine<Int>>.cancel(cause) // cancel the job
        return wasCancelled
    }

    override fun onStart() {
        require(process != null)
        require(processID != 0)
        trace { "onStart ${config.command.first()} with PID=$processID" }
    }

    internal suspend fun onProcessEvent(event: ProcessEvent){
        aggregateChannel.send(event)
    }



    //regarding cancellation:
    // problem: our cancellation is long-running.
    // [potential] solution: attach an unstarted job as a child.
    //                       override `onCancellation()` to call that job
    //                       that job an atomic (non-cancellable) impl of killOnceWITHSync

    //other things:
    // - error history
    // - PID -- getter that throws illegal state exception
    // - kill with sync, kill without sync.
    // - input lines, needs an actor.

    override val cancelsParent: Boolean get() = true

    //regarding subclassing this obnoxious internal methods:
    // see if maybe you can find the mangled generated access method,
    // something like super.internal$getCompletedInternal, that prevents you from forking this.
    //
    // then, I think we can take this, you'll need to call `process.start()` somewhere.
    private val _methods = this::class.java.methods

    private val _getCompleted = _methods.single { it.name == "getCompletedInternal\$kotlinx_coroutines_core" }
    override fun getCompleted(): Int = _getCompleted(this) as Int

    private val _await = _methods.single { it.name == "awaitInternal\$kotlinx_coroutines_core" }
    override suspend fun await(): Int = suspendCoroutine { _await(this, it) }

    override val onAwait: SelectClause1<Int> get() = this

    private val _registerSelectClause1 = _methods.single { it.name == "registerSelectClause1Internal\$kotlinx_coroutines_core" }
    override fun <R> registerSelectClause1(select: SelectInstance<R>, block: suspend (Int) -> R): Unit = _registerSelectClause1(this, select, block) as Unit

    companion object {
        fun makeName(config: ProcessBuilder) = CoroutineName("exec ${config.command.joinToString()}")
    }
}