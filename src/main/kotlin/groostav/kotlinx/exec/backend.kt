package groostav.kotlinx.exec

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.*
import kotlinx.coroutines.experimental.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.experimental.selects.SelectClause1
import kotlinx.coroutines.experimental.selects.SelectClause2
import kotlinx.coroutines.experimental.selects.select
import org.zeroturnaround.process.PidProcess
import java.io.*
import java.nio.charset.Charset
import java.lang.ProcessBuilder as JProcBuilder
import java.lang.Process as JProcess

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit


internal class RunningProcessImpl(
        _config: ProcessBuilder,
        private val process: JProcess,
        private val processControlWrapper: PidProcess
): RunningProcess {

    private val config = _config.copy()

    override val processID: Int = processControlWrapper.pid

    override val standardOutput: ReceiveChannel<String> = process.inputStream.toPumpedReceiveChannel(config.encoding)
    override val standardError: ReceiveChannel<String> = process.errorStream.toPumpedReceiveChannel(config.encoding)
    override val standardInput: SendChannel<String> = process.outputStream.toSendChannel(config.encoding)

    private val _exitCode: Deferred<Int> = async<Int>(blockableThread){
        process.waitFor()
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

    override fun close(cause: Throwable?) = standardInput.close(cause)
    override fun cancel(cause: Throwable?): Boolean = aggregateChannel.cancel()

    override suspend fun kill(gracefulTimeousMillis: Long?): Unit = withContext<Unit>(blockableThread){

        if(_exitCode.isCompleted) return@withContext

        try {

            if (gracefulTimeousMillis != null) {
                processControlWrapper.destroyGracefully()
                withTimeoutOrNull(gracefulTimeousMillis, TimeUnit.MILLISECONDS) { _exitCode.join() }

                if (_exitCode.isCompleted) {
                    return@withContext
                }
            }

            processControlWrapper.destroyForcefully()
            _exitCode.join() //can this fail?
        }
        finally {
            standardOutput.cancel()
            standardError.cancel()
            standardInput.close()
        }
    }

    override suspend fun join() = _exitCode.join()


    //SendChannel
    override val isClosedForSend: Boolean get() = standardInput.isClosedForSend
    override val isFull: Boolean get() = standardInput.isFull
    override val onSend: SelectClause2<String, SendChannel<String>> = standardInput.onSend
    override fun offer(element: String): Boolean = standardInput.offer(element)
    override suspend fun send(element: String) = standardInput.send(element)

    private val aggregateChannel = produce<ProcessEvent> {
        while(isActive){
            val next = select<ProcessEvent>{
                if( ! standardError.isClosedForReceive) standardError.onReceive { StandardError(it) }
                if( ! standardOutput.isClosedForReceive) standardOutput.onReceive { StandardOutput(it) }
                exitCode.onAwait { ExitCode(it) }
                //todo this is a race: exitCode might show up before the standard output is finished.
            }
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

}

//TODO: keep alive time of 60 seconds on non-daemon threads isn't acceptable.
private val blockableThread = Executors.newCachedThreadPool().asCoroutineDispatcher()


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
            }
            catch (ex: FileNotFoundException) {
                //writer was closed, process was terminated.
                return@actor
            }
        }
    }
}