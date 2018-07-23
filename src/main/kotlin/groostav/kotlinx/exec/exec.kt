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

    //TODO: dont like dependency on zero-turnaround, but its so well packaged...
    //
    // on windows: interestingly, they use a combination the cmd tools taskkill and wmic, and a reflection hack + JNA Win-Kernel32 call to manage the process
    //   - note that oshi (https://github.com/oshi/oshi, EPL license) has some COM object support... why cant I just load wmi.dll from JNA?
    // on linux: they dont support the deletion of children (???), and its pure shell commands (of course, since the shell is so nice)
    // what about android or even IOS? *nix == BSD support means... what? is there even a use-case here?
    //
    // so I think cross-platform support is a grid of:
    //                    windows | osx | linux | solaris | *nix?
    // getPID(jvmProc)     kern32 |  ?  |   ?   |    ?    |   ?
    // descendants(pid)    wmic   |  ?  |   ?   |    ?    |   ?
    // kill(pid)         taskkill |  ?  |   ?   |    ?    |   ?
    // isAlive(pid)        wmic   |  ?  |   ?   |    ?    |   ?
    // join(pid)          jvm...? |
    //
    // and you probably want to target jdk 6, so a third dimension might be jdk-9
    //
    // also, what can I steal from zero-turnarounds own process API? Its not bad, it uses a builder and it buffers _all_ standard output.
    // clearly their consumption model is for invoking things like `ls`, not so much `ansys`.
    //
    // also, java 9's API is nice, but it doesnt provide kill mechanism
    // http://www.baeldung.com/java-9-process-api
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
