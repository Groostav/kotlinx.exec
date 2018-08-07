package groostav.kotlinx.exec

import kotlinx.coroutines.experimental.channels.*
import java.lang.ProcessBuilder as JProcBuilder

internal fun execAsync(config: ProcessBuilder): RunningProcess {

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

fun execAsync(config: ProcessBuilder.() -> Unit): RunningProcess = execAsync(processBuilder(config))
fun execAsync(commandFirst: String, vararg commandRest: String): RunningProcess = execAsync {
    command = listOf(commandFirst) + commandRest.toList()
}

suspend fun exec(config: ProcessBuilder.() -> Unit): Pair<List<String>, Int> {
    val configActual = processBuilder {
        standardErrorBufferCharCount = 0
        standardOutputBufferCharCount = 0

        config()
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
    }
    return execAsync(configActual).exitCode.await()
}
suspend fun execVoid(commandFirst: String, vararg commandRest: String) = execVoid {
    command = listOf(commandFirst) + commandRest.toList()
}

class InvalidExitValueException(
        val command: List<String>,
        val exitValue: Int,
        val expectedExitCodes: Set<Int>,
        message: String
): RuntimeException(message)

internal fun makeExitCodeException(command: List<String>, exitCode: Int, expectedOutputCodes: Set<Int>, lines: List<String>): Throwable {
    val builder = StringBuilder().apply {
        appendln("exec '${command.joinToString(" ")}'")
        val multipleOutputs = expectedOutputCodes.size > 1
        append("exited with code $exitCode ")
        val exitCodesScription = expectedOutputCodes.joinToString("', '")
        appendln("(expected ${if(multipleOutputs) "one of " else ""}'$exitCodesScription')")

        if(lines.any()){
            appendln("the most recent standard-error output was:")
            lines.forEach { appendln(it) }
        }
    }

    return InvalidExitValueException(command, exitCode, expectedOutputCodes, builder.toString())
}
