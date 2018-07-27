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

//TODO: regarding ZeroTurnarounds own "run this and get me a list of std-out"  style java builder,
// should we add a third method here to cover that same use case? Simply suspend until all output is availabe,
// and return it as a list of lines?

enum class OutputHandlingStrategy { Buffer, Drop }
enum class InputSourceStrategy { None }

//TODO: this seems excessive, defaulted on `exec` and `execAsync` would likely do the job.
data class ProcessBuilder internal constructor(
        var inputProvidingStrategy: InputSourceStrategy = InputSourceStrategy.None,
        var outputHandlingStrategy: OutputHandlingStrategy = OutputHandlingStrategy.Buffer,
        var encoding: Charset = Charsets.UTF_8,
        var command: List<String> = emptyList(),
        var includeDescendantsInKill: Boolean = false,
        var gracefulTimeousMillis: Long = 1500L,
        var charBufferSize: Int = 2048

        //TODO handling of non-zero exit codes:
        // most libs throw exceptions for non-zero exit codes... I could `exitCode.completeExceptionlly` there... make that configurable here?
        //    -> maybe even buffer a couple lines from standard-error to throw with the exception?
        // this is most useful for 'fire-and-forget' (mutable) processes (mkdir, curl, etc),
        // where the user isnt liklely to do anything with the exit value.
        // on the other hand, why return it at all if you throw an exception when its not zero?
)
internal fun processBuilder(block: ProcessBuilder.() -> Unit): ProcessBuilder {
    val result = ProcessBuilder()
    result.block()
    require(result.command.any()) { "empty command: $result" }
    return result //defensive copy?
}
