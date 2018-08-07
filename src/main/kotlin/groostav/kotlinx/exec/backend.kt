package groostav.kotlinx.exec

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.CancellationException
import kotlinx.coroutines.experimental.channels.*
import kotlinx.coroutines.experimental.selects.SelectClause1
import kotlinx.coroutines.experimental.selects.SelectClause2
import kotlinx.coroutines.experimental.selects.select
import kotlinx.coroutines.experimental.sync.Mutex
import kotlinx.coroutines.experimental.sync.withLock
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.CharBuffer
import java.util.*
import java.util.concurrent.*
import kotlin.coroutines.experimental.CoroutineContext

import java.lang.ProcessBuilder as JProcBuilder
import java.lang.Process as JProcess

internal val TRACE = true

internal inline fun trace(message: () -> String){
    if(TRACE){
        println(message())
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

object BlockableDispatcher: CoroutineContext by ThreadPoolExecutor(
        0,
        Integer.MAX_VALUE,
        100L,
        TimeUnit.MILLISECONDS,
        SynchronousQueue()
).asCoroutineDispatcher(){

    // hack to avoid late thread allocation, consider jvm process documentation
    //
    // >Because some native platforms only provide limited buffer size for standard input and output streams,
    // >failure to promptly write the input stream or read the output stream of the subprocess
    // >may cause the subprocess to block, or even deadlock."
    //
    // because we're allocating threads to 'pump' those streams,
    // the thread-allocation time might not be 'prompt' enough.
    // so we'll use a hack to make sure 2 threads exist such that when we dispatch jobs to this pool,
    // the jobs will be subitted to a pool with 2 idle threads.
    //
    // TODO: how can this be tested? Can we find a place where not prestarting results in data being lost?
    // what about a microbenchmark?
    internal fun prestart(jobs: Int){

        trace { "prestarting $jobs on $this, possible deadlock..." }

        val latch = CountDownLatch(jobs)
        for(jobId in 1 .. jobs){
            launch(this) { latch.countDown() }
        }

        latch.await()

        trace { "prestarted $jobs threads on $this" }
    }
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
