package groostav.kotlinx.exec

import kotlinx.coroutines.*
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.CharBuffer
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import kotlin.concurrent.thread

//internal val TRACE = java.lang.Boolean.getBoolean("kotlinx.exec.trace")
internal val TRACE = true

private val ThreadCreator = ThreadFactory { job -> thread(start = false, isDaemon = false, name = "", block = job::run) }
internal val ExecDispatcher = Executors.newSingleThreadScheduledExecutor(ThreadCreator).asCoroutineDispatcher()
internal val ProcessScope = GlobalScope + ExecDispatcher

internal inline fun trace(message: () -> String){
    if(TRACE){
        println(message())
    }
}

//TODO: this sorta ssumed we could decouple from JNA. Seems unlikely, so maybe just do Platform.isWindows() & Platform.isUnix()?
enum class ProcessOS {
    Windows, Unix;
}

internal val JavaProcessOS: ProcessOS = run {
    //this is the strategy from apache commons lang... I hate it, but they seem to think it works.
    //https://github.com/apache/commons-lang/blob/master/src/main/java/org/apache/commons/lang3/SystemUtils.java

    val name = System.getProperty("os.name").toLowerCase()

    when {
        name.startsWith("windows") -> ProcessOS.Windows
        name.startsWith("linux") || name.endsWith("bsd") -> ProcessOS.Unix
        else -> throw UnsupportedOperationException("")
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
internal sealed class Maybe<out T> {
    abstract val value: T
}
internal data class Supported<out T>(override val value: T): Maybe<T>()
internal data class Unsupported(val reason: String): Maybe<Nothing>() {
    val platform = "${System.getProperty("os.name")}-jre${System.getProperty("java.version")}"
    override val value: Nothing get() = throw UnsupportedOperationException("unsupported platform $platform")
}
internal val SupportedUnit = Supported(Unit)

internal class NamedTracingProcessReader private constructor(
        src: InputStream,
        val name: String,
        val config: ProcessConfiguration
): Reader() {

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

        fun forStandardError(process: java.lang.Process, pid: Int, config: ProcessConfiguration) =
                NamedTracingProcessReader(process.errorStream, "stderr-$pid", config)

        fun forStandardOutput(process: java.lang.Process, pid: Int, config: ProcessConfiguration) =
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

// uhhh does this leak handles? if you attach your coroutine to this... does it hang on?
// done jobs have not listeners. No, this is not a leak.
object DONE_JOB: Job by GlobalScope.launch(CoroutineName("DONE_JOB"), block = {})

internal fun nfg(): Nothing = TODO()
internal fun nfg(message: String): Nothing = TODO(message)