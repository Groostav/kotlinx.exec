package groostav.kotlinx.exec

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.selects.SelectClause1
import kotlinx.coroutines.selects.SelectClause2
import kotlinx.coroutines.selects.SelectInstance
import java.lang.IllegalStateException
import java.util.*
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.suspendCoroutine

@Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE") //renames a couple of the param names for SendChannel & ReceiveChannel
/**
 * A concurrent proxy to an external operating system process.
 *
 * This class converts the otherwise difficult to manage java process primatives
 * into concurrently accessible values.
 *
 * It has two main modes:
 *
 * 1. two high-level line-by-line channels: one SendChannel (for input)
 *    and one RecieveChannel (for output)
 * 2. a set of lower level channels for character-by-character access to
 *    std-in, std-err, std-out, and the process exit code as a
 *    [SendChannel], two [ReceiveChannel]s, and a [Deferred] respectively.
 *
 * This interface was designed for use where some level of parallelism is required,
 * for example: when output messages must be read and processed as they are
 * emitted by the child process. If you have no such requirement for parallelism
 * it will likely be easier to use the [exec] or [execVoid] builders, which
 * provide sequential access to the same values, suspending until the sub-process
 * terminates.
 *
 * The receive channel implemented by this object is known as the aggregate channel,
 * and represents a multiplexed set of outputs from this process, including:
 * - standard error messages
 * - standard output messages
 * - the processes exit value
 * emitted as [ProcessEvent] instances.
 *
 * [execVoid] and [execAsync] are the most concise process-builder factories.
 */
interface RunningProcess: SendChannel<String>, ReceiveChannel<ProcessEvent>, Deferred<Int> {

    val standardOutput: ReceiveChannel<Char>
    val standardError: ReceiveChannel<Char>
    val standardInput: SendChannel<Char>

    /**
     * The exit code of the process, or [InvalidExitValueException] if configured
     *
     * If the process exits normally, and the process exit code is one of
     * [ProcessBuilder.expectedOutputCodes], then this value will be completed
     * with the exit code provided by the child process. If the process exits
     * with a code that is _not_ in the `expectedOutputCodes`, it will throw
     * an [InvalidExitValueException].
     *
     * Cancellation of this deferred will kill the backing process.
     */
    override suspend fun await(): Int

    /**
     * Ths process ID associated with this process.
     *
     * If the process has not yet been started, this method will throw [IllegalStateException].
     */
    val processID: Int

    /**
     * kills the process
     *
     * This method attempts to kill the process gracefully with an interrupt signal (SIG_INT)
     * in accordance with the settings in [ProcessBuilder.gracefulTimeoutMillis], then, if that fails,
     * uses a more aggressive kill signal (SIG_KILL) to end the process, suspending until the process
     * is terminated.
     *
     * Once this method returns, all outputs will be closed.
     */
    suspend fun kill(): Unit


    override val isClosedForReceive: Boolean
    override val isEmpty: Boolean
    override val onReceive: SelectClause1<ProcessEvent>
    override val onReceiveOrNull: SelectClause1<ProcessEvent?>
    override fun iterator(): ChannelIterator<ProcessEvent>
    override fun poll(): ProcessEvent?
    override suspend fun receive(): ProcessEvent
    override suspend fun receiveOrNull(): ProcessEvent?
    /**
     * kills the running process and drops any further not-yet-processed output
     * from standard-output, standard-error, or the aggregate channel
     *
     * Note, if you intended to mute the output channel without killing the process,
     * consider setting [ProcessBuilder.standardOutputBufferCharCount],
     * [ProcessBuilder.standardErrorBufferCharCount] and [ProcessBuilder.aggregateOutputBufferLineCount]
     * to zero.
     */
    override fun cancel(): Unit { cancel(null) }
    override fun cancel0(): Boolean = cancel(null)
    override fun cancel(cause: Throwable?): Boolean

    override val isClosedForSend: Boolean
    override val isFull: Boolean
    override val onSend: SelectClause2<String, SendChannel<String>>
    override fun offer(messageLine: String): Boolean
    override suspend fun send(messageLine: String)
    /**
     * sends the end-of-input signal to the input stream,
     * and closes the input channel.
     *
     * This does not directly terminate the process
     *
     * The character based input channel will also be closed.
     */
    override fun close(cause: Throwable?): Boolean
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

typealias FlushCommand = Unit

class ProcessChannels(
        val name: String,
        val stdin: Channel<Char> = Channel(Channel.RENDEZVOUS),
        val stdout: SimpleInlineMulticaster<Char> = SimpleInlineMulticaster("$name-stdout"),
        val stderr: SimpleInlineMulticaster<Char> = SimpleInlineMulticaster("$name-stderr"),
        val flush: Channel<FlushCommand> = Channel(Channel.RENDEZVOUS)
)

@InternalCoroutinesApi internal class ExecCoroutine(
        private val config: ProcessBuilder,
        parentContext: CoroutineContext,
        override val standardInput: SendChannel<Char>,
        override val standardOutput: ReceiveChannel<Char>,
        override val standardError: ReceiveChannel<Char>,
        private val aggregateChannel: Channel<ProcessEvent>,
        inputLines: SendChannel<String>,
        private val pidGen: ProcessIDGenerator
):
        AbstractCoroutine<Int>(parentContext + makeName(config), true),
        RunningProcess,
        SelectClause1<Int>,
        ReceiveChannel<ProcessEvent> by aggregateChannel,
        SendChannel<String> by inputLines
{
    internal var process: Process? = null
    internal val recentErrorOutput = CircularArrayQueue<String>(config.linesForExceptionError)

    override val processID: Int get() = process?.let { pidGen.findPID(it) } ?: throw IllegalStateException()

    override suspend fun kill() {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun cancel(): Unit {
        cancel(null)
    }

    override fun onCancellation(cause: Throwable?) {
        when(cause){
            null -> {} //completed normally
            is CancellationException -> {  } // cancelled --killOnceWithoutSync?
            else -> {  } // failed --killOnceWithoutSync?
        }
    }

    override fun cancel0(): Boolean = cancel(null)

    override fun cancel(cause: Throwable?): Boolean {
        val wasCancelled: Boolean = true
        if (wasCancelled) super<AbstractCoroutine<Int>>.cancel(cause) // cancel the job
        return wasCancelled
    }

    override fun onStart() {
        require(process != null)
        require(processID != 0)
        trace { "onStart ${config.command.first()} with PID=$processID" }
    }

    internal suspend fun onProcessEvent(event: ProcessEvent){
        aggregateChannel.send(event)
    }



    //regarding cancellation:
    // problem: our cancellation is long-running.
    // [potential] solution: attach an unstarted job as a child.
    //                       override `onCancellation()` to call that job
    //                       that job an atomic (non-cancellable) impl of killOnceWITHSync

    //other things:
    // - error history
    // - PID -- getter that throws illegal state exception
    // - kill with sync, kill without sync.
    // - input lines, needs an actor.

    override val cancelsParent: Boolean get() = true

    //regarding subclassing this obnoxious internal methods:
    // see if maybe you can find the mangled generated access method,
    // something like super.internal$getCompletedInternal, that prevents you from forking this.
    //
    // then, I think we can take this, you'll need to call `process.start()` somewhere.
    private val _methods = this::class.java.methods

    private val _getCompleted = _methods.single { it.name == "getCompletedInternal\$kotlinx_coroutines_core" }
    override fun getCompleted(): Int = _getCompleted(this) as Int

    private val _await = _methods.single { it.name == "awaitInternal\$kotlinx_coroutines_core" }
    override suspend fun await(): Int = suspendCoroutine { _await(this, it) }

    override val onAwait: SelectClause1<Int> get() = this

    private val _registerSelectClause1 = _methods.single { it.name == "registerSelectClause1Internal\$kotlinx_coroutines_core" }
    override fun <R> registerSelectClause1(select: SelectInstance<R>, block: suspend (Int) -> R): Unit = _registerSelectClause1(this, select, block) as Unit

    companion object {
        fun makeName(config: ProcessBuilder) = CoroutineName("exec ${config.command.joinToString()}")
    }
}