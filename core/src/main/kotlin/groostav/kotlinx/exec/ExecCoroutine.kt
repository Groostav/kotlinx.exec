package groostav.kotlinx.exec

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.selects.SelectClause0
import kotlinx.coroutines.selects.SelectClause1
import kotlinx.coroutines.selects.SelectInstance
import java.io.IOException
import java.lang.IllegalArgumentException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.*

@OptIn(InternalCoroutinesApi::class)
class ExecCoroutine(
    context: CoroutineContext,
    val config: ProcessConfiguration,
    private val channel: Channel<ProcessEvent> = Channel<ProcessEvent>(
        capacity = config.aggregateOutputBufferLineCount,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
): AbstractCoroutine<Int?>(context, initParentJob = true, active = false), RunningProcess, Flow<ProcessEvent> {

    init {
        require(config.command.isNotEmpty())
    }

    private val recentErrors = CircularArray<String>(config.linesForExceptionError)

    internal lateinit var process: Process
    private lateinit var handle: ProcessHandle
    private lateinit var out: NamedTracingProcessReader
    private lateinit var err: NamedTracingProcessReader

    private val outMessages = OutputParser(config.delimiters)
    private val errMessages = OutputParser(config.delimiters)

    private var processState: AtomicReference<State> = AtomicReference(NotStarted)

    // TODO this should probably be on the state object...
    // the only place this field is updated is in onStart(), so its probably safe?
    // aside from that we use an immutable copy and throw it down-suspension, (eg collect(), await())
    private var tracing = run {
        val name = config.command.first().split("/", "\\").last().take(30)
        coroutineContext[CoroutineTracer]?.appendName(name) ?: CoroutineTracer(name)
    }

    override val processID: Long get() = handle.pid()

    // kotlinx coroutines guarantee: this will only be called once
    override fun onStart() = doTracing<Unit>("onStart") {

        // even though onstart() cant be called twice,
        // we could be cancelled while onStart-ing.
        val newState = processState.updateAndGet { state ->
            when (state) {
                is NotStarted -> Running()
                is Closed -> state
                is AllOutputsReported, is BeingKilled, is Running -> TODO("state=$state onStart()")
            }
        }

        when (newState) {
            is Running -> {
                this.tracing = this.tracing.mark("start")

                val processBuilder = ProcessBuilder(config.command)
                    .directory(config.workingDirectory.toFile())
                    .apply {
                        if (config.environment !== InheritedDefaultEnvironment) {
                            environment().apply { clear(); putAll(config.environment) }
                        }
                    }

                val procStartResult /* :Throwable|Process */ = try {
                    //TODO: is there a non platform specific way to find out if the command is on the PATH?
                    // if there was, and it was reliable, it would be nice to turn an IOException: cant find that command
                    // into an illegal argument exception
                    processBuilder.start()
                }
                catch(ex: IOException){
                    ex
                }

                when(procStartResult){
                    is Process -> {
                        // TODO: we have a race condition here;
                        // after process.start(), the sub process may
                        // already be flooding its standard-output.
                        // we really dont want to be doing any work after process.start() so that we can get to
                        // reading its output asap.
                        // thus, we should work to optimize the time-to-attachment here,
                        // but can we repro that in some kind of test environment?
                        process = procStartResult
                        handle = procStartResult.toHandle()

                        this.tracing = this.tracing.appendName(handle.pid().toString())

                        err = NamedTracingProcessReader.forStandardError(procStartResult, processID, config.encoding)
                        out = NamedTracingProcessReader.forStandardOutput(procStartResult, processID, config.encoding)

                        // inlined copy-pasta from CoroutineStart.start...
//                            val continuation = body.createCoroutineUnintercepted(this)
                        makeCoroutine(suspensionGappedFunction = ::readToProbableClose, context = coroutineContext, pipeOutputTo = this::resume)
                            .tryCancellablyInvoke(finally = this::resumeWithException)
                    }
                    is IOException -> {
                        processState.updateAndGet { state ->
                            when(state){
                                is NotStarted -> TODO("state=$newState onStart()")
                                is Running, is BeingKilled -> {
                                    // note: if we got killed before this,
                                    Closed(ProcessClosing.NotStarted(procStartResult))
                                }
                                is AllOutputsReported, is Closed -> TODO("state=$newState onStart()")
                            }
                        }
                        // failed to start the sub-process but we're already running
                        this.resume(KilledBeforeStartedExitCode)
                    }
                }
            }
            is Closed -> this.resume(newState.closedOutput.exitCode) // kill() before start()
            is AllOutputsReported, is BeingKilled, is NotStarted -> TODO("state=$newState onStart()")
        }
    }

    // this method can miss output on win32 because of attachToConsole,
    // if a child process attaches to the same console as we're reading from we
    // wont get a clear EOF signal on the standard output, so
    private suspend fun readToProbableClose(): Int = doTracing("body.invoke()") { tracing ->

        // ExecCoroutine does support cancellation
        // but it is implemented entirely via kill(),
        // regardless of whether or not we are cancelled this function
        // must read output until it is closed.
        // TODO: is it possible for 'kill -9' OR 'taskkill /force' to fail?
        withContext(NonCancellable) {

            var polledState = processState.get()
            //note: we may already be in the final state
            // if there is contention between coroutine.start() and kill()

            var backoff = BackoffWindowMillis.first

            while (polledState is RunningOrBeingKilled) {

                polledState.run { check(errOpen || errOpen || exitCodeOrNull != null) { "state not promoted: $this" } }

                if (isCancelled && polledState is Running) {
                    kill0(CancelledExitCode)
                }

                check(coroutineContext[ContinuationInterceptor] == Dispatchers.IO)

                when (val outputsOrNull = pollOutputsAndUpdateState(polledState)) {
                    null -> {
                        backoff = (backoff * 2)
                            .coerceIn(BackoffWindowMillis)
                            .coerceAtLeast(1)

                        delay(backoff)
                    }
                    else -> for (event in outputsOrNull) {
                        output(event)
                    }
                }

                polledState = processState.get()
            }

            when (val finishingState = polledState) {
                is AllOutputsReported -> finishingState.closedOutput.exitCode
                is Closed -> makeResult<Result<Int>>(tracing).getOrThrow()
                is BeingKilled, is NotStarted, is Running -> TODO("finishingState=$finishingState in body.finally")
            }
        }
    }

    // ack, this isnt a nice function
    // it changes the processState field and
    // and messages.addAndParse() is also not ref transparent
    // and making them so requires more object allocations... do we care?
    // TODO: make this more ref transparent --maybe add support for binary IO
    // the updateState portion of this is tricky since its a CAS...
    // plenty of sophisticated concepts for a CAS loop around...
    // can I make this more idiomatic?
    private suspend fun pollOutputsAndUpdateState(initialState: RunningOrBeingKilled): List<ProcessEvent>? {

        @Suppress("BlockingMethodInNonBlockingContext") val errReady = err.ready()
        @Suppress("BlockingMethodInNonBlockingContext") val outReady = out.ready()
        val exitCodeReady = !handle.isAlive

        val processEnded = initialState.exitCodeOrNull != null

        val outputsOrNull: List<ProcessEvent>? = when {
            // we read err preferentially, since when the exit code isnt null
            // we're going to slam this block
            initialState.errOpen && (errReady || processEnded) -> {

                val chunk = err.readAvailable(force = processEnded)

                if (chunk === END_OF_FILE_CHUNK) {
                    processState.updateAndGet { state -> when (state) {
                        is NotStarted -> TODO()
                        is Running -> closedOrPromoted(state, errNowOpen = false)
                        is BeingKilled -> closedOrPromoted(state, errNowOpen = false)
                        is AllOutputsReported, is Closed -> TODO()
                    }}

                    emptyList<ProcessEvent>()
                }
                // note: we dont need some kind of CAS loop on reading this fragment
                // since only we can read output from the stream,
                // so if we get output, even if it was closed by sombody else,
                // it's still safe to bounce to the output stream
                else {
                    val lines = errMessages.addAndParse(chunk!!)
                    recentErrors += lines
                    lines.map(::StandardErrorMessage)
                }
            }
            initialState.outOpen && (outReady || processEnded) -> {

                val chunk = out.readAvailable(force = processEnded)

                if (chunk === END_OF_FILE_CHUNK) {
                    processState.updateAndGet { state -> when (state) {
                        is NotStarted -> TODO()
                        is Running -> closedOrPromoted(state, outNowOpen = false)
                        is BeingKilled -> closedOrPromoted(state, outNowOpen = false)
                        is AllOutputsReported, is Closed -> TODO()
                    }}

                    emptyList<ProcessEvent>()
                }
                else outMessages
                    .addAndParse(chunk!!)
                    .map(::StandardOutputMessage)
            }
            initialState.exitCodeOrNull == null && exitCodeReady -> {

                val exitCode = process.exitValue()

                val priorState = processState.getAndUpdate { state -> when (state) {
                    is NotStarted -> TODO()
                    is Running -> closedOrPromoted(state, newExitCodeOrNull = exitCode)
                    is BeingKilled -> closedOrPromoted(state, newExitCodeOrNull = exitCode)
                    is AllOutputsReported, is Closed -> TODO()
                }}

                check(priorState is RunningOrBeingKilled && priorState.exitCodeOrNull == null)

                val code = when (priorState) {
                    is NotStarted -> TODO()
                    is Running -> exitCode
                    is BeingKilled -> priorState.closedOutput.exitCode
                    is AllOutputsReported, is Closed -> TODO()
                }

                listOf(ExitCode(code))
            }
            else -> null
        }
        return outputsOrNull
    }

    private fun output(it: ProcessEvent) {
        val sent = channel.trySend(it)

        if(sent.isFailure) tracing.trace { "failed to send '$it' !?" }
    }

    override fun onCancelling(cause: Throwable?) = doTracing("onCancelling($cause)"){ tracing ->
        // noop?
        val x = 4;
    }

    override fun onCancelled(cause: Throwable, handled: Boolean) = doTracing("onCancelled($cause)"){ tracing ->
        val previousState = processState.getAndUpdate { state -> when (state) {
            is NotStarted -> {
                // happens when a lazy coroutine is created and then the parent is killed
                Closed(ProcessClosing.Killed(null, makeProcessKilledException(tracing, null)))
            }
            is Running -> {
                if(cause is IOException)
                    Closed(ProcessClosing.NotStarted(cause)) else
                    TODO("state=$state in onCancelled($cause)")
            }
            is BeingKilled -> TODO("state=$state in onCancelled($cause)")
            is AllOutputsReported -> Closed(state.closedOutput)
            is Closed -> state.also { tracing.trace { "onCancelled when somebody closed?" } }
        }}

        if (previousState !is Closed) commitClose()
    }

    override fun onCompleted(value: Int?) = doTracing("onCompleted($value)"){ tracing ->
        val previousState = processState.getAndUpdate { state -> when (state) {
            is NotStarted, is Running, is BeingKilled -> TODO("state=$state in onCompleted($value)")
            is AllOutputsReported -> Closed(state.closedOutput)
            is Closed -> state.also { tracing.trace { "onComplete when somebody closed?" } }
        }}

        if (previousState !is Closed) commitClose()
    }

    // this method is tricky,
    // when we complete normally, onCompleted() will be sufficient
    // but in the case of cancellation, closing-up in onCancel() is too eager
    // since the process will produce more output.
    // so the strategy is this: the guy who does a state transfer to Closed() is responsible for calling this.
    private fun commitClose() = doTracing("commitClose"){ tracing ->
        val state = processState.get()
        check(state is Closed)

        val result = makeResult<Result<Int>>(tracing)
        if(result.isSuccess) channel.close() else channel.close(result.exceptionOrNull()!!)
    }

//    private fun makeResult(): Result<Int> { -- fails for ABI stability... ehhhh....
    @Suppress("FINAL_UPPER_BOUND") // using generic to dodge kotlinc edge case "dont use Result as return value"
    private fun <T: Result<Int>> makeResult(tracing: CoroutineTracer): T /*kotlin.Result<Int>*/ {
        val state = (processState.get() as Closed)

        @Suppress("UNCHECKED_CAST") return when(val output = state.closedOutput) {
            is ProcessClosing.CompletedNormally -> {
                output.exitCode.takeIf { config.expectedExitCodes == null || it in config.expectedExitCodes!! }
                    ?.let { Result.success(output.exitCode) }
                    ?: Result.failure(makeInvalidExitCodeException(tracing, output.exitCode))
            }
            is ProcessClosing.Killed -> {
                output.obtrudeExitCode.takeIf { config.expectedExitCodes == null || it in config.expectedExitCodes!! }
                    ?.let { Result.success(it) }
                    ?: Result.failure(output.killSource)
            }
            is ProcessClosing.CancelledAndKilled -> {
                Result.failure(makeCancelledException(tracing, output.cancellingEx))
            }
            is ProcessClosing.NotStarted -> {
                when(val ex = output.procStartException){
                    is Throwable -> Result.failure(makeProcessFailedToStartException(tracing, output.procStartException))
                    null -> Result.success(output.exitCode)
                    else -> TODO("$ex not throwable nor null")
                }
            }
        } as T
    }


    // TODO: support for binary input and output.
    override fun sendLine(input: String): Unit {
        val encoded = (input + '\n').toByteArray(config.encoding)
        start()
        process.outputStream.run {
            write(encoded)
            flush()
        }
    }

    override suspend fun kill(obtrudeExitCode: Int?): Unit {
        require(obtrudeExitCode == null || obtrudeExitCode >= 0)
        kill0(obtrudeExitCode)
        join()
    }

    private fun kill0(obtrudingExitCodeOrKillCode: Int?): Unit = doTracing("kill0(obtrude=$obtrudingExitCodeOrKillCode)", mark = true){ tracing ->

        val closedOutput: ProcessClosing = when(obtrudingExitCodeOrKillCode){
            null, in 0 .. Int.MAX_VALUE -> ProcessClosing.Killed(obtrudingExitCodeOrKillCode, makeProcessKilledException(tracing, obtrudingExitCodeOrKillCode))
            KilledBeforeStartedExitCode -> ProcessClosing.Killed(KilledBeforeStartedExitCode, makeProcessKilledException(tracing, obtrudingExitCodeOrKillCode))
            CancelledExitCode -> ProcessClosing.CancelledAndKilled(makeProcessKilledException(tracing, obtrudingExitCodeOrKillCode, getCancellationException()))
            else -> throw IllegalArgumentException("unknown obtrudeExitCode $obtrudingExitCodeOrKillCode")
        }

        val isCancelling = obtrudingExitCodeOrKillCode == CancelledExitCode

        val priorState = processState.getAndUpdate { state -> when(state) {
            is NotStarted -> Closed(ProcessClosing.NotStarted(null))
            is Running -> killed(state, closedOutput)
            is BeingKilled -> {
                if(isCancelling)
                    state.copy(closedOutput) /*we 'upgrade' from a kill to a cancel*/ else
                    state
            }
            is AllOutputsReported -> {
                if (isCancelling)
                    AllOutputsReported(closedOutput) else
                    state // noop since we tried to kill a finished process --let it finish normally
            }
            is Closed -> state // process already finished and reported --this might be a user error?
        }}

        when (priorState) {
            is NotStarted -> {
                // process never started,
                check(processState.get() is Closed)
                commitClose()
            }
            is Running -> {
                // we got the kill, so now we actually have to do it!
                commitKillAsync()
            }
            is BeingKilled -> Unit // contention: the process was killed by somebody else
            is AllOutputsReported -> Unit // contention: tried killed a process thats already finished
            is Closed -> {
                check(!isCancelling) // too late to cancel;
                // we're protected by coroutines here, it will handle somebody calling cancel() twice or
                // calling cancel() on a coroutine whose onComplete() has already gone off.
            }
        }
    }

    // function does not provide a synchronization mechanism on
    // on the process ending!
    private fun commitKillAsync() {
        tracing.trace { "commitKillAsync ${handle.pid()}" }

        val deadline = System.currentTimeMillis() + config.gracefulTimeoutMillis

        val gracefullyKilled = config.gracefulTimeoutMillis != 0L
                && doPlatformKillGracefullyAndSynchronize(deadline)

        if (gracefullyKilled){
            tracing.trace { "kill gracefully appears successful" }
        }
        else {
            JEP102ProcessFacade.killForcefullyAsync(tracing, process, config.includeDescendantsInKill)
        }
//            process.waitFor()
    }

    private fun doPlatformKillGracefullyAndSynchronize(deadline: Long): Boolean {

        val os = System.getProperty("os.name").lowercase()

        val probablySuccededInGracefullKilling = when {
            "windows" in os -> {
                WindowsProcessControl.tryKillGracefullyAsync(
                    tracing,
                    ProcessHandle.current().pid(),
                    process,
                    config.includeDescendantsInKill,
                    deadline
                )
            }
            "nix" in os -> {
                JEP102ProcessFacade.tryKillGracefullyAsync(
                    tracing,
                    process,
                    config.includeDescendantsInKill,
                    deadline
                )
            }
            else -> TODO("unknown OS: $os")
        }

        val timeout = deadline - System.currentTimeMillis()
        if(timeout <= 0) return false.also { tracing.trace { "timed out" }}

        process.outputStream.close()

        val dead = probablySuccededInGracefullKilling
                && process.waitFor(timeout, TimeUnit.MILLISECONDS)

        return dead
    }

    // note: I dont have access to awaitInternal() on JobSupport
    // which is what DeferredCoroutine uses.
    // so we synchronize with join() and fetch the result with our own state.
    override suspend fun await(): Int? = doTracing("await", mark = true){ tracing ->
        join()
        val result = makeResult<Result<Int>>(tracing)
        return result.getOrThrow().takeIf { it >= 0 }
    }

    override val onAwait: SelectClause1<Int?> get() = object: SelectClause1<Int?>{
        val delegate: SelectClause0 = onJoin
        @InternalCoroutinesApi
        override fun <R> registerSelectClause1(select: SelectInstance<R>, block: suspend (Int?) -> R) {
            delegate.registerSelectClause0(select) { block(await()) }
        }
    }

    private fun makeProcessFailedToStartException(tracing: CoroutineTracer, procStartException: IOException): IOException {
        return IOException(procStartException.message).apply {
            stackTrace = tracing.makeMangledTrace().toTypedArray()
        }
    }

    private fun makeCancelledException(tracing: CoroutineTracer, cancellingEx: Throwable) =
        CancellationException("Process was cancelled (and killed)", cancellingEx).apply {
            stackTrace = tracing.makeMangledTrace().toTypedArray()
        }

    private fun makeProcessKilledException(
        tracing: CoroutineTracer,
        exitCode: Int?,
        sourceException: Exception? = null
    ): ProcessKilledException {
        val message = buildString {
            append("killed ")
            if(exitCode != null) append("(with exit code $exitCode) ")
            appendAbbreviatedCommandLine()
        }
        return ProcessKilledException(message, sourceException).apply {
            stackTrace = tracing.makeMangledTrace().toTypedArray()
        }
    }

    private fun makeInvalidExitCodeException(
        tracing: CoroutineTracer,
        exitCode: Int
    ): InvalidExitCodeException {
        val recentErrorLines = recentErrors.toList()

        val message = buildString {
            append("exit code $exitCode from ")
            appendAbbreviatedCommandLine()
            if(recentErrorLines.any()) appendLine().appendLine("the most recent standard-error output was:")
            for (recentErrorLine in recentErrorLines) {
                appendLine(recentErrorLine.take(ColumnLimit))
            }
        }
        return InvalidExitCodeException(
            exitCode, config.expectedExitCodes,
            config.command, recentErrorLines, message
        ).apply {
            stackTrace = tracing.makeMangledTrace().toTypedArray()
        }
    }

    private fun StringBuilder.appendAbbreviatedCommandLine() {
        val cmdLine = config.command.joinToString(" ")
        append(cmdLine.take(ColumnLimit))
        if (cmdLine.length > ColumnLimit) append("[...]")
    }

    override suspend fun receive(): ProcessEvent = doTracing("receive", mark = true){ tracing ->
        return try {
            channel.receive()
        }
        catch(ex: CancellationException){
            throw makeCancelledException(tracing, ex)
        }
    }

    override val onReceive: SelectClause1<ProcessEvent>
        get() = channel.onReceive

    override suspend fun receiveCatching(): ChannelResult<ProcessEvent> = doTracing("receiveCatching", true){ tracing ->
        val result = channel.receiveCatching()
        when {
            result.isSuccess -> result
            result.isClosed -> {
                val sourceEx = result.exceptionOrNull() as? CancellationException
                    ?: CancellationException("Cancellation while receiving", result.exceptionOrNull())
                ChannelResult.closed(makeCancelledException(tracing, sourceEx))
            }
            result.isFailure -> result
            else -> TODO()
        }
    }

    override val onReceiveCatching: SelectClause1<ChannelResult<ProcessEvent>>
        get() = channel.onReceiveCatching

    override fun tryReceive(): ChannelResult<ProcessEvent> = channel.tryReceive()

    @OptIn(InternalCoroutinesApi::class)
    override suspend fun collect(collector: FlowCollector<ProcessEvent>) = doTracing("collect", mark = true) { tracing ->
        try {
            // do we convert channel to flow ahead of time once or do we do it per request?
            // im doing the latter because it throws-away aborted flows
            // (calling first() will abort the flow, but I do not want that to prevent future output)
            val flow = channel.receiveAsFlow()

            flow.collect(collector)
        }
        catch(ex: Exception) {
            throw if(ex is CancellationException)
                ex else //abort flow exception must be thrown as-is
                CancellationException("Process abruptly stopped producing output", ex).apply {
                    stackTrace = tracing.makeMangledTrace().toTypedArray()
                }
        }
    }

    private inline fun <R> doTracing(blockName: String, mark: Boolean = false, block: (CoroutineTracer) -> R): R{
        val initialTracing = tracing
        val tracing = if(mark) initialTracing.mark(blockName) else initialTracing
        try {
            tracing.trace { "${processState.get()} -> $blockName" }

            return block(tracing)
        }
        catch(ex: Throwable){
            initialTracing.trace(isErr = true) { "$blockName threw/will throw $ex" }
            throw ex
        }
        finally {
            initialTracing.trace { "$blockName -> ${processState.get()}" }
        }
    }
}

private fun killed(state: Running, closedOutput: ProcessClosing) = BeingKilled(
    state.errOpen,
    state.outOpen,
    state.exitCodeOrNull,
    closedOutput
)

private fun closedOrPromoted(
    state: RunningOrBeingKilled,
    errNowOpen: Boolean = state.errOpen,
    outNowOpen: Boolean = state.outOpen,
    newExitCodeOrNull: Int? = state.exitCodeOrNull
): State {
    val exitCodeOrNull = state.exitCodeOrNull ?: newExitCodeOrNull
    val isFinished = !errNowOpen && !outNowOpen && exitCodeOrNull != null

    val result = when {
        state is Running && ! isFinished -> Running(
            errOpen = errNowOpen,
            outOpen = outNowOpen,
            exitCodeOrNull = exitCodeOrNull
        )
        state is Running && isFinished -> AllOutputsReported(
            ProcessClosing.CompletedNormally(newExitCodeOrNull!!)
        )
        state is BeingKilled  && ! isFinished -> BeingKilled(
            errOpen = errNowOpen,
            outOpen = outNowOpen,
            exitCodeOrNull = exitCodeOrNull,
            state.closedOutput
        )
        state is BeingKilled && isFinished -> AllOutputsReported(
            state.closedOutput
        )
        else -> TODO("cant close or promote $state")
    }

    require(state != result) { "didnt close and didnt promote, before=$state, after=$result" }

    return result
}

private sealed class State {
    open override fun toString(): String = this::class.simpleName ?: "anon"
}
private object NotStarted: State()
interface RunningOrBeingKilled {
    val errOpen: Boolean
    val outOpen: Boolean
    val exitCodeOrNull: Int?
}
private class Running(
    override val errOpen: Boolean = true,
    override val outOpen: Boolean = true,
    override val exitCodeOrNull: Int? = null
): State(), RunningOrBeingKilled

private class BeingKilled(
    override val errOpen: Boolean = true,
    override val outOpen: Boolean = true,
    override val exitCodeOrNull: Int? = null,
    val closedOutput: ProcessClosing
): State(), RunningOrBeingKilled {
    fun copy(newClosedOutput: ProcessClosing) = BeingKilled(errOpen, outOpen, exitCodeOrNull, newClosedOutput)
}

sealed class ProcessClosing() {

    abstract val exitCode: Int

    class NotStarted(val procStartException: IOException?): ProcessClosing(){
        override val exitCode: Int get() = KilledBeforeStartedExitCode
    }
    class CompletedNormally(override val exitCode: Int): ProcessClosing()
    class Killed(val obtrudeExitCode: Int?, val killSource: ProcessKilledException): ProcessClosing() {
        override val exitCode get() = obtrudeExitCode ?: KilledWithoutObtrudingCodeExitCode
    }
    // difference here is that we kill the sub-process but also act with cancellation semantics
    // result-ish things (collect, await, etc) must throw a CancellationException instead of
    // the typical kill behaviour
    class CancelledAndKilled(val cancellingEx: ProcessKilledException): ProcessClosing() {
        override val exitCode get() = CancelledExitCode
    }
}

private class AllOutputsReported(val closedOutput: ProcessClosing): State()
private class Closed(val closedOutput: ProcessClosing): State()

val BackoffWindowMillis = 0L .. 100L
val END_OF_FILE_CHUNK: CharArray? = null

val KilledBeforeStartedExitCode = -1
val CancelledExitCode = -2
val KilledWithoutObtrudingCodeExitCode = -5
val ColumnLimit = 512

// helper to force the runtime to stop optimizing things
// so a debugger can actually see what is happening
internal fun doStuff(vararg any: Any?){
    val x = 4;
}