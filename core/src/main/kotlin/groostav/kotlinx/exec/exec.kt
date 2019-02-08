package groostav.kotlinx.exec

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*

data class ProcessResult(val outputAndErrorLines: List<String>, val exitCode: Int)

internal fun CoroutineScope.execAsync(config: ProcessConfiguration, start: CoroutineStart): RunningProcess {
    return EXEC_ASYNC_WRAPPER.execAsync(this, config, start)
}

fun CoroutineScope.execAsync(start: CoroutineStart = CoroutineStart.DEFAULT, config: ProcessConfiguration.() -> Unit): RunningProcess {

    val configActual = configureProcess {
        config()
        source = AsynchronousExecutionStart(command.toList())
    }
    return execAsync(configActual, start)
}

fun CoroutineScope.execAsync(
        commandFirst: String,
        vararg commandRest: String,
        start: CoroutineStart = CoroutineStart.DEFAULT
): RunningProcess = execAsync(start) {
    command = listOf(commandFirst) + commandRest.toList()
}

@Throws(InvalidExitCodeException::class)
suspend fun exec(
        start: CoroutineStart = CoroutineStart.DEFAULT,
        config: ProcessConfiguration.() -> Unit
): ProcessResult = coroutineScope {

    val configActual = configureProcess {
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

@Throws(InvalidExitCodeException::class)
suspend fun exec(commandFirst: String, vararg commandRest: String, start: CoroutineStart = CoroutineStart.DEFAULT): ProcessResult
        = exec(start) { command = listOf(commandFirst) + commandRest }

@Throws(InvalidExitCodeException::class)
suspend fun execVoid(start: CoroutineStart = CoroutineStart.DEFAULT, config: ProcessConfiguration.() -> Unit): Int = coroutineScope {

    val configActual = configureProcess {

        apply(config)

        aggregateOutputBufferLineCount = 0
        standardErrorBufferCharCount = 0
        standardErrorBufferCharCount = 0

        source = SynchronousExecutionStart(command.toList())
    }
    val runningProcess = execAsync(configActual, start)
    runningProcess.await()
}
@Throws(InvalidExitCodeException::class)
suspend fun execVoid(
        commandFirst: String, vararg commandRest: String,
        start: CoroutineStart = CoroutineStart.DEFAULT
): Int = execVoid(start) {
    command = listOf(commandFirst) + commandRest.toList()
}

class InvalidExitCodeException(
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
        else -> nfg("unknown entryPoint type $entryPoint")
    }
}


internal fun makeExitCodeException(config: ProcessConfiguration, exitCode: Int, recentErrorOutput: List<String>): InvalidExitCodeException {
    val expectedCodes = config.expectedOutputCodes
    val builder = buildString {

        appendln("exec '${config.command.joinToString(" ")}'")

        val parentheticDescription = when(expectedCodes?.size){
            null -> "any exit value".also { nfg("How did you get here!?") }
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

    val result = InvalidExitCodeException(config.command, exitCode, expectedCodes, recentErrorOutput, builder, config.source)

    require(result.stackTrace != null)

    return result
}
