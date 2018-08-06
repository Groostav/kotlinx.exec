package groostav.kotlinx.exec
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.updateAndGet
import kotlin.coroutines.experimental.Continuation
import kotlin.coroutines.experimental.intrinsics.suspendCoroutineOrReturn

enum class ShutdownItem { ProcessJoin, ExitCodeJoin, AggregateChannel }

sealed class ShutdownEntry {
    abstract val item: ShutdownItem
    operator abstract fun component1(): ShutdownItem
    operator abstract fun component2(): Continuation<Unit>?

    data class Waiting(override val item: ShutdownItem, val continuation: Continuation<Unit>): ShutdownEntry()
    data class Pending(override val item: ShutdownItem): ShutdownEntry(){
        override fun component2(): Continuation<Unit>? = null
    }
}

class ShutdownZipper {

    //ordered map
    private val initial = listOf<ShutdownEntry>(
            ShutdownEntry.Pending(ShutdownItem.ExitCodeJoin),
            ShutdownEntry.Pending(ShutdownItem.AggregateChannel),
            ShutdownEntry.Pending(ShutdownItem.ProcessJoin)
    )

    private val elements = atomic(initial)

    suspend fun waitFor(item: ShutdownItem) = suspendCoroutineOrReturn<Unit> { continuation ->

        var readyDependants: List<Continuation<Unit>> = emptyList()

        val newState = elements.updateAndGet { elements ->

            val headEntry = elements.first()

            val remaining = if(headEntry.item == item) {
                require(headEntry is ShutdownEntry.Pending)
                elements.drop(1)

                readyDependants = elements.takeWhile { it is ShutdownEntry.Waiting }
                        .filterIsInstance<ShutdownEntry.Waiting>()
                        .map { it.continuation }

                elements.dropWhile { it !is ShutdownEntry.Pending }
            }
            else {
                elements.map { entry ->
                    when {
                        entry.item == item -> ShutdownEntry.Waiting(item, continuation)
                        else -> entry
                    }
                }
            }

            remaining
        }

        readyDependants.forEach { it.resume(Unit) }

        if (item !in newState.map { it.item }) return@suspendCoroutineOrReturn Unit
    }
}

