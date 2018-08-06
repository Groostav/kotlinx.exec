package groostav.kotlinx.exec

import kotlinx.coroutines.experimental.channels.*
import kotlinx.coroutines.experimental.channels.Channel.Factory.UNLIMITED
import java.io.*
import kotlin.coroutines.experimental.CoroutineContext



internal fun OutputStream.toSendChannel(config: ProcessBuilder): SendChannel<Char> {
    return actor<Char>(blockableThread) {
        val delimParts = config.delimiters.flatMap { it.toSet() }.toSet()
        val writer = OutputStreamWriter(this@toSendChannel, config.encoding)

        consumeEach { nextChar ->

            try {
                writer.append(nextChar)
                if(nextChar == config.inputFlushMarker) writer.flush()
            }
            catch (ex: FileNotFoundException) {
                //writer was closed, process was terminated.
                //TODO need a test to induce this, verify correctness.
                return@actor
            }
        }

        writer.close()
    }
}