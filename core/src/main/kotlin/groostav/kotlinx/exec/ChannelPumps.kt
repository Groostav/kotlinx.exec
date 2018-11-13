package groostav.kotlinx.exec

import kotlinx.coroutines.experimental.CoroutineName
import kotlinx.coroutines.experimental.Unconfined
import kotlinx.coroutines.experimental.channels.*
import kotlinx.coroutines.experimental.channels.Channel.Factory.UNLIMITED
import java.io.*
import kotlin.coroutines.experimental.CoroutineContext


internal fun OutputStream.toSendChannel(config: ProcessBuilder): SendChannel<Char> {
    return actor<Char>(Unconfined + CoroutineName("process.stdin")) {

        val writer = OutputStreamWriter(this@toSendChannel, config.encoding)

        try {
            consumeEach { nextChar ->

                try {
                    writer.append(nextChar)
                    if (nextChar == config.inputFlushMarker) writer.flush()
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
}