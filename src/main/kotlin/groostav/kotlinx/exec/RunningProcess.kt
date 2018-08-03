package groostav.kotlinx.exec

import kotlinx.coroutines.experimental.Deferred
import kotlinx.coroutines.experimental.channels.ChannelIterator
import kotlinx.coroutines.experimental.channels.ReceiveChannel
import kotlinx.coroutines.experimental.channels.SendChannel
import kotlinx.coroutines.experimental.selects.SelectClause1

/**
 * A concurrent proxy to an external operating system process.
 *
 * This class converts the otherwise difficult to manage java process primatives
 * into concurrently accessible values.
 *
 * It has two main modes:
 *
 * 1. two high-level line-by-line multiplexed channels: one SendChannel (for input)
 *    and one RecieveChannel (for output)
 * 2. a set of lower level primatives for direct access std-in, std-err, std-out,
 *    and the process exit code as a SendChannel, ReceiveChannels, and a Deferred.
 *
 * [execVoid] and [execAsync] are the most concise process-builder factories.
 */
interface RunningProcess: SendChannel<String>, ReceiveChannel<ProcessEvent> {

    //TODO: these should be broadcast channels, but I need dynamic size
    // and I want it backed by CharArray rather than Array<Characater>
    val standardOutput: ReceiveChannel<Char>
    val standardError: ReceiveChannel<Char>
    val standardInput: SendChannel<Char>

    val exitCode: Deferred<Int>

    val processID: Int

    //suspends while cancelling gracefully, then forcefully if that doesnt work
    suspend fun kill(): Unit
    suspend fun join(): Unit


    override val isClosedForReceive: Boolean
    override val isEmpty: Boolean
    override val onReceive: SelectClause1<ProcessEvent>
    override val onReceiveOrNull: SelectClause1<ProcessEvent?>
    override fun cancel(cause: Throwable?): Boolean
    override fun iterator(): ChannelIterator<ProcessEvent>
    override fun poll(): ProcessEvent?
    override suspend fun receive(): ProcessEvent
    override suspend fun receiveOrNull(): ProcessEvent?
}

sealed class ProcessEvent
data class StandardError(val line: String): ProcessEvent()
data class StandardOutput(val line: String): ProcessEvent()
data class ExitCode(val value: Int): ProcessEvent()