package groostav.kotlinx.exec

import java.util.concurrent.atomic.AtomicReference

typealias Handler<T> = (T) -> Unit

class EventSource<T> {

    private val handlers: AtomicReference<State<T>> = AtomicReference(State.Uninitialized)

    fun register(handler: Handler<T>) {
        handlers.getAndUpdate { state ->
            when (state) {
                is State.Uninitialized -> State.Waiting(listOf(handler))
                is State.Waiting<T> -> State.Waiting(state.handlers + handler)
                is State.Running<T> -> throw IllegalStateException("already running")
                is State.Finished -> throw IllegalStateException("already finished")
            }
        }
    }

    fun fireEvent(event: T){
        val handlers = handlers.getAndUpdate { state ->
            when(state){
                is State.Uninitialized -> throw IllegalStateException("no subs")
                is State.Waiting -> State.Running(state.handlers, event)
                is State.Running -> state.copy(lastValue = event)
                is State.Finished -> throw IllegalStateException("closed")
            }
        }
    }

    fun close(){
        handlers.set(State.Finished)
    }

    private sealed class State<out T> {
        object Uninitialized: State<Nothing>()
        data class Waiting<T>(val handlers: List<Handler<T>>): State<T>()
        data class Running<T>(val handlers: List<Handler<T>>, val lastValue: T): State<T>()
        object Finished: State<Nothing>()
    }
}
