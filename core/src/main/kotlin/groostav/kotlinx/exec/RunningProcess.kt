package groostav.kotlinx.exec

import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector

interface RunningProcess: Flow<ProcessEvent> {

    val processID: Long

    fun sendLine(input: String)

    // killing a process does not cancel it
    // but cancelling a process does kill it
    suspend fun kill(obtrudeExitCode: Int? = null): Unit

    // oook, so what about cancel()
    // is it the case that all implementors of Job() are on the hook for a cancellation behaviour?
    // even if its not on the return object?
//    public fun cancel(cause: CancellationException? = null)

    fun start(): Boolean
    suspend fun await(): Int?
    suspend fun join(): Unit

    val isCompleted: Boolean

    // oook so im really torn.
    // I actually think that
    suspend fun receive(): ProcessEvent
    suspend fun receiveCatching(): ChannelResult<ProcessEvent>

    @OptIn(InternalCoroutinesApi::class)
    override suspend fun collect(collector: FlowCollector<ProcessEvent>)
}

sealed class ProcessEvent {
    abstract val formattedMessage: String
}
data class StandardOutputMessage(val line: String): ProcessEvent() {
    override val formattedMessage get() = line
}
data class StandardErrorMessage(val line: String): ProcessEvent() {
    override val formattedMessage get() = "ERROR: $line"
}
data class ExitCode(val code: Int): ProcessEvent() {
    override val formattedMessage: String get() = "Process finished with exit code $code"
}

class InvalidExitCodeException(
    val exitCode: Int,
    val expectedExitCodes: Set<Int>?,
    val command: List<String>,
    val recentStandardErrorLines: List<String>,
    message: String
): RuntimeException(message)

class ProcessKilledException(message: String, cause: Exception? = null): RuntimeException(message, cause)