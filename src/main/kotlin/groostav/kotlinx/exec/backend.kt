package groostav.kotlinx.exec

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.*
import kotlinx.coroutines.experimental.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.experimental.selects.SelectClause1
import kotlinx.coroutines.experimental.selects.SelectClause2
import kotlinx.coroutines.experimental.selects.select
import kotlinx.coroutines.experimental.sync.Mutex
import kotlinx.coroutines.experimental.sync.withLock
import java.io.*
import java.nio.charset.Charset
import java.util.concurrent.CopyOnWriteArrayList

import java.lang.ProcessBuilder as JProcBuilder
import java.lang.Process as JProcess

import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit


internal class RunningProcessImpl(
        _config: ProcessBuilder,
        private val process: JProcess,
        private val processControlWrapper: ProcessFacade
): RunningProcess {

    private val config = _config.copy()

    override val processID: Int = processControlWrapper.pid.value

    //TODO, the docs require "prompt" attachment to these streams,
    // because of coroutines and the 'pump' implementation we might require a thread allocation
    private var _standardOutput: BroadcastChannel<Char> = process.inputStream.toPumpedReceiveChannel(config)
    private var _standardError: BroadcastChannel<Char> = process.errorStream.toPumpedReceiveChannel(config)
    private val _standardInput: SendChannel<Char> = process.outputStream.toSendChannel(config.encoding)
    private val inputLineLock = Mutex()

    override val standardOutput: ReceiveChannel<Char> by lazy { _standardOutput.openSubscription() }
    override val standardError: ReceiveChannel<Char> by lazy { _standardError.openSubscription() }
    override val standardInput: SendChannel<Char> by lazy { actor<Char> {
        consumeEach {
            inputLineLock.withLock {
                _standardInput.send(it)
            }
        }
        _standardInput.close()
    }}

    private val _exitCode: CompletableDeferred<Int> = CompletableDeferred<Int>().apply {
        processControlWrapper.addCompletionHandle().value { result -> complete(result) }
    }

    override val exitCode: Deferred<Int> = async<Int>(blockableThread) {
        try {
            _exitCode.await()
        }
        catch(ex: CancellationException){
            try {
                kill()
            }
            catch(innerEx: Exception){
                //todo: add suppressed exception is java 9.
            }
            throw ex
        }
    }


    override suspend fun kill(): Unit = withContext<Unit>(blockableThread){

        val gracefulTimeousMillis = config.gracefulTimeousMillis

        if(_exitCode.isCompleted) return@withContext

        try {

            if (gracefulTimeousMillis != null) {
                processControlWrapper.killGracefully(config.includeDescendantsInKill)
                withTimeoutOrNull(gracefulTimeousMillis, TimeUnit.MILLISECONDS) { _exitCode.join() }

                if (_exitCode.isCompleted) { return@withContext }
            }

            processControlWrapper.killForcefully(config.includeDescendantsInKill)
            _exitCode.join() //can this fail?
        }
        finally {
            _standardOutput.cancel()
            _standardError.cancel()
            _standardInput.close()
        }
    }

    override suspend fun join(): Unit = _exitCode.join()

    private val inputLines by lazy {
        actor<String> {
            consumeEach { nextLine ->
                inputLineLock.withLock {
                    nextLine.forEach { _standardInput.send(it) }
                    System.lineSeparator().forEach { _standardInput.send(it) }
                }
            }
        }
    }

    //SendChannel
    override val isClosedForSend: Boolean get() = inputLines.isClosedForSend
    override val isFull: Boolean get() = inputLines.isFull
    override val onSend: SelectClause2<String, SendChannel<String>> = inputLines.onSend
    override fun offer(element: String): Boolean = inputLines.offer(element)
    override suspend fun send(element: String) = inputLines.send(element)
    override fun close(cause: Throwable?) = inputLines.close(cause)

    private val aggregateChannel by lazy {
        produce<ProcessEvent> {

            val errorLines = _standardError.openSubscription().lines()
            val outputLines = _standardOutput.openSubscription().lines()

            while (isActive) {
                val next = select<ProcessEvent?> {
                    if (!errorLines.isClosedForReceive) errorLines.onReceiveOrNull { errorMessage ->
                        errorMessage?.let { StandardError(it) }
                    }
                    if (!outputLines.isClosedForReceive) outputLines.onReceiveOrNull { outputMessage ->
                        outputMessage?.let { StandardOutput(it) }
                    }
                    exitCode.onAwait { ExitCode(it) }
                }
                if (next == null) continue
                send(next)
                if (next is ExitCode) return@produce
            }
        }
    }

    //ReceiveChannel
    override val isClosedForReceive: Boolean get() = aggregateChannel.isClosedForReceive
    override val isEmpty: Boolean get() = aggregateChannel.isEmpty
    override val onReceive: SelectClause1<ProcessEvent> get() = aggregateChannel.onReceive
    override val onReceiveOrNull: SelectClause1<ProcessEvent?> get() = aggregateChannel.onReceiveOrNull
    override fun iterator(): ChannelIterator<ProcessEvent> = aggregateChannel.iterator()
    override fun poll(): ProcessEvent? = aggregateChannel.poll()
    override suspend fun receive(): ProcessEvent = aggregateChannel.receive()
    override suspend fun receiveOrNull(): ProcessEvent? = aggregateChannel.receiveOrNull()
    override fun cancel(cause: Throwable?): Boolean = aggregateChannel.cancel(cause)
}


internal val blockableThread = ThreadPoolExecutor(
        0,
        Integer.MAX_VALUE,
        100L,
        TimeUnit.MILLISECONDS,
        SynchronousQueue()
).asCoroutineDispatcher()

private fun InputStream.toPumpedReceiveChannel(config: ProcessBuilder): BroadcastChannel<Char> {

    val source = produce(capacity = UNLIMITED, context = blockableThread){
        val reader = BufferedReader(InputStreamReader(this@toPumpedReceiveChannel, config.encoding))

        while(isActive){
            val nextCodePoint = reader.read().takeUnless { it == -1 } ?: break
            val nextChar = nextCodePoint.toChar()
            send(nextChar)
        }
    }

    val result = BroadcastChannel<Char>(config.charBufferSize).apply {
        launch(Unconfined){
            source.consumeEach { send(it) }
            close()
        }
    }

    return result
}

//TODO: why isn't this part of kotlinx.coroutines already? Something they know I dont?
internal suspend fun ReceiveChannel<Char>.lines() = produce<String>{
    val buffer = StringBuilder(80)
    var lastWasSlashR = false

    for(nextChar in this@lines){

        when(nextChar){
            '\r' -> {
                val line = buffer.toString().also { buffer.setLength(0) }
                lastWasSlashR = true
                send(line)
            }
            '\n' -> {
                if( ! lastWasSlashR){
                    val line = buffer.toString().also { buffer.setLength(0) }
                    lastWasSlashR = false
                    send(line)
                }
                else {
                    lastWasSlashR = false
                }
            }
            else -> {
                buffer.append(nextChar)
                lastWasSlashR = false
            }
        }
    }
}

private fun OutputStream.toSendChannel(encoding: Charset = Charsets.UTF_8): SendChannel<Char> {
    return actor<Char>(blockableThread) {
        val writer = OutputStreamWriter(this@toSendChannel, encoding)

        consumeEach { nextChar ->

            try {
                writer.append(nextChar)
                if(nextChar == '\n') writer.flush()
            }
            catch (ex: FileNotFoundException) {
                //writer was closed, process was terminated.
                return@actor
            }
        }
    }
}
internal sealed class Maybe<out T> {
    abstract val value: T
}

internal data class Supported<out T>(override val value: T): Maybe<T>()
internal object Unsupported : Maybe<Nothing>() { override val value: Nothing get() = TODO() }

internal typealias ResultHandler = (Int) -> Unit
internal typealias ResultEventSource = (ResultHandler) -> Unit

internal interface ProcessFacade {

    val pid: Maybe<Int> get() = Unsupported
    fun killGracefully(includeDescendants: Boolean): Maybe<Unit> = Unsupported
    fun killForcefully(includeDescendants: Boolean): Maybe<Unit> = Unsupported
    fun addCompletionHandle(): Maybe<ResultEventSource> = Unsupported
}

internal infix fun ProcessFacade.thenTry(backup: ProcessFacade): ProcessFacade {

    fun flatten(facade: ProcessFacade): List<ProcessFacade> = when(facade){
        is CompositeProcessFacade -> facade.facades
        else -> listOf(facade)
    }

    return CompositeProcessFacade(flatten(this) + flatten(backup))
}

internal class CompositeProcessFacade(val facades: List<ProcessFacade>): ProcessFacade {

    override val pid: Maybe<Int> get() = firstSupported { it.pid }
    override fun killGracefully(includeDescendants: Boolean): Maybe<Unit> = firstSupported { it.killGracefully(includeDescendants) }
    override fun killForcefully(includeDescendants: Boolean): Maybe<Unit> = firstSupported { it.killForcefully(includeDescendants) }
    override fun addCompletionHandle(): Maybe<ResultEventSource> = firstSupported { it.addCompletionHandle() }

    private fun <R> firstSupported(call: (ProcessFacade) -> Maybe<R>): Maybe<R> {
        return facades.asSequence().map(call).firstOrNull { it != Unsupported }
                ?: throw UnsupportedOperationException("none of $facades supports $call")
    }
}

//TODO: dont like dependency on zero-turnaround, but its so well packaged...
//
// on windows: interestingly, they use a combination the cmd tools taskkill and wmic, and a reflection hack + JNA Win-Kernel32 call to manage the process
//   - note that oshi (https://github.com/oshi/oshi, EPL license) has some COM object support... why cant I just load wmi.dll from JNA?
// on linux: they dont support the deletion of children (???), and its pure shell commands (of course, since the shell is so nice)
// what about android or even IOS? *nix == BSD support means... what? is there even a use-case here?
//
// so I think cross-platform support is a grid of:
//                    windows           | osx | linux
// getPID(jvmProc)     jre9->kern32     |
// descendants(pid)    jre9->wmic       |  ?  |
// kill(pid)         taskkill           |  ?  |
// isAlive(pid)        wmic             |  ?  |
// join(pid)          jvm...?           |
//
// and you probably want to target jdk 6, so a third dimension might be jdk-9
//
// also, what can I steal from zero-turnarounds own process API? Its not bad, it uses a builder and it buffers _all_ standard output.
// clearly their consumption model is for invoking things like `ls`, not so much `ansys`.
//
// also, java 9's API is nice, but it doesnt provide kill mechanism
// http://www.baeldung.com/java-9-process-api

internal fun makeCompositImplementation(jvmRunningProcess: JProcess): ProcessFacade {

    //TODO: look at features, reflect on runtime, maybe use a table? whats the most concise way in kotlin to express a feature map?

    return ZeroTurnaroundProcessFacade(jvmRunningProcess) thenTry ThreadBlockingResult(jvmRunningProcess)
}


internal class BufferredBroadcastChannel<T>(
        val source: ReceiveChannel<T>,
        private val broadcast: BroadcastChannel<T>
): BroadcastChannel<T> by broadcast  {

    private val buffer = CopyOnWriteArrayList<T>() //gaurded by mutex
    private val mutex = Mutex()

    init {
        //TODO: does Unconfied get me what I want? who is the initial entry point?
        //TBH a callback intrface is actually what I want here:
        // source.onNext { nextElement -> .... }, where the lambda is called in the `send()`ers context.
        launch(blockableThread){
            source.consumeEach {
                mutex.withLock {
                    buffer += it
                }
                send(it)
            }
        }
    }

    private class Rest<T>(val subscription: ReceiveChannel<T>)

    override fun openSubscription() = produce<T> {

        var current = 0

        while(true) {

            val next: Any? /*T | Rest<ReceiveChannel<T>>*/ = mutex.withLock {
                val result = if (current < buffer.size) buffer[current] else Rest(broadcast.openSubscription())
                current += 1
                result
            }

            if(next is Rest<*>){
                val newSubscription = next.subscription as ReceiveChannel<T>
                newSubscription.consumeEach {
                    testing(it)
                    send(it)
                }
                //TODO: so it seems
                val x = 4;
                break
            }
            else {
                send(next as T)
            }
        }

        val y = 4;
    }

}


private fun testing(it: Any?){
    val x = 4;
}