package groostav.kotlinx.exec

import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers.Unconfined
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.RENDEZVOUS
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.consumeEach
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

// the express purpose of this object is to block on send,
// adhere to all back-pressure provided by any of the subscribers!
// in this way we pass on any problems back up to source!
class SimpleInlineMulticaster<T>(val name: String) {

    constructor(): this("anonymous${counter.getAndIncrement()}")

    sealed class State<T> {
        class Registration<T>(val subs: List<Channel<T>> = emptyList()): State<T>() {
            override fun toString() = "Registration"
        }
        class Running<T>(val subs: List<Channel<T>> = emptyList(), val src: ReceiveChannel<T>): State<T>() {
            override fun toString() = "Running{$src}"
        }
        class Closed<T>(): State<T>() {
            override fun toString() = "Closed"
        }
    }

    private val state: AtomicReference<State<T>> = AtomicReference(State.Registration())
    private val sourceJob = CompletableDeferred<Unit>()

    init {
        trace { "instanced $this" }
    }

    fun sinkFrom(source: ReceiveChannel<T>): Job {

        val newState = state.updateAndGet {
            when(it){
                is State.Registration -> State.Running(it.subs, source)
                is State.Running -> throw IllegalStateException("already started")
                is State.Closed -> throw IllegalStateException("already started")
            }
        }

        if (newState !is State.Running) {
            throw IllegalStateException("can only start syndicating once")
        }

        trace { "publishing src=$source to $this, locked-in subs: ${newState.subs.joinToString("\n\t", "\n\t")}" }

        return GlobalScope.launch(Unconfined + CoroutineName(this@SimpleInlineMulticaster.toString())) {
            try {
                source.consumeEach { next ->
                    // note: even if we have zero subs, we still want to read to completion
                    // this is because source is likely a rendezvous channel, and thus we need to block on it.
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
            for (it in previous.subs) { it.close() }
            sourceJob.complete(Unit)
            trace { "all subs of ${this@SimpleInlineMulticaster} closed" }
        }
    }

    fun openSubscription(description: String? = null): ReceiveChannel<T> {

        val registered = state.updateAndGet {
            when(it){
                is State.Registration<T> -> {

                    val subscription = newSubscription(it, description)

                    State.Registration(it.subs + subscription)
                }
                is State.Running -> throw IllegalStateException("state = $it")
                is State.Closed -> throw IllegalStateException("state = $it")
            }
        }

        registered as? State.Registration<T> ?: throw IllegalStateException("state = $registered")

        val subscription = registered.subs.last()
        trace { "opened $subscription from ${this@SimpleInlineMulticaster}" }
        return subscription
    }

    private fun newSubscription(registration: State.Registration<T>, description: String?): Channel<T> {

        val subSuffix = if(description != null) "[$description]" else ""
        val resultActual = Channel<T>(RENDEZVOUS)

        return object: Channel<T> by resultActual {
            val id = registration.subs.size+1
            override fun toString() = "sub$id$subSuffix-$name[$resultActual]"
        }
    }

    // suspends until source is empty and all elements have been dispatched to all subscribers.
    // key functional difference here vs BroadcastChannel.
    fun asJob(): Job = sourceJob

    override fun toString() = "caster-$name{${state.get()}}"

    companion object {
        private val counter = AtomicInteger(1)
    }
}