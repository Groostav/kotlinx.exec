package groostav.kotlinx.exec

import kotlinx.coroutines.experimental.CompletableDeferred
import kotlinx.coroutines.experimental.Deferred
import kotlinx.coroutines.experimental.channels.ReceiveChannel
import kotlinx.coroutines.experimental.launch

internal interface ProcessListenerProvider {

    val standardErrorEvent: Maybe<ReceiveChannel<Char>>
    val standardOutputEvent: Maybe<ReceiveChannel<Char>>
    val exitCodeEvent: Maybe<Deferred<Int>>
}

internal class ThreadBlockingListenerProvider(val process: Process, val pid: Int, val config: ProcessBuilder): ProcessListenerProvider {
    override val standardErrorEvent by lazy {
        Supported(process.errorStream.toPumpedReceiveChannel("stderr-$pid", config))
    }
    override val standardOutputEvent by lazy {
        Supported(process.inputStream.toPumpedReceiveChannel("stdout-$pid", config))
    }
    override val exitCodeEvent by lazy {
        val result = CompletableDeferred<Int>()
        launch(blockableThread){
            try { result.complete(process.waitFor()) } catch (ex: Exception) { result.completeExceptionally(ex) }
        }
        Supported(result)
    }
}
