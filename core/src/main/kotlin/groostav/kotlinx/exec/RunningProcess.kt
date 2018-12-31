package groostav.kotlinx.exec

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.selects.SelectClause1
import kotlinx.coroutines.selects.SelectClause2
import kotlinx.coroutines.selects.SelectInstance
import kotlinx.coroutines.sync.Mutex
import java.io.IOException
import java.lang.ClassCastException
import java.lang.IllegalStateException
import java.lang.ProcessBuilder.Redirect.*
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater
import javax.naming.ldap.ControlFactory
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
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
        private val processControlFacade: ProcessControlFacade.Factory,
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
                val recentErrorOutput: LinkedList<String>,
                val stdoutAggregatorJob: Job?,
                val stderrAggregatorJob: Job?,
                val exitCodeAggregatorJob: Job
        ): State() {
            override fun toString() = "Prestarted"
        }
        data class Running(
                val prestarted: Prestarted,
                val process: Process,
                val pid: Int,
                val reaper: Job? = null
        ): State()
        data class Completed(val pid: Int, val exitCode: Int): State()
    }

    private val shortName = config.command.first()

    private @Volatile var state: State = State.Uninitialized
        set(value) { field = value.also { trace { "process $shortName moved to state $it"} } }

    private val exitCodeInflection = CompletableDeferred<Int>()
    private val stdinInflection: Channel<Char> = Channel(Channel.RENDEZVOUS)
    private val stdoutInflection: SimpleInlineMulticaster<Char> = SimpleInlineMulticaster("$shortName-stdout")
    private val stderrInflection: SimpleInlineMulticaster<Char> = SimpleInlineMulticaster("$shortName-stderr")

    override val standardInput: Channel<Char> = Channel(Channel.RENDEZVOUS)
    override val standardOutput = stdoutInflection.openSubscription("chars").tail(config.standardOutputBufferCharCount)
    override val standardError = stderrInflection.openSubscription("chars").tail(config.standardErrorBufferCharCount)

    override val processID: Int by lazy {
        when(val state = state){
            State.Uninitialized, is State.Prestarted -> throw IllegalStateException("process not started")
            is State.Running -> state.pid
            is State.Completed -> state.pid
        }
    }

    internal fun prestart(){
        val uninitialized = state
        require(uninitialized is State.Uninitialized)

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

            LinkedList<String>().apply {
                launchChild {
                    stderrInflection.openSubscription("err-cache").lines().consumeEach { errorMessageLine ->
                        addLast(errorMessageLine)
                        if(size > config.linesForExceptionError){
                            removeFirst()
                        }
                    }
                }
            }
        }
        var stdoutAggregatorJob: Job? = null
        var stderrAggregatorJob: Job? = null
        val exitCodeAggregatorJob: Job

        run prestartAggregateChannel@ {
            if(config.aggregateOutputBufferLineCount > 0) {
                stdoutAggregatorJob = launchChild(Dispatchers.Unconfined) {
                    for (message in stdoutInflection.openSubscription("aggregator").lines()) {
                        aggregateChannel.send(StandardOutputMessage(message))
                    }
                }
                stderrAggregatorJob = launchChild(Dispatchers.Unconfined) {
                    for (message in stderrInflection.openSubscription("aggregator").lines()) {
                        aggregateChannel.send(StandardErrorMessage(message))
                    }
                }
            }
            exitCodeAggregatorJob = launchChild(Dispatchers.Unconfined){
                val result = exitCodeInflection.await()
                // the concurrent nature here means we could get std-out messages after an exit code,
                // which is just strange. So We'll sync on the output streams, making sure they come first.
                stdoutAggregatorJob?.join()
                stderrAggregatorJob?.join()
                aggregateChannel.send(ExitCode(result))
            }
        }
        val procBuilder = run prestartJvmProc@ {
            java.lang.ProcessBuilder(config.command).apply {

                if (environment() !== config.environment) environment().apply {
                    clear()
                    putAll(config.environment)
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

        if(state != uninitialized) throw ConcurrentModificationException()
        state = State.Prestarted(
                procBuilder,
                recentErrorOutput,
                stdoutAggregatorJob = stdoutAggregatorJob,
                stderrAggregatorJob = stderrAggregatorJob,
                exitCodeAggregatorJob = exitCodeAggregatorJob
        )
    }

    internal fun kickoff() {
        // remember:`jvmProcess.start()` is NOT safe in a cas loop!

        val prestarted = state
        require(prestarted is State.Prestarted)

        val process = try { prestarted.jvmProcessBuilder.start() }
        catch(ex: IOException){ throw InvalidExecConfigurationException(ex.message ?: "", config, ex) }

        val pid = pidGen.findPID(process)

        if(state != prestarted) throw ConcurrentModificationException()
        state = State.Running(prestarted, process, pid)
    }

    internal suspend fun waitFor(): Int = try {
        val initialState = state
        val prestarted = when(initialState){
            is State.Prestarted -> {
                kickoff()
                initialState
            }
            is State.Running -> {
                initialState.prestarted
            }
            else -> throw IllegalStateException()
        }

        val running = state
        require(running is State.Running)

        val listeners = listenerFactory.create(running.process, processID, config)

        stdoutInflection.sinkFrom(listeners.standardOutputChannel.value)
        stderrInflection.sinkFrom(listeners.standardErrorChannel.value)
        exitCodeInflection.sinkFrom(listeners.exitCodeDeferred.value)
        running.process.outputStream.toSendChannel(config).sinkFrom(stdinInflection)

        prestarted.run {
            stderrAggregatorJob?.join()
            stdoutAggregatorJob?.join()
            exitCodeAggregatorJob.join()
        }

        stderrInflection.asJob().join()
        stdoutInflection.asJob().join()
        val exitCode = exitCodeInflection.await()

        if(state != running) throw ConcurrentModificationException()
        state = State.Completed(running.pid, exitCode)

        val expectedExitCodes = config.expectedOutputCodes

        val result = when {
            expectedExitCodes == null -> exitCode
            exitCode in expectedExitCodes -> exitCode
            else -> throw makeExitCodeException(config, exitCode, prestarted.recentErrorOutput.toList())
        }

        result.also { trace { "exec $shortName(PID=${running.pid}) returned with code $result" }}
    }
    catch(ex: Exception){
        trace { "exec $shortName(PID=${Try { processID }}) threw an exception of '${ex.message}'" }
        throw ex
    }
    finally {
        aggregateChannel.close()
        inputLines.close()
//        standardError.cancel() --shouldnt need this, the close will propagate from the src to the tail
//        standardOutput.cancel()
        standardInput.close()
    }

    override suspend fun kill() {
        // whats my strategy?
        // who am i suspending?
        // how can i atomically move to a cancelled state?

        //how about this:
        fun makeUnstartedReaper(running: State.Running) = GlobalScope.launch(start = CoroutineStart.LAZY){
            val killer = processControlFacade.create(running.process, running.pid).value
            killer.killForcefullyAsync(config.includeDescendantsInKill)
        }

        //kill:
        // we atomically update a state to 'Doomed', that state is checked before return in the exec body
        val newState = atomicState.updateAndGet(this){ when(it) {
            is State.Running -> it.copy(reaper = makeUnstartedReaper(it))
            is State.Completed -> it
            State.Uninitialized -> TODO() //we can go directly to completed here, but `exec` needs to be updated.
            is State.Prestarted -> TODO() //nothing we can do, no way to know if process.start() has gone off or not.
        }}

        if (newState is State.Running) {
            newState.reaper!!.let { reaper -> reaper.start(); reaper.join() }
        }

        join()

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
        trace { "completed/cancelled $this" }
    }

    override fun cancel0(): Boolean = cancel(null)

    override fun cancel(cause: Throwable?): Boolean {
        return super<AbstractCoroutine<Int>>.cancel(cause) // cancel the job
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

    // failure of an exec call is not something special.
    // If an exec call fails its failure mode is entirely handled by the standard API surface,
    // there is no need to propagate the failure to parent coroutines, instead it is easier, and smarter,
    // to simply throw an exception.
    // if you flip this value, then notice the new strange stack-trace in tests with runBlocking blocks:
    // groostav.kotlinx.exec.StandardIOTests.when running standard error chatty script with bad exit code should get the tail of that error output

    override val cancelsParent: Boolean get() = false

    private val _getCompleted: Method
    private val _await: Method
    private val _registerSelectClause1: Method

    init {
        // regarding subclassing this obnoxious internal methods:
        //
        // if @elizarov wont give us public visibility THEN WE STORM THE BRIDGES!!
        //
        // see if maybe you can find the mangled generated access method,
        // something like super.internal$getCompletedInternal, that prevents you from forking this.
        //
        // then, I think we can take this, you'll need to call `process.start()` somewhere.
        //
        // long term, we'll see where this API lands, but fundamentally it shouldnt bee to hard to
        // grab source code for DeferredCoroutine (kotlinx-coroutines-core-common-1.0.1-sources.jar!/Builders.common.kt:87),
        // regardless of its implementation.

        val bridgeMethods = this::class.java.methods
        _getCompleted = bridgeMethods.single { it.name == "getCompletedInternal\$kotlinx_coroutines_core" }
        _await = bridgeMethods.single { it.name == "awaitInternal\$kotlinx_coroutines_core" }
        _registerSelectClause1 = bridgeMethods.single { it.name == "registerSelectClause1Internal\$kotlinx_coroutines_core" }
    }

    override fun getCompleted(): Int = _getCompleted<Int>()
    override suspend fun await(): Int = suspendCoroutine<Int> { _await<Any>(it) }
    override val onAwait: SelectClause1<Int> get() = this
    override fun <R> registerSelectClause1(select: SelectInstance<R>, block: suspend (Int) -> R): Unit = _registerSelectClause1<Unit>(select, block)

    private inline operator fun <reified T> Method.invoke(vararg args: Any): T {
        val result = try {
            this.invoke(this@ExecCoroutine, *args)
        }
        catch(ex: InvocationTargetException){
            throw ex.cause ?: ex
        }
        return if(result is T) result else throw ClassCastException("$result cannot be cast to ${T::class} as needed for the return type of $name")
    }

    private fun CoroutineScope.launchChild(
            context: CoroutineContext = EmptyCoroutineContext + Dispatchers.Unconfined,
            start: CoroutineStart = CoroutineStart.DEFAULT,
            block: suspend CoroutineScope.() -> Unit
    ) = launch(context, start) {
        val id = jobId.getAndIncrement()
        trace { "started job-$id" }
        try {
            block()
            trace { "completed job-$id normally" }
        }
        catch(ex: Exception){
            trace { "completed job-$id exceptionally" }
            throw ex
        }
    }

    companion object Factory {
        private val jobId = AtomicInteger(1)

        fun makeName(config: ProcessBuilder) = CoroutineName("exec ${config.command.joinToString()}")

        operator fun invoke(
                config: ProcessBuilder,
                parentContext: CoroutineContext,
                pidGen: ProcessIDGenerator,
                listenerFactory: ProcessListenerProvider.Factory,
                processControlFactory: ProcessControlFacade.Factory
        ) = ExecCoroutine(
                config, parentContext, pidGen, listenerFactory, processControlFactory,
                aggregateChannel = Channel(config.aggregateOutputBufferLineCount.asQueueChannelCapacity()),
                inputLines = Channel(Channel.RENDEZVOUS)
        )

        private val atomicState = AtomicReferenceFieldUpdater.newUpdater(ExecCoroutine::class.java, State::class.java, "state")
    }
}