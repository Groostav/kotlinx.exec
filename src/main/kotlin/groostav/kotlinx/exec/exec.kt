package groostav.kotlinx.exec

import java.nio.charset.Charset
import java.lang.ProcessBuilder as JProcBuilder

internal fun execAsync(config: ProcessBuilder): RunningProcess {

    val jvmProcessBuilder = JProcBuilder(config.command)
    val jvmRunningProcess = jvmProcessBuilder.start()

    val processControllerFacade: ProcessFacade = makeCompositImplementation(jvmRunningProcess)

    require(config.outputHandlingStrategy == OutputHandlingStrategy.Buffer)

    return RunningProcessImpl(config, jvmRunningProcess, processControllerFacade)
}

fun execAsync(config: ProcessBuilder.() -> Unit): RunningProcess = execAsync(processBuilder(config))
fun execAsync(commandFirst: String, vararg commandRest: String): RunningProcess = execAsync {
    command = listOf(commandFirst) + commandRest.toList()
}

suspend fun exec(config: ProcessBuilder.() -> Unit): Int = execAsync(processBuilder(config)).exitCode.await()
suspend fun exec(commandFirst: String, vararg commandRest: String) = exec {
    command = listOf(commandFirst) + commandRest.toList()
}

enum class OutputHandlingStrategy { Buffer, Drop }
enum class InputSourceStrategy { None }

sealed class ProcessEvent
data class StandardError(val line: String): ProcessEvent()
data class StandardOutput(val line: String): ProcessEvent()
data class ExitCode(val value: Int): ProcessEvent()

data class ProcessBuilder internal constructor(
        var inputProvidingStrategy: InputSourceStrategy = InputSourceStrategy.None,
        var outputHandlingStrategy: OutputHandlingStrategy = OutputHandlingStrategy.Buffer,
        var encoding: Charset = Charsets.UTF_8,
        var command: List<String> = emptyList(),
        var includeDescendantsInKill: Boolean = false
)
internal fun processBuilder(block: ProcessBuilder.() -> Unit): ProcessBuilder {
    val result = ProcessBuilder()
    result.block()
    require(result.command.any()) { "empty command: $result" }
    return result
}
