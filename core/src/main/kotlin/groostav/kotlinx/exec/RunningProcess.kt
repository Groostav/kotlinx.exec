package groostav.kotlinx.exec

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.selects.SelectClause1
import kotlinx.coroutines.selects.SelectClause2
import kotlinx.coroutines.selects.SelectInstance
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.lang.IllegalStateException
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.*
import java.lang.ProcessBuilder as JProcBuilder

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
interface RunningProcess: SendChannel<String>, ReceiveChannel<ProcessEvent> {

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
    val exitCode: Deferred<Int>

    val processID: Int

    /**
     * kills the process
     *
     * This method attempts to kill the process gracefully with an interrupt signal (SIG_INT)
     * in accordance with the settings in [ProcessBuilder.gracefulTimeousMillis], then, if that fails,
     * uses a more aggressive kill signal (SIG_KILL) to end the process, suspending until the process
     * is terminated.
     *
     * Once this method returns, all outputs will be closed.
     */
    suspend fun kill(): Unit

    /**
     * joins on this process
     *
     * suspends until the child process exits normally or is killed via a [kill] command.
     */
    suspend fun join(): Unit

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
    override fun cancel()

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

internal class Channels(val config: ProcessBuilder, val listenerFactory: ProcessListenerProvider.Factory) {

    val exitCode = CompletableDeferred<Int>()

    val stdoutLines = SimpleInlineMulticaster<String>("stdout-lines")
    val stderrLines = SimpleInlineMulticaster<String>("stderr-lines")

    val stdout: SimpleInlineMulticaster<Char> = SimpleInlineMulticaster<Char>("stdout")
    val stderr: SimpleInlineMulticaster<Char> = SimpleInlineMulticaster<Char>("stderr")

    val stdin = CompletableDeferred<FlushableSendChannel<Char>>()

    // region input
    private val inputLineLock = Mutex()

    val standardInputChars: SendChannel<Char> = GlobalScope.actor<Char> {

        val stdin = stdin.await() //TODO: what if we never connect? is abandoning this ok?
        consumeEach {
            inputLineLock.withLock {
                stdin.send(it)
            }
        }
    }
    val standardInputLines: SendChannel<String> = GlobalScope.actor<String> {

        val stdin = stdin.await()
        val newlineChar = config.inputFlushMarker

        consumeEach { nextLine ->
            inputLineLock.withLock {
                nextLine.forEach { stdin.send(it) }
                newlineChar?.also { stdin.send(it) }
                Unit
            }
        }
    }

    fun connect(pid: Int, process: Process){

        val listenerProvider = listenerFactory.create(process, pid, config)

        stdoutLines.syndicateAsync(stdout.openSubscription().lines(config.delimiters))
        stderrLines.syndicateAsync(stderr.openSubscription().lines(config.delimiters))

        stdout.syndicateAsync(listenerProvider.standardOutputChannel.value)
        stderr.syndicateAsync(listenerProvider.standardOutputChannel.value)

        GlobalScope.launch {
            exitCode.complete(listenerProvider.exitCodeDeferred.value.await())
        }

        stdin.complete(process.outputStream.toSendChannel(config, pid))
    }
}

internal data class Proxy(
        val config: ProcessBuilder,
        val processID: Int,
        val process: Process,
        val processControlWrapper: ProcessControlFacade
)

internal sealed class State(val ordinal: Int){
    abstract val channels: Channels
    abstract val waiters: List<Continuation<Unit>>
}
internal sealed class PeeredState(ordinal: Int): State(ordinal) {
    abstract val proxy: Proxy
}

internal data class Unstarted(
        val jBuilder: JProcBuilder,
        override val channels: Channels,
        override val waiters: List<Continuation<Unit>> = emptyList()
): State(ordinal) {
    companion object { val ordinal: Int = 0 }
}
//required because j.l.Process.start() cannot be atomic
internal data class Starting(
        val jBuilder: java.lang.ProcessBuilder,
        override val channels: Channels,
        override val waiters: List<Continuation<Unit>> = emptyList()
        // this thing represents a
): State(1)

internal data class Running(
        val process: Process,
        override val proxy: Proxy,
        override val channels: Channels,
        override val waiters: List<Continuation<Unit>> = emptyList()
): PeeredState(ordinal){
    companion object { val ordinal: Int = 2}
}

internal data class Doomed(
        val stdoutEOF: Boolean = false,
        val stderrEOF: Boolean = false,
        val hasKillOrder: Boolean = false,
        val exitCode: Int? = null,
        override val proxy: Proxy,
        override val channels: Channels,
        override val waiters: List<Continuation<Unit>> = emptyList()
): PeeredState(ordinal){
    companion object { val ordinal: Int = 3 }
}

internal data class Finished(
        val exitCode: Int,
        override val proxy: Proxy,
        override val channels: Channels,
        override val waiters: List<Continuation<Unit>> = emptyList()
): PeeredState(ordinal){
    companion object { val ordinal: Int = 4 }
}

internal operator fun State.plus(waiter: Continuation<Unit>): State = when(this){
    // well heres some fun kotlin boilerplate.
    // why do I need this?
    // unfortunately a `sealed data class` doesn't exist.
    // we could handle this a bit more polymorphically but then I'm just putting each
    // of the below boiler-plate into the subclasses
    // and replacing a `when(x) is A` with a vtable lookup.
    // yeah I think.. `sealed data class X`, which requires that all subclasses of X are data classes,
    // but **does not** state rules about `component1()` and `component2()` would do the trick.
    // frankly, I dont use that component1(), component2() stuff much anyways.
    is Running -> copy(waiters = waiters + waiter)
    is Doomed -> copy(waiters = waiters + waiter)
    is Finished -> copy(waiters = waiters + waiter)
    is Unstarted -> copy(waiters = waiters + waiter)
    is Starting -> copy(waiters = waiters + waiter)
}

internal fun Doomed.copyOrFinish(
        encounteredStdoutEOF: Boolean = stdoutEOF,
        encounteredStderrEOF: Boolean = stderrEOF,
        newHasKillOrder: Boolean = hasKillOrder,
        encounteredExitCode: Int? = exitCode
): State
        = if(encounteredStdoutEOF && encounteredStderrEOF && encounteredExitCode != null) Finished(encounteredExitCode, proxy, channels)
          else Doomed(encounteredStdoutEOF, encounteredStderrEOF, newHasKillOrder, encounteredExitCode, proxy, channels)


private inline class AtomicState(private val ref: AtomicReference<State>){
    constructor(state: State): this(AtomicReference(state))

    fun getAndUpdate(update: (State) -> State): State {
        val (initial: State, _) = getAndUpdateAndGet(update)
        return initial
    }

    fun updateAndGet(update: (State) -> State): State {
        val (_, updated: State) = getAndUpdateAndGet(update)
        return updated
    }

    fun get(): State = ref.get()
    fun set(value: State): Unit = ref.set(value)

    // this is fundamentally a bad idea because it would require synchronization
    // on the read that assumes your not in whatever state you want to select for!
    // in other words, if you write atomicState.onChange { (it as? Finished)... } '
    // how can you be sure its not already finished?

//    @InternalCoroutinesApi
//    val onChange: SelectClause1<State> get() = object: SelectClause1<State> {
//        @InternalCoroutinesApi
//        override fun <R> registerSelectClause1(select: SelectInstance<R>, block: suspend (State) -> R) {
//
//            //so there's the issue of buffering here, do we notify of previous state changes?
//
//            val wrapper: suspend () -> R = { block(ref.get()) }
//
//            val continuation = wrapper.createCoroutine(select.completion)
//
//            getAndUpdateAndGet { it + continuation }
//
////            block.startCoroutine(asdf, select.completion)
//        }
//    }

    private fun getAndUpdateAndGet(update: (State) -> State): Pair<State, State> {
        var initial: State
        var updated: State
        do {
            initial = ref.get()
            updated = update(initial)
        } while (!ref.compareAndSet(initial, updated))

        if (!updated::class.isInstance(initial)) {
            initial.waiters.forEach { it.resume(Unit) }
        }
        return Pair(initial, updated)
    }
}

@InternalCoroutinesApi
internal class RunningProcessImpl(
        val config: ProcessBuilder,
        _state: State,
        val parentContext: CoroutineContext
): RunningProcess {

    // ok, so what does extending AbstractCoroutine get me?
    // cancellation behaviour...?

    private val state = AtomicState(_state)
    private val finished = CompletableDeferred<Int>()

    fun kickoff() {

        trace { "in onStart()" }

        val oldState = state.getAndUpdate {
            require(it is Unstarted) { "expected Unstarted but was $it" }
            Starting(it.jBuilder, it.channels)
        } as Unstarted

        val jvmRunningProcess = try { oldState.jBuilder.start() }
        catch(ex: IOException){ throw InvalidExecConfigurationException(ex.message!!, config, ex.takeIf { TRACE }) }

        val pidProvider = makePIDGenerator(jvmRunningProcess)
//            trace { "selected pidProvider=$pidProvider" }

        val processID = pidProvider.pid.value
//            trace { "pid=$processID" }

        val processControllerFacade: ProcessControlFacade = makeCompositeFacade(jvmRunningProcess, processID)
//            trace { "selected facade=$processControllerFacade" }
//
//            val listenerProvider = listenerProviderFactory.create(jvmRunningProcess, processID, config)
//            trace { "selected listenerProvider=$listenerProvider" }

        val channels = oldState.channels
        channels.connect(processID, jvmRunningProcess)

        val stdout = channels.stdout
        launchReaper("$stdout-reaper") {

            waitForState(Running.ordinal)
            stdout.join()

            val x = 4;

            state.updateAndGet { when(it){
                is Unstarted, is Starting -> TODO("state=$it")
                is Running -> Doomed(stdoutEOF = true, proxy = it.proxy, channels = it.channels)
                is Doomed -> it.copyOrFinish(encounteredStdoutEOF = true)
                is Finished -> throw IllegalStateException("obtrusion of stdout completion")
            }}
        }

        val stderr = channels.stderr
        launchReaper("$stderr-reaper") {

            waitForState(Running.ordinal)
            stderr.join()

            val x = 4;

            state.updateAndGet { when(it){
                is Unstarted, is Starting -> TODO("state=$it")
                is Running -> Doomed(stderrEOF = true, proxy = it.proxy, channels = it.channels)
                is Doomed -> it.copyOrFinish(encounteredStderrEOF = true)
                is Finished -> throw IllegalStateException("obtrusion of stderr completion")
            }}
        }

        val exitValue = channels.exitCode
        launchReaper("$processID-exitcode-reaper") {

            // TODO: ok so, consider cancellation vs kill vs aborted.
            // the java API can probably be reasonably relied upon to produce an exit value regardless of exceptions
            // but what about cancellation?
            waitForState(Running.ordinal)
            val result = exitValue.await()

            val x = 4;

            state.updateAndGet { when(it) {
                is Unstarted, is Starting -> TODO("state=$it")
                is Running -> Doomed(exitCode = result, proxy = it.proxy, channels = it.channels)
                is Doomed -> it.copyOrFinish(encounteredExitCode = result)
                is Finished -> throw IllegalStateException("obtrusion exit code $result for $processID")
            }}
        }

        val proxy = Proxy(
                config,
                processID,
                jvmRunningProcess,
                processControllerFacade
        )

        this.state.set(Running(jvmRunningProcess, proxy, channels))

        trace { "done onStart()" }
    }

    private fun launchReaper(name: String, block: suspend CoroutineScope.() -> State){
        // TODO I need to verify the parent-child relationship here,
        // and the impact on cancellation
        GlobalScope.launch(parentContext + CoroutineName(name)){
            val newState = block()
            teardownIfFinished(newState)
        }
    }

    private fun teardownIfFinished(newState: State) {
        if(newState !is Finished) { return }

        finished.complete(newState.exitCode);
    }

    private fun killOnceWithoutSync() {
        val oldState = state.getAndUpdate { oldState -> when(oldState){
            is Unstarted -> throw IllegalStateException() //TODO: cancelling an unstarted coroutine... AFAIK thats fine? Can we just hop to Finished?
            is Starting -> throw IllegalStateException() //TODO: I have to do better here, if you create unstarted, then asynchronously cancel it, we could end up here.
            is Running -> Doomed(hasKillOrder = true, proxy = oldState.proxy, channels = oldState.channels)
            is Doomed -> oldState.copy(hasKillOrder = true)
            is Finished -> oldState
        }}

        val needsToBeKilled = oldState is Running || (oldState is Doomed && !oldState.hasKillOrder)

        if(needsToBeKilled){
            val control = (oldState as? PeeredState)?.proxy?.processControlWrapper
                    ?: throw IllegalStateException("cannot cancel process in state=$state")

            // note: i'd like to synchronize on this, but the docs say
            // This function is invoked once when this coroutine is cancelled similarly to invokeOnCompletion
            // => Implementations of CompletionHandler must be fast and lock-free
            // so I cant block until the resource comes back.
            control.killForcefullyAsync(config.includeDescendantsInKill)
        }
    }

    //TODO: this violates the "don't throw" convention of getters.
    override val processID: Int get() = when(val state = state.get()){
        is PeeredState -> state.proxy.processID
        is Unstarted -> throw IllegalStateException("Process not yet started")
        is Starting -> throw IllegalStateException("Process not yet started")
    }

    override val standardOutput: ReceiveChannel<Char> = state.get().channels.stdout.openSubscription().tail(config.standardOutputBufferCharCount)
    override val standardError: ReceiveChannel<Char> = state.get().channels.stderr.openSubscription().tail(config.standardErrorBufferCharCount)

    // region input

    override val standardInput: SendChannel<Char> = state.get().channels.standardInputChars

    // endregion input

    //region join, kill

//    private val killed = AtomicBoolean(false)

//    private val _exitCode = config.scope.async(Unconfined + CoroutineName("process(PID=$processID)._exitcode")) {
//
//        val result = processListenerProvider.exitCodeDeferred.value.await()
//
//        trace { "$processID exited with $result, closing streams" }
//
//        _standardOutputSource.join()
//        _standardErrorSource.join()
//        standardInput.close()
//        inputLines.close()
//
//        resumeWith(Result.success(Unit))
//        result
//    }

    override val exitCode: Deferred<Int> = GlobalScope.async {
        waitForState(Finished.ordinal)
        (state.get() as Finished).exitCode
    }

    //hmm, can get better safety with
    // inline suspend fun <refied T: State> waitForState(): T
    // but that would require converting static type info into ordinal values,
    // and it would require states to keep a kind of history so that if somebody says
    // Process{state=finished}.waitForState<Doomed>()
    // we can still get them that value.
    private suspend fun waitForState(targetStateOrdinal: Int): Unit {
        while(state.get().ordinal < targetStateOrdinal) {
            suspendCoroutine<Unit> { cont ->
                val oldState = state.getAndUpdate { state ->
                    if (state.ordinal < targetStateOrdinal) state + cont else state
                }

                //already at that state, no need to suspend.
                if (oldState.ordinal >= targetStateOrdinal) {
                    cont.resume(Unit)
                }
            }

            val x = 4;
        }
    }
    @Suppress("FINAL_UPPER_BOUND")//yeah I know, im just looking to see how well this gets used...
    private inline suspend fun <reified T: Finished> waitForState(): T {
        waitForState(Finished.ordinal)
        return state.get() as Finished as T
    }


    override suspend fun join() {
        waitForState(Finished.ordinal)
    }

    //    private val errorHistory = config.scope.async<Queue<String>>(Unconfined + CoroutineName("process(PID=$processID).errorHistory")) {
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

    override suspend fun kill(): Unit {
        TODO()
//        killOnceWithoutSync()
//        join()
    }



    //endregion

    //region SendChannel

    private val inputLines: SendChannel<String> get() = TODO()
//            by lazy {
//        config.scope.actor<String>(Unconfined) {
//            val newlineString = System.lineSeparator()
//            consumeEach { nextLine ->
//                inputLineLock.withLock {
//                    nextLine.forEach { _standardInput.send(it) }
//                    newlineString.forEach { _standardInput.send(it) }
//                }
//            }
//            _standardInput.close()
//        }
//    }

    override fun cancel() = TODO()
    @ObsoleteCoroutinesApi override fun cancel(cause: Throwable?): Boolean = TODO()

    override val isClosedForSend: Boolean get() = inputLines.isClosedForSend
    override val isFull: Boolean get() = inputLines.isFull
    override val onSend: SelectClause2<String, SendChannel<String>> get() = inputLines.onSend
    override fun offer(element: String): Boolean = inputLines.offer(element)
    override suspend fun send(element: String) = inputLines.send(element)
    override fun close(cause: Throwable?): Boolean {
        TODO()
//        _standardInput.close(cause)
        return inputLines.close(cause)
    }
    override fun invokeOnClose(handler: (cause: Throwable?) -> Unit) = inputLines.invokeOnClose(handler)

    //endregion

    //region ReceiveChannel

    private val aggregateChannel: ReceiveChannel<ProcessEvent> = when(config.aggregateOutputBufferLineCount){
        0 -> {
            val name = "aggregate[NoBufferedOutput]"
            val actual = GlobalScope.produce<ProcessEvent>(CoroutineName("TODO"), capacity = 1){
                val code = waitForState<Finished>().exitCode
                send(ExitCode(code))
            }
            object: ReceiveChannel<ProcessEvent> by actual{
                override fun toString() = name
            }
        }
        else -> {
            val channels = state.get().channels
            val errorLines = channels.stderrLines.openSubscription()
            val outputLines = channels.stdoutLines.openSubscription()

            val name = "aggregate[out=$outputLines,err=$errorLines]"

            val actual = GlobalScope.produce<ProcessEvent>(CoroutineName("TODO")) {
                try {
                    var stderrWasNull = false
                    var stdoutWasNull = false

                    pumpingOutputs@ while (isActive && (!stderrWasNull || !stdoutWasNull) ) {

                        val next = select<ProcessEvent?> {
                            if (!stderrWasNull) errorLines.onReceiveOrNull { errorMessage ->
                                if(errorMessage == null){ stderrWasNull = true }
                                errorMessage?.let { StandardErrorMessage(it) }
                            }
                            if (!stdoutWasNull) outputLines.onReceiveOrNull { outputMessage ->
                                if(outputMessage == null){ stdoutWasNull = true }
                                outputMessage?.let { StandardOutputMessage(it) }
                            }
                        }

                        when (next) {
                            null -> continue@pumpingOutputs
                            else -> { send(next); continue@pumpingOutputs }
                        }
                    }

                    val result = finished.await()

                    send(ExitCode(result))
                }
                finally {
                    trace { "aggregate channel done" }
                }
            }

            val namedAggregate = object: ReceiveChannel<ProcessEvent> by actual {
                override fun toString(): String = name
            }

            namedAggregate.tail(config.aggregateOutputBufferLineCount + 1)
            // +1 for exitCode. If the configuration has statically known math
            // (eg 54 lines for `ls` of a directory with 54 items).
        }
    }

    override val isClosedForReceive: Boolean get() = aggregateChannel.isClosedForReceive
    override val isEmpty: Boolean get() = aggregateChannel.isEmpty
    override val onReceive: SelectClause1<ProcessEvent> get() = aggregateChannel.onReceive
    override val onReceiveOrNull: SelectClause1<ProcessEvent?> get() = aggregateChannel.onReceiveOrNull

    override fun iterator(): ChannelIterator<ProcessEvent> = aggregateChannel.iterator()
    override fun poll(): ProcessEvent? = aggregateChannel.poll()
    override suspend fun receive(): ProcessEvent = aggregateChannel.receive()
    override suspend fun receiveOrNull(): ProcessEvent? = aggregateChannel.receiveOrNull()

    @Suppress("INAPPLICABLE_JVM_NAME")
    @Deprecated(level = DeprecationLevel.HIDDEN, message = "Left here for binary compatibility")
    @JvmName("cancel") public override fun cancel0(): Boolean = cancel(null)

    //endregion
}
