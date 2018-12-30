package groostav.kotlinx.exec

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.selects.SelectClause1
import kotlinx.coroutines.selects.SelectClause2
import kotlinx.coroutines.selects.SelectInstance
import kotlinx.coroutines.sync.Mutex
import java.io.IOException
import java.lang.IllegalStateException
import java.lang.ProcessBuilder.Redirect.*
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

typealias FlushCommand = Unit

@InternalCoroutinesApi internal class ExecCoroutine private constructor(
        private val config: ProcessBuilder,
        parentContext: CoroutineContext,
        private val pidGen: ProcessIDGenerator,
        private val listenerFactory: ProcessListenerProvider.Factory,
        private val aggregateChannel: Channel<ProcessEvent>,
        private val inputLines: Channel<String>
):
        AbstractCoroutine<Int>(parentContext + makeName(config), true),
        RunningProcess,
        SelectClause1<Int>,
        ReceiveChannel<ProcessEvent> by aggregateChannel,
        SendChannel<String> by inputLines
{

    sealed class State {
        object Uninitialized: State()
        class Prestarted(
                val jvmProcessBuilder: java.lang.ProcessBuilder,
                val recentErrorOutput: CircularArrayQueue<String>,
                val stdoutAggregatorJob: Job?,
                val stderrAggregatorJob: Job?,
                val exitCodeAggregatorJob: Job
        ): State()
        data class Running(val process: Process, val pid: Int): State()
        data class Completed(val pid: Int, val exitCode: Int): State()
    }

    private val shortName = config.command.first()

    private @Volatile var state: State = State.Uninitialized
        get() = field
        set(value) { field = value.also { trace { "process $shortName moved to state $it"} } }

    private val exitCodeInflection = CompletableDeferred<Int>()
    private val stdinInflection: Channel<Char> = Channel(Channel.RENDEZVOUS)
    private val stdoutInflection: SimpleInlineMulticaster<Char> = SimpleInlineMulticaster("$shortName-stdout")
    private val stderrInflection: SimpleInlineMulticaster<Char> = SimpleInlineMulticaster("$shortName-stderr")

    override val standardInput: Channel<Char> = Channel(Channel.RENDEZVOUS)
    override val standardOutput = stdoutInflection.openSubscription().tail(config.standardOutputBufferCharCount)
    override val standardError = stderrInflection.openSubscription().tail(config.standardErrorBufferCharCount)

    override val processID: Int by lazy {
        when(val state = state){
            State.Uninitialized, is State.Prestarted -> throw IllegalStateException("process not started")
            is State.Running -> state.pid
            is State.Completed -> state.pid
        }
    }

    internal fun prestart(){
        val state = state
        require(state is State.Uninitialized)

        run prestartStdin@{
            val stdinMutex = Mutex()

            inputLines.sinkTo(stdinInflection.flatMap<Char, String> { (it + config.inputFlushMarker).asIterable() }.lockedBy(stdinMutex))
            standardInput.sinkTo(stdinInflection.lockedBy(stdinMutex))

        }
        run prestartStdout@ {
//            stdoutInflection.openSubscription().tail(config.standardOutputBufferCharCount).sinkTo(standardOutput)
        }
        val recentErrorOutput = run prestartStderr@ {
//            stderrInflection.openSubscription().tail(config.standardErrorBufferCharCount).sinkTo(standardError)

            CircularArrayQueue<String>(config.linesForExceptionError).also { recentErrorOutput ->
                launch {
                    stderrInflection.openSubscription("err-cache").lines().consumeEach { errorMessageLine ->
                        recentErrorOutput.enqueue(errorMessageLine)
                    }
                }
            }
        }
        var stdoutAggregatorJob: Job? = null
        var stderrAggregatorJob: Job? = null
        val exitCodeAggregatorJob: Job

        run prestartAggregateChannel@ {
            if(config.aggregateOutputBufferLineCount > 0) {
                stdoutAggregatorJob = launch(Dispatchers.Unconfined) {
                    for (message in stdoutInflection.openSubscription("aggregator").lines()) {
                        onProcessEvent(StandardOutputMessage(message))
                    }
                }
                stderrAggregatorJob = launch(Dispatchers.Unconfined) {
                    for (message in stderrInflection.openSubscription("aggregator").lines()) {
                        onProcessEvent(StandardErrorMessage(message))
                    }
                }
            }
            exitCodeAggregatorJob = launch(Dispatchers.Unconfined){
                val result = exitCodeInflection.await()
                stdoutAggregatorJob?.join()
                stderrAggregatorJob?.join()
                onProcessEvent(ExitCode(result))
            }
        }
        val procBuilder = run prestartJvmProc@ {
            java.lang.ProcessBuilder(config.command).apply {

                environment().apply {
                    if (this !== config.environment) {
                        clear()
                        putAll(config.environment)
                    }
                }

                if(config.run { standardErrorBufferCharCount == 0 && aggregateOutputBufferLineCount == 0 }){
                    redirectError(DISCARD)
                }
                if(config.run { standardOutputBufferCharCount == 0 && aggregateOutputBufferLineCount == 0}){
                    redirectOutput(DISCARD)
                }

                directory(config.workingDirectory.toFile())
            }
        }

        this.state = State.Prestarted(procBuilder, recentErrorOutput, stdoutAggregatorJob, stderrAggregatorJob, exitCodeAggregatorJob)
    }

    suspend fun exec(): Int = try {
        val prestarted = state
        require(prestarted is State.Prestarted)

        val process = try { prestarted.jvmProcessBuilder.start() }
        catch(ex: IOException){ throw InvalidExecConfigurationException(ex.message ?: "", config, ex) }

        val pid = pidGen.findPID(process)
        val running = State.Running(process, pid).also { this.state = it }

        val listeners = listenerFactory.create(process, processID, config)

        stdoutInflection.sinkFrom(listeners.standardOutputChannel.value)
        stderrInflection.sinkFrom(listeners.standardErrorChannel.value)
        exitCodeInflection.sinkFrom(listeners.exitCodeDeferred.value)
        process.outputStream.toSendChannel(config).sinkFrom(stdinInflection)

        prestarted.run {
            stderrAggregatorJob?.join()
            stdoutAggregatorJob?.join()
            exitCodeAggregatorJob.join()
        }

        stderrInflection.asJob().join()
        stdoutInflection.asJob().join()
        val exitCode = exitCodeInflection.await()

        val completed = State.Completed(running.pid, exitCode).also { this.state = it }

        val expectedExitCodes = config.expectedOutputCodes

        val result = when {
            expectedExitCodes == null -> exitCode
            exitCode in expectedExitCodes -> exitCode
            else -> throw makeExitCodeException(config, exitCode, prestarted.recentErrorOutput.toList())
        }

        result.also { trace { "exec $shortName(PID=$pid) returned with code $result" }}
    }
    catch(ex: Exception){
        trace { "exec $shortName threw an exception of '${ex.message}'" }
        throw ex
    }

    override suspend fun kill() {
        // whats my strategy?
        // who am i suspending?
        // how can i atomically move to a cancelled state?

        //how about this:

        //kill:
        // we atomically update a state to 'Doomed', that state is checked before return in the exec body
        // we call kill gracefully
        // val success = withTimeoutOrNull(gracefulTime) { join() }
        // if (success == null) cancel()
        // listener.await()

        //cancel:
        // kill forcefully

        //so...
        // I think the block should probably start an actor in global scope
        // each of these feed it an input
        // at least that way we can reduce the concurrency to an actor managing its state.
//        fail;
        TODO()
    }

    override fun cancel(): Unit {
        cancel(null)
    }

    override fun onCancellation(cause: Throwable?) {
        when(cause){
            null -> {} //completed normally
            is CancellationException -> {  } // cancelled --> kill -9
            else -> {  } // failed --> kill -9
        }
    }

    override fun cancel0(): Boolean = cancel(null)

    override fun cancel(cause: Throwable?): Boolean {
        return super<AbstractCoroutine<Int>>.cancel(cause) // cancel the job
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

    companion object Factory {
        fun makeName(config: ProcessBuilder) = CoroutineName("exec ${config.command.joinToString()}")

        operator fun invoke(
                config: ProcessBuilder,
                parentContext: CoroutineContext,
                pidGen: ProcessIDGenerator,
                listenerFactory: ProcessListenerProvider.Factory
        ) = ExecCoroutine(
                config, parentContext, pidGen, listenerFactory,
                aggregateChannel = Channel(config.aggregateOutputBufferLineCount.asQueueChannelCapacity()),
                inputLines = Channel(Channel.RENDEZVOUS)
        )
    }
}