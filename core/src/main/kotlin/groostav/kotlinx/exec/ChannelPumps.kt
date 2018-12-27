package groostav.kotlinx.exec

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers.Unconfined
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.coroutineScope
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter

internal fun OutputStream.toSendChannel(config: ProcessBuilder, pid: Int): FlushableSendChannel<Char> {

    val writer = OutputStreamWriter(this@toSendChannel, config.encoding)
    val actual = GlobalScope.actor<Char>(Unconfined + CoroutineName("process.stdin")) {

        try {
            consumeEach { nextChar ->

                try {
                    writer.append(nextChar)
                    if (nextChar == config.inputFlushMarker) flush()
                }
                catch (ex: IOException) {
                    //writer was closed, process was terminated.
                    //TODO need a test to induce this, verify correctness.
                    return@actor
                }
            }
        }
        finally {
            writer.close()
        }
    }

    return object: FlushableSendChannel<Char>, SendChannel<Char> by actual {
        override fun flush() = writer.flush() //TODO what about exceptions?
        override fun toString() = "stdin-$pid"
    }
}

interface FlushableSendChannel<in T>: SendChannel<T> {
    /**
     * Abstraction over [OutputStream.flush] for channels.
     */
    fun flush(): Unit
}