package groostav.kotlinx.exec

import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.Unconfined
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.channels.ReceiveChannel
import kotlinx.coroutines.experimental.channels.RendezvousChannel
import kotlinx.coroutines.experimental.channels.consumeEach
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.selects.select
import java.nio.channels.ClosedChannelException
import java.util.concurrent.CopyOnWriteArrayList

// the express purpose of this object is to block on send,
// adhere to all back-pressure provided by any of the subscribers!
// in this way we pass on any problems back up to source!
class SimpleInlineMulticaster<T>(val source: ReceiveChannel<T>) {

    private var subs: MutableList<Channel<T>>? = CopyOnWriteArrayList<Channel<T>>()
    private val sourceJob: Job

    init {
        sourceJob = launch(Unconfined) {
            val subs = subs!!
            try {
                trace { "started ${this@SimpleInlineMulticaster}" }
                source.consumeEach { next ->
                    for (sub in subs) {
                        sub.send(next)
                        // apply back-pressure from _all_ subs,
                        // suspending the upstream until all children are satisfied.
                    }
                }
                trace { "${this@SimpleInlineMulticaster} saw EOF, closing subs" }
                this@SimpleInlineMulticaster.subs = null
            }
            finally {
                subs.forEach { it.close() }
                trace { "all subs of ${this@SimpleInlineMulticaster} closed" }
            }
        }
    }

    fun openSubscription(): ReceiveChannel<T> {
        val subs = subs

        //debugging purposes only, require thread-safe formalisms to use actual ClosedChannelException.
        if(subs == null) throw IllegalStateException(ClosedChannelException())

        val subscription = object: RendezvousChannel<T>() {
            override fun toString() = "sub-$source"
        }

        subs += subscription
        trace { "opened $subscription" }

        return subscription
    }

    // suspends until source is empty and all elements have been dispatched to all subscribers.
    // key functional difference here vs BroadcastChannel.
    suspend fun join(): Unit {
        trace { "$this.join()..." }
        sourceJob.join()
        trace { "$this.join() completed" }
    }

    override fun toString() = "caster-$source"
}