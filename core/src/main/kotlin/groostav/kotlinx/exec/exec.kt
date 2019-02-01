package groostav.kotlinx.exec

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlin.coroutines.EmptyCoroutineContext
import java.lang.ProcessBuilder as JProcBuilder

data class ProcessResult(val outputAndErrorLines: List<String>, val exitCode: Int)

@InternalCoroutinesApi
internal fun CoroutineScope.execAsync(config: ProcessBuilder, start: CoroutineStart): RunningProcess {

    val newContext = newCoroutineContext(EmptyCoroutineContext)
    val coroutine = ExecCoroutine(
            config,
            newContext, start,
            makePIDGenerator(), makeListenerProviderFactory(), CompositeProcessControlFactory
    )

    if(start != CoroutineStart.LAZY) {
        require(coroutine.prestart())
        require(coroutine.kickoff())
    }
    coroutine.start(start, coroutine, ExecCoroutine::waitFor)

    return coroutine
}

@InternalCoroutinesApi
fun CoroutineScope.execAsync(start: CoroutineStart = CoroutineStart.DEFAULT, config: ProcessBuilder.() -> Unit): RunningProcess {

    val configActual = processBuilder() {
        config()
        source = AsynchronousExecutionStart(command.toList())
    }
    return execAsync(configActual, start)
}
@InternalCoroutinesApi
@Throws(InvalidExitValueException::class)
fun CoroutineScope.execAsync(
        commandFirst: String,
        vararg commandRest: String,
        start: CoroutineStart = CoroutineStart.DEFAULT
): RunningProcess = execAsync(start) {
    command = listOf(commandFirst) + commandRest.toList()
}

@InternalCoroutinesApi
@Throws(InvalidExitValueException::class)
suspend fun exec(
        start: CoroutineStart = CoroutineStart.DEFAULT,
        config: ProcessBuilder.() -> Unit
): ProcessResult = coroutineScope {

    val configActual = processBuilder {
        apply(config)

        source = SynchronousExecutionStart(command.toList())
        exitCodeInResultAggregateChannel = false
    }

    val runningProcess = execAsync(configActual, start)
    runningProcess.join()

    val output = runningProcess
            .map { it.formattedMessage }
            .toList()

    ProcessResult(output, runningProcess.getCompleted())
}

@InternalCoroutinesApi
@Throws(InvalidExitValueException::class)
suspend fun exec(commandFirst: String, vararg commandRest: String, start: CoroutineStart = CoroutineStart.DEFAULT): ProcessResult
        = exec(start) { command = listOf(commandFirst) + commandRest }

@InternalCoroutinesApi
@Throws(InvalidExitValueException::class)
suspend fun execVoid(start: CoroutineStart = CoroutineStart.DEFAULT, config: ProcessBuilder.() -> Unit): Int = coroutineScope {

    val configActual = processBuilder {
        aggregateOutputBufferLineCount = 0
        standardErrorBufferCharCount = 0
        standardErrorBufferCharCount = 0

        apply(config)

        source = SynchronousExecutionStart(command.toList())
    }
    val runningProcess = execAsync(configActual, start)
    runningProcess.await()
}
@InternalCoroutinesApi
@Throws(InvalidExitValueException::class)
suspend fun execVoid(
        commandFirst: String, vararg commandRest: String,
        start: CoroutineStart = CoroutineStart.DEFAULT
): Int = execVoid(start) {
    command = listOf(commandFirst) + commandRest.toList()
}

class InvalidExitValueException(
        val command: List<String>,
        val exitValue: Int,
        val expectedExitCodes: Set<Int>?,
        val recentStandardErrorLines: List<String>,
        message: String,
        entryPoint: ExecEntryPoint?
): RuntimeException(message) {

    init {
        mergeCauses(null, entryPoint)
    }
}

private fun Throwable.mergeCauses(cause: Throwable?, entryPoint: ExecEntryPoint?) {

    if(cause != null){
        cause.mergeCauses(cause.cause, entryPoint)
        initCause(cause)
    }
    else when (entryPoint) {
        is AsynchronousExecutionStart -> {
            initCause(entryPoint)
        }
        is SynchronousExecutionStart -> {
//            stackTrace = entryPoint.stackTrace
            initCause(entryPoint)
        }
        null -> {
            //nothing to do
        }
        else -> TODO("unknown entryPoint type $entryPoint")
    }
}


internal fun makeExitCodeException(config: ProcessBuilder, exitCode: Int, recentErrorOutput: List<String>): InvalidExitValueException {
    val expectedCodes = config.expectedOutputCodes
    val builder = buildString {

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

    val result = InvalidExitValueException(config.command, exitCode, expectedCodes, recentErrorOutput, builder, config.source)

    require(result.stackTrace != null)

    return result
}

class InvalidExecConfigurationException(message: String, cause: Exception? = null)
    : RuntimeException(message, cause)

class ProcessInterruptedException(val exitCode: Int, entryPoint: ExecEntryPoint?, killSource: CancellationException)
    : CancellationException("process interrupted, finished with exit code $exitCode"){
    init {
         mergeCauses(killSource, entryPoint)
    }
}

class ProcessKilledException(val exitCode: Int, entryPoint: ExecEntryPoint?, killSource: CancellationException)
    : CancellationException("process killed, finished with exit code $exitCode"){
    init {
        mergeCauses(killSource, entryPoint)
    }
}