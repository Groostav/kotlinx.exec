package groostav.kotlinx.exec

import kotlinx.coroutines.experimental.launch
import java.io.InputStream
import java.nio.charset.Charset

internal interface ProcessListenerProvider {

    val standardErrorEvent: Maybe<NewMessageChunkEventSource>
    val standardOutputEvent: Maybe<NewMessageChunkEventSource>
    val exitCodeEvent: Maybe<ResultEventSource>
}

typealias MessageHandler = (List<Char>) -> Unit
typealias NewMessageChunkEventSource = (MessageHandler) -> Unit

internal class ThreadBlockingListenerProvider(val process: Process, val config: ProcessBuilder): ProcessListenerProvider {

    private val standardError = EventSource<List<Char>>()
    private val standardOutput = EventSource<List<Char>>()
    private val exitCode = EventSource<Int>()

    override val standardErrorEvent by lazy {
        makeDedicatedStreamPump(process.errorStream, standardError, config.encoding)
        Supported<NewMessageChunkEventSource> { standardError.register(it) }
    }
    override val standardOutputEvent by lazy {
        makeDedicatedStreamPump(process.inputStream, standardOutput, config.encoding)
        Supported<NewMessageChunkEventSource> { standardOutput.register(it) }
    }
    override val exitCodeEvent by lazy {
        makeDedicatedExitCodePump(process, exitCode)
        Supported<ResultEventSource> { exitCode.register(it) }
    }
}

fun makeDedicatedExitCodePump(process: Process, eventSource: EventSource<Int>) = launch(blockableThread){
    val result = process.waitFor()

    trace { "calling process waitFor" }
    eventSource.fireEvent(result)
    eventSource.close()
}

private fun makeDedicatedStreamPump(stream: InputStream, eventSource: EventSource<List<Char>>, encoding: Charset) = launch(blockableThread) {
    val reader = stream.reader(encoding)
    val buffer = CharArray(1024)

    trace { "allocated buffer=1024 for pump"}

    while(true) {
        val readChars = reader.read(buffer)
        if(readChars == -1) break
        val nextChunk = buffer.asList().subList(0, readChars).toList()
        eventSource.fireEvent(nextChunk)
    }
    eventSource.close()

    trace { "eof" }
}