package groostav.kotlinx.exec

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.ReceiveChannel
import java.lang.Process
import java.lang.Boolean.getBoolean


internal interface ProcessListenerProvider {

    val standardErrorChannel: Maybe<ReceiveChannel<Char>>// get() = Unsupported
    val standardOutputChannel: Maybe<ReceiveChannel<Char>>// get() = Unsupported
    val exitCodeDeferred: Maybe<Deferred<Int>>// get() = Unsupported

    interface Factory {
        fun create(process: Process, pid: Int, config: ProcessBuilder): ProcessListenerProvider
    }
}

internal val UseBlockableThreads = getBoolean("groostav.kotlinx.exec.UseBlockableThreads")

internal fun makeListenerProviderFactory(): ProcessListenerProvider.Factory {
    return when {
        UseBlockableThreads -> ThreadBlockingListenerProvider.also {
            ThreadBlockingListenerProvider.BlockableDispatcher.prestart(3)
        }
        else -> PollingListenerProvider
    }
}

