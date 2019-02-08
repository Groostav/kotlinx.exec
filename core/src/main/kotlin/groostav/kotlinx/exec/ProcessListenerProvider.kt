package groostav.kotlinx.exec

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.ReceiveChannel
import java.lang.Process
import java.lang.Boolean.getBoolean


internal interface ProcessListenerProvider {

    // note: these channels will be used even when the buffer size is set to zero.
    // at time of writing, it seems better so synchronize on stdout completing even if its not used.

    val standardErrorChannel: Maybe<ReceiveChannel<Char>>// get() = Unsupported
    val standardOutputChannel: Maybe<ReceiveChannel<Char>>// get() = Unsupported
    val exitCodeDeferred: Maybe<Deferred<Int>>// get() = Unsupported

    interface Factory {

        // TODO: I think this leaks: what if the user starts a hangingProcess(), then abandons is,
        // then the process object is off the graph, but any created threads for pumping will still be there,
        // continuing to pump the outputs even if nobody cares.
        // can we simply make Process a WeakRef or should we try to tie this into a scope with exceptions?
        fun create(process: Process, pid: Int, config: ProcessConfiguration): ProcessListenerProvider
    }
}

internal val UseBlockableThreads = getBoolean("kotlinx.exec.UseBlockableThreads")

internal fun makeListenerProviderFactory(): ProcessListenerProvider.Factory {
    return when {
        UseBlockableThreads -> ThreadBlockingListenerProvider.also {
            ThreadBlockingListenerProvider.BlockableDispatcher.prestart(3)
        }
        else -> PollingListenerProvider
    }
}

