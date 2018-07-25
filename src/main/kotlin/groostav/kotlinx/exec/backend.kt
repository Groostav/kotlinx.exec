package groostav.kotlinx.exec

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.*
import kotlinx.coroutines.experimental.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.experimental.selects.SelectClause1
import kotlinx.coroutines.experimental.selects.SelectClause2
import kotlinx.coroutines.experimental.selects.select
import java.io.*
import java.lang.Runnable
import java.nio.charset.Charset

import java.lang.ProcessBuilder as JProcBuilder
import java.lang.Process as JProcess

import java.util.concurrent.Executors
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

    override val standardOutput: ReceiveChannel<String> = process.inputStream.toPumpedReceiveChannel(config.encoding)
    override val standardError: ReceiveChannel<String> = process.errorStream.toPumpedReceiveChannel(config.encoding)
    override val standardInput: SendChannel<String> = process.outputStream.toSendChannel(config.encoding)

    private val _exitCode: CompletableDeferred<Int> = CompletableDeferred<Int>().apply {
        processControlWrapper.addCompletionHandle().value { result -> complete(result) }
    }

    override val exitCode: Deferred<Int> = async<Int>(blockableThread) {
        try {
            _exitCode.await()
        }
        catch(ex: CancellationException){
            kill(null as Long?)
            throw ex
        }
        finally {
            (standardOutput as Job).join()
            (standardError as Job).join()
        }
    }

    override suspend fun kill(gracefulTimeousMillis: Long?): Unit = withContext<Unit>(blockableThread){

        if(_exitCode.isCompleted) return@withContext

        try {

            if (gracefulTimeousMillis != null) {
                processControlWrapper.killGracefully(config.includeDescendantsInKill)
                withTimeoutOrNull(gracefulTimeousMillis, TimeUnit.MILLISECONDS) { _exitCode.join() }

                if (_exitCode.isCompleted) {
                    return@withContext
                }
            }

            processControlWrapper.killForcefully(config.includeDescendantsInKill)
            _exitCode.join() //can this fail?
        }
        finally {
            standardOutput.cancel()
            standardError.cancel()
            standardInput.close()
        }
    }

    override suspend fun join(): Unit = _exitCode.join()


    //SendChannel
    override val isClosedForSend: Boolean get() = standardInput.isClosedForSend
    override val isFull: Boolean get() = standardInput.isFull
    override val onSend: SelectClause2<String, SendChannel<String>> = standardInput.onSend
    override fun offer(element: String): Boolean = standardInput.offer(element)
    override suspend fun send(element: String) = standardInput.send(element)
    //TODO this doesnt seem right... can channels be closed for send but open for receive?
    override fun close(cause: Throwable?) = standardInput.close(cause)

    //TODO: should we make standardError and standardOutput broadcast channels and pickup a subscription here?
    private val aggregateChannel = produce<ProcessEvent> {
        //TODO should standardError and standardOutput be subscription channels?
        while(isActive){
            val next = select<ProcessEvent?>{
                if( ! standardError.isClosedForReceive) standardError.onReceiveOrNull { errorMessage ->
                    errorMessage?.let { StandardError(it) }
                }
                if( ! standardOutput.isClosedForReceive) standardOutput.onReceiveOrNull { outputMessage ->
                    outputMessage?.let { StandardOutput(it) }
                }
                exitCode.onAwait { ExitCode(it) }
            }
            if(next == null) continue
            send(next)
            if(next is ExitCode) return@produce
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
    //TODO this doesnt seem right... we should probably shut the whole show down right? Can channels be closed for receive but open for send?
    override fun cancel(cause: Throwable?): Boolean = aggregateChannel.cancel()

}


internal val blockableThread = ThreadPoolExecutor(
        0,
        Integer.MAX_VALUE,
        100L,
        TimeUnit.MILLISECONDS,
        SynchronousQueue()
).asCoroutineDispatcher()

private fun InputStream.toPumpedReceiveChannel(encoding: Charset = Charsets.UTF_8): ReceiveChannel<String> {

    return produce(capacity = UNLIMITED, context = blockableThread){
        val reader = BufferedReader(InputStreamReader(this@toPumpedReceiveChannel, encoding))

        while(isActive){
            val line = reader.readLine() ?: break
            send(line)
        }
    }
}

private fun OutputStream.toSendChannel(encoding: Charset = Charsets.UTF_8): SendChannel<String> {
    return actor<String>(blockableThread) {
        val writer = OutputStreamWriter(this@toSendChannel, encoding)

        consumeEach { nextLine ->
            try {
                writer.appendln(nextLine)
                writer.flush()
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

