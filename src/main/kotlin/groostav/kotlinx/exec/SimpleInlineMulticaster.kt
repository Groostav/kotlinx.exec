package groostav.kotlinx.exec

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.ReceiveChannel
import kotlinx.coroutines.experimental.channels.RendezvousChannel
import kotlinx.coroutines.experimental.channels.consumeEach
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

// the express purpose of this object is to block on send,
// adhere to all back-pressure provided by any of the subscribers!
// in this way we pass on any problems back up to source!
class SimpleInlineMulticaster<T>(val name: String) {

    sealed class State<T> {
        data class Registration<T>(val subs: List<RendezvousChannel<T>> = emptyList()): State<T>()
        data class Running<T>(val subs: List<RendezvousChannel<T>> = emptyList()): State<T>()
        class Closed<T>(): State<T>()
    }

    private val state: AtomicReference<State<T>> = AtomicReference(State.Registration())
    private var source: ReceiveChannel<T>? = null
    private val sourceJob = CompletableDeferred<Unit>()
    private val subId = AtomicInteger(0)

    init {
        trace { "instanced $this" }
    }

    fun start(source: ReceiveChannel<T>) {

        val newState = state.updateAndGet {
            when(it){
                is State.Registration -> State.Running(it.subs)
                is State.Running -> throw IllegalStateException("already started")
                is State.Closed -> throw IllegalStateException("already started")
            }
        }

        if(newState is State.Running){

            this.source = source
            trace { "publishing src=$source to $this, locked-in subs: ${newState.subs.joinToString()}" }

            launch(Unconfined) {
                try {
                    source.consumeEach { next ->
                        for (sub in newState.subs) {
                            sub.send(next)
                            // apply back-pressure from _all_ subs,
                            // suspending the upstream until all children are satisfied.
                        }
                    }
                }
                finally {
                    shutdown()
                }
            }

        }
    }

    private fun shutdown(){
        val previous = state.getAndUpdate {
            when(it){
                is State.Registration -> throw IllegalStateException()
                is State.Running -> State.Closed()
                is State.Closed -> it
            }
        }

        if(previous is State.Running){
            trace { "${this@SimpleInlineMulticaster} saw EOF, closing subs" }
            sourceJob.complete(Unit)
            for (it in previous.subs) {
                it.close()
                val x = 4;
            }
            trace { "all subs of ${this@SimpleInlineMulticaster} closed" }
        }
    }

    fun openSubscription(): ReceiveChannel<T> {

        val registered = state.updateAndGet {
            when(it){
                is State.Registration<T> -> {

                    val subscription = object: RendezvousChannel<T>() {
                        val id = it.subs.size+1
                        override fun toString() = "sub$id-$name"
                    }

                    State.Registration(it.subs + subscription)
                }
                is State.Running -> throw IllegalStateException()
                is State.Closed -> throw IllegalStateException()
            }
        }

        if(registered is State.Registration<T>){
            val subscription = registered.subs.last()
            trace { "opened $subscription from ${this@SimpleInlineMulticaster}" }
            return subscription
        }
        else {
            return TODO()
        }
    }

    // suspends until source is empty and all elements have been dispatched to all subscribers.
    // key functional difference here vs BroadcastChannel.
    suspend fun join(): Unit {
        sourceJob.join()
        trace { "$this.join() completed" }
    }

    override fun toString() = "caster-$name"
}