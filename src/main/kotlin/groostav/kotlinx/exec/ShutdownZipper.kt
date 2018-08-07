package groostav.kotlinx.exec

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.experimental.Continuation
import kotlin.coroutines.experimental.intrinsics.suspendCoroutineOrReturn
import kotlin.coroutines.experimental.intrinsics.COROUTINE_SUSPENDED

class ShutdownZipper<T>(val initialValues: List<T>) {

    //TODO use optimistic locking scheme!
    private var values = initialValues.map { ShutdownEntry(it, arrayListOf()) }
    private val lock = ReentrantLock()

    val ordinal = initialValues.withIndex().associate { (index, item) -> item to index }
    @Volatile var index = 0

    private sealed class Result<out T> {
        data class ENQUEUED<T>(val nowReady: List<ShutdownEntry<T>>) : Result<T>()
        object ALREADY_COMPLETED : Result<Nothing>()
    }
    private class ShutdownEntry<T>(val item: T, val continuations: MutableList<Continuation<Unit>>)

    suspend fun waitFor(suiter: T) = suspendCoroutineOrReturn<Unit> { continuation ->

        trace { "wait for $suiter" }

        val change = lock.withLock {

            val head = values.getOrNull(index)

            val suiterOrdinal = ordinal.getValue(suiter)
            val headOrdinal = head?.let { ordinal.getValue(it.item) } ?: suiterOrdinal

            //TODO convert to more lock-free
            // first implementation: could replace index & head semantics with lock-free-linked-list,
            // call 'val head = peek' here,
            // and then... how do you removeWhile {} atomically?
            // keep the lock

            val result = when{
                head == null -> Result.ALREADY_COMPLETED
                head.item == suiter -> {
                    head.continuations += continuation
                    val completables = values.drop(index).takeWhile { it.continuations.any() }
                    index += completables.size
                    Result.ENQUEUED(nowReady = completables)
                }
                suiterOrdinal < headOrdinal -> Result.ALREADY_COMPLETED
                suiterOrdinal > headOrdinal -> {
                    values[suiterOrdinal].continuations += continuation
                    Result.ENQUEUED(nowReady = emptyList())
                }

                else -> TODO("index=$index, suiter=$suiter, value=$values")
            }

            result
        }

        return@suspendCoroutineOrReturn when(change){
            is Result.ENQUEUED -> {
                change.nowReady.flatMap { it.continuations }.forEach { it.resume(Unit) }
                COROUTINE_SUSPENDED
            }
            is Result.ALREADY_COMPLETED -> Unit
        }

    }
}

//
// class AnotherShutdownZipper<T>(val initialValues: List<T>) {
//
//    private val elements = AtomicReference(initialValues.map { ShutdownEntry(it, emptyList()) })
//
//    suspend fun waitFor(item: T) = suspendCoroutineOrReturn<Unit> { continuation ->
//
//        require(item in initialValues)
//
//        var readyDependants: List<ShutdownEntry<T>> = emptyList()
//
//        val elements = elements.updateAndGet { elements ->
//
//            val headEntry = elements.firstOrNull()
//
//            val remaining = when {
//                fail; //sorry, my brains gone, this thing is a pain in the ass.
//                // i got it green bar by using an extra flag, but im pretty sure that messes up the state,
//
//                item !in elements.map { it.item } -> {
//                    readyDependants = emptyList()
//                    elements
//                }
//                headEntry?.item == item -> {
//                    require(headEntry!!.continuations.isEmpty())
//                    readyDependants = emptyList()
//                    readyDependants += ShutdownEntry(item, listOf(continuation))
//                    readyDependants += elements.drop(1).takeWhile { it.continuations.any() }
//
//                    elements.drop(readyDependants.size) //could use sublist, but then we hang on to old continuations
//                }
//                else -> elements.map { entry ->
//                    readyDependants = emptyList()
//                    when (entry.item) {
//                        item -> ShutdownEntry(item, entry.continuations + continuation)
//                        else -> entry
//                    }
//                }
//            }
//
//            remaining
//        }
//
//        //note: this continuation is likely in this list.
//        readyDependants.flatMap { it.continuations }.forEach { it.resume(Unit) }
//
//        if(item !in elements.map { it.item }) Unit else COROUTINE_SUSPENDED
//    }
//}
//
//class BadShutdownZipper<T>(values: List<T>) {
//
//    private val elements = AtomicReference(values.map { ShutdownEntry(it, null) })
//    private val channel = RendezvousChannel<T>()
//
//    init {
//        launch {
//            var index = 0
//            for(element in channel){
//                val elements = elements.get()
//                while(true){
//                    val next = elements[index].continuations
//                    if(next == null) break
//                    next.resume(Unit)
//                    index += 1
//                }
//            }
//        }
//    }
//
//    suspend fun waitFor(item: T) = suspendCoroutineOrReturn<Unit> { continuation ->
//        elements.updateAndGet { elements ->
//            elements.map { if(it.item == item) ShutdownEntry(item, continuation) else it }
//        }
//        launch { channel.send(item) }
//        COROUTINE_SUSPENDED
//    }
//}

