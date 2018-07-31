package groostav.kotlinx.exec

import kotlinx.coroutines.experimental.launch
import java.util.concurrent.atomic.AtomicReference

internal class ThreadBlockingResult(val jvmProcess: java.lang.Process): ProcessFacade {

    private val handlers: AtomicReference<State> = AtomicReference(State.Uninitialized)

    override fun addCompletionHandle() = Supported { handler: ResultHandler ->

        val previousState = handlers.getAndUpdate { state ->
            when(state) {
                State.Uninitialized -> State.Waiting(listOf(handler))
                is State.Waiting -> State.Waiting(state.handlers + handler) //would really really like kotlinx.immutable.collections :(
                is State.Finished -> state
            }
        }

        when(previousState){
            State.Uninitialized -> {
                // we're the first to request a result value, so we need to initialize the blocking thread
                launch(blockableThread) {
                    val result = jvmProcess.waitFor()
                    fireCompletion(result)
                }
            }
            is State.Waiting -> {
                // noop, our handler is registered, we dont need to do any cleanup.
            }
            is State.Finished -> {
                // already done, so just fire the handler right now
                handler(previousState.result)
            }
        }

        Unit
    }

    private fun fireCompletion(result: Int){
        val previousState = handlers.getAndUpdate { state ->
            when(state){
                State.Uninitialized -> throw IllegalStateException()
                is State.Waiting -> State.Finished(result)
                is State.Finished -> state
            }
        }

        if(previousState is State.Waiting){
            previousState.handlers.forEach { handler -> handler.invoke(result) }
        }
    }

    private sealed class State {
        object Uninitialized: State()
        class Waiting(val handlers: List<ResultHandler>): State()
        class Finished(val result: Int): State()
    }
}
