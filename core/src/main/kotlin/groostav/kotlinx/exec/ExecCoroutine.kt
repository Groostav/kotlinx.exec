package groostav.kotlinx.exec

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.selects.SelectClause1
import kotlinx.coroutines.selects.SelectInstance
import kotlinx.coroutines.sync.Mutex
import java.io.IOException
import java.lang.ClassCastException
import java.lang.IllegalStateException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.createCoroutineUnintercepted
import kotlin.coroutines.intrinsics.intercepted

@InternalCoroutinesApi
internal class ExecCoroutine private constructor(
        private val config: ProcessBuilder,
        parentContext: CoroutineContext,
        isActive: Boolean,
        private val pidGen: ProcessIDGenerator,
        private val listenerFactory: ProcessListenerProvider.Factory,
        private val processControlFacade: ProcessControlFacade.Factory,
        private val aggregateChannel: Channel<ProcessEvent>,
        private val inputLines: Channel<String>
):
        AbstractCoroutine<Int>(parentContext + makeName(config), isActive),
        RunningProcess,
        SelectClause1<Int>,
        ReceiveChannel<ProcessEvent> by aggregateChannel,
        SendChannel<String> by inputLines
{

    sealed class State {
        object Uninitialized: State()
        class WarmingUp( //job is in STARTED state ==> because this dispatches child jobs.
                val jvmProcessBuilder: java.lang.ProcessBuilder,
                val recentErrorOutput: LinkedList<String>,
                val stdoutAggregatorJob: Job?,
                val stderrAggregatorJob: Job?,
                val exitCodeAggregatorJob: Job
        ): State(){
            override fun toString() = "WarmingUp"
        }
        data class Running(
                val prev: WarmingUp,
                val process: Process,
                val pid: Int,
                val interrupt: Job? = null,
                val reaper: Job? = null
        ): State(){
            override fun toString() = "Running(PID=$pid${if(reaper != null) ", hasReaper)" else ")"}"
            override fun equals(other: Any?) = super.equals(other)
            override fun hashCode(): Int = super.hashCode()
        }
        class Completed(val pid: Int, val exitCode: Int): State(){
            override fun toString() = "Completed(PID=$pid, exitCode=$exitCode)"
        }
        data class Euthanized(val first: Boolean): State()
    }

    private val shortName = config.debugName ?: makeNameString(config, targetLength = 20)

    // this thing is used both in a CAS loop for Running(no-reaper) to Running(reaper),
    // and from Running(*) to Completed, but is _not_ CAS'd from Initialized to Prestarted to Running,
    // there it is only used as a best-effort CME detection device.
    private @Volatile var state: State = State.Uninitialized
        set(value) { field = value.also { trace { "process '$shortName' moved to state $it" } } }

    private val exitCodeInflection = CompletableDeferred<Int>()
    private val stdinInflection: Channel<Char> = Channel(Channel.RENDEZVOUS)
    private val stdoutInflection: SimpleInlineMulticaster<Char> = SimpleInlineMulticaster("stdout[$shortName]")
    private val stderrInflection: SimpleInlineMulticaster<Char> = SimpleInlineMulticaster("stderr[$shortName]")

    override val standardInput: Channel<Char> = Channel(Channel.RENDEZVOUS)
    override val standardOutput: Channel<Char> = Channel(Channel.RENDEZVOUS)
    override val standardError: Channel<Char> = Channel(Channel.RENDEZVOUS)

    override val processID: Int get() = when(val state = state){
        // only way to get this exception AFAIK is to create ExecCoroutine(Start.LAZY).processID,
        // which seems unlikely.
        State.Uninitialized, is State.WarmingUp -> throw IllegalStateException("process not started")
        is State.Running -> state.pid
        is State.Completed -> state.pid
        is State.Euthanized -> throw IllegalStateException("process not started")
    }

    internal fun prestart(){
        // I've had effectivley 3 attempts at getting this method right.
        // 1. The first time I just used coroutine builders. Demultiplexing even the simple three channels
        //    without some kind of structure for what data is available when is difficult and lead to very messy brittle code.
        // 2. attempt to was a state machine with similar states to `State`,
        //    but it involved just as many coroutine builders and didnt obviously solve the problem of
        //    "how do I wait for a state?" (in retrospect, the answer is ConflatedChannel)
        // 3. this is my third implementation. Eagerly create some datastructures which can be both read and written.
        val uninitialized = state
        require(uninitialized is State.Uninitialized)

        run prestartStdin@{
            val stdinMutex = Mutex()

            inputLines.sinkTo(stdinInflection.flatMap<Char, String> { (it + config.inputFlushMarker).asIterable() }.lockedBy(stdinMutex))
            standardInput.sinkTo(stdinInflection.lockedBy(stdinMutex))

        }
        run prestartStdout@ {
            stdoutInflection.openSubscription().tail(config.standardOutputBufferCharCount).sinkTo(standardOutput)
        }
        val recentErrorOutput = run prestartStderr@ {
            stderrInflection.openSubscription().tail(config.standardErrorBufferCharCount).sinkTo(standardError)

            LinkedList<String>().apply {
                val errorLines = stderrInflection.openSubscription("err-cache").lines()
                launchChild("errorCacheJob") {
                    errorLines.consumeEach { errorMessageLine ->
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
                val stdoutForAggregator = stdoutInflection.openSubscription("aggregator").lines()
                stdoutAggregatorJob = launchChild("stdoutAggregatorJob") {
                    for (message in stdoutForAggregator) {
                        aggregateChannel.pushForward(StandardOutputMessage(message))
                    }
                }
                val stderrForAggregator = stderrInflection.openSubscription("aggregator").lines()
                stderrAggregatorJob = launchChild("stderrAggregatorJob") {
                    for (message in stderrForAggregator) {
                        aggregateChannel.pushForward(StandardErrorMessage(message))
                    }
                }
            }
            exitCodeAggregatorJob = launchChild("exitCodeAggregatorJob") {
                val result = exitCodeInflection.await()
                // the concurrent nature here means we could get std-out messages after an exit code,
                // which is just strange. So We'll sync on the output streams, making sure they come first.
                stdoutAggregatorJob?.join()
                stderrAggregatorJob?.join()
                if (config.exitCodeInResultAggregateChannel) {
                    aggregateChannel.pushForward(ExitCode(result))
                }
                else {} //even if we dont aggregate the output, we sill need this job as a sync point.
            }
        }
        val procBuilder = run prestartJvmProc@ {
            java.lang.ProcessBuilder(config.command).apply {

                if (environment() !== config.environment) environment().apply {
                    clear()
                    putAll(config.environment)
                }

                if(config.run { standardErrorBufferCharCount == 0 && aggregateOutputBufferLineCount == 0 }){
                    redirectError(java.lang.ProcessBuilder.Redirect.DISCARD)
                }
                if(config.run { standardOutputBufferCharCount == 0 && aggregateOutputBufferLineCount == 0}){
                    redirectOutput(java.lang.ProcessBuilder.Redirect.DISCARD)
                }

                directory(config.workingDirectory.toFile())
            }
        }

        state = if(state == uninitialized) State.WarmingUp(
                procBuilder,
                recentErrorOutput,
                stdoutAggregatorJob = stdoutAggregatorJob,
                stderrAggregatorJob = stderrAggregatorJob,
                exitCodeAggregatorJob = exitCodeAggregatorJob
        ) else throw makeCME(expected = uninitialized)
    }

    internal fun kickoff() {
        // remember:`jvmProcess.start()` is NOT safe in a cas loop!

        val warmingUp = state
        require(warmingUp is State.WarmingUp)

        val process = try { warmingUp.jvmProcessBuilder.start() }
        catch(ex: IOException){ throw InvalidExecConfigurationException(ex.message ?: "", config, ex) }

        val pid = pidGen.findPID(process)

        val running = State.Running(warmingUp, process, pid)
        state = if(state == warmingUp) running else throw makeCME(expected = warmingUp)

        val listeners = listenerFactory.create(running.process, processID, config)

        stdoutInflection.sinkFrom(listeners.standardOutputChannel.value)
        stderrInflection.sinkFrom(listeners.standardErrorChannel.value)
        exitCodeInflection.sinkFrom(listeners.exitCodeDeferred.value)
        running.process.outputStream.toSendChannel(config).sinkFrom(stdinInflection)
    }

    internal suspend fun waitFor(): Int = try {
        val initialState = state
        val initialized = when(initialState){
            is State.Uninitialized -> TODO()
            is State.WarmingUp -> {
                kickoff()
                initialState
            }
            is State.Running -> {
                initialState.prev
            }
            is State.Completed -> throw IllegalStateException("state=$initialState")
            is State.Euthanized -> null
        }


        val running = state
        require(running is State.Running)
        require(initialized != null)

        initialized.run {
            stderrAggregatorJob?.join()
            stdoutAggregatorJob?.join()
            exitCodeAggregatorJob.join()
        }

        stderrInflection.asJob().join()
        stdoutInflection.asJob().join()
        val exitCode = exitCodeInflection.await()

        val stateBeforeCompletion = atomicState.getAndUpdate(this){ when(it){
            running -> State.Completed(running.pid, exitCode)
            // somebody concurrently updated us to running, adding a reaper
            // either way we're done.
            is State.Running -> State.Completed(running.pid, exitCode)
            else -> throw makeCME(expected = running)
        }} as State.Running

        val expectedExitCodes = config.expectedOutputCodes

        val result = when {
            stateBeforeCompletion.reaper != null -> throw CancellationException()
            expectedExitCodes == null -> exitCode
            exitCode in expectedExitCodes -> exitCode
            else -> throw makeExitCodeException(config, exitCode, initialized.recentErrorOutput.toList())
        }

        result.also { trace { "exec '$shortName'(PID=${running.pid}) returned with code $result" } }
    }
    catch(ex: Exception){
        trace { "exec '$shortName'(PID=${Try { processID }}) threw an exception of '${ex.message}'" }
        throw ex
    }
    finally {
        teardown()
    }

    private fun teardown() {
        aggregateChannel.close()
        inputLines.close()
        // standardError.cancel() --shouldnt need this, the close will propagate from the src to the tail
        // standardOutput.cancel()
        standardInput.close()
    }

    private fun tryKillAsync(gentle: Boolean = false): Unit {

        if(config.notKillable) return

        fun makeUnstartedInterruptOrReaper(running: State.Running) = GlobalScope.launch(start = CoroutineStart.LAZY){
            val killer = processControlFacade.create(config, running.process, running.pid).value
            when(gentle){
                true -> killer.tryKillGracefullyAsync(config.includeDescendantsInKill)
                false -> killer.killForcefullyAsync(config.includeDescendantsInKill)
            }
        }

        // we atomically update a state to 'Doomed', that state is checked before return in the exec body
        val newState = atomicState.updateAndGet(this){ when(it) {
            State.Uninitialized -> State.Euthanized(first = true)
            is State.Running -> when {
                gentle -> it.copy(interrupt = makeUnstartedInterruptOrReaper(it))
                else -> it.copy(reaper = makeUnstartedInterruptOrReaper(it))
            }
            is State.Completed -> it
            is State.Euthanized -> it.copy(first = false)
            is State.WarmingUp -> TODO()
        }}

        val reaperOrInterrupt = when(newState) {
            is State.Uninitialized, is State.WarmingUp -> TODO()
            is State.Running -> when {
                gentle -> newState.interrupt!!
                else -> newState.reaper!!
            }
            is State.Completed -> {
                trace { "attempted to reap/interrupt '$shortName' but its already completed." }
                null
            }
            is State.Euthanized -> {
                if(newState.first) {
                    cancel()
                    teardown()
                    trace { "killed unstarted process" }
                }
                null
            }
        }

        if(reaperOrInterrupt != null) reaperOrInterrupt.start()

        return
    }

    override fun onStart() {
        prestart()
        kickoff()
        (ExecCoroutine::waitFor).createCoroutineUnintercepted(this, this).intercepted().resume(Unit)
    }

    override suspend fun kill() {
        // whats my strategy?
        // who am i suspending?
        // how can i atomically move to a cancelled state?

        //how about this:
        val killedNormally = if(config.gracefulTimeoutMillis > 0){
            val killed = withTimeoutOrNull(config.gracefulTimeoutMillis) {
                tryKillAsync(gentle = true)
                join()
            } != null

            killed.also { trace { "interrupted '$shortName' gracefully: $killed" } }
        }
        else false

        if ( ! killedNormally) {
            tryKillAsync(gentle = false)
            join()
        }

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

    //MISNOMER: this is not on cancellation so much as its onCompletionOrCancellation!
    override fun onCancellation(cause: Throwable?) {
        val description = when(cause){
            null -> "completed normally"
            is CancellationException -> {
                tryKillAsync()
                "cancelled"
            }
            is InvalidExitValueException -> {
                "completed with bad exit code"
            }
            else -> {
                cause.printStackTrace()
                "unknown failure"
            }
        }
        trace { "finished as '$description' $this" }
    }

    override fun cancel0(): Boolean = cancel(null)

    override fun cancel(cause: Throwable?): Boolean {
        return super<AbstractCoroutine<Int>>.cancel(cause) // cancel the job
    }

    override fun close() {
        close(null)
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
    // groostav.kotlinx.exec.StandardIOTests.when running standard error chatty script with bad exit code
    // should get the tail of that error output

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
        // grab source code for DeferredCoroutine
        // (kotlinx-coroutines-core-common-1.0.1-sources.jar!/Builders.common.kt:87),
        // regardless of its implementation.

        val bridgeMethods = this::class.java.methods
        _getCompleted = bridgeMethods.single { it.name == "getCompletedInternal\$kotlinx_coroutines_core" }
        _await = bridgeMethods.single { it.name == "awaitInternal\$kotlinx_coroutines_core" }
        _registerSelectClause1 = bridgeMethods.single { it.name == "registerSelectClause1Internal\$kotlinx_coroutines_core" }
    }

    override fun getCompleted(): Int = _getCompleted<Int>()
    override suspend fun await(): Int = suspendCoroutine<Int> { _await<Any>(it) }
    override val onAwait: SelectClause1<Int> get() = this
    override fun <R> registerSelectClause1(select: SelectInstance<R>, block: suspend (Int) -> R): Unit
            = _registerSelectClause1<Unit>(select, block)

    private inline operator fun <reified T> Method.invoke(vararg args: Any): T {
        val result = try {
            this.invoke(this@ExecCoroutine, *args)
        }
        catch(ex: InvocationTargetException){
            throw ex.cause ?: ex
        }
        return if(result is T) result
        else if (result == null && T::class == Unit::class) Unit as T //this seems to be a problem with kotlin mapping through reflection.
        else throw ClassCastException("$result cannot be cast to ${T::class} as needed for the return type of $name")
    }

    private fun CoroutineScope.launchChild(
            description: String? = null,
            context: CoroutineContext = EmptyCoroutineContext + Dispatchers.Unconfined,
            start: CoroutineStart = CoroutineStart.DEFAULT,
            block: suspend CoroutineScope.() -> Unit
    ) = launch(context, start) {
        val id = jobId.getAndIncrement()
        val jobName = "job-$id${description?.let { "=$it" } ?: ""}"
        trace { "started $jobName" }
        try {
            block()
            trace { "completed $jobName normally" }
        }
        catch(ex: Exception){
            trace { "completed $jobName exceptionally: $ex" }
            throw ex
        }
    }

    private fun makeCME(expected: State) = ConcurrentModificationException(
            "expected state=$expected but was actually $state"
    )

    companion object Factory {
        private val jobId = AtomicInteger(1)

        private fun makeName(config: ProcessBuilder): CoroutineName = CoroutineName(config.debugName ?: "exec ${makeNameString(config, 50)}")

        private fun makeNameString(config: ProcessBuilder, targetLength: Int) = buildString {
            val commandSeq = config.command.asSequence()
            append(commandSeq.first().replace("\\", "/").substringAfterLast("/").take(targetLength))

            val iterator = commandSeq.drop(1).iterator()
            if(length < targetLength){
                while(length < targetLength -1 && iterator.hasNext()){
                    append(" ")
                    append(iterator.next().take(targetLength - length))
                }
                append("...")
            }
        }

        operator fun invoke(
                config: ProcessBuilder,
                parentContext: CoroutineContext,
                start: CoroutineStart,
                pidGen: ProcessIDGenerator,
                listenerFactory: ProcessListenerProvider.Factory,
                processControlFactory: ProcessControlFacade.Factory
        ) = ExecCoroutine(
                config,
                parentContext, start != CoroutineStart.LAZY,
                pidGen, listenerFactory, processControlFactory,
                aggregateChannel = Channel(config.aggregateOutputBufferLineCount.asQueueChannelCapacity()),
                inputLines = Channel(Channel.RENDEZVOUS)
        )

        private val atomicState = AtomicReferenceFieldUpdater.newUpdater(ExecCoroutine::class.java, State::class.java, "state")
    }
}