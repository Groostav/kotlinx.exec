package groostav.kotlinx.exec

//TODO: factor out safely


import kotlinx.coroutines.experimental.Deferred
import kotlinx.coroutines.experimental.channels.ReceiveChannel
import kotlinx.coroutines.experimental.channels.SendChannel
import org.zeroturnaround.process.Processes
import org.zeroturnaround.process.WindowsProcess
import java.nio.charset.Charset
import java.lang.ProcessBuilder as JProcBuilder

fun execAsync(config: ProcessBuilder): RunningProcess {

    val jvmProcessBuilder = JProcBuilder(config.command)
    val jvmRunningProcess = jvmProcessBuilder.start()
    val ztpidRunningProcess = (Processes.newPidProcess(jvmRunningProcess) as WindowsProcess).apply {
        isIncludeChildren = true
        isGracefulDestroyEnabled = true
    }

    require(config.outputHandlingStrategy == OutputHandlingStrategy.Buffer)

    return RunningProcessImpl(config, jvmRunningProcess, ztpidRunningProcess)
}

fun execAsync(vararg command: String): RunningProcess {
    val builder = processBuilder {
        this.command = command.toList()
    }
    return execAsync(builder)
}
fun execAsync(config: ProcessBuilder.() -> Unit): RunningProcess = execAsync(processBuilder(config))
suspend fun exec(config: ProcessBuilder.() -> Unit): Int = execAsync(processBuilder(config)).exitCode.await()
suspend fun exec(vararg command: String): Int {
    val builder = processBuilder {
        this.command = command.toList()
    }

    return execAsync(builder).exitCode.await()
}


enum class OutputHandlingStrategy { Buffer, Drop }
enum class InputSourceStrategy { None }

sealed class ProcessEvent
class StandardError(val line: String): ProcessEvent()
class StandardOutput(val line: String): ProcessEvent()
class ExitCode(val value: Int): ProcessEvent()


interface RunningProcess: SendChannel<String>, ReceiveChannel<ProcessEvent> {

    val standardOutput: ReceiveChannel<String>
    val standardError: ReceiveChannel<String>
    val standardInput: SendChannel<String>

    val exitCode: Deferred<Int>

    val processID: Int

    //suspends while cancelling gracefully
    suspend fun kill(gracefulTimeousMillis: Long? = 3_000)

    suspend fun join(): Unit
}

data class ProcessBuilder(
        var inputProvidingStrategy: InputSourceStrategy = InputSourceStrategy.None,
        var outputHandlingStrategy: OutputHandlingStrategy = OutputHandlingStrategy.Buffer,
        var encoding: Charset = Charsets.UTF_8,
        var command: List<String> = emptyList()
)
inline fun processBuilder(block: ProcessBuilder.() -> Unit): ProcessBuilder = ProcessBuilder().apply(block)
