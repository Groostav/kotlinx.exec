package groostav.kotlinx.exec

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.CancellationException
import kotlinx.coroutines.experimental.channels.*
import kotlinx.coroutines.experimental.selects.SelectClause1
import kotlinx.coroutines.experimental.selects.SelectClause2
import kotlinx.coroutines.experimental.selects.select
import kotlinx.coroutines.experimental.sync.Mutex
import kotlinx.coroutines.experimental.sync.withLock
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