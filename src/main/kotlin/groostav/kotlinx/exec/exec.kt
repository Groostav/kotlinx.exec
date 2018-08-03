package groostav.kotlinx.exec

import kotlinx.coroutines.experimental.channels.*
import java.lang.ProcessBuilder as JProcBuilder

internal fun execAsync(config: ProcessBuilder): RunningProcess {

    val jvmProcessBuilder = JProcBuilder(config.command).apply {
        environment().apply { clear(); putAll(config.environment) }
    }
    blockableThread.prestart(2)
    val jvmRunningProcess = jvmProcessBuilder.start()

    //TODO: using a lateinit flow with `facade.init(jvmRunningProcess)` would allow you to re-order this
    // and avoid the object allocations in this time-sensitive spot.
    val processControllerFacade: ProcessControlFacade = makeCompositImplementation(jvmRunningProcess)

    return RunningProcessImpl(config, jvmRunningProcess, processControllerFacade)
}

fun execAsync(config: ProcessBuilder.() -> Unit): RunningProcess = execAsync(processBuilder(config))
fun execAsync(commandFirst: String, vararg commandRest: String): RunningProcess = execAsync {
    command = listOf(commandFirst) + commandRest.toList()
}

suspend fun exec(config: ProcessBuilder.() -> Unit): Pair<List<String>, Int> {
    val runningProcess = execAsync(processBuilder(config))
    runningProcess.exitCode.await()

    val output = runningProcess
            .filter { it !is ExitCode }
            .map { it.toDefaultString() }

    return output.toList() to runningProcess.exitCode.getCompleted()
}

suspend fun exec(commandFirst: String, vararg commandRest: String): Pair<List<String>, Int>
        = exec { command = listOf(commandFirst) + commandRest }

suspend fun execVoid(config: ProcessBuilder.() -> Unit): Int {
    val configActual = processBuilder {
        lineBufferSize = 0
//        standardOutputCharBuffer = 0

        config()
    }
    return execAsync(configActual).exitCode.await()
}
suspend fun execVoid(commandFirst: String, vararg commandRest: String) = execVoid {
    command = listOf(commandFirst) + commandRest.toList()
}

class InvalidExitValueException(val command: List<String>, val exitValue: Int, val expectedExitCodes: Set<Int>, message: String): RuntimeException(message)
internal fun makeExitCodeException(command: List<String>, exitCode: Int, expectedOutputCodes: Set<Int>, lines: List<String>): Throwable {
    val builder = StringBuilder().apply {
        appendln("exec '${command.joinToString(" ")}'")
        appendln("exited with code $exitCode (expected one of '${expectedOutputCodes.joinToString("', '")}')")

        if(lines.any()){
            appendln("the most recent standard-error output was:")
            lines.forEach { appendln(it) }
        }
    }

    return InvalidExitValueException(command, exitCode, expectedOutputCodes, builder.toString())
}
