package groostav.kotlinx.exec

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import kotlin.coroutines.EmptyCoroutineContext
import java.lang.ProcessBuilder as JProcBuilder

data class ProcessResult(val outputAndErrorLines: List<String>, val exitCode: Int)

@InternalCoroutinesApi
internal fun CoroutineScope.execAsync(
        config: ProcessBuilder,
        start: CoroutineStart = CoroutineStart.DEFAULT
): RunningProcess {

    val newContext = newCoroutineContext(EmptyCoroutineContext)

    val channels = ProcessChannels(config.command.first())
    val stdout = channels.stdout.openSubscription().lines(config.delimiters)
    val stderr = channels.stderr.openSubscription().lines(config.delimiters)

    val aggregateChannel = Channel<ProcessEvent>(config.aggregateOutputBufferLineCount)

    val block: suspend ExecCoroutine.() -> Int = {

        val jvmProcessBuilder = java.lang.ProcessBuilder(config.command).apply {

            environment().apply {
                if (this !== config.environment) {
                    clear()
                    putAll(config.environment)
                }
            }

            directory(config.workingDirectory.toFile())
        }

        val process = try { jvmProcessBuilder.start() }
        catch(ex: IOException){ throw InvalidExecConfigurationException(ex.message ?: "", config, ex) }

        this.process = process
        val listeners = makeListenerProviderFactory().create(process, processID, config)

        channels.stdout.syndicateAsync(listeners.standardOutputChannel.value)
        channels.stderr.syndicateAsync(listeners.standardErrorChannel.value)
        GlobalScope.launch { channels.stdin.mapTo(process.outputStream.toSendChannel(config)) { it } }

        val exitCode = listeners.exitCodeDeferred.value
        var hasCode: Boolean = false

        if(config.aggregateOutputBufferLineCount > 0) do {
            val stdoutWasOpen = !stdout.isClosedForReceive
            val stderrWasOpen = !stderr.isClosedForReceive

            val next = select<ProcessEvent?> {
                if (stdoutWasOpen) stdout.onReceiveOrNull { it?.let(::StandardOutputMessage) }
                if (stderrWasOpen) stderr.onReceiveOrNull { it?.let(::StandardErrorMessage) }
                if ( ! hasCode) exitCode.onAwait { ExitCode(it).also { hasCode = true } }
            }
            onProcessEvent(next ?: continue)
        }
        while (isActive && (stderrWasOpen || stdoutWasOpen || !hasCode))

        val result = exitCode.await()

        aggregateChannel.close()
        standardInput.close()

        result
    }

    val stdinMutex = Mutex()

    val coroutine = ExecCoroutine(
            config,
            newContext,
            channels.stdin.lockedBy(stdinMutex),
            channels.stdout.openSubscription().tail(config.standardOutputBufferCharCount),
            channels.stderr.openSubscription().tail(config.standardErrorBufferCharCount),
            aggregateChannel,
            fail; //this isnt tested: you need to make sure this actually flushes and that it actually hits the input stream.
            channels.stdin.lockedBy(stdinMutex).flatMap { (it + config.inputFlushMarker).asIterable() },
            makePIDGenerator()
    )
    coroutine.start(start, coroutine, block)
    return coroutine
}

@InternalCoroutinesApi
fun CoroutineScope.execAsync(config: ProcessBuilder.() -> Unit): RunningProcess{

    val configActual = processBuilder(coroutineScope = this@execAsync) {
        config()
        source = AsynchronousExecutionStart(command.toList())
    }
    return execAsync(configActual)
}
@InternalCoroutinesApi
fun CoroutineScope.execAsync(commandFirst: String, vararg commandRest: String): RunningProcess = execAsync {
    command = listOf(commandFirst) + commandRest.toList()
}

@InternalCoroutinesApi
suspend fun exec(config: ProcessBuilder.() -> Unit): ProcessResult = coroutineScope {

    val configActual = processBuilder(GlobalScope) {
        standardErrorBufferCharCount = 0
        standardOutputBufferCharCount = 0

        apply(config)

        source = SynchronousExecutionStart(command.toList())
    }

    val runningProcess = execAsync(configActual)
    runningProcess.join()

    val output = runningProcess
            .filter { it !is ExitCode }
            .map { it.formattedMessage }

    ProcessResult(output.toList(), runningProcess.getCompleted())
}

@InternalCoroutinesApi
suspend fun exec(commandFirst: String, vararg commandRest: String): ProcessResult
        = exec { command = listOf(commandFirst) + commandRest }

@InternalCoroutinesApi
suspend fun execVoid(config: ProcessBuilder.() -> Unit): Int = coroutineScope {

    val configActual = processBuilder(GlobalScope) {
        aggregateOutputBufferLineCount = 0
        standardErrorBufferCharCount = 0
        standardErrorBufferCharCount = 0

        apply(config)

        source = SynchronousExecutionStart(command.toList())
    }
    val runningProcess = execAsync(configActual)

        runningProcess.await()
}
@InternalCoroutinesApi
suspend fun execVoid(commandFirst: String, vararg commandRest: String): Int = execVoid {
    command = listOf(commandFirst) + commandRest.toList()
}

class InvalidExitValueException(
        val command: List<String>,
        val exitValue: Int,
        val expectedExitCodes: Set<Int>?,
        val recentStandardErrorLines: List<String>,
        message: String,
        stackTraceApplier: InvalidExitValueException.() -> Unit
): RuntimeException(message) {

    init {
        stackTraceApplier()
        if(stackTrace == null || stackTrace.isEmpty()) super.fillInStackTrace()
    }

    override fun fillInStackTrace(): Throwable = this.also {
        //noop, this is handled by init
    }
}

internal fun makeExitCodeException(config: ProcessBuilder, exitCode: Int, recentErrorOutput: List<String>): Throwable {
    val expectedCodes = config.expectedOutputCodes
    val builder = StringBuilder().apply {

        appendln("exec '${config.command.joinToString(" ")}'")

        val parentheticDescription = when(expectedCodes?.size){
            null -> "any exit value".also { TODO("How did you get here!?") }
            1 -> "${expectedCodes.single()}"
            in 2 .. Int.MAX_VALUE -> "one of ${expectedCodes.joinToString()}"
            else -> TODO()
        }
        appendln("exited with code $exitCode (expected $parentheticDescription)")

        if(recentErrorOutput.any()){
            appendln("the most recent standard-error output was:")
            recentErrorOutput.forEach { appendln(it) }
        }
    }

    val result = InvalidExitValueException(config.command, exitCode, expectedCodes, recentErrorOutput, builder.toString()){
        val source = config.source
        when(source){
            is AsynchronousExecutionStart -> {
                initCause(source)
            }
            is SynchronousExecutionStart -> {
                stackTrace = source.stackTrace
            }
        }
    }

    require(result.stackTrace != null)

    return result
}

class InvalidExecConfigurationException(message: String, val configuration: ProcessBuilder, cause: Exception? = null)
    : RuntimeException(message, cause)