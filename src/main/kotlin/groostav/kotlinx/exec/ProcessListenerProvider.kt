package groostav.kotlinx.exec

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.ConflatedBroadcastChannel
import kotlinx.coroutines.experimental.channels.ReceiveChannel
import kotlinx.coroutines.experimental.channels.produce
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.CharBuffer
import kotlin.coroutines.experimental.CoroutineContext

internal interface ProcessListenerProvider {

    val standardErrorChannel: Maybe<ReceiveChannel<Char>> get() = Unsupported
    val standardOutputChannel: Maybe<ReceiveChannel<Char>> get() = Unsupported
    val exitCodeDeferred: Maybe<Deferred<Int>> get() = Unsupported
}


internal fun makeListenerProvider(jvmRunningProcess: Process, pid: Int, config: ProcessBuilder): ProcessListenerProvider {
    return PollingListenerProvider(jvmRunningProcess, pid, config)
//    return ThreadBlockingListenerProvider(jvmRunningProcess, pid, config)
}

