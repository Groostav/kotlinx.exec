package groostav.kotlinx.exec

import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.experimental.Continuation
import kotlin.coroutines.experimental.intrinsics.suspendCoroutineOrReturn
import kotlin.coroutines.experimental.intrinsics.COROUTINE_SUSPENDED

private class ShutdownEntry<T>(val item: T, val continuations: List<Continuation<Unit>>)

class ShutdownZipper<T>(val initialValues: List<T>) {

    val state = AtomicReference(ZipSet(initialValues))

    suspend fun waitFor(item: T) = suspendCoroutineOrReturn<Unit> { continuation ->

        var success: Boolean = false
        var resumables: List<Continuation<Unit>> = emptyList()

        state.getAndUpdate {
            val (newState, attemptResumables, attemptSuccess) = it.remove(item)
            success = attemptSuccess
            resumables = attemptResumables
            newState
        }

        when {
            ! success -> Unit
            else -> {
                resumables.forEach { it.resume(Unit) }
                COROUTINE_SUSPENDED
            }
        }

    }


    class ZipSet<T> (initialValues: List<T>) {

        private val currentIndex: Int = 0
        private val table = initialValues.map { ShutdownEntry(it, emptyList()) }

        fun remove(item: T, continuation: Continuation<Unit>): Triple<ZipSet<T>, List<Continuation<Unit>>, Boolean> {

            val head = table[currentIndex]

            when {
                head.item == item -> {
                    val resumables = head.continuations + continuation
                    return Triple()
                }
            }


            TODO("currentIndex += 1")
        }

        companion object {
            operator fun <T> invoke(): ZipSet<T> = TODO()
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

