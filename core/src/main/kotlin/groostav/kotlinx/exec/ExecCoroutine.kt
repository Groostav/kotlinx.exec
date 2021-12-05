package groostav.kotlinx.exec

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.receiveOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.internal.resumeCancellableWith
import java.lang.IllegalArgumentException
import java.lang.RuntimeException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.intrinsics.createCoroutineUnintercepted
import kotlin.coroutines.intrinsics.intercepted
import kotlin.coroutines.resume

@OptIn(InternalCoroutinesApi::class)
class ExecCoroutine(
    context: CoroutineContext,
    val config: ProcessConfiguration,
    private val lazy: Boolean,
    private val channel: Channel<ProcessEvent> = Channel<ProcessEvent>(
        capacity = config.aggregateOutputBufferLineCount,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
): AbstractCoroutine<Int?>(context, !lazy), RunningProcess, Flow<ProcessEvent> {

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
    private var tracing = CoroutineTracer(config.command.first().split("/").last().take(30))

    init {
        if( ! lazy) onStart()
        if(lazy) tracing = tracing.mark("init")
    }

    override val processID: Long get() = handle.pid()

    // kotlinx coroutines guarantee: this will only be called once
    override fun onStart() {

        val newState = processState.updateAndGet { state ->
            when(state){
                is NotStarted -> Running()
                is Closed -> state
                is AllOutputsReported, is BeingKilled, is Running -> TODO("state=$state onStart()")
            }
        }

        when (newState) {
            is Running -> {
                val processBuilder = ProcessBuilder(config.command)
                    .directory(config.workingDirectory.toFile())
                    .apply {
                        if(config.environment !== InheritedDefaultEnvironment) {
                            environment().apply { clear(); putAll(config.environment) }
                        }
                    }

                process = processBuilder.start()
                handle = process.toHandle()

                tracing = tracing
                    .appendName(handle.pid().toString())
                    .mark("start")

                err = NamedTracingProcessReader.forStandardError(process, processID, config.encoding)
                out = NamedTracingProcessReader.forStandardOutput(process, processID, config.encoding)

                if (lazy) {
                    val continuation = body.createCoroutineUnintercepted(this)
                    try {
                        continuation.intercepted().resumeCancellableWith(Result.success(Unit))
                    }
                    catch (e: Throwable) {
                        this.resumeWith(Result.failure(e))
                    }
                }
            }
            is Closed -> this.resume(KilledBeforeStartedExitCode) // contention on start() and kill()
            is AllOutputsReported, is BeingKilled, is NotStarted -> TODO("state=$newState onStart()")
        }
    }

    internal val body: suspend () -> Int? = body@ {

        try { withContext(NonCancellable) {

            tracing.trace { "${processState.get()} -> body.invoke()" }

            var initialState = processState.get()
            //note: we may already be in the final state if there is contention between coroutine.start() and kill()

            var backoff = BackoffWindowMillis.first

            while (initialState is RunningOrBeingKilled) {

                initialState.run { check(errOpen || errOpen || exitCodeOrNull != null) { "state not promoted: $this" } }

                if (isCancelled && initialState is Running) {
                    kill0(CancelledExitCode)
                }

                check(coroutineContext[ContinuationInterceptor] == Dispatchers.IO)

                when(val outputsOrNull = pollOutputsAndUpdateState(initialState)){
                    null -> {
                        backoff = (backoff * 2)
                            .coerceIn(BackoffWindowMillis)
                            .coerceAtLeast(1)

                        delay(backoff)
                    }
                    else -> for(event in outputsOrNull){
                        output(event)
                    }
                }

                initialState = processState.get()
            }

            when (val finishingState = initialState) {
                is AllOutputsReported -> finishingState.closedOutput.exitCode
                is Closed -> makeResult<Result<Int>>(tracing).getOrThrow()
                is BeingKilled, is NotStarted, is Running -> TODO("finishingState=$finishingState in body.finally")
            }
        }}
        catch(ex: Exception){
            if(ex is CancellationException) throw ex
            RuntimeException("INTERNAL EXEC COROUTINE FAILURE", ex).printStackTrace()
            InternalErrorExitCode
        }
        finally {
            tracing.trace { "body.invoke() -> ${processState.get()}" }
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
        //im still not actually sure if this calls a prefetching code.
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
        channel.offer(it)
    }

    override fun onCancelled(cause: Throwable, handled: Boolean) {
        tracing.trace { "${processState.get()} -> onCancelled($cause, $handled)" }
        try {
            val previousState = processState.getAndUpdate { state -> when (state) {
                is NotStarted -> {
                    // happens when a lazy coroutine is created and then the parent is killed
                    Closed(ProcessClosing.Killed(null, makeProcessKilledException(tracing, null)))
                }
                is Running, is BeingKilled -> TODO("state=$state in onCancelled($cause)")
                is AllOutputsReported -> Closed(state.closedOutput)
                is Closed -> state.also { tracing.trace { "onCancelled when somebody closed?" } }
            }}

            if (previousState !is Closed) onProcessStateClosed()
        }
        finally {
            tracing.trace { "onCancelled(...) -> ${processState.get()}" }
        }
    }

    override fun onCompleted(value: Int?) {
        tracing.trace { "${processState.get()} -> onCompleted($value)" }

        try {
            val previousState = processState.getAndUpdate { state -> when (state) {
                is NotStarted, is Running, is BeingKilled -> TODO("state=$state in onCompleted($value)")
                is AllOutputsReported -> Closed(state.closedOutput)
                is Closed -> state.also { tracing.trace { "onComplete when somebody closed?" } }
            }}

            if (previousState !is Closed) onProcessStateClosed()
        }
        finally {
            tracing.trace { "onCompleted(...) -> ${processState.get()}" }
        }
    }

    // this method is tricky,
    // when we complete normally, onCompleted() will be sufficient
    // but in the case of cancellation, closing-up in onCancel() is too eager
    // since the process will produce more output.
    // so the strategy is this: the guy who does a state transfer to Closed() is responsible for calling this.
    private fun onProcessStateClosed() {
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
        kill0(obtrudeExitCode)
        join()
    }

    @Suppress("ThrowableNotThrown")
    private fun kill0(obtrudingExitCodeOrKillCode: Int?): Unit = try {

        val tracing = tracing.mark("kill")
        tracing.trace { "${processState.get()} -> kill0(code=$obtrudingExitCodeOrKillCode)" }

        val closedOutput: ProcessClosing = when(obtrudingExitCodeOrKillCode){
            in 0 .. Int.MAX_VALUE -> ProcessClosing.Killed(obtrudingExitCodeOrKillCode, makeProcessKilledException(tracing, obtrudingExitCodeOrKillCode))
            null -> ProcessClosing.Killed(null, makeProcessKilledException(tracing, obtrudingExitCodeOrKillCode))
            KilledBeforeStartedExitCode -> ProcessClosing.Killed(KilledBeforeStartedExitCode, makeProcessKilledException(tracing, obtrudingExitCodeOrKillCode))
            CancelledExitCode -> ProcessClosing.CancelledAndKilled(makeProcessKilledException(tracing, obtrudingExitCodeOrKillCode, getCancellationException()))
            else -> throw IllegalArgumentException("unknown obtrudeExitCode $obtrudingExitCodeOrKillCode")
        }

        val isCancelling = obtrudingExitCodeOrKillCode == CancelledExitCode

        val priorState = processState.getAndUpdate { state -> when(state) {
            is NotStarted -> Closed(closedOutput)
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
                onProcessStateClosed()
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

        tracing.trace { "kill0(...) -> ${processState.get()}" }
    }
    catch(ex: Throwable){
        RuntimeException("INTERNAL EXEC COROUTINE FAILURE", ex).printStackTrace()
        if(ex is Error) throw ex
        Unit
    }

    // function does not provide a synchronization mechanism on
    // on the process ending!
    private fun commitKillAsync() {
        tracing.trace { "commitKillAsync ${handle.pid()}" }

        val deadline = System.currentTimeMillis() + config.gracefulTimeoutMillis

        val gracefullyKilled = config.gracefulTimeoutMillis != 0L
                && doPlatformKillGracefullyAndSynchronize(deadline)

        if (!gracefullyKilled) {
            JEP102ProcessFacade.killForcefullyAsync(tracing, process, config.includeDescendantsInKill)
//            process.waitFor()
        }
    }

    fun doPlatformKillGracefullyAndSynchronize(deadline: Long): Boolean {

        val os = System.getProperty("os.name").toLowerCase()

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

        val dead = probablySuccededInGracefullKilling
                && process.waitFor(timeout, TimeUnit.MILLISECONDS)

        return dead
    }

    // note: I dont have access to awaitInternal() on JobSupport
    // which is what DeferredCoroutine uses.
    // so we synchronize with join() and fetch the result with our own state.
    override suspend fun await(): Int {
        val tracing = tracing.mark("await")
        try {
            tracing.trace { "${processState.get()} -> await()" }
            join()
            val result = makeResult<Result<Int>>(tracing)
            return result.getOrThrow()
        }
        finally {
            tracing.trace { "await() -> ${processState.get()}" }
        }
    }


    private fun makeCancelledException(tracing: CoroutineTracer, cancellingEx: Throwable) =
        CancellationException("Process was cancelled (and killed)", cancellingEx)

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

    override suspend fun receive(): ProcessEvent {
        val tracing = tracing.mark("receive")
        tracing.trace { "${processState.get()} -> receive()"}

        return try {
            channel.receive()
        }
        catch(ex: CancellationException){
            throw CancellationException("process cancelled (no further output)", ex).apply {
                stackTrace = tracing.makeMangledTrace().toTypedArray()
            }
        }
        finally {
            tracing.trace { "receive() -> ${processState.get()}"}
        }
    }
    override suspend fun receiveOrNull(): ProcessEvent? {
        return channel.receiveOrNull()
    }

    @OptIn(InternalCoroutinesApi::class)
    override suspend fun collect(collector: FlowCollector<ProcessEvent>) {
        val tracing = tracing.mark("collect")
        try {
            // do we convert channel to flow ahead of time once or do we do it per request?
            // im doing the latter because it throws-away aborted flows
            // (calling first() will abort the flow, but I do not want that to prevent future output)
            val flow = channel.receiveAsFlow()

            flow.collect(object: FlowCollector<ProcessEvent> {
                override suspend fun emit(value: ProcessEvent) {
//                    try {
                        collector.emit(value)
//                    }
//                    catch (ex: CancellationException) {
//                        // TODO: write a test that actually incurrs this exception? How do we get it?
//                        throw CancellationException("failed to emit element", ex).apply {
//                            stackTrace = tracing.makeMangledTrace().toTypedArray()
//                        }
//                    }
                }
            })
        }
        catch(ex: Exception) {
            throw if(ex is CancellationException)
                ex else //abort flow exception must be thrown as-is
                CancellationException("Process abruptly stopped producing output", ex).apply {
                    stackTrace = tracing.makeMangledTrace().toTypedArray()
                }
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

private sealed class State { }
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

    data class CompletedNormally(override val exitCode: Int): ProcessClosing()
    data class CancelledAndKilled(val cancellingEx: ProcessKilledException): ProcessClosing() {
        override val exitCode get() = CancelledExitCode
    }
    data class Killed(val obtrudeExitCode: Int?, val killSource: ProcessKilledException): ProcessClosing() {
        override val exitCode get() = obtrudeExitCode ?: KilledWithoutObtrudingCodeExitCode
    }
}

private class AllOutputsReported(val closedOutput: ProcessClosing): State()
private class Closed(val closedOutput: ProcessClosing): State()

val BackoffWindowMillis = 0L .. 100L
val END_OF_FILE_CHUNK: CharArray? = null
