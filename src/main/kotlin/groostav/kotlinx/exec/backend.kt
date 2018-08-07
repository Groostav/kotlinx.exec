package groostav.kotlinx.exec

import kotlinx.coroutines.experimental.asCoroutineDispatcher
import kotlinx.coroutines.experimental.launch
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.CharBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.experimental.CoroutineContext

internal val TRACE = true

internal inline fun trace(message: () -> String){
    if(TRACE){
        println(message())
    }
}

enum class ProcessOS { Windows, Unix } //TODO: Solaris?

internal val JavaProcessOS: ProcessOS = run {
    when {
        //TODO: I dont want an external dep, but I dont have a process instance here, what do?
        true -> ProcessOS.Windows
        false -> ProcessOS.Unix
        else -> TODO()
    }
}

//https://github.com/openstreetmap/josm/blob/master/src/org/openstreetmap/josm/tools/Utils.java
internal val JavaVersion = run {
    var version = System.getProperty("java.version")
    if (version.startsWith("1.")) {
        version = version.substring(2)
    }
    // Allow these formats:
    // 1.8.0_72-ea, 9-ea, 9, 10, 9.0.1
    val dotPos = version.indexOf('.')
    val dashPos = version.indexOf('-')

    version.substring(0, if (dotPos > -1) dotPos else if (dashPos > -1) dashPos else version.length).toInt()
}

// return value wrapper to indicate support for the provided method
// (alternative to things like "UnsupportedOperationException" or "NoClassDefFoundError")
// main providers of classes returning 'Unsupported':
//   - OS specific features
//   - java version specific features
// could probably make this monadic...
internal sealed class Maybe<out T> {
    abstract val value: T
}
internal data class Supported<out T>(override val value: T): Maybe<T>()
internal object Unsupported : Maybe<Nothing>() { override val value: Nothing get() = TODO() }

internal typealias ResultHandler = (Int) -> Unit
internal typealias ResultEventSource = (ResultHandler) -> Unit

internal fun <T> supportedIf(condition: Boolean, resultIfTrue: () -> T): Maybe<T> = when(condition){
    true -> Supported(resultIfTrue())
    false -> Unsupported
}

internal class NamedTracingProcessReader private constructor(
        src: InputStream,
        val name: String,
        val config: ProcessBuilder
): Reader() {

    //TODO: there doesnt seem to be any way to control buffering here.
    //how can we stricly conform to buffer sizes from the user?
    val src = InputStreamReader(src, config.encoding)

    init {
        trace { "SOF on $this" }
    }

    override fun skip(n: Long): Long = src.skip(n)
    override fun ready(): Boolean = src.ready()
    override fun reset() = src.reset()
    override fun close() = src.close()
    override fun markSupported(): Boolean = src.markSupported()
    override fun mark(readAheadLimit: Int) = src.mark(readAheadLimit)
    override fun read(target: CharBuffer?): Int = src.read(target)

    override fun read(): Int =
            src.read().also { if(it == EOF_VALUE){ trace { "EOF on $this" } } }
    override fun read(cbuf: CharArray?): Int =
            src.read(cbuf).also { if(it == EOF_VALUE){ trace { "EOF on $this" } } }
    override fun read(cbuf: CharArray?, off: Int, len: Int): Int =
            src.read(cbuf, off, len).also { if(it == EOF_VALUE){ trace { "EOF on $this" } } }

    override fun toString() = name

    companion object {

        fun forStandardError(process: java.lang.Process, pid: Int, config: ProcessBuilder) =
                NamedTracingProcessReader(process.errorStream, "stderr-$pid", config)

        fun forStandardOutput(process: java.lang.Process, pid: Int, config: ProcessBuilder) =
                NamedTracingProcessReader(process.inputStream, "stdout-$pid", config)
    }
}

internal const val EOF_VALUE: Int = -1



internal fun <R, S> List<S>.firstSupporting(call: (S) -> Maybe<R>): R {
    val candidate = this.asSequence().map(call).filterIsInstance<Supported<R>>().firstOrNull()
            ?: throw UnsupportedOperationException("none of $this supports $call")

    return candidate.value
}

internal fun <R, S> List<S>.filterSupporting(call: (S) -> Maybe<R>): List<R> {
    return this.map { call(it) }
            .filterIsInstance<Supported<R>>()
            .map { it.value }
}


internal inline fun <T> Try(block: () -> T) = try { block() } catch (e: Exception) { null }

internal fun testing(x: Any?){
    val y = 4;
}