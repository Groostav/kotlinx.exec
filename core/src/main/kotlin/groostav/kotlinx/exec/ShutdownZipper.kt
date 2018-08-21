package groostav.kotlinx.exec

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.experimental.Continuation
import kotlin.coroutines.experimental.intrinsics.suspendCoroutineOrReturn
import kotlin.coroutines.experimental.intrinsics.COROUTINE_SUSPENDED

class ShutdownZipper<T>(val initialValues: List<T>) {

    private var values = initialValues.map { ShutdownEntry(it, arrayListOf()) }
    private val lock = ReentrantLock()

    private val ordinal = initialValues.withIndex().associate { (index, item) -> item to index }
    @Volatile var index = 0

    private sealed class Result<out T> {
        data class ENQUEUED<T>(val nowReady: List<ShutdownEntry<T>>) : Result<T>()
        object ALREADY_COMPLETED : Result<Nothing>()
    }
    private class ShutdownEntry<T>(val item: T, val continuations: MutableList<Continuation<Unit>>)

    suspend fun waitFor(suiter: T) = suspendCoroutineOrReturn<Unit> { continuation ->

        trace { "wait for $suiter" }

        val suiterOrdinal = ordinal.getValue(suiter)

        val change = lock.withLock {

            val head = values.getOrNull(index)
            val headOrdinal = head?.let { ordinal.getValue(it.item) } ?: suiterOrdinal

            val result = when{
                head == null -> Result.ALREADY_COMPLETED
                head.item == suiter -> {
                    head.continuations += continuation
                    val completables = values.subList(index, values.size).takeWhile { it.continuations.any() }
                    index += completables.size
                    Result.ENQUEUED(nowReady = completables)
                }
                suiterOrdinal < headOrdinal -> Result.ALREADY_COMPLETED
                suiterOrdinal > headOrdinal -> {
                    values[suiterOrdinal].continuations += continuation
                    Result.ENQUEUED(nowReady = emptyList())
                }

                else -> throw IllegalStateException("index=$index, suiter=$suiter, value=$values")
            }

            result
        }

        return@suspendCoroutineOrReturn when(change){
            is Result.ENQUEUED -> {
                // note: this coroutine might be in change.nowready.continuations,
                // but it is legal for us to resume a not-yet-suspended coroutine,
                // and its the only way I can think of to get 'fairness' semantics to
                // order-of-dispatch calls.
                change.nowReady.flatMap { it.continuations }.forEach { it.resume(Unit) }
                COROUTINE_SUSPENDED
            }
            is Result.ALREADY_COMPLETED -> Unit
        }

    }
}
