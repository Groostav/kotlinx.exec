package groostav.kotlinx.exec

import kotlinx.coroutines.experimental.channels.ReceiveChannel
import kotlinx.coroutines.experimental.channels.any
import kotlinx.coroutines.experimental.channels.map
import kotlinx.coroutines.experimental.channels.toList
import java.nio.charset.Charset
import java.lang.ProcessBuilder as JProcBuilder

internal fun execAsync(config: ProcessBuilder): RunningProcess {

    val jvmProcessBuilder = JProcBuilder(config.command).apply {
        environment().apply { clear(); putAll(config.environment) }
    }
    blockableThread.prestart(2)
    val jvmRunningProcess = jvmProcessBuilder.start()

    val processControllerFacade: ProcessFacade = makeCompositImplementation(jvmRunningProcess)

    return RunningProcessImpl(config, jvmRunningProcess, processControllerFacade)
}

fun execAsync(config: ProcessBuilder.() -> Unit): RunningProcess = execAsync(processBuilder(config))
fun execAsync(commandFirst: String, vararg commandRest: String): RunningProcess = execAsync {
    command = listOf(commandFirst) + commandRest.toList()
}

suspend fun exec(config: ProcessBuilder.() -> Unit): Pair<List<String>, Int>
        = execAsync(processBuilder(config)).run { map { it.toDefaultString() }.toList() to exitCode.await() }

suspend fun exec(commandFirst: String, vararg commandRest: String): Pair<List<String>, Int>
        = exec { command = listOf(commandFirst) + commandRest }

suspend fun execVoid(config: ProcessBuilder.() -> Unit): Int = execAsync(processBuilder(config)).exitCode.await()
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
