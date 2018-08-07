package groostav.kotlinx.exec

import kotlinx.coroutines.experimental.channels.*
import java.lang.ProcessBuilder as JProcBuilder

internal fun execAsync(config: ProcessBuilder, origin: Exception = Exception()): RunningProcess {

    val jvmProcessBuilder = JProcBuilder(config.command).apply {
        environment().apply { clear(); putAll(config.environment) }
    }

    val runningProcessFactory = RunningProcessFactory()

    val listenerProviderFactory = makeListenerProviderFactory()

    val jvmRunningProcess = jvmProcessBuilder.start()

    val pidProvider = makePIDGenerator(jvmRunningProcess)
    val processID = pidProvider.pid.value

    val processControllerFacade: ProcessControlFacade = makeCompositeFacade(jvmRunningProcess, processID)
    val listenerProvider = listenerProviderFactory.create(jvmRunningProcess, processID, config)

    return runningProcessFactory.create(config, jvmRunningProcess, processID, processControllerFacade, listenerProvider)
}

fun execAsync(config: ProcessBuilder.() -> Unit): RunningProcess{
    val configActual = processBuilder {
        config()

        source = AsynchronousExecutionStart(command.toList())
    }
    return execAsync(configActual)
}
fun execAsync(commandFirst: String, vararg commandRest: String): RunningProcess = execAsync {
    command = listOf(commandFirst) + commandRest.toList()
}

suspend fun exec(config: ProcessBuilder.() -> Unit): Pair<List<String>, Int> {

    val configActual = processBuilder {
        standardErrorBufferCharCount = 0
        standardOutputBufferCharCount = 0

        config()

        source = SynchronousExecutionStart(command.toList())
    }
    val runningProcess = execAsync(configActual)

    runningProcess.join()

    val output = runningProcess
            .filter { it !is ExitCode }
            .map { it.formattedMessage }

    return output.toList() to runningProcess.exitCode.getCompleted()
}

suspend fun exec(commandFirst: String, vararg commandRest: String): Pair<List<String>, Int>
        = exec { command = listOf(commandFirst) + commandRest }

suspend fun execVoid(config: ProcessBuilder.() -> Unit): Int {
    val configActual = processBuilder {
        aggregateOutputBufferLineCount = 0
        standardErrorBufferCharCount = 0
        standardErrorBufferCharCount = 0

        config()

        source = SynchronousExecutionStart(command.toList())
    }
    return execAsync(configActual).exitCode.await()
}
suspend fun execVoid(commandFirst: String, vararg commandRest: String) = execVoid {
    command = listOf(commandFirst) + commandRest.toList()
}

class InvalidExitValueException internal constructor(
        val command: List<String>,
        val exitValue: Int,
        val expectedExitCodes: Set<Int>,
        message: String,
        stackTraceApplier: InvalidExitValueException.() -> Unit
): RuntimeException(message) {

    init {
        stackTraceApplier()
        if(stackTrace == null) super.fillInStackTrace()
    }

    override fun fillInStackTrace(): Throwable = this.also {
        //noop, this is handled by init
    }
}

internal fun makeExitCodeException(config: ProcessBuilder, exitCode: Int, recentOutput: List<String>): Throwable {
    val builder = StringBuilder().apply {
        appendln("exec '${config.command.joinToString(" ")}'")
        val multipleOutputs = config.expectedOutputCodes.size > 1
        append("exited with code $exitCode ")
        val exitCodesScription = config.expectedOutputCodes.joinToString("', '")
        appendln("(expected ${if(multipleOutputs) "one of " else ""}'$exitCodesScription')")

        if(recentOutput.any()){
            appendln("the most recent standard-error output was:")
            recentOutput.forEach { appendln(it) }
        }
    }

    val result = InvalidExitValueException(config.command, exitCode, config.expectedOutputCodes, builder.toString()){
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
