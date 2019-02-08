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

@InternalCoroutinesApi
internal class ExecCoroutine(
        private val config: ProcessConfiguration,
        parentContext: CoroutineContext,
        isActive: Boolean,
        private val pidGen: ProcessIDGenerator,
        private val listenerFactory: ProcessListenerProvider.Factory,
        private val processControlFacade: ProcessControlFacade.Factory,
        private val makeProcessBuilder: (List<String>) -> java.lang.ProcessBuilder,
        private val makeInputStreamActor: (Process) -> SendChannel<Char>,
        private val aggregateChannel: Channel<ProcessEvent>,
        private val inputLines: Channel<String>
):
        AbstractCoroutine<Int>(parentContext + makeName(config), isActive),
        RunningProcess,
        SelectClause1<Int>,
        ReceiveChannel<ProcessEvent> by aggregateChannel,
        SendChannel<String> by inputLines
{

    init {
        require( ! aggregateChannel.isFull) { "aggregate channel is full at start, looks like a rendezvous channel?" }
    }

    sealed class State {
        object Uninitialized: State()
        class WarmingUp: State() {
            override fun equals(other: Any?) = this === other
            override fun hashCode() = System.identityHashCode(this)
        }

        data class Ready(
                val jvmProcessBuilder: java.lang.ProcessBuilder = NullProcBuilder,
                val recentErrorOutput: LinkedList<String> = LinkedList(),
                val stdoutAggregatorJob: Job = DONE_JOB,
                val stderrAggregatorJob: Job = DONE_JOB,
                val exitCodeAggregatorJob: Job = DONE_JOB
        ): State(){
            override fun toString() = "Ready"
        }
        data class Starting(val jobs: List<() -> Unit> = emptyList()): State()
        data class Running(
                val prev: Ready,
                val process: Process,
                val pid: Int,
                val obtrudingExitCode: Int? = null,
                val interrupt: SourcedTermination? = null,
                val reaper: SourcedTermination? = null,
                val stderrEOF: Boolean = false,
                val stdoutEOF: Boolean = false
        ): State(){

            internal class SourcedTermination(val source: CancellationException, val job: Job)

            override fun toString(): String {
                return "Running(pid=$pid, obtrudingExitCode=$obtrudingExitCode, interrupt=$interrupt, reaper=$reaper, stderrEOF=$stderrEOF, stdoutEOF=$stdoutEOF)"
            }
        }
        data class Completed(
                val pid: Int,
                val exitCode: Int,
                val interrupt: CancellationException?,
                val reaper: CancellationException?,
                val stderrEOF: Boolean,
                val stdoutEOF: Boolean
        ): State()

        data class Euthanized(val first: Boolean, val source: CancellationException): State()
    }

    private val shortName = config.debugName ?: makeNameString(config, targetLength = 20)

    // we CAS for the states:
    // - Uninitialized to WarmingUp,
    // - Running(*) to Running(reaper)
    // - Running to Completed
    // the other states (WarmingUp, Ready, Running) are not CAS'd but instead use a best-effort CME.

    internal @Volatile var state: State = State.Uninitialized
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
        State.Uninitialized, is State.Ready, is State.WarmingUp, is State.Starting -> throw IllegalStateException("process not started")
        is State.Running -> state.pid
        is State.Completed -> state.pid
        is State.Euthanized -> throw IllegalStateException("process not started")
    }

    internal fun prestart(): Boolean {

        val myChange = State.WarmingUp()
        val warmingUp = atomicState.updateAndGet(this) { when(it){
            State.Uninitialized -> myChange
            else -> it
        }}

        if(warmingUp !is State.WarmingUp) {
            trace { "state=$warmingUp before prestart: $this" }
            return false
        }
        if(warmingUp != myChange) {
            trace { "WARN: contention in prestart!?"}
            return false
        }

        run prestartStdin@{
            val stdinMutex = Mutex()

            inputLines.sinkTo(stdinInflection.flatMap<Char, String> { (it + config.inputFlushMarker).asIterable() }.lockedBy(stdinMutex))
            standardInput.sinkTo(stdinInflection.lockedBy(stdinMutex))
        }
        run prestartStdout@ {
            stdoutInflection.openSubscription().tail(config.standardOutputBufferCharCount).sinkTo(standardOutput)
        }

        var readyness = State.Ready(NullProcBuilder, LinkedList(), DONE_JOB, DONE_JOB, DONE_JOB)

        run prestartStderr@ {
            stderrInflection.openSubscription().tail(config.standardErrorBufferCharCount).sinkTo(standardError)

            val errorLines = stderrInflection.openSubscription("err-cache").lines()

            launchChild("errorCacheJob") {
                readyness.recentErrorOutput.apply {
                    errorLines.consumeEach { errorMessageLine ->
                        addLast(errorMessageLine)
                        if (size > config.linesForExceptionError) {
                            removeFirst()
                        }
                    }
                }
            }
        }

        if(config.aggregateOutputBufferLineCount > 0) {
            val stdoutForAggregator = stdoutInflection.openSubscription("aggregator").lines()
            readyness = readyness.copy(stdoutAggregatorJob = launchChild("stdoutAggregatorJob") {
                for (message in stdoutForAggregator) {
                    aggregateChannel.pushForward(StandardOutputMessage(message))
                }
            })
            val stderrForAggregator = stderrInflection.openSubscription("aggregator").lines()
            readyness = readyness.copy(stderrAggregatorJob = launchChild("stderrAggregatorJob") {
                for (message in stderrForAggregator) {
                    aggregateChannel.pushForward(StandardErrorMessage(message))
                }
            })
        }

        readyness = readyness.copy(exitCodeAggregatorJob = launchChild("exitCodeAggregatorJob") {
            val result = exitCodeInflection.await()

            // the concurrent nature here means we could get std-out messages after an exit code,
            // which is just strange. So We'll sync on the output streams, making sure they come first.
            readyness.stdoutAggregatorJob.join()
            readyness.stderrAggregatorJob.join()

            if (config.exitCodeInResultAggregateChannel) {
                aggregateChannel.pushForward(ExitCode(result))
            }
            else {} //even if we dont aggregate the output, we sill need this job as a sync point.
        })

        readyness = readyness.copy(jvmProcessBuilder = makeProcessBuilder(config.command).apply {

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
        })

        atomicState.updateAndGet(this){ when(it){
            warmingUp -> readyness
            else -> throw makeCME(warmingUp)
        }}

        return true
    }

    internal fun kickoff(): Boolean {

        val myChange = State.Starting()

        val (readyState, starting) = atomicState.getAndUpdateAndGet(this) { prev -> when(prev){
            is State.Ready -> myChange
            else -> prev
        }}

        if(starting !is State.Starting) {
            trace { "state=$starting before kickoff: $this" }
            return false
        }
        if(starting != myChange) {
            trace { "WARN: contention in kickoff!?"}
            return false
        }
        require(readyState is State.Ready)

        val process = try { readyState.jvmProcessBuilder.start() }
        catch(ex: IOException){ throw IllegalArgumentException(ex.message ?: "", ex) }

        val pid = pidGen.findPID(process)

        val listeners = listenerFactory.create(process, pid, config)

        makeInputStreamActor(process).sinkFrom(stdinInflection)

        stdoutInflection.sinkFrom(listeners.standardOutputChannel.value.thenOnCompletion {
            atomicState.updateAndGet(this){ when(it) {
                is State.Completed -> it.copy(stdoutEOF = true)
                is State.Running -> it.copy(stdoutEOF = true)
                else -> TODO()
            }}
        })
        stderrInflection.sinkFrom(listeners.standardErrorChannel.value.thenOnCompletion {
            atomicState.updateAndGet(this){ when(it) {
                is State.Completed -> it.copy(stderrEOF = true)
                is State.Running -> it.copy(stderrEOF = true)
                else -> TODO()
            }}
        })
        exitCodeInflection.sinkFrom(listeners.exitCodeDeferred.value.then { exitCode ->
            val finalState = atomicState.updateAndGet(this){ when(it) {
                is State.Running -> State.Completed(
                        it.pid,
                        it.obtrudingExitCode ?: exitCode,
                        it.interrupt?.source,
                        it.reaper?.source,
                        it.stderrEOF,
                        it.stdoutEOF
                )
                else -> TODO()
            }} as State.Completed

            finalState.exitCode
        })

        val suiters = atomicState.getAndUpdate(this){ when(it){
            is State.Starting -> State.Running(readyState, process, pid)
            else -> throw makeCME(starting)
        }}

        if(suiters is State.Starting){
            if(suiters.jobs.any()){
                trace { "kickoff found ${suiters.jobs.size} deferred jobs on $suiters" }
            }
            suiters.jobs.forEach { it.invoke() }
        }

        return true
    }

    internal suspend fun waitFor(): Int {

        val initialState = state
        val initialized = when(initialState){
            is State.Uninitialized, is State.WarmingUp, is State.Ready, is State.Starting -> TODO("state=$initialState")
            is State.Running -> initialState.prev
            is State.Completed -> return initialState.exitCode
            is State.Euthanized -> throw ProcessKilledException(-1, config.source, initialState.source)
        }

        return try {
            val running = state
            require(running is State.Running)

            initialized.run {
                stderrAggregatorJob.join()
                stdoutAggregatorJob.join()
                exitCodeAggregatorJob.join()
            }

            stderrInflection.asJob().join()
            stdoutInflection.asJob().join()
            val exitCode = exitCodeInflection.await()

            val state = state as? State.Completed ?: throw makeCME<State.Completed>()

            val expectedExitCodes = config.expectedOutputCodes

            val result = when {
                state.interrupt != null -> throw ProcessInterruptedException(exitCode, config.source, state.interrupt)
                state.reaper != null -> throw ProcessKilledException(exitCode, config.source, state.reaper)
                expectedExitCodes == null -> exitCode
                exitCode in expectedExitCodes -> exitCode
                else -> throw makeExitCodeException(config, exitCode, initialized.recentErrorOutput.toList())
            }

            result.also { trace { "exec '$shortName'(PID=${running.pid}) returned with code $result" } }
        }
        catch(ex: Exception){
            trace { "exec '$shortName'(PID=${Try { processID }}) threw an exception of '$ex'" }
            throw ex
        }
        finally {
            teardown()
        }
    }

    private fun teardown() {
        aggregateChannel.close()
        inputLines.close()
        // standardError.cancel() --shouldnt need this, the close will propagate from the src to the tail
        // standardOutput.cancel()
        standardInput.close()
    }

    fun makeUnstartedInterruptOrReaper(running: State.Running, gentle: Boolean) = GlobalScope.launch(start = CoroutineStart.LAZY){
        val killer = processControlFacade.create(config, running.process, running.pid).value
        when(gentle){
            true -> killer.tryKillGracefullyAsync(config.includeDescendantsInKill)
            false -> killer.killForcefullyAsync(config.includeDescendantsInKill)
        }
        //TODO: from what i can tell theres nothing meaningful to synchronize on here.
        // - waiting until the interrupt/kill is dispatched doesnt provide anything useful
        // - waiting until the process is dead is redundant --you can just use exitCode.await() for that.
        // so why return a job at all? why not just return unit? _it gives me the willies_
    }

    private fun tryKillAsync(source: CancellationException, obtrudeExitCode: Int?, gentle: Boolean = false): Unit {

        val newState = atomicState.updateAndGet(this){ when(it) {
            State.Uninitialized -> State.Euthanized(first = true, source = source)
            is State.WarmingUp -> State.Euthanized(first = true, source = source)
            is State.Ready -> State.Euthanized(first = true, source = source)
            is State.Starting -> {
                it.copy(jobs = it.jobs + { tryKillAsync(source, obtrudeExitCode, gentle) })
            }
            is State.Running -> {
                val interruptOrReaper = makeUnstartedInterruptOrReaper(it, gentle)
                val sourcedInterruptOrReaper = State.Running.SourcedTermination(source, interruptOrReaper)

                if (gentle) it.copy(interrupt = sourcedInterruptOrReaper, obtrudingExitCode = obtrudeExitCode)
                        else it.copy(reaper = sourcedInterruptOrReaper, obtrudingExitCode = obtrudeExitCode)
            }
            is State.Completed -> it
            is State.Euthanized -> it.copy(first = false)
        }}

        val reaperOrInterrupt = when(newState) {
            is State.Uninitialized, is State.Ready, is State.WarmingUp -> TODO("state=$newState")
            is State.Starting -> {
                trace {
                    "attempted to kill/interrupt $shortName, " +
                            "but it hasn't started yet, so the job was deferred to kickoff()"
                }
                null
            }
            is State.Running -> when {
                gentle -> newState.interrupt!!
                else -> newState.reaper!!
            }
            is State.Completed -> {
                trace { "attempted to kill/interrupt '$shortName' but its already completed." }
                null
            }
            is State.Euthanized -> {
                if(newState.first) {
                    cancel()
                    teardown()
                    trace { "killed unstarted process" }
                }
                else {
                    trace { "attempted to kill/interrupt '$shortName' but its euthanized" }
                }
                null
            }
        }

        if(reaperOrInterrupt != null) reaperOrInterrupt.job.start()

        return
    }

    override fun onStart() {
        if( ! prestart()) return
        if( ! kickoff()) return
        ::waitFor.createCoroutine(this).resume(Unit)
    }

    override suspend fun kill(obtrudeExitCode: Int?) {

        val killSource = CancellationException()

        val killedNormally = if(config.gracefulTimeoutMillis > 0){
            val killed = withTimeoutOrNull(config.gracefulTimeoutMillis) {
                tryKillAsync(gentle = true, obtrudeExitCode = obtrudeExitCode, source = killSource)
                join()
            } != null

            killed.also { trace { "interrupted '$shortName' gracefully: $killed" } }
        }
        else false

        if ( ! killedNormally) {
            tryKillAsync(gentle = false, obtrudeExitCode = obtrudeExitCode, source = killSource)
            join()
        }

    }

    override fun cancel(): Unit {
        cancel(null)
    }

    //MISNOMER: this is not on cancellation so much as its onCompletionOrCancellation!
    override fun onCancellation(cause: Throwable?) {
        val description = when(cause){
            null -> "completed normally"
            is CancellationException -> {
                tryKillAsync(cause, obtrudeExitCode = null)
                "cancelled"
            }
            is InvalidExitCodeException -> {
                "completed with bad exit code"
            }
            else -> {
                cause.printStackTrace()
                "unknown failure"
            }
        }
        trace { "onCancellation for $shortName: '$description'" }
    }

    override fun cancel0(): Boolean = cancel(null)

    override fun cancel(cause: Throwable?): Boolean {
        return super<AbstractCoroutine<Int>>.cancel(cause) // cancel the job
        //see [onCancellation]
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
    private inline fun <reified T: State> makeCME() = ConcurrentModificationException(
            "expected 'state is ${T::class.simpleName}' but was actually state=$state"
    )

    companion object Factory {

        operator fun invoke(
                config: ProcessConfiguration,
                parentContext: CoroutineContext,
                start: CoroutineStart,
                pidGen: ProcessIDGenerator,
                listenerFactory: ProcessListenerProvider.Factory,
                processControlFactory: ProcessControlFacade.Factory
        ) = ExecCoroutine(
                config,
                parentContext, start != CoroutineStart.LAZY,
                pidGen, listenerFactory, processControlFactory, ::ProcessBuilder, { it.outputStream.toSendChannel(config) },
                aggregateChannel = Channel(config.aggregateOutputBufferLineCount.asQueueChannelCapacity()),
                inputLines = Channel(Channel.RENDEZVOUS)
        )

        @JvmStatic
        private val atomicState = AtomicReferenceFieldUpdater.newUpdater(
                ExecCoroutine::class.java,
                State::class.java,
                "state"
        )
    }

    private fun loopOnState(block: (State) -> Unit){
        while(true){
            block(state)
        }
    }
}

private val jobId = AtomicInteger(1)

private fun makeName(config: ProcessConfiguration): CoroutineName = CoroutineName(config.debugName ?: "exec ${makeNameString(config, 50)}")

private fun makeNameString(config: ProcessConfiguration, targetLength: Int) = buildString {
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

private val NullProcBuilder = java.lang.ProcessBuilder()

private inline fun <T, V> AtomicReferenceFieldUpdater<T, V>.getAndUpdateAndGet(obj: T, updater: (V) -> V): Pair<V, V>{
    var prev: V
    var next: V
    do {
        prev = get(obj)
        next = updater(prev)
    } while (!compareAndSet(obj, prev, next))

    return prev to next
}