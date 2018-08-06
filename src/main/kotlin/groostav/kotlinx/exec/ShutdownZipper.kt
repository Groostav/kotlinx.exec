package groostav.kotlinx.exec
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.experimental.Continuation
import kotlin.coroutines.experimental.intrinsics.suspendCoroutineOrReturn
import kotlin.coroutines.experimental.intrinsics.COROUTINE_SUSPENDED


private class ShutdownEntry<T>(val item: T, val continuation: Continuation<Unit>?)

class ShutdownZipper<T>(values: List<T>) {

    private val elements = AtomicReference(values.map { ShutdownEntry(it, null) })

    fail; //NFG, because there is a race between the entering coroutine and
    // the coroutines that are resumed by it.
    suspend fun waitFor(item: T) = suspendCoroutineOrReturn<Unit> { continuation ->

        var readyDependants: List<ShutdownEntry<T>> = emptyList()

        val newState = elements.updateAndGet { elements ->

            val headEntry = elements.first()

            val remaining = if(headEntry.item == item) {
                require(headEntry.continuation == null)
                val elements = elements.drop(1)

                readyDependants = elements.takeWhile { it.continuation != null }

                elements.drop(readyDependants.size) //could use sublist, but then we hang on to old continuations
            }
            else {
                elements.map { entry ->
                    when (entry.item) {
                        item -> ShutdownEntry(item, continuation)
                        else -> entry
                    }
                }
            }

            remaining
        }

        readyDependants.forEach { it.continuation!!.resume(Unit) }

        val result = if (item !in newState.map { it.item }) Unit else COROUTINE_SUSPENDED

        result;
    }
}

