package groostav.kotlinx.exec

import kotlinx.coroutines.experimental.channels.BroadcastChannel
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.channels.ReceiveChannel
import kotlinx.coroutines.experimental.channels.SendChannel
import kotlinx.coroutines.experimental.internal.LockFreeLinkedListHead
import kotlinx.coroutines.experimental.internal.LockFreeLinkedListNode
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.selects.SelectClause2
import java.util.*

//class SlowCharArrayListBroadcastChannel : BroadcastChannel<Char> {
//
//    class CharArrayList {
//
//        @Volatile var elements = CharArray(16)
//        @Volatile var size: Int = 0; private set
//        @Volatile private var version: Long = 0
//
//        fun add(char: Char){
//            val previousVersion = version
//            version += 1
//            ensureCapacity(size + 1)
//            elements[size++] = char
//
//            if(version != previousVersion+1) throw ConcurrentModificationException()
//        }
//
//        private fun ensureCapacity(minCapacity: Int){
//            val size = size
//            if (minCapacity - size > 0){
//                val newCapcity = (size + (size shr 1)).coerceAtLeast(minCapacity)
//                elements = Arrays.copyOf(elements, newCapcity)
//            }
//        }
//    }
//
//    var backingChannel = Channel<Char>().also { launch {
//        for(newChar in it){
//
//        }
//    }}
//    var data = CharArrayList()
//    var subs = LockFreeLinkedListHead()
//
//    override val isClosedForSend: Boolean get() = backingChannel.isClosedForSend
//    override val isFull: Boolean get() = backingChannel.isFull
//    override val onSend: SelectClause2<Char, SendChannel<Char>> get() = backingChannel.onSend
//    override fun close(cause: Throwable?): Boolean = backingChannel.close()
//    override fun offer(element: Char): Boolean = backingChannel.offer(element)
//    suspend override fun send(element: Char) = backingChannel.send(element)
//
//    override fun cancel(cause: Throwable?): Boolean {
//        TODO()
//    }
//
//    override fun openSubscription(): ReceiveChannel<Char> {
//        val sub = Subscriber()
//        subs.addLast(LockFreeLinkedListNode(sub))
//        return sub
//    }
//
//    inner class Subscriber(): ReceiveChannel<Char>{
//
//    }
//}