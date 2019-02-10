package groostav.kotlinx.exec

import com.sun.jna.Platform
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.CharBuffer
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import kotlin.concurrent.thread
import kotlin.jvm.internal.FunctionReference
import kotlin.reflect.KClass
import kotlin.reflect.KFunction

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
    Windows, Unix
}

internal val JavaProcessOS: ProcessOS = when {
    Platform.isWindows() || Platform.isWindowsCE() -> ProcessOS.Windows
    Platform.isLinux() || Platform.isFreeBSD() || Platform.isOpenBSD() || Platform.isSolaris()-> ProcessOS.Unix
    else -> throw UnsupportedOperationException("unsupported OS:"+System.getProperty("os.name"))
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
internal data class Unsupported(
        val reason: String?,
        val functionName: String? = null,
        val typeName: String? = null,
        val src: Unsupported? = null
): Maybe<Nothing>() {
    override val value: Nothing get(){
        throw UnsupportedOperationException("cannot invoke ${header()}${traceMembers().joinToString("\n", "\n")}")
    }
}
internal val SupportedUnit = Supported(Unit)

internal val <T> Maybe<T>.valueOrNull: T? get() = when(this){
    is Supported -> value
    is Unsupported -> null
}

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


internal fun <T: Any> Sequence<Maybe<T>>.firstSupported(): T {
    val candidate = this.firstOrNull { it is Supported<*> }
    if(candidate != null) return candidate.value

    val problems = this.filterIsInstance<Unsupported>().toList()

    val message = problems
            .flatMap { listOf(it.header()) + it.traceMembers() }
            .joinToString("\n\t", "\n\t")

    val method = problems.map { it.functionName }.toSet().joinToString()

    // killAsync 'reason' because 'cause.reason'
    throw UnsupportedOperationException("$method is not supported:$message")
}

internal fun Unsupported.header() = "$typeName/$functionName:${reason?.let { " $it" } ?: ""}"
internal fun Unsupported.traceMembers(): List<String> {
    val members = generateSequence(src) { it.src }
    return members.map { it.run {"\t$typeName/$functionName:${reason?.let { " $it" } ?: ""}"} }.toList()
}


internal fun <H, R, F> Maybe<H>.supporting(call: F): Maybe<R>
        where H: Any, F: Function1<H, Maybe<R>>, F: KFunction<Maybe<R>>
        = mapAndAppendMetaIfAppropriate(call) { call(it) }

internal fun <H, P1, R, F> Maybe<H>.supporting(call: F, p1: P1): Maybe<R>
        where H: Any, F: Function2<H, P1, Maybe<R>>, F: KFunction<Maybe<R>>
        = mapAndAppendMetaIfAppropriate(call) { call(it, p1) }

internal fun <H, P1, P2, R, F> Maybe<H>.supporting(call: F, p1: P1, p2: P2): Maybe<R>
        where H: Any, F: Function3<H, P1, P2, Maybe<R>>, F: KFunction<Maybe<R>>
        = mapAndAppendMetaIfAppropriate(call) { call(it, p1, p2) }

internal fun <H, P1, P2, P3, R, F> Maybe<H>.supporting(call: F, p1: P1, p2: P2, p3: P3): Maybe<R>
        where H: Any, F: Function4<H, P1, P2, P3, Maybe<R>>, F: KFunction<Maybe<R>>
        = mapAndAppendMetaIfAppropriate(call) { call(it, p1, p2, p3) }

//a lot of this goes away if they give us this syntax Host::method(arg1, arg2)
// which binds arg1 and arg2 to p1 and p2.

private fun <T: Any, R> Maybe<T>.mapAndAppendMetaIfAppropriate(call: KFunction<Maybe<R>>, boundCall: (T) -> Maybe<R>): Maybe<R> {

    val result = if(this is Unsupported) Unsupported(null, src = this) else boundCall(value)

    return when(result){
        is Supported -> result
        is Unsupported -> {
            val typeName = when(this){
                is Unsupported -> ((call as? FunctionReference)?.owner as? KClass<*>)?.qualifiedName ?: "unknown"
                is Supported -> value::class.let { it.qualifiedName ?: "<anonymous>" }
            }
            result.copy(functionName = call.name, typeName = typeName.removePrefix("groostav.kotlinx.exec."))
        }
    }
}

internal inline fun <T> Try(block: () -> T) = try { block() } catch (e: Exception) { null }

// uhhh does this leak handles? if you attach your coroutine to this... does it hang on?
// done jobs have not listeners. No, this is not a leak.
object DONE_JOB: Job by GlobalScope.launch(CoroutineName("DONE_JOB"), block = {})

internal fun nfg(): Nothing = TODO()
internal fun nfg(message: String): Nothing = TODO(message)