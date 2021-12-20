package groostav.kotlinx.exec

import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.jvm.jvmName

internal data class CoroutineTracer internal constructor(
    val debugName: String,
    val traceElements: List<Exception>
): CoroutineContext.Element {

    constructor(name: String): this(name, emptyList())

    companion object Key: CoroutineContext.Key<CoroutineTracer>

    init {
        require(debugName.isNotBlank())
    }

    fun appendName(nameSuffix: String) = CoroutineTracer("$debugName/$nameSuffix", traceElements)

    inline fun trace(isErr: Boolean = false, crossinline messageSupplier: () -> String) {
        if(TRACE){
            (if(isErr) System.err else System.out).println("$debugName: ${messageSupplier()}")
        }
    }

    fun mark(markName: String): CoroutineTracer = copy(traceElements = traceElements + Exception(markName))

    //umm,
    // ok so, idiomatically I like this,
    // and it is the case that the exception will be saved and used later,
    // (making it a good fit for this objects read patterns)
    // but right now its CAS'd onto the object as a way to atomically indiciate 'wasCancelled'
    // and the trace object is here for debugging and not for any useful state information,
    // so I'll leave it to the caller to manage the cancellation exception.
//    fun onCancelled(cause: Throwable) = copy(cancellationEx = (cause as? Exception) ?: Exception(cause))

    fun makeMangledTrace(currentStack: Throwable? = null): List<StackTraceElement> {

        if(traceElements.isEmpty() && currentStack == null) {
            trace { "no exception data?" }
            return Exception().stackTrace.toList()
        }

        // old trace might have
        //
        // g.k.e.A
        // g.k.e.B
        // kotlinx.builder
        // org.junit
        //
        // new trace has:
        //
        // g.k.e.C
        // g.k.e.D
        // kotlinx.builder
        // org.junit

        // want
        // g.k.e.C
        // g.k.e.D
        // MAYBE_A_NOTE_ABOUT_MAGIC_HERE
        // g.k.e.A
        // g.k.e.B
        // kotlinx.builder
        // org.junit

        val traceElements: List<Throwable> = if(currentStack != null)
            traceElements + currentStack else
            traceElements

        val result = traceElements[0].stackTrace.toMutableList()

        for(exceptionIndex in 1 until traceElements.size){

            val oldEx = traceElements[exceptionIndex - 1]
            val oldTrace = oldEx.stackTrace
            val newEx = traceElements[exceptionIndex]
            val newTrace = newEx.stackTrace

            var uselessTrailingElementCount = 0

            while(uselessTrailingElementCount < oldTrace.size && uselessTrailingElementCount < newTrace.size
                && oldTrace[oldTrace.lastIndex - uselessTrailingElementCount] == newTrace[newTrace.lastIndex - uselessTrailingElementCount]
            ){
                uselessTrailingElementCount += 1
            }

            val newUsefulElements = newTrace.take(newTrace.size - uselessTrailingElementCount)

            result.add(0, makeFrame(oldEx.message ?: "unknown"))
            result.addAll(0, newUsefulElements)
        }

        return result.toList()
    }

    fun makeFrame(name: String) = StackTraceElement(CoroutineTracer::class.jvmName, "ASYNC_RECOVERY_FOR_${name.toUpperCase()}", null, 0)

    override val key: CoroutineContext.Key<CoroutineTracer> = Key
}