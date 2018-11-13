package groostav.kotlinx.exec

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers.Unconfined
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.coroutineScope
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter


internal fun OutputStream.toSendChannel(config: ProcessBuilder): SendChannel<Char> {
    return config.scope.actor<Char>(Unconfined + CoroutineName("process.stdin")) {

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