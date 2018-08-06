package groostav.kotlinx.exec
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.updateAndGet
import kotlin.coroutines.experimental.Continuation
import kotlin.coroutines.experimental.intrinsics.suspendCoroutineOrReturn
import kotlin.coroutines.experimental.intrinsics.COROUTINE_SUSPENDED

enum class ShutdownItem { ProcessJoin, ExitCodeJoin, AggregateChannel }

class ShutdownEntry(val item: ShutdownItem, val continuation: Continuation<Unit>?)

class ShutdownZipper {

    //ordered map
    private val initial = listOf(
            ShutdownEntry(ShutdownItem.ExitCodeJoin, null),
            ShutdownEntry(ShutdownItem.AggregateChannel, null),
            ShutdownEntry(ShutdownItem.ProcessJoin, null)
    )

    private val elements = atomic(initial)

    suspend fun waitFor(item: ShutdownItem) = suspendCoroutineOrReturn<Unit> { continuation ->

        var readyDependants: List<Continuation<Unit>> = emptyList()

        val newState = elements.updateAndGet { elements ->

            val headEntry = elements.first()

            val remaining = if(headEntry.item == item) {
                require(headEntry.continuation == null)
                elements.drop(1)

                readyDependants = elements.takeWhile { it.continuation != null }
                        .map { it.continuation!! }

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

        readyDependants.forEach { it.resume(Unit) }

        return@suspendCoroutineOrReturn if (item !in newState.map { it.item }) Unit else COROUTINE_SUSPENDED
    }
}

